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

package analysis.opt

import analysis.numeric.MAX_UINT
import analysis.opt.ConstantPropagatorAndSimplifier.Companion.simplifyTop
import datastructures.stdcollections.*
import log.*
import tac.NBId
import tac.Tag
import tac.commonSuperTag
import utils.*
import vc.data.*
import vc.data.tacexprutil.ExprUnfolder.Companion.unfoldToExprPlusOneCmd
import vc.data.tacexprutil.TACExprUtils.contains
import vc.data.tacexprutil.TACExprUtils.eqTo
import vc.data.tacexprutil.TACExprUtils.isFalse
import vc.data.tacexprutil.TACExprUtils.isTrue
import vc.data.tacexprutil.asConstOrNull
import vc.data.tacexprutil.eval
import vc.data.tacexprutil.getFreeVars
import vc.data.tacexprutil.rebuild
import verifier.PatchingProgramWrapper
import java.math.BigInteger

private val logger = Logger(LoggerTypes.PROPAGATOR_SIMPLIFIER)

/**
 * Does a light weight constant propagation and simplification pass separately on each block. See [simplifyTop] for
 * more details.
 * It actually does a little more than run separately run on each block, it also uses domination information, and uses
 * constants it found in previous blocks in the common case where a variable has one definition site and it is
 * dominating.
 *
 * [handleLeinoVars] should be true when this is called late in the pipeline, as these necessitate special treatment
 * if this optimization erases blocks.
 */
class ConstantPropagatorAndSimplifier(val code: CoreTACProgram, private val handleLeinoVars: Boolean = false) {

    private val g = code.analysisCache.graph
    private val dom = code.analysisCache.domination
    private val singleDefSites = buildMap {
        val badVars = mutableSetOf<TACSymbol.Var>()
        g.commands
            .mapNotNull { (ptr, cmd) -> ptr `to?` cmd.getLhs() }
            .filter { (_, lhs) -> lhs !in badVars }
            .forEach { (ptr, lhs) ->
                if (lhs in this) {
                    this -= lhs
                    badVars += lhs
                } else {
                    this[lhs] = ptr.block
                }
            }
    }

    private val blockConstants = mutableMapOf<NBId, Map<TACSymbol.Var, BigInteger>>()

    fun rewrite(): CoreTACProgram {
        val patcher = PatchingProgramWrapper(code)
        code.topoSortFw.forEach { nbid ->
            blockConstants[nbid] = BlockWorker(nbid, patcher).process()
        }
        logger.trace {
            patcher.debugPrinter().toString(code, javaClass.simpleName)
        }
        //patcher.debugPrinter().print(code)
        return patcher.toCode(handleLeinoVars)
    }

