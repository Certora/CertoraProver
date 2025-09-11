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
package analysis.opt.signed

import algorithms.UnionFind
import analysis.CmdPointer
import analysis.LTACCmd
import analysis.TACProgramPrinter
import analysis.opt.signed.SignedDetector.Spot.Expr
import analysis.opt.signed.SignedDetector.Spot.Lhs
import analysis.storage.StorageAnalysisResult
import datastructures.stdcollections.*
import log.*
import tac.Tag
import utils.*
import utils.Color.Companion.blue
import utils.Color.Companion.green
import utils.Color.Companion.greenBg
import utils.Color.Companion.red
import utils.Color.Companion.redBg
import utils.Color.Companion.yellow
import utils.Color.Companion.yellowBg
import vc.data.*
import vc.data.tacexprutil.subs


private val logger = Logger(LoggerTypes.SIGNED_DETECTOR)

/**
 * Tries to understand which variables and expressions are signed and which are unsigned.
 * The result is given by a function from each such [Spot] in the program to one [Color].
 * Note that maps are considered to have only one color - that makes sense for storage, because each non-indexed-path
 * normally contains only one primitive solidity types. However, bytemaps are problematic because they can mix different
 * types. There is no simple solution for this (maybe mixing it with bytemapInliner logic, but that is going to be
 * ugly).
 *
 * The idea is to separate the different [Spot]s to equivalence classes, and use hints from their usage, to understand
 * if they are signed or unsigned.
 */
class SignedDetector(private val code: CoreTACProgram) {
    private val g = code.analysisCache.graph
    private val def = code.analysisCache.def // Notice - non strict!!!

    sealed interface Spot {
        data class Expr(val ptr: CmdPointer, val e: TACExpr) : Spot
        data class Lhs(val ptr: CmdPointer) : Spot
        data class Storage(val path: StorageAnalysisResult.NonIndexedPath) : Spot
    }
    private fun Expr(ptr : CmdPointer, s : TACSymbol) =
        if (s is TACSymbol.Var) {
            Expr(ptr, s.asSym())
        } else {
            null
        }

    private val signed = mutableSetOf<Spot>()
    private val unsigned = mutableSetOf<Spot>()
    private val uf = UnionFind<Spot>()

    operator fun MutableSet<Spot>.plusAssign(spot: Spot?) {
        spot?.let { add(spot) }
    }

    private val Tag.isGoodTag get() = this is Tag.Bits || this is Tag.Int || this is Tag.Map

    /**
     * Entry Point
     */
    fun go(): (Spot) -> Color? {
        g.commands.forEach(::handleCmd)
        return process()
    }


    private fun handleCmd(lcmd: LTACCmd) {
        val (ptr, cmd) = lcmd

        fun e(s : TACSymbol) = Expr(ptr, s)

        cmd.getFreeVarsOfRhs().filter { it.tag.isGoodTag }
            .forEach {
                uf.union(def.defSitesOf(it, ptr).map(Spot::Lhs) + e(it)!!)
            }

        with(cmd) {
            when (this) {
                is TACCmd.Simple.AssigningCmd.AssignExpCmd -> {
                    if (lhs.tag.isGoodTag) {
                        uf.union(Expr(ptr, rhs), Lhs(ptr))
                    }
                    handleExp(ptr, rhs)
                }

                is TACCmd.Simple.AssumeExpCmd ->
                    handleExp(ptr, cond)

                is TACCmd.Simple.WordStore -> {
                    union(e(base), e(value))
                    unsigned += e(loc)
                }

                is TACCmd.Simple.AssigningCmd.WordLoad -> {
                    union(Lhs(ptr), e(base))
                    unsigned += e(loc)
                }

                is TACCmd.Simple.ByteLongCopy -> {
                    unsigned += e(dstOffset)
                    unsigned += e(srcOffset)
                    unsigned += e(length)
                    union(e(dstBase), e(srcBase))
                }

                is TACCmd.Simple.AssigningCmd.ByteLoad -> {
                    union(Lhs(ptr), e(base))
                    unsigned += e(loc)
                }

                is TACCmd.Simple.AssigningCmd.ByteStore -> {
                    union(Lhs(ptr), e(base), e(value))
                    unsigned += e(loc)
                }

                is TACCmd.Simple.AssigningCmd.ByteStoreSingle -> {
                    union(Lhs(ptr), e(base), e(value))
                    unsigned += e(loc)
                }

                else -> {}
            }
        }
    }


    private fun union(vararg spots: Spot?) {
        uf.union(spots.filterNotNull())
    }

    private fun union(spots: Collection<Spot?>) {
        uf.union(spots.filterNotNull())
    }

