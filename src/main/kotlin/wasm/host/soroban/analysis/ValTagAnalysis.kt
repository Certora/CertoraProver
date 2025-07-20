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

package wasm.host.soroban.analysis

import datastructures.stdcollections.*
import analysis.CmdPointer
import analysis.LTACCmd
import analysis.LTACCmdView
import analysis.TACCommandGraph
import analysis.dataflow.IMustEqualsAnalysis
import analysis.numeric.*
import analysis.worklist.StatefulWorklistIteration
import analysis.worklist.StepResult
import com.certora.collect.*
import tac.NBId
import utils.*
import vc.data.AnalysisCache
import vc.data.TACCmd
import vc.data.TACExpr
import vc.data.TACSummary
import vc.data.TACSymbol
import wasm.host.soroban.Val
import java.math.BigInteger
import kotlin.let

/**
 *  A lightweight relational analysis to track variables 'x' whose contents are the
 *  possible [Val.Tag]s of other variables 'y'.
 */
class ValTagAnalysis private constructor(val graph: TACCommandGraph) {
    companion object : AnalysisCache.Key<ValTagAnalysis> {
        override fun createCached(graph: TACCommandGraph) = ValTagAnalysis(graph)
    }

    /**
     * @return the set of variables `x` that are equal to the tag of [s]
     */
    fun eqTagsOf(where: CmdPointer, s: TACSymbol): Set<TACSymbol.Var>? =
        stateAt(where)?.keysMatching { _, v -> v.tagsOf.any { it.x == s } }?.toSet()

    /**
     * @return the set of possible tags of [s] at [where], or [null] if unknown
     */
    fun tagSet(where: CmdPointer, s: TACSymbol): Set<Val.Tag>? =
        stateAt(where)?.let { state ->
            // Return the intersection of all 'tag witnesses' for s
            val tagsOfS = state.filterValues { it.tagsOf.any { it.x == s } }.values

            tagsOfS
                .mapNotNull { it.x.tags }
                .takeIf { it.isNotEmpty() }
                ?.intersectAll()
        }

    private fun stateAt(where: CmdPointer): ValTagDomain? {
        if (where.block !in inState) {
            throw IllegalArgumentException("Block for $where not in state map")
        }
        var state = inState[where.block]!!
        val block = graph.elab(where.block)
        for (cmd in block.commands) {
            if (cmd.ptr == where) {
                return state
            }
            state = interpreter.step(cmd, state)
        }
        throw IllegalArgumentException("No in state for $where")
    }

    private val inState  = mutableMapOf<NBId, ValTagDomain>()
    private val interpreter = AbstractInterpreter(graph.cache.gvn)

    init {
        graph.rootBlocks.forEach {
            inState[it.id] = treapMapOf()
        }
        (object : StatefulWorklistIteration<NBId, Unit, Unit>() {
            override val scheduler = graph.cache.naturalBlockScheduler

            override fun process(it: NBId): StepResult<NBId, Unit, Unit> {
                return this.cont(iterBlock(it))
            }

            override fun reduce(results: List<Unit>) {}

        }).submit(graph.rootBlocks.map { it.id })
    }

    private fun iterBlock(blockId: NBId): Set<NBId> {
        var m = inState[blockId] ?: return emptySet()
        val block = graph.elab(blockId)

        for (command in block.commands) {
            m = interpreter.step(command, m)
        }

        val nxt = mutableSetOf<NBId>()
        for ((succ, pc) in graph.pathConditionsOf(blockId)) {
            val next = m
            val fst = graph.elab(succ).commands.first()
            val propagated = interpreter.propagate(fst, next, pc)

            if (propagated == null) {
                continue
            }

            if (succ !in inState) {
                inState[succ] = propagated
                nxt.add(succ)
            } else {
                val prev = inState[succ]!!
                val joined = prev.parallelMerge(propagated) { _, v1, v2 ->
                    if (v1 == null || v2 == null) {
                        ValTagValueDomain.nondet
                    } else {
                        v1.join(v2)
                    }
                }
                if (joined != prev) {
                    inState[succ] = joined
                    nxt.add(succ)
                }
            }
        }

        return nxt
    }

}

private typealias ValTagDomain = TreapMap<TACSymbol.Var, ValTagValueDomain>

/**
 * @param [x] a set of tags
 * @param [qual] relational qualifiers that either summarize a (path) condition or relate this value to some other
 *        variable's tags
 */
