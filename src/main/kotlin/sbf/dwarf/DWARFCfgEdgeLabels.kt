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
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package sbf.dwarf

import report.calltrace.printer.StackEntry
import sbf.cfg.SbfInstruction
import utils.*
import vc.data.SnippetCmd
import vc.data.TACCmd
import datastructures.stdcollections.*
import dwarf.DWARFOperation
import dwarf.DWARFOperation.*
import dwarf.DwarfMethod
import dwarf.Offset
import sbf.SolanaConfig
import sbf.cfg.LocatedSbfInstruction
import sbf.cfg.SBF_STACK_START
import sbf.cfg.Value
import sbf.disassembler.SbfRegister
import sbf.domains.*
import sbf.tac.TACDebugView

/**
 * A list of labels that will be added to a CFG edge that will be translated to TAC Annotation Commands
 * in [sbf.tac.SbfCFGToTAC].
 */
sealed interface DWARFCfgEdgeLabel {

    /**
     * Needed to associate the variables to the right level on the call stack.
     *
     * A non-persistent CfgEdgeAnnotation will be translated to a debug step that will be invisible to the user.
     * Example: Say a statement `curr` closes function scope fn_2 and - due to optimizations - the next statement opens function scope fn_2,
     * but the statement curr assigns a variable bar that lives in the scope of fn_1 (that calls fn_2).
     * For the debugger to track variable bar, in scope fn_1. it is important that the assignments occurs in fn_1, i.e. fn_2 must be closed,
     * then the assignment must occur before the function scope to fn_2 is opened again. I.e. the sequence is
     * )_fn_2 -> assign bar -> (_fn2, however we don't want to see this behaviour in the debugger as it leads to jumpy behaviour.
     * Therefore, the function scope closing and opening will _not_ be persisted.
     */
    fun asPersistent(persistent: Boolean): DWARFCfgEdgeLabel

    fun toAnnotations(locInst: LocatedSbfInstruction, dwarfView: TACDebugView): List<TACCmd.Simple.AnnotationCmd>

    fun usedRegisters(): Set<Value.Reg>


    /**
     * A label that marks the end of a scope - i.e. matches either [SubProgramStart] or [CallSiteWithSources]
     */
    sealed interface ScopeEnd : DWARFCfgEdgeLabel {
        data class FunctionEnds(val depth: ULong, val allMatchingPushesInSources: Boolean, val persist: Boolean = true) : ScopeEnd {
            override fun toString(): String {
                return ")_fn_$depth (${persist})"
            }

            override fun toAnnotations(locInst: LocatedSbfInstruction, dwarfView: TACDebugView): List<TACCmd.Simple.AnnotationCmd> {
                return if (allMatchingPushesInSources) {
                    listOf(SnippetCmd.SolanaSnippetCmd.ExplicitDebugPopAction(persist).toAnnotation())
                } else {
                    emptyList()
                }
            }

            override fun asPersistent(persistent: Boolean) = this.copy(persist = persistent)

            override fun usedRegisters(): Set<Value.Reg> {
                return setOf()
            }
        }
    }

    /**
     * Marks the start of a scope, that is either an entire subprogram or a function that was inlined.
     */
    sealed class ScopeStart(open val node: DwarfMethod) : DWARFCfgEdgeLabel {

        /**
         * Marks the start of a callee that was inlined by the compiler. For an inlined callee,
         * there is also a call site associated [callSiteRange] that marks the location where the callee was inlined.
         * [declRange] is the range in source code where the function is implemented (it doesn't highlight the entire
         * body of a function but just the first line, i.e., the method signature).
         */
        data class InlinedCallee(val functionName: String,
                                 val callSiteRange: Range.Range,
                                 val declRange: Range.Range,
                                 val persist: Boolean = true,
                                 override val node: DwarfMethod) : ScopeStart(node) {
            override fun toAnnotations(locInst: LocatedSbfInstruction, dwarfView: TACDebugView): List<TACCmd.Simple.AnnotationCmd> = listOfNotNull(
                if (persist && callSiteRange.isInSources()) {
                    SnippetCmd.ExplicitDebugStep(callSiteRange).toAnnotation()
                } else {
                    null
                },
                if (declRange.isInSources()) {
                    SnippetCmd.SolanaSnippetCmd.ExplicitDebugPushAction(StackEntry.SolanaFunction(functionName, declRange), persist).toAnnotation()
                } else {
                    null
                }
            )

            override fun asPersistent(persistent: Boolean) = this.copy(persist = persistent)
            override fun toString(): String {
                return "(_fn_${node.getDepth()}_${node.getMethodName()} (Inlined callee: call site at $callSiteRange, function body in source: $declRange) (${persist})"
            }

            override fun usedRegisters(): Set<Value.Reg> = setOf()
        }

