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

import analysis.LTACCmdView
import analysis.TACExprWithRequiredCmdsAndDecls
import analysis.opt.inliner.Inlinee
import analysis.opt.inliner.InliningCalculator
import analysis.split.Ternary
import config.Config
import datastructures.stdcollections.*
import evm.BITS_IN_A_BYTE
import evm.EVM_BITWIDTH256
import evm.EVM_BYTES_IN_A_WORD
import evm.MAX_EVM_UINT256
import tac.Tag
import utils.*
import vc.data.*
import vc.data.TACCmd.Simple
import vc.data.TACCmd.Simple.*
import vc.data.TACExpr.SimpleHash
import java.math.BigInteger
import tac.MetaMap

object TACUnboundedHashingUtils {
    private val configLengthBound = Config.PreciseHashingLengthBound.get()
    private val configMaskOddBytes = Config.MaskOddBytesWhenHashing.get()

    /* these two flags must be in sync with the python flags */
    private const val pythonOptOptimistic = "--optimistic_hashing"
    private const val pythonOptLengthBound = "--hashing_length_bound"

    /**
     * The assertion message that we report to the user in case of non-optimistic hashing that violates
     * the length bound.
     */
    private val unboundedHashingAssertionMessage =
        "Trying to hash a non-constant length array whose length may exceed the " +
            "bound (set in option \"$pythonOptLengthBound\", " +
            "current value is $configLengthBound). Optimistic unbounded hashing is " +
            "currently deactivated (can be activated via option " +
            "\"$pythonOptOptimistic\")."

    private val txf = TACExprFactUntyped

    private fun hashOfEmptyArray(hashFamily: HashFamily) =
        txf { SimpleHash(length = TACExpr.zeroExpr, args = emptyList(), hashFamily = hashFamily) }


    /**
     * Hash in the bytemap [map] the range from [start] to [start] + [length].
     * Defaults for [start] and [length] are to hash the whole array, starting from 0 and taking it's
     * associated `a_totalLength` variable as the length.
     *
     * @return The [SimpleHash] expression and the fresh variable that was created for it; the caller needs
     *  to make sure that this variable is being registered; if the variable is not given (i.e. the second
     *  element of the returned Pair is null), nothing needs to be done.
     *
     *
     * The caller may pass a list of precomputed "leading constants" via [leadingConstants]; when doing this, it must be
     * guaranteed that the first [leadingConstants.size] hashed items can only have those (constant) values, and that
     * [length] must at least be long enough to contain them all.
     * The caller may also pass a precomputed length via [computedLength] (this can be very beneficial when [length],
     * is not a constant itself, as we can avoid the case distinction then).
     * NB, both [length] and [computedLength] are in bytes (not words).
     */
    fun fromByteMapRange(
        hashFamily: HashFamily,
        map: TACSymbol.Var,
        start: TACExpr = TACExpr.zeroExpr,
        length: TACExpr,
        leadingConstants: List<BigInteger> = emptyList(),
        computedLength: BigInteger? = null,
    ): TACExprWithRequiredCmdsAndDecls<Simple> {
        val lengthConstant = computedLength ?: length.getAsConst()
        require(lengthConstant == null || lengthConstant >= leadingConstants.size.toBigInteger()) {
            "it's not allowed to pass more leading constants than fit into the hashed length; " +
                "leadingConstants: $leadingConstants, computed/observed length: $lengthConstant"
        }
        val (exp, newDecls, newCmds) = if (lengthConstant != null) {
            // length is a constant
            fromByteMapRangeConstantLength(hashFamily, map, start, lengthConstant, leadingConstants)
        } else {
            // length of the array is symbolic
            fromByteMapRangeSymbolicLength(hashFamily, map, start, length, leadingConstants)
        }
        val unfoldDecls = mutableListOf<TACSymbol.Var>()
        val (unfoldedExp, unfoldCmds) = ExprUnfolder.unfold(exp) { tag ->
            val newSym = TACSymbol.Var("arrayHash", tag).toUnique()
            unfoldDecls.add(newSym)
            newSym
        }
        return TACExprWithRequiredCmdsAndDecls(
            exp = unfoldedExp,
            declsToAdd = newDecls + unfoldDecls,
            cmdsToAdd = newCmds + unfoldCmds,
        )
    }

