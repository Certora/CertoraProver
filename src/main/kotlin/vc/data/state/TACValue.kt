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

@file:UseSerializers(BigIntegerSerializer::class)   // BigInteger is a java class therefore java serializable so we
                                                    // need to define a kotlin serializer for it.
package vc.data.state

import com.certora.collect.*
import datastructures.stdcollections.*
import evm.EVM_WORD_SIZE
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.json.Json
import smt.axiomgenerators.fullinstantiation.StorageHashAxiomGeneratorDataTypes
import smt.solverscript.functionsymbols.AxiomatizedFunctionSymbol
import smtlibutils.algorithms.Substitutor
import smtlibutils.data.*
import smtlibutils.data.Sort.Companion.SKeySort
import tac.Tag
import tac.emptyTags
import utils.*
import vc.data.*
import vc.data.TACBuiltInFunction.Hash.Companion.skeySort
import vc.data.parser.parseExpr
import vc.data.tacexprutil.TACExprPrettyPrinter
import vc.data.tacexprutil.evaluators.TACExprInterpreter
import verifier.sampling.TACValueGenerator
import java.lang.Exception
import java.lang.IllegalArgumentException
import java.math.BigInteger

/**
 * Nb, this class doesn't make too much sense -- all [TACValue]s are concrete -- but for now we don't have that
 * [asConstantTacExpr] method for every [TACValue], so this interface designates its presence.
 */
interface ConcreteTacValue : SerializableWithAdapter {
    /** Returns a [TACExpr] that corresponds to a value, including scalar constants, but also skey terms and
     * map-definitions. */
    fun asConstantTacExpr(): TACExpr
}

val Int.asTACValue get() = TACValue.valueOf(this)
val Long.asTACValue get() = TACValue.valueOf(this)
val BigInteger.asTACValue get() = TACValue.valueOf(this)
val Boolean.asTACValue get() = TACValue.valueOf(this)

/**
 * Represents the value of a [TACExpr].
 * Used, for instance, to represent SMT models when they have been translated back to speak about TAC. These then take
 * the shape of a Map<[TACSymbol.Var], [TACValue]>.
 * This also means that every TAC type ([Tag]) should have some fitting correspondence here, more or less.
 * Also, I suggest to not use this for other things than this part of TAC semantics (like aspects of displaying things
 * or so).
 */

@Serializable
@Treapable
sealed class TACValue : SerializableWithAdapter {
    private class Adapter(ty: TACValue? = null) : SerializationAdapter<TACValue>(serializer(), ty) {
        override val json: Json get() = Json { allowStructuredMapKeys = true }
    }

    override fun writeReplace(): Any {
        return Adapter(this)
    }

    fun asBigIntOrNull() = tryAs<PrimitiveValue>()?.asBigInt

    /**
     * Q (alex): do we want to save the [Tag] in the [TACValue] -- unsure about his ..
     * A (shelly): for now, no
     */
    fun isNonInitialValue() =
        this != Uninitialized

    // TODO: review: should this be serializable (or is it an error if it gets serialized)?
    @Serializable
    object Uninitialized : TACValue() { // For uninitialized array/map entries.
        override fun hashCode() = hashObject(this)
        fun readResolve(): Any = Uninitialized
    }

    companion object {
        val True
            get() = PrimitiveValue.Bool.True

        val False
            get() = PrimitiveValue.Bool.False

        fun valueOf(b: Boolean) = PrimitiveValue.Bool(b)

        fun valueOf(i: BigInteger): PrimitiveValue.Integer = PrimitiveValue.Integer(i)

        fun valueOf(i: Long): PrimitiveValue.Integer = valueOf(i.toBigInteger())

        fun valueOf(i: Int): PrimitiveValue.Integer = valueOf(i.toBigInteger())

    }

    @Serializable
    sealed class PrimitiveValue : TACValue() {
        /** Returns the raw value as a [BigInteger] */
        abstract val asBigInt: BigInteger

        @Serializable
        data class Integer(val value: BigInteger) :
            PrimitiveValue(), Comparable<Integer>, ConcreteTacValue { // A concrete value of an integer.
            // Should we know here whether its Tag.Int or Tag.Bit256 , perhaps PrimitiveValue.Bit256 would make sense?
            override fun asConstantTacExpr(): TACExpr.Sym.Const = TACSymbol.Const(value, Tag.Int).asSym()

            override val asBigInt
                get() = value

            override fun toString(): String {
                return if (value < BigInteger.ZERO) {
                    value.toString()
                } else {
                    "0x${value.toString(16)}"
                }
            }

            override fun compareTo(other: Integer) = this.value.compareTo(other.value)

            /** Returns a [TACValue.PrimitiveValue.Integer] that represents the sum of [value] and [summand]. */
            operator fun plus(summand: BigInteger): Integer = Integer(this.value + summand)

            /** Returns a [TACValue.PrimitiveValue.Integer] that represents the sum of [value] and [summand]. */
            operator fun plus(summand: Integer): Integer = Integer(this.value + summand.value)
        }