    private inner class BlockWorker(val nbid: NBId, val patcher: PatchingProgramWrapper) {
        private val constants = mutableMapOf<TACSymbol.Var, BigInteger>()

        private fun getPrevConstant(v: TACSymbol.Var) =
            singleDefSites[v]
                ?.takeIf { it != nbid }
                ?.takeIf { dom.dominates(it, nbid) }
                ?.let { blockConstants[it]!![v] }

        private fun simplifyExp(exp: TACExpr): TACExpr {
            when (exp) {
                is TACExpr.Sym.Const -> return exp
                is TACExpr.Sym.Var -> return (constants[exp.s] ?: getPrevConstant(exp.s))?.asTACExpr(exp.s.tag) ?: exp
                is TACExpr.QuantifiedFormula ->
                    if (exp.quantifiedVars.any { it in constants }) {
                        return exp
                    }

                else -> Unit
            }
            return simplifyTop(
                exp.rebuild(exp.getOperands().map(::simplifyExp))
            )
        }

        private val mapper = object : DefaultTACCmdMapper() {
            override fun mapSymbol(t: TACSymbol): TACSymbol =
                when (t) {
                    is TACSymbol.Const -> t
                    is TACSymbol.Var -> (constants[t] ?: getPrevConstant(t))?.asTACSymbol(t.tag) ?: t
                }
        }

        fun process(): Map<TACSymbol.Var, BigInteger> {
            g.lcmdSequence(nbid).forEach { (ptr, cmd) ->
                when (cmd) {
                    is TACCmd.Simple.AssigningCmd.AssignExpCmd -> {
                        val newRhs = simplifyExp(cmd.rhs)
                        if (newRhs != cmd.rhs) {
                            if (newRhs.contains { it is TACExpr.QuantifiedFormula || it is TACExpr.MapDefinition }) {
                                patcher.replace(ptr, cmd.copy(rhs = newRhs))
                            } else {
                                patcher.replace(
                                    ptr,
                                    unfoldToExprPlusOneCmd("t", newRhs) {
                                        cmd.copy(rhs = it)
                                    })
                            }
                        }
                        newRhs.getAsConst()
                            ?.let { constants[cmd.lhs] = it }
                            ?: constants.remove(cmd.lhs)
                    }

                    is TACCmd.Simple.AssumeExpCmd -> {
                        val newCond = simplifyExp(cmd.cond)
                        if (newCond != cmd.cond) {
                            patcher.replace(ptr, cmd.copy(cond = newCond))
                        }
                    }

                    is TACCmd.Simple.Assume -> {
                        if (cmd.condExpr !is TACExpr.Sym.Const) {
                            val newCond = simplifyExp(cmd.condExpr)
                            val description = (cmd as? TACCmd.Simple.AssumeCmd)?.msg.orEmpty()
                            newCond.getAsConst()?.let {
                                patcher.replace(
                                    ptr,
                                    TACCmd.Simple.AssumeCmd(cond = it.asBoolTACSymbol(), msg = description)
                                )
                            }
                        }
                    }

                    is TACCmd.Simple.JumpiCmd -> {
                        val newCond = simplifyExp(cmd.cond.asSym())
                        when (newCond.getAsConst()) {
                            BigInteger.ONE -> patcher.jumpiTojump(ptr.block, cmd.dst)
                            BigInteger.ZERO -> patcher.jumpiTojump(ptr.block, cmd.elseDst)
                        }
                    }

                    is TACCmd.Simple.AssertCmd -> {
                        if (cmd.o !is TACSymbol.Const) {
                            val newCond = simplifyExp(cmd.o.asSym())
                            newCond.getAsConst()?.let {
                                patcher.replace(ptr, cmd.copy(o = it.asBoolTACSymbol()))
                            }
                        }
                    }

                    is TACCmd.Simple.StorageAccessCmd, // because inlining constants will erase important metas
                    is TACCmd.Simple.AnnotationCmd
                        -> Unit

                    else -> mapper.map(cmd).takeIf { it != cmd }?.let {
                        patcher.replace(ptr, it)
                    }
                }
            }
            return constants
        }
    }


