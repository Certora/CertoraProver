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

package vc.gen

import analysis.CmdPointer
import analysis.LTACCmd
import analysis.LTACCmdView
import analysis.opt.inliner.InliningCalculator
import datastructures.stdcollections.*
import utils.*
import vc.data.*
import vc.data.TACMeta.ACCESS_PATHS
import vc.data.tacexprutil.TACUnboundedHashingUtils
import java.math.BigInteger

object TACSimpleSimple {

    fun TACCmd.Simple.inSimpleSimpleFragment() = this is TACCmd.Simple.AssigningCmd.AssignHavocCmd ||
        this is TACCmd.Simple.AssigningCmd.AssignExpCmd || this is TACCmd.Simple.AssumeCmd ||
        this is TACCmd.Simple.AssumeNotCmd || this is TACCmd.Simple.AssumeExpCmd ||
        this is TACCmd.Simple.AssertCmd || this is TACCmd.Simple.LabelCmd ||
        this is TACCmd.Simple.AnnotationCmd || this is TACCmd.Simple.JumpCmd || this is TACCmd.Simple.JumpiCmd ||
        this === TACCmd.Simple.NopCmd

    fun simpleSimpleHashing(p: CoreTACProgram): CoreTACProgram {
        val patching = p.toPatchingProgram()
        val inliningCalculator by lazy { InliningCalculator(p).also { it.go() } }
        patching.forEachOriginal { cmdPointer, simple ->
            when (simple) {
                is TACCmd.Simple.AssigningCmd.AssignSha3Cmd -> {
                    simpleSimplifyAssignSha3Cmd(simple, cmdPointer, patching, inliningCalculator)
                }

                else -> {}
            }
        }
        return patching.toCode(p, TACCmd.Simple.NopCmd)
    }

    private fun simpleSimplifyByteLongCopy(c: TACCmd.Simple.ByteLongCopy,   cmdPointer: CmdPointer,
                                           patching: SimplePatchingProgram,
                                           renamedDstmap: TACSymbol.Var) {
        patching.replaceCommand(
            cmdPointer, listOf(
                TACCmd.Simple.AssigningCmd.AssignExpCmd(
                    lhs = c.dstBase,
                    rhs = TACExpr.LongStore(
                        dstMap = renamedDstmap.asSym(),
                        dstOffset = c.dstOffset.asSym(),
                        length = c.length.asSym(),
                        srcMap = c.srcBase.asSym(),
                        srcOffset = c.srcOffset.asSym()
                    ),
                    meta = c.meta
                )
            )
        )
    }

    private fun simpleSimplifyAssignSha3Cmd(
        c: TACCmd.Simple.AssigningCmd.AssignSha3Cmd,
        cmdPointer: CmdPointer,
        patching: SimplePatchingProgram,
        inliningCalculator: InliningCalculator, // passing this for optimizations
    ) {
        val (leadingConstants, computedLength) =
            TACUnboundedHashingUtils.HashOptimizer.getLeadingConstantsAndLength(
                LTACCmdView(LTACCmd(cmdPointer, c)),
                inliningCalculator
            )
        val (hashExp, newDecls, newCmds) = TACUnboundedHashingUtils.fromByteMapRange(
            hashFamily = HashFamily.Keccack, // TOOD: double check -- this one's really Keccack, the SimpleSha3 is actually Sha3 -- is that right, shelly? (should prob rename..)
            map = (c.memBaseMap as? TACSymbol.Var)
                ?: throw UnsupportedOperationException("Cannot convert constant hash base $c @ $cmdPointer"),
            start = c.op1.asSym(),
            length = c.op2.asSym(),
            leadingConstants = leadingConstants,
            computedLength = computedLength,
        )
        patching.addVarDecls(newDecls)
        patching.replaceCommand(
            cmdPointer,
            newCmds + TACCmd.Simple.AssigningCmd.AssignExpCmd(
                c.lhs,
                hashExp,
                c.meta
            )
        )
    }