        @KSerializable
        sealed class Bool private constructor(val value: Boolean) : PrimitiveValue(), ConcreteTacValue {

            @KSerializable
            object True: Bool(true) {
                private fun readResolve(): Any = True
                override fun hashCode() = hashObject(this)
            }

            @KSerializable
            object False: Bool(false) {
                private fun readResolve(): Any = False
                override fun hashCode() = hashObject(this)
            }

            override val asBigInt: BigInteger
                get() = if (value) {
                    BigInteger.ONE
                } else {
                    BigInteger.ZERO
                }

            override fun asConstantTacExpr(): TACExpr.Sym.Const =
                if (value) {
                    TACSymbol.True.asSym()
                } else {
                    TACSymbol.False.asSym()
                }

            override fun toString(): String {
                return value.toString()
            }

            companion object {

                operator fun invoke(value: BigInteger) = invoke(value != BigInteger.ZERO)
                operator fun invoke(value: Boolean) =
                    if (value) {
                        True
                    } else {
                        False
                    }
            }
        }

        @Serializable
        data class UninterpretedSortValue(val serialNumber: Int = 0) : TACValue()
    }


    @Serializable
    sealed class MappingValue : TACValue() {

        @Serializable
        class MapDefinition(val exp: TACExpr.MapDefinition): MappingValue(), ConcreteTacValue {

            override fun asConstantTacExpr(): TACExpr = exp

            override fun toString(): String = TACExprPrettyPrinter.print(exp)

            override fun hashCode(): Int = hash { it + exp }

            override fun equals(other: Any?): Boolean {
                if (other !is MapDefinition) { return false }
                return other.exp == this.exp
            }

            /**
             * Returns the value of this mapping at the given index tuple.
             */
            operator fun get(indices: List<TACValue>): TACValue {
                val paramToValue = exp.defParams
                    .map { it.s }
                    .zip(indices)
                    .toTreapMap()
                return TACExprInterpreter.eval(paramToValue, exp.definition)
            }

            fun store(loc: TACValue, value: TACValue) : MapDefinition {
                return MapDefinition(
                    TACExpr.MapDefinition(
                        this.exp.defParams,
                        TACExpr.Store(
                            this.exp.definition,
                            (loc as ConcreteTacValue).asConstantTacExpr(),
                            (value as ConcreteTacValue).asConstantTacExpr()
                        ),
                        this.exp.tag
                    )
                )
            }

