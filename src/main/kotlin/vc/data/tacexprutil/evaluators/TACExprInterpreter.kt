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

package vc.data.tacexprutil.evaluators

import com.certora.collect.*
import datastructures.stdcollections.*
import solver.CounterexampleModel
import tac.MetaKey
import tac.Tag
import vc.data.*
import vc.data.state.SimpleTACState
import vc.data.state.TACState
import vc.data.state.TACValue
import vc.data.state.asTACValue
import vc.data.tacexprutil.AccumulatingTACExprTransFormerWithDefaultRes
import java.io.Serializable
import java.math.BigInteger

/** Defines default [TACValue]s for every [Tag]. (Going for "some variation of 0" as much as possible here.)  */
fun Tag.defaultValue(): TACValue {
    fun unsupp(): Nothing =
        throw UnsupportedOperationException("Tag.defaultValue does not support this tag: $this")
    return when (this) {
        Tag.Int,
        is Tag.Bits -> 0.asTACValue
        Tag.Bool -> false.asTACValue
        Tag.ByteMap ->
            TACValue.MappingValue.MapDefinition(
                TACExprFactUntyped.mapDef(mapOf("x" to Tag.Bit256), { 0.asTACExpr }, Tag.ByteMap)
            )
        Tag.WordMap ->
            TACValue.MappingValue.MapDefinition(
                TACExprFactUntyped.mapDef(mapOf("x" to Tag.Bit256), { 0.asTACExpr }, Tag.WordMap)
            )
        is Tag.UserDefined.UninterpretedSort ->
            if (this == TACBuiltInFunction.Hash.skeySort) {
                TACValue.SKey.Basic(0.asTACValue)
            } else {
                unsupp()
            }
        is Tag.GhostMap,
        is Tag.UserDefined.Struct,
        is Tag.Move,
        Tag.BlockchainState,
        Tag.CVLArray.RawArray,
        is Tag.CVLArray.UserArray -> unsupp()
    }
}


/**
 * Interprets a given [TACExpr] under a given [TACState].
 * Will crash if not all free variables in the expression are present in the state, or if the corresponding [TACValue]s
 * don't have the right type.
 *
 * Current use case is for evaluating expressions in the call trace context. This does not cover all cases in all TAC
 * variants. Plan is to extend it with cases that are needed and make sense to support here on demand.
 *
 * (No fancy stuff like automatic initialization of values or so.)
 */
object TACExprInterpreter : AccumulatingTACExprTransFormerWithDefaultRes<TACExprInterpreter.Acc, TACValue>() {

    /** Evaluate [exp] under the assumption that it contains no variables, thus can just be "computed" -- will return
     * `null` if that is not the case. */
    fun evalConstInt(exp: TACExpr): BigInteger? =
        @Suppress("SwallowedException")
        try {
            val tacVal = eval(mapOf(), exp)
            when (tacVal) {
                is TACValue.PrimitiveValue.Integer -> tacVal.value
                is TACValue.PrimitiveValue.Bool -> tacVal.asBigInt
                else -> null
            }
        } catch (e: UnsupportedOperationException) {
            // can happen e.g. on some builtin functions -- should be fine to swallow here, as those don't have a
            // constant integer value (if they do, we should implement that below)
            null
        } catch (e: ValueMissingFromStateException) {
            null
        }


    /** Uses the [CounterexampleModel.havocedVariables] and returns [TACValue.Uninitialized] on them. */
    fun eval(model: CounterexampleModel, exp: TACExpr) =
        transform(Acc.CallTraceModel(model), exp)

    fun eval(assignment: Map<TACSymbol.Var, TACValue>, exp: TACExpr) =
        eval(SimpleTACState(assignment.toTreapMap()), exp)

    fun eval(tacState: TACState, exp: TACExpr) =
        transform(Acc.State(tacState), exp)

    override fun defaultRes(acc: Acc, e: TACExpr): TACValue =
        throw UnsupportedOperationException("failed to interpret expression \"$e\" -- problably this case " +
            "unsupported so far")


    interface Acc {
        operator fun get(v: TACSymbol.Var): TACValue?

        @JvmInline
        value class State(private val state: TACState): Acc {
            override operator fun get(v: TACSymbol.Var): TACValue? = state[v]
        }

        @JvmInline
        value class CallTraceModel(private val model: CounterexampleModel): Acc {
            override operator fun get(v: TACSymbol.Var): TACValue? =
                model.tacAssignments[v] ?:
                    if (v in model.havocedVariables) {
                        TACValue.Uninitialized // this might only be OK/good in call trace land ..
                    } else {
                        null
                    }
        }

        companion object {
            operator fun invoke(state: TACState) = State(state)
        }
    }


    private val BigInteger.asTacValue get() = TACValue.valueOf(this)

    private val Boolean.asTacValue get() = TACValue.valueOf(this)

