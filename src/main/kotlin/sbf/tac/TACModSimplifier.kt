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

import analysis.*
import analysis.CommandWithRequiredDecls.Companion.withDecls
import analysis.opt.PatternRewriter
import analysis.opt.PatternRewriter.Key.*
import analysis.patterns.PatternHelpers
import com.certora.collect.*
import config.*
import datastructures.stdcollections.*
import instrumentation.transformers.*
import java.math.BigInteger
import log.*
import sbf.*
import sbf.cfg.*
import tac.*
import utils.*
import vc.data.*
import vc.data.SimplePatchingProgram.Companion.patchForEach
import vc.data.tacexprutil.*
import vc.data.tacexprutil.TACExprUtils.contains
import verifier.*
import wasm.analysis.intervals.IntervalBasedExprSimplifier

private typealias PI = analysis.PatternMatcher.Pattern<analysis.patterns.Info>
private typealias PatternHandlerContext = PatternRewriter.PatternHandler.Context

/**
    Used to annotate expressions whose results are guaraneed to fit in 64 bits.  The reason string is for debugging
    purposes only.

    Note: this really is treated as meaning the expression cannot overflow, except in the case of addition or
    subtraction of a constant that is a 2's complement representation of a negative number, which we accomodate by
    flipping the operation to subtraction or addition of the negation of that constant.
 */
val CANNOT_OVERFLOW_64_REASON = tac.MetaKey<String>("sbf.tac.cannot.overflow")

/**
    Attempts to minimize the use of the Mod operator in TAC expressions that implement 64-bit math using Bit256
    operations.
 */
object TACModSimplifier {
    private val optimistic = SolanaConfig.TACOptimisticOverflowOptimization.get()

    /**
        Transforms to be applied prior to loop unrolling.  We do this before unrolling because the unroller may have
        a better chance of guessing loop constants if we can eliminate the mod operations first.
     */
    fun CoreTACProgram.Linear.simplifyModMathPreUnroll() = runIf(SolanaConfig.TACSoundSignedMath.get()) {
        map(CoreToCoreTransformer(ReportTypes.MOD_MATH_NORMALIZE, ::normalize))
        .mapIfAllowed(CoreToCoreTransformer(ReportTypes.MOD_MATH_FIND_MORE_POINTERS, ::findMorePointers))
        .map(CoreToCoreTransformer(ReportTypes.MOD_MATH_REMOVE_NO_OVERFLOW, ::removeNoOverflow))
        .map(CoreToCoreTransformer(ReportTypes.MOD_MATH_SIMPLIFY, ::simplify))
        .mapIfAllowed(CoreToCoreTransformer(ReportTypes.MOD_MATH_INTERVALS_SIMPLIFY, IntervalBasedExprSimplifier::analyze))
    } ?: this

    /**
        Transforms to be applied after loop unrolling.  We do this after unrolling because we may have more visibility
        into the expressions, allowing for further simplifications.
     */
    fun CoreTACProgram.Linear.simplifyModMathPostUnroll() = runIf(SolanaConfig.TACSoundSignedMath.get()) {
        mapIfAllowed(CoreToCoreTransformer(ReportTypes.MOD_MATH_SIMPLIFY, ::simplify))
    } ?: this

    private val bvMode = Config.Smt.UseBV.get()

    private fun TACExpr.cannotOverflow64(): Pair<String, TACExpr>? =
        (this as? TACExpr.AnnotationExp<*>)
            ?.takeIf { it.annot.k == CANNOT_OVERFLOW_64_REASON }
            ?.let { it.annot.v as String to it.o }

    /** Matches a SignExtend expression, with the EVM-style size argument [n] */
    context(PatternHelpers)
    private fun PI.sextEVM(n: PI) = n.signExtend(this)

    /** Produces a SignExtend expression with the EVM-style size argument [n] */
    context(PatternHandlerContext)
    private fun ToTACExpr.sextEVM(n: ToTACExpr) = SignExtend(n.toTACExpr(), this.toTACExpr())

    /** Produces a SignExtend expression with the bit width [n] (which must be a multiple of 8 and less than 256) */
    context(PatternHandlerContext)
    private fun ToTACExpr.sextBits(n: Int): TACExpr {
        require(n in 0..<256 && n % 8 == 0)
        return SignExtend(((n / 8) - 1).asTACExpr, this.toTACExpr())
    }

    context(PatternHandlerContext, ModZm)
    private fun ToTACExpr.isNeg() = this gt maxSigned.asTACExpr
    context(PatternHandlerContext, ModZm)
    private fun ToTACExpr.isNonNeg() = this le maxSigned.asTACExpr
    context(PatternHandlerContext, ModZm)
    private fun sameSign(o1: ToTACExpr, o2: ToTACExpr) = o1.isNonNeg() eq o2.isNonNeg()


    private fun CoreTACProgram.cleanup() = removeUnusedAssignments(this, expensive = false)