        /**
         * Marks the start of a subprogram / also called subroutine in DWARF. [declRange]
         * is the range in source code where the function is implemented (it doesn't highlight the entire
         * body of a function but just the first line, i.e., the method signature).
         */
        data class SubProgramStart(val functionName: String,
                                   val declRange: Range.Range,
                                   val persist: Boolean = true,
                                   override val node: DwarfMethod) : ScopeStart(node) {
            override fun toString(): String {
                return "(_sp_${node.getDepth()} (function body in source: $declRange)"
            }

            override fun asPersistent(persistent: Boolean) = this.copy(persist = persistent)

            override fun toAnnotations(locInst: LocatedSbfInstruction, dwarfView: TACDebugView) = listOf(SnippetCmd.SolanaSnippetCmd.ExplicitDebugPushAction(StackEntry.SolanaFunction(functionName, declRange), persist).toAnnotation())

            override fun usedRegisters(): Set<Value.Reg> = setOf()
        }
    }

    /**
     * An explicit step that will be used to tell the debugger this is a point where the debugger should
     * stop next (these come from DWARF .debug_line information.)
     */
    data class DebugStep(val range: Range.Range) : DWARFCfgEdgeLabel {
        override fun toAnnotations(locInst: LocatedSbfInstruction, dwarfView: TACDebugView): List<TACCmd.Simple.AnnotationCmd> {
            return if (range.isInSources()) {
                listOf(SnippetCmd.ExplicitDebugStep(range).toAnnotation())
            } else {
                emptyList()
            }
        }

        override fun asPersistent(persistent: Boolean): DWARFCfgEdgeLabel {
            return this
        }

        override fun toString(): String {
            return "Source line info: $range"
        }

        override fun usedRegisters(): Set<Value.Reg> {
            return setOf()
        }
    }

    /**
     * When this edge label is inserted it means the variable contained in [variableInfo]
     * is live after the label.
     */
    data class VariableBecomingLive(val stackLevel: CallStackLevel, val variableInfo: SourceVariableDebugInformation, val persist: Boolean = true) : DWARFCfgEdgeLabel {
        private fun toAnnotations(displayPathExpressions: Map<Offset, DWARFExpression>): List<TACCmd.Simple.AnnotationCmd> {
            if (stackLevel.scopes.any { !it.getDeclRange().isInSources() } || variableInfo.variableType == null) {
                return emptyList()
            }
            return listOf(SnippetCmd.SolanaSnippetCmd.VariableBecomingLive(variableInfo.variableName, variableInfo.variableType, displayPathExpressions, persist = persist).toAnnotation())
        }

        override fun toAnnotations(locInst: LocatedSbfInstruction, dwarfView: TACDebugView): List<TACCmd.Simple.AnnotationCmd> {
            if (variableInfo.variableType == null) {
                return toAnnotations(mapOf(Offset(0UL) to DWARFExpression.StringValue("(No variable type given)")))
            }
            if (variableInfo.operations.isEmpty()) {
                // when the operations list (as given by gimli / DWARF debug information) is empty, it means the rust compiler / LLVM removed the variables during optimizations.
                return toAnnotations(mapOf(Offset(0UL) to DWARFExpression.StringValue("(variable optimized out)")))
            }


            val allParts = variableInfo.operations.splitToOffsets(variableInfo.variableType)
            val res = allParts.mapNotNull { (field, ops) ->
                ops.toExpression { registerAccess, asStackValue ->
                    val register = Value.Reg(SbfRegister.getByValue(registerAccess.register()))
                    if (asStackValue) {
                        if (registerAccess.register() == SbfRegister.R10_STACK_POINTER.value) {
                            if (stackLevel.frameBasePointer == null) {
                                return@toExpression DWARFExpression.StringValue("Cannot compute information relative to frame base without frame base pointer given.")
                            }
                            return@toExpression DWARFExpression.Constant(
                                SBF_STACK_START.toBigInteger() +
                                    SolanaConfig.StackFrameSize.get()
                                    + stackLevel.frameBasePointer.toBigInteger()
                                    + registerAccess.offset().toBigInteger())
                        }
                    }
                    when (registerAccess) {
                        is FrameOffset -> {
                            if (stackLevel.frameBasePointer == null) {
                                return@toExpression DWARFExpression.StringValue("Cannot compute information relative to frame base without frame base pointer given.")
                            }
                            val baseOffset = stackLevel.frameBasePointer + field.offset.toLong() + registerAccess.offset()
                            val variable = dwarfView.getStackTACVariable(locInst, register, PTAOffset(baseOffset))
                            variable?.let { tacVar -> DWARFExpression.ModelValue(tacVar) }
                                ?: DWARFExpression.StringValue("Cannot find TAC variable at offset $baseOffset from frame base.")
                        }

                        is Register -> DWARFExpression.ModelValue(dwarfView.getRegisterTACVariable(register))
                        is RegisterOffset -> DWARFExpression.BinaryExpr(
                            Plus,
                            DWARFExpression.ModelValue(dwarfView.getRegisterTACVariable(register)),
                            DWARFExpression.Constant(registerAccess.offset().toBigInteger()))
                    }
                }?.let { field to it }
            }

            return this.toAnnotations(res.toMap())
        }

