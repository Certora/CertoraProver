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

package analysis.patterns

import analysis.*
import analysis.PatternMatcher.Pattern
import analysis.PatternMatcher.Pattern.AnySymbol.Companion.anyLSymbol
import analysis.PatternMatcher.Pattern.AnySymbol.Companion.anySymbol
import analysis.PatternMatcher.Pattern.FromConstant.Companion.withPredicate
import analysis.PatternMatcher.Pattern.FromVar.Companion.anyLVar
import analysis.PatternMatcher.Pattern.FromVar.Companion.anyVar
import analysis.patterns.Info.Companion.joinWith
import analysis.patterns.Info.Companion.lastCmd
import analysis.patterns.Info.Companion.set
import analysis.patterns.Info.Companion.setLastCmd
import analysis.patterns.InfoKey.*
import analysis.patterns.PatternHelpers.sha3
import evm.MAX_EVM_UINT256
import tac.Tag
import utils.*
import vc.data.TACBuiltInFunction
import vc.data.TACCmd
import vc.data.TACExpr
import vc.data.TACSymbol
import java.math.BigInteger

private typealias PI = Pattern<Info>

/**
 * Helper functions defined on top of the [PatternMatcher]. The patterns here all return [Info] or null,
 * joining and collecting information along the matched pattern, thus checking for discrepancies.
 *
 * Patterns that are not symbol patterns add the location of the [LTACCmd] to the [Info] in question (the only exception
 * is [sha3] which doesn't save it (this is ok because we don't need it in the list).
 */
object PatternHelpers {

    operator fun invoke(pattern: PatternHelpers.() -> PI) = this.pattern()

    fun <T, R : Any> Pattern<T>.mapResult2(map : (T, LTACCmd?) -> R?) : Pattern<R> =
        Pattern.MapNotNull(
            wrapped = this,
            map = map,
            patternName = "AndAlso"
        )

    fun <T, R : Any> Pattern<T>.mapResult(map : (T) -> R?) : Pattern<R> =
        mapResult2 { t, _ -> map(t) }

    /**
     * add a transformation of [Info] on top of a pattern. For easier usage, [extra] is an extension
     * function, so the generated info can be accessed directly as [this].
     */
    fun PI.andDo(extra: Info.() -> Info?) =
        this.mapResult(extra)

    fun PI.andDo2(extra: Info.(LTACCmd) -> Info?) =
        this.mapResult2 { info, lcmd ->
            info.extra(lcmd!!)
        }

    private fun PI.setLastCmd() = andDo2 { setLastCmd(it) }

    /** Creates an [Info] for a variable and sets the [key] if not null to the variable */
    fun v(key: InfoKey<TACSymbol.Var>? = null) =
        anyVar.mapResult { v -> Info(key, v) }

    fun sym(key: InfoKey<TACSymbol>? = null) =
        anySymbol.mapResult { s -> Info(key, s) }

    fun lVar(key: InfoKey<LTACVar>? = null) =
        anyLVar.mapResult { v -> Info(key, v) }

    fun lSym(key: InfoKey<LTACSymbol>? = null) =
        anyLSymbol.mapResult { s -> Info(key, s) }

    private fun unaryApply(bif : TACBuiltInFunction, p1 : PI) =
        Pattern.FromUnaryApply(
            extractor = { _, u ->
                runIf((u.f as? TACExpr.TACFunctionSym.BuiltIn)?.bif == bif) {
                    (u.ops.single() as? TACExpr.Sym)?.s
                }
            },
            inner = p1,
            out = { _, i -> i }
        )

    fun safeMathNarrow(p1: PI) = unaryApply(TACBuiltInFunction.SafeMathNarrow.Implicit(Tag.Bit256), p1)

    fun maybeNarrow(p1: PI) = p1 OR safeMathNarrow(p1)

    inline fun <reified T : Tag> lSymTag(key: InfoKey<LTACSymbol>? = null) =
        anyLSymbol.mapResult { s -> runIf(s.symbol.tag is T) { Info(key, s) } }

    fun lSym256(key: InfoKey<LTACSymbol>) =
        lSymTag<Tag.Bit256>(key).onlyIf {
            // this is needed only because we sometimes create Tag.bv256 constant symbols with out of bounds
            // numbers. When that is fixed we can chuck this `onlyIf` clause
            get(key)!!.symbol.let {
                it !is TACSymbol.Const || (it.value >= BigInteger.ZERO && it.value <= MAX_EVM_UINT256)
            }
        }

    /** if `p(found constant)` is true, Creates a new [Info] with the found constant as the value of the given [key] */
    fun c(key: InfoKey<BigInteger>? = null, p : (BigInteger) -> Boolean = { true }) =
        withPredicate(p).mapResult { Info(key, it) }

