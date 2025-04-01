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

package analysis.numeric

import utils.hash
import vc.data.TACSymbol
import java.math.BigInteger

/**
An interface for qualifiers marking that the qualifier relates to an array variable [arrayVar]
 */
interface ArrayAnnot {
    val arrayVar : TACSymbol.Var

    fun <T> argSaturate(equivClass: VariableSaturation, f: (TACSymbol.Var) -> T) : List<T> =
            equivClass(arrayVar).map(f)
}

interface CalldataAnnot {
    val calldataArrayVar: TACSymbol.Var

    fun <T> argSaturate(equivClass: VariableSaturation, f: (TACSymbol.Var) -> T) : List<T> =
            equivClass(calldataArrayVar).map(f)
}

/**
 * Indicates that the qualifier indicates safety for an array as stored in [ArrayAnnot.arrayVar]
 */
interface ArraySafetyAnnot : ArrayAnnot, SelfQualifier<IntQualifier>

interface BinaryQualifer<T> : Qualifier<T> {
    val op1: TACSymbol
    val op2: TACSymbol
    fun <T> argSaturate(equivClasses: VariableSaturation, f: (TACSymbol, TACSymbol) -> T) : List<T> =
            equivClasses(op1).flatMap { o1 ->
                equivClasses(op2).map { o2 ->
                    f(o1, o2)
                }
            }

    override fun relates(v: TACSymbol.Var): Boolean = v == op1 || v == op2
}

interface Flippable {
    fun flip(): IntQualifier
}

sealed class IntQualifier : SelfQualifier<IntQualifier> {
    override abstract fun relatesAny(vars: Collection<TACSymbol.Var>): Boolean

    /**
     * This integer is a "pointer" to the beginning of an (empty) data segment for an array-like constant struct.
     */
    object EmptyDataSegment : IntQualifier() {
        override fun hashCode() = utils.hashObject(this)
        override fun relatesAny(vars: Collection<TACSymbol.Var>): Boolean = false

        override fun saturateWith(equivClasses: VariableSaturation): List<IntQualifier> = listOf(this)
        override fun relates(v: TACSymbol.Var): Boolean = false

    }

    /**
     * The size of the calldata buffer, i.e., this variable is provably equal to tacCalldataSize.
     */
    object CalldataSize : IntQualifier() {
        override fun hashCode() = utils.hashObject(this)
        override fun relatesAny(vars: Collection<TACSymbol.Var>): Boolean = false

        override fun saturateWith(equivClasses: VariableSaturation): List<IntQualifier> = listOf(this)
        override fun relates(v: TACSymbol.Var): Boolean = false
    }

    /**
     * The variable holds the size of the data portion of calldata, i.e., the size of the calldata
     * minus 4 (the size of the sighash).
     */
    object CalldataPayloadSize : IntQualifier() {
        override fun hashCode() = utils.hashObject(this)
        override fun relatesAny(vars: Collection<TACSymbol.Var>): Boolean = false

        override fun saturateWith(equivClasses: VariableSaturation): List<IntQualifier> = listOf(this)
        override fun relates(v: TACSymbol.Var): Boolean = false
    }

    /**
     * This variables holds the (logical) length of an array encoded into calldata. The start location of the
     * encoded array is held in [calldataArrayVar]
     */
    data class LengthOfCalldataArray(override val calldataArrayVar: TACSymbol.Var) : IntQualifier(), CalldataAnnot {
        override fun relatesAny(vars: Collection<TACSymbol.Var>): Boolean = calldataArrayVar in vars

        override fun saturateWith(equivClasses: VariableSaturation): List<IntQualifier> = this.argSaturate(equivClasses, ::LengthOfCalldataArray)
        override fun relates(v: TACSymbol.Var): Boolean = v == calldataArrayVar
    }

    /**
     * A valid calldata index into the array (encoded in the calldata buffer) at [calldataArrayVar]
     */
    data class ValidCalldataArrayIndex(override val calldataArrayVar: TACSymbol.Var) : IntQualifier(), CalldataAnnot {
        override fun relatesAny(vars: Collection<TACSymbol.Var>): Boolean = calldataArrayVar in vars

