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

package vc.data.tacexprutil

import analysis.CommandWithRequiredDecls
import analysis.opt.ConstantPropagatorAndSimplifier.Companion.defaultTag
import com.certora.collect.*
import datastructures.stdcollections.*
import evm.EVM_BITWIDTH256
import spec.cvlast.CVLType
import tac.MetaKey
import tac.MetaMap
import tac.Tag
import utils.*
import vc.data.*
import vc.data.TACCmd.Simple.AnnotationCmd.Annotation
import java.io.Serializable
import java.math.BigInteger

object TACExprUtils {
    open class SubstitutorVar(val substitution: Map<TACExpr.Sym.Var, TACExpr>) : DefaultTACExprTransformer() {
        private val subs = substitution.mapKeys { (v, _) -> v.s }

        override fun transformVar(exp: TACExpr.Sym.Var): TACExpr =
        // TODO: this should be replaced with an equiv relation over TACExpr (i.e. we need an equivalence relation
        // implemented for TACExpr that doesn't care about irrelevant data fields such as the tag). As an approximation
            // we're checking that the s field of TACExpr.Sym.Var is equal
            subs[exp.s] ?: super.transformVar(exp)
    }

    open class Substitutor(val substitution: Map<out TACExpr, TACExpr>) : DefaultTACExprTransformer() {
        override fun transform(exp: TACExpr): TACExpr = substitution[exp] ?: super.transform(exp)
    }


    //TODO: check function is completely "anti-pattern": we should remove it completely. Instead, the meta data of the TACCmd should maintain which kind of expression is in the rhs
    fun <T : TACExpr> check(c: TACCmd.Simple, tacExprClass: Class<T>): Boolean =
        c is TACCmd.Simple.AssigningCmd.AssignExpCmd && tacExprClass.isInstance(c.rhs)

    fun <T : TACExpr> check(c: TACCmd.Simple.AssigningCmd.AssignExpCmd, tacExprClass: Class<T>): Boolean =
        tacExprClass.isInstance(c.rhs)

    fun <T : TACExpr> check(c: TACCmd.EVM, tacExprClass: Class<T>): Boolean =
        c is TACCmd.EVM.AssigningCmd.WithExprBuilder<*> && tacExprClass.isInstance(c.rhsAsExpr())

    fun <T : TACExpr> rhsExprAsNullable(c: TACCmd.Simple, tacExprClass: Class<T>): T? =
        if (c is TACCmd.Simple.AssigningCmd.AssignExpCmd && tacExprClass.isInstance(c.rhs)) tacExprClass.cast(
            c.rhs
        ) else null

    fun TACExpr.contains(p: (TACExpr) -> Boolean): Boolean =
        p(this) || getOperands().any { it.contains(p) }

    val TACExpr.isTrue get() = this == TACExprFactUntyped.True
    val TACExpr.isFalse get() = this == TACExprFactUntyped.False
    infix fun TACExpr.eqTo(i: BigInteger) = getAsConst() == i
    infix fun TACExpr.eqTo(i: Int) = getAsConst()?.toIntOrNull() == i
    infix fun TACExpr.leTo(i: BigInteger) = getAsConst()?.let { it <= i} == true
    infix fun TACExpr.geTo(i: BigInteger) = getAsConst()?.let { it >= i} == true
    infix fun TACExpr.ltTo(i: BigInteger) = getAsConst()?.let { it < i} == true
    infix fun TACExpr.gtTo(i: BigInteger) = getAsConst()?.let { it > i} == true
    infix fun TACExpr.leTo(i: Int) = leTo(i.toBigInteger())
    infix fun TACExpr.geTo(i: Int) = geTo(i.toBigInteger())
    infix fun TACExpr.ltTo(i: Int) = ltTo(i.toBigInteger())
    infix fun TACExpr.gtTo(i: Int) = gtTo(i.toBigInteger())
}

// This should be local within `rebuild` but can't have local functions within inline ones.
inline fun <reified T : TACExpr> T.checkSize(newOps: List<TACExpr>, i: Int) =
    this.also {
        require(newOps.size == i) { "A ${javaClass.simpleName} requires $i operands. Got $newOps " }
    }