    private val TACValue.asPrimitiveInt: TACValue.PrimitiveValue.Integer
        get() =
            when (this) {
                is TACValue.PrimitiveValue.Integer -> this
                is TACValue.PrimitiveValue.Bool -> this.asBigInt.asTacValue
                else -> error("can't convert value \"$this\" to a primitive int value")
            }

    override fun transformVar(acc: Acc, exp: TACExpr.Sym.Var): TACValue =
        acc[exp.s] ?:
            throw ValueMissingFromStateException(exp, acc)

    override fun transformConst(acc: Acc, exp: TACExpr.Sym.Const): TACValue = when (exp.s.tag) {
        Tag.Int,
        is Tag.Bits -> TACValue.valueOf(exp.s.value)
        Tag.Bool -> TACValue.valueOf(exp.s.value != BigInteger.ZERO)
        else -> throw UnsupportedOperationException("evaluating TACExpr.Sym.Const \"$exp\" is not yet supported")
    }

    private fun transformIntList(acc: Acc, ls: List<TACExpr>) = ls.map { transform(acc, it).asPrimitiveInt.value }


    override fun transformVec(acc: Acc, e: TACExpr.Vec): TACValue =
        e.eval(transformIntList(acc, e.ls)).asTacValue

    override fun transformApply(acc: Acc, e: TACExpr.Apply): TACValue {
        return when (val fs = e.f) {
            is TACExpr.TACFunctionSym.BuiltIn -> when (val bif = fs.bif) {
                is TACBuiltInFunction.Hash.ToSkey -> transformSelect(acc, bif.asMapVar, e.expectSingleOp(), bif.returnSort)
                is TACBuiltInFunction.Hash.FromSkey -> transformSelect(acc, bif.asMapVar, e.expectSingleOp(), bif.returnSort)
                is TACBuiltInFunction.Hash.SimpleHashApplication -> {
                    val children = e.ops.map { transform(acc, it).expectSKey() }
                    TACValue.SKey.Node(children, TACValue.valueOf(0))
                }
                is TACBuiltInFunction.Hash.Basic -> {
                    val offset = transform(acc, e.expectSingleOp()).expectInteger()
                    TACValue.SKey.Basic(offset)
                }
                is TACBuiltInFunction.Hash.Addition -> {
                    val (firstOp, secondOp) = e.expectOpCount(2)

                    val skey = transform(acc, firstOp).expectSKey()
                    val int = transform(acc, secondOp).expectInteger()

                    skey.withOffset(skey.offset + int)
                }
                else -> super.transformApply(acc, e)
            }
            is TACExpr.TACFunctionSym.Adhoc -> super.transformApply(acc, e)
        }

    }

    override fun transformBinBoolOp(acc: Acc, e: TACExpr.BinBoolOp): TACValue.PrimitiveValue.Integer =
        e.eval(transformIntList(acc, e.ls)).asTacValue

    override fun transformBinRel(acc: Acc, e: TACExpr.BinRel): TACValue.PrimitiveValue.Bool {
        fun unsupp(): Nothing {
            throw UnsupportedOperationException("Binary relation evaluation not yet implemented for this case: $e")
        }

        val o1 = transform(acc, e.o1)
        val o2 = transform(acc, e.o2)
        return when (o1) {
            is TACValue.PrimitiveValue ->
                (e.eval(
                    o1.asPrimitiveInt.value,
                    o2.asPrimitiveInt.value
                ) != BigInteger.ZERO).asTacValue
            is TACValue.SKey ->
                when (e) {
                    /** The `equals` method of the [TACValue.SKey] classes is written such that `==` works as the
                     * semantic equality.   */
                    is TACExpr.BinRel.Eq -> (o1 == o2).asTacValue
                    else -> unsupp()
                }
            else -> unsupp()
        }
    }


    override fun transformBinOp(acc: Acc, e: TACExpr.BinOp): TACValue.PrimitiveValue.Integer =
        e.eval(transform(acc, e.o1).asPrimitiveInt.value, transform(acc, e.o2).asPrimitiveInt.value).asTacValue

    override fun transformTernary(acc: Acc, e: TACExpr.TernaryExp): TACValue =
        e.eval(
            transform(acc, e.o1).asPrimitiveInt.value,
            transform(acc, e.o2).asPrimitiveInt.value,
            transform(acc, e.o3).asPrimitiveInt.value,
        ).asTacValue