private data class ValTagValueDomain(
    override val x: TagSet,
    override val qual: TreapSet<ValTagQualifier>
): BoundedQualifiedInt<ValTagValueDomain, ValTagQualifier, TagSet>, WithUIntApproximation<TagSet> {
    val tagsOf: Set<ValTagQualifier.TagOf>
        get() = qual.filterIsInstance<ValTagQualifier.TagOf>()

    fun join(o: ValTagValueDomain) = ValTagValueDomain(
        x.join(o.x),
        qual.intersect(o.qual)
    )

    override fun withQualifiers(x: Iterable<ValTagQualifier>): ValTagValueDomain =
        ValTagValueDomain(this.x, x.toTreapSet())

    companion object {
        val nondet = ValTagValueDomain(TagSet.nondet, treapSetOf())
    }

    override fun withBoundAndQualifiers(
        lb: BigInteger,
        ub: BigInteger,
        quals: Iterable<ValTagQualifier>
    ): ValTagValueDomain {
        val tagSet = TagSet.lift(lb, ub)

        return ValTagValueDomain(tagSet, quals.toTreapSet())
    }
}


/**
 * This is the main value abstraction, which is essentially just
 * a may-equal set of constants
 */
private data class TagSet(val tags: Set<Val.Tag>?): UIntApprox<TagSet> {
    override fun getUpperBound(): BigInteger =
        tags?.maxByOrNull { it.value }?.value?.toBigInteger() ?: MAX_UINT

    override fun getLowerBound(): BigInteger =
        tags?.minByOrNull { it.value }?.value?.toBigInteger() ?: BigInteger.ZERO

    override fun shiftLeft(lb: BigInteger, ub: BigInteger): TagSet = nondet

    override val isConstant: Boolean
        get() = tags?.size == 1

    override val c: BigInteger
        get() = tags!!.single().value.toBigInteger()

    override fun shiftRight(lb: BigInteger, ub: BigInteger): TagSet = nondet

    override fun join(other: TagSet): TagSet {
        if (tags == null || other.tags == null) {
            return nondet
        }
        @Suppress("Treapability")
        return TagSet(tags + other.tags)
    }

    override fun widen(next: TagSet): TagSet = join(next)

    override fun mayOverlap(other: TagSet): Boolean {
        if (tags != null && other.tags != null) {
            @Suppress("Treapability")
            return tags.intersect(other.tags).isNotEmpty()
        }
        return true
    }

    override fun sub(other: TagSet): Pair<TagSet, Boolean> = nondet to true
    override fun add(other: TagSet): Pair<TagSet, Boolean> = nondet to true
    override fun mult(other: TagSet): Pair<TagSet, Boolean> = nondet to true

    companion object  {
        val nondet = TagSet(null)

        fun lift(lb: BigInteger, ub: BigInteger): TagSet {
            val minTag = lb.asTag
            val maxTag = ub.asTag
            if (minTag == null || maxTag == null) {
                return nondet
            }
            return TagSet(Val.Tag.entries.filter { it.value in (minTag.value .. maxTag.value) }.toSet())
        }
    }
}

private sealed class ValTagQualifier: SelfQualifier<ValTagQualifier> {
    /** if y : TagOf(x) then the value of y is the tag of x, i.e. the low 8 bits */
    data class TagOf(val x: TACSymbol.Var): ValTagQualifier() {
        override fun relates(v: TACSymbol.Var): Boolean = x == v

        override fun saturateWith(equivClasses: VariableSaturation): List<ValTagQualifier> = equivClasses(x).map { TagOf(it) }
    }

    data class Condition(val c: SimpleIntQualifier.Condition): ValTagQualifier(), ConditionQualifier by c {
        constructor (op1: TACSymbol, op2: TACSymbol, cond: ConditionQualifier.Condition):
            this(SimpleIntQualifier.Condition(op1,op2,cond))

        override fun relates(v: TACSymbol.Var): Boolean = c.relates(v)

        override fun saturateWith(equivClasses: VariableSaturation): List<ValTagQualifier> =
            c.saturateWith(equivClasses).map { Condition(it) }
    }

    data class LogicalConnective(val lc: SimpleIntQualifier.LogicalConnective): ValTagQualifier(), LogicalConnectiveQualifier by lc {
        constructor (connective: LogicalConnectiveQualifier.LBinOp, op1: TACSymbol.Var, op2: TACSymbol.Var):
            this(SimpleIntQualifier.LogicalConnective(connective, op1, op2))

        constructor (op1: TACSymbol.Var, op2: TACSymbol.Var, connective: LogicalConnectiveQualifier.LBinOp, applyNot: Boolean):
            this(SimpleIntQualifier.LogicalConnective(op1, op2,connective, applyNot))