    private fun hashNWords(
        hashFamily: HashFamily,
        map: TACSymbol,
        start: TACExpr,
        numberOfWords: Int,
        bytesLength: TACExpr,
        leadingConstants: List<BigInteger>,
        mask: (TACExpr) -> TACExpr,
    ): TACExpr {
        fun hashedWords(i: Int): TACExpr =
            if (i < leadingConstants.size) {
                // inline leading constant
                leadingConstants[i].asTACExpr
            } else {
                txf { Select(map.asSym(), Add(start, number(i * EVM_BYTES_IN_A_WORD))) }
            }

        val args = mutableListOf<TACExpr>()

        val lastWordIndex = numberOfWords - 1
        for (it in (0 until lastWordIndex)) {
            args.add(hashedWords(it))
        }

        // the last needs masking
        args.add(mask(hashedWords(lastWordIndex)))

        return txf.SimpleHash(bytesLength, args, hashFamily)
    }

    private fun fromByteMapRangeConstantLength(
        hashFamily: HashFamily,
        map: TACSymbol.Var,
        start: TACExpr,
        lengthConstant: BigInteger,
        leadingConstants: List<BigInteger>,
    ): TACExprWithRequiredCmdsAndDecls<Simple> {
        fun maskConstant(toMask: TACExpr, bytesRemaining: Int): TACExpr =
            if (bytesRemaining != 0 && configMaskOddBytes) {
                txf {
                    BWAnd(
                        toMask,
                        number(Ternary.highOnes(BITS_IN_A_BYTE * bytesRemaining)),
                    )
                }
            } else {
                toMask
            }

        fun hash(map: TACSymbol, start: TACExpr, length: Int): TACExpr {
            if (length == 0) {
                return hashOfEmptyArray(hashFamily)
            }

            return hashNWords(
                hashFamily,
                map,
                start,
                length.divRoundUp(EVM_BYTES_IN_A_WORD),
                txf.number(length),
                leadingConstants,
            ) { maskConstant(it, length % EVM_BYTES_IN_A_WORD) }
        }

        val intLen = try {
            lengthConstant.intValueExact()
        } catch (e: ArithmeticException) {
            throw CertoraInternalException(
                CertoraInternalErrorType.HASHING,
                "attempting to hash a constant-length array with a byte length " +
                    "that exceeds the Int range -- let's revisit this (length: \"$lengthConstant\")",
                e
            )
        }

        return TACExprWithRequiredCmdsAndDecls(
            exp = hash(map, start, intLen),
            declsToAdd = setOf(),
            cmdsToAdd = listOf(),
        )
    }

