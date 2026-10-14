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

package sbf.tac

import analysis.LTACCmd
import analysis.LTACSymbol
import analysis.dataflow.StrictDefAnalysis
import analysis.opt.PatternRewriter
import analysis.patterns.InfoKey
import analysis.patterns.PatternHelpers
import analysis.patterns.get
import analysis.patterns.lhs
import datastructures.stdcollections.*
import evm.twoToThe
import java.util.concurrent.ConcurrentHashMap
import utils.*
import vc.data.*
import wasm.analysis.intervals.IntervalAnalysis

/**
    Recognizes the expansion of a 128-bit signed multiply-with-overflow that the Solana platform-tools emit inline
    (rather than as a call to compiler-rt's `__muloti4`, which [SummarizeIntegerU128CompilerRt.summarizeMuloti4]
    already models directly).

    The expansion computes the four partial products with `__multi3` and then accumulates them into a 256-bit value,
    one 64-bit word at a time:

    ```
    p.ll = zext(a) * zext(c)    p.hl = sext(b) * zext(c)
    p.lh = zext(a) * sext(d)    p.hh = sext(b) * sext(d)

    word0 = lo(p.ll)
    word1 = lo(lo(p.lh) + lo(lo(p.hl) + hi(p.ll)))
    word2, word3 = ... the carries out of those, plus p.hh, plus sign-extension words ...
    overflow <=> word2 != signMask(word1) || word3 != signMask(word1)
    ```

    Proving that accumulation equal to the exact product is 128x128 multiplier equivalence checking, which no solver
    we have manages - not in the integer encoding, and not in bitvectors either.  So instead of leaving it to the
    solver we recognize the whole shape and rewrite its three observable outputs (`word0`, `word1` and the overflow
    flag) into the direct form that `__muloti4` would have produced.  `word2` and `word3` feed nothing but the flag,
    so they fall out as dead code.

    All three outputs have to be rewritten together: leaving any one of them expressed in terms of the partial
    products leaves the solver with essentially the same equivalence to prove.
 */
object TACMul128SignedOverflow {

    /** EVM-style `SignExtend` size argument for a value that is 64 bits wide */
    private val SEXT_FROM_64 = 7.toBigInteger()

    /** Bit width of the `SignExtend` that turns a 128-bit two's complement value into a 256-bit one */
    private const val SEXT_FROM_128 = 128

    /** `-2^127`, as a 256-bit two's complement value */
    private val MIN_SIGNED_128 = twoToThe(256) - twoToThe(127)

    /** `2^127 - 1` */
    private val MAX_SIGNED_128 = modz128.maxSigned

    // ---------------------------------------------------------------------------------------------------------
    // Keys
    //
    // The chain reaches the same value from several places.  Any value that is matched twice needs a key per
    // occurrence, because an [analysis.patterns.Info] join compares the *use site* of a symbol, not just the symbol.
    // ---------------------------------------------------------------------------------------------------------

    private class SymKey(private val label: String) : InfoKey<LTACSymbol>() {
        override fun toString() = label
    }

    private class CmdKey(private val label: String) : InfoKey<LTACCmd>() {
        override fun toString() = label
    }

    /**
        A value the chain uses more than once.  It is matched as a subtree at exactly one site - which records the
        command defining it - and referred to by symbol at the other, which [resolvesToItsDefinition] ties back.
     */
    private class Shared(label: String) {
        val definedAt = CmdKey("$label@")
        val reference = SymKey("&$label")
    }

    context(PatternHandlerContext)
    private fun Shared.resolvesToItsDefinition() =
        src(reference) == StrictDefAnalysis.Source.Defs(info[definedAt]!!.ptr)

    // The four 64-bit words of the operands.  `a`/`c` are the low words, `b`/`d` the high ones; each appears in two
    // of the four partial products.
    private val A_IN_LL = SymKey("a.ll")
    private val A_IN_LH = SymKey("a.lh")
    private val B_IN_HL = SymKey("b.hl")
    private val B_IN_HH = SymKey("b.hh")
    private val C_IN_LL = SymKey("c.ll")
    private val C_IN_HL = SymKey("c.hl")
    private val D_IN_LH = SymKey("d.lh")
    private val D_IN_HH = SymKey("d.hh")

    /** The 128-bit values the low words are extracted from, for the `word0` pattern */
    private val X_SRC = SymKey("x")
    private val Y_SRC = SymKey("y")
    private val X_LOW_WORD = CmdKey("x.lo@")
    private val Y_LOW_WORD = CmdKey("y.lo@")