            companion object {

                val IdentityWordMap =
                    MapDefinition(
                        TACSymbol.Var("x", Tag.Bit256).asSym().let { x ->
                           TACExpr.MapDefinition(listOf(x), x, Tag.WordMap)
                        }
                    )


                private val txf = TACExprFactUntyped

                private fun translateApplicationExp(e: SmtExp.Apply, symbolTable: SmtSymbolTable?): TACExpr {
                    fun unsupp(): Nothing = throw UnsupportedOperationException(
                        "failed to translate SMT application expression \"$e\" " +
                            "back to a TAC expression when translating an UF or array model"
                    )
                    val args = e.args.map { translateBody(it, symbolTable) }
                    return if (e.isDatatypeLiteral(symbolTable)) {
                        SKey.fromSmtExp(e).asConstantTacExpr()
                    } else {
                        txf {
                            when (e.fs) {
                                is SmtIntpFunctionSymbol.Core.Ite -> Ite(args[0], args[1], args[2])
                                is SmtIntpFunctionSymbol.Core.Eq -> Eq(args[0], args[1])
                                is SmtIntpFunctionSymbol.Core.Distinct -> {
                                    LAnd(args.unorderedPairs().map { (l, r) -> LNot(Eq(l, r)) }.toList())
                                }

                                is SmtIntpFunctionSymbol.Core.LNot -> LNot(args[0])
                                is SmtIntpFunctionSymbol.Core.LAnd -> LAnd(args)
                                is SmtIntpFunctionSymbol.Core.LOr -> LOr(args)
                                is SmtIntpFunctionSymbol.Core.LXor -> LNot(Eq(args[0], args[1]))
                                is SmtIntpFunctionSymbol.Core.LImplies -> LOr(LNot(args[0]), args[1])
                                is SmtIntpFunctionSymbol.BV.Rel.BvUGe,
                                is SmtIntpFunctionSymbol.IRA.Ge -> Ge(args[0], args[1])
                                is SmtIntpFunctionSymbol.BV.Rel.BvSGe -> Sge(args[0], args[1])
                                is SmtIntpFunctionSymbol.BV.Rel.BvSGt -> Sgt(args[0], args[1])
                                is SmtIntpFunctionSymbol.BV.Rel.BvUGt,
                                is SmtIntpFunctionSymbol.IRA.Gt -> Gt(args[0], args[1])
                                is SmtIntpFunctionSymbol.BV.Rel.BvSLe -> Sle(args[0], args[1])
                                is SmtIntpFunctionSymbol.BV.Rel.BvSLt -> Slt(args[0], args[1])
                                is SmtIntpFunctionSymbol.BV.Rel.BvULe,
                                is SmtIntpFunctionSymbol.IRA.Le -> Le(args[0], args[1])
                                is SmtIntpFunctionSymbol.BV.Rel.BvULt,
                                is SmtIntpFunctionSymbol.IRA.Lt -> Lt(args[0], args[1])
                                is SmtIntpFunctionSymbol.IRA.Add -> IntAdd(args)
                                is SmtIntpFunctionSymbol.IRA.Mul -> IntMul(args)
                                is SmtIntpFunctionSymbol.IRA.Sub -> IntSub(args[0], args[1])
                                is SmtIntpFunctionSymbol.IRA.Neg -> IntSub(number(0), args[0])
                                else -> unsupp()
                            }
                        }
                    }
                }


                /** Translate the body of a lambda to a tac expression (this expression can be somewhat symbolic,
                 * e.g., containing `ite`s, thus we can't just use `smtExpToTacValue` from CounterExampleModel.kt here.
                 */
                private fun translateBody(e: SmtExp, symbolTable: SmtSymbolTable?): TACExpr {
                    fun unsupp(): Nothing = throw UnsupportedOperationException(
                        "failed to translate SMT expression \"$e\" " +
                            "back to a TAC expression when translating an UF or array model"
                    )
                    return when (e) {
                        is SmtExp.QualIdentifier ->
                            // expecting this to be only the vars bound by the lambda (right?..) -- maybe check ..
                            try {
                                // this might fail in case the name has "@<callIndex>" in it ..
                                TACSymbol.Var(e.id.toString(), sortToTag(e.sort!!)).asSym()
                            } catch (_: IllegalArgumentException) {
                                // in case of a nontrivial id, we parse it (but not always since it might have
                                // some overhead)
                                try {
                                    "${e.id}:${sortToTag(e.sort!!)}".parseExpr()
                                } catch (@Suppress("TooGenericExceptionCaught") _: Exception) {
                                    unsupp()
                                }
                            }
                        is SmtExp.IntLiteral -> e.i.asTACExpr
                        is SmtExp.BoolLiteral -> e.b.asTACExpr
                        is SmtExp.BitvectorLiteral -> e.n.asTACExpr
                        is SmtExp.Apply -> translateApplicationExp(e, symbolTable)
                        else -> unsupp()
                    }
                }

                private fun sortToTag(s: Sort): Tag {
                    fun unsupp(): Nothing =
                        throw UnsupportedOperationException("failed to convert sort \"$s\" to Tag when translating " +
                            "an UF or array model")
                    return when (s) {
                        SKeySort -> skeySort
                        Sort.BV256Sort,
                        Sort.IntSort -> Tag.Bit256
                        is Sort.Apply ->
                            if (s.symbol is SortSymbol.Intp.Array) {
                                Tag.GhostMap(s.args.dropLast(1).map { sortToTag(it) }, sortToTag(s.args.last()))
                            } else {
                                unsupp()
                            }
                        is Sort.Symbol,
                        is Sort.Param -> unsupp()
                    }
                }

                private fun translateLambdaBoundVar(e: SortedVar): TACExpr.Sym.Var =
                    TACSymbol.Var(e.id, sortToTag(e.sort)).asSym()

                fun fromSmtExp(e: SmtExp, symbolTable: SmtSymbolTable?): MapDefinition {
                    fun unsupp(): Nothing = throw UnsupportedOperationException(
                        "failed to translate SMT expression \"$e\" to a TACValue.MapDefinition"
                    )
                    return when  {
                        e is SmtExp.Lambda -> {
                            // e.g. bitwuzla produces lambda variables that aren't valid TACSymbols (starting with @ is
                            // the problem I think)
                            // so we need to alpha-rename those before doing the translation
                            val script = symbolTable?.let { SmtScript(it) } ?: FactorySmtScript

                            val substitution = mutableMapOf<SmtExp, SmtExp>()
                            val renamedParams = mutableListOf<SortedVar>()
                            e.args.forEachIndexed { i, sortedVar ->
                                val newName = "arg$i"
                                val oldId = sortedVar.toQualId(script)
                                val newId = script.id(newName, sortedVar.sort)
                                substitution[oldId] = newId
                                renamedParams += sortedVar.copy(id = newName)
                            }
                            val bodySubstituted = Substitutor(substitution, script).expr(e.e, Unit)

                            // to do: prob use factory for TACExpr
                            MapDefinition(
                                TACExpr.MapDefinition(
                                    renamedParams.map { translateLambdaBoundVar(it) },
                                    translateBody(bodySubstituted, symbolTable),
                                    Tag.ByteMap // might need to revise this being always Bytemap in time ..
                                )
                            )
                        }
                        e is SmtExp.Apply.Unary && e.fs is SmtIntpFunctionSymbol.Array.Const -> {
                            check(e.sort?.isArraySort() == true) { "expecting an array sort, got \"${e.sort}\"" }
                            val arraySort = sortToTag(e.sort!!)
                            val paramSort = (arraySort as Tag.GhostMap).paramSorts.singleOrNull() ?:
                                throw UnsupportedOperationException("expecting only one-dimensional map models " +
                                    "(for now), got \"$e\"")
                            MapDefinition(TACExpr.MapDefinition(
                                listOf(TACSymbol.Var("x", paramSort).asSym()),
                                translateBody(e.args.single(), symbolTable),
                                Tag.ByteMap
                            ))
                        }
                        e is SmtExp.Apply.Ternary && e.fs is SmtIntpFunctionSymbol.Array.Store -> {
                            val base = fromSmtExp(e.args[0], symbolTable)
                            check(base.exp.defParams.size == 1) { "only one-dimensional (non-nested) maps are " +
                                "supported for now"  }
                            val loc = translateBody(e.args[1], symbolTable)
                            val value = translateBody(e.args[2], symbolTable)
                            return MapDefinition(TACExpr.MapDefinition(
                                base.exp.defParams,
                                txf {
                                    Ite(
                                        Eq(base.exp.defParams.single(), loc),
                                        value,
                                        base.exp.definition
                                    )
                                },
                                Tag.ByteMap
                            ))
                        }
                        else -> unsupp()
                    }
                }
            }
        }


