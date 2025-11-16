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
import analysis.CommandWithRequiredDecls.Companion.mergeMany
import datastructures.stdcollections.*
import tac.*
import tac.generation.*
import utils.*
import vc.data.*

/**
    Summarizes a call to a ghost mapping function.
 */
@KSerializable
data class GhostMapping(
    val name: MoveFunctionName,
    val typeArgs: List<MoveType.Value>,
    val params: List<MoveType>,
    val args: List<TACSymbol.Var>,
    val resultType: MoveType,
    val result: TACSymbol.Var,
) : AssignmentSummary() {
    override val annotationDesc get() = "ghost mapping: $result := $name(${args.joinToString(", ")})"

    override val inputs get() = args
    override val mustWriteVars get() = listOf(result)
    override val mayWriteVars get() = listOf(result)

    override fun transformSymbols(f: Transformer) = copy(
        args = args.map { f(it) },
        result = f(result)
    )

    context(SummarizationContext)
    private fun materialize(ptr: CmdPointer, patch: PatchingTACProgram<TACCmd>, ignoreParams: Boolean) {
        /*
            - For a nongeneric ghost function with no parameters `native fun ghost(): &R`, we generate a single
                TAC variable of type `R.toTag()`.
            - For a nongeneric ghost function with a single numeric parameter, we generate a single TAC variable
                of type `MoveTag.GhostArray(R)`, and use the parameter as an index into the array.
            - For a nongeneric ghost function with multiple parameters, we generate a single TAC variable of
                type `MoveTag.GhostArray(R)`, and hash the parameters to get the index.
            - Generic ghost functions work exactly as above, except we generate a separate variable for each
                instantiation of the ghost function.
        */

        fun ghostVar(tag: Tag): TACSymbol.Var {
            val name = name.toVarName()
            return TACSymbol.Var(
                name,
                tag,
                // treat ghost variables as keywords, to preserve their names in TAC dumps
                meta = MetaMap(TACSymbol.Var.KEYWORD_ENTRY to TACSymbol.Var.KeywordEntry(name))
            ).letIf(typeArgs.isNotEmpty()) {
                it.withSuffix(
                    typeArgs.joinToString("!") { it.symNameExt() },
                    "!"
                )
            }
        }

        val (isRefReturn, resultValType) = when (resultType) {
            is MoveType.Reference -> true to resultType.refType
            is MoveType.Value -> false to resultType
        }

        val refVar = if (isRefReturn) {
            result
        } else {
            TACKeyword.TMP(MoveTag.Ref(resultValType))
        }

        val makeRef = when {
            params.isEmpty() || ignoreParams -> {
                // No parameters: just use a simple TAC variable
                TACCmd.Move.BorrowLocCmd(
                    ref = refVar,
                    loc = ghostVar(resultValType.toTag()).ensureHavocInit(resultValType)
                ).withDecls(refVar)
            }
            params.size == 1 && params[0] is MoveType.Bits -> {
                // A single numeric parameter: use it as an index into a ghost array
                val ghostArrayRef = TACKeyword.TMP(MoveTag.Ref(MoveType.GhostArray(resultValType)), "ghostArrayRef")
                mergeMany(
                    TACCmd.Move.BorrowLocCmd(
                        ref = ghostArrayRef,
                        loc = ghostVar(MoveTag.GhostArray(resultValType)).ensureHavocInit()
                    ).withDecls(ghostArrayRef),
                    TACCmd.Move.GhostArrayBorrowCmd(
                        dstRef = refVar,
                        arrayRef = ghostArrayRef,
                        index = args[0]
                    ).withDecls(refVar)
                )
            }
            else -> {
                // Hash the arguments to get an index into a ghost array
                val hash = TACKeyword.TMP(Tag.Bit256)
                val ghostArrayRef = TACKeyword.TMP(MoveTag.Ref(MoveType.GhostArray(resultValType)), "ghostArrayRef")
                mergeMany(
                    CvlmHash.hashArguments(hash, name, typeArgs, args),
                    TACCmd.Move.BorrowLocCmd(
                        ref = ghostArrayRef,
                        loc = ghostVar(MoveTag.GhostArray(resultValType)).ensureHavocInit()
                    ).withDecls(ghostArrayRef),
                    TACCmd.Move.GhostArrayBorrowCmd(
                        dstRef = refVar,
                        arrayRef = ghostArrayRef,
                        index = hash
                    ).withDecls(refVar)
                )
            }
        }

        val replacement = if (isRefReturn) {
            makeRef
        } else {
            mergeMany(
                makeRef,
                TACCmd.Move.ReadRefCmd(
                    dst = result,
                    ref = refVar
                ).withDecls(result)
            )
        }

        patch.replaceCommand(ptr, replacement)
    }

    companion object {
        context(SummarizationContext)
        fun materialize(code: MoveTACProgram): MoveTACProgram {
            val calls = code.graph.commands.mapNotNull { (ptr, cmd) ->
                ptr `to?` (cmd as? TACCmd.Simple.SummaryCmd)?.summ as? GhostMapping
            }.toList()
            if (calls.isEmpty()) {
                return code
            }

            val callsByFuncInstance = calls.groupBy { (_,  call) -> call.name to call.typeArgs }
            val loopBlocks = code.graph.getNaturalLoops().map { it.body }.unionAll()

            val patch = code.toPatchingProgram()
            calls.forEach { (ptr, call) ->
                // As a special case, if there is only one use of a particular ghost mapping, and it's not in a loop (so
                // we know it will only execute once), then we can ignore the parameters.  This avoids creating a ghost
                // array, which can hugely benefit static analysis and solving.
                val onlyOneCall = callsByFuncInstance[call.name to call.typeArgs]?.size == 1 && ptr.block !in loopBlocks

                call.materialize(ptr, patch, ignoreParams = onlyOneCall)
            }
            return patch.toCode(code)
        }
    }
}
