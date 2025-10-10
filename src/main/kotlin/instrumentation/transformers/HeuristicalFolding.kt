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

package instrumentation.transformers

import analysis.NonTrivialDefAnalysis
import analysis.dataflow.SingleUseAnalysis
import analysis.maybeExpr
import analysis.maybeNarrow
import analysis.narrow
import config.Config
import datastructures.stdcollections.*
import evm.EVM_BITWIDTH256
import evm.EVM_MOD_GROUP256
import instrumentation.transformers.PeepHoleOptimizer.peepholeOptimize
import log.*
import tac.MetaMap
import tac.Tag
import utils.*
import vc.data.*
import vc.data.SimplePatchingProgram.Companion.patchForEach
import vc.data.TACKeyword.*
import vc.data.TACMeta.CVL_EXP
import vc.data.TACMeta.CVL_VAR
import vc.data.TACMeta.DIRECT_STORAGE_ACCESS_TYPE
import vc.data.TACMeta.IS_RETURNDATA
import vc.data.TACMeta.STORAGE_KEY
import vc.data.TACSymbol.Var.Companion.hasKeyword
import vc.data.tacexprutil.*
import java.math.BigInteger
import java.util.*
import kotlin.streams.asStream

private val logger = Logger(LoggerTypes.HEURISTICAL_FOLDING)
object HeuristicalFolding {
    private fun TACExpr.isEligibleForFolding(allowAxiomatized: Boolean) =
        subs.all { e ->
            when (e) {
                is TACExpr.Vec.Add,
                is TACExpr.BinOp.Sub,
                is TACExpr.Vec.Mul,
                is TACExpr.BinOp.Div,
                is TACExpr.Vec.IntMul,
                is TACExpr.BinOp.BWAnd ->
                    allowAxiomatized

                is TACExpr.Select,
                is TACExpr.Store,
                is TACExpr.MultiDimStore,
                is TACExpr.LongStore,
                is TACExpr.MapDefinition,
                is TACExpr.SimpleHash ->
                    false

                else -> true
            }
        }

    // some backtranslation will be needed here!
    /**
     * Rewrite [p] using some optimizations run iteratively until a fix point is reached or a bound is reached.
     * (We really don't want to diverge!)
     *
     * @param ignoreCallTrace Whether to apply optimizations that may remove meta-keys required for CallTrace construction.
     */
    fun rewrite(p: CoreTACProgram): CoreTACProgram {
        if (!Config.EnableHeuristicalFolding.get()) {
            return p
        }

        var iter = p
        var changed = true
        var numIters = 0
        val start = getCurrentTime()
        while (changed && numIters < 100 && start.elapsedNow().inWholeSeconds < Config.MaxHeuristicalFoldingTime.get()) {
            ++numIters
            rewriteInternal(iter).let { (a, b) ->
                iter = a
                changed = b
            }

            iter = peepholeOptimize(iter)
            iter = iteOptimize(iter)
            iter = arithFolder(iter)
            iter = removeUnusedAssignments(iter, expensive = false)
        }
        return iter
    }

    /**
     * Optimizes a program [p].
     * If in [p] there is an assignment of nested ite-s: `x := ite ( .., ite(...), ite(...))`
     * where all the leaf-expressions (i.e. non-ite) are exactly the same
     * expression `e`, to be just `x := e`
     */
    private fun iteOptimize(p: CoreTACProgram): CoreTACProgram {
        fun leaves(e: TACExpr): TACExpr? =
            when (e) {
                is TACExpr.TernaryExp.Ite -> leaves(e.t)?.takeIf { it == leaves(e.e) }
                else -> e
            }

        return p.analysisCache.graph.commands.mapNotNull { it.maybeExpr<TACExpr.TernaryExp.Ite>() }
            .mapNotNull { iteLcmd ->
                iteLcmd `to?` leaves(iteLcmd.exp)
            }.asStream().patchForEach(p) { (lcmd, exp) ->
                this.update(lcmd.ptr, lcmd.cmd.copy(rhs = exp))
            }
    }

