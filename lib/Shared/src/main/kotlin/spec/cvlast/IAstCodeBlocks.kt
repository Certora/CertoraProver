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

package spec.cvlast

import datastructures.stdcollections.*
import spec.rules.CVLSingleRule
import spec.rules.ICVLRule

interface IAstCodeBlocks {
    val rules: List<ICVLRule>
    val subs: List<CVLFunction>
    val invs: List<CVLInvariant>
    val ghosts: List<CVLGhostDeclaration>
    val definitions: List<CVLDefinition>
    val hooks: List<CVLHook>

    /**
     * @return A sequence of all [CVLCmd]s that exist anywhere in the spec( rule, cvl-function, hook body, etc.)
     */
    fun getAllBlocks(): Sequence<CVLCmd> {
        fun wrapExpWithApplyCmd(exp: CVLExp.UnresolvedApplyExp) =
            CVLCmd.Simple.Apply(exp.getRangeOrEmpty(), exp, exp.getScope())

        return (
            rules.flatMap { rule -> (rule as CVLSingleRule).block } +
                subs.flatMap { sub -> sub.block } +
                invs.flatMap { inv ->
                    inv.exp.subExprsOfType<CVLExp.UnresolvedApplyExp>().map(::wrapExpWithApplyCmd)
                } +
                hooks.flatMap { hook -> hook.block } +
                ghosts.flatMap<CVLGhostDeclaration, CVLCmd> { ghost ->
                    (ghost as? CVLGhostWithAxiomsAndOldCopy)?.axioms?.flatMap {
                        it.exp.subExprsOfType<CVLExp.UnresolvedApplyExp>().map(::wrapExpWithApplyCmd)
                    } ?: datastructures.stdcollections.listOf()
                } +
                definitions.flatMap { def ->
                    def.body.subExprsOfType<CVLExp.UnresolvedApplyExp>().map(::wrapExpWithApplyCmd)
                }
            ).asSequence()
    }
}