    private val P_HL = Shared("p.hl")
    private val P_LH = Shared("p.lh")
    private val P_HH = Shared("p.hh")
    private val P_HL_LO = Shared("p.hl.lo")
    private val WORD1_SUM = Shared("word1.sum")
    private val WORD2_SUM = Shared("word2.sum")
    private val WORD3_SUM = Shared("word3.sum")
    private val CARRY1_IN = Shared("carry1.in")
    private val ACC_HL = Shared("acc.hl")
    private val ACC_LH = Shared("acc.lh")
    private val SIGN_OF_WORD1 = Shared("word1.sign")

    private val allShared = listOf(
        P_HL, P_LH, P_HH, P_HL_LO, WORD1_SUM, WORD2_SUM, WORD3_SUM, CARRY1_IN, ACC_HL, ACC_LH, SIGN_OF_WORD1
    )

    // ---------------------------------------------------------------------------------------------------------
    // Sub-patterns
    // ---------------------------------------------------------------------------------------------------------

    context(PatternHelpers)
    private fun PI.mod64() = this.rem(c(modz64.modulus))

    context(PatternHelpers)
    private fun PI.lowWord() = this bwAnd c(modz64.maxUnsigned)

    context(PatternHelpers)
    private fun PI.highWord() = this shr c(64)

    /** `(w.sext64 >>a 63) mod 2^64`: all ones when [w]'s low word is negative, zero otherwise */
    context(PatternHelpers)
    private fun signMask(w: PI) = (w.sextEVM(c(SEXT_FROM_64)) shra c(63)).mod64()

    /** A `__multi3` result: the low 128 bits of the product of two 128-bit operands */
    context(PatternHelpers)
    private fun mul128(x: PI, y: PI) =
        maybeNarrow(maybePromote(x) anyMul maybePromote(y)).rem(c(modz128.modulus))

    /** How the frontend widens a 64-bit word into an unsigned `__multi3` operand */
    context(PatternHelpers)
    private fun zeroExtended(lo: PI) = (c(0) shl c(64)) + lo

    /**
        How the frontend widens a 64-bit word into a signed `__multi3` operand, once [sextWidenHandler] has
        normalized it.  In this form the operand denotes `sext64(hi mod 2^64)` whatever [hi] is, which is what lets
        the rewrites below be unconditional in the high words.
     */
    context(PatternHelpers)
    private fun signExtended(hi: PI) = hi.sextEVM(c(SEXT_FROM_64)).rem(c(modz128.modulus))

    /** A 64-bit add that discards its carry */
    context(PatternHelpers)
    private fun wrapAdd(u: PI, v: PI) = (u + v).mod64()

    /** A 64-bit add kept wide, so that the carry out of it survives */
    context(PatternHelpers)
    private fun wideAdd(u: PI, v: PI) =
        maybeNarrow(maybePromote(u) anyAdd maybePromote(v))

    /** The carry out of a [wideAdd] whose result is [sum] */
    context(PatternHelpers)
    private fun carryOf(sum: PI) = ite(sum gt c(modz64.maxUnsigned), c(1), c(0))

    /** The carry out of a [wrapAdd], recovered by comparing its result [lo] against one of its addends */
    context(PatternHelpers)
    private fun carryOfWrapped(lo: PI, addend: PI) = ite(lo lt addend, c(1), c(0))

    // ---------------------------------------------------------------------------------------------------------
    // Replacement expressions
    // ---------------------------------------------------------------------------------------------------------

    /** The 128-bit operand whose words are [lo] and [hi] */
    context(PatternHandlerContext)
    private fun operand128(lo: ToTACExpr, hi: ToTACExpr) =
        ((hi shiftL 64.asTACExpr) add lo) bwAnd modz128.maxUnsigned.asTACExpr

    /** The exact product of two 128-bit signed operands, as a 256-bit two's complement value */
    context(PatternHandlerContext)
    private fun signedProduct128(x: ToTACExpr, y: ToTACExpr) =
        x.sextBits(SEXT_FROM_128) mul y.sextBits(SEXT_FROM_128)

    // ---------------------------------------------------------------------------------------------------------
    // Patterns
    // ---------------------------------------------------------------------------------------------------------

