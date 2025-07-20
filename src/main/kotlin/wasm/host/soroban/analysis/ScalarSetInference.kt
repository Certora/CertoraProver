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
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package wasm.host.soroban

import algorithms.UnionFind
import analysis.*
import analysis.PatternMatcher.Pattern.Companion.toBuildable
import analysis.numeric.IntValue
import analysis.split.Ternary.Companion.isPowOf2
import com.certora.collect.*
import datastructures.stdcollections.*
import evm.MASK_SIZE
import instrumentation.transformers.DSA_BLOCK_END
import instrumentation.transformers.DSA_BLOCK_START
import log.*
import tac.Tag
import utils.*
import vc.data.*
import vc.data.tacexprutil.getFreeVars
import wasm.analysis.intervals.IntervalAnalysis
import wasm.host.soroban.Val.isObjectTagValue
import wasm.host.soroban.analysis.ValTagAnalysis
import wasm.host.soroban.types.SymbolType
import wasm.impCfg.*
import wasm.impCfg.WASM_HOST_FUNC_SUMMARY_START
import wasm.impCfg.WASM_SDK_FUNC_SUMMARY_START
import wasm.summarization.soroban.SorobanSDKSummarizer
import tac.generation.ConditionalTrapRevert
import wasm.analysis.intervals.interpret
import wasm.host.soroban.ScalarSetTypeInference.PartitionedType.Base
import wasm.analysis.intervals.State as IntervalState
import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap
import java.util.stream.Collectors

private val logger = Logger(LoggerTypes.WASM_ANALYSIS)

/**
 * This analysis determines whether object vals are used "symmetrically".
 *
 *  Roughly, we want to know if reachability in the input program is preserved
 *  if we arbitrarily permute object ids (i.e. the upper 56 bits of Object vals).
 *
 *  This is typically the case if the program does not do any inspection of these handles
 *  (and we don't expect a legitimate need to do so - they really are just opaque identifiers).
 *
 *  The analysis works by type inference: each variable has a type that says whether it is "symmetric".
 *  The global typing then defines a (family) of automorphisms H(G, p) where G is a type environment and
 *  p is a permutation on 56-bit values.
 *  The action of H(G, p) on a (64-bit) value v is:
 *    - if (v & 0xFF) is not an object tag (see [Val.Tag]), then just return v
 *    - otherwise, (p(truncate(56, (v >> 8))) << 8) | (v & 0xFF)
 *  Then the action of H(G, p) on a state S (mapping of variables to values) is S' where:
 *    S'[x] = if G(x) = sym then H(G, p) v else v IFF S[x] = v
 *
 *  The main soundness theorem of the analysis is that if the program can take a step from state s1 to state s2,
 *  then the program can also step from H(G, p)s1 to H(G, p)s2 (this is actually an iff).
 *
 *  See "Better Verification Through Symmetry" by Dill and Ip for the main idea - we extend
 */