    companion object {

        val TACExpr.defaultTag: Tag
            get() = when (this) {
                is TACExpr.QuantifiedFormula,
                is TACExpr.BinRel,
                is TACExpr.BinBoolOp,
                is TACExpr.UnaryExp.LNot ->
                    Tag.Bool

                is TACExpr.BinOp.BWAnd,
                is TACExpr.BinOp.BWOr,
                is TACExpr.BinOp.BWXOr,
                is TACExpr.UnaryExp.BWNot,
                is TACExpr.BinOp.Div,
                is TACExpr.BinOp.Exponent,
                is TACExpr.BinOp.Mod,
                is TACExpr.BinOp.SDiv,
                is TACExpr.BinOp.SMod,
                is TACExpr.BinOp.ShiftLeft,
                is TACExpr.BinOp.ShiftRightArithmetical,
                is TACExpr.BinOp.ShiftRightLogical,
                is TACExpr.BinOp.SignExtend,
                is TACExpr.BinOp.Sub,
                is TACExpr.TernaryExp.AddMod,
                is TACExpr.TernaryExp.MulMod,
                is TACExpr.Vec.Add,
                is TACExpr.Vec.Mul ->
                    Tag.Bit256

                is TACExpr.BinOp.IntDiv,
                is TACExpr.BinOp.IntExponent,
                is TACExpr.BinOp.IntMod,
                is TACExpr.BinOp.IntSub,
                is TACExpr.Vec.IntAdd,
                is TACExpr.Vec.IntMul ->
                    Tag.Int

                is TACExpr.AnnotationExp<*> -> o.tag ?: o.defaultTag
                is TACExpr.TernaryExp.Ite -> commonSuperTag(e.tag ?: e.defaultTag, t.tag ?: t.defaultTag)

                else -> error("No default tag for $this")
            }

        private val txf = TACExprFactTypeCheckedOnlyPrimitives

        /**
         * A standalone function for simplifying expressions. It does constant reasoning, but a bit more, e.g.:
         * ```
         *    a + 0                 ~~~>    a
         *    ite(a, a, b)          ~~~>    a \/ b
         *    k signExtend maxUint  ~~~>    maxUint
         * ```
         * It simplifies the top level expression only (so best practice is to first simplify the operands and
         * then call this).
         */
        fun simplifyTop(e: TACExpr) = simplifyTopOrNull(e) ?: e

        /**
         * Given a possibly partial list of constant values for the operands of [e], tries to calculate a constant
         * value for [e]. If it can't, returns null.
         */
        fun calculateOrNull(e: TACExpr, opValues: List<BigInteger?>) =
            e.rebuild(
                e.getOperands().zip(opValues).map { (op, value) ->
                    value?.asTACExpr(op.tagAssumeChecked) ?: op
                }
            ).let(::simplifyTopOrNull)?.asConstOrNull


        /** returns the simplified expression, or null if it is unchanged */
        fun simplifyTopOrNull(exp: TACExpr): TACExpr? {
            if (exp is TACExpr.Sym) {
                return null
            }

            val tag = exp.tag ?: exp.defaultTag
            fun BigInteger.asExpr() = asTACExpr(tag)
            fun Int.asExpr() = toBigInteger().asExpr()

            val ops = exp.getOperands()
            exp.eval(ops, TACExpr::getAsConst)?.let {
                return it.asExpr()
            }

            return with(txf) {
                when (exp) {

                    is TACExpr.UnaryExp -> {
                        val op = ops.single()
                        when (exp) {
                            is TACExpr.UnaryExp.BWNot -> when {
                                op is TACExpr.UnaryExp.BWNot -> op.o
                                else -> null
                            }

                            is TACExpr.UnaryExp.LNot -> when {
                                op is TACExpr.UnaryExp.LNot -> op.o
                                else -> null
                            }
                        }
                    }

                    is TACExpr.TernaryExp.Ite -> {
                        val (i, t, e) = ops
                        when {
                            i.isTrue -> t
                            i.isFalse -> e
                            t == e -> t
                            tag is Tag.Bool ->
                                when {
                                    t.isTrue && e.isFalse -> i
                                    t.isFalse && e.isTrue -> LNot(i)
                                    t.isTrue -> LOr(i, e)
                                    e.isTrue -> LOr(simplifyTop(LNot(i)), t)
                                    t.isFalse -> LAnd(simplifyTop(LNot(i)), e)
                                    e.isFalse -> LAnd(i, t)
                                    i == t -> LOr(i, e)
                                    i == e -> LAnd(i, t)
                                    else -> null
                                }?.let(::simplifyTop)

                            else -> null
                        }
                    }

                    is TACExpr.TernaryExp.AddMod -> {
                        val (a, b, m) = ops
                        when {
                            m eqTo 0 -> 0.asExpr()
                            a eqTo 0 -> simplifyTop(Mod(b, m))
                            b eqTo 0 -> simplifyTop(Mod(a, m))
                            a is TACExpr.Sym.Const && m is TACExpr.Sym.Const ->
                                AddMod((a.s.value % m.s.value).asExpr(), b, m)

                            b is TACExpr.Sym.Const && m is TACExpr.Sym.Const ->
                                AddMod(a, (b.s.value % m.s.value).asExpr(), m)

                            else -> null
                        }
                    }

                    is TACExpr.TernaryExp.MulMod -> {
                        val (a, b, m) = ops
                        when {
                            a eqTo 0 || b eqTo 0 || m eqTo 0 -> 0.asExpr()
                            a eqTo 1 -> simplifyTop(Mod(b, m))
                            b eqTo 1 -> simplifyTop(Mod(a, m))
                            a is TACExpr.Sym.Const && m is TACExpr.Sym.Const ->
                                MulMod((a.s.value % m.s.value).asExpr(), b, m)

                            b is TACExpr.Sym.Const && m is TACExpr.Sym.Const ->
                                MulMod(a, (b.s.value % m.s.value).asExpr(), m)

                            else -> null
                        }
                    }

                    is TACExpr.BinRel -> {
                        val (o1, o2) = ops
                        val tag1 = o1.tagAssumeChecked
                        val tag2 = o2.tagAssumeChecked
                        when (exp) {
                            is TACExpr.BinRel.Eq ->
                                when {
                                    o1 == o2 -> True
                                    tag1 == Tag.Bool && tag2 == Tag.Bool -> when {
                                        o1.isTrue -> o2
                                        o2.isTrue -> o1
                                        o1.isFalse -> simplifyTop(LNot(o2))
                                        o2.isFalse -> simplifyTop(LNot(o1))
                                        else -> null
                                    }

                                    else -> null
                                }

                            is TACExpr.BinRel.Ge,
                            is TACExpr.BinRel.Lt,
                            is TACExpr.BinRel.Le,
                            is TACExpr.BinRel.Gt,
                            is TACExpr.BinRel.Sge,
                            is TACExpr.BinRel.Sgt,
                            is TACExpr.BinRel.Sle,
                            is TACExpr.BinRel.Slt -> runIf(
                                (o1 is TACExpr.Sym.Const && tag2 is Tag.Bits) ||
                                    (o2 is TACExpr.Sym.Const && tag1 is Tag.Bits)
                            ) {
                                fun minMax(op: TACExpr) = (op.tagAssumeChecked as Tag.Bits).let { tag ->
                                    when {
                                        exp is TACExpr.BinRel.Ge || exp is TACExpr.BinRel.Lt ||
                                            exp is TACExpr.BinRel.Le || exp is TACExpr.BinRel.Gt ->
                                            BigInteger.ZERO to tag.maxUnsigned

                                        op.tag is Tag.Bits -> tag.minSigned2s to tag.maxSigned
                                        else -> `impossible!`
                                    }
                                }
                                when {
                                    o1 is TACExpr.Sym.Const -> {
                                        val (min, max) = minMax(o2)
                                        val x = exp.eval(o1.s.value, min)
                                        x.runIf(x == exp.eval(o1.s.value, max)) {
                                            x.asBoolTACExpr
                                        }
                                    }

                                    o2 is TACExpr.Sym.Const -> {
                                        val (min, max) = minMax(o1)
                                        val x = exp.eval(min, o2.s.value)
                                        x.runIf(x == exp.eval(max, o2.s.value)) {
                                            x.asBoolTACExpr
                                        }
                                    }

                                    else -> `impossible!`
                                }

                            }
                        }
                    }


                    is TACExpr.BinBoolOp.LOr -> {
                        if (ops.any { it.isTrue }) {
                            return True
                        }
                        val filtered = ops.filterNot { it.isFalse }.distinct()
                        when (filtered.size) {
                            0 -> txf.False
                            1 -> filtered.single()
                            ops.size -> null
                            else -> txf.LOr(filtered)
                        }
                    }

                    is TACExpr.BinBoolOp.LAnd -> {
                        if (ops.any { it.isFalse }) {
                            return False
                        }
                        val filtered = ops.filterNot { it.isTrue }.distinct()
                        when (filtered.size) {
                            0 -> txf.True
                            1 -> filtered.single()
                            ops.size -> null
                            else -> txf.LAnd(filtered)
                        }
                    }

                    is TACExpr.Vec.Add, is TACExpr.Vec.IntAdd -> {
                        val (consts, exprs) = ops.partition { it is TACExpr.Sym.Const }
                        val c = exp.eval(consts.map { it.getAsConst()!! })!!
                        val newOps = exprs.letIf(c != BigInteger.ZERO) { it + c.asExpr() }
                        when (newOps.size) {
                            0 -> 0.asExpr()
                            1 -> newOps.single()
                            ops.size -> null
                            else -> exp.rebuild(newOps)
                        }
                    }

                    is TACExpr.Vec.Mul, is TACExpr.Vec.IntMul -> {
                        val (consts, exprs) = ops.partition { it is TACExpr.Sym.Const }
                        val c = exp.eval(consts.map { it.getAsConst()!! })!!
                        if (c == BigInteger.ZERO) {
                            0.asExpr()
                        } else {
                            val newOps = exprs.letIf(c != BigInteger.ONE) { it + c.asExpr() }
                            when (newOps.size) {
                                0 -> 1.asExpr()
                                1 -> newOps.single()
                                ops.size -> null
                                else -> exp.rebuild(newOps)
                            }
                        }
                    }

                    is TACExpr.BinOp -> {
                        val (o1, o2) = ops
                        fun max1() = (o1.tagAssumeChecked as? Tag.Bits)?.maxUnsigned!!
                        fun max2() = (o2.tagAssumeChecked as? Tag.Bits)?.maxUnsigned!!

                        when (exp) {
                            is TACExpr.BinOp.BWAnd -> when {
                                o1 == o2 -> o1
                                o1 eqTo max2() -> o2
                                o2 eqTo max1() -> o1
                                o1 eqTo 0 || o2 eqTo 0 -> 0.asExpr()
                                else -> null
                            }

                            is TACExpr.BinOp.BWOr -> when {
                                o1 == o2 -> o1
                                o1 eqTo 0 -> o2
                                o2 eqTo 0 -> o1
                                o1 eqTo max2() -> max2().asExpr()
                                o2 eqTo max1() -> max1().asExpr()
                                else -> null
                            }

                            is TACExpr.BinOp.BWXOr -> when {
                                o1 == o2 -> 0.asExpr()
                                o1 eqTo 0 -> o2
                                o2 eqTo 0 -> o1
                                o1 eqTo max2() -> simplifyTop(BWNot(o2))
                                o2 eqTo max1() -> simplifyTop(BWNot(o1))
                                else -> null
                            }

                            is TACExpr.BinOp.Div, is TACExpr.BinOp.IntDiv, is TACExpr.BinOp.SDiv -> when {
                                o1 eqTo 0 || o2 eqTo 0 -> 0.asExpr()
                                o1 == o2 -> 1.asExpr()
                                o2 eqTo 1 -> o1
                                else -> null
                            }

                            is TACExpr.BinOp.Exponent, is TACExpr.BinOp.IntExponent -> when {
                                o2 eqTo 0 -> 1.asExpr()
                                o1 eqTo 0 -> 0.asExpr()
                                o1 eqTo 1 -> 1.asExpr()
                                o2 eqTo 1 -> o1
                                else -> null
                            }

                            is TACExpr.BinOp.IntSub, is TACExpr.BinOp.Sub -> when {
                                o1 == o2 -> 0.asExpr()
                                o2 eqTo 0 -> o1
                                else -> null
                            }

                            is TACExpr.BinOp.IntMod, is TACExpr.BinOp.Mod, is TACExpr.BinOp.SMod -> when {
                                o1 == o2 -> 0.asExpr()
                                o2 eqTo 0 || o2 eqTo 1 -> 0.asExpr()
                                o1 eqTo 1 -> 1.asExpr()
                                else -> null
                            }

                            is TACExpr.BinOp.ShiftLeft -> when {
                                o1 eqTo 0 -> 0.asExpr()
                                o2 eqTo 0 -> o1
                                o2.getAsConst()?.toIntOrNull()
                                    ?.let { it >= (o1.tag as? Tag.Bits)?.bitwidth!! } == true -> 0.asExpr()

                                else -> null
                            }

                            is TACExpr.BinOp.ShiftRightLogical -> when {
                                o1 eqTo 0 -> 0.asExpr()
                                o2 eqTo 0 -> o1
                                o2.getAsConst()?.toIntOrNull()
                                    ?.let { it >= (o1.tag as? Tag.Bits)?.bitwidth!! } == true -> 0.asExpr()

                                else -> null
                            }

                            is TACExpr.BinOp.ShiftRightArithmetical -> when {
                                o2 eqTo 0 -> o1
                                o1 eqTo 0 || o1 eqTo max1() -> o1
                                else -> null
                            }

                            is TACExpr.BinOp.SignExtend -> when {
                                o1 eqTo ((o2.tag as Tag.Bits).bitwidth / 8 - 1) -> o2
                                o2 eqTo max2() || o2 eqTo 0 -> o2
                                else -> null
                            }
                        }
                    }

                    is TACExpr.Apply ->
                        when ((exp.f as? TACExpr.TACFunctionSym.BuiltIn)?.bif) {
                            is TACBuiltInFunction.TwosComplement.Wrap -> {
                                val o = ops[0]
                                if (o is TACExpr.Apply &&
                                    (o.f as? TACExpr.TACFunctionSym.BuiltIn)?.bif is TACBuiltInFunction.TwosComplement.Unwrap
                                ) {
                                    o.ops[0]
                                } else {
                                    null
                                }
                            }

                            is TACBuiltInFunction.TwosComplement.Unwrap -> {
                                val o = ops[0]
                                if (o is TACExpr.Apply &&
                                    (o.f as? TACExpr.TACFunctionSym.BuiltIn)?.bif is TACBuiltInFunction.TwosComplement.Wrap
                                ) {
                                    o.ops[0]
                                } else {
                                    null
                                }
                            }

                            is TACBuiltInFunction.NoSAddOverAndUnderflowCheck,
                            is TACBuiltInFunction.NoAddOverflowCheck -> when {
                                ops[0] eqTo 0 || ops[1] eqTo 0 -> True
                                else -> null
                            }

                            is TACBuiltInFunction.NoSSubOverAndUnderflowCheck -> when {
                                ops[1] eqTo 0 -> True // `0 - minInt` actually overflows.
                                ops[0] eqTo MAX_UINT -> True // bizarre but true, `-1 - x` will never overflow
                                else -> null
                            }

                            is TACBuiltInFunction.NoMulOverflowCheck,
                            is TACBuiltInFunction.NoSMulOverAndUnderflowCheck -> when {
                                ops[0] eqTo 0 || ops[1] eqTo 0 -> True
                                ops[0] eqTo 1 || ops[1] eqTo 1 -> True
                                else -> null
                            }

                            is TACBuiltInFunction.SafeMathPromotion ->
                                ops.single()

                            else -> null
                        }

                    is TACExpr.QuantifiedFormula -> {
                        val quantifiedVars = exp.quantifiedVars.filter { it in exp.body.getFreeVars() }
                        when (quantifiedVars.size) {
                            0 -> exp.body
                            exp.quantifiedVars.size -> null
                            else -> exp.copy(quantifiedVars = quantifiedVars)
                        }
                    }

                    is TACExpr.StructAccess -> with(exp) {
                        if (struct is TACExpr.StructConstant) {
                            struct.fieldNameToValue[fieldName]
                                ?.let { rhs ->
                                    val expectedTag =
                                        (struct.tag as Tag.UserDefined.Struct).getField(exp.fieldName)!!.type
                                    check(rhs.tag == expectedTag)
                                    rhs
                                }
                        } else {
                            null
                        }
                    }

                    else -> null
                }
            }
        }
    }

}