        override fun saturateWith(equivClasses: VariableSaturation): List<IntQualifier> {
            return argSaturate(equivClasses, ::ValidCalldataArrayIndex)
        }

        override fun relates(v: TACSymbol.Var): Boolean = v == calldataArrayVar
    }

    /**
     * If added to the start of the data segment of the calldata array whose start is in [calldataArrayVar], will
     * yield the element at index [index]
     */
    data class SafeCalldataOffset(override val calldataArrayVar: TACSymbol.Var, val index: TACSymbol.Var) : IntQualifier(), CalldataAnnot {
        override fun relatesAny(vars: Collection<TACSymbol.Var>): Boolean = calldataArrayVar in vars || index in vars

        override fun saturateWith(equivClasses: VariableSaturation): List<IntQualifier> {
            return equivClasses(index).flatMap { indEquiv ->
                argSaturate(equivClasses) {
                    SafeCalldataOffset(it, indEquiv)
                }
            }
        }

        override fun relates(v: TACSymbol.Var): Boolean = calldataArrayVar == v || index == v
    }

    /**
     * This integer actually represents the base pointer of a byte array plus its length
     */
    data class BasePlusLength(override val arrayVar: TACSymbol.Var) : ArrayAnnot, IntQualifier() {
        override fun relatesAny(vars: Collection<TACSymbol.Var>): Boolean = arrayVar in vars

        override fun saturateWith(equivClasses: VariableSaturation): List<IntQualifier> {
            return argSaturate(equivClasses, IntQualifier::BasePlusLength)
        }
        override fun relates(v: TACSymbol.Var): Boolean = v == arrayVar
    }

    abstract override fun saturateWith(equivClasses: VariableSaturation) : List<IntQualifier>

    private fun soAddMeMaybe(v: TACSymbol, out: MutableSet<TACSymbol.Var>) {
        if(v is TACSymbol.Var) {
            out.add(v)
        }
    }

    fun collectReferenced(out: MutableSet<TACSymbol.Var>) {
        when(this) {
            is ConditionVar -> {
                soAddMeMaybe(this.op1, out)
                soAddMeMaybe(this.op2, out)
            }
            is LengthOfArray -> {
                out.add(this.arrayVar)
            }
            is LengthOfElementSegment -> {
                out.add(this.elementBlockVar)
            }
            is ElementOffsetFor -> {
                out.add(this.arrayVar)
                this.index?.let(out::add)
            }
            is SafeArrayOffset -> {
                out.add(this.arrayVar)
                this.index?.let(out::add)
            }
            is ValidIndexOf -> {
                out.add(this.arrayVar)
            }
            is MultipleOf -> Unit
            is SizeOfElementSegment -> {
                out.add(this.arrayVar)
            }
            is SizeOfArrayBlock -> {
                out.add(this.arrayVar)
            }
            is EndOfArraySegment -> {
                out.add(this.arrayVar)
            }
            is UpperBoundProofFor -> {
                out.add(this.mightBeElem)
                this.upperBound.ops.forEach {
                    soAddMeMaybe(it, out)
                }
            }
            CalldataSize,
            CalldataPayloadSize,
            EmptyDataSegment -> {

            }
            is BasePlusLength -> out.add(this.arrayVar)
            is LengthOfCalldataArray -> {
                out.add(this.calldataArrayVar)
            }
            is ValidCalldataArrayIndex -> {
                out.add(this.calldataArrayVar)
            }
            is SafeCalldataOffset -> {
                out.add(this.calldataArrayVar)
                out.add(this.index)
            }
            is UntilEndFor -> out.add(this.arrayElem)
            is LogicalConnective -> {
                out.add(op1)
                out.add(op2)
            }
            is RemainingBound -> {
                out.add(arrayVar)
                remaining.ops.forEach {
                    soAddMeMaybe(it, out)
                }
            }
            is RemainingElementsProofFor -> {
                out.add(arrayVar)
                out.add(indexVar)
            }
            is MaskedOf -> {
                out.add(this.op)
            }
            is HasUpperBound -> this.other.collectReferenced(out)
            is ModularUpperBound -> out.add(this.other)
            is NullabilityFlagFor -> out.add(this.pointerVar)
        }
    }

