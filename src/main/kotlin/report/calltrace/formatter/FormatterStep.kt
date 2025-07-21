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

@file:Suppress("IfThenToElvis")

package report.calltrace.formatter

import datastructures.stdcollections.*
import evm.MASK_SIZE
import report.BigIntPretty.bigIntPretty
import report.calltrace.formatter.AlternativeRepresentations.Representations
import report.calltrace.formatter.AlternativeRepresentations.RepresentationsMap
import report.calltrace.sarif.Sarif
import report.checkWarn
import report.globalstate.logger
import rules.ContractInfo
import scene.ISceneIdentifiers
import solver.CounterexampleModel
import spec.cvlast.EVMBuiltinTypes.evmBitWidths
import utils.*
import utils.ModZm.Companion.asBigInteger
import vc.data.state.TACValue
import java.math.BigInteger
import java.math.BigInteger.ONE
import java.math.BigInteger.ZERO

/**
 * For [tacValueIn], using the other parameters, compute the final representation(s), as well as the `truncatable` flag.
 */
internal class ValueFormattingJob(val tacValueIn: TACValue,
                         val type: FormatterType.Value<*>,
                         val addrToContract: Map<TACValue.PrimitiveValue.Integer, ContractInfo>,
                         val scene: ISceneIdentifiers,
                         val model: CounterexampleModel) {

    init {
        checkWarn(tacValueIn != TACValue.Uninitialized) {
            "ValueFormattingJob should not get an uninitialized value (it only lists a list of representations but " +
                "cannot do the Sarif-wrapping that should be done in that case)"
        }
    }

    /**
     * Returns the representations and whether the [Pretty] representation is truncatable.
     *
     * The bool is true iff the first argument in the value list is truncatable (i.e. is a hex value, not a short hand
     * or such). Discussion: https://www.notion.so/certora/Formatted-value-representations-WIP-174fe5c14fd380bba775e08063af1fb8?d=174fe5c14fd380729f68001cb30cac3d#174fe5c14fd380bda91cd54709853eb8
     * See also [PrettyRep], on the `truncatable` topic.
     */
    internal fun run(): Pair<RepresentationsMap, Boolean>  {
        var tv = tacValueIn

        val contractName = computeContractName(tv)

        tv = undoBoolReplacement(tv)

        val (tacValuePrettyPrint, truncatableImmut) = computePrettyRepresentation(tv)
        var truncatable = truncatableImmut

        val alternativeRepresentations = AlternativeRepresentations.computeRepresentations(tv, tacValuePrettyPrint, type)

        // wrap things up //

        val res = alternativeRepresentations.map.toMutableMap()

        checkWarn(!type.isAddress || res.size == 3) {
            "if value is an address it must have three values, for \"$tacValueIn\" got \"$res\"; thus not " +
                "replacing the address with the contract name (here: \"$contractName\") (also not replacing the DEC " +
                "with the HEX value in case contract name is null)"
        }

        /** postprocessing for the address case
         * s -> "$contractName (s)" */
        if (type.isAddress && res.size == 3) {
            if (contractName != null) {
                // we throw the value for PRETTY and DEC away in favour of the shorthand (as per the design doc)
                res[Representations.Pretty] = "$contractName"
                res[Representations.Decimal] = "$contractName"
                // HEX is shown as hex -- no update needed

                // we don't allow truncating the contract name
                truncatable = false
            } else {
                // we replace the DEC case with the hexadecimal/pretty (as per the design doc)
                res[Representations.Decimal] = res[Representations.Pretty]!!
                // HEX is shown as hex -- no update needed
            }
        }

        return RepresentationsMap(res) to truncatable
    }

    /**
     * The output of [computePrettyRepresentation].
     *
     * @param tacValuePrettyPrint is a nice representation of the given [TACValue] (e.g. short hands for large numbers,
     *   [undoRightPadding] for `bytes` and more..); not all sub types of [TACValue] have such a nice representation, so
     *   this may be `null`.
     * @param truncatable indicates whether the string in [tacValuePrettyPrint] may be truncated by the front end;
     *   truncation means turning something like `0x1234132512351` into `0x12...51` or so; we currently only allow this
     *   for hex values like this example value (this flag will appear as is in [Sarif.Arg])
     * */
    private data class PrettyRep(val tacValuePrettyPrint: String?, val truncatable: Boolean)

    private fun computePrettyRepresentation(tv: TACValue): PrettyRep {
        /** Prints a value based on its [TACValue]. Set [ValueFormattingJob.tacValuePrettyPrint]. */

        fun ValueMeta.Enum.format(ordinal: BigInteger): String {
            /** We're using the value emitted from the SMT as an array index, so we must validate it */
            val nameOfLiteral = ordinal.toIntOrNull()?.let { idx -> elements.getOrNull(idx) }

            return if (nameOfLiteral != null) {
                "$name.$nameOfLiteral"
            } else {
                "$name($ordinal)"
            }
        }

        var truncatable = false
        val tacValuePrettyPrinted = when (tv) {
            is TACValue.PrimitiveValue -> {
                // precedence:
                //  1. nice type-based representations for enums, bools, and such
                //  2. shorthand from our matching strategies (e.g. powers of two, max_int, etc.)
                //  3. number as hex

                val num = tv.asBigInt
                val meta = type.toMeta()

                if (meta is ValueMeta.Enum) {
                    meta.format(num)
                } else if (meta is ValueMeta.Boolean || tv is TACValue.PrimitiveValue.Bool) {
                    /** XXX: can we can ensure booleans get assigned to [TACValue.PrimitiveValue.Bool]? */
                    val b = num != ZERO
                    b.toString()
                } else if (meta is ValueMeta.Bytes) {
                    truncatable = true
                    num.undoRightPadding(meta.bytewidth).toHexString()
                } else if (type.isAddress) {
                    // addresses don't get the shorthands
                    truncatable = true
                    num.toHexString()
                } else {
                    AlternativeRepresentations.matchingDescriptions(num, type.toMeta())
                        ?: bigIntPretty(num)
                        ?: if (num.abs() <= MASK_SIZE(32)) {
                            truncatable = true
                            num.toString(10)
                        } else {
                            truncatable = true
                            num.toHexString()
                        }
                }
            }

            // (alex n:) `job.tv is TACValue.Skey` is actually not the right criterion for this case.
            //  See the other usages of CounterExampleModel.storageKeyToInteger for the right criterion.
            //  (There it was somewhat easy to do, but here we're missing the type of the tac expression whose model
            //  we're printing unfortunately.)
            //  (background: in the Legacy hashing scheme case, we should use the value of `from_skey(x)` rather than
            //  `x`, just like in the Datatypes case, and then we'll see a TACValue...Integer here)
            is TACValue.SKey -> {
                model
                    .storageKeyToInteger(tv)
                    .mapLeft { integer -> integer.toString() }
                    .leftOrElse { err ->
                        logger.warn { "while formatting $tv: expected hashable SKey, got $err" }
                        null
                    }
            }

            else -> null
        }
        return PrettyRep(tacValuePrettyPrinted, truncatable)
    }

    /**
     * bv256s are allowed to be optimized to booleans.
     * this should be opaque to the user, and the call trace should format using the original type.
     *
     * for example, an expression like `foo(1)` for some internal `function foo(uint256 n)`
     * may have had its parameter replaced with a boolean, changing its string representation to `foo(true)`,
     * which we don't want here.
     *
     * this step un-replaces the value with an integer.
     */
    private fun undoBoolReplacement(tvOld: TACValue): TACValue =
        if(tvOld is TACValue.PrimitiveValue.Bool && !type.isBool) {
            TACValue.PrimitiveValue.Integer(tvOld.value.asBigInteger)
        } else {
            tvOld
        }

    /**
     * Adds the name to values which are addresses of known contracts.
     * for each (non pre-compiled) contract in [scene], [modelAddrToContractName] maps the model's
     * chosen address for that contract to the [ContractInfo] of that address.
     *
     * [scene] will be used in the future to recognize pre-compiled contracts (CERT-1917)
     */
    private fun computeContractName(tv: TACValue): String? {
        unused(scene)

        val modelAddrToContractName: Map<BigInteger, String> =
            addrToContract
                .entries
                .associate { (tv, info) -> tv.asBigInt to info.name }

        fun contractAddr(tv: TACValue, type: FormatterType.Value<*>?): BigInteger? =
            if (tv is TACValue.PrimitiveValue.Integer && type?.toMeta() is ValueMeta.Address) {
                tv.asBigInt
            } else {
                null
            }

        val addr = contractAddr(tv, this.type)
        return modelAddrToContractName[addr]
    }
}


