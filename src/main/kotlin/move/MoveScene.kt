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
import java.nio.file.*
import java.util.stream.*
import kotlin.streams.*
import log.*
import tac.*
import utils.*
import vc.data.*

private val logger = Logger(LoggerTypes.MOVE)

/**
    Loads all move modules acessible to the Prover, including the spec module.
 */
class MoveScene(
    val modulePath: Path,
    val optimize: Boolean = true,
) {
    private val moduleMap: Map<MoveModuleName, MoveModule> by lazy {
        Files.walk(modulePath, FileVisitOption.FOLLOW_LINKS)
        .filter { @Suppress("ForbiddenMethodCall") it.toString().endsWith(MoveModule.FILE_EXTENSION) }
        .parallel()
        .map { MoveModule(it) }
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

    val rules by lazy {
        cvlmManifest.selectedRules.flatMap { (funcName, ruleType) ->
            val def = maybeDefinition(funcName)
                ?: error("No definition found for rule function $funcName")
            // Instantiate the function, using the `Nothing` type for all type arguments.
            val func = MoveFunction(
                def.function,
                typeArguments = def.function.typeParameters.map { nothingType }
            )
            MoveToTAC.compileRule(func, ruleType, this)
        }.also {
            if (it.isEmpty()) {
                throw CertoraException(
                    CertoraErrorType.CVL,
                    "No CVLM rules found. Check that the rules are defined in the module manifest(s)."
                )
            }
        }
    }

    context(SummarizationContext)
    fun summarize(call: MoveCall): MoveBlocks? {
        return CvlmApi.summarize(call)
            ?: cvlmManifest.summarize(call)
    }

    fun maybeShadowType(type: MoveType.Struct) = CvlmApi.maybeShadowType(type) ?: cvlmManifest.maybeShadowType(type)

    fun definition(func: MoveModule.FunctionHandle) = module(func.name.module).definition(func)
    fun definition(type: MoveModule.DatatypeHandle) = module(type.definingModule).definition(type)

    fun maybeDefinition(func: MoveFunctionName) = moduleMap[func.module]?.maybeDefinition(func)
    fun maybeDefinition(type: MoveStructName) = moduleMap[type.module]?.maybeDefinition(type)

    val nothingType by lazy {
        val name = MoveStructName(MoveModuleName(Config.CvlmAddress.get(), "nothing"), "Nothing")
        val def = maybeDefinition(name) ?: error("No definition found for $name")
        def.structHandle.toMoveStructRaw() // "raw" to prevent shadowing
    }
}
