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

package spec

import analysis.CommandWithRequiredDecls
import config.Config
import datastructures.stdcollections.*
import evm.EVM_WORD_SIZE
import tac.Tag
import vc.data.*
import java.math.BigInteger

object CodeGenUtils {
    /**
     * Rounds [s] up to the nearest multiple of 32 by:
     * idx = *
     * idxMul = idx * 32
     * assume idxMul >= s
     * prevIdx = idx -1
     * prevMul = prevIdx * 32
     * assume prevMul < s
     *
     * Note that all of these operations are done in Int land so we handle 0 properly.
     * You can convince yourself that idxMul is the smallest value greater than s that is divisible by 32.
     *
     * idxMul is thus returned (after downcasting to Bit256) and returned along with the commands to effect the above
     */
    fun wordAlignSymbol(s: TACSymbol.Var) : Pair<TACSymbol.Var, CommandWithRequiredDecls<TACCmd.Simple>> {
        val decls = mutableSetOf<TACSymbol.Var>()
        val toReturn = mutableListOf<TACCmd.Simple>()

        // round up to the nearest multiple of 32
        val promote = TACKeyword.TMP(Tag.Int, "!lenInt").toUnique("!")

        val idx = TACKeyword.TMP(Tag.Int, "!sub").toUnique("!")
        val idxMul = TACKeyword.TMP(Tag.Int, "!b").toUnique("!")
        val iAssume = TACKeyword.TMP(Tag.Bool, "!assumeThis").toUnique("!")

        val prevIdx = TACKeyword.TMP(Tag.Int, "!prev").toUnique()
        val prevMul = TACKeyword.TMP(Tag.Int, "!prevMul").toUnique("!")
        val prevAssume = TACKeyword.TMP(Tag.Bool, "!assumePrev").toUnique("!")
        val bumpAmount = TACKeyword.TMP(Tag.Bit256, "!roundedUp").toUnique("!")

        decls.addAll(
            listOf(
                idx, promote, prevIdx, idxMul, prevMul, prevAssume, iAssume, bumpAmount
            )
        )
        toReturn.addAll(
            listOf(
                TACCmd.Simple.AssigningCmd.AssignHavocCmd(
                    lhs = idx
                ),
                TACCmd.Simple.AssigningCmd.AssignExpCmd(
                    lhs = promote,
                    rhs = TACExpr.Apply(
                        TACBuiltInFunction.SafeMathPromotion(s.tag as Tag.Bits),
                        listOf(s.asSym()),
                        Tag.Int
                    )
                ),
                TACCmd.Simple.AssigningCmd.AssignExpCmd(
                    lhs = idxMul,
                    rhs = TACExpr.Vec.IntMul(
                        idx.asSym(),
                        TACSymbol.Const(
                            value = EVM_WORD_SIZE,
                            tag = Tag.Int
                        ).asSym()
                    )
                ),
                TACCmd.Simple.AssigningCmd.AssignExpCmd(
                    lhs = iAssume,
                    rhs = TACExpr.BinRel.Ge(
                        idxMul.asSym(),
                        promote.asSym()
                    )
                ),
                TACCmd.Simple.AssumeCmd(
                    iAssume
                ),
                TACCmd.Simple.AssigningCmd.AssignExpCmd(
                    lhs = prevIdx,
                    rhs = TACExpr.BinOp.IntSub(
                        idx.asSym(),
                        TACSymbol.Const(BigInteger.ONE, Tag.Int).asSym()
                    )
                ),
                TACCmd.Simple.AssigningCmd.AssignExpCmd(
                    lhs = prevMul,
                    rhs = TACExpr.Vec.IntMul(
                        prevIdx.asSym(),
                        TACSymbol.Const(
                            value = EVM_WORD_SIZE,
                            tag = Tag.Int
                        ).asSym()
                    )
                ),
                TACCmd.Simple.AssigningCmd.AssignExpCmd(
                    lhs = prevAssume,
                    rhs = TACExpr.BinRel.Lt(
                        prevMul.asSym(),
                        promote.asSym()
                    )
                ),
                TACCmd.Simple.AssumeCmd(
                    prevAssume
                ),
                TACCmd.Simple.AssigningCmd.AssignExpCmd(
                    lhs = bumpAmount,
                    rhs = TACExpr.Apply(
                        TACBuiltInFunction.SafeMathNarrow(Tag.Bit256),
                        listOf(idxMul.asSym()),
                        Tag.Bit256
                    )
                )
            )
        )
        return bumpAmount to CommandWithRequiredDecls(toReturn, decls)
    }

    /**
     * Calculate the representation of [n] as a bytesK with bitwidth [bytesKBitWidth] (i.e. right-padded).
     */
    fun numAsBytesKConst(n: BigInteger, bytesKBitWidth: Int): TACSymbol.Const {
        check(n >= BigInteger.ZERO && n.bitLength() <= bytesKBitWidth){
            "typechecker should have caught these violations"
        }

        //left shift by 256-k bytes
        val leftShifted = n.shiftLeft(Config.VMConfig.registerBitwidth - bytesKBitWidth)
        return TACSymbol.Const(leftShifted, Tag.Bit256)
    }
}