        /**
         * An abstract class used for map-like structures which support select (read) and store (write) operations.
         */
        @Serializable
        sealed class KVMapping : MappingValue() {
            /** A default value for uninitialized entries */
            abstract val initialValue: PrimitiveValue.Integer?

            /**
             * Reads a bv256 value from a given offset.
             * @offset the offset to read from. If no value is stored there, returns [initialValue] if not null or
             * otherwise handles the Uninitialized case
             * @alwaysInitialized A flag stating whether we should generate a value in case the entry is
             * uninitialized
             * @valueOracle the oracle to use in case we need to generate a value
             * @return Returns the TACValue that is associated with [offset] and, in case this call required to
             * store a new value (can happen if [offset] points to an uninitialized slot and [alwaysInitialized] is
             * TRUE) it also returns the new KVMapping (since KVMappings are immutable in nature)
             */
            abstract fun select(offset: TACValue, alwaysInitialized: Boolean, valueOracle: TACValueGenerator)
                    : Pair<TACValue, KVMapping?>
            abstract fun store(offset: TACValue, value: PrimitiveValue.Integer): KVMapping

            /**
             * @bound the first offset of the immutable space
             * @boundConst the value to return when calling select with offset above the bound
             */
            @Serializable
            @Treapable
            data class Bound(
                val bound: PrimitiveValue.Integer,
                val boundConst: PrimitiveValue.Integer
            ) {
                /**
                 * Returns TRUE if [offset] is below the bound, i.e. within the ByteMap's mutable space, or FALSE if it
                 * is above the bound where the ByteMap is immutable and pre-set with constant value
                 */
                fun isBelowBound(offset: PrimitiveValue.Integer): Boolean {
                    return offset.value < bound.value
                }
            }