    private fun fromByteMapRangeSymbolicLength(
        hashFamily: HashFamily,
        map: TACSymbol.Var,
        start: TACExpr,
        lengthExpr: TACExpr,
        leadingConstants: List<BigInteger>
    ): TACExprWithRequiredCmdsAndDecls<Simple> {
        val auxVarsToRegister = mutableSetOf<TACSymbol.Var>()
        val cmdsToAdd = mutableListOf<Simple>()

        // make an extra auxvar for oddBytes so we don't repeat the expression all the time
        val oddBytesExpr = txf { Mod(lengthExpr, number(EVM_BYTES_IN_A_WORD)) }
        val oddBytesAuxVar = TACSymbol.Var("oddBytes", Tag.Bit256).toUnique()
        auxVarsToRegister.add(oddBytesAuxVar)
        cmdsToAdd.add(AssigningCmd.AssignExpCmd(oddBytesAuxVar, oddBytesExpr))

        /**
         * Non-constant version of the masking.
         * This returns the expression `toMask & max_uint - ((1 << (256 - oddbits)) - 1)`.
         *
         * [oddBytes] is the number of bytes of [toMask] that overlap beyond the word boundary, i.e.
         * "lengthInBytes % 32"; this function 0s everything after the oddbytes in [toMask].
         */
        fun mask(toMask: TACExpr, oddBytes: TACExpr): TACExpr =
            if (configMaskOddBytes) {
                txf {
                    // Q: should we enforce a precise bwand here through a big case switch? (LIA/NIA don't do the bwand precisely on non-constant masks)
                    // Q: should we introduce aux variables for these masked expressions? (wouldn't be so hard, but would blow up the TAC a bit)

                    // note that the SafeMathNarrows are needed to cast the Tag.Int coming from the
                    // Int-operations to Tag.Bit256; this is sound because there can be no over-/underflows

                    BWAnd( // toMask & max_uint - ((1 << (256 - oddbits)) - 1)
                        toMask,
                        TACExpr.Apply(
                            TACBuiltInFunction.SafeMathNarrow(Tag.Bit256).toTACFunctionSym(),
                            listOf(
                                IntSub( // max_uint - ((1 << (256 - oddbits)) - 1)
                                    number(MAX_EVM_UINT256),
                                    IntSub( // (1 << (256 - oddbits)) - 1
                                        ShiftLeft(
                                            // (1 << (256 - oddbits))
                                            number(1),
                                            TACExpr.Apply(
                                                TACBuiltInFunction.SafeMathNarrow(Tag.Bit256).toTACFunctionSym(),
                                                listOf(
                                                    IntSub( // 256 - oddBits
                                                        number(EVM_BITWIDTH256), //256
                                                        IntMul(number(BITS_IN_A_BYTE), oddBytes) // oddbits
                                                    )
                                                ),
                                                Tag.Bit256
                                            ),
                                        ),
                                        number(1)
                                    )
                                )
                            ),
                            Tag.Bit256
                        )
                    )
                }
            } else {
                toMask
            }


        // we don't count the leading constants into the length bound
        val lengthBoundInBytesWithLeadingConstants = configLengthBound + (leadingConstants.size * EVM_BYTES_IN_A_WORD)
        val leadingConstantsLabel = runIf (leadingConstants.isNotEmpty()) {
            LabelCmd("found ${leadingConstants.size} leading constants in unbounded hash; adding them to the length bound")
        }

        val lengthE = (lengthExpr as? TACExpr.Sym.Var)?.s?.let { it ->
            val meta = it.meta.plus(MetaMap(TACMeta.HASHING_BOUND_LENGTH))
            it.withMeta(meta)
        }?.asSym() ?: lengthExpr
        val hashBoundExp = (txf { lengthE le lengthBoundInBytesWithLeadingConstants.asTACExpr })
        val assertOrAssumeCmd =
            if (Config.OptimisticUnboundedHashing.get()) {
                listOfNotNull(
                    leadingConstantsLabel,
                    LabelCmd("optimistic hashing: assume hashedValue.length <= hashing_length_bound"),
                    AssumeExpCmd(hashBoundExp),
                )
            } else {
                val auxVar = TACSymbol.Var("unboundedHashViolation", Tag.Bool).toUnique()
                auxVarsToRegister.add(auxVar)
                val assertCmd = if(lengthE is TACExpr.Sym.Var) {
                    AssertCmd(auxVar, unboundedHashingAssertionMessage, MetaMap(TACMeta.HASHING_BOUND_ASSERT to lengthE.s))
                } else {
                    AssertCmd(auxVar, unboundedHashingAssertionMessage)
                }
                listOfNotNull(
                    leadingConstantsLabel,
                    LabelCmd("pessimistic hashing: assert hashedValue.length <= hashing_length_bound"),
                    AssigningCmd.AssignExpCmd(auxVar, hashBoundExp),
                    assertCmd
                )
            }
        cmdsToAdd.addAll(assertOrAssumeCmd)

        val nondetAuxVar = TACSymbol.Factory.getFreshAuxVar(
            TACSymbol.Factory.AuxVarPurpose.HASH_APPROX,
            TACSymbol.Var("H", Tag.Bit256)
        )
        auxVarsToRegister.add(nondetAuxVar)

        val nonEmptyHashExp = (1..configLengthBound.divRoundUp(EVM_BYTES_IN_A_WORD)).reversed()
            .fold(nondetAuxVar.asSym() as TACExpr) { acc, index ->
                txf {
                    val hashedWordsCount = index + leadingConstants.size
                    Ite(
                        lengthExpr le number(hashedWordsCount * EVM_BYTES_IN_A_WORD),
                        hashNWords(
                            hashFamily,
                            map,
                            start,
                            hashedWordsCount,
                            lengthExpr,
                            leadingConstants,
                        ) {
                            val oddBytes = txf { Mod(lengthExpr, number(EVM_BYTES_IN_A_WORD)) }
                            mask(it, oddBytes)
                          },
                        acc
                    )
                }
            }
        val possiblyEmptyHashExp =
            if (leadingConstants.isEmpty()) {
                // there are no leading constants, account for the hash being empty
                txf {
                    Ite(
                        lengthExpr eq number(0),
                        hashOfEmptyArray(hashFamily),
                        nonEmptyHashExp,
                    )
                }
            } else {
                // there are leading constants, no need to account for the hash being empty
                // (it's an invariant that only as many leading constants are passed as there is guaranteed to be space
                //  for within `lengthSym`.)
                nonEmptyHashExp
            }
        return TACExprWithRequiredCmdsAndDecls(
            exp = possiblyEmptyHashExp,
            declsToAdd = auxVarsToRegister,
            cmdsToAdd = cmdsToAdd,
        )
    }