class ScalarSetTypeInference(
    private val graph: TACCommandGraph,
    symbolTable: TACSymbolTable
) {
    private var freshCtr: Int = 0

    /**
     * [BaseType] indicates whether the construction of the typed term is 'symmetric':
     * If E : symmetric, then if E evalutes to V in state S, given a handle permutation h,
     * it must be the case that E evalutes to apply_value(h, V) in state apply_state(h, S)
     *
     * e.g. the literal value 0x12344 is _not_ symmetric: the tag is 0x44,
     *   so apply_value(h, 0x12344) == h(0x123)|0x44 while evaluating 0x12344 in any state
     *   obviously results in the value 0x12344.
     */
    private sealed class BaseType {
        open val baseVars: Set<TyVar> = setOf()

        /** non-symmetric constructions */
        data object Top : BaseType()

        /** symmetric constructions */
        data object Sym : BaseType()

        /**
         * Unification variables
         */
        data class TyVar(val id: Int) : BaseType() {
            override val baseVars get() = setOf(this)
        }
    }

    private sealed class PartitionedType {
        abstract val baseVars: Set<BaseType.TyVar>

        data class Map(val indices: List<BaseType>, val result: BaseType) : PartitionedType() {
            constructor (i: BaseType, r: BaseType) : this(listOf(i), r)

            override val baseVars get() = indices.flatMapToSet { it.baseVars } + result.baseVars
        }

        sealed interface TypesInState {
            fun types(phi: IntervalState): List<BaseType>
        }

        data class Base(val b: BaseType) : TypesInState, PartitionedType() {
            override val baseVars: Set<BaseType.TyVar>
                get() = b.baseVars

            override fun types(phi: IntervalState): List<BaseType> {
                return listOf(b)
            }
        }

        data class Partitioned(val c: TACSymbol.Var, val partitioning: TreapMap<Partition, BaseType>) : TypesInState,
            PartitionedType() {
            override val baseVars
                get() = partitioning.values.flatMapToSet { it.baseVars }

            private fun IntervalState.sat(p: PartitionedType.Partition): Boolean {
                return interpret(c.asSym())?.x?.mayIntersect(p.v) ?: true
            }

            override fun types(phi: IntervalState): List<BaseType> =
                partitioning.mapNotNull { (part, ty) ->
                    ty.takeIf { phi.sat(part) }
                }
        }

        data class Partition(val v: IntValue) {
            override fun toString(): String =
                if (v == IntValue.Nondet) {
                    "{ T }"
                } else if (v.ub == IntValue.Nondet.ub) {
                    "{ [${v.lb}, T] }"
                } else if (v.isConstant) {
                    "{ ${v.c} }"
                } else {
                    "{ [${v.lb}, ${v.ub}] }"
                }
        }

        companion object {
            fun top(t: Tag?): PartitionedType =
                when (t) {
                    is Tag.GhostMap ->
                        Map(t.paramSorts.map { BaseType.Top }, BaseType.Top)

                    is Tag.ByteMap ->
                        Map(BaseType.Top, BaseType.Top)

                    else ->
                        Base(BaseType.Top)
                }
        }
    }

    /** A constraint [t] = [u] | ([phiT], [phiU])  */
    private data class TypeConstraint(
        val phiT: IntervalState,
        val t: PartitionedType,
        val phiU: IntervalState,
        val u: PartitionedType
    ) {
        constructor (phi: IntervalState, t: PartitionedType, u: PartitionedType): this (phi, t, phi, u)

        val isNonTrivial: Boolean
            get() = t != u
    }

    private data class SimpleConstraint(val where: CmdPointer, val t: BaseType, val u: BaseType)

    private fun gatherSimpleConstraints(): Collection<SimpleConstraint> {
        val simpleConstraints = mutableSetOf<SimpleConstraint>()

        for ((w, cs) in constraints) {
            for (c in cs) {
                if (c.t is PartitionedType.Map && c.u is PartitionedType.Map) {
                    check(c.t.indices.size == c.u.indices.size)
                    simpleConstraints.addAll(
                        c.u.indices.zip(c.t.indices).map { (ut, ct) ->
                            SimpleConstraint(w, ut, ct)
                        }
                    )
                    simpleConstraints.add(
                        SimpleConstraint(w, c.t.result, c.u.result)
                    )
                } else if (c.t is PartitionedType.TypesInState && c.u is PartitionedType.TypesInState) {
                    for (t in c.t.types(c.phiT)) {
                        for (u in c.u.types(c.phiU)) {
                            simpleConstraints.add(SimpleConstraint(w, t, u))
                        }
                    }
                } else {
                    `impossible!`
                }
            }
        }

        return simpleConstraints
    }

    private fun UnionFind<BaseType>.hasType(x: BaseType, b: BaseType): Boolean {
        return this.areEqual(x, b)
    }

    private fun solveConstraints(cs: Collection<SimpleConstraint>): Either<Pair<SimpleConstraint, UnionFind<BaseType>>, UnionFind<BaseType>> {
        val uf = UnionFind<BaseType>()

        for (c in cs) {
            if ( (uf.hasType(BaseType.Sym, c.t) && uf.hasType(BaseType.Top, c.u)) ||
                 (uf.hasType(BaseType.Sym, c.u) && uf.hasType(BaseType.Top, c.t))) {
                return (c to uf).toLeft()
            }
            uf.union(c.t, c.u)
        }

        return uf.toRight()
    }

    private data class TypeContext(
        val g: TreapMap<TACSymbol.Var, PartitionedType>,
    ) {
        operator fun get(x: TACSymbol.Var): PartitionedType =
            g[x] ?: `impossible!`
    }

    private fun freshBase() = freshCtr++.let(BaseType::TyVar)

    private fun freshPart(t: Tag?) = when (t) {
        is Tag.GhostMap ->
            PartitionedType.Map(t.paramSorts.map { freshBase() }, freshBase())

        is Tag.ByteMap ->
            PartitionedType.Map(freshBase(), freshBase())

        else ->
            Base(freshBase())
    }

    private fun frame(where: CmdPointer, ctx: TypeContext, modified: Collection<TACSymbol.Var>) {
        ctx.g.forEachEntry { (y, t) ->
            if (y !in modified && t is PartitionedType.Partitioned && t.c in modified) {
               assignConstraint(where, t, t)
            }
        }
    }

    private fun consGenStmt(ctx: TypeContext, lcmd: LTACCmd) {
        val where = lcmd.ptr
        val cmd = lcmd.cmd

        // Because types are partitioned by abstract state,
        // assigning a variable results in a change in abstract state. Concretely, assume we're analyzing
        //
        // x := e
        //
        // another program variable y's partitions may (or may not) depend on x. There's an implicit flow, then,
        // from the type of y in the pre-state to the type of y in the post-state of the assignment.
        //
        if (cmd is TACCmd.Simple.AssigningCmd) {
            val x = cmd.lhs
            frame(where, ctx, setOf(x))
        } else if (cmd is TACCmd.Simple.SummaryCmd) {
            val summ = cmd.summ
            if (summ is AssigningSummary) {
                frame(where, ctx, summ.mustWriteVars)
            }
        }

        when (cmd) {
            is TACCmd.Simple.AssigningCmd.AssignHavocCmd ->
                consGenHavoc(ctx, where, cmd)

            is TACCmd.Simple.AssigningCmd.AssignExpCmd -> {
                setTagMatcher.queryFrom(lcmd.narrow()).toNullableResult()?.let { match ->
                    val (v, shift) = match.first
                    val tag = match.second.safeAsInt()
                    if (shift < 8.toBigInteger()) {
                        assignConstraint(where, ctx[cmd.lhs], PartitionedType.top(cmd.lhs.tag))
                        return
                    } else if (tag.isObjectTagValue) {
                        // If it *is* an object, then is the ID effectively unconstrainted? Then that's OK
                        // This is all an artifact of the fact that our nondet val uses the builtin nondetu64, but the val
                        // construction is in "userland"
                        val isHavoc = def.getDefCmd<TACCmd.Simple.AssigningCmd.AssignHavocCmd>(v, where)
                        if (isHavoc == null) {
                            assignConstraint(where, ctx[cmd.lhs], PartitionedType.top(cmd.lhs.tag))
                            return
                        }
                    }
                    // If it's *not* an object value,
                    // then this step is definitely invariant under handle permutations
                    assignConstraint(where, ctx[cmd.lhs], freshPart(cmd.lhs.tag))
                } ?: consGenAssignExpr(ctx, where, cmd.lhs, cmd.rhs)
            }

            is TACCmd.Simple.AssigningCmd.ByteStore -> {
                val baseType = ctx[cmd.base]
                check(baseType is PartitionedType.Map)
                val locType = consGenExpr(ctx, where, cmd.loc.asSym())
                val valType = consGenExpr(ctx, where, cmd.value.asSym())
                equalInState(where, locType, Base(baseType.indices.single()))
                equalInState(where, valType, Base(baseType.result))
            }

            is TACCmd.Simple.AssigningCmd ->
                assignConstraint(where, ctx[cmd.lhs], PartitionedType.top(cmd.lhs.tag))

            is TACCmd.Simple.AnnotationCmd -> {
                when {
                    cmd.maybeAnnotation(WASM_INLINED_FUNC_START) != null ||
                        cmd.maybeAnnotation(WASM_HOST_FUNC_SUMMARY_START) != null ||
                        cmd.maybeAnnotation(WASM_SDK_FUNC_SUMMARY_START) != null ||
                        cmd.maybeAnnotation(WASM_INLINED_FUNC_END) != null ||
                        cmd.maybeAnnotation(WASM_HOST_FUNC_SUMMARY_END) != null ||
                        cmd.maybeAnnotation(WASM_SDK_FUNC_SUMMARY_END) != null -> {
                        // Skip
                    }

                    else -> {
                        cmd.getFreeVarsOfRhs().forEach {
                            equalInState(where, PartitionedType.top(it.tag), ctx[it])
                        }
                    }
                }
            }

            is TACCmd.Simple.SummaryCmd -> consGenSummary(ctx, where, cmd.summ)

            else -> cmd.getFreeVarsOfRhs().forEach {
                equalInState(where, PartitionedType.top(it.tag), ctx[it])
            }
        }
    }

    private fun consGenHavoc(ctx: TypeContext, where: CmdPointer, cmd: TACCmd.Simple.AssigningCmd.AssignHavocCmd) {
        val t = ctx[cmd.lhs]
        val u = freshPart(cmd.lhs.tag)
        assignConstraint(where, t, u)
    }

    private fun consGenAssignExpr(ctx: TypeContext, where: CmdPointer, lhs: TACSymbol.Var, rhs: TACExpr) {
        val t1 = ctx[lhs]
        val t2 = consGenExpr(ctx, where, rhs)
        assignConstraint(where, t1, t2)
    }

    private fun consGenSummary(ctx: TypeContext, where: CmdPointer, summ: TACSummary) {
        when (summ) {
            is SorobanSDKSummarizer.ValToIntIntrinsic ->
                summ.mustWriteVars.forEach {
                    assignConstraint(where, ctx[it], PartitionedType.top(it.tag))
                }

            is Val.CheckValid ->
                assignConstraint(where, ctx[summ.out], PartitionedType.top(summ.out.tag))

            is ConditionalTrapRevert ->
                summ.cond.tryAs<TACSymbol.Var>()?.let {
                    assignConstraint(where, ctx[it], PartitionedType.top(it.tag))
                }

            is SymbolType.SymbolIndexInMemory ->
                setOfNotNull<TACSymbol.Var>(summ.len.tryAs(), summ.retIndex.tryAs(), summ.slicesPos.tryAs()).forEach {
                    assignConstraint(where, ctx[it], PartitionedType.top(it.tag))
                }

            else ->
                summ.variables.forEach {
                    assignConstraint(where, ctx[it], PartitionedType.top(it.tag))
                }
        }
    }

    private fun consGenExpr(ctx: TypeContext, where: CmdPointer, e: TACExpr): PartitionedType {
        return when (e) {
            is TACExpr.Sym.Var -> ctx[e.s]

            is TACExpr.Sym.Const -> {
                val tag = e.s.value.and(255.toBigInteger()).safeAsInt()
                if (tag in Val.Tag.ObjectCodeLowerBound.value..Val.Tag.ObjectCodeUpperBound.value) {
                    PartitionedType.top(e.tag)
                } else {
                    // Because h(this) == this
                    Base(freshBase())
                }
            }

            is TACExpr.BinOp.BWAnd -> {
                val t1 = consGenExpr(ctx, where, e.o1)
                val t2 = consGenExpr(ctx, where, e.o2)
                if (!(e.o2AsNullableTACSymbol() == 255.asTACSymbol() || e.o1AsNullableTACSymbol() == 255.asTACSymbol())) {
                    equalInState(where, t1, PartitionedType.top(e.o1.tag))
                    equalInState(where, t2, PartitionedType.top(e.o2.tag))
                }
                PartitionedType.top(e.tag)
            }

            is TACExpr.BinOp.SignExtend -> {
                val t1 = consGenExpr(ctx, where, e.o1)
                val t2 = consGenExpr(ctx, where, e.o2)

                equalInState(where, t1, PartitionedType.top(e.o1.tag))
                // We're sign extending s2. If o2 must not be an object, then it's "safe" (invariant under handle permutaitons)
                // (e.g., it might be an I128Small, so we can just sign extend/shift around to extract the i128)
                e.o2AsNullableTACSymbol()?.let { o2 ->
                    if (tags.tagSet(where, o2)?.all { !it.isObjectTag } == true) {
                        return PartitionedType.top(e.tag)
                    }
                }
                // Otherwise it's now Top
                equalInState(where, t2, PartitionedType.top(e.o2.tag))
                PartitionedType.top(e.tag)
            }

            is TACExpr.BinOp.Mod -> {
                val t1 = consGenExpr(ctx, where, e.o1)
                val t2 = consGenExpr(ctx, where, e.o2)
                if (e.o2 is TACExpr.Sym.Const) {
                    // x % tag_mul is fine because the lower 8 bits are invariant
                    if (e.o2.s.value == Val.TAG_MUL.toBigInteger()) {
                        return PartitionedType.top(e.tag)
                    } else if (e.o2.s.value == BigInteger.TWO.pow(64)) {
                        where.stateAt()?.let {
                            val x = it.interpret(e.o1)?.x ?: IntValue.Nondet
                            if (BigInteger.TWO.pow(64) <= x.ub) {
                                equalInState(where, t1, PartitionedType.top(e.o1.tag))
                            }
                        }
                        return t1
                    }
                }
                equalInState(where, t1, PartitionedType.top(e.o1.tag))
                equalInState(where, t2, PartitionedType.top(e.o2.tag))
                return t1
            }

            is TACExpr.BinOp.ShiftRightLogical -> {
                val t1 = consGenExpr(ctx, where, e.o1)
                val t2 = consGenExpr(ctx, where, e.o2)
                // If the shifted element must not be an object, we can ignore it
                if (e.o1 !is TACExpr.Sym.Var || tags.tagSet(where, e.o1.s)?.all { !it.isObjectTag } != true) {
                    equalInState(where, t1, PartitionedType.top(e.o1.tag))
                }
                equalInState(where, t2, PartitionedType.top(e.o2.tag))
                PartitionedType.top(e.tag)
            }

            is TACExpr.BinRel.Le -> {
                val t1 = consGenExpr(ctx, where, e.o1)
                val t2 = consGenExpr(ctx, where, e.o2)
                equalInState(where, t1, t2)
                if (!(e.o2 is TACExpr.Sym.Const && e.o2.s.value >= MASK_SIZE(64))) {
                    equalInState(where, t1, PartitionedType.top(e.o1.tag))
                }
                PartitionedType.top(e.tag)
            }

            is TACExpr.BinRel.Lt -> {
                val t1 = consGenExpr(ctx, where, e.o1)
                val t2 = consGenExpr(ctx, where, e.o2)
                // A very special case: the 'assume' we insert to constrain the fresh handle.
                e.o1AsNullableTACSymbol()?.tryAs<TACSymbol.Var>()?.let { x ->
                    val isFreshHandle =
                        def.getDefCmd<TACCmd.Simple.AssigningCmd.AssignHavocCmd>(x, where)
                            ?.cmd?.meta?.containsKey(Val.WASM_FRESH_HANDLE) == true
                    val rhsGtHandleSize =
                        where.stateAt()?.interpret(e.o2)?.x?.isDefinitelyGreaterThan(MASK_SIZE(Val.sizeInBytes*8)) == true
                    if (isFreshHandle && rhsGtHandleSize) {
                        // Then we don't need to equate t1 and t2
                        return PartitionedType.top(e.tag)
                    }
                }
                // Similarly, if we have x < 2**64 and x is definitely less than 2**64, we can skip the equality
                if (!(e.o2 is TACExpr.Sym.Const
                        && BigInteger.TWO.pow(Val.sizeInBytes*8) <= e.o2.s.value
                        && where.stateAt()?.interpret(e.o1)?.x?.ub?.let { it < BigInteger.TWO.pow(Val.sizeInBytes*8)} == true)) {
                    equalInState(where, PartitionedType.top(e.o1.tag), t1)
                    equalInState(where, t1, t2)
                }
                PartitionedType.top(e.tag)
            }

            is TACExpr.TernaryExp.Ite -> {
                consGenExpr(ctx, where, e.i)
                val tTy = consGenExpr(ctx, where, e.t)
                val eTy = consGenExpr(ctx, where, e.e)

                val ite = freshPart(e.tag)
                equalInState(where, tTy, ite)
                equalInState(where, eTy, ite)
                ite
            }

            is TACExpr.MapDefinition -> {
                val indices = e.defParams.map { freshBase() }
                val bodyCtx = TypeContext(ctx.g.putAll(e.defParams.zip(indices).map { (x, b) -> x.s to Base(b) }))
                val resultTy = consGenExpr(bodyCtx, where, e.definition)
                val retTy = freshBase()
                equalInState(where, resultTy, Base(retTy))
                PartitionedType.Map(indices, retTy)
            }

            is TACExpr.SimpleHash -> {
                PartitionedType.top(e.tag)
            }

            is TACExpr.BinRel.Eq -> {
                val t1 = consGenExpr(ctx, where, e.o1)
                val t2 = consGenExpr(ctx, where, e.o2)
                equalInState(where, t1, t2)
                // We could just make this 'Top', but technically the equality should be invariant under
                // permutations
                freshPart(e.tag)
            }

            is TACExpr.Select -> {
                val selectInfo = e.extractMultiDimSelectInfo()
                val baseType = consGenExpr(ctx, where, selectInfo.base)
                check(baseType is PartitionedType.Map)
                check(selectInfo.indices.size == baseType.indices.size)
                baseType.indices.zip(selectInfo.indices).forEach { (t, ei) ->
                    val et = consGenExpr(ctx, where, ei)
                    equalInState(where, Base(t), et)
                }
                Base(baseType.result)
            }

            is TACExpr.MultiDimStore -> {
                val mTy = consGenExpr(ctx, where, e.base)
                check(mTy is PartitionedType.Map)
                val lTy = e.locs.map { consGenExpr(ctx, where, it) }
                check(mTy.indices.size == lTy.size)
                mTy.indices.zip(lTy).forEach {
                    equalInState(where, Base(it.first), it.second)
                }
                val vTy = consGenExpr(ctx, where, e.value)
                equalInState(where, Base(mTy.result), vTy)
                mTy
            }

            is TACExpr.Store -> {
                val mTy = consGenExpr(ctx, where, e.base)
                val lTy = consGenExpr(ctx, where, e.loc)
                val vTy = consGenExpr(ctx, where, e.value)
                check(mTy is PartitionedType.Map)
                check(mTy.indices.size == 1)
                equalInState(where, lTy, Base(mTy.indices[0]))
                equalInState(where, vTy, Base(mTy.result))
                mTy
            }

            else -> {
                logger.trace { "Not handling form ${e}@${where}" }
                e.getFreeVars().forEach {
                    equalInState(where, PartitionedType.top(it.tag), ctx[it])
                }
                PartitionedType.top(e.tag)
            }
        }
    }

    private fun CmdPointer.stateAt(): IntervalState? {
        val loc = dsaRegions[this]?.first ?: this
        return intervals.inState(loc)
    }

    private fun CmdPointer.stateAfter(): IntervalState? {
        val loc = dsaRegions[this]?.second ?: this
        return intervals.outState(loc)
    }

    private fun equalInState(where: CmdPointer, t: PartitionedType, u: PartitionedType) {
        addConstraint(
            where,
            TypeConstraint(
                where.stateAt() ?: return,
                t,
                u
            )
        )
    }

    private fun assignConstraint(where: CmdPointer, destType: PartitionedType, fromType: PartitionedType) {
        // If either state is unreachable, then this constraint is not reachable
        addConstraint(
            where,
            TypeConstraint(
                where.stateAfter() ?: return,
                destType,
                where.stateAt() ?: return,
                fromType
                )
        )
    }

    private fun addConstraint(where: CmdPointer, c: TypeConstraint) {
        if (c.isNonTrivial) {
            constraints.getOrPut(where) { mutableSetOf() }.add(c)
        }
    }

    private val intervals = graph.cache[IntervalAnalysis]
    private val tags = graph.cache[ValTagAnalysis]

    private val def = NonTrivialDefAnalysis(graph)
    private val constAnalysis = MustBeConstantAnalysis(graph)

    private val gCtx: TypeContext
    private val partitions: Map<TACSymbol.Var, Pair<TACSymbol.Var, Set<IntValue>>>
    private val setTagMatcher = PatternMatcher.compilePattern(graph, setTagPattern)
    // l |-> (s, e) if l is in a dsa assignment region beginning at s and ending at e
    // since dsa assignments are 'in parallel', this effectively gives us the pre- and post-states
    // of *all* the assignments as a single 'atomic' block
    private val dsaRegions: Map<CmdPointer, Pair<CmdPointer, CmdPointer>>
    private val constraints: MutableMap<CmdPointer, MutableSet<TypeConstraint>> = mutableMapOf()
    private val solution: UnionFind<BaseType>?

    fun isSymmetricDef(def: LTACCmdView<TACCmd.Simple.AssigningCmd>): Boolean {
        if (solution == null) {
            return false
        }
        gCtx.g[def.cmd.lhs]?.let {
            if (it !is PartitionedType.TypesInState) {
                return false
            }
            return BaseType.Top !in it.types(def.ptr.stateAfter() ?: return true)
        }
        return false
    }

    // For this block, map all commands that are within DSA_BLOCK_START/END (inclusive)
    // to the pair of cmdpointers corresponding to the actual DSA_BLOCK_START/END annotations
    //
    // i.e., map each command to CmdPointers delimiting the DSA block
    private fun TACBlock.dsaRegions(): Map<CmdPointer, Pair<CmdPointer, CmdPointer>> {
        var cmds = commands
        val dsa = mutableMapOf<CmdPointer, Pair<CmdPointer, CmdPointer>>()

        while (cmds.isNotEmpty()) {
            cmds = cmds.dropWhile { it.maybeAnnotation(DSA_BLOCK_START) == null }
            val inBlockCmds = cmds.takeUntil { it.maybeAnnotation(DSA_BLOCK_END) != null } ?: break
            val first = inBlockCmds.firstOrNull()?.ptr ?: continue
            val last = inBlockCmds.lastOrNull()?.ptr ?: continue
            inBlockCmds.forEach { cmd ->
                dsa[cmd.ptr] = first to last
            }
            cmds = cmds.drop(inBlockCmds.size)
        }

        return dsa
    }

    init {
        val dsa = ConcurrentHashMap<CmdPointer, Pair<CmdPointer, CmdPointer>>()

        // Here we want to compute, for each `x` that is assigned within a dsa region,
        // for all such assignments at different locations in the program, is there
        // a _single_ `y` that is always assigned _some_ constant value whenever
        // `x` is assigned
        //
        // assignedConstants[x] = listOf(s1, s2, ..., sn)
        //   where each si denotes a set of constant assignments (var/bigint pairs) in scope at an assignment to x,
        //   (at n such assignments)
        //
        // if we had
        //   if * { y = 0; x =... } else if * { y = 1; z = 2; x = ... } else { y = 1; x = ... },
        // then
        //   assignedConstants[x] == listOf({(y, 0)}, {(y, 1), (z, 2)}, {(y, 1)})
        //
        // as a side effect, we also populate (in `dsaRegions`)
        val assignedConstants = graph.blocks.parallelStream().flatMap { block ->
            val blockDSARegions = block.dsaRegions()
            blockDSARegions.forEachEntry { (ptr, region) ->
                dsa[ptr] = region
            }

            val regions = blockDSARegions.values.toSet()
            // For each DSA region (i.e. list of parallel assignments)
            // find the assignments that are known to be constant
            regions.flatMapToSet { (start, end) ->
                // These are the assignments
                val assignments = graph.iterateBlock(start, end.pos).mapNotNull {
                    it.maybeNarrow<TACCmd.Simple.AssigningCmd.AssignExpCmd>()
                }
                // Constant assignments in this region
                val dsaBlockConstantAssignments = assignments.associateNotNull { assign ->
                   constAnalysis.mustBeConstantAt(end, assign.cmd.lhs).takeIf {
                       end != assign.ptr
                   }?.let {
                       assign.cmd.lhs to it
                   }
                }
                // For each x := e in the dsa block,
                // the "in-scope" constant assignments are all the other x' := e' where
                // e' must be constant, i.e. dsaBlockConstantAssignments - x.
                dsaBlockConstantAssignments.entries.let { xConsts ->
                    assignments.mapNotNullToSet { lcmd ->
                        val toTake = xConsts.filterToSet { it.key != lcmd.cmd.lhs }
                        lcmd.cmd.lhs to toTake
                    }
                }
            }.stream()
        }.collect(Collectors.toSet()).groupBy(keySelector = { it.first }, valueTransform = { it.second })
        dsaRegions = dsa

        partitions = assignedConstants.mapValuesNotNull { (_, constAssignments) ->
            // Whenever x is assigned (i.e. in dsa blocks before a join), is there a unique
            // y that is always assigned some constant (but may be a different constant at each assignment)?
            val uniqueVar = constAssignments
                .map { it.mapToSet { it.key } }
                .intersectAll()
                .singleOrNull()
                ?: return@mapValuesNotNull null

            // We've found the unique var, so we will gather all of its possible assignments
            // and partition the type of x accordingly: so if uniqueVar is possibly assigned 0 or 1,
            // we will have distinct types for x when uniqueVar is 0, when it is 1, and when it is in [2, inf]
            val ranges = constAssignments.map { it.single { it.key == uniqueVar }.value }.toSet().sorted()
            var curUB = BigInteger.ZERO
            val ps = mutableSetOf<IntValue>()
            for (r in ranges) {
                if (curUB < r) {
                    ps.add(IntValue(curUB, r - BigInteger.ONE))
                }
                ps.add(IntValue.Constant(r))
                curUB = r + BigInteger.ONE
            }
            ps.add(IntValue(curUB, IntValue.Nondet.ub))
            uniqueVar to ps
        }

        val typeEnv = symbolTable
            .tags
            .keys
            .associateWith { x ->
                when (val t = x.tag) {
                    is Tag.GhostMap, is Tag.ByteMap ->
                        freshPart(t)

                    else ->
                        partitions[x]?.let { (c, ivs) ->
                            val partitions = ivs.associate { iv ->
                                PartitionedType.Partition(iv) to freshBase()
                            }.toTreapMap<PartitionedType.Partition, BaseType>()
                            PartitionedType.Partitioned(c, partitions)
                        } ?: freshPart(x.tag)
                }
            }.toTreapMap().builder()

        val objectKeyMapType = PartitionedType.Map(BaseType.Sym, BaseType.Top)
        val symMappingType = PartitionedType.Map(listOf(BaseType.Sym, BaseType.Top), BaseType.Sym)
        val storageMappingType = PartitionedType.Map(listOf(BaseType.Top, BaseType.Top), BaseType.Sym)
        val storageRawMappingType = PartitionedType.Map(listOf(BaseType.Top, BaseType.Top), BaseType.Top)
        val rawMappingType = PartitionedType.Map(listOf(BaseType.Sym, BaseType.Top), BaseType.Top)
        val sizesType = PartitionedType.Map(listOf(BaseType.Sym), BaseType.Top)
        val intObjMappingType = PartitionedType.Map(BaseType.Sym, BaseType.Top)

        val sorobanEnvTyping = mapOf<TACKeyword, PartitionedType>(
            // Globals
            TACKeyword.SOROBAN_OBJECTS to objectKeyMapType,
            TACKeyword.SOROBAN_OBJECT_DIGEST to objectKeyMapType,
            TACKeyword.SOROBAN_VEC_SIZES to objectKeyMapType,
            // Vecs and maps hold Vals
            TACKeyword.SOROBAN_VEC_MAPPINGS to symMappingType,
            TACKeyword.SOROBAN_MAP_MAPPINGS to symMappingType,
            // Storage
            TACKeyword.SOROBAN_CONTRACT_DATA to storageMappingType,
            TACKeyword.SOROBAN_CONTRACT_DATA_KEY_DIGESTS to storageRawMappingType,
            // These hold raw data
            TACKeyword.SOROBAN_BYTES_MAPPINGS to rawMappingType,
            TACKeyword.SOROBAN_SYMBOL_MAPPINGS to rawMappingType,
            TACKeyword.SOROBAN_STRING_MAPPINGS to rawMappingType,
            TACKeyword.SOROBAN_MAP_KEY_DIGESTS to rawMappingType,
            // Sizes for mapping types
            TACKeyword.SOROBAN_BYTES_SIZES to sizesType,
            TACKeyword.SOROBAN_STRING_SIZES to sizesType,
            TACKeyword.SOROBAN_SYMBOL_SIZES to sizesType,
            TACKeyword.SOROBAN_VEC_SIZES to sizesType,
            TACKeyword.SOROBAN_MAP_SIZES to sizesType,
            // Map key management
            TACKeyword.SOROBAN_MAP_KEYS to PartitionedType.Map(listOf(BaseType.Sym, BaseType.Sym), BaseType.Top),
            TACKeyword.SOROBAN_MAP_KEY_TO_INDEX to PartitionedType.Map(listOf(BaseType.Sym, BaseType.Sym), BaseType.Top),
            TACKeyword.SOROBAN_MAP_INDEX_TO_KEY to PartitionedType.Map(listOf(BaseType.Sym, BaseType.Top), BaseType.Sym),
            TACKeyword.SOROBAN_U64_VALUES to intObjMappingType,
            TACKeyword.SOROBAN_I64_VALUES to intObjMappingType,
            TACKeyword.SOROBAN_U128_VALUES to intObjMappingType,
            TACKeyword.SOROBAN_I128_VALUES to  intObjMappingType,
            TACKeyword.SOROBAN_U256_VALUES to intObjMappingType,
            TACKeyword.SOROBAN_I256_VALUES to intObjMappingType,
            TACKeyword.SOROBAN_TIMEPOINT_VALUES to intObjMappingType,
            TACKeyword.SOROBAN_DURATION_VALUES to intObjMappingType,
        )

        sorobanEnvTyping.forEachEntry { (x, t) -> typeEnv[x.toVar()] = t }

        gCtx = TypeContext(typeEnv.build())

        graph.blocks.filter { it.id !in graph.cache.revertBlocks }.forEach {
            for (c in it.commands) {
                consGenStmt(gCtx, c)
            }
        }

        logger.info { "${constraints.size} generated constraints " }
        logger.trace { "Constraints:" }
        val m = constraints.entries
            .flatMap { (where, cs) -> cs.map { it to where } }
            .groupBy { it.first }
            .mapValues { (_, b) -> b.map { it.second }.toSet() }
        for ((c, locs) in m) {
            logger.trace { "${c.t} <= ${c.u} @ ${locs}" }
        }

        val simple = gatherSimpleConstraints()
        logger.info { "${simple.size} simplified constraints " }

        val result = solveConstraints(simple)

        when (result) {
            is Either.Left -> {
                val cmd = graph.elab(result.d.first.where)
                logger.warn {
                    "Scalar Inference: ${graph.name} - $cmd unifies 'Sym' and 'Top'"
                }
                val tyvars = mutableSetOf<BaseType.TyVar>()
                logger.debug {
                    "Constraint: ${result.d.first.t} === ${result.d.first.u}"
                }
                for (x in setOfNotNull(cmd.cmd.getLhs()) + cmd.cmd.getFreeVarsOfRhs()) {
                    val xty = gCtx[x]
                    logger.debug {
                        "$x :: ${gCtx[x]}"
                    }
                    tyvars.addAll(xty.baseVars)
                }
                for (t in tyvars) {
                    logger.debug {
                        "$t := ${result.d.second.find(t)}"
                    }
                }
                logger.debug { "Top := ${result.d.second.find(BaseType.Top)}"}
                logger.debug { "Sym := ${result.d.second.find(BaseType.Sym)}"}
                solution = null
            }

            is Either.Right -> {
                solution = result.d
            }
        }
    }
}

private val setTagPattern = PatternDSL.build {
    (((Var shl Const) mod (Const(BigInteger.TWO.pow(64)))) or Const).commute.withAction { (shiftVar, _), t ->
        shiftVar to t
    } `lor` ((Var shl Const) or Const).commute.withAction { shiftVar, t ->
        shiftVar to t
    } `lor` ((Var * PatternMatcher.Pattern.FromConstant.withPredicate { it.isPowOf2 }.toBuildable()).commute + Const).commute.withAction { (shiftVar, k), t ->
        (shiftVar to k.lowestSetBit.toBigInteger()) to t
    }
}
