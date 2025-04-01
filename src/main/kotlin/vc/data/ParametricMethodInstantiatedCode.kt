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

package vc.data

import analysis.CommandWithRequiredDecls
import rules.CheckableTAC
import scene.IScene
import spec.CVLCompiler
import spec.rules.CVLSingleRule
import spec.rules.SingleRuleGenerationMeta
import tac.NBId
import vc.data.ParametricInstantiation.Companion.merge
import vc.data.ParametricInstantiation.Companion.mergeMany


object ParametricMethodInstantiatedCode {

    fun mergeProgs(
        p1: ParametricInstantiation<CVLTACProgram>,
        p2: ParametricInstantiation<CVLTACProgram>
    ): ParametricInstantiation<CVLTACProgram> =
        merge(listOf(p1, p2), "Expression Compilation")

    /**
     * Matches parametric method instantiations from [l] and [r] (no instantiation for a given method from one set
     * will match all instantiations for that method in the other set) and combines each match using [mergeTwoCodes]
     *
     */
    private fun <T: TACProgram<*>> mergeTwoInstantiations(
        l: ParametricInstantiation<T>,
        r: ParametricInstantiation<T>,
        mergeTwoCodes: (lCode: T, rCode: T) -> T
    ): ParametricInstantiation<T> = l.merge(r, mergeTwoCodes)

    // currently needed for if-then-else
    private fun <T: TACProgram<*>> mergeThreeInstantiations(
        c1: ParametricInstantiation<T>,
        c2: ParametricInstantiation<T>,
        c3: ParametricInstantiation<T>,
        mergeThreeCodes: (code1: T, code2: T, code3: T) -> T
    ): ParametricInstantiation<T> =
        c1.merge(c2, c3, mergeThreeCodes)

    /**
     * Generates CFG which branches based on the result of [cond], going to [thenC] in the true case and [elseC]
     * in the false case. This will propagate instantiations in [thenC] and [elseC] into the resulting program
     */
    fun mergeIf(
        cond: CVLTACProgram,
        jumpi: TACCmd.Simple.JumpiCmd,
        thenC: ParametricInstantiation<CVLTACProgram>,
        elseC: ParametricInstantiation<CVLTACProgram>
    ): ParametricInstantiation<CVLTACProgram> =
        mergeTwoInstantiations(thenC, elseC) { thenProgram, elseProgram ->
            mergeIfCodes(
                cond,
                jumpi,
                thenProgram,
                elseProgram
            )
        }

    /**
     * Generates CFG which branches based on the result of [cond], going to [thenC] in the true case and [elseC]
     * in the false case. This will propagate instantiations in [thenC] and [elseC] into the resulting program
     */
    fun mergeIf(
        cond: ParametricInstantiation<CVLTACProgram>,
        jumpi: TACCmd.Simple.JumpiCmd,
        thenC: ParametricInstantiation<CVLTACProgram>,
        elseC: ParametricInstantiation<CVLTACProgram>
    ): ParametricInstantiation<CVLTACProgram> =
        mergeThreeInstantiations(cond, thenC, elseC) { condProgram, thenProgram, elseProgram ->
            mergeIfCodes(
                condProgram,
                jumpi,
                thenProgram,
                elseProgram
            )
        }

    /**
     * Merges each successive element of [toMerge] as program successors (routing leaves of each element to the
     * root of the following element), ensuring that instantiations for each element of [toMerge] are propagated
     * through the whole program
     */
    fun merge(toMerge: List<ParametricInstantiation<CVLTACProgram>>, name: String): ParametricInstantiation<CVLTACProgram> =
        mergeMany<CVLTACProgram>(toMerge, id = CVLTACProgram.empty(name), combiner = ::mergeCodes).transformCode { it.copy(name = name) }

    /**
     * Background: each rule in a [ParametricMethodInstantiatedCode] object has an associated [MethodParameterInstantiation] mapping,
     * which tells how the parameter methods in have been instantiated in each rule instance ([CVLTACProgram]).
     * Therefore, we don't want any havocs of the method variable - it's basically a constant.
     * This function removes any such havocs.
     * NB: we assume the only havoc of method variables is one generated during param/variable setup, this is enforced
     *      by the CVL Type Checker which ensures that there are no CVL havocs of method variables
     */
    fun ParametricInstantiation<CVLTACProgram>.removeMethodParamHavocs(): ParametricInstantiation<CVLTACProgram> =
        this.transform { (tacProgram, methodParameterInstantiation) ->
            val newProg = tacProgram.patching { p ->
                tacProgram.ltacStream().filter { lc ->
                    // methodParameterInstantiation contains all the method parameters which have been instantiated
                    // because of an invocation/apply of the method parameter. This means that the method parameter
                    // is no longer symbolic, but a constant in each instance of this program. Therefore we remove
                    // the havoc which is added by default (and as noted above, assume there were no user havocs of
                    // the method variable) and instead populate the method variable with all the meta information
                    // about the particular instantiation this transform lambda body corresponds to
                    lc.cmd is TACCmd.Simple.AssigningCmd.AssignHavocCmd &&
                            lc.cmd.lhs.namePrefix in methodParameterInstantiation.keys
                        .map { methodName -> methodName.namePrefix }
                }.forEach { lc ->
                    p.replace(lc.ptr) { _ -> listOf() }
                }
            }

            ParametricInstantiation.DataWithMethodParameterInstantiation(newProg, methodParameterInstantiation)
        }

    fun ParametricInstantiation<CVLTACProgram>.addSink(cmds: CommandWithRequiredDecls<TACCmd.Spec>, env: CVLCompiler.CallIdContext): ParametricInstantiation<CVLTACProgram> =
        this.transformCode { c -> c.addSink(cmds, env).first }

    fun ParametricInstantiation<CVLTACProgram>.toCheckableTACs(
        scene: IScene,
        sanity: SingleRuleGenerationMeta.Sanity,
        subrule: CVLSingleRule
    ): List<CheckableTAC> {
        val compiler = CVLToSimpleCompiler(scene)
        return withMethodParamInsts.map { CheckableTAC(compiler.compile(it.tacCode), it.instantiation, sanity, subrule) }
    }
}

fun <T: TACProgram<*>>  ParametricInstantiation<T>.getHead(): NBId =
    this.withMethodParamInsts.map { it.tacCode.getStartingBlock() }.toSet().let {
        check(it.size == 1) { "All instances must share the same head, but got $it" }
        it.first()
    }