    data class ConditionVar(
        override val op1: TACSymbol,
        override val op2: TACSymbol,
        override val condition: ConditionQualifier.Condition
    ) : BinaryQualifer<IntQualifier>, IntQualifier(), Flippable, ConditionQualifier {

        override fun hashCode() = hash { it + op1 + op2 + condition }
        override fun relatesAny(vars: Collection<TACSymbol.Var>): Boolean = op1 in vars || op2 in vars

        override fun saturateWith(equivClasses: VariableSaturation): List<ConditionVar> {
            return argSaturate(equivClasses) { a, b ->
                ConditionVar(a, b, condition)
            }
        }

        override fun flip(): ConditionVar {
            return ConditionQualifier.flip(this, ::ConditionVar)
        }
    }

    data class LogicalConnective(
        override val op1: TACSymbol.Var,
        override val op2: TACSymbol.Var,
        override val connective: LogicalConnectiveQualifier.LBinOp,
        override val applyNot: Boolean
    ) : BinaryQualifer<IntQualifier>, IntQualifier(), Flippable, LogicalConnectiveQualifier {

        override fun hashCode() = hash { it + op1 + op2 + connective + applyNot }
        override fun relatesAny(vars: Collection<TACSymbol.Var>): Boolean = op1 in vars || op2 in vars

        override fun saturateWith(equivClasses: VariableSaturation): List<IntQualifier> {
            return argSaturate(equivClasses) { a, b ->
                LogicalConnective(
                    (a as? TACSymbol.Var) ?: return@argSaturate null,
                    (b as? TACSymbol.Var) ?: return@argSaturate null,
                    connective,
                    applyNot
                )
            }.filterNotNull()
        }

        override fun flip(): LogicalConnective {
            return LogicalConnectiveQualifier.flip(this, ::LogicalConnective)
        }


    }

    /**
     * This variable holds the logical length (in number of elements) of the array in [arrayVar]
     */
    data class LengthOfArray(override val arrayVar: TACSymbol.Var) : ArrayAnnot, IntQualifier() {
        override fun relates(v: TACSymbol.Var): Boolean = arrayVar == v
        override fun relatesAny(vars: Collection<TACSymbol.Var>): Boolean = arrayVar in vars

        override fun saturateWith(equivClasses: VariableSaturation): List<IntQualifier> = argSaturate(equivClasses, ::LengthOfArray)
    }

    /**
     * This variable holds the LOGICAL length (in terms of number of elements) of the element segment pointed to by [elementBlockVar]
     */
    data class LengthOfElementSegment(val elementBlockVar: TACSymbol.Var) : IntQualifier() {
        override fun relates(v: TACSymbol.Var): Boolean = elementBlockVar == v
        override fun relatesAny(vars: Collection<TACSymbol.Var>): Boolean = elementBlockVar in vars

        override fun saturateWith(equivClasses: VariableSaturation): List<IntQualifier> = equivClasses(elementBlockVar).map(::LengthOfElementSegment)
    }

    /**
     * This variable, when added to the start of the element block of [arrayVar] yields a valid element pointer.
     *
     * N.B. arrayVar is the start of the array segment, added to that, this does nothing.
     */
    data class ElementOffsetFor(override val arrayVar: TACSymbol.Var, val index: TACSymbol.Var?) : ArrayAnnot, IntQualifier() {
        override fun relates(v: TACSymbol.Var): Boolean = arrayVar == v || index == v
        override fun relatesAny(vars: Collection<TACSymbol.Var>): Boolean = arrayVar in vars || (index != null && index in vars)