            /**
             * @value The underlying Kotlin map, holds the actual concrete entries
             * @initialValue A default value to return in case no entry was set, if null, the WordMap will return
             * TACValue.Uninitialized in such cases. This is only relevant for map that were defined with a const map
             * definition. For example, the definition [i -> 0x0] will result in a initVal=0x0
             */
            @Serializable(with = WordMapSerializer::class)
            data class WordMap(
                val value: TreapMap<SKey, PrimitiveValue.Integer> = treapMapOf(),
                override val initialValue: PrimitiveValue.Integer? = null
            ) : KVMapping() {

                operator fun get(offset: SKey): TACValue = internalSelect(offset)

                private fun internalSelect(offset: SKey): TACValue {
                    return value[offset] ?: initialValue ?: Uninitialized
                }

                /**
                 * Reads a bv256 value from a given offset.
                 * @offset the offset to read from. If no value is stored there, returns [initialValue] if not null or
                 * otherwise handles the Uninitialized case
                 * @alwaysInitialized A flag stating whether we should generate a value in case the entry is
                 * uninitialized
                 * @valueOracle the oracle to use in case we need to generate a value
                 * @return Returns the TACValue that is associated with [offset] and, in case this call required to
                 * store a new value (can happen if [offset] points to an uninitialized slot and [alwaysInitialized] is
                 * TRUE) it also returns the new WordMap (since WordMaps are immutable in nature)
                 */
                override fun select(offset: TACValue, alwaysInitialized: Boolean, valueOracle: TACValueGenerator)
                        : Pair<TACValue, WordMap?> {
                    require(offset is SKey) {"offset isn't of type SKey. offset=$offset"}
                    return internalSelect(offset).let { value ->
                        if (value == Uninitialized) {
                            if (alwaysInitialized) {
                                val randValue = valueOracle.getUnsignedRandomBit256()
                                val updatedMap = internalStore(offset, randValue)
                                randValue to WordMap(updatedMap, initialValue)
                            } else {
                                Uninitialized to null
                            }
                        } else {
                            value to null
                        }
                    }
                }

                private fun internalStore(offset: SKey, value: PrimitiveValue.Integer):
                        TreapMap<SKey, PrimitiveValue.Integer> {
                    return this.value + (offset to value)
                }

                override fun store(offset: TACValue, value: PrimitiveValue.Integer): KVMapping {
                    require(offset is SKey) { "offset isn't of type SKey. offset=$offset" }
                    val updatedMap = internalStore(offset, value)
                    return WordMap(updatedMap, initialValue)
                }

                operator fun contains(offset: SKey): Boolean {
                    return value.containsKey(offset)
                }

                override fun toString(): String {
                    return initialValue?.let { "[i -> $initialValue][${value.size}]" }
                        ?: "WordMap[${value.size}]"
                }
            }