    /**
     * Simple substitutions for arithmetical expressions.
     * TODO: Replace with better mechanism and put much earlier in the pipeline
     */
    private fun arithFolder(p: CoreTACProgram): CoreTACProgram {
        val g = p.analysisCache.graph
        val def = p.analysisCache.def
        val patching = p.toPatchingProgram()
        p.analysisCache.graph.commands.forEach { lcmd ->
            val cmd = lcmd.maybeNarrow<TACCmd.Simple.AssigningCmd.AssignExpCmd>()?.cmd
            val rhs = cmd?.rhs
            // this can be done with patterns but ugh DSLs TODO
            // TODO also should have unit tests for this
            if (rhs is TACExpr.BinOp.Sub) {
                // given A-B, if A=B+C can replace with C
                // also if we have A=C+B then can replace with C
                if (rhs.operandsAreSyms()) {
                    val A = rhs.o1.getAs<TACExpr.Sym.Var>()
                    val B = rhs.o2.getAs<TACExpr.Sym.Var>()
                    if (A != null && B != null) {
                        def.defSitesOf(A.s, lcmd.ptr).singleOrNull()?.let { defOfA ->
                            val maybeBplusC = g.elab(defOfA)
                                .maybeNarrow<TACCmd.Simple.AssigningCmd.AssignExpCmd>()?.cmd?.rhs as? TACExpr.Vec.Add
                            if (maybeBplusC != null && maybeBplusC.ls.size == 2 && maybeBplusC.o1AsNullableTACSymbol() == B.s) {
                                patching.update(lcmd.ptr) { cmd.copy(rhs = maybeBplusC.o2) }
                            } else if (maybeBplusC != null && maybeBplusC.ls.size == 2 && maybeBplusC.o2AsNullableTACSymbol() == B.s) {
                                patching.update(lcmd.ptr) { cmd.copy(rhs = maybeBplusC.o1) }
                            }
                        }
                    }
                }
            } else if (rhs is TACExpr.Vec.Add) {
                // given A+c, if A=B+c' then can replace with B+(c+c') (c,c' are consts)
                if (rhs.ls.size == 2 && rhs.operandsAreSyms() && rhs.o2 is TACExpr.Sym.Const) {
                    val A = rhs.o1.getAs<TACExpr.Sym.Var>()
                    if (A != null) {
                        def.defSitesOf(A.s, lcmd.ptr).singleOrNull()?.let { defOfA ->
                            val maybeBplusConst = g.elab(defOfA)
                                .maybeNarrow<TACCmd.Simple.AssigningCmd.AssignExpCmd>()?.cmd?.rhs as? TACExpr.Vec.Add
                            if (maybeBplusConst != null && maybeBplusConst.ls.size == 2 && maybeBplusConst.o2 is TACExpr.Sym.Const) {
                                patching.update(lcmd.ptr) {
                                    cmd.copy(
                                        rhs = TACExpr.Vec.Add(
                                            maybeBplusConst.o1,
                                            ((maybeBplusConst.o2 as TACExpr.Sym.Const).s.value + (rhs.o2 as TACExpr.Sym.Const).s.value).mod(
                                                EVM_MOD_GROUP256
                                            ).asTACSymbol()
                                                .asSym()
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        return patching.toCode(p)

    }


    /*
    when we have:
    x := exp
    splitStorage := x

    the TACDSA simplifier throws away splitStorage with its nice meta information.
    So we rewrite the above to:
    x := exp
    splitStorage := exp
    (exp has no side effects.)
 */
    fun foldSplitStores(p: CoreTACProgram): CoreTACProgram {
        val patching = p.toPatchingProgram()
        val g = p.analysisCache.graph
        val nonTrivialdef = NonTrivialDefAnalysis(g)
        val def = p.analysisCache.def
        patching.forEachOriginal { cmdPointer, simple ->
            if (simple is TACCmd.Simple.AssigningCmd.AssignExpCmd && simple.lhs.meta.containsKey(STORAGE_KEY) &&
                simple.lhs.meta.containsKey(TACMeta.SCALARIZATION_SORT) && simple.rhs is TACExpr.Sym.Var
            ) {
                /* [simple] is an assignment of a variable to a split storage scalar */
                val nonTrivialDefOfRhs = nonTrivialdef.getDefAsExpr<TACExpr>(simple.rhs.s, cmdPointer)
                if (nonTrivialDefOfRhs != null) {
                    val varsInDef = TACExprFreeVarsCollector.getFreeVars(nonTrivialDefOfRhs.exp)
                    if (varsInDef.all {
                            def.defSitesOf(it, nonTrivialDefOfRhs.ptr) == def.defSitesOf(
                                it,
                                cmdPointer
                            )
                        }) {
                        /* [defOfStore.exp] is defined at [cmdPointer] the same as at [defOfStore.ptr]; therefore
                        * the folding is safe.
                        * */
                        patching.update(
                            cmdPointer
                        ) { simple.copy(rhs = nonTrivialDefOfRhs.exp) }
                    }
                }
            }
        }

        return patching.toCode(p, TACCmd.Simple.NopCmd)
    }

    /**
     * Folds assignments that are used once to save on variables in the program [p].
     * Assignments that contain sub-expressions for arithmetic operations,
     * array accesses, ite-s and special functions are not folded.
     * The motivation is to avoid folding of expressions that may lead to bigger axiom formulas,
     * in particular the array axioms, and to reduce the number of uninterp_mod_256 applications.
     *
     * Simple symbols are always folded.
     *
     * @param ignoreCallTrace Whether to fold assignments without considering meta-keys required for CallTrace construction.
     */
    private fun rewriteInternal(p: CoreTACProgram): Pair<CoreTACProgram, Boolean> {
        val g = p.analysisCache.graph
        val use = SingleUseAnalysis(g, p.topoSortFw)
        val definedOnce = g.commands.filter { lcmd ->
            lcmd.cmd is TACCmd.Simple.AssigningCmd
        }.mapNotNull {
            it.cmd.getLhs()
        }.groupingBy { it }.eachCount().filter { it.value == 1 }

        val ufVariablesDoNotFold = p.symbolTable.uninterpretedFunctions().filter { uf -> uf.paramSorts.isEmpty() }
            .map { uf -> TACSymbol.Var(uf.name, uf.returnSort) }
        val assignmentsToFold = g.commands.filter { lcmd ->
            lcmd.maybeNarrow<TACCmd.Simple.AssigningCmd.AssignExpCmd>()?.let { c ->
                val lhs = c.cmd.lhs
                val e = c.cmd.rhs
                // defined once
                // use a use analysis that efficiently finds a single use and null if it's not a single use
                val lazyComputedDataFromUse by lazy {
                    val t = use.useSitesAfter(lhs, c.ptr)
                        .filter { g.elab(it).cmd.let { c -> c is TACCmd.Simple.AssigningCmd || c is TACCmd.Simple.AssumeCmd || c is TACCmd.Simple.AssumeNotCmd } }

                    t to (t.singleOrNull()?.let {
                        // this is definitely not a select if we're not folding selects inside other expressions and we're in simple-simple fragment
                        g.elab(it)
                            .maybeNarrow<TACCmd.Simple.AssigningCmd.AssignExpCmd>()?.cmd?.rhs?.let { useExp ->
                                useExp is TACExpr.Select || useExp is TACExpr.Store || useExp is TACExpr.LongStore || useExp is TACExpr.MultiDimStore
                                    || useExp is TACExpr.BinOp.BWAnd || useExp is TACExpr.BinOp.BWOr || useExp is TACExpr.BinOp.BWXOr || useExp is TACExpr.BinBoolOp.LAnd
                            }
                    } == true)
                }

                val isNonArithOrArray = e.isEligibleForFolding(allowAxiomatized = false)

                (p.destructiveOptimizations || (!c.cmd.meta.containsKey(CVL_EXP) && !lhs.meta.containsKey(CVL_VAR) && lhs !in ufVariablesDoNotFold &&
                    !lhs.meta.containsKey(IS_RETURNDATA))) && lhs in definedOnce &&
                    // option 1: symbols, doesn't matter how many times used
                    ((e is TACExpr.Sym) ||
                        // option 2:
                        // in any case don't fold inside array expressions
                        // start from true unless finds subexpression that makes it ineligible in general
                        lazyComputedDataFromUse.let { (uses, useIsArrayOrBitwise) ->
                            (isNonArithOrArray &&
                                // single use within assignments and assumes (will be substituted and for assume -> assumeExps) that is not an array expression
                                uses.size == 1 && !useIsArrayOrBitwise)
                        })
            } == true
        }.map { lcmd ->
            lcmd.narrow<TACCmd.Simple.AssigningCmd.AssignExpCmd>().let { c ->
                c.cmd.lhs to c.cmd.rhs
            }
        }.toMap() // improve the map creation

        var changed = false
        val maxDepth = Config.MaxHeuristicFoldingDepth.get()
        val mapper = object : DefaultTACCmdMapperWithPointer() {
            var depth = 0

            override val exprMapper = object : DefaultTACExprTransformer() {
                override fun transform(exp: TACExpr): TACExpr {
                    if(depth >= maxDepth && maxDepth != -1) {
                        return exp
                    }
                    depth++
                    return try {
                        when (exp) {
                            is TACExpr.Sym.Var -> {
                                if (exp.s in assignmentsToFold &&
                                    DIRECT_STORAGE_ACCESS_TYPE !in exp.s.meta &&
                                    !exp.s.meta.hasKeyword(BALANCE) &&
                                    !exp.s.meta.hasKeyword(EXTCODESIZE)
                                ) {
                                    changed = true
                                    assignmentsToFold[exp.s]!!
                                } else {
                                    exp
                                }
                            }
                            else -> super.transform(exp)
                        }
                    } catch (e: TACExprTransformerException) {
                        logger.warn(e) { "Could not transform $exp, falling back to original expression" }
                        exp
                    } finally {
                        depth--
                    }
                }
            }

            override fun mapExpr(expr: TACExpr): TACExpr {
                return exprMapper.transform(expr)
            }

            override fun mapAssignExpCmd(t: TACCmd.Simple.AssigningCmd.AssignExpCmd): TACCmd.Simple {
                return if (t.lhs in assignmentsToFold) {
                    // don't modify hash based assignments!
                    // ~but can remove them because they're folded~ only later
                    t
                } else {
                    t.copy(rhs = mapExpr(t.rhs), meta = mapMeta(t.meta))
                    // don't modify lhs-s
                }
            }

            override fun mapSymbol(t: TACSymbol): TACSymbol {
                return when (t) {
                    is TACSymbol.Const -> { t }
                    is TACSymbol.Var -> { (assignmentsToFold[t] as? TACExpr.Sym.Var)?.s ?: t }
                }
            }

            override fun mapAssumeCmd(t: TACCmd.Simple.AssumeCmd): TACCmd.Simple {
                return assignmentsToFold[t.cond]?.let { TACCmd.Simple.AssumeExpCmd(it) } ?: t
            }

            override fun mapAssumeNotCmd(t: TACCmd.Simple.AssumeNotCmd): TACCmd.Simple {
                return assignmentsToFold[t.cond]?.let { TACCmd.Simple.AssumeExpCmd(TACExpr.UnaryExp.LNot(it)) } ?: t
            }
        }

        return mapper.mapCommands(p) to changed
    }

    fun removeAnnotations(c: CoreTACProgram): CoreTACProgram {
        // replace the annotation commands that are not of the "nothing value"
        // with labels (could do so that it matches their codemap representation, but punting on it)
        // why keep the nothing value? because for example some of them are used to compute splitting scores,
        // so if we remove them, splitting is disabled.
        val g = c.analysisCache.graph
        val p = c.toPatchingProgram()
        g.commands.filter { it.cmd is TACCmd.Simple.AnnotationCmd && it.cmd.annot.v.toString() != MetaMap.Companion.nothing.toString() }
            .forEach {
                p.update(it.ptr) { TACCmd.Simple.LabelCmd(it.cmd.toString())}
            }
        return p.toCode(c)
    }

}

internal object PeepHoleOptimizer {
    val peepHoleOptimizeExprMapper = object : DefaultTACExprTransformer() {
        override fun transformUnary(e: TACExpr.UnaryExp): TACExpr {
            return when (val unary = super.transformUnary(e)) {
                is TACExpr.UnaryExp.LNot -> {
                    if (unary.o is TACExpr.UnaryExp.LNot) {
                        unary.o.o
                    } else {
                        unary
                    }
                }
                else -> unary
            }
        }

        /**
         * Idea of the simplification here:
         * If we have signextend(x,se) << bits then if (se+1)*8+bits = 256, the sign extended bits are
         * disappearing in the left shift, thus we can just do x << bits.
         * The reason is that signextend(x,b) signextends x assuming it has (b+1)*8 bits to 256... so if we
         * add to the sign extend bits the left shift bits and we get 256, it means the signextended bits
         * disappear.
         */
        override fun transformShiftLeft(e: TACExpr.BinOp): TACExpr {
            val shiftLeft = super.transformShiftLeft(e)
            if (shiftLeft !is TACExpr.BinOp.ShiftLeft) {
                return shiftLeft
            }

            val bits = (shiftLeft.o2 as? TACExpr.Sym.Const)?.s?.value ?: return shiftLeft

            val signExtend = (shiftLeft.o1 as? TACExpr.BinOp.SignExtend)
            val seIntTypeSizeInBytes =
                (signExtend?.o1 as? TACExpr.Sym.Const)?.s?.value

            if (seIntTypeSizeInBytes != null) {
                if ((seIntTypeSizeInBytes + BigInteger.ONE) * BigInteger.valueOf(8) + bits >=
                    EVM_BITWIDTH256.toBigInteger()
                ) {
                    // All the bits that the signExtend changed are dropped by the shiftLeft, thus we can drop the signextend.
                    return shiftLeft.copy(o1 = signExtend.o2)
                }
            }

            val bwand = (shiftLeft.o1 as? TACExpr.BinOp.BWAnd)
            val bwandBits = (bwand?.o2 as? TACExpr.Sym.Const)?.s?.value
            if (bwandBits != null && bits < EVM_BITWIDTH256.toBigInteger()) {
                if (bwandBits == BigInteger.TWO.pow(EVM_BITWIDTH256 - bits.toInt()) - BigInteger.ONE) {
                    return shiftLeft.copy(o1 = bwand.o1)
                }
            }

            return shiftLeft
        }

        override fun transformIte(e: TACExpr.TernaryExp.Ite): TACExpr {
            val ite = super.transformIte(e)
            if (ite !is TACExpr.TernaryExp.Ite) {
                return ite
            }
            return if (ite.i is TACExpr.Sym.Const && ite.i.s == TACSymbol.True) {
                ite.t
            } else if (ite.i is TACExpr.Sym.Const && ite.i.s == TACSymbol.False) {
                ite.e
            } else {
                ite
            }
        }

        override fun transformEq(e: TACExpr.BinRel.Eq): TACExpr {
            val eq = super.transformEq(e)
            if (eq !is TACExpr.BinRel.Eq) {
                return eq
            }
            return if (eq.o1 is TACExpr.Sym.Const && eq.o2 is TACExpr.Sym.Const) {
                TACSymbol.lift(eq.o1.s.value == eq.o2.s.value).asSym()
            } else if (eq.o1 == eq.o2) { // bizarre but happens
                TACSymbol.True.asSym()
            } else {
                eq
            }
        }

        // this looks peephole but actually instrumental for optimizing def axioms (alex: I wonder why..)
        override fun transformVecMul(ls: List<TACExpr>, tag: Tag.Bits?): TACExpr {
            val mul = super.transformVecMul(ls, tag)
            if (mul !is TACExpr.Vec.Mul) {
                return mul
            }
            return transformAlgebraicOp(
                mk = { ops: List<TACExpr> -> TACExprFactBasicSimp.Mul(ops, tag) },
                operands = mul.ls,
                arithOp = { x, y -> x.times(y).mod(EVM_MOD_GROUP256) },
                neutral = BigInteger.ONE,
                absorbing = BigInteger.ZERO,
                Tag.Bit256
            )
        }

        override fun transformVecIntMul(ls: List<TACExpr>, tag: Tag.Int?): TACExpr {
            val mul = super.transformVecIntMul(ls,tag)
            if (mul !is TACExpr.Vec.IntMul) {
                return mul
            }
            return transformAlgebraicOp(
                mk = { ops: List<TACExpr> -> TACExprFactBasicSimp.IntMul(ops, tag) },
                operands = mul.ls,
                arithOp = BigInteger::times,
                neutral = BigInteger.ONE,
                absorbing = BigInteger.ZERO,
                Tag.Int
            )
        }

        override fun transformVecAdd(ls: List<TACExpr>, tag: Tag.Bits?): TACExpr {
            val add = super.transformVecAdd(ls, tag)
            if (add !is TACExpr.Vec.Add) {
                return add
            }
            return transformAlgebraicOp(
                mk = { ops -> TACExprFactBasicSimp.Add(ops, tag) },
                operands = add.ls,
                arithOp = { x, y -> x.plus(y).mod(EVM_MOD_GROUP256) },
                neutral = BigInteger.ZERO,
                absorbing = null,
                Tag.Bit256
            )
        }


        override fun transformVecIntAdd(ls: List<TACExpr>, tag: Tag.Int?): TACExpr {
            val add = super.transformVecIntAdd(ls, tag)
            if (add !is TACExpr.Vec.IntAdd) {
                return add
            }
            return transformAlgebraicOp(
                mk = { ops -> TACExprFactBasicSimp.IntAdd(ops, tag) },
                operands = add.ls,
                arithOp = BigInteger::plus,
                neutral = BigInteger.ZERO,
                absorbing = null,
                Tag.Int
            )
        }

        override fun transformLOr(ls: List<TACExpr>, tag: Tag.Bool?): TACExpr {
            val lor = super.transformLOr(ls, tag)
            if (lor !is TACExpr.BinBoolOp.LOr) {
                return lor
            }
            return transformAlgebraicOp(
                mk = { ops -> TACExprFactBasicSimp.LOr(ops, tag) },
                operands = lor.ls,
                arithOp = { x,y -> x || y },
                neutral = false,
                absorbing = true,
                converterFrom = { t -> t != BigInteger.ZERO },
                converterTo = { b ->
                    if (b) {
                        BigInteger.ONE
                    } else {
                        BigInteger.ZERO
                    }
                },
                Tag.Bool
            )
        }

        override fun transformLAnd(ls: List<TACExpr>, tag: Tag.Bool?): TACExpr {
            val land = super.transformLAnd(ls, tag)
            if (land !is TACExpr.BinBoolOp.LAnd) {
                return land
            }
            return transformAlgebraicOp(
                mk = { ops -> TACExprFactBasicSimp.LAnd(ops, tag) },
                operands = land.ls,
                arithOp = { x,y -> x && y },
                neutral = true,
                absorbing = false,
                converterFrom = { t -> t != BigInteger.ZERO },
                converterTo = { b ->
                    if (b) {
                        BigInteger.ONE
                    } else {
                        BigInteger.ZERO
                    }
                },
                Tag.Bool
            )
        }

        /**
         * Simplify an operation that has a neutral element, and possibly an absorbing element (Add, Mul, LOr, LAnd...).
         * Further properties:
         *  - for vec operations; will compute constant parts
         *  - for non-"mathint" operations -- constant computations should be done mod 2^256
         */
        private fun <T> transformAlgebraicOp(
            mk: (List<TACExpr>) -> TACExpr,
            operands: List<TACExpr>,
            arithOp: (T, T) -> T,
            neutral: T,
            absorbing: T?,
            converterFrom: (BigInteger) -> T,
            converterTo: (T) -> BigInteger,
            tag: Tag
        ): TACExpr {
            val nonNeutralConstants = mutableListOf<TACExpr.Sym.Const>()
            val resultOperands = LinkedList<TACExpr>()

            operands.forEach { arg ->
                val const = (arg as? TACExpr.Sym.Const)?.s
                when (const?.value) {
                    null -> {
                        resultOperands.add(arg)
                    }
                    neutral -> { /* do nothing */ }
                    absorbing -> {
                        return arg
                    }
                    else -> {
                        nonNeutralConstants.add(arg)
                    }
                }

            }

            if (nonNeutralConstants.size > 1) {
                val constantsFolded = nonNeutralConstants
                    .fold(neutral) { acc, const -> arithOp(acc, converterFrom(const.s.value)) }
                resultOperands.addFirst(TACSymbol.Const(converterTo(constantsFolded), tag).asSym())
            } else if (nonNeutralConstants.size == 1) {
                resultOperands.addFirst(nonNeutralConstants.first())
            }

            return if (resultOperands.isNotEmpty()) {
                mk(resultOperands)
            } else {
                TACSymbol.Const(converterTo(neutral), tag).asSym()
            }
        }

        private fun transformAlgebraicOp(
            mk: (List<TACExpr>) -> TACExpr,
            operands: List<TACExpr>,
            arithOp: (BigInteger, BigInteger) -> BigInteger,
            neutral: BigInteger,
            absorbing: BigInteger?,
            tag: Tag) = transformAlgebraicOp(mk, operands, arithOp, neutral, absorbing, { t -> t }, { t -> t }, tag)
    }


    /**
     * Implements simple optimizations over the TAC program [p].
     * In particular:
     * - `a==a` as true
     * - `c1=c2` evaluated
     * - !!b as b
     * - filter neutral elements from mul and add, with goal of getting rid of redundant mod ops
     */
    fun peepholeOptimize(p: CoreTACProgram): CoreTACProgram {
        val simplifier = object : DefaultTACCmdMapper() {
            override fun mapExpr(expr: TACExpr): TACExpr {
                return peepHoleOptimizeExprMapper.transform(expr)
            }
        }
        val patching = p.toPatchingProgram()
        p.analysisCache.graph.commands.forEach { lcmd ->
            val rhs = lcmd.maybeNarrow<TACCmd.Simple.AssigningCmd.AssignExpCmd>()?.cmd?.rhs
            if (rhs != null) {
                patching.update(lcmd.ptr) { c -> simplifier.map(c) }
            }
        }

        return patching.toCode(p)
    }
}