        override fun relates(v: TACSymbol.Var): Boolean = lc.relates(v)

        override fun saturateWith(equivClasses: VariableSaturation): List<ValTagQualifier> =
            lc.saturateWith(equivClasses).map { LogicalConnective(it) }
    }
}

// We instantiate our abstract interpretation framework just so that we can get some reasoning 'for free',
// i.e. related to qualifier propagation at branches, etc.
//
// There is substantial boilerplate involved here: most of the 'interesting' logic is in the ValueSemantics.
private class AbstractInterpreter(val me: IMustEqualsAnalysis): AbstractAbstractInterpreter<ValTagDomain, ValTagDomain>() {

    private val qualifierManager = object : QualifierManager<ValTagQualifier, ValTagValueDomain, ValTagDomain>(me) {
        override fun mapValues(
            s: ValTagDomain,
            mapper: (TACSymbol.Var, ValTagValueDomain) -> ValTagValueDomain
        ): ValTagDomain {
            return s.mapValues { mapper(it.key, it.value)}
        }

        override fun assignVar(
            toStep: ValTagDomain,
            lhs: TACSymbol.Var,
            toWrite: ValTagValueDomain,
            where: LTACCmd
        ): ValTagDomain {
            if (toWrite == ValTagValueDomain.nondet) {
                return toStep - lhs
            }
            return toStep + (lhs to toWrite)
        }
    }

    private val qualifierPropagator =
        object : QualifierPropagationComputation<ValTagValueDomain, ValTagDomain, ValTagDomain, ValTagQualifier>() {
            override fun extractValue(
                op1: TACSymbol.Var,
                s: ValTagDomain,
                w: ValTagDomain,
                l: LTACCmd
            ): ValTagValueDomain? {
                return s[op1]
            }
        }

    override val pathSemantics = object : BoundedQIntPropagationSemantics<ValTagValueDomain, ValTagQualifier, ValTagDomain, ValTagDomain>(qualifierPropagator) {
        override fun assignVar(
            toStep: ValTagDomain,
            lhs: TACSymbol.Var,
            toWrite: ValTagValueDomain,
            where: LTACCmd
        ): ValTagDomain {
            return qualifierManager.assign(toStep, lhs, toWrite, where)
        }

        override fun propagateSummary(
            summary: TACSummary,
            s: ValTagDomain,
            w: ValTagDomain,
            l: LTACCmd
        ): ValTagDomain? {
            return s
        }
    }

    override fun project(l: LTACCmd, w: ValTagDomain): ValTagDomain = w

    override val statementInterpreter: IStatementInterpreter<ValTagDomain, ValTagDomain> = StatementInterpreter(qualifierManager)

    override fun postStep(stepped: ValTagDomain, l: LTACCmd): ValTagDomain {
        return stepped
    }

    override fun killLHS(
        lhs: TACSymbol.Var,
        s: ValTagDomain,
        w: ValTagDomain,
        narrow: LTACCmdView<TACCmd.Simple.AssigningCmd>
    ): ValTagDomain {
        return qualifierManager.killLHS(lhs = lhs, lhsVal = s[lhs], narrow = narrow, s = s)
    }
}


private class ValueSemantics: StatelessUIntValueSemantics<ValTagValueDomain, TagSet>() {
    override fun lift(lb: BigInteger, ub: BigInteger): TagSet = TagSet.lift(lb, ub)

    override fun lift(iVal: TagSet): ValTagValueDomain = ValTagValueDomain(iVal, treapSetOf())

    override val nondet: ValTagValueDomain
        get() = ValTagValueDomain.nondet

    override fun evalMod(
        v1: ValTagValueDomain,
        o1: TACSymbol,
        v2: ValTagValueDomain,
        o2: TACSymbol,
        toStep: Any,
        input: Any,
        whole: Any,
        l: LTACCmdView<TACCmd.Simple.AssigningCmd.AssignExpCmd>
    ): ValTagValueDomain {
        return super.evalMod(v1, o1, v2, o2, toStep, input, whole, l).let { av ->
            val q = treapSetBuilderOf<ValTagQualifier>()
            // If we have o1 % [Val.TAG_MUL], then the resulting value is exactly the tag of o1
            if (o2 is TACSymbol.Const && o2.value == Val.TAG_MUL.toBigInteger()) {
                (o1 as? TACSymbol.Var)?.let {
                    q.add(ValTagQualifier.TagOf(it))
                }
                q.addAll(v1.tagsOf)
            }
            av.copy(qual = av.qual + q)
        }
    }