            /**
             * @map The underlying Kotlin map, holds the actual concrete entries
             * @keys Holds the original offsets used in Store/Select operations. Required in order to translate the
             * ByteMap into a constraint expression for the SMT solver.
             * @bound A bound on the offsets that are writeable. Only offsets below the bound can be used for storing
             * values. Bound also define the value to return on select requests with offsets above the bound. This is
             * only relevant for maps that were defined with an ite map definition. For example, the definition
             * [i -> i < R56 ? * : 0x0] when evaluated with a state saying R56 -> 0x123, will result in a
             * Bound(0x123, 0x0).
             * @initialValue A default value to return in case no entry was set, if null, the ByteMap will return
             * TACValue.Uninitialized in such cases. This is only relevant for map that were defined with a const map
             * definition. For example, the definition [i -> 0x0] will result in a initVal=0x0
             */
            @Serializable(with = ByteMapSerializer::class)
            @Suppress("DataClassShouldBeImmutable")
            data class ByteMap(
                val map: TreapMap<BigInteger, Byte> = treapMapOf(),
                var accessedIndices: TreapSet<PrimitiveValue.Integer> = treapSetOf(),
                val bound: Bound? = null,
                override val initialValue: PrimitiveValue.Integer? = null
            ) : KVMapping() {

                fun get(offset: PrimitiveValue.Integer): TACValue = internalSelect(offset)

                private fun internalSelect(offset: PrimitiveValue.Integer): TACValue {
                    // check that this offset, when considering word granularity, is entirely below the bound
                    if (!isBelowBound(PrimitiveValue.Integer(offset.value + EVM_WORD_SIZE - BigInteger.ONE))) {
                        return bound!!.boundConst
                    }

                    // extract SIZE_OF_WORD_IN_BYTES bytes starting from offset and translate to PrimitiveValue.Integer
                    // (via BigInteger)
                    val seg = extractSegment(map, offset.value, EVM_WORD_SIZE, true)
                    return if (seg.second != EVM_WORD_SIZE.toInt()) {
                        PrimitiveValue.Integer(BigInteger(seg.first.values.toByteArray()))
                    } else {
                        initialValue ?: Uninitialized
                    }
                }

                /**
                 * Reads a bv256 value from a given offset.
                 * @offset the offset to read from. If no value is stored there, return [initialValue] if not null or
                 * otherwise handle the Uninitialized case
                 * @alwaysInitialized A flag stating whether we should generate a value in case the entry is uninitialized
                 * @valueOracle the oracle to use in case we need to generate a value
                 * @return Returns the TACValue that is associated with [offset] and, in case this call required to store a
                 * new value (can happen if [offset] points to an uninitialized slot and [alwaysInitialized] is TRUE) it
                 * also returns the new ByteMap (since ByteMaps are immutable in nature)
                 */
                override fun select(offset: TACValue, alwaysInitialized: Boolean, valueOracle: TACValueGenerator)
                        : Pair<TACValue, ByteMap?> {
                    require(offset is PrimitiveValue.Integer) {
                        "offset isn't of type PrimitiveValue.Integer. offset=$offset"}
                    return internalSelect(offset).let { value ->
                        if (value == Uninitialized) {
                            if (alwaysInitialized) {
                                val randValue = valueOracle.getUnsignedRandomBit256()
                                val updatedMap = internalStore(offset, randValue)
                                val updatedKeys = accessedIndices + offset
                                randValue to ByteMap(updatedMap, updatedKeys, bound, initialValue)
                            } else {
                                Uninitialized to null
                            }
                        } else {
                            accessedIndices += offset // maintain the list of offset that were addressed by the
                                                      // program
                            value to null
                        }
                    }
                }

                private fun internalStore(offset: PrimitiveValue.Integer, value: PrimitiveValue.Integer):
                        TreapMap<BigInteger, Byte> {
                    // translate [value] to byte array and project it on the underlying [map]
                    val valAsArray = value.value.toByteArray()
                    val valAsWordInMap = treapMapOf<BigInteger, Byte>().builder()
                    for (i in 0 until EVM_WORD_SIZE.toInt()) {
                        if (i < EVM_WORD_SIZE.toInt() - valAsArray.size) {
                            valAsWordInMap[i.toBigInteger() + offset.value] = 0
                        } else {
                            valAsWordInMap[i.toBigInteger() + offset.value] =
                                valAsArray[i - (EVM_WORD_SIZE.toInt() - valAsArray.size)]
                        }
                    }
                    return map + valAsWordInMap
                }

                /**
                 * Stores a concrete value in the given offset
                 * @offset the offset to store in
                 * @value the value to store
                 * @return returns the new ByteMap (since ByteMaps are immutable)
                 */
                override fun store(offset: TACValue, value: PrimitiveValue.Integer): KVMapping {
                    require(offset is PrimitiveValue.Integer) {
                        "offset isn't of type PrimitiveValue.Integer. offset=$offset"
                    }
                    // check that this offset, when considering word granularity, is entirely below the bound
                    check(isBelowBound(PrimitiveValue.Integer(offset.value + EVM_WORD_SIZE - BigInteger.ONE)))
                    { "Trying to store beyond the bound of a bounded ByteMap. offset=$offset, bound=${bound!!.bound}" }
                    val updatedMap = internalStore(offset, value)
                    val updatedKeys = accessedIndices + offset
                    return ByteMap(updatedMap, updatedKeys, bound, initialValue)
                }

                /**
                 * Extracts a segment from the source ByteMap and project it on this `ByteMap`
                 * @srcMap the source map
                 * @srcOffset the first offset from where to extract the segment
                 * @dstOffset the first offset where the segment should be written to
                 * @length the size of the segment to copy
                 * @return returns the new ByteMap (since ByteMaps are immutable)
                 */
                fun longStore(
                    srcMap: ByteMap, srcOffset: PrimitiveValue.Integer, dstOffset: PrimitiveValue.Integer,
                    length: PrimitiveValue.Integer
                ): ByteMap {
                    // check that the entire segment is targeted below the bound
                    check(isBelowBound(PrimitiveValue.Integer(dstOffset.value + length.value - BigInteger.ONE))) {
                        "Trying to longStore beyond the bound of BoundedByteMap. dOffset=$dstOffset" }
                    val relevantSrcKeys = srcMap.accessedIndices.asSequence().filter {
                        it.value in (srcOffset.value.rangeTo(srcOffset.value + length.value.dec()))
                    }.map {
                        PrimitiveValue.Integer(it.value + dstOffset.value - srcOffset.value)
                    }
                    val relevantDstKeys = this.accessedIndices.removeAll {
                        it.value in (dstOffset.value.rangeTo(dstOffset.value + length.value.dec()))
                    }
                    // extract the relevant segment from the src map and copy it to the dst map starting from the dst offset
                    val segment = extractSegment(srcMap.map, srcOffset.value, length.value, false).first.asSequence().map {
                        it.key - srcOffset.value + dstOffset.value to it.value
                    }
                    return ByteMap(map + segment, relevantDstKeys + relevantSrcKeys, bound, initialValue)
                }

                /**
                 * Returns the offsets used in Store/Select operations. Required in order to translate the ByteMap into a
                 * constraint expression for the SMT solver.
                 */
                fun getEntries(): Sequence<Pair<PrimitiveValue.Integer, TACValue>> {
                    return accessedIndices.asSequence().map { it to internalSelect(it) }
                }

                override fun toString(): String {
                    return bound?.let { "[i -> i < ${bound.bound} ? * : ${bound.boundConst}][${accessedIndices.size}]" }
                        ?: initialValue?.let { "[i -> $initialValue][${accessedIndices.size}]" }
                        ?: "ByteMap[${accessedIndices.size}]"
                }



                /**
                 * Tries to extract [length] values starting from [offset]. If [fillZeros] is true, it will fill with
                 * zeros the missing offsets.
                 * Returns the extracted map and the number of slots that were filled with zeroes (only relevant if
                 * `fillZeros` is true)
                 */
                private fun extractSegment(
                    src: Map<BigInteger, Byte>,
                    offset: BigInteger,
                    length: BigInteger,
                    fillZeros: Boolean
                ): Pair<TreapMap<BigInteger, Byte>, Int> {
                    val segment = src.filterKeysTo(treapMapOf<BigInteger, Byte>().builder()) {
                        it in (offset.rangeTo (offset + length.dec()))
                    }
                    var fillCnt = 0
                    if (fillZeros) {
                        var i: BigInteger = offset
                        while (i < offset + length) {
                            val v = segment.putIfAbsent(i, 0)
                            if (v == null) {
                                fillCnt += 1
                            }
                            i += BigInteger.ONE
                        }
                    }
                    return segment.build() to fillCnt
                }

                /**
                 * Returns TRUE if [offset] is below the bound, i.e. within the ByteMap's mutable space, or above the bound
                 * where the ByteMap is immutable and pre-set with constant value
                 */
                private fun isBelowBound(offset: PrimitiveValue.Integer): Boolean {
                    return bound?.isBelowBound(offset) ?: true
                }
            }
        }

