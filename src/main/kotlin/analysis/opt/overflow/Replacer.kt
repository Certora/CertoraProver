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

package analysis.opt.overflow

import analysis.CmdPointer
import analysis.opt.overflow.Matcher.Match
import analysis.opt.overflow.OverflowKey.MATCHED_LCMD
import analysis.opt.overflow.OverflowPatternRewriter.Companion.OverflowMetaData
import analysis.opt.overflow.OverflowPatternRewriter.Companion.overflowMeta
import analysis.patterns.get
import datastructures.stdcollections.*
import evm.EVM_BITWIDTH256
import log.*
import tac.NBId
import tac.Tag
import utils.*
import utils.ModZm.Companion.from2s
import utils.ModZm.Companion.lowOnes
import utils.ModZm.Companion.to2s
import utils.SignUtilities.maxSignedValueOfBitwidth
import utils.SignUtilities.maxUnsignedValueOfBitwidth
import utils.SignUtilities.minSignedValueOfBitwidth
import vc.data.*
import vc.data.TACCmd.Simple.*
import vc.data.TACCmd.Simple.AssigningCmd.AssignExpCmd
import vc.data.TACExprFactUntyped.Ge
import vc.data.TACExprFactUntyped.LOr
import vc.data.TACExprFactUntyped.Le
import java.math.BigInteger

private val logger = Logger(LoggerTypes.OVERFLOW_PATTERN_REWRITER)


/**
 * A Worker class for the actual rewriting.
 */