    /**
        Matches the test `word2 == signMask(word1) && word3 == signMask(word1)` - written by the compiler as
        `((word3 ^ signMask(word1)) | (word2 ^ signMask(word1))) == 0` - and with it the whole accumulation that
        produces those words.  Rewrites it to "the exact product fits in a signed 128-bit value".
     */
    private fun PatternRewriter.overflowFlagHandler(
        fitsIn64Bits: (LTACSymbol) -> Boolean,
        recognized: MutableSet<Pair<TACSymbol, TACSymbol>>,
    ) = PatternHandler(
        name = "mul128-signed-overflow-flag",
        pattern = {
            val pll = mul128(zeroExtended(lSym(A_IN_LL)), zeroExtended(lSym(C_IN_LL)))
            val phl = mul128(signExtended(lSym(B_IN_HL)), zeroExtended(lSym(C_IN_HL)))
                .lastCmd(P_HL.definedAt)
            val phlLo = phl.lowWord().lastCmd(P_HL_LO.definedAt)

            val carry1In = wrapAdd(phlLo, pll.highWord()).lastCmd(CARRY1_IN.definedAt)
            val carry1 = carryOfWrapped(lSym(CARRY1_IN.reference), lSym(P_HL_LO.reference))

            val plh = mul128(zeroExtended(lSym(A_IN_LH)), signExtended(lSym(D_IN_LH)))
                .lastCmd(P_LH.definedAt)
            val word1Sum = wideAdd(plh.lowWord(), carry1In).lastCmd(WORD1_SUM.definedAt)
            val carry2 = carryOf(lSym(WORD1_SUM.reference))

            val accHl = wrapAdd(lSym(P_HL.reference).highWord(), carry1).lastCmd(ACC_HL.definedAt)
            val accLh = wrapAdd(lSym(P_LH.reference).highWord(), carry2).lastCmd(ACC_LH.definedAt)

            val word2Sum = wideAdd(accHl, accLh).lastCmd(WORD2_SUM.definedAt)
            val carry3 = carryOf(lSym(WORD2_SUM.reference))

            val phh = mul128(signExtended(lSym(B_IN_HH)), signExtended(lSym(D_IN_HH)))
                .lastCmd(P_HH.definedAt)
            val word3Sum = wideAdd(lSym(P_HH.reference).lowWord(), word2Sum.mod64())
                .lastCmd(WORD3_SUM.definedAt)
            val carry4 = carryOf(lSym(WORD3_SUM.reference))

            val signWords = wrapAdd(signMask(lSym(ACC_HL.reference)), signMask(lSym(ACC_LH.reference)))
            val word3 = wrapAdd(wrapAdd(phh.highWord(), wrapAdd(signWords, carry3)), carry4)
            val word2 = word3Sum.mod64()

            val signOfWord1 = signMask(word1Sum.mod64()).lastCmd(SIGN_OF_WORD1.definedAt)

            ((word3 xor signOfWord1) bwOr (word2 xor lSym(SIGN_OF_WORD1.reference))) eq c(0)
        },
        handle = {
            runIf(isWholeIdiom(fitsIn64Bits)) {
                recognized += lowWordsOfOperands()
                val product = signedProduct128(
                    operand128(sym(A_IN_LL), sym(B_IN_HL)),
                    operand128(sym(C_IN_LL), sym(D_IN_LH))
                )
                (MIN_SIGNED_128.asTACExpr sLe product) and (product sLe MAX_SIGNED_128.asTACExpr)
            }
        },
        TACExpr.BinRel.Eq::class.java,
        regressionMessage = true
    )

    /**
        `word1` of the accumulation, which is `(x * y >> 64) mod 2^64`.  This is a shallower match than the flag: it
        only needs the three partial products that contribute to the low 128 bits of the result.
     */
    private fun PatternRewriter.resultWord1Handler(
        fitsIn64Bits: (LTACSymbol) -> Boolean,
        recognized: MutableSet<Pair<TACSymbol, TACSymbol>>,
    ) = PatternHandler(
        name = "mul128-signed-word1",
        pattern = {
            val pll = mul128(zeroExtended(lSym(A_IN_LL)), zeroExtended(lSym(C_IN_LL)))
            val phl = mul128(signExtended(lSym(B_IN_HL)), zeroExtended(lSym(C_IN_HL)))
            val plh = mul128(zeroExtended(lSym(A_IN_LH)), signExtended(lSym(D_IN_LH)))
            wideAdd(plh.lowWord(), wrapAdd(phl.lowWord(), pll.highWord())).mod64()
        },
        handle = {
            runIf(operandWordsAgree() && lowWordsFitIn64Bits(fitsIn64Bits)) {
                recognized += lowWordsOfOperands()
                val product = signedProduct128(
                    operand128(sym(A_IN_LL), sym(B_IN_HL)),
                    operand128(sym(C_IN_LL), sym(D_IN_LH))
                )
                (product bwAnd modz128.maxUnsigned.asTACExpr) shiftRLog 64.asTACExpr
            }
        },
        TACExpr.BinOp.Mod::class.java,
        regressionMessage = true
    )