/** Fill the list of alternative representations for a given value (to be cycled through by the users). */
object AlternativeRepresentations {

    private val shortHands: Map<BigInteger, MutableList<OptionalDescription>>

    init {
        shortHands = mutableMapOf()

        fun add(value: BigInteger, desc: String, condition: (ValueMeta?) -> Boolean) {
            val vd = OptionalDescription(desc, condition)
            shortHands
                .getOrPut(value) { mutableListOf() }
                .add(vd)
        }

        for (bitWidth in evmBitWidths) {
            add(powerOfTwoMinusOne(bitWidth), "MAX_UINT$bitWidth") { it is ValueMeta.UIntK && it.bitwidth == bitWidth }

            add(powerOfTwoMinusOne(bitWidth - 1), "MAX_INT$bitWidth") { it is ValueMeta.SignedIntK && it.bitwidth == bitWidth }
            add(powerOfTwo(bitWidth - 1).negate(), "MIN_INT$bitWidth") { it is ValueMeta.SignedIntK && it.bitwidth == bitWidth }

            add(powerOfTwo(bitWidth), "2^$bitWidth") { it is ValueMeta.Mathint }
            add(powerOfTwo(bitWidth).negate(), "-(2^$bitWidth)") { it is ValueMeta.Mathint }
        }

        // would be dead code now, and I'm not sure how useful the shorthand is
        // add(powerOfTwoMinusOne(EVM_ADDRESS_SIZE), "MAX_EVM_ADDRESS") { it is ValueMeta.Address }
    }