class Replacer<T : OverflowContext>(
    private val code: CoreTACProgram,
    private val patcher: SimplePatchingProgram,
    private val context: T,
    private val match: Match<T>
) {
    private val g = code.analysisCache.graph
    private val infos get() = match.infos
    private val recipe get() = match.recipe
    private val width get() = match.width
    private val lcmds = infos.values.map { it[MATCHED_LCMD]!! }
    private val origOperationPtr get() = context.ptr
    private val tag get() = context.cmd.rhs.tagAssumeChecked as Tag.Bits
    private val txf = TACExprFactUntyped



    /** Adds a meta the new operation command. Used especially for tests. */
    private fun TACCmd.Simple.addOverflowMeta(): TACCmd.Simple {
        val overflow = OverflowMetaData(recipe.type, width, recipe.signed)
        logger.info { "${code.name} at $origOperationPtr : replaced overflow pattern $overflow" }
        logger.debug {
            "For ${code.name} at $origOperationPtr:\n    $recipe, $width, assume=${match is Match.Assume<*>}" +
                "\n    $infos\n  $"
        }
        Logger.regression { "${code.name} : replaced overflow pattern $overflow" }
        return plusMeta(overflowMeta, overflow)
    }

    private val newOperationCommand = with(context) {
        with(txf) {
            if (recipe.signed) {
                when (recipe.type) {
                    RecipeType.Add -> twosWrap(IntAdd(twosUnwrap(sym1), twosUnwrap(sym2)))
                    RecipeType.Sub -> twosWrap(IntSub(twosUnwrap(sym1), twosUnwrap(sym2)))
                    RecipeType.ConstMul, RecipeType.BinaryMul -> twosWrap(IntMul(twosUnwrap(sym1), twosUnwrap(sym2)))
                }
            } else {
                when (recipe.type) {
                    RecipeType.Add -> safeMathNarrow(IntAdd(sym1, sym2), tag)
                    RecipeType.Sub -> safeMathNarrow(IntSub(sym1, sym2), tag)
                    RecipeType.ConstMul, RecipeType.BinaryMul -> safeMathNarrow(IntMul(sym1, sym2), tag)
                }
            }.let {
                cmd.copy(rhs = it).addOverflowMeta()
            }
        }
    }

    /** The specific bounds for the the result of the operation (currently only multiplications) */
    private val constNoOverflowExp = (context as? OverflowContext.Const)?.run {
        check(recipe.type == RecipeType.ConstMul) {
            "Currently only overflow recipes for multiplications by constants are supported, and not ${recipe.type}"
        }
        if (recipe.signed) {
            val c = const.from2s(modZ256)
            check(c != BigInteger.ZERO) {
                "Multiplication by 0 should have been simplified away, and never go through overflow pattern matching"
            }
            val (maxPos, minNeg) = if (c > BigInteger.ZERO) {
                Pair(
                    maxSignedValueOfBitwidth(width) / c,
                    minSignedValueOfBitwidth(width) / c
                )
            } else {
                Pair(
                    minSignedValueOfBitwidth(width) / c,
                    maxSignedValueOfBitwidth(width) / c
                )
            }
            if (minNeg == BigInteger.ZERO) {
                Le(o1.asSym(), maxPos.asTACExpr)
            } else {
                LOr(
                    Le(o1.asSym(), maxPos.asTACExpr),
                    Ge(o1.asSym(), minNeg.to2s(modZ256).asTACExpr)
                )
            }
        } else {
            Le(o1.asSym(), (lowOnes(width) / const).asTACExpr)
        }
    }

    private val basicNoOverflowExp = (context as? OverflowContext.Binary)?.run {
        with(txf) {
             if (recipe.signed) {
                when (recipe.type) {
                    RecipeType.Add -> noSAddOverAndUnderflow(sym1, sym2)
                    RecipeType.Sub -> noSSubOverAndUnderflow(sym1, sym2)
                    RecipeType.ConstMul, RecipeType.BinaryMul -> noSMulOverAndUnderflow(sym1, sym2)
                }
            } else {
                when (recipe.type) {
                    RecipeType.Add -> noAddOverflow(sym1, sym2)
                    RecipeType.Sub -> Ge(sym1, sym2)
                    RecipeType.ConstMul, RecipeType.BinaryMul -> noMulOverflow(sym1, sym2)
                }
            }
        }
    }

    /** This only makes sense if we already know the [basicNoOverflowExp] is true */
    private val exactNoOverflowExp = (context as? OverflowContext.Binary)
        ?.runIf(width != EVM_BITWIDTH256) {
            with(txf) {
                if (recipe.signed) {
                    LOr(
                        Le(lhs.asSym(), maxSignedValueOfBitwidth(width).asTACExpr),
                        Ge(lhs.asSym(), minSignedValueOfBitwidth(width).to2s().asTACExpr),
                    )
                } else {
                    Le(lhs.asSym(), maxUnsignedValueOfBitwidth(width).asTACExpr)
                }
            }
        }

    /**
     * simple enough...
     */
    private fun handleAssume() {
        lcmds.forEach { // deletes the assumes
            patcher.delete(it.ptr)
        }
        patcher.replaceCommand( // replace the operation
            origOperationPtr,
            listOfNotNull(
                AssumeExpCmd(constNoOverflowExp ?: basicNoOverflowExp!!),
                newOperationCommand,
                exactNoOverflowExp?.let { AssumeExpCmd(it) }
            )
        )
    }


    /**
     * creates a little tree of basically empty blocks to create a simulation of non-deterministic choice between
     * all of the blocks of [toBlocks]. [from] is just used for naming the blocks.
     */
    private fun nonDetChoice(from: NBId, toBlocks: List<NBId>): NBId {
        if (toBlocks.size == 1) {
            return toBlocks.single()
        }
        val nondetVar = patcher.tmpVar("nondet", Tag.Bool)
        return patcher.addBlock(
            from,
            listOf(
                AssigningCmd.AssignHavocCmd(nondetVar),
                JumpiCmd(
                    cond = nondetVar,
                    dst = toBlocks[0],
                    elseDst = nonDetChoice(from, toBlocks.subList(1, toBlocks.size))
                )
            )
        )
    }


    /** See the kdoc of [OverflowPatternRewriter] for details */
    private fun handleJumps() {
        check(match is Match.JumpI) {
            "`handleJumps` should not be called on a non-jump match : $match"
        }
        fun lastBlockPtr(b: NBId) = g.elab(b).commands.last().ptr
        val lastBlock = match.lastBlock
        val firstBlock = match.firstBlock
        val intermediateBlocks = match.intermediateBlocks
        val revertBlock = nonDetChoice(firstBlock, match.revertBlocks)

        // create the new intermediate block
        val exactCond = patcher.tmpVar("overflow", Tag.Bool)
        val simpleCond = patcher.tmpVar("overflow", Tag.Bool)

        val operationBlock =
            if (exactNoOverflowExp != null) {
                patcher.addBlock(
                    firstBlock,
                    listOf(
                        newOperationCommand,
                        AssignExpCmd(exactCond, exactNoOverflowExp),
                        JumpiCmd(cond = exactCond, dst = lastBlock, elseDst = revertBlock)
                    )
                )
            } else {
                lastBlock
            }

        when (match) {
            is Match.JumpI.Backward -> {
                check(lastBlock == origOperationPtr.block)
                patcher.replaceCommand(
                    lastBlockPtr(firstBlock),
                    intermediateBlocks.flatMap { blk ->
                        g.elab(blk).commands.dropLast(1).map { it.cmd }
                    } + listOf(
                        AssignExpCmd(simpleCond, constNoOverflowExp ?: basicNoOverflowExp!!),
                        JumpiCmd(cond = simpleCond, dst = operationBlock, elseDst = revertBlock)
                    )
                )
                if (exactNoOverflowExp != null) {
                    // in this case the `newOperationCommand` appears in `tempBlock`.
                    patcher.delete(origOperationPtr)
                } else {
                    patcher.update(origOperationPtr, newOperationCommand)
                }
            }

            is Match.JumpI.Forward -> {
                check(firstBlock == origOperationPtr.block)

                fun usesMulResult(cmd: TACCmd.Simple) =
                    cmd.getFreeVarsOfRhs().any { it in match.outflow }

                val addToLastBlock = mutableListOf<TACCmd.Simple>()
                val addToFirstBlock = mutableListOf<TACCmd.Simple>()

                if (exactNoOverflowExp == null) {
                    // `newOperationCommand` does not appear in `tempBlock`, and we add it to the last block.
                    addToLastBlock += newOperationCommand
                }

                g.lcmdSequence(firstBlock, origOperationPtr.pos + 1, lastBlockPtr(firstBlock).pos - 1)
                    .filter { usesMulResult(it.cmd) }
                    .forEach { (ptr, cmd) ->
                        patcher.delete(ptr)
                        addToLastBlock += cmd
                    }

                intermediateBlocks.forEach { b ->
                    g.lcmdSequence(b, high = g.elab(b).commands.lastIndex - 1).forEach { (_, cmd) ->
                        if (usesMulResult(cmd)) {
                            addToLastBlock += cmd
                        } else {
                            addToFirstBlock += cmd
                        }
                    }
                }

                patcher.replaceCommand(
                    lastBlockPtr(firstBlock),
                    addToFirstBlock + listOf(
                        AssignExpCmd(simpleCond, constNoOverflowExp ?: basicNoOverflowExp!!),
                        JumpiCmd(cond = simpleCond, dst = operationBlock, elseDst = revertBlock)
                    )
                )

                patcher.addBefore(
                    CmdPointer(lastBlock, 0),
                    addToLastBlock
                )
            }
        }

        intermediateBlocks.forEach {
            patcher.removeBlock(it)
        }
    }


    fun go() {
        when (match) {
            is Match.Assume -> handleAssume()
            is Match.JumpI -> handleJumps()
        }
    }

}