        @Serializable
        data class UninterpretedFunction(val value: Map<List<TACValue>, TACValue>) :
            MappingValue() {
            operator fun get(offsets: List<TACValue>): TACValue =
                value[offsets] ?: Uninitialized

            /** Uninterpreted functions don't have a store operation in TAC but we still need to update them sometimes
             * as a side effect of fixing their value at some offset during evaluation. */
            fun store(offsets: List<TACValue>, storedVal: TACValue): UninterpretedFunction {
                if (this[offsets] == storedVal) {
                    // nothing to do
                    return this
                }
                require(this[offsets] == Uninitialized)
                { "Cannot initialize an uninterpreted function ($this) twice for the same argument ($offsets)." }
                val newMap = this.value.toMutableMap()
                newMap[offsets] = storedVal
                return this.copy(value = newMap)
            }
        }

        /**
         * We must use Field as the key in order to represent StructAccess, in particular in the SymbolicOracle
         * when constructing the expressions for the solver.
         * structs shouldn't actually be encountered in the fuzzer in the current toolchain because of struct
         * flattening. Currently only havoced structs appear and are supposed to be reduced.
         * This code is for future use if required.
         *
         */
        @Serializable
        data class StructValue(val fields: Map<Tag.UserDefined.Struct.Field, TACValue>) :
            MappingValue() {
            operator fun get(fieldId: Tag.UserDefined.Struct.Field): TACValue =
                fields[fieldId] ?: Uninitialized
        }
    }