    fun toSimpleSimple(p: CoreTACProgram): CoreTACProgram {
        val patching = p.toPatchingProgram()
        val inliningCalculator by lazy { InliningCalculator(p).also { it.go() } }
        patching.forEachOriginal { cmdPointer, simple ->
            when (simple) {
                is TACCmd.Simple.AssigningCmd.AssignExpCmd -> return@forEachOriginal
                is TACCmd.Simple.AssigningCmd.AssignSha3Cmd -> {
                    simpleSimplifyAssignSha3Cmd(simple, cmdPointer, patching, inliningCalculator)
                }

                is TACCmd.Simple.AssigningCmd.AssignSimpleSha3Cmd -> {
                    patching.replaceCommand(cmdPointer, listOf(
                        TACCmd.Simple.AssigningCmd.AssignExpCmd(
                            simple.lhs,
                            TACExpr.SimpleHash(
                                simple.length.asSym(),
                                simple.args.map { it.asSym() },
                                hashFamily = HashFamily.Keccack,
                            ),
                            simple.meta
                        )
                    ))
                }

                is TACCmd.Simple.AssigningCmd.ByteLoad -> {
                    patching.replaceCommand(
                        cmdPointer, listOf(
                            TACCmd.Simple.AssigningCmd.AssignExpCmd(
                                lhs = simple.lhs,
                                rhs = TACExpr.Select(
                                    base = simple.base.asSym(),
                                    loc = simple.loc.asSym()
                                ),
                                meta = simple.meta
                            )
                        )
                    )
                }

                is TACCmd.Simple.AssigningCmd.ByteStore -> {
                    patching.replaceCommand(
                        cmdPointer, listOf(
                            TACCmd.Simple.AssigningCmd.AssignExpCmd(
                                lhs = simple.base,
                                rhs = TACExpr.Store(
                                    base = simple.base.asSym(),
                                    loc = simple.loc.asSym(),
                                    value = simple.value.asSym()
                                ),
                                meta = simple.meta
                            )
                        )
                    )
                }

                is TACCmd.Simple.AssigningCmd.ByteStoreSingle -> {
                    val loc = simple.loc.asSym()
                    val value = simple.value.asSym()
                    val mod = TACExpr.BinOp.Mod(loc, 32.toBigInteger().asTACSymbol().asSym())
                    val alignedLoc = TACExpr.BinOp.Sub(
                        loc,
                        mod
                    )
                    val shiftFactor = TACExpr.Vec.Mul(
                        listOf(
                            BigInteger.valueOf(8).asTACSymbol().asSym(),
                            TACExpr.BinOp.Sub(
                                BigInteger.valueOf(31).asTACSymbol().asSym(),
                                mod
                            )
                        )
                    )
                    val oldValue = TACExpr.Select(simple.base.asSym(), alignedLoc)
                    val oldValueWithHole = TACExpr.BinOp.BWAnd(
                        oldValue,
                        TACExpr.UnaryExp.BWNot(
                            TACExpr.BinOp.ShiftLeft(
                                255.toBigInteger().asTACSymbol().asSym(),
                                shiftFactor
                            )
                        )
                    )
                    val rhsShifted = TACExpr.BinOp.ShiftLeft(
                        TACExpr.BinOp.BWAnd(
                            value, 255.toBigInteger().asTACSymbol().asSym()
                        ), shiftFactor
                    )
                    val newValue = TACExpr.BinOp.BWOr(oldValueWithHole, rhsShifted)
                    patching.replaceCommand(
                        cmdPointer, listOf(
                            TACCmd.Simple.AssigningCmd.AssignExpCmd(
                                lhs = simple.base,
                                rhs = TACExpr.Store(
                                    base = simple.base.asSym(),
                                    loc = alignedLoc,
                                    value = newValue
                                ),
                                meta = simple.meta
                            )
                        )
                    )
                }

                is TACCmd.Simple.AssigningCmd.WordLoad -> {
                    val rhs = TACExpr.Select(
                        base = simple.base.asSym(),
                        loc = simple.loc.asSym()
                    ).let {
                        (it.loc as? TACExpr.Sym.Var)?.s?.meta?.get(ACCESS_PATHS)?.let { paths ->
                            TACExpr.AnnotationExp(it, ACCESS_PATHS, paths)
                        } ?: it
                    }
                    patching.update(cmdPointer,
                        TACCmd.Simple.AssigningCmd.AssignExpCmd(
                                lhs = simple.lhs,
                                rhs = rhs,
                                meta = simple.meta
                        )
                    )
                }

                is TACCmd.Simple.AssigningCmd.AssignMsizeCmd -> {
                    patching.replaceCommand(
                        cmdPointer, listOf(
                            TACCmd.Simple.AssigningCmd.AssignHavocCmd(
                                lhs = simple.lhs,
                                meta = simple.meta
                            )
                        )
                    )
                }

                is TACCmd.Simple.AssigningCmd.AssignGasCmd -> {
                    patching.replaceCommand(
                        cmdPointer, listOf(
                            TACCmd.Simple.AssigningCmd.AssignHavocCmd(
                                lhs = simple.lhs,
                                meta = simple.meta
                            )
                        )
                    )
                }

                is TACCmd.Simple.AssigningCmd.AssignHavocCmd -> return@forEachOriginal
                is TACCmd.Simple.AnnotationCmd,
                is TACCmd.Simple.LabelCmd -> return@forEachOriginal

                is TACCmd.Simple.WordStore -> {
                    val rhs = TACExpr.Store(
                        value = simple.value.asSym(),
                        base = simple.base.asSym(),
                        loc = simple.loc.asSym()
                    ).let {
                        (it.loc as? TACExpr.Sym.Var)?.s?.meta?.get(ACCESS_PATHS)?.let { paths ->
                            TACExpr.AnnotationExp(it, ACCESS_PATHS, paths)
                        } ?: it
                    }
                    patching.replaceCommand(
                        cmdPointer, listOf(
                            TACCmd.Simple.AssigningCmd.AssignExpCmd(
                                lhs = simple.base,
                                rhs = rhs,
                                meta = simple.meta
                            )
                        )
                    )
                }

                is TACCmd.Simple.ByteLongCopy -> {
                    simpleSimplifyByteLongCopy(simple, cmdPointer, patching, simple.dstBase)
                }

                is TACCmd.Simple.JumpCmd,
                is TACCmd.Simple.JumpiCmd -> return@forEachOriginal

                is TACCmd.Simple.SummaryCmd -> throw UnsupportedOperationException("Summary command $simple @ $cmdPointer falls outside fragment")
                is TACCmd.Simple.CallCore -> throw UnsupportedOperationException("Call core $simple @ $cmdPointer should have been replaced")
                is TACCmd.Simple.JumpdestCmd,
                is TACCmd.Simple.LogCmd,
                is TACCmd.Simple.ReturnCmd,
                is TACCmd.Simple.ReturnSymCmd,
                is TACCmd.Simple.RevertCmd -> patching.replaceCommand(cmdPointer, listOf(), null)

                TACCmd.Simple.NopCmd -> patching.replaceCommand(cmdPointer, listOf(), null)
                is TACCmd.Simple.AssumeCmd,
                is TACCmd.Simple.AssumeNotCmd,
                is TACCmd.Simple.AssumeExpCmd,
                is TACCmd.Simple.AssertCmd -> return@forEachOriginal
            }
        }
        return patching.toCode(p, TACCmd.Simple.NopCmd)
    }
}