    /**
        Unfolds expressions that we will need to analyze, and normalizes expressions over constant negative values
        to use constant positive values instead (so they are less likely to overflow).

        This transform must be applied before the others from [TACModSimplifier].
     */
    private fun normalize(code: CoreTACProgram): CoreTACProgram {
        /**
            To avoid overflow (which is hard for solvers), we detect cases where we are adding (or subtracting) a
            constant that is a 2's complement representation of a negative number, and flip the operation to subtract
            (or add) the negation of that constant.
        */
        fun ModZm.maybeFlipSign(encoded: BigInteger) =
            encoded.takeIf { it.inBounds }?.from2s()?.takeIf { it < BigInteger.ZERO }?.let { -it }

        return PatternRewriter.rewrite(
            code,
            repeat = 1,
            patternList = { listOf(
                /*
                    (x + c).mod(2^n) ~~> (x - abs(c)).mod(2^n)
                    where c is a 2's complement representation of a negative number, of width n
                */
                PatternHandler(
                    name = "add-flip-sign",
                    pattern = {
                        (lSym(A) + c(C1)).rem(c(C2))
                    },
                    handle = {
                        ModZm.fromMod(C2.n)?.run {
                            maybeFlipSign(C1.n)?.let { flipped ->
                                (sym(A) sub flipped.asTACExpr).mod(modulus.asTACExpr)
                            }
                        }
                    },
                    TACExpr.BinOp.Mod::class.java,
                    regressionMessage = true
                ),

                /*
                    (x - c).mod(2^n) ~~> (x + (-c)).mod(2^n)
                    where c is a 2's complement representation of a negative number, of width n
                */
                PatternHandler(
                    name = "sub-flip-sign",
                    pattern = {
                        (lSym(A) - c(C1)).rem(c(C2))
                    },
                    handle = {
                        ModZm.fromMod(C2.n)?.run {
                            maybeFlipSign(C1.n)?.let { flipped ->
                                (sym(A) add flipped.asTACExpr).mod(modulus.asTACExpr)
                            }
                        }
                    },
                    TACExpr.BinOp.Mod::class.java,
                    regressionMessage = true
                ),

                /*
                    (x + c).cannotOverflow64().mod(2^64) ~~> (x - (-c)).cannotOverflow64().mod(2^64)
                    where c is a 2's complement representation of a negative number, of width 64

                    Note that "cannot overflow" here really applies to the *normalized* expression; it's not saying that
                    (x + c) cannot overflow, but rather that (x - (-c)) cannot overflow.  We have to allow this because
                    the compiler actually emits pointer math like `p + -8`, which literally overflows, but semantically
                    does not.
                */
                PatternHandler(
                    name = "add-flip-sign-no-ovf-64",
                    pattern = {
                        (lSym(A) + c(C1)).annotated(CANNOT_OVERFLOW_64_REASON).rem(c(modz64.modulus))
                    },
                    handle = {
                        with(modz64) {
                             maybeFlipSign(C1.n)?.let { flipped ->
                                (sym(A) sub flipped.asTACExpr)
                                    .annotated(CANNOT_OVERFLOW_64_REASON, "Flipped sign")
                                    .mod(modulus.asTACExpr)
                            }
                        }
                    },
                    TACExpr.BinOp.Mod::class.java,
                    regressionMessage = true
                ),

                /*
                    (-1 * x).mod(2^n) ~~> (x.mod(2^n) == 0) ? 0 : (2^n - x.mod(2^n))
                */
                PatternHandler(
                    name = "neg-mod",
                    pattern = {
                        (c(C1) * lSym(A)).rem(c(C2))
                    },
                    handle = {
                        ModZm.fromMod(C2.n)?.run {
                            maybeFlipSign(C1.n)?.takeIf { it == BigInteger.ONE }?.let {
                                ite(
                                    sym(A).mod(modulus.asTACExpr) eq 0.asTACExpr,
                                    0.asTACExpr,
                                    modulus.asTACExpr sub sym(A).mod(modulus.asTACExpr)
                                )
                            }
                        }
                    },
                    TACExpr.BinOp.Mod::class.java,
                    regressionMessage = true
                ),
            ) }
        ).cleanup()
    }

