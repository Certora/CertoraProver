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

package smt.axiomgenerators.fullinstantiation.expnormalizer

import analysis.split.Ternary.Companion.isPowOf2
import evm.EVM_MOD_GROUP256
import evm.MAX_EVM_UINT256
import evm.twoToThe
import smt.HashingScheme
import smt.solverscript.LExpressionFactory
import smt.solverscript.SmtTheory
import smt.solverscript.functionsymbols.*
import tac.Tag
import tac.commonBitsOrInt
import utils.*
import vc.data.LExpression
import vc.data.lexpression.*
import verifier.CreateAxiomGeneratorContainer
import java.math.BigInteger

/**
 * Used for translating to BV-Logic.
 */
class ExpNormalizerBV(
    lxf: LExpressionFactory,
    targetTheory: SmtTheory,
    hashingScheme: HashingScheme,
    private val overflowChecks: CreateAxiomGeneratorContainer.Config.BvOverflowChecks
) : ExpNormalizer(lxf, targetTheory, hashingScheme) {

    val useSmtBuiltinOverflowChecks
        get() = when (overflowChecks) {
            CreateAxiomGeneratorContainer.Config.BvOverflowChecks.NewSmtLib -> true
            CreateAxiomGeneratorContainer.Config.BvOverflowChecks.ViaDefineFun -> false
            CreateAxiomGeneratorContainer.Config.BvOverflowChecks.DontCare ->
                throw IllegalArgumentException(
                    "Choice for overflow checks cannot be \"DontCare\" when translating to " +
                        "a BV logic."
                )
        }

    private fun Tag.toBV(): Tag = when (this) {
        Tag.Int -> Tag.Bit256
        is Tag.GhostMap -> Tag.GhostMap(
            paramSorts.map { it.toBV() },
            resultSort.toBV()
        )
        is Tag.UserDefined.Struct,
        is Tag.TransientTag -> error("should not be treating $this as values in SMT")
        else -> this
    }

    private fun List<LExpression>.deriveTag(): Tag.Bits =
        map { it.tag }.commonBitsOrInt() as Tag.Bits
    override val functionSymbolNormalizer = object: PlainLExpressionTransformer() {
        override fun literalPre(lit: LExpression.Literal) = when (lit.tag) {
            Tag.Int -> {
                if (lit.i < BigInteger.ZERO ||  Tag.Bit256.maxUnsigned < lit.i) {
                    logger.warn { "Coercing integer literal $lit into ${Tag.Bit256}" }
                }
                LExpression.Literal(lit.i.mod(EVM_MOD_GROUP256), Tag.Bit256).lift()
            }
            else -> null
        }
        override fun identifierPre(id: LExpression.Identifier) =
            id.copy(tag = id.tag.toBV()).lift()

        private fun sub(exp: ApplyExpr) = lxf { exp.lhs bvAdd bvNeg(exp.rhs) }.lift()
        private fun vecAdd(exp: ApplyExpr, tag: Tag.Bits) =
            lxf.buildAppExpFoldVecToBinary(TheoryFunctionSymbol.BV.BvAdd(tag), exp.args, exp.meta).lift()
        private fun vecMul(exp: ApplyExpr, tag: Tag.Bits) =
            lxf.buildAppExpFoldVecToBinary(TheoryFunctionSymbol.BV.BvMul(tag), exp.args, exp.meta).lift()

        override fun applyExprPost(exp: ApplyExpr) = when (exp.f) {
            is NonSMTInterpretedFunctionSymbol -> when (exp.f) {
                is NonSMTInterpretedFunctionSymbol.Nullary.Nondet -> null

                is NonSMTInterpretedFunctionSymbol.Unary.BitwiseNot ->
                    exp.copy(f = TheoryFunctionSymbol.BV.BvNot(exp.arg.tag as Tag.Bits))
                is NonSMTInterpretedFunctionSymbol.Unary.Extract ->
                    exp.copy(f = TheoryFunctionSymbol.BV.Extract(exp.f.l, exp.f.r))
                is NonSMTInterpretedFunctionSymbol.Unary.SignedPromote ->
                    exp.copy(f = TheoryFunctionSymbol.BV.SignedPromote(exp.f.from, exp.f.to))
                is NonSMTInterpretedFunctionSymbol.Unary.UnsignedPromote ->
                    exp.copy(f = TheoryFunctionSymbol.BV.UnsignedPromote(exp.f.from, exp.f.to))
                is NonSMTInterpretedFunctionSymbol.Unary.SafeMathNarrow -> when (val at = exp.arg.tag) {
                    exp.f.to -> exp.arg.lift()
                    Tag.Int -> exp.copy(f = TheoryFunctionSymbol.BV.UnsignedNarrow(Tag.Bit256, exp.f.to))
                    is Tag.Bits -> exp.copy(f = TheoryFunctionSymbol.BV.UnsignedNarrow(at, exp.f.to))
                    else -> error("Expected source type of narrow to be numeric")
                }
                is NonSMTInterpretedFunctionSymbol.Unary.SafeSignedNarrow -> null // will come later
                is NonSMTInterpretedFunctionSymbol.Unary.SafeUnsignedNarrow -> null // will come later
                is NonSMTInterpretedFunctionSymbol.Binary.AssignEq ->
                    exp.copy(f = TheoryFunctionSymbol.Binary.Eq(exp.f.tag))
                is NonSMTInterpretedFunctionSymbol.Binary.Sub -> sub(exp)
                is NonSMTInterpretedFunctionSymbol.Binary.Div ->
                    exp.copy(f = TheoryFunctionSymbol.BV.BvUDiv(lxf.commonBitsTag(exp.args)))
                is NonSMTInterpretedFunctionSymbol.Binary.Mod ->
                    exp.copy(f = TheoryFunctionSymbol.BV.BvURem(lxf.commonBitsTag(exp.args)))
                is NonSMTInterpretedFunctionSymbol.Binary.Exp ->
                    exp.copy(f = AxiomatizedFunctionSymbol.UninterpExp(exp.args.deriveTag()))
                is NonSMTInterpretedFunctionSymbol.Binary.ShiftLeft ->
                    exp.copy(f = TheoryFunctionSymbol.BV.BvShl(exp.lhs.tag as Tag.Bits))
                is NonSMTInterpretedFunctionSymbol.Binary.ShiftRightLogical ->
                    exp.copy(f = TheoryFunctionSymbol.BV.BvLshr(exp.lhs.tag as Tag.Bits))
                is NonSMTInterpretedFunctionSymbol.Binary.ShiftRightArithmetical ->
                    exp.copy(f = TheoryFunctionSymbol.BV.BvAshr(exp.lhs.tag as Tag.Bits))
                is NonSMTInterpretedFunctionSymbol.Binary.Lt ->
                    exp.copy(f = TheoryFunctionSymbol.BV.BvULt(exp.args.deriveTag()))
                is NonSMTInterpretedFunctionSymbol.Binary.Slt ->
                    exp.copy(f = TheoryFunctionSymbol.BV.BvSLt(exp.args.deriveTag()))
                is NonSMTInterpretedFunctionSymbol.Binary.Sle ->
                    exp.copy(f = TheoryFunctionSymbol.BV.BvSLe(exp.args.deriveTag()))
                is NonSMTInterpretedFunctionSymbol.Binary.Sgt ->
                    exp.copy(f = TheoryFunctionSymbol.BV.BvSGt(exp.args.deriveTag()))
                is NonSMTInterpretedFunctionSymbol.Binary.Sge ->
                    exp.copy(f = TheoryFunctionSymbol.BV.BvSGe(exp.args.deriveTag()))
                is NonSMTInterpretedFunctionSymbol.Binary.Gt ->
                    exp.copy(f = TheoryFunctionSymbol.BV.BvUGt(exp.args.deriveTag()))
                is NonSMTInterpretedFunctionSymbol.Binary.Le ->
                    exp.copy(f = TheoryFunctionSymbol.BV.BvULe(exp.args.deriveTag()))
                is NonSMTInterpretedFunctionSymbol.Binary.Ge ->
                    exp.copy(f = TheoryFunctionSymbol.BV.BvUGe(exp.args.deriveTag()))
                is NonSMTInterpretedFunctionSymbol.Binary.BitwiseAnd ->
                    exp.copy(f = TheoryFunctionSymbol.BV.BvAnd(exp.args.deriveTag()))
                is NonSMTInterpretedFunctionSymbol.Binary.BitwiseOr ->
                    exp.copy(f = TheoryFunctionSymbol.BV.BvOr(exp.args.deriveTag()))
                is NonSMTInterpretedFunctionSymbol.Binary.BitwiseXor ->
                    exp.copy(f = TheoryFunctionSymbol.BV.BvXOr(exp.args.deriveTag()))
                is NonSMTInterpretedFunctionSymbol.Binary.NoAddOverflow ->
                    // translating to x + y >= x
                    lxf { (exp.lhs bvAdd exp.rhs) bvUGe exp.lhs }.lift()
                is NonSMTInterpretedFunctionSymbol.Binary.NoMulOverflow ->
                    if (useSmtBuiltinOverflowChecks) {
                        ApplyExpr(
                            TheoryFunctionSymbol.Unary.LNot,
                            exp.copy(f = TheoryFunctionSymbol.BV.BvUMulO(exp.lhs.tag as Tag.Bits))
                        )
                    } else {
                        exp.copy(f = AxiomatizedFunctionSymbol.BvOverflowChecks.NoUMulOverflow(Tag.Bit256))
                    }
                is NonSMTInterpretedFunctionSymbol.Binary.NoSMulOverUnderflow ->
                    if (useSmtBuiltinOverflowChecks) {
                        ApplyExpr(
                            TheoryFunctionSymbol.Unary.LNot,
                            exp.copy(f = TheoryFunctionSymbol.BV.BvSMulO(exp.lhs.tag as Tag.Bits))
                        )
                    } else {
                        exp.copy(f = AxiomatizedFunctionSymbol.BvOverflowChecks.NoSMulOverUnderflow(Tag.Bit256))
                    }
                is NonSMTInterpretedFunctionSymbol.Binary.NoSAddOverUnderflow ->
                    if (useSmtBuiltinOverflowChecks) {
                        ApplyExpr(
                            TheoryFunctionSymbol.Unary.LNot,
                            exp.copy(f = TheoryFunctionSymbol.BV.BvSAddO(exp.lhs.tag as Tag.Bits))
                        )
                    } else {
                        exp.copy(f = AxiomatizedFunctionSymbol.BvOverflowChecks.NoSAddOverUnderflow(Tag.Bit256))
                    }
                is NonSMTInterpretedFunctionSymbol.Binary.NoSSubOverUnderflow ->
                    if (useSmtBuiltinOverflowChecks) {
                        ApplyExpr(
                            TheoryFunctionSymbol.Unary.LNot,
                            exp.copy(f = TheoryFunctionSymbol.BV.BvSSubO(exp.lhs.tag as Tag.Bits))
                        )
                    } else {
                        exp.copy(f = AxiomatizedFunctionSymbol.BvOverflowChecks.NoSSubOverUnderflow(Tag.Bit256))
                    }
                is NonSMTInterpretedFunctionSymbol.Binary.Concat ->
                    exp.copy(f = TheoryFunctionSymbol.BV.Concat(exp.f.left, exp.f.right))

                is NonSMTInterpretedFunctionSymbol.Vec.Add -> vecAdd(exp, exp.args.deriveTag())
                is NonSMTInterpretedFunctionSymbol.Vec.Mul -> vecMul(exp, exp.args.deriveTag())
                is NonSMTInterpretedFunctionSymbol.Hash -> null
                is NonSMTInterpretedFunctionSymbol.MultiDimArray -> null

                /**
                 * MulMod(a,b,n) is defined as (a*b) mod n where (a*b) is precise (integer) multiplication, i.e.,
                 * without an overflow. To achieve this with BV, we first extend a, b, and n to 512 bit width,
                 * then perform the multiplication and modulo, and then reduce back to 256 bit width.
                 */
                is NonSMTInterpretedFunctionSymbol.Ternary.MulMod -> lxf {
                    fun LExpression.as512() = lit256(0) bvConcat this
                    val mulmod = (exp.args[0].as512() bvMul exp.args[1].as512()) bvURem exp.args[2].as512()
                    applyExp(TheoryFunctionSymbol.BV.BvExtractLower256From512, mulmod)
                }.lift()

                /**
                 * AddMod(a,b,n) is defined as (a+b) mod n where (a+b) is precise (integer) addition, i.e.,
                 * without an overflow. To achieve this with BV, we first extend a, b, and n to 512 bit width,
                 * then perform the addition and modulo, and then reduce back to 256 bit width.
                 * Note that 257 bits (or 264 bits) would be sufficient, and we might want to change this.
                 */
                is NonSMTInterpretedFunctionSymbol.Ternary.AddMod -> lxf {
                    fun LExpression.as512() = lit256(0) bvConcat this
                    val addmod = (exp.args[0].as512() bvAdd exp.args[1].as512()) bvURem exp.args[2].as512()
                    applyExp(TheoryFunctionSymbol.BV.BvExtractLower256From512, addmod)
                }.lift()
            }
            is TheoryFunctionSymbol -> when (exp.f) {
                is TheoryFunctionSymbol.Array -> null
                is TheoryFunctionSymbol.BV -> null

                is TheoryFunctionSymbol.Unary.Is ->
                    exp.copy(f = exp.f.copy(constructor = normalizeSKeyDtFS(exp.f.constructor)))
                is TheoryFunctionSymbol.Unary -> null

                is TheoryFunctionSymbol.Binary.LImplies -> null
                is TheoryFunctionSymbol.Binary.IntDiv ->
                    exp.copy(f = TheoryFunctionSymbol.BV.BvUDiv(exp.args.deriveTag()))
                is TheoryFunctionSymbol.Binary.IntSub -> sub(exp)
                is TheoryFunctionSymbol.Binary.IntMod ->
                    exp.copy(f = TheoryFunctionSymbol.BV.BvURem(exp.args.deriveTag()))
                is TheoryFunctionSymbol.Binary.IntLt ->
                    exp.copy(f = TheoryFunctionSymbol.BV.BvULt(exp.args.deriveTag()))
                is TheoryFunctionSymbol.Binary.IntGt ->
                    exp.copy(f = TheoryFunctionSymbol.BV.BvUGt(exp.args.deriveTag()))
                is TheoryFunctionSymbol.Binary.IntLe ->
                    exp.copy(f = TheoryFunctionSymbol.BV.BvULe(exp.args.deriveTag()))
                is TheoryFunctionSymbol.Binary.IntGe ->
                    exp.copy(f = TheoryFunctionSymbol.BV.BvUGe(exp.args.deriveTag()))
                is TheoryFunctionSymbol.Binary.Eq -> null

                is TheoryFunctionSymbol.Ternary.Ite ->
                    exp.copy(f = TheoryFunctionSymbol.Ternary.Ite(exp.f.tag.toBV()))

                is TheoryFunctionSymbol.Vec.LAnd -> null
                is TheoryFunctionSymbol.Vec.LOr -> null
                is TheoryFunctionSymbol.Vec.IntAdd -> vecAdd(exp, Tag.Bit256)
                is TheoryFunctionSymbol.Vec.IntMul -> vecMul(exp, Tag.Bit256)
            }

            is ConstantSymbol -> null
            is UserDefinedFunctionSymbol -> null
            is ArraySelectFunctionSymbol.OneDim ->
                exp.copy(f = exp.f.copy(signature = exp.f.signature.transform { it.toBV() }))
            is ArraySelectFunctionSymbol.MultiDim ->
                exp.copy(f = exp.f.copy(signature = exp.f.signature.transform { it.toBV() }))
            is AxiomatizedFunctionSymbol -> when (exp.f) {
                is AxiomatizedFunctionSymbol.SKeyDt -> exp.copy(f = normalizeSKeyDtFS(exp.f))
                is AxiomatizedFunctionSymbol.Hash.SimpleHashN ->
                    exp.copy(f = exp.f.copy(tag = exp.f.tag.toBV()))
                is AxiomatizedFunctionSymbol.Bitwise.SignExtend -> lxf {
                    val tag = exp.arg.tag as Tag.Bits
                    val bits = (exp.f.i + 1) * 8
                    val lowOnes = BigInteger.TWO.pow(bits) - BigInteger.ONE
                    val onlyLows = exp.arg bvAnd lit(lowOnes, tag)
                    ite(
                        onlyLows bvULt lit(twoToThe(bits - 1), tag),
                        onlyLows,
                        exp.arg bvOr lit(MAX_EVM_UINT256 - lowOnes, tag)
                    )
                }.lift()
                is AxiomatizedFunctionSymbol.UninterpExp ->
                    if (exp.lhs.asConstOrNull?.isPowOf2 == true) {
                        lxf {
                            val tag = exp.lhs.tag as Tag.Bits
                            when(val m = exp.lhs.asConst.lowestSetBit) {
                                // 0 means m is 2. It is the same as the "else" case, but `m=1` so no need for multiplication.
                                0 -> lit(1, tag) bvShl exp.rhs
                                else -> lit(1, tag) bvShl (lit(m, tag) bvMul exp.rhs)
                            }
                        }.lift()
                    } else {
                        exp.copy(f = exp.f.copy(tag = exp.f.tag.toBV()))
                    }
                else -> null
            }
            else -> null
        }
    }

    fun normalizeSKeyDtFS(f: AxiomatizedFunctionSymbol.SKeyDt): AxiomatizedFunctionSymbol.SKeyDt = when (f) {
        is AxiomatizedFunctionSymbol.SKeyDt.Basic -> f.copy(tag = f.tag.toBV())
        is AxiomatizedFunctionSymbol.SKeyDt.SkeyNode -> f
        is AxiomatizedFunctionSymbol.SKeyDt.SkeyAdd -> f.copy(tag = f.tag.toBV())
        is AxiomatizedFunctionSymbol.SKeyDt.ToSkey -> f.copy(tag = f.tag.toBV())
        is AxiomatizedFunctionSymbol.SKeyDt.FromSkey -> f.copy(tag = f.tag.toBV())
        is AxiomatizedFunctionSymbol.SKeyDt.SKeySelector ->
            f.copy(tag = f.tag.toBV(), constructor = normalizeSKeyDtFS(f.constructor))
    }

    override fun normalizeFunctionSymbol(fs: FunctionSymbol): FunctionSymbol = when (fs) {
        is AxiomatizedFunctionSymbol.SKeyDt -> normalizeSKeyDtFS(fs)

        is AxiomatizedFunctionSymbol.DefFunc ->
            fs.copy(signature = fs.signature.transform { it.toBV() })
        else -> fs
    }
}