    /** Like above, but only if the found constant is an [Int] */
    @JvmName("c1")
    fun c(key: InfoKey<Int>? = null, p : (Int) -> Boolean = { true }) =
        withPredicate { it.toIntOrNull()?.let(p) == true }.mapResult { Info(key, it.toInt()) }

    /**
     * Creates an empty [Info] for a sha3 command. It doesn't apply [setLastCmd] because for current use
     * case we don't want it there.
     */
    fun sha3() = Pattern.SimpleHash { Info() }

    /** For a storage read command matching location pattern [loc] */
    fun read(loc: PI) = Pattern.StorageRead(loc) { lcmd, info -> info.setLastCmd(lcmd) }

    fun bwNot(p1: PI) =
        Pattern.FromUnaryOp(
            extractor = { _, u ->
                ((u as? TACExpr.UnaryExp.BWNot)?.o as? TACExpr.Sym.Var)?.s
            },
            inner = p1,
            out = { _, i -> i }
        ).setLastCmd()

    operator fun PI.not() =
        Pattern.FromUnaryOp(
            extractor = { _, u ->
                ((u as? TACExpr.UnaryExp.LNot)?.o as? TACExpr.Sym.Var)?.s
            },
            inner = this,
            out = { _, i -> i }
        ).setLastCmd()

    /** One pattern or the other */
    @Suppress("FunctionName")
    infix fun PI.OR(p1: PI) = Pattern.Or(
        first = this,
        adapt1 = { it },
        second = p1,
        adapt2 = { it }
    )

    /** a non infix version with varargs */
    @Suppress("FunctionName")
    fun OR(vararg patterns: PI) = patterns.reduce { a, b -> a OR b }

    /** Joins the info gathered from all arguments */
    fun ite(c: PI, t: PI, e: PI) =
        Pattern.Ite(c, t, e) { lcmd, cInfo, tInfo, eInfo ->
            cInfo.joinWith(tInfo).joinWith(eInfo)?.setLastCmd(lcmd)
        }.mapResult { it }

    /** Combines two patterns, joining the resulting [Info]s */
    private fun <T : TACExpr.BinExp> combiner(op: Class<T>, p1: PI, p2: PI) =
        Pattern.FromBinOp.from(
            op,
            p1 = p1,
            p2 = p2,
            comb = { lcmd, i1, i2 ->
                i1.joinWith(i2)?.setLastCmd(lcmd)
            },
            patternName = op.simpleName
        ).mapResult { it }


    /** Combines two patterns when the operator at the base is commutative. Joins the resulting [Info]s */
    private fun <T : TACExpr.BinExp> commutativeCombiner(op: Class<T>, p1: PI, p2: PI) =
        Pattern.Or(
            first = Pattern.FromBinOp.from(
                op,
                p1 = p1,
                p2 = p2,
                comb = { lcmd, i1, i2 ->
                    i1.joinWith(i2)?.setLastCmd(lcmd)
                }
            ),
            second = Pattern.FromBinOp.from(
                op,
                p1 = p2,
                p2 = p1,
                comb = { lcmd, i1, i2 ->
                    i1.joinWith(i2)?.setLastCmd(lcmd).set(REVERSED(lcmd.ptr), true)
                }
            ),
            adapt1 = { it },
            adapt2 = { it }
        ).mapResult { it }

    // Shorthand functions and infix functions for using [combiner]

    infix fun PI.bwAnd(p2: PI) = commutativeCombiner(TACExpr.BinOp.BWAnd::class.java, this, p2)
    infix fun PI.bwOr(p2: PI) = commutativeCombiner(TACExpr.BinOp.BWOr::class.java, this, p2)
    infix fun PI.xor(p2: PI) = commutativeCombiner(TACExpr.BinOp.BWXOr::class.java, this, p2)
    infix fun PI.signExtend(p2: PI) = combiner(TACExpr.BinOp.SignExtend::class.java, this, p2)
    infix fun PI.exp(p2: PI) = combiner(TACExpr.BinOp.Exponent::class.java, this, p2)
    infix fun PI.shl(p2: PI) = combiner(TACExpr.BinOp.ShiftLeft::class.java, this, p2)
    infix fun PI.shr(p2: PI) = combiner(TACExpr.BinOp.ShiftRightLogical::class.java, this, p2)
    infix operator fun PI.times(p2: PI) = commutativeCombiner(TACExpr.Vec.Mul::class.java, this, p2)
    infix operator fun PI.rem(p2: PI) = combiner(TACExpr.BinOp.Mod::class.java, this, p2)
    infix operator fun PI.div(p2: PI) = combiner(TACExpr.BinOp.Div::class.java, this, p2)
    infix operator fun PI.minus(p2: PI) = combiner(TACExpr.BinOp.Sub::class.java, this, p2)
    infix operator fun PI.plus(p2: PI) = commutativeCombiner(TACExpr.Vec.Add::class.java, this, p2)
    infix fun PI.sDiv(p2: PI) = combiner(TACExpr.BinOp.SDiv::class.java, this, p2)