    /**
        When generating TAC, we used information from the pointer analysis to annotate pointer math with
        [CANNOT_OVERFLOW_64_REASON], so that we can assert/assume those operations won't overflow.  Here we do an extra
        search for pointer math we might have missed, using a dataflow analysis to back-propagate pointer-ness inferred
        from uses as memory access locations.
     */
    private fun findMorePointers(code: CoreTACProgram): CoreTACProgram {
        val graph = code.analysisCache.graph
        val mbc = MustBeConstantAnalysis(graph)
        val def = code.analysisCache.def

        // Back-propagate the "pointer-ness" of all variables through the program.  If a variable is used as the
        // location of a memory access, it is a "pointer".  If a pointer is computed by adding a constant to another
        // variable, then the other variable is also a pointer.  Any other computation of a pointer is treated as
        // opaque.
        val ptrAnalysis = object : TACCommandDataflowAnalysis<TreapSet<TACSymbol.Var>>(
            graph = graph,
            lattice = JoinLattice.ofJoin { a, b -> a union b },
            bottom = treapSetOf<TACSymbol.Var>(),
            dir = Direction.BACKWARD
        ) {
            override fun transformCmd(inState: TreapSet<TACSymbol.Var>, cmd: LTACCmd): TreapSet<TACSymbol.Var> {
                var outState = inState
                cmd.cmd.getLhs()?.let { outState -= it }
                outState += treapSetOfNotNull(
                    when (cmd.cmd) {
                        is TACCmd.Simple.DirectMemoryAccessCmd -> (cmd.cmd.loc as? TACSymbol.Var)
                        is TACCmd.Simple.AssigningCmd.AssignExpCmd -> runIf(cmd.cmd.lhs in inState) {
                            propagateTo(cmd.ptr, cmd.cmd.rhs) as? TACSymbol.Var
                        }
                        else -> null
                    }
                )
                return outState
            }

            // Given that [e] computes a location, find a variable in [e] which should also be considered a location.
            private fun propagateTo(where: CmdPointer, e: TACExpr): TACSymbol? = when (e) {
                is TACExpr.Sym -> mbc.mustBeConstantAt(where, e.s)?.let { TACSymbol.Const(it) } ?: e.s
                is TACExpr.AnnotationExp<*> -> propagateTo(where, e.o)
                is TACExpr.Vec.Add -> e.ls.map {
                    propagateTo(where, it) ?: return null
                }.singleOrNull {
                    it is TACSymbol.Var
                }
                is TACExpr.BinOp.Mod -> runIf(
                    e.o2 is TACExpr.Sym && mbc.mustBeConstantAt(where, e.o2.s) == modz64.modulus
                ) {
                    propagateTo(where, e.o1)
                }
                else -> null
            }

            init {
                runAnalysis()
            }
        }

        // Find assignments of the form: `lhs := loc.mod(2^64)`, where `lhs` and `loc` are pointers according to the
        // above analysis, and annotate `loc` to indicate that it cannot have overflowed.
        return code.parallelLtacStream().mapNotNull { (ptr, cmd) ->
            if (cmd !is TACCmd.Simple.AssigningCmd.AssignExpCmd) { return@mapNotNull null }
            if (cmd.rhs !is TACExpr.BinOp.Mod) { return@mapNotNull null }
            if (cmd.rhs.o2 !is TACExpr.Sym) { return@mapNotNull null }
            if (mbc.mustBeConstantAt(ptr, cmd.rhs.o2.s) != modz64.modulus) { return@mapNotNull null }
            val loc = cmd.rhs.o1 as? TACExpr.Sym.Var ?: return@mapNotNull null
            if (loc.s !in ptrAnalysis.cmdOut[ptr].orEmpty()) { return@mapNotNull null }
            if (cmd.lhs in ptrAnalysis.cmdIn[ptr].orEmpty()) { return@mapNotNull null }

            // Check if this pointer value is already annotated
            val locDef = def.defSitesOf(loc.s, ptr).singleOrNull() ?: return@mapNotNull null
            val locDefCmd = graph.toCommand(locDef) as? TACCmd.Simple.AssigningCmd.AssignExpCmd ?: return@mapNotNull null
            if (locDefCmd.rhs is TACExpr.AnnotationExp<*>) { return@mapNotNull null }

            // `loc.mod(2^64) ~~> loc.cannotOverflow64().mod(2^64)
            ptr to TXF {
                cmd.rhs.o1
                    .annotated(CANNOT_OVERFLOW_64_REASON, "inferred pointer")
                    .mod(cmd.rhs.o2)
            }.let { ExprUnfolder.unfoldTo(it, cmd.lhs, cmd.meta) }
        }.patchForEach(code) { (ptr, cmds) -> replaceCommand(ptr, cmds) }
    }