    override fun evalBWAnd(
        v1: ValTagValueDomain,
        o1: TACSymbol,
        v2: ValTagValueDomain,
        o2: TACSymbol,
        toStep: Any,
        input: Any,
        whole: Any,
        l: LTACCmdView<TACCmd.Simple.AssigningCmd.AssignExpCmd>
    ): ValTagValueDomain {
        return super.evalBWAnd(v1, o1, v2, o2, toStep, input, whole, l).let { av ->
            val q = treapSetBuilderOf<ValTagQualifier>()
            // If we have [Val.TAG_MASK] & o2, then the resulting value is exactly the tag of o2
            if (o1 is TACSymbol.Const && o1.value == Val.TAG_MASK.toBigInteger()) {
                (o2 as? TACSymbol.Var)?.let {
                    q.add(ValTagQualifier.TagOf(it))
                }
                q.addAll(v2.tagsOf)
            }
            // If we have o1 & [Val.TAG_MASK], then the resulting value is exactly the tag of o1
            if (o2 is TACSymbol.Const && o2.value == Val.TAG_MASK.toBigInteger()) {
                (o1 as? TACSymbol.Var)?.let {
                    q.add(ValTagQualifier.TagOf(it))
                }
                q.addAll(v1.tagsOf)
            }
            av.copy(qual = av.qual + q)
        }
    }
}

private class ExpressionInterpreter(val qualifierManager: QualifierManager<ValTagQualifier, ValTagValueDomain, ValTagDomain>): NonRelationalExpressionInterpreter<ValTagDomain, ValTagValueDomain, ValTagDomain>() {
    override val nondet: ValTagValueDomain
        get() = ValTagValueDomain.nondet

    override fun forget(
        lhs: TACSymbol.Var,
        toStep: ValTagDomain,
        input: ValTagDomain,
        whole: ValTagDomain,
        wrapped: LTACCmd
    ): ValTagDomain {
        return toStep - lhs
    }

    override val valueSemantics = ValueSemantics()
        .withPathConditions(
            condition = ValTagQualifier::Condition,
            conjunction = ValTagQualifier::LogicalConnective,
            flip = {
                when (it) {
                    is ValTagQualifier.Condition -> ConditionQualifier.flip(it, ValTagQualifier::Condition)
                    is ValTagQualifier.LogicalConnective -> LogicalConnectiveQualifier.flip(it, ValTagQualifier::LogicalConnective)
                    is ValTagQualifier.TagOf -> null
                }
            }
        )

    override fun liftConstant(value: BigInteger): ValTagValueDomain =
        value.toIntOrNull()?.asTag?.let { tag ->
            ValTagValueDomain(TagSet(setOf(tag)), treapSetOf())
        } ?: ValTagValueDomain.nondet

    override fun interp(
        o1: TACSymbol,
        toStep: ValTagDomain,
        input: ValTagDomain,
        whole: ValTagDomain,
        l: LTACCmdView<TACCmd.Simple.AssigningCmd.AssignExpCmd>
    ): ValTagValueDomain =
        when (o1) {
            is TACSymbol.Const -> liftConstant(o1.value)
            is TACSymbol.Var -> toStep[o1]
        } ?: nondet

    override fun assign(
        toStep: ValTagDomain,
        lhs: TACSymbol.Var,
        newValue: ValTagValueDomain,
        input: ValTagDomain,
        whole: ValTagDomain,
        wrapped: LTACCmd
    ): ValTagDomain {
        return qualifierManager.assign(toStep, lhs, newValue, wrapped)
    }
}

private class StatementInterpreter(qualifierManager: QualifierManager<ValTagQualifier, ValTagValueDomain, ValTagDomain>): AbstractStatementInterpreter<ValTagDomain, ValTagDomain>() {
    private val exprInterp = ExpressionInterpreter(qualifierManager)

    override fun forget(
        lhs: TACSymbol.Var,
        toStep: ValTagDomain,
        input: ValTagDomain,
        whole: ValTagDomain,
        l: LTACCmd
    ): ValTagDomain {
        return toStep - lhs
    }

    override fun stepExpression(
        lhs: TACSymbol.Var,
        rhs: TACExpr,
        toStep: ValTagDomain,
        input: ValTagDomain,
        whole: ValTagDomain,
        l: LTACCmdView<TACCmd.Simple.AssigningCmd.AssignExpCmd>
    ): ValTagDomain {
        return exprInterp.stepExpression(lhs, rhs, toStep, input, whole, l)
    }
}

private val Int.asTag: Val.Tag?
    get() = Val.Tag(this)

private val BigInteger.asTag: Val.Tag?
    get() = this.toIntOrNull()?.let { Val.Tag(it) }