/**
 * Returns a [TACExpr] whose operands have been exchanged for [newOps].
 * - assumes the canonical ordering of operands, like in [getOperands]
 *    (should be the ordering in the constructor in all cases, but [getOperands] is authoritative)
 * - assumes that the right number of operands is given, will throw otherwise
 *
 * Implementation uses `copy` methods where available.
 */
inline fun <reified T : TACExpr> T.rebuild(newOps: List<TACExpr>): T {
    return when (val temp = this as TACExpr) {
        is TACExpr.Sym ->
            this

        is TACExpr.UnaryExp ->
            temp.checkSize(newOps, 1).rebuild(newOps[0])

        is TACExpr.BinRel ->
            temp.checkSize(newOps, 2).rebuild(newOps[0], newOps[1])

        is TACExpr.BinOp ->
            temp.checkSize(newOps, 2).rebuild(newOps[0], newOps[1])

        is TACExpr.TernaryExp ->
            temp.checkSize(newOps, 3).rebuild(newOps[0], newOps[1], newOps[2])

        is TACExpr.BinBoolOp ->
            temp.rebuild(newOps)

        is TACExpr.Vec ->
            temp.rebuild(newOps)

        is TACExpr.SimpleHash -> {
            require(newOps.isNotEmpty()) { "can't create a SimpleHash with an empty list of operands" }
            temp.copy(length = newOps.first(), args = newOps.drop(1))
        }

        is TACExpr.Apply ->
            temp.copy(ops = newOps)

        is TACExpr.LongStore ->
            temp.checkSize(newOps, 5).copy(
                dstMap = newOps[0],
                dstOffset = newOps[1],
                srcMap = newOps[2],
                srcOffset = newOps[3],
                length = newOps[4],
            )

        is TACExpr.MapDefinition ->
            temp.checkSize(newOps, 1)

        is TACExpr.MultiDimStore -> {
            require(newOps.size >= 4) { "A MultiDimStore must have at least four operands, got $newOps." }
            temp.copy(base = newOps.first(), locs = newOps.drop(1).dropLast(1), value = newOps.last())
        }

        is TACExpr.QuantifiedFormula ->
            temp.checkSize(newOps, 1).copy(body = newOps[0])

        is TACExpr.Select ->
            temp.checkSize(newOps, 2).copy(base = newOps[0], loc = newOps[1])

        is TACExpr.Store ->
            temp.checkSize(newOps, 3).copy(base = newOps[0], loc = newOps[1], value = newOps[2])

        is TACExpr.StructAccess ->
            temp.checkSize(newOps, 1).copy(struct = newOps[0])

        is TACExpr.StructConstant ->
            temp.checkSize(newOps, 0)

        is TACExpr.Unconstrained ->
            temp.checkSize(newOps, 0)

        is TACExpr.AnnotationExp<*> ->
            temp.checkSize(newOps, 1).copy(o = newOps[0])
    } as T
}

inline fun <reified T : TACExpr> T.rebuild(vararg newOps : T): T =
    rebuild(newOps.toList())

inline fun <reified T : TACExpr> T.rebuild(f : (TACExpr) -> TACExpr): T =
    rebuild(getOperands().map(f))

fun TACExpr.postTransform(f : (TACExpr) -> TACExpr): TACExpr =
    f(rebuild(getOperands().map { it.postTransform(f) }))

fun <T> TACExpr.postFold(f : (TACExpr, List<T>) -> T): T =
    f(this, getOperands().map { it.postFold(f) })

/** f(originalE, E with operands already transformed) -> newTransformedE */
fun TACExpr.postTransformWithOriginal(f : (TACExpr, TACExpr) -> TACExpr): TACExpr =
    f(this, rebuild(getOperands().map { it.postTransformWithOriginal(f) }))


fun TACExpr.evalAsExprOrNull(vararg args : BigInteger) = eval(args.toList())?.asTACExpr(tag ?: defaultTag)
fun TACExpr.evalAsExpr(vararg args : BigInteger) = evalAsExprOrNull(*args)!!

