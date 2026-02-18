/*
 *     The Certora Prover
 *     Copyright (C) 2025  Certora Ltd.
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, version 3 of the License.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package move

import analysis.*
import com.certora.collect.*
import config.*
import datastructures.*
import datastructures.stdcollections.*
import java.math.BigInteger
import java.nio.file.*
import java.util.concurrent.ConcurrentHashMap
import java.util.stream.*
import kotlin.streams.*
import log.*
import spec.cvlast.typechecker.CVLError
import spec.cvlast.validateRuleChoices
import spec.cvlast.validateSimpleMethodChoices
import tac.*
import utils.*
import utils.CollectingResult.Companion.bind
import utils.CollectingResult.Companion.lift
import vc.data.*

private val logger = Logger(LoggerTypes.MOVE)
private val loggerSetupHelpers = Logger(LoggerTypes.SETUP_HELPERS)


/**
    Loads all move modules acessible to the Prover, including the spec module.
 */
open class MoveScene(
    val modulePath: Path
) {
    private val moduleMap: Map<MoveModuleName, MoveModule> by lazy {
        Files.walk(modulePath, FileVisitOption.FOLLOW_LINKS)
        .filter { @Suppress("ForbiddenMethodCall") it.toString().endsWith(MoveModule.FILE_EXTENSION) }
        .parallel()
        .map { MoveModule(this, it) }
        .asSequence()
        .groupBy { it.moduleName }
        .mapValues { (name, modules) ->
            modules.singleOrNull() ?: error(
                "Multiple modules with the same name $name found in ${modules.joinToString(", ") { it.path.toString() }}"
            )
        }
        .also {
            logger.debug { "Loaded modules: ${it.keys.joinToString(", ")}" }
        }
    }

    val modules get() = moduleMap.keys
    fun module(name: MoveModuleName) = moduleMap[name] ?: error("No module found with name $name")

    val sourceContext by lazy { MoveSourceContext(this) }

    val cvlmManifest by lazy { CvlmManifest(this) }

    val allCvlmRules: List<CvlmRule> by lazy { CvlmRule.loadAll() }

    fun getSelectedCvlmRules(): CollectingResult<List<CvlmRule>, CVLError> {
        val allRuleNames = allCvlmRules.mapToSet { it.ruleName }
        return validateRuleChoices(allRuleNames).bind {
            val selectedRuleNames = Config.getRuleChoices(allRuleNames)

            val allTargetNames = allCvlmRules.flatMapToSet { it.parametricTargetNames }

            validateSimpleMethodChoices(allTargetNames).bind {
                val selectedTargetNames = Config.getSimpleMethodChoices(allTargetNames)

                allCvlmRules.filter {
                    it.ruleName in selectedRuleNames && selectedTargetNames.containsAll(it.parametricTargetNames)
                }.lift()
            }
        }
    }

    fun targetFunctions(module: MoveModuleName) = cvlmManifest.targetFunctions(module)

    val cvlmApi by lazy { CvlmApi(this) }

    context(SummarizationContext)
    fun summarize(call: MoveCall): MoveBlocks? {
        return cvlmApi.summarize(call)
            ?: cvlmManifest.summarize(call)
    }

    fun maybeShadowType(type: MoveType.Struct) = cvlmApi.maybeShadowType(type) ?: cvlmManifest.maybeShadowType(type)

    fun definition(func: MoveModule.FunctionHandle) = module(func.name.module).definition(func)
    fun definition(type: MoveModule.DatatypeHandle) = module(type.definingModule).definition(type)

    fun maybeDefinition(func: MoveFunctionName) = moduleMap[func.module]?.maybeDefinition(func)
    fun maybeDefinition(type: MoveDatatypeName) = moduleMap[type.module]?.maybeDefinition(type)

    private val buildInfos: List<MoveBuildInfo> by lazy {
        Files.walk(modulePath, FileVisitOption.FOLLOW_LINKS)
        .filter { it.endsWith("BuildInfo.yaml") && Files.isRegularFile(it) }
        .map { MoveBuildInfo.parse(it) }
        .toList()
    }

    open protected val addressAliases: Map<BigInteger, List<String>> by lazy {
        buildInfos.flatMap {
            it.compiledPackageInfo.addressAliasInstantiation.entries.map { (alias, address) ->
                address to alias
            }
        }.groupBy({ it.first }, { it.second })
    }

    fun maybeAlias(address: BigInteger) = addressAliases[address]?.singleOrNull()


    private val functionsChecked = ConcurrentHashMap.newKeySet<MoveFunctionName>()

    /**
        Does one-time "setup helper" checks of each function body that is used by a rule.
     */
    fun doSetupHelperFunctionBodyCheck(funcName: MoveFunctionName, code: MoveModule.CodeUnit) {
        if (functionsChecked.add(funcName)) {
            // Check for functions that simply abort.  These are often used as placeholders for dependencies, and may
            // need to be summarized.
            if (code.instructions.all { it is MoveModule.Instruction.Abort || it is MoveModule.Instruction.LdU64 }) {
                loggerSetupHelpers.warn {
                    "Function $funcName simply aborts.  It may be deprecated, or a stubbed-out dependency."
                }
            }
        }
    }
}