    /**
        `word0` of the accumulation.  The low word of a product depends only on the low words of its operands, so
        `word0` is reachable only from `p.ll`: neither the other three partial products nor the high words of the
        operands are in its subtree.  Two things follow.  It has to match through the low-word extractions to reach
        the 128-bit operands at all, so it only fires where both operands are a single value the frontend splits, and
        not where their words arrive as separate 64-bit loads.  And it cannot tell on its own whether it is looking at
        the idiom, so [recognize] hands it the multiplies the other two patterns recognized.
     */
    private fun PatternRewriter.resultWord0Handler(
        recognized: Set<Pair<TACSymbol, TACSymbol>>,
    ) = PatternHandler(
        name = "mul128-signed-word0",
        pattern = {
            mul128(
                zeroExtended(lSym(X_SRC).lowWord().lastCmd(X_LOW_WORD)),
                zeroExtended(lSym(Y_SRC).lowWord().lastCmd(Y_LOW_WORD))
            ).lowWord()
        },
        handle = {
            // The rewrite itself holds for any product of two low words, but it is only worth making - it trades a
            // 64x64 product for a 128x128 one - where the rest of the idiom was recognized.  Matching the low words
            // by variable is good enough to decide that, since nothing here rests on the answer being exact.
            runIf((info.lhs(X_LOW_WORD) to info.lhs(Y_LOW_WORD)) in recognized) {
                val product = signedProduct128(
                    operand128(sym(X_SRC).lowWord(), sym(X_SRC).highWord()),
                    operand128(sym(Y_SRC).lowWord(), sym(Y_SRC).highWord())
                )
                (product bwAnd modz128.maxUnsigned.asTACExpr) bwAnd modz64.maxUnsigned.asTACExpr
            }
        },
        TACExpr.BinOp.BWAnd::class.java,
        regressionMessage = true
    )

    /** The low words of the two operands, identifying the multiply the match belongs to. */
    context(PatternHandlerContext)
    private fun lowWordsOfOperands(): Pair<TACSymbol, TACSymbol> =
        info[A_IN_LL]!!.symbol to info[C_IN_LL]!!.symbol

    context(PatternHandlerContext)
    private fun ToTACExpr.lowWord() = this bwAnd modz64.maxUnsigned.asTACExpr

    context(PatternHandlerContext)
    private fun ToTACExpr.highWord() = this shiftRLog 64.asTACExpr

    /** The same operand word has to have reached each partial product it appears in. */
    context(PatternHandlerContext)
    private fun operandWordsAgree() =
        listOf(src(A_IN_LL), src(A_IN_LH)).allSame() &&
            listOf(src(C_IN_LL), src(C_IN_HL)).allSame()

    /**
        The zero-extended operands denote `zext128(word)` only for a word that really is 64 bits wide; nothing in
        their shape says so, so we have to establish it.  The sign-extended operands need no such check - a
        [signExtended] operand denotes the sign extension of its argument's low word whatever the argument is.
     */
    context(PatternHandlerContext)
    private fun lowWordsFitIn64Bits(fitsIn64Bits: (LTACSymbol) -> Boolean) =
        fitsIn64Bits(info[A_IN_LL]!!) && fitsIn64Bits(info[C_IN_LL]!!)

    context(PatternHandlerContext)
    private fun isWholeIdiom(fitsIn64Bits: (LTACSymbol) -> Boolean) =
        operandWordsAgree() &&
            listOf(src(B_IN_HL), src(B_IN_HH)).allSame() &&
            listOf(src(D_IN_LH), src(D_IN_HH)).allSame() &&
            allShared.all { it.resolvesToItsDefinition() } &&
            lowWordsFitIn64Bits(fitsIn64Bits)

    // ---------------------------------------------------------------------------------------------------------

    fun recognize(code: CoreTACProgram): CoreTACProgram {
        // The partial products are matched in the form `sextWidenHandler` leaves the operands in.
        val normalized = PatternRewriter.rewrite(
            code,
            repeat = 1,
            patternList = { listOf(sextWidenHandler()) }
        )

        val intervals = normalized.analysisCache[IntervalAnalysis]
        val fitsIn64Bits = { s: LTACSymbol ->
            intervals.inState(s.ptr)?.interpret(s.symbol)?.x?.getUpperBound()
                ?.let { it <= modz64.maxUnsigned } == true
        }

        // The `word0` rewrite cannot see the rest of the idiom from where it sits, so the two patterns that can
        // tell it which multiplies to touch run first.
        val recognized = ConcurrentHashMap.newKeySet<Pair<TACSymbol, TACSymbol>>()
        val withFlagAndWord1 = PatternRewriter.rewrite(
            normalized,
            repeat = 1,
            patternList = {
                listOf(
                    overflowFlagHandler(fitsIn64Bits, recognized),
                    resultWord1Handler(fitsIn64Bits, recognized),
                )
            }
        )
        return runIf(recognized.isNotEmpty()) {
            PatternRewriter.rewrite(
                withFlagAndWord1,
                repeat = 1,
                patternList = { listOf(resultWord0Handler(recognized)) }
            )
        } ?: withFlagAndWord1
    }
}