/**
 * TAC function for cast expression
 */
@KSerializable
sealed class TACFunForCastExpression : HasKSerializable {

    /**
     * Returns true if the [innerExp] is within bounds for
     * performing a safe cast operation. If the cast operation is always safe
     * (e.g. SignedInt -> UnsignedInt), it simply returns true.
     *
     * @param innerExp: the exp for which the bounds have to be checked
     */
    abstract fun withinBoundsForSafeCast(innerExp: BigInteger): Boolean

    abstract fun safeCastExpr(exp: TACSymbol): TACExpr
}

/**
 * Get the appropriate TAC Function for Cast expression from [fromCastType] to [toCastType].
 * In case the return value is [null], it means we can skip casting and return the inner expression
 * directly in [CVLCompiler]. Throws [UnsupportedOperationException] in case a cast operation is
 * not supported.
 */
fun getTACCastExpressionHelpers(
    fromCastType: CVLType.PureCVLType,
    toCastType: CVLType.PureCVLType
): TACFunForCastExpression? {
    return when {
        // skip casting
        fromCastType == toCastType -> null
        fromCastType == CVLType.PureCVLType.Primitive.BytesK(32) && toCastType == CVLType.PureCVLType.Primitive.UIntK(
            256
        ) -> null

        toCastType is CVLType.PureCVLType.Primitive.UIntK -> CastToUnsignedInt(toCastType.bitWidth)
        toCastType is CVLType.PureCVLType.Primitive.IntK -> CastToSignedInt(toCastType.bitWidth)
        toCastType is CVLType.PureCVLType.Primitive.Mathint -> null
        toCastType is CVLType.PureCVLType.Primitive.AccountIdentifier -> CastToAccountIdentifier
        else -> throw UnsupportedOperationException(
            "no TAC Function for Cast Expression found from $fromCastType to $toCastType"
        )
    }
}

@KSerializable
data class CastToSignedInt(val bitWidth: Int) : TACFunForCastExpression() {
    override fun withinBoundsForSafeCast(innerExp: BigInteger): Boolean =
        innerExp.bitLength() < bitWidth // note bitLength doesn't include the sign bit

    override fun safeCastExpr(exp: TACSymbol): TACExpr =
        TACExprFactTypeCheckedOnlyPrimitives {
            TACExpr.BinBoolOp.LAnd(
                TACExpr.BinRel.Ge(
                    TACSymbol.lift(SignUtilities.maxSignedValueOfBitwidth(bitWidth), Tag.Int).asSym(),
                    exp.asSym()
                ),
                TACExpr.BinRel.Le(
                    TACSymbol.lift(SignUtilities.minSignedValueOfBitwidth(bitWidth), Tag.Int).asSym(),
                    exp.asSym()
                )
            )
        }
}

@KSerializable
data class CastToUnsignedInt(val bitWidth: Int) : TACFunForCastExpression() {

    override fun withinBoundsForSafeCast(innerExp: BigInteger): Boolean =
        innerExp >= BigInteger.ZERO && innerExp.bitLength() <= bitWidth

    override fun safeCastExpr(exp: TACSymbol): TACExpr =
        TACExprFactTypeCheckedOnlyPrimitives {
            TACExpr.BinBoolOp.LAnd(
                TACExpr.BinRel.Ge(
                    TACSymbol.lift(SignUtilities.maxUnsignedValueOfBitwidth(bitWidth)).asSym(),
                    exp.asSym()
                ),
                TACExpr.BinRel.Le(
                    TACSymbol.lift(0).asSym(),
                    exp.asSym()
                )
            )
        }

