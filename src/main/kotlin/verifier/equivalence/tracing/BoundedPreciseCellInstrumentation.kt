/*
 *     The Certora Prover
 *     Copyright (C) 2025  Certora Ltd.
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, version 3 of the License.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY, without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR a PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package verifier.equivalence.tracing

import analysis.CommandWithRequiredDecls
import analysis.TACExprWithRequiredCmdsAndDecls
import evm.EVM_BYTE_SIZE
import evm.EVM_WORD_SIZE
import tac.Tag
import vc.data.*
import vc.data.tacexprutil.ExprUnfolder
import datastructures.stdcollections.*
import vc.data.TACExprFactoryExtensions.mul
import vc.data.TACProgramCombiners.andThen

/**
 * Instrumentation for computing the *precise* value in a cell of memory
 * up to some bound. The bound of the window is [windowSize].
 * We do this by recording the number of writes to the cell in [writeCountVar],
 * and updating a shadow version of the [shadowAccum] for the last such writes.
 *
 * We can determine which writes were the last `k` by using the [finalWriteCountProphecy] to send
 * the final write "back through time". See below
 */
internal class BoundedPreciseCellInstrumentation(
    val windowSize: Int,
    val writeCountVar: TACSymbol.Var,
    val shadowAccum: TACSymbol.Var,
    val finalWriteCountProphecy: TACSymbol.Var
) : InstrumentationMixin {

    /**
     * Turn an expression representing a number of bytes into the number of bits
     */
    context(TACExprFact)
    private fun ToTACExpr.bytesToBits() = this mul EVM_BYTE_SIZE

    /**
     * Give a *byte* width `this`, compute the "mod mask" for `this` to isolate
     * the least significant `this` bytes.
     *
     * The mod mask is used to compute the equivalence of a bitwise and with non-constant arguments, exploiting the fact
     * that for any `k < 256`, `v mod 2^k == v & 2^(k) - 1`. Accordingly, this function simply returns `1 << this * 8`.
     * Note: our axiomatization of BWAnd does *not* handle non-constant masks, but mod does, so we use this.
     */
    context(TACExprFact)
    private fun ToTACExpr.modMaskOfByteWidth() = (1.asTACExpr shiftL (this.bytesToBits()))

    /**
     * Used to isolate the upper bits of a uint256. Computes:
     * `((x >> upperBitStart) mod modMask) << upperBitStart`
     *
     * If (as is always the case), 2^(256 - upperBitStart) == modMask,
     * then is equivalent to:
     * `x & ~(2^upperBitStart - 1)`
     *
     * proof:
     * ```
     * (declare-const upperBitStart (_ BitVec 256))
     * (declare-const modMask (_ BitVec 256))
     *
     * (assert (bvult upperBitStart (_ bv256 256)))
     *
     * (declare-const x (_ BitVec 256))
     * (assert (=
     * 		 modMask
     * 		 (bvshl (_ bv1 256)
     * 				(bvsub (_ bv256 256) upperBitStart))))
     *
     * (define-const mask (_ BitVec 256)
     * 			 (bvnot (bvsub
     * 			  (bvshl (_ bv1 256) upperBitStart)
     * 			  (_ bv1 256))))
     *
     * (define-const res (_ BitVec 256)
     *   (bvshl (bvurem (bvlshr x upperBitStart) modMask) upperBitStart))
     *
     * (assert (distinct res (bvand mask x)))
     * (check-sat)
     * ```
     *
     * Why not just use bwnot? Our axiomatization does *not* work if we have non-constant masks like this, but the above works
     */
    context(TACExprFact)
    private fun ToTACExpr.modMaskUpperBitsWith(upperBitStart: ToTACExpr, modMask: ToTACExpr) = ((this shiftRLog upperBitStart) mod modMask) shiftL upperBitStart

    /**
     * Helper class for stringing together auxiliary definitions. See below
     */
    private class Binder(val tmpVar: TACSymbol.Var, val definingExpr: TACExpr, vararg val extra: TACSymbol.Var) {
        @JvmName("in_withdecl")
        infix fun `in`(f: (TACSymbol.Var) -> TACExprWithRequiredCmdsAndDecls<TACCmd.Simple>) : TACExprWithRequiredCmdsAndDecls<TACCmd.Simple> {
            return getBinding() andThen f(tmpVar)
        }

        private fun getBinding() =
            CommandWithRequiredDecls(
                TACCmd.Simple.AssigningCmd.AssignExpCmd(lhs = tmpVar, rhs = definingExpr),
                setOf(tmpVar) + extra
            )

        @JvmName("in_expr")
        infix fun inExpr(f: (TACSymbol.Var) -> ToTACExpr) : TACExprWithRequiredCmdsAndDecls<TACCmd.Simple> {
            val expr = f(tmpVar).toTACExpr()
            return getBinding() andThen TACExprWithRequiredCmdsAndDecls(exp = expr.toTACExpr(), cmdsToAdd = listOf(), declsToAdd = setOf())
        }
    }

    /**
     * Helper for a (kinda lame) DSL for binding temporary variables. Used like
     * ```
     * bind { someExpr } `in` { tVar ->
     *    ...
     * }
     * ```
     * where `tVar` is a temporary variable generated to hold `someExpr`. `in` is expected to return a
     * [TACExprWithRequiredCmdsAndDecls], to which the binding of `someExpr` to `tVar` is *prepended*.
     *
     * Thus, these temporary bindings are bubbled up, yielded a single [TACExprWithRequiredCmdsAndDecls] with
     * all the temporary bindings in the "right" order.
     *
     * I'm not going to say monad, but maybe you thought it?
     */
    private fun bind(mk: TACExprFactoryExtensions.() -> ToTACExpr) : Binder {
        val holder = TACKeyword.TMP(Tag.Bit256, "preciseTempVar")
        val expr = TACExprFactoryExtensions.mk().toTACExpr()
        if(expr is TACExpr.Unconstrained) {
            val rhs = TACKeyword.TMP(Tag.Bit256, "!Unconstrained")
            return Binder(holder, rhs.asSym(), rhs)
        }
        return Binder(holder, expr)
    }

    override fun atPrecedingUpdate(
        s: IBufferUpdate,
        overlapSym: TACSymbol.Var,
        writeEndPoint: TACSymbol.Var,
        baseInstrumentation: ILongReadInstrumentation
    ): CommandWithRequiredDecls<TACCmd.Simple> {
        val newValueTerm = when(val src = s.updateSource) {
            /**
             * Two cases this handles: copying from the environment and copying from the buffer.
             *
             * These two (enormous!) expressions only make sense if the memory operation overlaps with the target cell.
             * Those checks are not included, assume that the following only happens when there is a least one byte of overlap
             * with the target cell.
             */
            is IWriteSource.EnvCopy -> {
                TACExprFactoryExtensions.run {
                    /**
                     * Encodes the following logic. "return x" means that we take `x` as the new value of the
                     * shadow accumulator. `target` will be prophecy variable which records the byte offset
                     * of the cell we're modeling. The following uses calldata to avoid more notation; substitute for the appropriate
                     * basemap for other cases. Finally, recall that `writeEndPoint` "abbreviates" s.updateLoc + s.length
                     *
                     * ```
                     * // the last index (exclusive) touched by this cell
                     * cellEnd = target + 32
                     * if(s.len == 0) {
                     *   return shadowAccum
                     * }
                     * if(s.updateLoc <= target && writeEndPoint >= cellEnd) {
                     *    // relative position of target in source buffer
                     *    return calldata[s.source + (target - s.updateLoc)]
                     * }
                     * // slicing off "earlier" (aka *more*) significant bytes
                     * if(s.updateLoc + s.length < target + 32) {
                     *    // we're copying less than 32 bytes into the middle of this cell
                     *    // give up
                     *    if(target < s.updateLoc) {
                     *       return *
                     *    }
                     *    // number of upper bytes sliced off
                     *    slicedUpperBytes = (s.updateLoc + s.length) - target
                     *    // bytes remaining in shadowAccum
                     *    remainingLowerBytes = 32 - slicedUpperBytes
                     *    // equivalent to shadowAccum & (2 ^ (remainingLowerBytes * 8) - 1)
                     *    remainingShadow = shadowAccum mod modMaskOf(remainingLowerBytes)
                     *    // target >= s.updateLoc from the above, (target - s.updateLoc) is the relative offset
                     *    // in the copied buffer
                     *
                     *    fromBuf = calldata[s.sourceLoc + (target - s.updateLoc)]
                     *    // equivalent to fromBuf & ~(2^(remainingLowerBytes * 8) - 1)
                     *    upperBytes = modMaskUpperBits(fromBuff, upperBitStart = remainingLowerBytes * 8, modMaskOf(upperBytesWidth))
                     *    return upperBytes + remainingShadow
                     * } else {
                     *    // slicing later (less significant) bytes
                     *    // also the count of how many upper bytes remain in shadow accum
                     *    sliceStartIndex = s.updateLoc - target
                     *    lowerBytesWidth = 32 - sliceStartIndex
                     *    remainingShadow = modMaskUpperBits(shadowAccum, upperBitStart = lowerBytesWidth * 8, modMaskOf(sliceStartIndex))
                     *    // the new lower bytes of shadowAccum are coming from the UPPER bytes of the word in calldata
                     *    fromBuf = calldata[s.sourceLoc] >> (sliceStartIndex * 8)
                     *    return fromBuf + remainingShadow
                     * }
                     * ```
                     */
                    bind { baseInstrumentation.baseProphecy add  EVM_WORD_SIZE } `in` { cellEnd ->
                        ite(s.len eq 0, shadowAccum,
                        ite(
                            // overlapping completely
                            (writeEndPoint ge cellEnd) and (s.updateLoc le baseInstrumentation.baseProphecy),
                            select(src.baseMap.asSym(), (src.sourceLoc add (baseInstrumentation.baseProphecy sub s.updateLoc))),
                            ite(
                                // slicing off the top/middle
                                writeEndPoint lt cellEnd,
                                ite(
                                    // actually slicing the middle, give up :(
                                    baseInstrumentation.baseProphecy lt s.updateLoc,
                                    bind { TACExpr.Unconstrained(Tag.Bit256) } inExpr { it },
                                    bind { writeEndPoint sub baseInstrumentation.baseProphecy } `in` { upperBytesWidth ->
                                        bind { EVM_WORD_SIZE sub upperBytesWidth} `in` { remainingLowerBytes ->
                                            bind { baseInstrumentation.baseProphecy sub s.updateLoc } inExpr  { sourceDataStart ->
                                                (shadowAccum mod remainingLowerBytes.modMaskOfByteWidth()) add
                                                    select(
                                                        src.baseMap.asSym(),
                                                        (sourceDataStart add src.sourceLoc)
                                                    ).modMaskUpperBitsWith(upperBitStart = remainingLowerBytes.bytesToBits(), modMask = upperBytesWidth.modMaskOfByteWidth())
                                            }
                                        }
                                    }
                                ),
                                // slicing off the bottom
                                // NB this only happens if the copy targets somewhere within the target range
                                // so there is no need to adjust the start offset
                                bind { s.updateLoc sub baseInstrumentation.baseProphecy } `in` { remainingUpperBytes ->
                                    bind { EVM_WORD_SIZE sub remainingUpperBytes } inExpr  { lowerBytesWidth ->
                                        // Mask out the top `remainingUpperbytes` starting at lowerBytesWidth, combine them with
                                        // the top `lowerBytesWitdh` of the data from `src.baseMap[src.sourceLoc]`, which we
                                        // get by shifting down remainingUpperBytes (where `lowerBytes = 32 - remainingUpperBytes`)
                                        // so the bytes that remain will be top `lowerBytes` from the buffer. Confused?
                                        shadowAccum.modMaskUpperBitsWith(upperBitStart = lowerBytesWidth.bytesToBits(), modMask = remainingUpperBytes.modMaskOfByteWidth()) add
                                            (select(
                                                src.baseMap.asSym(),
                                                src.sourceLoc.asSym()
                                            ) shiftRLog remainingUpperBytes.bytesToBits())
                                    }
                                }
                            )
                        ))
                    }
                }
            }
            is IWriteSource.ByteStore -> {
                /**
                 * Encodes *basically* the same as above, but instead of having to find relative positions, we can just use the
                 * written symbol, which we will call written.
                 *
                 * For completeness:
                 * ```
                 * if(s.updateLoc == target) {
                 *    return written
                 * }
                 * // slicing off the top again
                 * if(s.updateLoc < target) {
                 *    // how many bytes of shadowAccum will remain
                 *    remainingLowerBytes = target - s.updateLoc
                 *    // also how many *lower* bytes of written get merged in
                 *    removedUpperBytes = 32 - remainingLowerBytes
                 *    return written mod modMaskOf(removedUpperBytes) << remainingLowerBytes +
                 *       (shadowAccum mod modMaskOf(remainingLowerBytes))
                 * } else {
                 *    remainingUpperBytes = s.updateLoc - target
                 *    // also how many *upper* bytes of written get merged in
                 *    removedLowerBytes = 32 - remainingUpperBytes
                 *    return modMaskUpperBits(shadowAccum, upperBitsStart = removedLowerBytes * 8, modMask = modMaskOf(remainingUpperBytes)) +
                 *       written >> (remainingUpperBytes * 8)
                 * }
                 * ```
                 */
                TXF {
                    ite(
                        s.updateLoc eq baseInstrumentation.baseProphecy,
                        src.writeSymbol,
                        ite(
                        s.updateLoc lt baseInstrumentation.baseProphecy,
                            bind { baseInstrumentation.baseProphecy sub s.updateLoc } `in` { remainingLowerBytes ->
                                bind { EVM_WORD_SIZE sub remainingLowerBytes } inExpr { upperOverlapBytes ->
                                    (shadowAccum mod remainingLowerBytes.modMaskOfByteWidth()) add
                                        ((src.writeSymbol mod upperOverlapBytes.modMaskOfByteWidth()) shiftL remainingLowerBytes.bytesToBits())
                                }
                            },
                            bind { s.updateLoc sub baseInstrumentation.baseProphecy } `in` { remainingUpperBytes ->
                                bind { EVM_WORD_SIZE sub remainingUpperBytes } inExpr { lowerOverlapBytes ->
                                    (shadowAccum.modMaskUpperBitsWith(upperBitStart = lowerOverlapBytes.bytesToBits(), remainingUpperBytes.modMaskOfByteWidth())) add
                                        // by shifting down by the number of bits to keep in shadow accum,
                                        // we end up with a value that holds exactly the lowerOverlapBytes we expect
                                        (src.writeSymbol shiftRLog remainingUpperBytes.bytesToBits())
                                }
                            }
                        )
                    )
                }

            }
            is IWriteSource.ConditionalReturnCopy,
            is IWriteSource.LongMemCopy,
            is IWriteSource.Other -> {
                TACExprFactTypeCheckedOnlyPrimitives.run {
                    bind { TACExpr.Unconstrained(Tag.Bit256) } inExpr { it }
                }
            }
        }

        /**
         * Now, check to see whether we:
         * 1. overlap, and
         * 2. are one of the final k writes.
         *
         * We do this with the prophecy variable [finalWriteCountProphecy]; simplifying from
         * `finalWriteCountProphecy - k <= writeCountVar`
         *
         * this expression tells us whether to use newValue from above, or keep the original value
         */
        val useNewValue = TACExprFactTypeCheckedOnlyPrimitives.run {
            overlapSym and ((finalWriteCountProphecy intSub writeCountVar) le windowSize.asIntTACExpr)
        }
        return newValueTerm.toCRD() andThen TACExprFactoryExtensions.run {
            CommandWithRequiredDecls(listOf(
                // if this is the first write in the window, initialize the shadow accum with the current value of the cell
                TACCmd.Simple.AssigningCmd.AssignExpCmd(
                    lhs = shadowAccum,
                    rhs = ite(
                        ((finalWriteCountProphecy sub writeCountVar) eq windowSize) and overlapSym,
                        TACKeyword.MEMORY.toVar().get(baseInstrumentation.baseProphecy),
                        shadowAccum
                    )
                ),
                // conditionally update the shadow accumulator, using the new term
                TACCmd.Simple.AssigningCmd.AssignExpCmd(
                    lhs = shadowAccum,
                    rhs = ite(useNewValue, newValueTerm.exp, shadowAccum)
                ),
                TACCmd.Simple.AssigningCmd.AssignExpCmd(
                    lhs = writeCountVar,
                    rhs = ite(overlapSym, writeCountVar add 1, writeCountVar)
                )
            ))
        }
    }

    override fun atLongRead(s: ILongRead): CommandWithRequiredDecls<TACCmd.Simple> {
        /**
         * Immediately before the "long" read, write the value of the shadow acumulator into memory,
         * masking whatever imprecision we are trying to work around. Also update the final write count prophecy.
         */
        return ExprUnfolder.unfoldPlusOneCmd("assumeFinalWriteProphecy", TXF {
            finalWriteCountProphecy eq writeCountVar
        }) {
            TACCmd.Simple.AssumeCmd(it.s, "set prophecy")
        }.merge(finalWriteCountProphecy, writeCountVar) andThen CommandWithRequiredDecls(listOf(
            TACCmd.Simple.AssigningCmd.ByteStore(
                base = TACKeyword.MEMORY.toVar(),
                loc = s.loc,
                value = shadowAccum
            )
        ))
    }

    override val havocInitVars: List<TACSymbol.Var>
        get() = listOf(
            shadowAccum, finalWriteCountProphecy
        )
    override val constantInitVars: List<Pair<TACSymbol.Var, ToTACExpr>>
        get() = listOf(
            writeCountVar to TACSymbol.Zero
        )
}