    object HashOptimizer {

        /** For the given hash of a range, returns the largest contiguous sequence of constant values being hashed
         * at the start of that range (residing in the given memory array, according to the static analysis we
         * performed in this class). Also makes sure that the length being hashed covers all those constants
         * (will cut the sequence accordingly if needed so all the returned constants are surely hashed by the
         * given command).
         *
         * Returns a list of constants at the start of the hashed memory chunk and the length of the chunk, if that
         * length is known to be constant.
         * NB length is to given in bytes (not words).
         * */
        fun getLeadingConstantsAndLength(
            ltacCmd: LTACCmdView<AssigningCmd.AssignSha3Cmd>,
            inliningCalculator: InliningCalculator
        ): Pair<List<BigInteger>, BigInteger?> {
            val (ptr, rangeHashCmd) = ltacCmd
            val len = inliningCalculator.rhsVal(ptr, rangeHashCmd.op2) as? Inlinee.Term
                ?: return emptyList<BigInteger>() to null
            val lenLowerBoundWords = inliningCalculator.getIntervalsAtRhs(ptr, len).minOrNull?.nOrNull()
                ?.div(EVM_BYTES_IN_A_WORD)
            val lenAsConst = len.asConstOrNull
            val map = inliningCalculator.rhsVal(ptr, rangeHashCmd.memBaseMap) as? Inlinee.Mapping
            var index = inliningCalculator.rhsVal(ptr, rangeHashCmd.op1) as? Inlinee.Term

            if (lenLowerBoundWords == null || map == null || index == null) {
                return emptyList<BigInteger>() to lenAsConst
            }

            val leadingConstants = buildList {
                while (true) {
                    map[index]?.asConstOrNull
                        ?.let(::add)
                        ?: break
                    index += EVM_BYTES_IN_A_WORD
                }
            }

            return if (lenLowerBoundWords >= leadingConstants.size.toBigInteger()) {
                leadingConstants
            } else {
                leadingConstants.subList(0, lenLowerBoundWords.toInt())
            } to lenAsConst
        }
    }

}