    private fun compileCast(
        outVar: TACSymbol.Var,
        inVar: TACSymbol.Var,
        constraningCmd: (TACSymbol.Var) -> TACCmd.Simple
    ): CommandWithRequiredDecls<TACCmd.Simple> {
        check(bitWidth <= EVM_BITWIDTH256) { "a bitwidth over $EVM_BITWIDTH256 makes narrowing unsafe here" }
        // if we are going to be asserting or assuming in-bounds anyway, let's give smt and constant propagation
        // a break and use this "unsafe" narrowing from Int to Bit256
        val dangerousNarrow = TACBuiltInFunction.SafeMathNarrow.Implicit(Tag.Bit256)
        val sanityVar = TACSymbol.Var("boundsCheck", Tag.Bool).toUnique(".")
        return CommandWithRequiredDecls(
            listOf(
                TACCmd.Simple.AssigningCmd.AssignExpCmd(sanityVar, safeCastExpr(inVar)),
                constraningCmd(sanityVar),
                TACCmd.Simple.AssigningCmd.AssignExpCmd(
                    outVar, dangerousNarrow.toTACFunctionSym(), listOf(inVar.asSym())
                )
            ), setOf(inVar, outVar, sanityVar)
        )
    }

    fun compileRequireCast(
        outVar: TACSymbol.Var,
        inVar: TACSymbol.Var
    ) =
        compileCast(outVar, inVar) { toConstrain -> TACCmd.Simple.AssumeCmd(toConstrain, "compileRequireCast") }

    /**
     * Assert that [inVar] is in range for an unsigned integer of bitwidth [bitwidth]
     * and stuff the value into a bitvector
     */
    fun compileAssertCast(
        outVar: TACSymbol.Var,
        inVar: TACSymbol.Var,
        msg: String = "sanity bounds check on int to bitvector conversion failed",
    ): CommandWithRequiredDecls<TACCmd.Simple> =
        compileCast(outVar, inVar) { toConstrain ->
            TACCmd.Simple.AssertCmd(
                toConstrain,
                msg,
            )
        }
}

@KSerializable
object CastToMathint : TACFunForCastExpression() {

    override fun withinBoundsForSafeCast(innerExp: BigInteger): Boolean = true

    override fun safeCastExpr(exp: TACSymbol): TACExpr = TACSymbol.Const(true).asSym()
}

@KSerializable
object CastToAccountIdentifier: TACFunForCastExpression() {
    override fun withinBoundsForSafeCast(innerExp: BigInteger): Boolean {
        return innerExp.bitLength() <= 160
    }

    override fun safeCastExpr(exp: TACSymbol): TACExpr {
        return TACExpr.BinRel.Le(
            exp.asSym(),
            TACSymbol.lift(SignUtilities.maxUnsignedValueOfBitwidth(160)).asSym()
        )
    }
}

inline fun <reified T : TACExpr.UnaryExp> T.rebuild(o: TACExpr) =
    when (val temp = this as TACExpr.UnaryExp) {
        is TACExpr.UnaryExp.BWNot -> temp.copy(o = o)
        is TACExpr.UnaryExp.LNot -> temp.copy(o = o)
    } as T

inline fun <reified T : TACExpr.BinRel> T.rebuild(o1: TACExpr, o2: TACExpr) =
    when (val temp = this as TACExpr.BinRel) {
        is TACExpr.BinRel.Eq -> temp.copy(o1 = o1, o2 = o2)
        is TACExpr.BinRel.Ge -> temp.copy(o1 = o1, o2 = o2)
        is TACExpr.BinRel.Gt -> temp.copy(o1 = o1, o2 = o2)
        is TACExpr.BinRel.Le -> temp.copy(o1 = o1, o2 = o2)
        is TACExpr.BinRel.Lt -> temp.copy(o1 = o1, o2 = o2)
        is TACExpr.BinRel.Sge -> temp.copy(o1 = o1, o2 = o2)
        is TACExpr.BinRel.Sgt -> temp.copy(o1 = o1, o2 = o2)
        is TACExpr.BinRel.Sle -> temp.copy(o1 = o1, o2 = o2)
        is TACExpr.BinRel.Slt -> temp.copy(o1 = o1, o2 = o2)
    } as T