    infix fun PI.intMul(p2: PI) = commutativeCombiner(TACExpr.Vec.IntMul::class.java, this, p2)
    infix fun PI.anyMul(p2: PI) = (this * p2) OR (this intMul p2)
    infix fun PI.intAdd(p2: PI) = commutativeCombiner(TACExpr.Vec.IntAdd::class.java, this, p2)
    infix fun PI.anyAdd(p2: PI) = (this + p2) OR (this intAdd p2)
    infix fun PI.intDiv(p2: PI) = commutativeCombiner(TACExpr.BinOp.IntDiv::class.java, this, p2)

    infix fun PI.eq(p2: PI) = commutativeCombiner(TACExpr.BinRel.Eq::class.java, this, p2)
    infix fun PI.lt(p2: PI) = combiner(TACExpr.BinRel.Lt::class.java, this, p2)
    infix fun PI.gt(p2: PI) = combiner(TACExpr.BinRel.Gt::class.java, this, p2)
    infix fun PI.le(p2: PI) = combiner(TACExpr.BinRel.Le::class.java, this, p2)
    infix fun PI.ge(p2: PI) = combiner(TACExpr.BinRel.Ge::class.java, this, p2)

    infix fun PI.sLt(p2: PI) = combiner(TACExpr.BinRel.Slt::class.java, this, p2)
    infix fun PI.sGt(p2: PI) = combiner(TACExpr.BinRel.Sgt::class.java, this, p2)
    infix fun PI.sLe(p2: PI) = combiner(TACExpr.BinRel.Sle::class.java, this, p2)
    infix fun PI.sGe(p2: PI) = combiner(TACExpr.BinRel.Sge::class.java, this, p2)

    infix fun PI.symmLe(p2: PI) = (this le p2) OR (p2 ge this)
    infix fun PI.symmLt(p2: PI) = (this lt p2) OR (p2 gt this)
    infix fun PI.symmGe(p2: PI) = (this ge p2) OR (p2 le this)
    infix fun PI.symmGt(p2: PI) = (this gt p2) OR (p2 lt this)

    infix fun PI.lOr(p2: PI) = commutativeCombiner(TACExpr.BinBoolOp.LOr::class.java, this, p2)
    infix fun PI.lAnd(p2: PI) = commutativeCombiner(TACExpr.BinBoolOp.LAnd::class.java, this, p2)

    @JvmName("land2")
    fun lAnd(p1: PI, p2: PI) = p1 lAnd p2

    @JvmName("lor2")
    fun lOr(p1: PI, p2: PI) = p1 lOr p2

    @JvmName("eq2")
    fun eq(p1: PI, p2: PI) = p1 eq p2


    /** saves the last command (the one matched) to [key] */
    fun PI.lastCmd(key: InfoKey<LTACCmd>) : PI =
        this.andDo { this@andDo.lastCmd(key) }

    /** if the Info generated does not satisfy the predicate, the resulting Info is null. */
    fun PI.onlyIf(pred: Info.() -> Boolean) =
        this.andDo { takeIf { pred() } }

    /** exact constant match shorthand */
    fun c(exact: BigInteger, key: InfoKey<BigInteger>? = null) =
        c(key) { it == exact }

    /** exact constant int match shorthand */
    fun c(exact: Int, key: InfoKey<Int>? = null) =
        c(key) { it == exact }

    val zero = c(0)

    /** exact variable match shorthand */
    fun v(exact: TACSymbol.Var) =
        VarKey().let { key ->
            v(key).onlyIf { get(key) == exact }
        }

    /** exact symbol match shorthand */
    fun sym(exact: TACSymbol) =
        SymKey().let { key ->
            sym(key).onlyIf { get(key) == exact }
        }

    inline fun <reified T : TACCmd.Simple> Info.view(key: InfoKey<LTACCmd>) =
        this[key]!!.narrow<T>()

    inline fun <reified T : TACExpr> Info.exprView(key: InfoKey<LTACCmd>) =
        this[key]!!.enarrow<T>()

    /**
     * Returns the operands of a commutative operator (created with [commutativeCombiner]) in the order that is
     * specified in the pattern
     */
    fun Info.ops(lcmd: LTACCmd) =
        ExprView<TACExpr.BinExp>(lcmd).let { v ->
            if (REVERSED(lcmd.ptr) in this) {
                v.exp.o2 to v.exp.o1
            } else {
                v.exp.o1 to v.exp.o2
            }
        }.let { (a, b) ->
            (a as TACExpr.Sym).s to (b as TACExpr.Sym).s
        }

    fun Info.ops(key: InfoKey<LTACCmd>) = this.ops(this[key]!!)
}