    override fun transformSelect(acc: Acc, base: TACExpr, loc: TACExpr, tag: Tag?): TACValue {
        val baseEvaled = transform(acc, base) as? TACValue.MappingValue ?: throw IncorrectValueTypeException(
            base, acc, "MappingValue"
        )
        val locEvaled = transform(acc, loc)
        return when (baseEvaled) {
            is TACValue.MappingValue.KVMapping.ByteMap -> baseEvaled.get(locEvaled as TACValue.PrimitiveValue.Integer)
            is TACValue.MappingValue.KVMapping.WordMap -> baseEvaled.get(locEvaled as TACValue.SKey)
            is TACValue.MappingValue.UninterpretedFunction -> baseEvaled.get(listOf(locEvaled))
            is TACValue.MappingValue.MapDefinition -> baseEvaled.get(listOf(locEvaled))
            is TACValue.MappingValue.StructValue ->
                throw UnsupportedOperationException("select on this kind of tacValue is not supported \"$baseEvaled\"")
        }
    }

    override fun transformStore(acc: Acc, base: TACExpr, loc: TACExpr, value: TACExpr, tag: Tag?): TACValue {
        val baseEvaled = transform(acc, base) as TACValue.MappingValue
        val locEvaled = transform(acc, loc)
        val valEvaled = transform(acc, value).asPrimitiveInt
        return when (baseEvaled) {
            is TACValue.MappingValue.KVMapping.ByteMap -> baseEvaled.store(locEvaled, valEvaled)
            is TACValue.MappingValue.KVMapping.WordMap -> baseEvaled.store(locEvaled, valEvaled)
            is TACValue.MappingValue.MapDefinition -> baseEvaled.store(locEvaled, valEvaled)
            is TACValue.MappingValue.StructValue,
            is TACValue.MappingValue.UninterpretedFunction ->
                throw UnsupportedOperationException("store on this kind of tacValue is not supported \"$baseEvaled\"")
        }
    }

    override fun transformLongStore(
        acc: Acc,
        dstMap: TACExpr,
        dstOffset: TACExpr,
        srcMap: TACExpr,
        srcOffset: TACExpr,
        length: TACExpr,
        tag: Tag?
    ): TACValue {
        val dstMapEvaled = transform(acc, dstMap) as TACValue.MappingValue
        val dstOffsetEvaled = transform(acc, dstOffset).asPrimitiveInt
        val srcMapEvaled = transform(acc, dstMap) as TACValue.MappingValue
        val srcOffsetEvaled = transform(acc, srcOffset).asPrimitiveInt
        val lengthEvaled = transform(acc, length).asPrimitiveInt
        return when (dstMapEvaled) {
            is TACValue.MappingValue.KVMapping.ByteMap ->
                dstMapEvaled.longStore(
                    srcMapEvaled as TACValue.MappingValue.KVMapping.ByteMap,
                    srcOffsetEvaled,
                    dstOffsetEvaled,
                    lengthEvaled
                )

            is TACValue.MappingValue.KVMapping.WordMap,
            is TACValue.MappingValue.StructValue,
            is TACValue.MappingValue.MapDefinition,
            is TACValue.MappingValue.UninterpretedFunction ->
                throw UnsupportedOperationException("longstore on this kind of tacValue is not supported \"$dstMapEvaled\"")
        }
    }

    override fun transformUnary(acc: Acc, e: TACExpr.UnaryExp): TACValue =
        e.eval(transform(acc, e.o).asPrimitiveInt.value).asTacValue

    override fun <T : Serializable> transformAnnotationExp(acc: Acc, o: TACExpr, k: MetaKey<T>, v: T) =
        transform(acc, o)

    override fun transformMapDefinition(acc: Acc, exp: TACExpr.MapDefinition): TACValue {
        // just wrap it here -- beta reduction is done on a select
        return TACValue.MappingValue.MapDefinition(exp)
    }

    sealed class InterpreterException(msg: String) : RuntimeException(msg)

    class IncorrectValueTypeException(v: TACExpr, acc: Acc, expected: String) : InterpreterException(
        "Interpreting \"$v\" in state \"$acc\" did not yield a value of type $expected"
    )

    class ValueMissingFromStateException(v: TACExpr.Sym.Var, acc: Acc) :
        InterpreterException("Interpreting expression failed: \"$v\" does not exist in state \"$acc\".")

    /** a single exception type which can be caught by downstream. simplistic approximation of possible exceptions */
    sealed class HashingException : RuntimeException() {
        override val message = "reached illegal state while hashing"

        class WrongNumberOfOperands : HashingException()
        class UnexpectedResultValue : HashingException()
    }

    private fun TACExpr.Apply.expectSingleOp() = ops.singleOrNull() ?: throw HashingException.WrongNumberOfOperands()
    private fun TACExpr.Apply.expectOpCount(count: Int) = ops.takeIf { it.size == count } ?: throw HashingException.WrongNumberOfOperands()

    private fun TACValue.expectInteger() = this as? TACValue.PrimitiveValue.Integer ?: throw HashingException.UnexpectedResultValue()
    private fun TACValue.expectSKey() = this as? TACValue.SKey ?: throw HashingException.UnexpectedResultValue()

}