inline fun <reified T : TACExpr.BinOp> T.rebuild(o1: TACExpr, o2: TACExpr) =
    when (val temp = this as TACExpr.BinOp) {
        is TACExpr.BinOp.BWAnd -> temp.copy(o1 = o1, o2 = o2)
        is TACExpr.BinOp.BWOr -> temp.copy(o1 = o1, o2 = o2)
        is TACExpr.BinOp.BWXOr -> temp.copy(o1 = o1, o2 = o2)
        is TACExpr.BinOp.Div -> temp.copy(o1 = o1, o2 = o2)
        is TACExpr.BinOp.Exponent -> temp.copy(o1 = o1, o2 = o2)
        is TACExpr.BinOp.IntExponent -> temp.copy(o1 = o1, o2 = o2)
        is TACExpr.BinOp.IntDiv -> temp.copy(o1 = o1, o2 = o2)
        is TACExpr.BinOp.IntMod -> temp.copy(o1 = o1, o2 = o2)
        is TACExpr.BinOp.IntSub -> temp.copy(o1 = o1, o2 = o2)
        is TACExpr.BinOp.Mod -> temp.copy(o1 = o1, o2 = o2)
        is TACExpr.BinOp.SDiv -> temp.copy(o1 = o1, o2 = o2)
        is TACExpr.BinOp.SMod -> temp.copy(o1 = o1, o2 = o2)
        is TACExpr.BinOp.ShiftLeft -> temp.copy(o1 = o1, o2 = o2)
        is TACExpr.BinOp.ShiftRightArithmetical -> temp.copy(o1 = o1, o2 = o2)
        is TACExpr.BinOp.ShiftRightLogical -> temp.copy(o1 = o1, o2 = o2)
        is TACExpr.BinOp.SignExtend -> temp.copy(o1 = o1, o2 = o2)
        is TACExpr.BinOp.Sub -> temp.copy(o1 = o1, o2 = o2)
    } as T

inline fun <reified T : TACExpr.TernaryExp> T.rebuild(o1: TACExpr, o2: TACExpr, o3: TACExpr) =
    when (val temp = this as TACExpr.TernaryExp) {
        is TACExpr.TernaryExp.AddMod -> temp.copy(a = o1, b = o2, n = o3)
        is TACExpr.TernaryExp.Ite -> temp.copy(i = o1, t = o2, e = o3)
        is TACExpr.TernaryExp.MulMod -> temp.copy(a = o1, b = o2, n = o3)
    } as T

inline fun <reified T : TACExpr.BinBoolOp> T.rebuild(ls: List<TACExpr>) =
    when (val temp = this as TACExpr.BinBoolOp) {
        is TACExpr.BinBoolOp.LAnd -> temp.copy(ls = ls)
        is TACExpr.BinBoolOp.LOr -> temp.copy(ls = ls)
    } as T

inline fun <reified T : TACExpr.Vec> T.rebuild(ls: List<TACExpr>) =
    when (val temp = this as TACExpr.Vec) {
        is TACExpr.Vec.Add -> temp.copy(ls = ls)
        is TACExpr.Vec.IntAdd -> temp.copy(ls = ls)
        is TACExpr.Vec.IntMul -> temp.copy(ls = ls)
        is TACExpr.Vec.Mul -> temp.copy(ls = ls)
    } as T


/** a sequence of all subexpressions of this [TACExpr] in preorder */
val TACExpr.subs : Sequence<TACExpr> get() =
    sequenceOf(this) + getOperands().asSequence().flatMap { it.subs }

val TACExpr.unquantifiedSubs : Sequence<TACExpr> get() =
    if (this is TACExpr.QuantifiedFormula) {
        emptySequence()
    } else {
        sequenceOf(this) + getOperands().flatMap { it.unquantifiedSubs }
    }

/**
 * a sequence of all subexpressions of this [TACExpr] in preorder,
 * including vars in meta values, and `mentionedVariables` of `AnnotationExp`
 */
