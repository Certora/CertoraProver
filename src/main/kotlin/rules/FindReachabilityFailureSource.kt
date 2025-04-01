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

package rules

import config.Config
import kotlinx.coroutines.*
import log.Logger
import log.LoggerTypes
import scene.ISceneIdentifiers
import spec.rules.IRule
import spec.cvlast.SpecType
import vc.FullProgramReachabilityResult
import vc.ReachabilityFailureSourceFinder
import vc.data.CoreTACProgram

private val logger = Logger(LoggerTypes.COMMON)

/**
 * A custom transformation function for manipulating the result of a rule.
 * The transformations run dependent on the ruleType - when adding a new transform be sure to add the necessary branch
 * to the 'when' statement in [doTransforms]
 */
object FindReachabilityFailureSource {
    fun shouldCheck(rule: IRule): Boolean =
        rule.ruleType is SpecType.Single.GeneratedFromBasicRule.SanityRule.VacuityCheck && Config.FindReachabilityFailurePoint.get()

    private suspend fun find(tacProgram: CoreTACProgram, scene: ISceneIdentifiers) =
        ReachabilityFailureSourceFinder(
            tacProgram,
            scene
        ).let {
            try {
                it.runReachabilityFailureSourceFinder()
            } catch(e: CancellationException) {
                throw e
            } catch(@Suppress("TooGenericExceptionCaught") e: Exception) {
                logger.error(
                    e,
                    "Had an exception while running Reachability Failure Source Finder ${tacProgram.name}",
                )
                null
            }
        }

    /**
     * Performs a (possibly empty) set of 'transformations' on the given [result], dependent on the rule type
     * If no transformation was performed - returns null
     */
    suspend fun find(
        shouldCheckRule: Boolean,
        tacProgram: CoreTACProgram,
        scene: ISceneIdentifiers,
    ): FullProgramReachabilityResult? = if (shouldCheckRule) {
        find(tacProgram, scene)
    } else {
        null
    }
}

