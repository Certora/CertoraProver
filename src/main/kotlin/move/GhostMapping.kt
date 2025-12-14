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
    private fun materialize(
        ptr: CmdPointer,
        patch: PatchingTACProgram<TACCmd>,
        callCountsByTypeArgs: Map<List<MoveType.Value>, Int>,
        allResultValTypes: Set<MoveType.Value>,
        loopBlocks: Set<NBId>
    ) {
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

        fun ghostVar(tag: Tag, staticTypeArgs: List<MoveType.Value>): TACSymbol.Var {
            val name = name.toVarName()
            return TACSymbol.Var(
                name,
                tag,
                // treat ghost variables as keywords, to preserve their names in TAC dumps
                meta = MetaMap(TACSymbol.Var.KEYWORD_ENTRY to TACSymbol.Var.KeywordEntry(name))
            ).letIf(staticTypeArgs.isNotEmpty()) {
                it.withSuffix(
                    staticTypeArgs.joinToString("!") { it.symNameExt() },
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

        // If all uses have static type args, we might be able to optimize.
        val typeArgsAreStatic = callCountsByTypeArgs.none { (typeArgs, _) -> typeArgs.any { it is MoveType.Nondet } }

        // If we have only a single use, or only a single use with these type args, we might be able to optimize.
        val singleUse = callCountsByTypeArgs.values.sum() == 1 && ptr.block !in loopBlocks
        val singleUseWithTheseTypeArgs = callCountsByTypeArgs[typeArgs] == 1 && ptr.block !in loopBlocks

        val makeRef = when {
            singleUse -> {
                // No other uses; just use a simple TAC variable
                TACCmd.Move.BorrowLocCmd(
                    ref = refVar,
                    loc = ghostVar(resultValType.toTag(), emptyList()).ensureHavocInit(resultValType)
                ).withDecls(refVar)
            }
            typeArgsAreStatic && (singleUseWithTheseTypeArgs || params.isEmpty()) -> {
                // We know the type args statically, and can ignore the parameters, so we can use a simple TAC variable
                TACCmd.Move.BorrowLocCmd(
                    ref = refVar,
                    loc = ghostVar(resultValType.toTag(), typeArgs).ensureHavocInit(resultValType)
                ).withDecls(refVar)
            }
            typeArgsAreStatic && params.size == 1 && params[0] is MoveType.Bits -> {
                // A single numeric parameter: use it as an index into a ghost array
                val ghostArrayRef = TACKeyword.TMP(MoveTag.Ref(MoveType.GhostArray(setOf(resultValType))), "ghostArrayRef")
                mergeMany(
                    TACCmd.Move.BorrowLocCmd(
                        ref = ghostArrayRef,
                        loc = ghostVar(MoveTag.GhostArray(setOf(resultValType)), typeArgs).ensureHavocInit()
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
                val ghostArrayRef = TACKeyword.TMP(MoveTag.Ref(MoveType.GhostArray(allResultValTypes)), "ghostArrayRef")
                mergeMany(
                    CvlmHash.hashArguments(hash, name, typeArgs, args),
                    TACCmd.Move.BorrowLocCmd(
                        ref = ghostArrayRef,
                        loc = ghostVar(MoveTag.GhostArray(allResultValTypes), emptyList()).ensureHavocInit()
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

            val callCounts = mutableMapOf<MoveFunctionName, MutableMap<List<MoveType.Value>, Int>>()
            val allResultTypes = mutableMapOf<MoveFunctionName, MutableSet<MoveType.Value>>()
            calls.forEach { (_, call) ->
                val counts = callCounts.getOrPut(call.name) { mutableMapOf() }
                counts[call.typeArgs] = (counts[call.typeArgs] ?: 0) + 1

                val resultTypes = allResultTypes.getOrPut(call.name) { mutableSetOf() }
                resultTypes.add(
                    when (call.resultType) {
                        is MoveType.Reference -> call.resultType.refType
                        is MoveType.Value -> call.resultType
                    }
                )
            }
            val loopBlocks = code.graph.getNaturalLoops().map { it.body }.unionAll()

            val patch = code.toPatchingProgram()
            calls.forEach { (ptr, call) ->
                call.materialize(ptr, patch, callCounts[call.name]!!, allResultTypes[call.name]!!, loopBlocks)
            }
            return patch.toCode(code)
        }
    }
}