        override fun asPersistent(persistent: Boolean) = this.copy(persist = persistent)
        override fun toString(): String {
            val postFix = if (variableInfo.operations.isEmpty()) {
                "has no debug information (optimized out)"
            } else {
                "stored in ${variableInfo.operations}"
            }
            return "Variable becoming life ${variableInfo.variableName} $postFix (${persist})"
        }

        override fun usedRegisters(): Set<Value.Reg> {
            return this.variableInfo.operations.mapNotNull { it.usedRegister() }.toSet()
        }

    }
    /**
     * When this edge label is inserted, there is a direct memory access at [instruction] that matches the DWARF operations in [operations]
     * The actual field that will be access is contained in [variableDebugInfo].
     *
     * (Note, only the formatting in [report.calltrace.formatter.SolanaCallTraceValueFormatter] will resolve which field was actually accessed).
     */
    data class DirectMemoryAccess(val stackLevel: CallStackLevel, val variableDebugInfo: SourceVariableDebugInformation, val offsetIntoStruct: Offset, val operations: List<DWARFOperation>, val instruction: SbfInstruction, val loadedValue: Value, val persist: Boolean = true) : DWARFCfgEdgeLabel {
        override fun toAnnotations(locInst: LocatedSbfInstruction, dwarfView: TACDebugView): List<TACCmd.Simple.AnnotationCmd> {
            if (stackLevel.scopes.any { !it.getDeclRange().isInSources() } || variableDebugInfo.variableType == null) {
                return emptyList()
            }
            val tacifiedExpr = this.operations.toExpression { _, _ ->
                //Handle offset
                //Handle asStackValue (second parameter)
                //Handle R10 STACK_POINTER
                val tacSymbol = DWARFExpression.ModelValue(dwarfView.getRegisterTACVariable(this.loadedValue as Value.Reg))
                tacSymbol
            } ?: return emptyList()

            return listOf(SnippetCmd.SolanaSnippetCmd.DirectMemoryAccess(variableDebugInfo.variableName, variableDebugInfo.variableType, offsetIntoStruct, tacifiedExpr, persist).toAnnotation())
        }

        override fun asPersistent(persistent: Boolean) = this.copy(persist = persistent)
        override fun toString(): String {
            return "Memory load on variable ${variableDebugInfo.variableName} at ($offsetIntoStruct) via ${variableDebugInfo.operations}  (${persist})"
        }

        override fun usedRegisters(): Set<Value.Reg> {
            return this.variableDebugInfo.operations.mapNotNull { it.usedRegister() }.toSet()
        }
    }

}


fun List<DWARFOperation>.toExpression(translateRegisterAccess: (RegisterAccess, Boolean) -> DWARFExpression): DWARFExpression? {
    if (this.isEmpty()) {
        return null
    }
    val stack = mutableListOf<DWARFExpression>()
    val asStackValue = this.any { it is StackValue }

    this.forEach { operation ->
        when (operation) {
            is RegisterAccess -> {
                stack.add(translateRegisterAccess(operation, asStackValue))
            }

            is Piece -> {
                `impossible!`
            }

            And,
            Minus,
            Or,
            Plus -> {
                check(stack.size >= 2) { "Expected to have at least two elements on the stack, got number of elements:  ${stack.size}, ${this}" }
                val left = stack[stack.lastIndex];
                stack.removeAt(stack.lastIndex);
                val right = stack[stack.lastIndex];
                stack.removeAt(stack.lastIndex);
                stack.add(DWARFExpression.BinaryExpr(operation, left, right))
            }

            is SignedConstant -> {
                stack.add(DWARFExpression.Constant(operation.value.toBigInteger()))
            }

            is UnsignedConstant -> {
                stack.add(DWARFExpression.Constant(operation.value.toBigInteger()))
            }

            StackValue -> {
            }

            is Unsupported -> {
                stack.add(DWARFExpression.StringValue(operation.operation))
            }
        }
    }
    return stack[stack.lastIndex]
}

fun DWARFOperation.usedRegister(): Value.Reg? {
    return when (this) {
        is RegisterAccess -> Value.Reg(SbfRegister.getByValue(register()))
        else -> null
    }
}