    /** This whole mechanism is overlapping with [BigInteger.prettyString], however it takes into account ValueMeta, so
     * it's not subsumed by it. Just keeping it for now -- we can play with these representations later. */
    fun matchingDescriptions(rawValue: BigInteger, meta: ValueMeta?) =
        shortHands[rawValue]
            ?.filter { it.condition(meta) }
            ?.map { it.description }?.firstOrNull()

    enum class Representations(val shortName: String, val example: String) {
        Pretty("PRETTY", "MAX_UINT8"),
        Decimal("DEC", "255") ,
        Hex("HEX", "0xff"),
        ;
    }

    @JvmInline
    value class RepresentationsMap(val map: Map<Representations, String>): Map<Representations, String> by map {
        constructor(pair: Pair<Representations, String>) : this(mapOf(pair))

        init {
            require(Representations.Pretty in map) {
                "the representations List/Map must always at least contain the Pretty representation (but doesn't: $map)"
            }
        }

        fun asRepList() = Representations.values().mapNotNull { map[it] }

        val pretty get() = map[Representations.Pretty]!!
    }


    private data class OptionalDescription(val description: String, val condition: (ValueMeta?) -> Boolean)

    fun computeRepresentations(tv: TACValue, tacValuePrettyPrint: String?, type: FormatterType.Value<*>): RepresentationsMap {
        checkWarn(tacValuePrettyPrint != null) { "tacValuePrettyPrint needs to be set at this point, but is `null`" }
        val default = RepresentationsMap(Representations.Pretty to tacValuePrettyPrint!!)
        return when (type) {
            is FormatterType.Value.Unknown -> default
            is FormatterType.Value.CVL,
            is FormatterType.Value.EVM -> {
                val bigInt = tv.asBigIntOrNull()
                // nb address gets three values here, so goes to the `else` case here, because we may want to replace
                // two of them with the contract name later
                if (type.isBool
                    || type.isBytes
                    || bigInt == null) {
                    default
               } else {
                    RepresentationsMap(
                        mapOf(
                            Representations.Pretty to tacValuePrettyPrint,
                            Representations.Decimal to bigInt.toString(10),
                            Representations.Hex to bigInt.toHexString(),
                        )
                    )
                }
            }
        }
    }
}


private fun powerOfTwo(exp: Int) = ONE.shl(exp)
private fun powerOfTwoMinusOne(exp: Int) = ONE.shl(exp).minus(ONE)


/** BytesK values (where K is the bytewidth) are right-padded to 32 bytes. this removes the right-padding. */
private fun BigInteger.undoRightPadding(k: Int): BigInteger {
    require(k in 1..32) { "expected 1 <= bytewidth <= 32, got $k" }
    val bytesToShift = 32 - k

    return this.shiftRight(8 * bytesToShift)
}