    /**
        For any expression annotated with [CANNOT_OVERFLOW_64_REASON], we add an assert/assume that the expression's
        result is indeed within bounds.  We also add asserts/assumes that any memory access locations are in the range
        of plausible memory addresses. Finally, we remove the mod operations on any expression annotated with
        [CANNOT_OVERFLOW_64_REASON], since they are redundant.
     */
    private fun removeNoOverflow(code: CoreTACProgram): CoreTACProgram {
        @Suppress("NAME_SHADOWING")
        var code = code

        // Add asserts/assumes for any annotated expressions
        code = code.parallelLtacStream().mapNotNull { (ptr, cmd) ->
            if (cmd !is TACCmd.Simple.AssigningCmd.AssignExpCmd) { return@mapNotNull null }
            val (reason, exp) = cmd.rhs.cannotOverflow64() ?: return@mapNotNull null

            val cond = TXF { exp le modz64.maxUnsigned }
            val condVar = TACKeyword.TMP(Tag.Bool)
            val msg = "Cannot overflow: $reason"
            val meta = MetaMap(TACMeta.REMOVABLE_IF_TRIVIAL)

            ptr to listOf(
                TACCmd.Simple.AssigningCmd.AssignExpCmd(condVar, cond),
                if (optimistic) {
                    TACCmd.Simple.AssumeCmd(condVar, msg, meta)
                } else {
                    TACCmd.Simple.AssertCmd(condVar, msg, meta)
                }
            ).withDecls(condVar)
        }.patchForEach(code) { (ptr, cmds) -> addBefore(ptr, cmds) }

        // For each memory access, assume the address is within the range of plausible addresses.
        code = code.parallelLtacStream().mapNotNull { (ptr, cmd) ->
            if (cmd !is TACCmd.Simple.DirectMemoryAccessCmd) { return@mapNotNull null }
            val loc = cmd.loc as? TACSymbol.Var ?: return@mapNotNull null

            ptr to ExprUnfolder.unfoldPlusOneCmd(
                "boundedLoc",
                TXF { safeMathNarrowAssuming(loc.asSym(), Tag.Bit256, SBF_IMPLAUSIBLE_MEM_ADDRESS.toBigInteger()) },
                cmd.meta,
                { cmd.withLoc(it.s) }
            )
        }.patchForEach(code) { (ptr, cmds) -> replaceCommand(ptr, cmds) }

        // Remove the mod operations on any annotated expressions
        code = PatternRewriter.rewrite(
            code,
            repeat = 1,
            patternList = { listOf(
                /*
                    x.cannotOverflow64().mod(2^64) ~~> x
                */
                PatternHandler(
                    name = "no-ovf",
                    pattern = {
                        lSym(A).annotated(CANNOT_OVERFLOW_64_REASON).rem(c(modz64.modulus))
                    },
                    handle = {
                        sym(A)
                    },
                    TACExpr.BinOp.Mod::class.java
                ),
            )}
        )

        return code.cleanup()
    }