val TACExpr.subsFull : Sequence<TACExpr>
    get() =
        sequenceOf(this) + when (this) {
            is TACExpr.Sym.Var ->
                s.meta.map.values
                    .filterIsInstance<WithSupport>()
                    .flatMap { it.support }
                    .map { it.asSym() }
                    .asSequence()

            is TACExpr.AnnotationExp<*> ->
                o.subsFull + mentionedVariables.asSequence().map { it.asSym() }

            else -> getOperands().asSequence().flatMap {
                it.subsFull
            }
        }

fun tempVar(name : String, tag : Tag, meta : MetaMap = MetaMap()) =
    TACKeyword.TMP(tag, "!$name", meta).toUnique("!")

/** Generates a [TreapSet], I'm not sure why */
fun Sequence<TACExpr>.toSymSet() =
    filterIsInstance<TACExpr.Sym>().map { it.s }.toTreapSet()

fun Sequence<TACExpr>.toConstSet() =
    filterIsInstance<TACExpr.Sym.Const>().mapToSet { it.s }

fun Sequence<TACExpr>.toVarSet() =
    filterIsInstance<TACExpr.Sym.Var>().mapToSet { it.s }

fun <T> TACExpr.eval(ops : List<T>, map : (T) -> BigInteger?) : BigInteger? =
    ops.monadicMap(map)?.let {
        eval(it)
    }

/**
 * Replaces variables in the expression according to [map].
 * "Extended" is because it do so for quantified variables, for var metas, and for annotation expressions.
 * Note that if the mapped-to variable has meta, it is ignored. Rather, the original var meta is transformed and kept.
 */
fun TACExpr.replaceVarsExtended(map : Map<TACSymbol.Var, TACSymbol.Var>) : TACExpr {
    val transformer = object {
        fun <T : Serializable> mapMetaPair(k: MetaKey<T>, v: T): T =
            applyTransEntityMappers(
                v, k,
                mapSymbol = { (it as? TACSymbol.Var)?.let(::trans) ?: it },
                mapVar = { trans(it) }
            ).uncheckedAs()

        fun <@Treapable T : Serializable> trans(e: TACExpr.AnnotationExp<T>) =
            TACExpr.AnnotationExp(e.o, Annotation(e.annot.k, mapMetaPair(e.annot.k, e.annot.v)))

        fun trans(m: MetaMap) = m.updateValues { (k, v) -> mapMetaPair(k, v) }
        fun trans(v: TACSymbol.Var) = (map[v] ?: v).withMeta { trans(it) }
        fun trans(e: TACExpr.Sym.Var) = trans(e.s).asSym()
    }
    return with(transformer) {
        postTransform { e ->
            when (e) {
                is TACExpr.Sym.Var -> trans(e)
                is TACExpr.QuantifiedFormula -> e.copy(quantifiedVars = e.quantifiedVars.map(this::trans))
                is TACExpr.AnnotationExp<*> -> trans(e)
                else -> e
            }
        }
    }
}

fun TACExpr.replaceVarsExtended(vararg pairs : Pair<TACSymbol.Var, TACSymbol.Var>) =
    replaceVarsExtended(pairs.toMap())

val TACExpr.isConst get() = this is TACExpr.Sym.Const
val TACExpr.asConstOrNull get() = (this as? TACExpr.Sym.Const)?.s?.value
val TACExpr.asConst get() = asConstOrNull!!

val TACExpr.isVar get() = this is TACExpr.Sym.Var
val TACExpr.asVarOrNull get() = (this as? TACExpr.Sym.Var)?.s
val TACExpr.asVar get() = asVarOrNull!!

val TACExpr.isSym get() = this is TACExpr.Sym
val TACExpr.asSymOrNull get() = (this as? TACExpr.Sym)?.s
val TACExpr.asSym get() = asSymOrNull!!


val TACSymbol.isConst get() = this is TACSymbol.Const
val TACSymbol.asConstOrNull get() = (this as? TACSymbol.Const)?.value
val TACSymbol.asConst get() = asConstOrNull!!

val TACSymbol.isVar get() = this is TACSymbol.Var
val TACSymbol.asVarOrNull get() = this as? TACSymbol.Var
val TACSymbol.asVar get() = asVarOrNull!!
