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

package sbf.tac

import analysis.opt.DiamondSimplifier.registerMergeableAnnot
import datastructures.stdcollections.*
import sbf.cfg.SbfInstruction
import utils.*
import vc.data.*

private val DEBUG_INLINED_FUNC_START_FROM_ANNOT = tac.MetaKey<SbfInlinedFuncStartAnnotation>("debug.sbf.function_start").registerMergeableAnnot()
private val DEBUG_INLINED_FUNC_END_FROM_ANNOT = tac.MetaKey<SbfInlinedFuncEndAnnotation>("debug.sbf.function_end").registerMergeableAnnot()
val DEBUG_INLINED_FUNC_START = tac.MetaKey<String>("debug.sbf.function_start").registerMergeableAnnot()
val DEBUG_INLINED_FUNC_END = tac.MetaKey<String>("debug.sbf.function_end").registerMergeableAnnot()
val DEBUG_UNREACHABLE_CODE = tac.MetaKey<String>("debug.sbf.unreachable").registerMergeableAnnot()
val DEBUG_EXTERNAL_CALL = tac.MetaKey<String>("debug.sbf.external_call").registerMergeableAnnot()
private val DEBUG_PTA_SPLIT_OR_MERGE = tac.MetaKey<DebugSnippet>("debug.pta_split_or_merge").registerMergeableAnnot()
private val UNSUPPORTED = tac.MetaKey<DebugSnippet>("debug.unsupported").registerMergeableAnnot()

@KSerializable
private data class DebugSnippet(val msg: String, val symbols: List<TACSymbol.Var>)
    : AmbiSerializable, TransformableVarEntityWithSupport<DebugSnippet> {
    override val support: Set<TACSymbol.Var> get() = symbols.toSet()
    override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var) =
        DebugSnippet(msg = msg, symbols = symbols.map{f(it)})
}


/** This class annotates TAC to make easier debugging (only for devs) **/
object Debug {
    fun unreachable(inst: SbfInstruction)  =
        TACCmd.Simple.AnnotationCmd(TACCmd.Simple.AnnotationCmd.Annotation(DEBUG_UNREACHABLE_CODE, "$inst"))

    fun externalCall(fname: String): TACCmd.Simple =
        TACCmd.Simple.AnnotationCmd(TACCmd.Simple.AnnotationCmd.Annotation(DEBUG_EXTERNAL_CALL, fname))

    fun externalCall(inst: SbfInstruction.Call): TACCmd.Simple = externalCall(inst.name)

    fun satisfy(inst: SbfInstruction.Call): TACCmd.Simple = externalCall(inst)

    fun startFunction(name: String, msg: String = ""): TACCmd.Simple =
        TACCmd.Simple.AnnotationCmd(
            TACCmd.Simple.AnnotationCmd.Annotation(DEBUG_INLINED_FUNC_START, "$name$msg"))

    fun startFunction(annot: SbfInlinedFuncStartAnnotation): TACCmd.Simple =
        TACCmd.Simple.AnnotationCmd(
            TACCmd.Simple.AnnotationCmd.Annotation(DEBUG_INLINED_FUNC_START_FROM_ANNOT, annot))

    fun endFunction(name: String, msg: String = ""): TACCmd.Simple =
        TACCmd.Simple.AnnotationCmd(
            TACCmd.Simple.AnnotationCmd.Annotation(DEBUG_INLINED_FUNC_END, "$name$msg"))

    fun endFunction(annot: SbfInlinedFuncEndAnnotation): TACCmd.Simple =
        TACCmd.Simple.AnnotationCmd(
            TACCmd.Simple.AnnotationCmd.Annotation(DEBUG_INLINED_FUNC_END_FROM_ANNOT, annot))

    fun ptaSplitOrMerge(msg: String, symbols: List<TACSymbol.Var>): TACCmd.Simple =
        TACCmd.Simple.AnnotationCmd(
            TACCmd.Simple.AnnotationCmd.Annotation(DEBUG_PTA_SPLIT_OR_MERGE, DebugSnippet(msg, symbols)))

    fun unsupported(msg: String, symbols: List<TACSymbol.Var>): TACCmd.Simple =
        TACCmd.Simple.AnnotationCmd(
            TACCmd.Simple.AnnotationCmd.Annotation(UNSUPPORTED, DebugSnippet(msg, symbols)))
}