    /**
        Simplifies expressions involving mod, using algebraic identities.  We apply these identities repeatedly (up to a
        configurable number of steps) to simplify complex expressions.

        This transform assumes the input code has already been normalized, and any [CANNOT_OVERFLOW_64_REASON]
        annotations have already been applied/eliminated.
     */
    private fun simplify(code: CoreTACProgram): CoreTACProgram {
        @Suppress("NAME_SHADOWING")
        var code = code
        code = PatternRewriter.rewrite(
            code,
            repeat = SolanaConfig.TACModSimplificationSteps.get(),
            patternList = { listOf(
                /*
                    (x + y).mod() < x ~~> (x.mod(m) + y.mod(m)) >= m
                */
                PatternHandler(
                    name = "add-ovf-1",
                    pattern = {
                        (lSym(A) + lSym(B)).rem(c(C1)) symmLt lSym(C)
                    },
                    handle = {
                        runIf(src(A) == src(C)) {
                            val x = sym(A)
                            val y = sym(B)
                            val m = C1.n.asTACExpr
                            (x.mod(m) intAdd y.mod(m)) ge m
                        }
                    },
                    TACExpr.BinRel.Gt::class.java, TACExpr.BinRel.Lt::class.java,
                    regressionMessage = true
                ),

                /*
                    (x + y).mod(m) >= x ~~> (x.mod(m) + y.mod(m)) < m
                */
                PatternHandler(
                    name = "add-ovf-2",
                    pattern = {
                        (lSym(A) + lSym(B)).rem(c(C1)) symmGe lSym(C)
                    },
                    handle = {
                        runIf(src(A) == src(C)) {
                            val x = sym(A)
                            val y = sym(B)
                            val m = C1.n.asTACExpr
                            (x.mod(m) intAdd y.mod(m)) lt m
                        }
                    },
                    TACExpr.BinRel.Ge::class.java, TACExpr.BinRel.Le::class.java,
                    regressionMessage = true
                ),

                /*
                    (x - y).mod(m) > x ~~> x.mod(m) < y.mod(m)
                */
                PatternHandler(
                    name = "sub-ovf-1",
                    pattern = {
                        (lSym(A) - lSym(B)).rem(c(C1)) symmGt lSym(C)
                    },
                    handle = {
                        runIf(src(A) == src(C)) {
                            val x = sym(A)
                            val y = sym(B)
                            val m = C1.n.asTACExpr
                            x.mod(m) lt y.mod(m)
                        }
                    },
                    TACExpr.BinRel.Gt::class.java, TACExpr.BinRel.Lt::class.java,
                    regressionMessage = true
                ),

                /*
                    (x - y).mod(m) <= x ~~> x.mod(m) >= y.mod(m)
                */
                PatternHandler(
                    name = "sub-ovf-2",
                    pattern = {
                        (lSym(A) - lSym(B)).rem(c(C1)) symmLe lSym(C)
                    },
                    handle = {
                        runIf(src(A) == src(C)) {
                            val x = sym(A)
                            val y = sym(B)
                            val m = C1.n.asTACExpr
                            x.mod(m) ge y.mod(m)
                        }
                    },
                    TACExpr.BinRel.Ge::class.java, TACExpr.BinRel.Le::class.java,
                    regressionMessage = true
                ),

                /*
                    c.sextEVM(n) ~~> (compute the sign-extension of c)
                        (This allows matching other patterns on the next pass)
                 */
                PatternHandler(
                    name = "const-sext",
                    pattern = {
                        c(C1).sextEVM(c(C2))
                    },
                    handle = {
                        runIf(C1.n in 0.toBigInteger()..31.toBigInteger()) {
                            ModZm.evmSignExtend(C2.n, C1.n).asTACExpr
                        }
                    },
                    TACExpr.BinOp.SignExtend::class.java,
                    regressionMessage = true
                ),

                /*
                    (x & y).sextEVM(n) < 0 ~~> (x.sextEVM(n) < 0) && (y.sextEVM(n) < 0)
                 */
                PatternHandler(
                    name = "and-sext-lt",
                    pattern = {
                        (lSym(A) bwAnd lSym(B)).sextEVM(c(C1)) symmSLt c(0)
                    },
                    handle = {
                        val x = sym(A)
                        val y = sym(B)
                        val n = C1.n.asTACExpr
                        (x.sextEVM(n) sLt 0.asTACExpr) and (y.sextEVM(n) sLt 0.asTACExpr)
                    },
                    TACExpr.BinRel.Slt::class.java, TACExpr.BinRel.Sgt::class.java,
                    regressionMessage = true
                ),

                /*
                    (x & y).sextEVM(n) >= 0 ~~> !(x.sextEVM(n) < 0) || !(y.sextEVM(n) < 0)
                 */
                PatternHandler(
                    name = "and-sext-ge",
                    pattern = {
                        (lSym(A) bwAnd lSym(B)).sextEVM(c(C1)) symmSGe c(0)
                    },
                    handle = {
                        val x = sym(A)
                        val y = sym(B)
                        val n = C1.n.asTACExpr
                        not(x.sextEVM(n) sLt 0.asTACExpr) or not(y.sextEVM(n) sLt 0.asTACExpr)
                    },
                    TACExpr.BinRel.Sge::class.java, TACExpr.BinRel.Sle::class.java,
                    regressionMessage = true
                ),

                /*
                    (x xor y).sextEVM(n) < 0 ~~> (x.sextEVM(n) < 0) != (y.sextEVM(n) < 0)
                 */
                PatternHandler(
                    name = "xor-sext-lt",
                    pattern = {
                        (lSym(A) xor lSym(B)).sextEVM(c(C1)) symmSLt c(0)
                    },
                    handle = {
                        val x = sym(A)
                        val y = sym(B)
                        val n = C1.n.asTACExpr
                        (x.sextEVM(n) sLt 0.asTACExpr) neq (y.sextEVM(n) sLt 0.asTACExpr)
                    },
                    TACExpr.BinRel.Slt::class.java, TACExpr.BinRel.Sgt::class.java,
                    regressionMessage = true
                ),

                /*
                    (x xor y).sextEVM(n) >= 0 ~~> (x.sextEVM(n) < 0) == (y.sextEVM(n) < 0)
                 */
                PatternHandler(
                    name = "xor-sext-ge",
                    pattern = {
                        (lSym(A) xor lSym(B)).sextEVM(c(C1)) symmSGe c(0)
                    },
                    handle = {
                        val x = sym(A)
                        val y = sym(B)
                        val n = C1.n.asTACExpr
                        (x.sextEVM(n) sLt 0.asTACExpr) eq (y.sextEVM(n) sLt 0.asTACExpr)
                    },
                    TACExpr.BinRel.Sge::class.java, TACExpr.BinRel.Sle::class.java,
                    regressionMessage = true
                ),

                /*
                    x.sextBits(n) <=(unsigned) maxSigned ~~> !(x.sextBits(n) <(signed) 0)
                 */
                PatternHandler(
                    name = "sext-unsigned-le-max",
                    pattern = {
                        lSym(A).sextEVM(c(C1)) symmLe c(C2)
                    },
                    handle = {
                        ModZm.fromEvmSignExtend(C1.n)?.run {
                            runIf(C2.n == maxSigned) {
                                not(sym(A).sextEVM(C1.n.asTACExpr) sLt 0.asTACExpr)
                            }
                        }
                    },
                    TACExpr.BinRel.Le::class.java, TACExpr.BinRel.Gt::class.java,
                    regressionMessage = true
                ),

                /*
                    (x.sextBits(n) shra (n-1)).mod(2^n) xor minSigned2s(n)
                        ~~> ite(x <= maxSigned(n), minSigned2s(n), maxSigned(n))
                 */
                PatternHandler(
                    name = "sext-shr-xor-1",
                    pattern = {
                        ((lSym(A).sextEVM(c(C1)) shra c(C2)).rem(c(C3)) xor c(C4))
                    },
                    handle = {
                        ModZm.fromEvmSignExtend(C1.n)?.run {
                            runIf(
                                C2.n == (bitwidth - 1).toBigInteger() &&
                                C3.n == modulus &&
                                C4.n == minSigned2s
                            ) {
                                ite(
                                    sym(A) le maxSigned.asTACExpr,
                                    minSigned2s.asTACExpr,
                                    maxSigned.asTACExpr
                                )
                            }
                        }
                    },
                    TACExpr.BinOp.BWXOr::class.java,
                    regressionMessage = true
                ),

                /*
                    (x.sextBits(n) shra (n-1)).mod(2^n) xor maxSigned(n)
                        ~~> ite(x <= maxSigned(n), maxSigned(n), minSigned2s(n))
                 */
                PatternHandler(
                    name = "sext-shr-xor-2",
                    pattern = {
                        ((lSym(A).sextEVM(c(C1)) shra c(C2)).rem(c(C3)) xor c(C4))
                    },
                    handle = {
                        ModZm.fromEvmSignExtend(C1.n)?.run {
                            runIf(
                                C2.n == (bitwidth - 1).toBigInteger() &&
                                C3.n == modulus &&
                                C4.n == maxSigned
                            ) {
                                ite(
                                    sym(A).sextEVM(C1.n.asTACExpr) le maxSigned.asTACExpr,
                                    maxSigned.asTACExpr,
                                    minSigned2s.asTACExpr
                                )
                            }
                        }
                    },
                    TACExpr.BinOp.BWXOr::class.java,
                    regressionMessage = true
                ),

                /*
                    Bit-twiddling magic implementation of `unsigned_abs`:

                    ((x xor (x.sextBits(n) shra (n-1)).mod(2^n)) - (x.sextBits(n) shra (n-1)).mod(2^n)).mod(2^n)
                      ~~> ite(x.mod(2^n) <= maxSigned(n), x, 2^64 - x).mod(2^n)
                 */
                PatternHandler(
                    name = "bit-twiddling-unsigned-abs",
                    pattern = {
                        (
                            (lSym(A) xor (lSym(B).sextEVM(c(C1)) shra c(C2)).rem(c(C3))) -
                                (lSym(C).sextEVM(c(C4)) shra c(C5)).rem(c(C6))
                        ).rem(c(C7))
                    },
                    handle = {
                        ModZm.fromEvmSignExtend(C1.n)?.run {
                            runIf(
                                listOf(src(A), src(B), src(C)).allSame() &&
                                C1.n == C4.n &&
                                C2.n == (bitwidth - 1).toBigInteger() &&
                                C3.n == modulus &&
                                C5.n == (bitwidth - 1).toBigInteger() &&
                                C6.n == modulus &&
                                C7.n == modulus
                            ) {
                                ite(
                                    sym(A).mod(modulus.asTACExpr) le maxSigned.asTACExpr,
                                    sym(A),
                                    (modulus.asTACExpr sub sym(A))
                                ).mod(modulus.asTACExpr)
                            }
                        }
                    },
                    TACExpr.BinOp.Mod::class.java,
                    regressionMessage = true
                ),

                /*
                    ((x << s).mod(2^n).sextBits(n) >> s).mod(2^n) ~~> x.sextBits(n-s).mod(2^n)

                        where s < n and s is a multiple of 8
                */
                PatternHandler(
                    name = "shl-mod-signext",
                    pattern = {
                        ((lSym(A) shl c(C1)).rem(c(C2)).sextEVM(c(C3)) shra c(C4)).rem(c(C5))
                    },
                    handle = {
                        ModZm.fromEvmSignExtend(C3.n)?.run {
                            runIf(
                                C1.n == C4.n &&
                                C2.n == modulus &&
                                C5.n == modulus &&
                                C1.n < bitwidth.toBigInteger() &&
                                C1.n.mod(8.toBigInteger()) == BigInteger.ZERO
                            ) {
                                sym(A).sextBits(bitwidth - C1.n.toInt()).mod(modulus.asTACExpr)
                            }
                        }
                    },
                    TACExpr.BinOp.Mod::class.java,
                    regressionMessage = true
                ),

                /*
                    x.sextBits(n).mod(2^m) <= maxSigned(m) ~~> x.mod(2^n) <= maxSigned(n)
                       where m >= n
                */
                PatternHandler(
                    name = "signext-mod-sign",
                    pattern = {
                        lSym(A).sextEVM(c(C1)).rem(c(C2)) symmLe c(C3)
                    },
                    handle = {
                        ModZm.fromEvmSignExtend(C1.n)?.let { modzN ->
                            ModZm.fromMod(C2.n)?.let { modzM ->
                                runIf(
                                    modzM.bitwidth >= modzN.bitwidth &&
                                    C3.n == modzM.maxSigned
                                ) {
                                    sym(A).mod(modzN.modulus.asTACExpr) le modzN.maxSigned.asTACExpr
                                }
                            }
                        }
                    },
                    TACExpr.BinRel.Ge::class.java, TACExpr.BinRel.Le::class.java,
                    regressionMessage = true
                ),

                /*
                    x.sextBits(n).mod(2^m) > 0 ~~> x.mod(2^n) > 0
                        where m >= n
                */
                PatternHandler(
                    name = "signext-mod-nonzero",
                    pattern = {
                        lSym(A).sextEVM(c(C1)).rem(c(C2)) symmGt c(0)
                    },
                    handle = {
                        ModZm.fromEvmSignExtend(C1.n)?.let { modzN ->
                            ModZm.fromMod(C2.n)?.let { modzM ->
                                runIf(
                                    modzM.bitwidth >= modzN.bitwidth
                                ) {
                                    sym(A).mod(modzN.modulus.asTACExpr) gt 0.asTACExpr
                                }
                            }
                        }
                    },
                    TACExpr.BinRel.Gt::class.java, TACExpr.BinRel.Lt::class.java,
                    regressionMessage = true
                ),

                /*
                    Signed > ~~> unsigned > :
                    x.sextBits(n) s> y.sextBits(n) ~~> (x.isNonNeg(n) && y.isNeg(n)) || (sameSign(n, x, y) && x > y)
                 */
                PatternHandler(
                    name = "gt-signed-to-unsigned",
                    pattern = {
                        lSym(A).sextEVM(c(C1)) symmGt lSym(B).sextEVM(c(C2))
                    },
                    handle = {
                        // This transformation won't help in BV mode!
                        runIf(!bvMode && C1.n == C2.n) {
                            ModZm.fromEvmSignExtend(C1.n)?.run {
                                (sym(A).isNonNeg() and sym(B).isNeg()) or
                                    (sameSign(sym(A), sym(B)) and (sym(A) gt sym(B)))
                            }
                        }
                    },
                    TACExpr.BinRel.Gt::class.java, TACExpr.BinRel.Lt::class.java,
                    regressionMessage = true
                ),

                /*
                    Signed >= ~~> unsigned >= :
                    x.sextBits(n) s>= y.sextBits(n) ~~> (x.isNonNeg(n) && y.isNeg(n)) || (sameSign(n, x, y) && x >= y)
                 */
                PatternHandler(
                    name = "ge-signed-to-unsigned",
                    pattern = {
                        lSym(A).sextEVM(c(C1)) symmGe lSym(B).sextEVM(c(C2))
                    },
                    handle = {
                        // This transformation won't help in BV mode!
                        runIf(!bvMode && C1.n == C2.n) {
                            ModZm.fromEvmSignExtend(C1.n)?.run {
                                (sym(A).isNonNeg() and sym(B).isNeg()) or
                                    (sameSign(sym(A), sym(B)) and (sym(A) ge sym(B)))
                            }
                        }
                    },
                    TACExpr.BinRel.Ge::class.java, TACExpr.BinRel.Le::class.java,
                    regressionMessage = true
                ),

                /*
                    x.mod(2^n) & maxUnsigned(n) ~~> x & maxUnsigned(n)
                */
                PatternHandler(
                    name = "mod-and",
                    pattern = {
                        lSym(A).rem(c(C1)) bwAnd c(C2)
                    },
                    handle = {
                        ModZm.fromMod(C1.n)?.run {
                            runIf(C2.n == maxUnsigned) {
                                sym(A) bwAnd maxUnsigned.asTACExpr
                            }
                        }
                    },
                    TACExpr.BinOp.BWAnd::class.java,
                    regressionMessage = true
                ),

                /*
                    x.mod(m).mod(m) ~~> x.mod(m)
                */
                PatternHandler(
                    name = "mod-mod",
                    pattern = {
                        lSym(A).rem(c(C1)).rem(c(C2))
                    },
                    handle = {
                        runIf(C1.n == C2.n) {
                            val x = sym(A)
                            val m = C1.n.asTACExpr
                            x.mod(m)
                        }
                    },
                    TACExpr.BinOp.Mod::class.java,
                    regressionMessage = true
                ),

                /*
                    (x.mod(m) + y).mod(m) ~~> (x + y).mod(m)
                    (x + y.mod(m)).mod(m) ~~> (x + y).mod(m)
                */
                PatternHandler(
                    name = "mod-add-simplify",
                    pattern = {
                        (lSym(A).rem(c(C1)) + lSym(B)).rem(c(C2))
                    },
                    handle = {
                        runIf(C1.n == C2.n) {
                            val x = sym(A)
                            val y = sym(B)
                            val m = C1.n.asTACExpr
                            (x add y).mod(m)
                        }
                    },
                    TACExpr.BinOp.Mod::class.java,
                    regressionMessage = true
                ),

                /*
                    (x.mod(m) - y).mod(m) ~~> (x - y).mod(m)
                */
                PatternHandler(
                    name = "mod-sub-simplify",
                    pattern = {
                        ((lSym(A).rem(c(C1)) - lSym(B)).rem(c(C2)))
                    },
                    handle = {
                        runIf(C1.n == C2.n) {
                            val x = sym(A)
                            val y = sym(B)
                            val m = C1.n.asTACExpr
                            (x sub y).mod(m)
                        }
                    },
                    TACExpr.BinOp.Mod::class.java,
                    regressionMessage = true
                ),

                /*
                    (x - y.mod(m)).mod(m) ~~> (x - y).mod(m)
                */
                PatternHandler(
                    name = "mod-sub-simplify",
                    pattern = {
                        ((lSym(A) - lSym(B).rem(c(C1))).rem(c(C2)))
                    },
                    handle = {
                        runIf(C1.n == C2.n) {
                            val x = sym(A)
                            val y = sym(B)
                            val m = C1.n.asTACExpr
                            (x sub y).mod(m)
                        }
                    },
                    TACExpr.BinOp.Mod::class.java,
                    regressionMessage = true
                ),

                /*
                    (x.mod(m) * y).mod(m) ~~> (x * y).mod(m)
                    (x * y.mod(m)).mod(m) ~~> (x * y).mod(m)
                */
                PatternHandler(
                    name = "mod-mul-simplify",
                    pattern = {
                        (lSym(A).rem(c(C1)) * lSym(B)).rem(c(C2))
                    },
                    handle = {
                        runIf(C1.n == C2.n) {
                            val x = sym(A)
                            val y = sym(B)
                            val m = C1.n.asTACExpr
                            (x mul y).mod(m)
                        }
                    },
                    TACExpr.BinOp.Mod::class.java,
                    regressionMessage = true
                ),

                /*
                    (x.mod(m) - c).mod(m) == 0 ~~> x.mod(m) == c.mod(m)
                */
                PatternHandler(
                    name = "mod-sub-eq-simplify",
                    pattern = {
                        (lSym(A).rem(c(C1)) - c(C2)).rem(c(C3)) eq c(0)
                    },
                    handle = {
                        runIf(C1.n == C3.n) {
                            val x = sym(A)
                            val m = C1.n.asTACExpr
                            val c = C2.n.asTACExpr
                            x.mod(m) eq c.mod(m)
                        }
                    },
                    TACExpr.BinRel.Eq::class.java,
                    regressionMessage = true
                ),

                /*
                    ((x.sextBits(n) >>a (n-1)).mod(2^n) << n) + x ~~> x.sextBits(n).mod(2^(n*2))

                        (For n <= 64)
                 */
                PatternHandler(
                    name = "sext-widen",
                    pattern = {
                        ((lSym(A).sextEVM(c(C1)) shra c(C2)).rem(c(C3)) shl c(C4)) + lSym(B)
                    },
                    handle = {
                        ModZm.fromEvmSignExtend(C1.n)?.run {
                            runIf(
                                bitwidth <= 64 &&
                                src(A) == src(B) &&
                                C2.n == (bitwidth - 1).toBigInteger() &&
                                C3.n == modulus &&
                                C4.n == bitwidth.toBigInteger()
                            ) {
                                sym(A).sextEVM(C1.n.asTACExpr).mod(BigInteger.TWO.pow(bitwidth * 2).asTACExpr)
                            }
                        }
                    },
                    TACExpr.Vec.Add.Binary::class.java,
                    regressionMessage = true
                ),
            ) }
        )

        // We only want to apply these patterns once, since they would otherwise expand recursively:
        code = PatternRewriter.rewrite(
            code,
            repeat = 1,
            patternList = { listOf(
                /*
                    x >> 63 ~~> (x >= 2^64) ? (x >> 63) : (x >= 2^63) ? 1 : 0

                    (This looks worse, but the interval rewriter will be able to eliminate the outer `ite` in most
                    cases)
                */
                PatternHandler(
                    name = "shr63-to-ite",
                    pattern = {
                        lSym(A) shr c(63)
                    },
                    handle = {
                        ite(
                            sym(A) ge modz64.modulus.asTACExpr,
                            sym(A) shiftRLog 63.asTACExpr,
                            ite(
                                sym(A) ge (modz64.modulus / 2).asTACExpr,
                                1.asTACExpr,
                                0.asTACExpr
                            )
                        )
                    },
                    TACExpr.BinOp.ShiftRightLogical::class.java,
                    regressionMessage = true
                ),
            ) }
        )
        return code.cleanup()
    }
}