        override fun saturateWith(equivClasses: VariableSaturation): List<IntQualifier> = index?.let(equivClasses)?.flatMap { ind ->
            argSaturate(equivClasses) { arr ->
                ElementOffsetFor(arr, ind)
            }
        } ?: argSaturate(equivClasses) {
            ElementOffsetFor(it, null)
        }
    }

    /**
     * This variable, when added to the beginning of the array block pointed to by [arrayVar] yields a safe index.
     *
     * Added to the beginning of the element segment of [arrayVar] this does nothing
     */
    data class SafeArrayOffset(override val arrayVar: TACSymbol.Var, val index: TACSymbol.Var?) : ArraySafetyAnnot, IntQualifier() {
        override fun relates(v: TACSymbol.Var): Boolean = arrayVar == v || index == v
        override fun relatesAny(vars: Collection<TACSymbol.Var>): Boolean = arrayVar in vars || (index != null && index in vars)

        override fun saturateWith(equivClasses: VariableSaturation): List<IntQualifier> = if(index == null) {
            argSaturate(equivClasses) { arr ->
                SafeArrayOffset(arr, null)
            }
        } else {
            equivClasses(index).flatMap { ind ->
                argSaturate(equivClasses) { arr ->
                    SafeArrayOffset(arr, ind)
                }
            }
        }
    }

    /**
     * This variable is a valid index of the array stored in [arrayVar]
     */
    data class ValidIndexOf(override val arrayVar: TACSymbol.Var) : ArrayAnnot, IntQualifier() {
        override fun relates(v: TACSymbol.Var): Boolean = v == arrayVar
        override fun relatesAny(vars: Collection<TACSymbol.Var>): Boolean = arrayVar in vars

        override fun saturateWith(equivClasses: VariableSaturation): List<IntQualifier> = argSaturate(equivClasses, ::ValidIndexOf)
    }

    /**
     * This variable is a multiple of [factor]
     */
    data class MultipleOf(val factor: BigInteger) : IntQualifier() {
        init {
            check(factor > BigInteger.ZERO)
                { "Factor is $factor, should be >0" }
        }
        override fun relates(v: TACSymbol.Var): Boolean = false
        override fun relatesAny(vars: Collection<TACSymbol.Var>): Boolean = false

        override fun saturateWith(equivClasses: VariableSaturation): List<IntQualifier> = listOf(MultipleOf(factor))

    }

    /**
     * The PHYSICAL size (i.e., in bytes) of the data segment of the BASE array pointed to by [arrayVar]
     */
    data class SizeOfElementSegment(override val arrayVar: TACSymbol.Var) : ArrayAnnot, IntQualifier() {
        override fun relatesAny(vars: Collection<TACSymbol.Var>): Boolean = arrayVar in vars

        override fun saturateWith(equivClasses: VariableSaturation): List<IntQualifier> {
            return equivClasses(arrayVar).map(::SizeOfElementSegment)
        }

        override fun relates(v: TACSymbol.Var): Boolean = v == arrayVar
    }

    /**
     * Holds the PHYSICAL length (i.e., in bytes) of the entire array segment held in [arrayVar]
     */
    data class SizeOfArrayBlock(override val arrayVar: TACSymbol.Var) : ArrayAnnot, IntQualifier() {
        override fun relatesAny(vars: Collection<TACSymbol.Var>): Boolean = arrayVar in vars

        override fun saturateWith(equivClasses: VariableSaturation): List<IntQualifier> {
            return argSaturate(equivClasses, ::SizeOfArrayBlock)
        }

        override fun relates(v: TACSymbol.Var): Boolean {
            return v == arrayVar
        }
    }

    /**
     * This variable points to the location in memory where the block for the array [arrayVar] ends.
     */
    data class EndOfArraySegment(override val arrayVar: TACSymbol.Var) : ArrayAnnot, IntQualifier() {
        override fun relatesAny(vars: Collection<TACSymbol.Var>): Boolean = arrayVar in vars