    /**
     * [TACValue] representing a storage key (skey) value.
     *
     * Note that we're using the [equals] method here to check semantic equality of the [SKey]s. Currently, this should
     * work fine, since the data classes mirror the skey datatype exactly -- if this ever changes, we'll need to
     * implement a custom equals method. (For an example use, see e.g. [TACExprInterpreter].
     */
    @Serializable
    sealed class SKey : TACValue(), ConcreteTacValue {
        abstract val offset: PrimitiveValue.Integer

        companion object {
            // NB (alex:) the `20` is just a (generous) magic constant used for these here typing purposes only
            // -- if we get higher-arity hashes, this will crash and we should think of something better
            val symbolTable = TACSymbolTable(setOf(TACBuiltInFunction.Hash.skeySort), setOf(), emptyTags(), mapOf())
            private val txf = TACExprFactTypeChecked(symbolTable)

            val skeySmtSort =
                Sort.Symbol(SortSymbol.UserDefined(StorageHashAxiomGeneratorDataTypes.dtSortDecSmt.name, 0))

            /* the functions below provide some syntactic sugar on top of our basic type constructors Nil and Node */
            fun basic(i: BigInteger): SKey = Basic(valueOf(i))
            fun add(op1: SKey, value: BigInteger): TACValue =
                when (op1) {
                    is Basic -> Basic(offset = op1.offset + value)
                    is Node -> Node(children = op1.children, offset = op1.offset + value)
                }

            fun simpleHash(ops: List<SKey>): TACValue = Node(ops.toList(), valueOf(0))

            /**
             * This does some parsing for the names of the skey costructor function symbols -- didn't see an elegant
             * way around that so far ..
             */
            fun fromSmtExp(value: SmtExp): SKey = when (value) {
                is SmtExp.Apply -> {
                    val fsName = value.fs.name.sym
                    if (fsName == AxiomatizedFunctionSymbol.SKeyDt.Basic(Tag.Bit256).name) {
                        Basic(PrimitiveValue.Integer(value.args.single().asBigInt()))
                    } else if (fsName.startsWith(AxiomatizedFunctionSymbol.SKeyDt.SkeyNode.namePrefix) &&
                        /* todo: check for +-1 errors in arity computation..*/
                        fsName.substring(AxiomatizedFunctionSymbol.SKeyDt.SkeyNode.namePrefix.length)
                            .toIntOrNull() == value.args.size - 1
                    ) {
                        Node(
                            value.args.dropLast(1).map { fromSmtExp(it) },
                            PrimitiveValue.Integer(value.args.last().asBigInt()),
                        )
                    } else {
                        throw UnsupportedOperationException(
                            "unexpected function symbol \"${value.fs}\" in value " +
                                    "expression (coming from a model) \"$value\""
                        )
                    }
                }
                is SmtExp.QualIdentifier ->
                    throw UnsupportedOperationException(
                        "unexpected identifier \"${value}\" in value expression (coming from a model), was expecting " +
                                "an skey"
                    )
                else -> throw UnsupportedOperationException(
                    "unexpected symbol type when expecting an skey model " +
                            "value, got symbol \"$value\""
                )
            }
        }

        fun withOffset(offset: PrimitiveValue.Integer) =
            when (this) {
                is Basic -> copy(offset = offset)
                is Node -> copy(offset = offset)
            }

        @Serializable
        data class Basic(override val offset: PrimitiveValue.Integer) : SKey() {
            override fun asConstantTacExpr(): TACExpr =
                txf.Apply(
                    TACBuiltInFunction.Hash.Basic.toTACFunctionSym(),
                    listOf(offset.asConstantTacExpr()),
                )
        }

        @Serializable
        data class Node(val children: List<SKey>, override val offset: PrimitiveValue.Integer) : SKey() {
            override fun asConstantTacExpr(): TACExpr {
                fun hash(args: List<SKey>): TACExpr =
                    txf.Apply(
                        TACBuiltInFunction.Hash.SimpleHashApplication(args.size, HashFamily.Keccack).toTACFunctionSym(),
                        args.map { it.asConstantTacExpr() },
                    )

                return if (offset.value == BigInteger.ZERO) {
                    hash(children)
                } else {
                    txf.Apply(
                        TACBuiltInFunction.Hash.Addition.toTACFunctionSym(),
                        listOf(hash(children), offset.asConstantTacExpr()),
                    )
                }
            }
        }
    }


    // (alex n): I'm sceptical this belongs here
    @Serializable
    object SumIndex : TACValue() { // For uninitialized array/map entries.
        override fun hashCode() = hashObject(this)
        fun readResolve(): Any = SumIndex
    }

}