    private fun handleExp(ptr: CmdPointer, e: TACExpr): Spot? {
        val opSpots = e.getOperands().map { handleExp(ptr, it) }
        val spot by lazy { Expr(ptr, e) }
        val goodTag = e.tagAssumeChecked.isGoodTag

        fun unionAll() {
            union(opSpots + spot)
        }
        when (e) {
            is TACExpr.Sym.Var ->
                unionAll()

            is TACExpr.TernaryExp.Ite ->
                if (goodTag) {
                    union(spot, opSpots[1], opSpots[2])
                }

            is TACExpr.AnnotationExp<*>,
            is TACExpr.Vec.Add,
            is TACExpr.Vec.Mul ->
                unionAll()

            is TACExpr.TernaryExp.AddMod,
            is TACExpr.TernaryExp.MulMod,
            is TACExpr.UnaryExp.BWNot,
            is TACExpr.BinOp.ShiftLeft,
            is TACExpr.BinOp.ShiftRightLogical,
            is TACExpr.BinOp.Mod,
            is TACExpr.BinOp.Div,
            is TACExpr.BinOp.Exponent -> {
                unionAll()
                unsigned += spot
            }

            is TACExpr.BinOp.SDiv,
            is TACExpr.BinOp.SMod -> {
                unionAll()
                signed += spot
            }

            is TACExpr.BinOp.ShiftRightArithmetical -> {
                union(opSpots[0], spot)
                signed += spot
                unsigned += opSpots[1]
            }

            is TACExpr.BinOp.SignExtend -> {
                signed += spot
                unsigned += opSpots[0]
            }

            //is TACExpr.BinOp.Sub, // sub can be of two unsigned and the result is signed
            is TACExpr.BinRel.Eq ->
                union(opSpots)

            is TACExpr.BinRel.Ge,
            is TACExpr.BinRel.Gt,
            is TACExpr.BinRel.Le,
            is TACExpr.BinRel.Lt -> {
                union(opSpots)
                // not necessarily unsigned, because these are used to bound signed variables as well.
            }

            is TACExpr.BinRel.Sge,
            is TACExpr.BinRel.Sgt,
            is TACExpr.BinRel.Sle,
            is TACExpr.BinRel.Slt -> {
                union(opSpots)
                signed += opSpots[0]
            }

            is TACExpr.Store -> {
                val (base, loc, value) = opSpots
                union(spot, base, value)
                unsigned += loc
            }

            is TACExpr.Select -> {
                val (base, loc) = opSpots
                union(spot, base)
                unsigned += loc
            }

            is TACExpr.LongStore -> {
                val (dstMap, dstOffset, srcMap, srcOffset, length) = opSpots
                unsigned += dstOffset
                unsigned += srcOffset
                unsigned += length
                union(spot, dstMap, srcMap)
            }

            is TACExpr.Apply -> {
                val bif = (e.f as? TACExpr.TACFunctionSym.BuiltIn)?.bif
                when (bif) {
                    is TACBuiltInFunction.SafeMathPromotion ->
                        unsigned += opSpots[0]

                    is TACBuiltInFunction.SafeMathNarrow ->
                        unsigned += spot

                    is TACBuiltInFunction.SafeUnsignedNarrow,
                    is TACBuiltInFunction.UnsignedPromotion -> {
                        unsigned += spot
                        unsigned += opSpots[0]
                    }

                    is TACBuiltInFunction.SafeSignedNarrow,
                    is TACBuiltInFunction.SignedPromotion -> {
                        unsigned += spot
                        unsigned += opSpots[0]
                    }

                    is TACBuiltInFunction.NoMulOverflowCheck,
                    is TACBuiltInFunction.NoAddOverflowCheck -> {
                        union(opSpots)
                        unsigned += opSpots[0]
                    }

                    is TACBuiltInFunction.TwosComplement.Wrap ->
                        signed += spot

                    is TACBuiltInFunction.TwosComplement.Unwrap ->
                        signed += opSpots[0]


                    is TACBuiltInFunction.NoSMulOverAndUnderflowCheck,
                    is TACBuiltInFunction.NoSAddOverAndUnderflowCheck,
                    is TACBuiltInFunction.NoSSubOverAndUnderflowCheck -> {
                        union(opSpots)
                        signed += opSpots[0]
                    }

                    else -> Unit
                }
            }

            else -> Unit
        }
        return spot
    }


    enum class Color {
        Signed, Unsigned, Contradicting
    }

    private fun process(): (Spot) -> Color? {
        val reps = uf.getAllRepresentatives()
        val repColor = reps.associateWith { r ->
            val set = uf.getEquivalenceClass(r)
            val colors = set.mapNotNullToSet { spot ->
                when {
                    spot in signed && spot in unsigned -> Color.Contradicting
                    spot in signed -> Color.Signed
                    spot in unsigned -> Color.Unsigned
                    else -> null
                }
            }
            when (colors.size) {
                0 -> null
                1 -> colors.single()
                else -> Color.Contradicting
            }
        }

        fun color(spot: Spot) = repColor[uf.find(spot)]

        logger.debug {
            g.commands.joinToString("\n") { (ptr, cmd) ->
                if (cmd is TACCmd.Simple.AssigningCmd.AssignExpCmd) {
                    cmd.rhs.subs.joinToString("\n") { e ->
                        if (e is TACExpr.Vec.Mul || e is TACExpr.Vec.Add || e is TACExpr.BinOp.Sub) {
                            when (color(Expr(ptr, e))) {
                                Color.Signed -> "Signed $ptr ${cmd.toStringNoMeta()}".yellow
                                Color.Unsigned -> "Unsigned $ptr ${cmd.toStringNoMeta()}".green
                                Color.Contradicting -> "Contradicting $ptr ${cmd.toStringNoMeta()}".red
                                null -> "Unknown $ptr ${cmd.toStringNoMeta()}".blue
                            }
                        } else {
                            ""
                        }
                    }
                } else {
                    ""
                }
            }
        }

        logger.trace {
            TACProgramPrinter.standard().extraLhsInfo { ptr ->
                val lhs = g.getLhs(ptr)
                    ?: return@extraLhsInfo ""
                if (lhs.tag is Tag.Bits) {
                    when (color(Lhs(ptr))) {
                        Color.Signed -> "Signed".yellowBg
                        Color.Unsigned -> "Unsigned".greenBg
                        Color.Contradicting -> "BAD".redBg
                        null -> ""
                    }
                } else {
                    ""
                }
            }.toString(code, javaClass.simpleName)
        }
        return ::color
    }
}
