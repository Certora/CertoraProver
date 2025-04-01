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

import analysis.storage.SimpleTransientStorageAnalysis
import scene.IScene
import spec.CVL
import spec.cvlast.*
import spec.rules.*

class RuleFilterWithScene(val scene: IScene) {

    /**
     * This methods creates a copy of [cvl] and filters out rules that shouldn't be submitted to verification.
     * This allows to filter out rules that couldn't be filtered out earlier in the pipeline, for instance, during CVL compilation
     * or parsing, but only when the entire [scene] is available. This applies for instance for the filter of the induction
     * step for transient storage which will be filtered out dependent on the [scene] using transient storage or not.
     */
    fun copyFiltered(cvl: CVL): CVL{
        return cvl.copy(rules = cvl.rules.mapNotNull { copyFiltered(it) })
    }

    private fun includeRule(rule: ICVLRule): Boolean{
        if((rule as? CVLSingleRule)?.ruleType is SpecType.Single.InvariantCheck.TransientStorageStep){
            //The induction step for an invariant will only be executed if there is a usage of transient storage in the scene.
            return SimpleTransientStorageAnalysis.usesTransientStorage(scene)
        }
        return true
    }

    private fun copyFiltered(rule: ICVLRule): ICVLRule? {
        if(!includeRule(rule)) {return null}
        return when(rule){
            is GroupRule -> rule.copy(rules = rule.rules.mapNotNull { copyFiltered(it) })
            is CVLSingleRule,
            is AssertRule,
            is StaticRule -> rule
        }
    }
}
