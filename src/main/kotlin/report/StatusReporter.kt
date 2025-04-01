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

package report

import config.Config
import json.BasicJsonifier
import log.ArtifactManagerFactory
import log.Logger
import log.LoggerTypes
import rules.RuleCheckResult
import scene.IScene
import spec.rules.GroupRule
import spec.rules.IRule
import spec.cvlast.SpecType
import utils.ArtifactFileUtils
import utils.TimePing

private val logger = Logger(LoggerTypes.COMMON)

object StatusReporter : OutputReporter {
    /*
        For rule filtering e.g. with -rule
     */
    interface Counted {
        val isCounted: Boolean
    }

    data class RegisteredRule(
            val rule: String,
            val ruleType: SpecType,
            val parentCVLDeclarationId: String?,
            override val isCounted: Boolean
    ): Counted

    private val conf = Config.OutputProgressUpdater

    // will map a registered rule to how many queries were answered already
    private val registeredRules = mutableSetOf<RegisteredRule>()
    private val handledRules = mutableSetOf<RegisteredRule>()

    /** We first register (via [register]) all rules, and then add results for them (via [addResults]).
     * The "freeze" happens after the registration step, no more rules may be registered after that. */
    private var ruleListFrozen: Boolean = false
        get() = field
        set(b: Boolean) = if (field) {
            throw IllegalArgumentException("Can't set frozen field after freeze")
        } else {
            field = b
        }

    fun freeze() = synchronized(this) {
        ruleListFrozen = true
        ArtifactManagerFactory().registerArtifact(conf.get())
        // start the progress reports
        TimePing.registerReport(this::progress)
    }

    private fun getRegistered(rule: IRule) = registeredRules.find {
        it.rule == rule.declarationId &&
        it.parentCVLDeclarationId == rule.parentIdentifier?.displayName &&
        it.ruleType == rule.ruleType
    }

    private fun isRegistered(rule: IRule) = getRegistered(rule) != null

    /* find a registered rule by name */
    fun findRuleByName(name: String) = registeredRules.find { it.rule == name }

    /* returns registered rule */
    fun register(rule: IRule, isCounted: Boolean = true): RegisteredRule? = synchronized(this) {
        if (ruleListFrozen) {
            logger.error("Can't register rule $rule after freeze")
            return null
        }

        val registeredRule = registerInternal(rule, isCounted)
        if (rule is GroupRule) {
            rule.rules.forEach { subRule ->
                register(subRule, isCounted)
            }
        }
        registeredRule
    }

    fun registerSubrule(rule: IRule): RegisteredRule = synchronized(this) {
        // allow even if frozen - hopefully we don't add cases here ever, and get rid of this need as well
        registerInternal(rule, true)
    }

    private fun registerInternal(rule: IRule, isCounted: Boolean): RegisteredRule {
        if (isRegistered(rule)) {
            logger.info { "Registers rule $rule again" }
            return getRegistered(rule)!!
        }

        val registeredRule = RegisteredRule(rule.declarationId, rule.ruleType, rule.parentIdentifier?.displayName, isCounted)
        registeredRules.add(
            registeredRule
        )
        return registeredRule
    }

    override fun getOutFile(): String {
        return conf.get()
    }

    private fun updateSingle(registeredRule: RegisteredRule) {
        handledRules.add(registeredRule)
    }

    private fun updateMulti(registeredRule: RegisteredRule, multiResults: RuleCheckResult.Multi) {
        handledRules.add(registeredRule)
        multiResults.results.forEach {
            addResults(it)
        }
    }

    override fun signalStart(rule: IRule) {}

    override fun resultFilter(result: RuleCheckResult): Boolean = true

    override fun addResults(results: RuleCheckResult) {
        synchronized (this) {
            if (!ruleListFrozen) {
                logger.error("Can't update results of rule before registering all of them and freezing")
                return
            }
            val rule = results.rule
            val registeredRule = getRegistered(rule)
            if (registeredRule == null) {
                logger.error("Did not register rule $rule") // should throw exception? TODO
                return
            }

            when (results) {
                is RuleCheckResult.Single -> updateSingle(registeredRule)
                is RuleCheckResult.Multi -> updateMulti(registeredRule, results)
                is RuleCheckResult.Skipped -> updateSingle(registeredRule)
                is RuleCheckResult.Error -> updateSingle(registeredRule)
            }
        }
    }

    override fun toFile(scene: IScene) {
        writeFile()
        // assert that expected == how many were checked
        val nonMatching = mutableListOf<RegisteredRule>()
        synchronized(registeredRules) {
            // Two threads may invoke toFile() and iterate over [registeredRules] concurrently
            registeredRules.forEach { registeredRule ->
                if (registeredRule !in handledRules && registeredRule.isCounted) {
                    nonMatching.add(registeredRule)
                }
            }
        }

        assert(nonMatching.isEmpty()) {
            "The following rules were registered but did not get a result for them: $nonMatching"
        }
    }

    private fun writeFile() {
        val status = mutableMapOf<RegisteredRule,Boolean>()
        synchronized(registeredRules) {
            // Two threads may invoke writeFile() and iterate over [registeredRules] concurrently
            registeredRules.forEach {
                status[it] = it in handledRules
            }
        }
        val jsond = BasicJsonifier.mapToJson(status)
        ArtifactManagerFactory().useArtifact(getOutFile()) { handle ->
            ArtifactFileUtils.getWriterForFile(handle, overwrite = true).use { i ->
                i.append(jsond)
            }
        }
    }

    override fun hotUpdate(scene: IScene) {
        // always under lock
        writeFile()
    }

    private fun progress(): String {
        synchronized(this) {
            val counted = registeredRules.filter { it.isCounted }
            val topLevel = counted.filter { it.parentCVLDeclarationId == null }
            val completeTopLevelCount = topLevel.count { it in handledRules }
            val incompleteCount = counted.count { it !in handledRules }

            val topLevelProgress = when {
                topLevel.size == 0 -> 0
                else -> 100 * completeTopLevelCount / topLevel.size
            }
            return "Processed $completeTopLevelCount/${topLevel.size} ($topLevelProgress%) rules. " +
                "${handledRules.size} tasks complete, $incompleteCount pending."
        }
    }

}