        override fun saturateWith(equivClasses: VariableSaturation): List<IntQualifier> {
            return equivClasses(arrayVar).map(::EndOfArraySegment)
        }

        override fun relates(v: TACSymbol.Var): Boolean = v == arrayVar
    }

    /**
     * This value represents the sum of some program terms ([upperBound]), and EITHER: 1) an array element or
     * something that might be an element ([mightBeElem]).
     *
     * If the result of this addition, i.e., the value to
     * which this qualifier is attached, is strictly less than the end of an array, then the variable of [mightBeElem] is
     * safe for access. Further, at least [upperBound] separates the element from the end of the array.
     *
     * This is often used in the following (pseudo-pattern)
     *
     * maybeElem = element + 32;
     * upperBound = maybeElem + 32
     * if(upperBound < endOfArray) {
     *     ... = memory[marybeElem]
     * }
     *
     * Here two copies of the intqualifier will be attached to the variable upperBound:
     * 1) mightBeElem = element, upperBound = 32 + 32
     * 2) mightBeElem = maybeElem, upperBound = 32
     *
     * When upperBound is proved to be strictly less than endOfArray, then we know that (respectively):
     * 1) element is a valid array element (known), and that it is at least 64 bytes from the end of the array
     * 2) maybeElem is a valid array element (it is derived from adding a positive value to a known element, and has
     * now been shown to be within the memory block), and further it is at least 32 bytes from the end of the array
     *
     * Whyyyy would this come up?
     *
     * abi.decode establishes the safety of reads by doing the following:
     *
     * `x = toRead + sizeof(expectedRead)
     * if(x > endOfBlock) { revert } else { memory`[toRead`] }`
     *
     * Precisely tracking this is key to supporting abi.decode
     *
     * XXX(jtoman): this is dead, it isn't used anymore
     */
    data class UpperBoundProofFor(val mightBeElem: TACSymbol.Var, val upperBound: CanonicalSum) : IntQualifier() {
        override fun saturateWith(equivClasses: VariableSaturation): List<IntQualifier> {
            return equivClasses(mightBeElem).map { o1 ->
                UpperBoundProofFor(o1, upperBound)
            }
        }

        override fun relatesAny(vars: Collection<TACSymbol.Var>): Boolean = mightBeElem in vars || vars.any { upperBound.contains(it) }

        override fun relates(v: TACSymbol.Var): Boolean = mightBeElem == v || v in upperBound
    }

    /**
     * If this value is greater than zero, then there is at least one byte remaining between the
     * element at [arrayElem] and the end of the array
     */
    data class UntilEndFor(val arrayElem: TACSymbol.Var) : IntQualifier() {
        override fun relatesAny(vars: Collection<TACSymbol.Var>): Boolean = arrayElem in vars

        override fun saturateWith(equivClasses: VariableSaturation): List<IntQualifier> {
            return equivClasses(arrayElem).map(IntQualifier::UntilEndFor)
        }

        override fun relates(v: TACSymbol.Var): Boolean = arrayElem == v
    }

    data class MaskedOf(val op: TACSymbol.Var, val bitWidth: Int) : IntQualifier() {
        override fun relatesAny(vars: Collection<TACSymbol.Var>): Boolean = op in vars

        override fun saturateWith(equivClasses: VariableSaturation): List<IntQualifier> {
            return equivClasses(op).map {
                MaskedOf(it, bitWidth)
            }
        }

        override fun relates(v: TACSymbol.Var): Boolean = op == v
    }
    /**
     * The variable with this qualifier represents how many LOGICAL elements remain between the index [indexVar] and the
     * length of [arrayVar]. If this value is provably non-zero, then [indexVar] is a valid index, because we must have
     * that [indexVar] < [arrayVar].length. Further, if the value greater than or equal to some non-zero value c, we can
     * prove that [indexVar] has at least c-1 elements. If [arrayVar] is a bytesArray, this allows using [CanonicalSum]
     * and untilEnd fields to prove future pointer arithmetic safe.
     */
    data class RemainingElementsProofFor(val indexVar: TACSymbol.Var, override val arrayVar: TACSymbol.Var) : IntQualifier(), ArrayAnnot {
        override fun relatesAny(vars: Collection<TACSymbol.Var>): Boolean = arrayVar in vars || indexVar in vars

        override fun saturateWith(equivClasses: VariableSaturation): List<IntQualifier> {
            return equivClasses(indexVar).flatMap { ind ->
                equivClasses(arrayVar).map { array ->
                    RemainingElementsProofFor(ind, array)
                }
            }
        }


        override fun relates(v: TACSymbol.Var): Boolean = v == indexVar || v == arrayVar
    }

    /**
     * This index for [arrayVar] has at least [remaining] elements until the length of the array.
     * If this index is also a valid element offset, (i.e., [arrayVar] is a bytes array), then [remaining]
     * becomes a term describing how many bytes remain before the element at the index this qualifier
     * applies to, and the end of the array. Thus, when this index is added to the element start of
     * [arrayVar], the resulting element pointer will have [remaining] as its [analysis.pta.ArrayStateAnalysis.Value.EndTracking.untilEnd] field
     */
    data class RemainingBound(val arrayVar: TACSymbol.Var, val remaining: CanonicalSum) : IntQualifier() {
        override fun relatesAny(vars: Collection<TACSymbol.Var>): Boolean = arrayVar in vars || vars.any { remaining.contains(it) }

        override fun saturateWith(equivClasses: VariableSaturation): List<IntQualifier> {
            return equivClasses(arrayVar).map { o1 ->
                RemainingBound(o1, remaining)
            }
        }

        override fun relates(v: TACSymbol.Var): Boolean = arrayVar == v || v in remaining
    }

    /**
     * Indicates that this variable has a value that is bounded above by a value abstracted
     * by [other]. The bound is strong or weak depending on the value of [weak]
     */
    data class HasUpperBound(val other: IntQualifier, val weak: Boolean) : IntQualifier() {
        override fun relatesAny(vars: Collection<TACSymbol.Var>): Boolean = other.relatesAny(vars)

        override fun saturateWith(equivClasses: VariableSaturation): List<IntQualifier> {
            return other.saturateWith(equivClasses).map {
                HasUpperBound(it, this.weak)
            }
        }

        override fun relates(v: TACSymbol.Var): Boolean {
            return other.relates(v)
        }
    }

    /**
     * A value v qualified with this has [other] as a strong or weak upper bound as determined by the [strong] flag.
     * Further, the difference between v and [other] must be divisible by [modulus] (note that when [strong] is false, a difference
     * of 0 is still considered divisible by [modulus] so it is *not* correct to assume that there exists at least [modulus]
     * difference between v and [other]).
     */
    data class ModularUpperBound(override val other: TACSymbol.Var, override val modulus: BigInteger, override val strong: Boolean) : IntQualifier(), ModularUpperBoundQualifier {
        override fun relatesAny(vars: Collection<TACSymbol.Var>): Boolean = other in vars

        override fun saturateWith(equivClasses: VariableSaturation): List<IntQualifier> {
            return equivClasses(other).map {
                ModularUpperBound(it, this.modulus, this.strong)
            }
        }

        override fun relates(v: TACSymbol.Var): Boolean {
            return other == v
        }

    }

    /**
     * Captures that [pointerVar] is non-null (a valid, non-null pointer) if the
     * variable qualified with this is non-zero.
     */
    data class NullabilityFlagFor(val pointerVar: TACSymbol.Var) : IntQualifier() {
        override fun relatesAny(vars: Collection<TACSymbol.Var>): Boolean = pointerVar in vars

        override fun saturateWith(equivClasses: VariableSaturation): List<IntQualifier> {
            return equivClasses(pointerVar).map(::NullabilityFlagFor)
        }

        override fun relates(v: TACSymbol.Var): Boolean {
            return v == pointerVar
        }
    }
}
