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

@file:UseSerializers(BigIntegerSerializer::class)
package analysis.alloc

import algorithms.dominates
import analysis.*
import analysis.alloc.AllocationAnalysis.roundUp
import analysis.numeric.*
import com.certora.collect.*
import config.Config
import datastructures.stdcollections.*
import evm.EVM_WORD_SIZE
import kotlinx.serialization.UseSerializers
import log.*
import normalizer.OptimisticSpillRewriter
import tac.MetaKey
import tac.NBId
import tac.Tag
import utils.*
import vc.data.*
import java.math.BigInteger

private val logger = Logger(LoggerTypes.PER_FUNCTION_SIMPLIFICATION)

/**
 * Class to analyze (and instrument) the myriad ways that solidity manages the data returned from an external call.
 *
 * For statically sized return sizes, solidity generates (roughly):
 * ```
 * returndatasize = havoc
 * rc = call outSize = k, outOffset = fp
 * if rc != 0 {
 *    if returndatasize < k {
 *       len = returndatasize
 *    } else {
 *       len = k
 *    }
 *    retBuffer = fp
 *    fp = retBuffer + roundup(len)
 *    if len < k {
 *       revert()
 *    }
 * }
 * ```
 *
 * Note that this is the happy path, when the call is known to not pass a value. If it does, then before the `if rc != 0` we instead have:
 * ```
 * if balance[to] + value > MAX_UINT || value > balance[this] {
 *    rc = 0
 *    returndatasize = 0
 * } else {
 *    returndatasize = havoc
 *    transfer(value, to, from)
 *    rc = call outSize = k, outOffset = fp
 *    if rc == 0 {
 *       transfer(value, from, to)
 *    }
 * }
 * if rc != 0 { ibid }
 * ```
 *
 * In this latter case especially, correlating the return buffer being accessed to the definite proceeding call is difficult.
 *
 * However, this is just one possible pattern. Solidity may also do this:
 * ```
 * ... as before ...
 * if rc != 0 {
 *    retBuffer = fp
 *    fp = retBuffer + roundUp(returndatasize)
 *    if returndatasize < k { revert() }
 * }
 * ```
 *
 * Note that even if we recognized this allocations as statically sized blocks, we don't actually see an initialization:
 * we are relying on the call command's implicit copying of returndata.
 *
 * But this is only for statically sized outputs. For dynamically sized outputs, we have:
 *
 * ```
 * rc = call outSize = 0, outOffset = fp
 * if rc != 0 {
 *    sz = returndatasize
 *    buffStart = fp
 *    mem[buffStart:sz] = returndata[0:sz]
 *    fp = buffStart + roundup(sz)
 *    // use buffStart as if it was the start of the element segment of an array with length sz
 * }
 * ```
 *
 * In this case, we see the explicit copy, but the object shape we are allocating isn't something we support, there is no length
 * field.
 *
 * The solution is to use a *very* specialized static analysis to recognize these allocations, and then instrument the allocations
 * as appropriate. In the dynamic case, we make the allocation look like a "real" array:
 * ```
 * dummyStart = fp // inserted
 * buffStart = dummyStart + 0x20 // modified
 * mem[dummyStart] = sz // inserted
 * mem[buffStart:sz] = returndata[0:sz]
 * fp = buffStart + roundup(sz)
 * ```
 *
 * After this instrumentation, it looks like a real allocation properly intialized.
 *
 * For the constant block case we remove any dynamic sizes from the allocation, and rewrite it to be
 * ```
 * retBuffer = fp
 * mem[retBuffer:k] = returndata[0:k]
 * fp = retBuffer + k
 * ```
 *
 * We've now made the allocation look like a constant sized block, and we've added the explicit initialization thereof.
 *
 * The details of the analysis are explained within the class.
 */
object ReturnBufferAnalysis {
    /**
     * Marker class, indicating that the allocation of a buffer of static size [allocSize] pointed
     * to by [outputVar] which holds the result of the most recent call has just completed. Consumed by the
     * [analysis.icfg.CallGraphBuilder] to signal that it should populate the abstract state with the return linking
     * information.
     */
    @Treapable
    @KSerializable
    data class ConstantReturnBufferAllocComplete(
        val outputVar: TACSymbol.Var,
        val allocSize: BigInteger
    ) : AmbiSerializable, TransformableVarEntityWithSupport<ConstantReturnBufferAllocComplete> {
        override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var): ConstantReturnBufferAllocComplete {
            return ConstantReturnBufferAllocComplete(f(outputVar), allocSize)
        }

        override val support: Set<TACSymbol.Var>
            get() = setOf(outputVar)

        companion object {
            val META_KEY = MetaKey<ConstantReturnBufferAllocComplete>("return.alloc.marker")
        }
    }

    /**
     * Simple set of qualifiers used in the analysis. Aside from the "standard" [Condition], and [Connective],
     * the only other qualifier is the [ReturnSizeOrConstantLowerBound], which captures a disjunctive fact, that a variable
     * is either an alias of [TACKeyword.RETURN_SIZE] *or* the constant [ReturnSizeOrConstantLowerBound.k].
     */
    sealed interface AnalysisQual : Qualifier<AnalysisQual> {
        data class Condition(
            override val op1: TACSymbol,
            override val op2: TACSymbol,
            override val condition: ConditionQualifier.Condition,
        ) : ConditionQualifier, AnalysisQual {
            override fun relates(v: TACSymbol.Var): Boolean {
                return v == op1 || v == op2
            }

            override fun saturateWith(equivClasses: VariableSaturation): List<AnalysisQual> {
                val s2Class = equivClasses(op2)
                return equivClasses(op1).flatMap { s1 ->
                    s2Class.map { s2 ->
                        Condition(s1, s2, condition)
                    }
                }
            }
        }

        data class Connective(
            override val op1: TACSymbol.Var,
            override val op2: TACSymbol.Var,
            override val connective: LogicalConnectiveQualifier.LBinOp,
            override val applyNot: Boolean
        ) : AnalysisQual, LogicalConnectiveQualifier {
            constructor(connective: LogicalConnectiveQualifier.LBinOp, op1: TACSymbol.Var, op2: TACSymbol.Var) : this(
                op1, op2, connective, false
            )

            override fun relates(v: TACSymbol.Var): Boolean {
                return op1 == v || op2 == v
            }

            override fun saturateWith(equivClasses: VariableSaturation): List<AnalysisQual> {
                val s2Class = equivClasses(op2)
                return equivClasses(op1).flatMap { s1 ->
                    s2Class.map { s2 ->
                        Connective(s1, s2, connective, applyNot)
                    }
                }
            }
        }

        data class MustEqual(val other: TACSymbol.Var) : AnalysisQual {
            override fun relates(v: TACSymbol.Var): Boolean {
                return v == other
            }

            override fun saturateWith(equivClasses: VariableSaturation): List<AnalysisQual> {
                return equivClasses(other).map(::MustEqual)
            }
        }

        data class ReturnSizeOrConstantLowerBound(val k: BigInteger) : AnalysisQual {
            override fun relates(v: TACSymbol.Var): Boolean {
                return false
            }

            override fun saturateWith(equivClasses: VariableSaturation): List<AnalysisQual> {
                return listOf(this)
            }
        }
    }

    /**
     * [BoundedQualifiedInt] instantiated with the [AnalysisQual] qualifiers.
     */
    data class QInt(override val x: IntValue, override val qual: TreapSet<AnalysisQual>) : BoundedQualifiedInt<QInt, AnalysisQual, IntValue> {
        override fun withQualifiers(x: Iterable<AnalysisQual>): QInt {
            return this.copy(qual = x.toTreapSet())
        }

        override fun withBoundAndQualifiers(lb: BigInteger, ub: BigInteger, quals: Iterable<AnalysisQual>): QInt {
            return QInt(x.withUpperBound(ub).withLowerBound(lb), quals.toTreapSet())
        }

        companion object {
            val nondet: QInt = QInt(IntValue.Nondet, treapSetOf())
        }
    }

    /**
     * Used to track the provenance of aliases of the free pointer. [Extant] means that the alias was created
     * before tracking began, [Copy] means a copy of an existing alias, or [Explicit] means that the free pointer
     * value was explicitly read at [Explicit.where].
     */
    private sealed interface AliasDefinition {
        object Extant : AliasDefinition
        data class Explicit(val where: CmdPointer) : AliasDefinition
        object Copy : AliasDefinition
    }

    /**
     * Abstract state used in the analysis [intState] models the value of integer variables using [QInt].
     * [fpAliases] tracks known, live aliases of the free pointer that exist after the call and which
     * are used along the allocation path. [returnBufferState] tracks the state of the call, starting from
     * the returndatasize initialization through to the fp write. It is a transition system which describes the
     * necessary order of steps the program is expected to go through for us to conclude we are looking at a
     * return buffer allocation. See [ReturnBufferState] for a more rigorous description of this transition system.
     */
    private data class State(
        val intState: TreapMap<TACSymbol.Var, QInt>,
        val fpAliases: TreapMap<TACSymbol.Var, AliasDefinition>,
        val returnBufferState: ReturnBufferState
    )

    /**
     * Value semantics for [QInt] with the path condition mixin.
     */
    private val qValueSemantics = object : IntValueSemantics<QInt, IntValue, TreapMap<TACSymbol.Var, QInt>, State>() {
        override fun lift(lb: BigInteger, ub: BigInteger): IntValue {
            return IntValue(lb, ub)
        }

        override fun lift(iVal: IntValue): QInt {
            return QInt(iVal, treapSetOf())
        }

        override val nondet: QInt
            get() = QInt.nondet

    }.withPathConditions(AnalysisQual::Condition, AnalysisQual::Connective) {
        when(it) {
            is AnalysisQual.Condition -> ConditionQualifier.flip(it, AnalysisQual::Condition)
            is AnalysisQual.Connective -> LogicalConnectiveQualifier.flip(it, AnalysisQual::Connective)
            is AnalysisQual.MustEqual,
            is AnalysisQual.ReturnSizeOrConstantLowerBound -> null
        }
    }

    /**
     * Expression interpreter using the value semantics of [QInt] defined above. The valuation of variables is
     * given by consulting the [State.intState] map embedded within [State]. The only notable extension is the
     * overriding of assign var to track value aliases.
     */
    private val expressionInterpeter = object : NonRelationalExpressionInterpreter<TreapMap<TACSymbol.Var, QInt>, QInt, State>() {
        override val nondet: QInt
            get() = QInt.nondet
        override val valueSemantics: IValueSemantics<TreapMap<TACSymbol.Var, QInt>, QInt, State>
            get() = qValueSemantics

        override fun liftConstant(value: BigInteger): QInt {
            return QInt(IntValue.Constant(value), treapSetOf())
        }

        override fun stepAssignVar(
            lhs: TACSymbol.Var,
            s: TACSymbol.Var,
            toStep: TreapMap<TACSymbol.Var, QInt>,
            input: TreapMap<TACSymbol.Var, QInt>,
            whole: State,
            l: LTACCmdView<TACCmd.Simple.AssigningCmd.AssignExpCmd>
        ): TreapMap<TACSymbol.Var, QInt> {
            val src = toStep[s] ?: QInt.nondet
            return this.assign(toStep, lhs, src.withQualifiers(src.qual + AnalysisQual.MustEqual(s)), input, whole, l.wrapped)
        }

        override fun interp(
            o1: TACSymbol,
            toStep: TreapMap<TACSymbol.Var, QInt>,
            input: TreapMap<TACSymbol.Var, QInt>,
            whole: State,
            l: LTACCmdView<TACCmd.Simple.AssigningCmd.AssignExpCmd>
        ): QInt {
            return when(o1) {
                is TACSymbol.Const -> liftConstant(o1.value)
                is TACSymbol.Var -> toStep[o1] ?: QInt.nondet
            }
        }

        override fun assign(
            toStep: TreapMap<TACSymbol.Var, QInt>,
            lhs: TACSymbol.Var,
            newValue: QInt,
            input: TreapMap<TACSymbol.Var, QInt>,
            whole: State,
            wrapped: LTACCmd
        ): TreapMap<TACSymbol.Var, QInt> {
            return toStep + (lhs to newValue)
        }

    }

    /**
     * statement semantics, using the [expressionInterpeter].
     */
    private val statementSemantics = object : SimpleStatementInterpreter<TreapMap<TACSymbol.Var, QInt>, State>(expressionInterpeter) {
        override fun forget(
            lhs: TACSymbol.Var,
            toStep: TreapMap<TACSymbol.Var, QInt>,
            input: TreapMap<TACSymbol.Var, QInt>,
            whole: State,
            l: LTACCmd
        ): TreapMap<TACSymbol.Var, QInt> {
            return toStep - lhs
        }
    }

    /**
     * Instantiation of the standard [QualifierPropagationComputation] for [QInt]/[AnalysisQual]
     */
    private val propagationComp = object : QualifierPropagationComputation<QInt, TreapMap<TACSymbol.Var, QInt>, State, AnalysisQual>() {
        override fun extractValue(op1: TACSymbol.Var, s: TreapMap<TACSymbol.Var, QInt>, w: State, l: LTACCmd): QInt? {
            return s[op1]
        }
    }

    /**
     * [IPathSemantics] for the abstract state, using [propagationComp] above for the actual propagation logic.
     */
    private val propagationSemantics = object : BoundedQIntPropagationSemantics<QInt, AnalysisQual, TreapMap<TACSymbol.Var, QInt>, State>(propagationComp) {
        override fun assignVar(
            toStep: TreapMap<TACSymbol.Var, QInt>,
            lhs: TACSymbol.Var,
            toWrite: QInt,
            where: LTACCmd
        ): TreapMap<TACSymbol.Var, QInt> {
            return toStep + (lhs to toWrite)
        }

        override fun propagateSummary(
            summary: TACSummary,
            s: TreapMap<TACSymbol.Var, QInt>,
            w: State,
            l: LTACCmd
        ): TreapMap<TACSymbol.Var, QInt> {
            return s
        }
    }

    /**
     * And finally, and abstract interpreter over the integer state in [State.intState] using the components above.
     */
    private val absInt = object : AbstractAbstractInterpreter<State, TreapMap<TACSymbol.Var, QInt>>() {
        override val statementInterpreter: IStatementInterpreter<TreapMap<TACSymbol.Var, QInt>, State>
            get() = statementSemantics
        override val pathSemantics: IPathSemantics<TreapMap<TACSymbol.Var, QInt>, State>
            get() = propagationSemantics

        override fun project(l: LTACCmd, w: State): TreapMap<TACSymbol.Var, QInt> {
            return w.intState
        }

        override fun postStep(stepped: TreapMap<TACSymbol.Var, QInt>, l: LTACCmd): TreapMap<TACSymbol.Var, QInt> {
            return stepped
        }

        override fun killLHS(
            lhs: TACSymbol.Var,
            s: TreapMap<TACSymbol.Var, QInt>,
            w: State,
            narrow: LTACCmdView<TACCmd.Simple.AssigningCmd>
        ): TreapMap<TACSymbol.Var, QInt> {
            return s.mapValues { (_, q) ->
                if(q.qual.isEmpty()) {
                    q
                } else {
                    val f = q.qual.retainAll {
                        !it.relates(lhs)
                    }
                    if(f === q.qual) {
                        q
                    } else {
                        q.copy(qual = f)
                    }
                }
            }
        }
    }

    private val returnSizeEqualQual = AnalysisQual.MustEqual(TACKeyword.RETURN_SIZE.toVar())
    private val returnCodeEqualQual = AnalysisQual.MustEqual(TACKeyword.RETURNCODE.toVar())

    private sealed interface StepResult {
        /**
         * Indicates the analysis should halt, this path of execution was judged uninteresting
         */
        object Halt : StepResult

        /**
         * Indicates that the analysis should halt, but we collected enough information in [st] to try to
         * instrument the return buffer.
         */
        data class Completed(val st: State) : StepResult

        /**
         * Indicates that the analysis should continue.
         */
        data class Continue(val st: State) : StepResult
    }

    /**
     * A representation of the lifecycle of a returnbuffer allocation. Each subtype of this
     * class represents a state of the allocation, the `transition*` methods
     * are called when events happen during the traversal of the program starting from the call.
     * In response to these events the state may transition to the error state (represented by the [Either.Right]
     * component), to another state, or to the same state. The whole process starts in the [ExpectReturnSize],
     * and by the end of the allocation, we expect to be in a "sufficient" state (see below).
     * All *potentially* sufficient states are subtypes of [PotentiallyCompleteState] and for which
     * we have at least seen an FP write that occurs after a call; this is precisely the predicate that the [isSufficientTrace]
     * function checks.
     *
      A "sufficient state" is not to be confused with a [HaltingState].
     * A [HaltingState] is a state in which the analysis cannot (or should not) proceed but which do not (necessarily)
     * indicate an analysis failure. These can be reached via two means:
     * 1. The analysis detected that is on an uninteresting path (e.g., we have entered a branch where the RC == 0), (this is the [Halt] state) or
     * 2. We have seen a write of the FP, but have reached a command with an "illegal" operation that prevent us continuing (the [ProcessingComplete] state)
     *
     * A good question is why we need to ever continue after seeing the FP write at all, that is, why don't
     * we immediately transition to a successful [HaltingState] once we see an FP write? As described in the
     * documentation for the [ReturnBufferAnalysis] class itself, the allocation sometimes isn't known to be a
     * return buffer allocation until we reach the end of the block after the fp write and check its
     * path conditions. However, to insure that the eventual transformation we do is sound, we need some strict guarantees about
     * what happens between the FP write and the end of the block: namely we don't kill any aliases
     * of the FP, we don't read memory etc. Note that these checks are precisely what we check *before* the FP write.
     * So it is convenient to just continue the analysis until the end of the block after the FP write if possible.
     * Then, if the analysis can reach the end of the block in this way, it is safe to consider its path condition to
     * judge whether something is a return allocation.
     *
     * Note that in principle we *could* have always stopped the analysis at the FP write, and then spawned an adhoc
     * analysis if necessary to see if we can safely reach the end of the block, but this was judged to be,
     * in technical parlance, an enormous pain in the ass.
     *
     * To ground this conversation, let's look at two examples. In the following snippets, we're assumed to be in some
     * rc != 0 branch following a call with static outsize k, and p is an alias of the fp at the call.
     *
     * In the first case, we have
     * ```
     * if returndatasize() < k { revert }
     * fp = p + k
     * use(mem[p])
     * ```
     *
     * After the FP write we immediately see a use of memory, which means that our analysis has to judge whether we have a
     * return allocation based on what it has seen up until the FP write; if we use information that comes after the program
     * has already started using memory, we potentially make unsound changes.
     *
     * Compare this with the following:
     * ```
     * w = returndatsize()
     * fp = p + w
     * if w < 0x40 { revert }
     * ```
     *
     * For us to conclude that we are (probably) allocating static array to hold the returndata, we need to reach the end of the
     * block and consult the path condition, but we need a little bit of slack between when the allocation is complete
     * and it starts being accessed in order to be confident in our findings.
     *
     * A quick aside: even with this song and dance around forbidding memory accesses between the FP write and
     * the end of the block, we can still have an unsound rewrite, consider:
     *
     * ```
     * w = returndatasize()
     * fp = p + w
     * if w < 0x40 { revert }
     * mem[fp] = ...;
     * use(mem[p+0x40])
     * ```
     *
     * Can the write at the new value of the `fp` be observed by `mem[p+0x40]`? In the original code, it depends on
     * the returndatasize. However, after our rewrite triggers, we'll have:
     *
     * ```
     * w = returndatasize()
     * if w < 0x40 { revert }
     * mem[p:0x40] = returndata[0:0x40]
     * fp = p + 0x40
     * mem[fp] = ...;
     * use(mem[p + 0x40])
     * ```
     *
     * Now the read definitely interferes with the write, even if the pointer analysis (correctly) fails. However, this
     * scenario is so absolutely cursed, this rewrite doesn't try to account for this use case.
     *
     * The transitions of the system are as follows:
     * * [ExpectReturnSize] => [transitionReturnHavoc] => [ExpectCallCore]
     *   Represents `returndatasize = *` encountered before the callcore. After this, the next event we see must
     *   be the callcore command, as which is what [ExpectCallCore] represents.
     * * [ExpectReturnSize] => [transitionReturnZero] => [ExpectCallSummary]
     *   Represents the `returndatasize = 0` which is added if the balance transfer fails. Note that along this path,
     *   we do not expect to see a callcore, but the decompiler/simplifier *does* still insert the [vc.data.TACCmd.EVM.CallOpcodeSummary],
     *   which we do expect to see.
     * * [ExpectCallCore] => [transitionCallCore] => [ExpectCallSummaryPostCallCore] with [ExpectCallSummaryPostCallCore.k]
     *   Unsurprisingly, when we expect to see a call core and then see a call core, we transition to another state
     *   [ExpectCallSummaryPostCallCore]; which means we expect to see the [vc.data.TACCmd.EVM.CallOpcodeSummary] inserted by
     *   the decompiler after this [vc.data.TACCmd.Simple.CallCore]. Note that this state is *different* from [ExpectCallSummary];
     *   that state is only reached if we do not expect to see a call core at all. The parameter `k` is the static return size declared
     *   for the output buffer. If we cannot infer this constant value, we instead transition to the error state.
     * * [ExpectCallSummary] => [transitionCallSummary] => [ExpectCallClose] with [ExpectCallClose.declaredOutSize]
     *   If we are expecting a call summary and encounter one, transition to [ExpectCallClose], which indicates
     *   the next event is encountering the [TACMeta.CALL_GROUP_END] annotation. Like the case above, we extract
     *   the constant declared outsize (which is available in the [vc.data.TACCmd.EVM.CallOpcodeSummary]).
     * * [ExpectCallSummaryPostCallCore] => [transitionCallSummary] => [ExpectCallClose] with [ExpectCallClose.declaredOutSize] = [ExpectCallSummaryPostCallCore.k]
     *   Expecting to see a call summary, we simply transition to the [ExpectCallClose] class, passing along the previously
     *   inferred static outsize [ExpectCallClose.declaredOutSize].
     * * [ExpectCallClose] => [transitionCallClose] => [ExpectRCBound] with [ExpectRCBound.outSize] = [ExpectCallClose.declaredOutSize]
     *   After encountering the (expected) [TACMeta.CALL_GROUP_END] annotation, transition to a state where we expect
     *   to enter a branch where [TACKeyword.RETURNCODE] is non-zero, i.e., the called code did not revert. We plumb through the [ExpectCallClose.declaredOutSize].
     * * [ExpectRCBound] => [transitionRCDefinitelyNonZero] => [SuccessPath], with [SuccessPath.successPathStart] == `p`, [SuccessPath.declaredOutSize] == [ExpectRCBound.outSize]
     *   Indicates that we have entered a path where the [TACKeyword.RETURNCODE] is non-zero, and thus we now expect
     *   to see an allocation (update of the FP) of the data returned from this successful call. p is the command pointer at which
     *   we see the bound on [TACKeyword.RETURNCODE].
     * * [SuccessPath] => [transitionFPWrite] => [SuccessPath]
     *   Indicates that we have now seen an FP update, and the allocation is complete. If we already saw an FP write (as
     *   recorded in [SuccessPath.seenFPWrite] != null, we instead transition an error state.
     * * [SuccessPath] => [transitionReturnDataCopy] => [SuccessPath]
     *   Indicates that we've seen an explicit data copy before the allocation, which happens for dynamic allocations (hence
     *   the condition on the declared outsize being 0). The current pointer `loc`, is recorded along side the existing
     *   results in [SuccessPath]. If there has already been a return data copy, we instead transition to an error.
     * * [ExpectRCBound] => [transitionRCDefinitelyNonZero] => [Halt]
     *   Indicates that we have started down the path for when the callee reverted, and we should stop
     * * [ExpectRCBound] => [transitionRCMaybeZero] => [ExpectRCBound]
     *   Indicates no change to the status of the RC.
     * * [SuccessPath] => [transitionRCMaybeZero] => \bot
     * * [SuccessPath] => [transitionRCDefinitelyZero] => \bot
     *   Indicates an unexpected departure from the code path where the call is known to succeed. This is treated as an error
     * * all others => [transitionRCDefinitelyZero] => same
     * * all others => [transitionRCDefinitelyNonZero] => same
     * * all others => [transitionRCMaybeZero] => same
     *   In any other state, changes in the state of the RC do not matter.
     * * all others => any other transition => \bot
     *
     * Any other transition in any state is an error
     */
    private sealed interface ReturnBufferState {
        object ExpectReturnSize : ReturnBufferState {
            override fun transitionReturnHavoc(): Either<ReturnBufferState, String> {
                return ExpectCallCore.toLeft()
            }

            override fun transitionReturnZero(): Either<ReturnBufferState, String> {
                return ExpectCallSummary.toLeft()
            }
        }

        object ExpectCallCore : ReturnBufferState {
            override fun transitionCallCore(
                c: TACCmd.Simple.CallCore,
                outSize: BigInteger
            ): Either<ReturnBufferState, String> {
                return ExpectCallSummaryPostCallCore(outSize).toLeft()
            }
        }

        /**
         * Holder state for when we are in a success path. [seenFPWrite] records where we saw an FP write
         * (which does not halt the analysis as described above). [copyLocation] is the location of the returndatacopy
         * seen along the success path (if nay). [successPathStart] indicates the [CmdPointer] at which the RC was
         * shown to be non-zero. Finally, [declaredOutSize] is the (potentially 0) static outsize declared at the callcore
         * being analyzed.
         */
        data class SuccessPath(
            override val copyLocation: CmdPointer?,
            override val seenFPWrite: CmdPointer?,
            override val successPathStart: CmdPointer,
            override val declaredOutSize: BigInteger,
            override val dominanceBoundary: Pair<NBId, NBId>?
        ) : RecoverableState, PotentiallyCompleteState {
            override fun transitionRCDefinitelyZero(p: CmdPointer): Either<ReturnBufferState, String> {
                return "Transitioned from success $successPathStart to fail state $p".toRight()
            }

            override fun transitionRCMaybeZero(p: CmdPointer): Either<ReturnBufferState, String> {
                return "Transitioned from success $successPathStart to non-success at $p".toRight()
            }

            override fun trackFPAliases(): Boolean {
                return seenFPWrite == null
            }

            override fun completeIfPossible(errString: String): Either<HaltingState, String> {
                if(!isSufficientTrace()) {
                    return errString.toRight()
                }
                return ProcessingComplete(
                    declaredOutSize = declaredOutSize,
                    seenFPWrite = seenFPWrite ?: return "supposedly sufficient trace, but didn't even have fp write".toRight(),
                    successPathStart = successPathStart,
                    copyLocation = copyLocation,
                    dominanceBoundary = dominanceBoundary
                ).toLeft()
            }

            override fun transitionDominanceLost(from: NBId, to: NBId): Either<ReturnBufferState, String> {
                // just keep track of the first time we lose our dominance relation. Later logic will attempt to
                // work around that
                if(dominanceBoundary != null) {
                    return this.toLeft()
                }
                return this.copy(
                    dominanceBoundary = from to to
                ).toLeft()
            }

            override fun transitionFPWrite(where: CmdPointer): Either<ReturnBufferState, String> {
                if(seenFPWrite != null) {
                    return "Already seen FP write at $seenFPWrite".toRight()
                }
                return this.copy(seenFPWrite = where).toLeft()
            }

            override fun transitionReturnDataCopy(p: CmdPointer): Either<ReturnBufferState, String> {
                if(declaredOutSize != BigInteger.ZERO) {
                    return "Seen explicit returncopy at $p but have non-zero declared outsize $declaredOutSize".toRight()
                }
                if(copyLocation != null) {
                    return "At $p, already seen return copy at $copyLocation".toRight()
                }
                return this.copy(
                    copyLocation = p
                ).toLeft()
            }

            override fun transitionRCDefinitelyNonZero(p: CmdPointer): Either<ReturnBufferState, String> {
                return this.toLeft()
            }

            override fun isSufficientTrace() = seenFPWrite != null
        }

        fun isSufficientTrace() = false

        /**
         * We are in a state where we exepct to see a [vc.data.TACCmd.EVM.CallOpcodeSummary] (but no callcore).
         */
        object ExpectCallSummary : ReturnBufferState {
            override fun transitionCallSummary(
                c: TACCmd.EVM.CallOpcodeSummary,
                outSize: BigInteger,
                st: State
            ): Either<ReturnBufferState, String> {
                if(st.intState[TACKeyword.RETURNCODE.toVar()]?.x?.mustEqual(BigInteger.ZERO) != true) {
                    return "RC is not known to be zero at call summary".toRight()
                }
                return ExpectCallClose(outSize).toLeft()
            }
        }

        /**
         * Indicates whether we are in a state where the analysis should try to track fp aliases (using
         * the [State.fpAliases] component).
         */
        fun trackFPAliases(): Boolean = false

        /**
         * Indicates that we have seen a callcore with static outsize [k], and now expect
         * to see a [vc.data.TACCmd.EVM.CallOpcodeSummary] with the same outsize.
         */
        data class ExpectCallSummaryPostCallCore(val k: BigInteger) : ReturnBufferState {
            override fun transitionCallSummary(
                c: TACCmd.EVM.CallOpcodeSummary,
                outSize: BigInteger,
                st: State
            ): Either<ReturnBufferState, String> {
                if(outSize != k) {
                    return "Different outsizes for callcore and callsummary, failing".toRight()
                }
                return ExpectCallClose(outSize).toLeft()
            }
        }

        /**
         * Indicates we've seen the expected callcore/opcode summaries with outsize [declaredOutSize],
         * and are now waiting to see the [TACMeta.CALL_GROUP_END] annotation.
         */
        data class ExpectCallClose(val declaredOutSize: BigInteger) : ReturnBufferState {
            override fun transitionCallClose(): Either<ReturnBufferState, String> {
                return ExpectRCBound(declaredOutSize, null).toLeft()
            }
        }

        /**
         * Marker interface for states that *might* contain sufficient information (i.e., a non-null
         * [seenFPWrite] with other information).
         */
        interface PotentiallyCompleteState : ReturnBufferState, WithDominanceTracking {
            val seenFPWrite: CmdPointer?
            val copyLocation: CmdPointer?
            val successPathStart: CmdPointer
            val declaredOutSize: BigInteger
            override val dominanceBoundary: Pair<NBId, NBId>?
        }

        object Halt : HaltingState {
            override fun toResult(st: State): StepResult {
                return StepResult.Halt
            }
        }

        /**
         * Indicates we have seen an FP write, and we should halt the analysis.
         */
        data class ProcessingComplete(
            override val declaredOutSize: BigInteger,
            override val seenFPWrite: CmdPointer,
            override val copyLocation: CmdPointer?,
            override val successPathStart: CmdPointer,
            override val dominanceBoundary: Pair<NBId, NBId>?
        ) : HaltingState, PotentiallyCompleteState {
            override fun toResult(st: State): StepResult {
                return StepResult.Completed(st.copy(
                    returnBufferState = this
                ))
            }
        }

        /**
         * A [ReturnBufferState] which tracks dominance information, namely when the control-flow from the call
         * reaches a point that the call no longer dominates.
         */
        sealed interface WithDominanceTracking {
            val dominanceBoundary: Pair<NBId, NBId>?
        }
        /**
         * We have passed the end of the call with static [outSize], and are now waiting to enter a branch where the
         * RC is non-zero (i.e., a success branch)
         */
        data class ExpectRCBound(val outSize: BigInteger, override val dominanceBoundary: Pair<NBId, NBId>?) : ReturnBufferState, WithDominanceTracking {
            override fun transitionRCDefinitelyZero(p: CmdPointer): Either<ReturnBufferState, String> {
                return Halt.toLeft()
            }

            override fun transitionDominanceLost(from: NBId, to: NBId): Either<ReturnBufferState, String> {
                // again, only track when this first starts
                if(dominanceBoundary != null) {
                    return this.toLeft()
                }
                return ExpectRCBound(outSize, from to to).toLeft()
            }

            override fun transitionRCDefinitelyNonZero(p: CmdPointer): Either<ReturnBufferState, String> {
                return SuccessPath(copyLocation = null, seenFPWrite = null, declaredOutSize = outSize, successPathStart = p, dominanceBoundary = dominanceBoundary).toLeft()
            }
        }

        fun transitionCallClose(): Either<ReturnBufferState, String> {
            return unsupported("call close")
        }

        fun transitionDominanceLost(from: NBId, to: NBId) : Either<ReturnBufferState, String> {
            return "Hit apparent dominance frontier going from $from to $to".toRight()
        }


        private fun unsupported(s: String) = "Unsupported transition $s in state $this".toRight()

        fun transitionCallCore(c: TACCmd.Simple.CallCore, outSize: BigInteger) : Either<ReturnBufferState, String> {
            return unsupported("call core")
        }

        fun transitionRCDefinitelyZero(p: CmdPointer) : Either<ReturnBufferState, String> {
            return this.toLeft()
        }

        fun transitionRCDefinitelyNonZero(p: CmdPointer) : Either<ReturnBufferState, String> {
            return this.toLeft()
        }

        fun transitionRCMaybeZero(p: CmdPointer) : Either<ReturnBufferState, String> {
            return this.toLeft()
        }

        fun transitionCallSummary(c: TACCmd.EVM.CallOpcodeSummary, outSize: BigInteger, st: State) : Either<ReturnBufferState, String> {
            return unsupported("call summary")
        }

        fun transitionReturnHavoc() : Either<ReturnBufferState, String> {
            return unsupported("return havoc")
        }

        fun transitionReturnZero(): Either<ReturnBufferState, String> {
            return unsupported("return zero")
        }

        fun transitionFPWrite(where: CmdPointer): Either<ReturnBufferState, String> {
            return unsupported("fp write")
        }

        fun transitionReturnDataCopy(p: CmdPointer) : Either<ReturnBufferState, String> {
            return unsupported("return data copy")
        }

        sealed interface RecoverableState : ReturnBufferState {
            /**
             * If we would otherwise fail the analysis due to some illegal operation at a command,
             * instead see if we can complete with a [HaltingState], otherwise return the error.
             */
            fun completeIfPossible(errString: String) : Either<HaltingState, String>
        }

        sealed interface HaltingState : ReturnBufferState {
            /**
             * Given the pre-step state [st], turn this [HaltingState] into a [StepResult] state
             * For [Halt] this simply returns [StepResult.Halt], for [ProcessingComplete] this
             * embeds that result into [st] and returns [StepResult.Completed]
             */
            fun toResult(st: State) : StepResult
        }
    }

    private fun ReturnBufferState.tryRecover(errString: String) = (this as? ReturnBufferState.RecoverableState)?.completeIfPossible(errString) ?: errString.toRight()

    /**
     * Payload of the pattern match for a return buffer allocation. [addLocation] is included for
     * debugging, but is not currently used.
     */
    private data class ReturnAllocMatch(
        val addLocation: LTACCmd,
        val fpOperand: LTACVar,
        val szOperand: LTACSymbol
    )

    private val returnSizeAllocPattern = PatternDSL.build {
        (roundUp {
            PatternMatcher.Pattern.AnySymbol({ where, x ->
                LTACSymbol(symbol = x, ptr = where.ptr)
            }).asBuildable()
        } + Var { maybeFp, where ->
            PatternMatcher.VariableMatch.Match(LTACVar(v = maybeFp, ptr = where.ptr))
        }).commute.withAction { where, roundUpOp, fpOp ->
            ReturnAllocMatch(
                addLocation = where,
                fpOperand = fpOp,
                szOperand = roundUpOp
            )
        } `^` (PatternMatcher.Pattern.FromConstant({ k, where ->
            LTACSymbol(symbol = k, ptr = where.ptr)
        }, { _, it -> it }).asBuildable() + Var { maybeFp, where ->
            PatternMatcher.VariableMatch.Match(LTACVar(v = maybeFp, ptr = where.ptr))
        }).commute.withAction { where, sizeOp, fpOp ->
            ReturnAllocMatch(
                addLocation = where,
                fpOperand = fpOp,
                szOperand = sizeOp
            )
        }
    }

    private fun doAllocRewrite(c: CoreTACProgram, patcher: SimplePatchingProgram, rewrite: ReturnBufferAlloc) {
        when(rewrite.allocSort) {
            is AllocSort.Dynamic -> {
                rewriteDynamicAllocation(rewrite.allocSort, rewrite, patcher, c)
            }
            is AllocSort.ExplicitSize -> {
                rewriteConstantSizeAllocation(rewrite.allocSort, rewrite, patcher)
            }

            AllocSort.ExplicitSizeAndInit -> {
                rewriteConstantExplicitAllocation(rewrite, patcher)
            }

            is AllocSort.Null -> {
                rewriteNullAllocation(rewrite.allocSort, patcher)
            }
        }
        pruneEdges(rewrite, patcher, c)
    }

    fun analyze(c: CoreTACProgram): CoreTACProgram {
        val matcher = PatternMatcher.compilePattern(c.analysisCache.graph, returnSizeAllocPattern) {
            it != TACKeyword.MEM64.toVar()
        }
        val rewrites = c.parallelLtacStream().filter {
            it.maybeAnnotation(TACMeta.CALL_GROUP_START)
        }.map {
            analyzeCallGroup(c, it, matcher)
        }.mapNotNull { work ->
            when(work) {
                is Either.Right -> {
                    logger.info {
                        "Failed analysis: ${work.d}"
                    }
                    return@mapNotNull null
                }
                is Either.Left -> work.d
            }
        }.toList()
        if(rewrites.isEmpty()) {
            return c
        }
        val patcher = c.toPatchingProgram()
        val (noDominance, withCrossing) = rewrites.partition {
            it.dominanceBoundary == null
        }
        noDominance.forEach {
            doAllocRewrite(c, patcher, it)
        }
        val g = c.analysisCache.graph
        outer@for((join, preds) in withCrossing.groupBy { it.dominanceBoundary!!.second }) {
            val predJoins = g.pred(join)
            if(preds.size != predJoins.size) {
                continue
            }
            if(predJoins != preds.mapToTreapSet { it.dominanceBoundary!!.first }) {
                continue
            }
            if(preds.map {
                it.allocSort
            }.uniqueOrNull() == null) {
                logger.info {
                    "At dominance frontier $join, alloc sort mismatches: $preds, so skipping"
                }
                continue@outer
            }
            val isSuccessPathMismatch = preds.mapToSet {
                it.successPathStart
            }.size > 1
            val first = preds[0]
            var it = 1
            var failedAliasMatching = false
            while(it < preds.size) {
                val other = preds[it]
                if (other.fpAliases != first.fpAliases || other.prunedPaths != first.prunedPaths) {
                    logger.info {
                        "At dominance frontier $join, alias state mismatch"
                    }
                    failedAliasMatching = true
                    break
                }
                it++
            }
            if(!isSuccessPathMismatch && !failedAliasMatching) {
                doAllocRewrite(c, patcher, first)
                continue@outer
            } else if(!failedAliasMatching) {
                // can we just "push" the success path later?
                if (first.allocSort !is AllocSort.ExplicitSize) {
                    continue@outer
                }
                val confluenceStart = CmdPointer(join, 0)
                // because of the equivalence on fpAliases above, we can do this check once
                val canUseJoinAsSuccess = first.fpAliases.all { (v, ty) ->
                    when (ty) {
                        /*
                              it is fine to rewrite any assignment using a copy, *or* the copies will automatically see the redefinition
                              at the new success point. Why? Well, where can we get a copy from? Either from the free pointer
                              (in which the copy must come after any Explicit, which must come after the confluenceStart)
                              OR from an extant pointer. But we also check that all extant pointers are only used *after* the confluence
                              start, so any aliases we create from them must come after the confluence start.

                              In other words, any rewriting we are doing with aliases is fine if we have copies around.
                             */
                        AliasDefinition.Copy -> true
                        is AliasDefinition.Explicit -> {
                            g.cache.domination.dominates(confluenceStart, ty.where)
                        }
                        /*
                             * so, redefining all extant aliases at the join point will be "fine"
                             */
                        AliasDefinition.Extant -> {
                            preds.all { p ->
                                g.cache.use.useSitesAfter(v, p.successPathStart).all { useSite ->
                                    g.cache.domination.dominates(confluenceStart, useSite)
                                }
                            }
                        }
                    }
                }
                if (canUseJoinAsSuccess) {
                    doAllocRewrite(
                        c, patcher, first.copy(
                            successPathStart = confluenceStart
                        )
                    )
                }
                continue@outer
            }
            // then we have a more complex join pattern
            val allocSort = preds.first().allocSort as? AllocSort.ExplicitSize ?: continue
            // well, can we push the success path later?

            // find the principle alias of the free pointer at these join points
            val confluenceStart = CmdPointer(join, 0)
            val lva = g.cache.lva
            val principleFp = preds.monadicMap {
                it.fpAliases.entries.singleOrNull { (v, _) ->
                    lva.isLiveBefore(confluenceStart, v)
                }?.key
            }?.uniqueOrNull() ?: continue@outer
            /*
             * "Hope" that any other FP aliases are dead, and thus will be ignored by the alloc analysis. Note that we
             * aren't breaking correctness here by ignoring them, by definition it is safe to redefine an alias
             * of a variable to be equal to that variable, and because we aren't changing the "interpretation" of the variables
             * (as is the case in the array allocations, where we push them 32 bytes later) it is fine to ignore
             * them. The worst that will happen is PTA fails.
             */
            rewriteConstantSizeAllocation(
                size = allocSort.k,
                fpWriteLocation = allocSort.fpWriteLocation,
                needRedef = listOf(principleFp),
                fpAlias = principleFp,
                simplePatchingProgram = patcher,
                successPathStart = confluenceStart
            )
            val selectedPaths = preds.map { it.prunedPaths }.uniqueOrNull()
            if(selectedPaths != null) {
                pruneEdges(selectedPaths, c, patcher)
            }
        }
        return patcher.toCode(c)
    }

    private fun rewriteNullAllocation(
        nullAlloc: AllocSort.Null,
        simplePatchingProgram: SimplePatchingProgram
    ) {
        simplePatchingProgram.replaceCommand(nullAlloc.where, listOf(TACCmd.Simple.NopCmd))
    }

    /**
     * Rewrite a constant size allocation which explicitly copies from returndata, but
     * may use an alias of the free pointer that was used as the input. In this case, no real rewriting is
     * needed, except generate a redefinition of the aliases in the success branch to make the PTA think
     * we're looking at a new alloc site.
     */
    private fun rewriteConstantExplicitAllocation(
        rewrite: ReturnBufferAlloc,
        patch: SimplePatchingProgram
    ) {
        val needRedef = rewrite.fpAliases.retainAll { (_, d) ->
            d == AliasDefinition.Extant
        }.keys
        if(needRedef.isEmpty()) {
            return
        }
        patch.addBefore(
            rewrite.successPathStart,
            needRedef.map {
                TACCmd.Simple.AssigningCmd.AssignExpCmd(
                    lhs = it,
                    rhs = TACKeyword.MEM64.toVar().asSym()
                )
            }
        )
    }

    private fun rewriteConstantSizeAllocation(
        size: BigInteger,
        needRedef: List<TACSymbol.Var>,
        fpAlias: TACSymbol.Var,

        fpWriteLocation: CmdPointer,
        successPathStart: CmdPointer,
        simplePatchingProgram: SimplePatchingProgram
    ) {
        val toAddBefore = mutableListOf<TACCmd.Simple>()
        for(k in needRedef) {
            toAddBefore.add(TACCmd.Simple.AssigningCmd.AssignExpCmd(
                lhs = k,
                rhs = TACKeyword.MEM64.toVar()
            ))
        }
        val k = fpAlias
        val nextFP = TACKeyword.TMP(Tag.Bit256, "!nextFp")
        simplePatchingProgram.addVarDecl(nextFP)
        simplePatchingProgram.replaceCommand(fpWriteLocation, listOf(
            TACCmd.Simple.AssigningCmd.AssignExpCmd(
                lhs = nextFP,
                rhs = TACExpr.Vec.Add(
                    k.asSym(),
                    size.asTACExpr
                )
            ),
            TACCmd.Simple.AssigningCmd.AssignExpCmd(
                lhs = TACKeyword.MEM64.toVar(),
                rhs = nextFP.asSym()
            ),
            TACCmd.Simple.ByteLongCopy(
                srcBase = TACKeyword.RETURNDATA.toVar(),
                length = size.asTACSymbol(),
                dstBase = TACKeyword.MEMORY.toVar(),
                srcOffset = TACSymbol.Zero,
                dstOffset = k
            ),
            TACCmd.Simple.AnnotationCmd(
                ConstantReturnBufferAllocComplete.META_KEY,
                ConstantReturnBufferAllocComplete(
                    outputVar = k,
                    allocSize = size
                )
            )
        ))
        simplePatchingProgram.addBefore(successPathStart, toAddBefore)
    }

    /**
     * Rewrite a constant sized allocation which uses the implicit copying of the callcore command. This inserts
     * an explicit copy of the [AllocSort.ExplicitSize.k] bytes in [allocSort], and rewrites the fp update
     * to instead perform a statically sized allocation by those same k bytes.
     *
     * As in the [rewriteConstantExplicitAllocation], extant aliases of the free pointer are redefined within the success
     * branch to avoid conflating scratch/allocation uses.
     */
    private fun rewriteConstantSizeAllocation(
        allocSort: AllocSort.ExplicitSize,
        rewrite: ReturnBufferAlloc,
        simplePatchingProgram: SimplePatchingProgram
    ) {
        val toAddBefore = mutableListOf<TACCmd.Simple>()
        for((k, w) in rewrite.fpAliases) {
            if(w !is AliasDefinition.Extant) {
                continue
            }
            toAddBefore.add(TACCmd.Simple.AssigningCmd.AssignExpCmd(
                lhs = k,
                rhs = TACKeyword.MEM64.toVar()
            ))
        }
        val (k, _) = rewrite.fpAliases.entries.firstOrNull { (_, v) ->
            v != AliasDefinition.Copy
        } ?: return
        rewriteConstantSizeAllocation(
            fpAlias = k,
            successPathStart = rewrite.successPathStart,
            fpWriteLocation = allocSort.fpWriteLocation,
            needRedef = rewrite.fpAliases.keysMatching { _, v ->
                v == AliasDefinition.Extant
            },
            size = allocSort.k,
            simplePatchingProgram = simplePatchingProgram
        )
    }

    /**
     * Rewrite a dynamically sized allocation. This finds all aliases of the free
     * pointer (either extant, or freshly created within the success branch) and bumps their
     * values by 0x20. The returndatacopy (which must exist, and which must copy to the FP) is not
     * touched, by virtue of targeting a known alias of the FP (see [stepBufferState]) we know that
     * by bumping all known free pointers we have also bumped where this copy will write.
     *
     * With this bumping, we end up reserving space for a length field (which isn't used, but
     * is necessary for this to "look like" an array allocation), into which we write the amount we copy
     * out of returndata (we insert this length field write immediately before the returndatacopy).
     */
    private fun rewriteDynamicAllocation(
        allocSort: AllocSort.Dynamic,
        rewrite: ReturnBufferAlloc,
        patch: SimplePatchingProgram,
        c: CoreTACProgram
    ) {
        val distinguishedAlias = TACKeyword.TMP(Tag.Bit256, "!fpRead")
        patch.addVarDecl(distinguishedAlias)
        val stagedInsertion = mutableMapOf<CmdPointer, MutableList<TACCmd.Simple>>(
            rewrite.successPathStart to mutableListOf(
                TACCmd.Simple.AssigningCmd.AssignExpCmd(
                    lhs = distinguishedAlias,
                    rhs = TACKeyword.MEM64.toVar()
                )
            )
        )
        val stagedReplacement = mutableMapOf<CmdPointer, TACCmd.Simple>()
        for((v, sort) in rewrite.fpAliases) {
            when(sort) {
                is AliasDefinition.Explicit -> {
                    val def = c.analysisCache.graph.elab(sort.where).enarrow<TACExpr.Sym>()
                    stagedReplacement[def.ptr] = TACCmd.Simple.AssigningCmd.AssignExpCmd(
                        lhs = v,
                        rhs = TACExpr.Vec.Add(
                            distinguishedAlias.asSym(),
                            EVM_WORD_SIZE.asTACExpr
                        )
                    )
                }
                AliasDefinition.Copy -> {}
                AliasDefinition.Extant -> {
                    stagedInsertion.getOrPut(rewrite.successPathStart) {
                        mutableListOf()
                    }.add(
                        TACCmd.Simple.AssigningCmd.AssignExpCmd(
                            lhs = v,
                            rhs = TACExpr.Vec.Add(
                                distinguishedAlias.asSym(),
                                EVM_WORD_SIZE.asTACExpr
                            )
                        )
                    )
                }
            }
        }
        stagedInsertion.computeIfAbsent(allocSort.copyLocation) {
            mutableListOf()
        }.add(
            TACCmd.Simple.AssigningCmd.ByteStore(
                value = TACKeyword.RETURN_SIZE.toVar(),
                loc = distinguishedAlias,
                base = TACKeyword.MEMORY.toVar()
            )
        )
        for((w, repl) in stagedReplacement) {
            if(w in stagedInsertion) {
                patch.replaceCommand(w, stagedInsertion[w]!! + repl)
                stagedInsertion.remove(w)
            } else {
                patch.replaceCommand(w, listOf(repl))
            }
        }
        for((where, prefix) in stagedInsertion) {
            patch.addBefore(where, prefix)
        }
    }

    /**
     * Solidity will sometimes generate spurious conditionals on the RC, and if they persist they confused
     * later analyses. This prunes those conditionals early.
     */
    private fun pruneEdges(rewrite: ReturnBufferAlloc, patch: SimplePatchingProgram, c: CoreTACProgram) {
        val prunedEdges = rewrite.prunedPaths
        pruneEdges(prunedEdges, c, patch)
    }

    private fun pruneEdges(
        prunedEdges: Set<Pair<NBId, NBId>>,
        c: CoreTACProgram,
        patch: SimplePatchingProgram
    ) {
        prunedEdges.mapNotNull { (head, tail) ->
            val final = c.analysisCache.graph.elab(head).commands.lastOrNull()?.maybeNarrow<TACCmd.Simple.JumpiCmd>()
                ?: return@mapNotNull null
            if (final.cmd.elseDst == tail) {
                final to true
            } else if (final.cmd.dst == tail) {
                final to false
            } else {
                null
            }
        }.let {
            if (it.isNotEmpty()) {
                patch.selectConditionalEdges(it)
            }
        }
    }

    context(EquivalenceClassProvider)
    private fun TACSymbol.isAliasOf(other: TACSymbol.Var) = this == other || this in equivalentVarsFor(other)

    context(EquivalenceClassProvider)
    private fun TACSymbol.isReturnSizeAlias() = this.isAliasOf(TACKeyword.RETURN_SIZE.toVar())

    context(EquivalenceClassProvider)
    private fun stepBufferState(st: State, where: LTACCmd, g: TACCommandGraph) : Either<ReturnBufferState, String> {
        val d = st.intState.retainAll { (k, q) ->
            k.isAliasOf(TACKeyword.RETURNCODE.toVar()) || returnCodeEqualQual in q.qual
        }.values.map {
            it.x
        }
        val isNonZero = d.any {
            it.mustNotEqual(BigInteger.ZERO)
        }
        val isDefinitelyZero = d.any {
            it.mustEqual(BigInteger.ZERO)
        }
        if(isNonZero && isDefinitelyZero) {
            return "Inconsistency in reduced product, have RC must both be zero and non-zero $d".toRight()
        }
        val rcStepOrErr = if(isNonZero) {
            st.returnBufferState.transitionRCDefinitelyNonZero(where.ptr)
        } else if (isDefinitelyZero) {
            st.returnBufferState.transitionRCDefinitelyZero(where.ptr)
        } else {
            st.returnBufferState.transitionRCMaybeZero(where.ptr)
        }
        val rcStep = when(rcStepOrErr) {
            is Either.Left -> rcStepOrErr.d
            is Either.Right -> return rcStepOrErr
        }
        if(rcStep is ReturnBufferState.HaltingState) {
            return rcStep.toLeft()
        }
        /**
         * Note that the only recoverable errors in what follows are memory operations
         */
        if(where.cmd is TACCmd.Simple.AssigningCmd && where.cmd.lhs == TACKeyword.RETURN_SIZE.toVar()) {
            return if(where.cmd is TACCmd.Simple.AssigningCmd.AssignHavocCmd) {
                rcStep.transitionReturnHavoc()
            } else if(where.cmd is TACCmd.Simple.AssigningCmd.AssignExpCmd && where.cmd.rhs.equivSym(TACSymbol.Zero)) {
                rcStep.transitionReturnZero()
            } else {
                "Illegal returnsize assignment: $where".toRight()
            }
        } else if(where.cmd is TACCmd.Simple.AssigningCmd && where.cmd.lhs == TACKeyword.MEM64.toVar()) {
            return rcStep.transitionFPWrite(where.ptr)
        } else if(where.cmd is TACCmd.Simple.CallCore) {
            val outSize = MustBeConstantAnalysis(graph = g).mustBeConstantAt(where.ptr, where.cmd.outSize) ?: return "Outsize for call-core $where was not constant".toRight()
            return rcStep.transitionCallCore(where.cmd, outSize)
        } else if(where.cmd is TACCmd.Simple.SummaryCmd && where.cmd.summ is TACCmd.EVM.CallOpcodeSummary) {
            val s = where.cmd.summ
            // without `else` clause IDEA complains, with it, the compiler complains about redundant when...
            val retSize = @Suppress("REDUNDANT_ELSE_IN_WHEN")
            when(s) {
                is CallOpcodeSummary -> s.retSize
                is CallcodeOpcodeSummary -> s.retLength
                is DelegatecallOpcodeSummary -> s.retLength
                is StaticcallOpcodeSummary -> s.retLength
                else -> `impossible!`
            }
            val mca = MustBeConstantAnalysis(graph = g)
            val constSize = mca.mustBeConstantAt(where.ptr, retSize) ?: return "Apparently dynamic outsize at $where".toRight()
            return rcStep.transitionCallSummary(where.cmd.summ, constSize, st)
        } else if(where.cmd.maybeAnnotation(TACMeta.CALL_GROUP_END)) {
            return rcStep.transitionCallClose()
        } else if(where.cmd is TACCmd.Simple.ByteLongCopy &&
            where.cmd.dstOffset in g.cache.gvn.equivBefore(where.ptr, TACKeyword.MEM64.toVar()) &&
            // the source offset is 0
            where.cmd.srcOffset == TACSymbol.Zero &&
            // copying FROM return data
            where.cmd.srcBase == TACKeyword.RETURNDATA.toVar() &&
            // copy to memory
            where.cmd.dstBase == TACKeyword.MEMORY.toVar() &&
            // this is a known alias, this matters for the rewrite
            where.cmd.dstOffset in st.fpAliases
        ) {
            return rcStep.transitionReturnDataCopy(where.ptr)
        } else if(where.cmd is TACCmd.Simple.DirectMemoryAccessCmd || where.cmd is TACCmd.Simple.LongAccesses) {
            val isSafeAccess = SighashBinder.SAFE_INSTRUMENTED_READ in where.cmd.meta ||
                OptimisticSpillRewriter.VALIDATION_READ in where.cmd.meta ||
                (where.cmd is TACCmd.Simple.AssigningCmd.ByteLoad && where.cmd.base.let {
                    it != TACKeyword.RETURNDATA.toVar() && it != TACKeyword.MEMORY.toVar()
                }) ||
                (where.cmd is TACCmd.Simple.DirectMemoryAccessCmd && where.cmd.loc is TACSymbol.Const && Config.EnableOptimisticReturnBufferAnalysis.get())
            return if(!isSafeAccess) {
                rcStep.tryRecover("Have memory access command at $where")
            } else {
                rcStep.toLeft()
            }
        } else {
            return rcStep.toLeft()
        }
    }

    /**
     * Step the interval state using the abstract interpreter
     */
    private fun stepIntervalState(st: State, lc: LTACCmd) : TreapMap<TACSymbol.Var, QInt> {
        return absInt.step(lc, st)
    }


    /**
     * If we are in a state where we should track aliases (as determined by [ReturnBufferState.trackFPAliases])
     * then find all FP alises, and track where we create new ones (at least until we see an FP write).
     */
    private fun stepFPAliasState(
        newBufferState: ReturnBufferState,
        step: State,
        lc: LTACCmd,
        g: TACCommandGraph
    ) : Either<TreapMap<TACSymbol.Var, AliasDefinition>, String> {
        /**
         * If we are starting to track aliases (because we're entering a success path) find all existing
         * aliases, and record that we'll need to redefine them in this branch (which is what [AliasDefinition.Extant] means)
         */
        val aliasState = if(newBufferState.trackFPAliases() && !step.returnBufferState.trackFPAliases()) {
            if(step.fpAliases.isNotEmpty()) {
                return "Already tracking fp aliases, despite only just now entering success state at $lc".toRight()
            }
            g.cache.gvn.equivBefore(lc.ptr, TACKeyword.MEM64.toVar()).filter {
                it != TACKeyword.MEM64.toVar() && g.cache.lva.isLiveBefore(lc.ptr, it)
            }.associateWith<TACSymbol.Var, AliasDefinition> { AliasDefinition.Extant }.toTreapMap()
        } else {
            step.fpAliases
        }
        /**
         * Even if we are no longer tracking FP aliases (because we've since seen an FP write)
         * it is very convenient to know that FP aliases are not killed in the time between the fp write
         * and the end of the block, so insist on that.
         */
        if(lc.cmd is TACCmd.Simple.AssigningCmd) {
            if (lc.cmd.lhs in step.fpAliases) {
                return "killed FP alias at $lc".toRight()
            }
        }
        /**
         * Again, we have have non-empty fp aliases and not be tracking because we're in a [analysis.alloc.ReturnBufferAnalysis.ReturnBufferState.SuccessPath]
         * state where [analysis.alloc.ReturnBufferAnalysis.ReturnBufferState.SuccessPath.seenFPWrite] is non-null.
         */
        if(!newBufferState.trackFPAliases()) {
            return aliasState.toLeft()
        }
        return if(lc.cmd is TACCmd.Simple.AssigningCmd) {
            if(lc.cmd !is TACCmd.Simple.AssigningCmd.AssignExpCmd || lc.cmd.rhs !is TACExpr.Sym.Var) {
                return aliasState.toLeft()
            }
            /**
             * New FP alias, record the definition site
             */
            if(lc.cmd.rhs.s == TACKeyword.MEM64.toVar()) {
                aliasState + (lc.cmd.lhs to AliasDefinition.Explicit(lc.ptr))
            } else if(lc.cmd.rhs.s in step.fpAliases) {
                aliasState + (lc.cmd.lhs to AliasDefinition.Copy)
            } else {
                aliasState
            }
        } else {
            aliasState
        }.toLeft()
    }

    context(EquivalenceClassProvider)
    private fun stepCommand(g: TACCommandGraph, lc: LTACCmd, st: State) : Either<StepResult, String> {
        return stepBufferState(st, lc, g).bindLeft l@{ s ->
            if(s is ReturnBufferState.HaltingState) {
                return@l s.toResult(st).toLeft()
            }
            stepFPAliasState(s, st, lc, g).mapLeft { fpAlias ->
                State(
                    intState = stepIntervalState(st, lc),
                    fpAliases = fpAlias,
                    returnBufferState = s
                ).let(StepResult::Continue)
            }.bindRight {
                // If stepping this state breaks the fp aliases, instead of stepping it state, can we just say we're done?
                st.returnBufferState.tryRecover(it).mapLeft { h ->
                    h.toResult(st)
                }
            }
        }
    }

    /**
     * Broadly, what kind of allocation is this?
     */
    private sealed interface AllocSort {
        /**
         * A statically sized block with an explicit returndatacopy
         */
        object ExplicitSizeAndInit : AllocSort

        /**
         * A statically sized block of size [k] that used the callcore's implicit copy, and
         * whose fp update (which we need to turn into a "static" looking alloc) was at [fpWriteLocation]
         */
        data class ExplicitSize(val k: BigInteger, val fpWriteLocation: CmdPointer) : AllocSort

        /**
         * A dynamically sized block using an explicit returndatacopy at [copyLocation]
         */
        data class Dynamic(val copyLocation: CmdPointer) : AllocSort
        data class Null(val where: CmdPointer) : AllocSort
    }

    /**
     * The result of a successful analysis. The success path for the call command begins at [successPathStart],
     * and performs an allocation of sort [allocSort]. [fpAliases] records the [fpAliases] at the fp write.
     * [prunedPaths] are the edges which were shown to be infeasible during the analysis (and which should be pruned).
     */
    private data class ReturnBufferAlloc(
        val successPathStart: CmdPointer,
        val allocSort: AllocSort,
        val fpAliases: TreapMap<TACSymbol.Var, AliasDefinition>,
        val prunedPaths: Set<Pair<NBId, NBId>>,
        val dominanceBoundary: Pair<NBId, NBId>?
    )

    private fun Either.Right<String>.format(f: (String) -> String) = f(this.d).toRight()

    private fun interface EquivalenceClassProvider {
        fun equivalentVarsFor(v: TACSymbol) : Set<TACSymbol>
    }

    /**
     * Given a call group beginning at [start] in [c], using the return alloc matcher,
     * try to determine if we have a return buffer allocation.
     */
    private fun analyzeCallGroup(c: CoreTACProgram, start: LTACCmd, matcher: SymbolQuery<PatternMatcher.ConstLattice<ReturnAllocMatch>>) : Either<ReturnBufferAlloc, String> {
        val nbs = c.analysisCache.naturalBlockScheduler
        val startState = State(
            intState = treapMapOf(),
            returnBufferState = ReturnBufferState.ExpectReturnSize,
            fpAliases = treapMapOf()
        )

        /**
         * map of command location => pre-state
         */
        val stateAccum = treapMapBuilderOf<CmdPointer, State>()

        /**
         * The out state for a block
         */
        val blockOut = treapMapBuilderOf<NBId, State>()

        val g = c.analysisCache.graph

        fun errWithContext(e: String) = "Failed analysis call group at ${start.ptr} in ${c.name}: $e"
        fun Either.Right<String>.withContext() = this.format(::errWithContext)
        fun err(e: String) = errWithContext(e).toRight()

        fun <T> withEquivAt(c: CmdPointer, f: context(EquivalenceClassProvider)() -> T) : T {
            return f { x: TACSymbol ->
                when(x) {
                    is TACSymbol.Const -> treapSetOf(x)
                    is TACSymbol.Var -> g.cache.gvn.equivBefore(c, x)
                }
            }
        }

        /**
         * Step [cmds] starting in [initState]. Either aborts with an error (the [Either.Right] case)
         * or the result of stepping (the [Either.Left] case).
         *
         * It is *not* guaranteed that all [cmds] in will be stepped, if the intermediate
         * steps hit [StepResult.Halt] or [StepResult.Completed], then this will return "early".
         *
         * However, for all commands which this command *attempts* to step, the pre-state of the command is
         * recorded in state accum.
         */
        fun processCommands(cmds: Sequence<LTACCmd>, initState: State) : Either<StepResult, String> {
            var stateIt = initState
            for(lc in cmds) {
                stateAccum[lc.ptr] = stateIt
                val stepResult = withEquivAt(lc.ptr) { this.stepCommand(g, lc, stateIt) }
                stateIt = when(stepResult) {
                    is Either.Right -> return stepResult
                    is Either.Left -> when(val s = stepResult.d) {
                        StepResult.Halt,
                        is StepResult.Completed -> return s.toLeft()
                        is StepResult.Continue -> s.st
                    }
                }
            }
            return StepResult.Continue(stateIt).toLeft()
        }

        val postSuffix = processCommands(g.iterateBlock(start.ptr, excludeStart = false).asSequence(), startState)

        var resultStateMut: State? = null

        /**
         * Based on [res] which is assumed to be the result of stepping some (not necessarily strict) suffix of the commands in
         * [b], either add the successors of [b] onto the worklist [nxt] or return the worklist, and
         * update internal accounting.
         *
         * If [res] is [StepResult.Halt], simply return without adding anything
         * If [res] is [StepResult.Completed], record the complete state in the "principle" success variable
         * resultStateMut. If this is already set, we have conflicting results, and return an error, otherwise,
         * update the variable and return [nxt] without adding work items.
         * Finally, if [res] is [StepResult.Continue], we record the contained state into the blockOut for [b].
         * Then, we update [nxt] after consulting the state contained within [res]. If the state is
         * "sufficient" for a return allocation check ([ReturnBufferState.isSufficientTrace]), then treat this case
         * like [StepResult.Completed] above (i.e., try to record the result, error if we already have a result, &c).
         * Otherwise, we add the successors of [b] onto [nxt].
         */
        fun processBlockCompletion(b: NBId, res: StepResult, nxt: TreapSet<NBId>) : Either<TreapSet<NBId>, String> {
            when(res) {
                is StepResult.Completed -> {
                    if(resultStateMut != null) {
                        return "Multiple conflicting success states, after processing $b, have $resultStateMut".toRight()
                    }
                    resultStateMut = res.st
                    return nxt.toLeft()
                }
                is StepResult.Continue -> {
                    blockOut[b] = res.st
                    if(res.st.returnBufferState.isSufficientTrace()) {
                        if(resultStateMut != null) {
                            return "Multiple conflicting success states, exiting $b but already have $resultStateMut".toRight()
                        }
                        resultStateMut = res.st
                        return nxt.toLeft()
                    }
                    return (nxt + g.succ(b)).toLeft()
                }
                StepResult.Halt -> return nxt.toLeft()
            }
        }

        /**
         * A wrapper around the implementation above, except errors in the result are returned as is
         */
        fun processBlockCompletion(b: NBId, res: Either<StepResult, String>, nxt: TreapSet<NBId>) : Either<TreapSet<NBId>, String> {
            return when (res) {
                is Either.Left -> processBlockCompletion(b, res.d, nxt)
                is Either.Right -> return res
            }
        }

        var worklist = when(val r = processBlockCompletion(start.ptr.block, postSuffix, treapSetOf())) {
            is Either.Left -> r.d
            is Either.Right -> return r.withContext()
        }
        val visited = treapSetBuilderOf(start.ptr.block)

        /**
         * A simple sanity check to make sure we don't accidentally spin out. We cap the number of blocks we're
         * willing to traverse at 20.
         */
        var blockCount = 0
        val prunedPaths = mutableSetOf<Pair<NBId, NBId>>()
        while(worklist.isNotEmpty() && blockCount < 20) {
            var nxt = treapSetOf<NBId>()
            for(b in worklist) {
                if(b in g.cache.revertBlocks) {
                    continue
                }
                if(!nbs.shouldSchedule(b) { worklist }) {
                    nxt += b
                    continue
                }
                /**
                 * Assume loop free code, if we see a loop, something has gone very wrong.
                 */
                if(b in visited) {
                    return err("already visited block $b")
                }
                visited.add(b)
                /**
                 * Find all predecessor states. The guarantees of the [analysis.worklist.NaturalBlockScheduler]
                 * and the non-looping constraint means that the states we observe here are sufficient
                 * to give an overapproximation of the state at this block. So if we have a predecessor
                 * for which we do not have an out state, then we *must* have pruned that path and so its okay
                 * to not wait for it (this is why the domination check above is important).
                 */
                val predStates = mutableListOf<Pair<NBId, State>>()
                for(pred in g.pred(b)) {
                    val st = blockOut[pred] ?: continue
                    val pc = g.pathConditionsOf(pred)[b] ?: error("No path condition from $pred -> $b?")
                    val propagated = absInt.propagate(g.elab(pred).commands.last(), pathCondition = pc, w = st)?.let { withPropagation ->
                        st.copy(
                            intState = withPropagation,
                        )
                    }
                    /**
                     * If this path is infeasible, record this for pruning later with [pruneEdges] and go
                     * to the top of the loop.
                     */
                    if(propagated == null) {
                        prunedPaths.add(pred to b)
                        continue
                    }
                    predStates.add(pred to propagated)
                }
                /**
                 * This is an infeasible block somehow
                 */
                if(predStates.isEmpty()) {
                    continue
                }
                blockCount++
                /**
                 * Join the numeric states, allowing the states to communicate with each other and use equivalence information.
                 * See [ReturnBufferAnalysis.reduce] for details.
                 */
                val numericState = reduce(predStates.map {
                    val lastCommad = g.elab(it.first).commands.last()
                    StateWithEquivalence(m = it.second.intState) { sym ->
                        if(sym !is TACSymbol.Var) {
                            setOf(sym)
                        } else {
                            g.cache.gvn.equivAfter(lastCommad.ptr, sym)
                        }
                    }
                })
                val joinedState = State(
                    intState = numericState,
                    /**
                     * This join rule means that we *always* are in a coherent, single view of the return buffer state
                     */
                    returnBufferState = predStates.map { it.second.returnBufferState }.uniqueOrNull() ?: return err("at start of $b, inconsistent return states: ${predStates.map { it.second.returnBufferState }}"),
                    /**
                     * The domain check here (and the nullability of the first argument) means we halt if there is disagreement about
                     * which variables are aliases.
                     */
                    fpAliases = predStates.map { it.second.fpAliases }.reduce { a: TreapMap<TACSymbol.Var, AliasDefinition>?, other: TreapMap<TACSymbol.Var, AliasDefinition> ->
                        a?.merge(other) { _, v1, v2 ->
                            if(v1 != v2) {
                                null
                            } else {
                                v1
                            }
                        }?.takeIf {
                            it.keys == other.keys
                        }
                    } ?: return err("disjoint domains detected at start for $b: $predStates")
                )
                val toIterate = if(!g.cache.domination.dominates(start.ptr.block, b)) {
                    if(joinedState.returnBufferState !is ReturnBufferState.WithDominanceTracking || joinedState.returnBufferState.dominanceBoundary == null) {
                        // this is likely equivalent to looking for the single predecessor for which we have an out state,
                        // but I prefer this as it is a more direct (To my mind) stating of what we're looking for
                        // note that we only expect one such predecessor here, despite this being a join point, because
                        // at this point we are doing the analysis on a per-call group basis, so we shouldn't have traversed the other
                        // paths to this point
                        val uniquePred = g.pred(b).singleOrNull { p ->
                            g.cache.domination.dominates(start.ptr.block, p)
                        } ?: return err("hit apparent dominance frontier $b, but couldn't find where we came from; aborting analysis")
                        when(val r = joinedState.returnBufferState.transitionDominanceLost(from = uniquePred, to = b)) {
                            is Either.Left -> joinedState.copy(
                                returnBufferState = r.d
                            )
                            is Either.Right -> return r.withContext()
                        }
                    } else {
                        joinedState
                    }
                } else {
                    joinedState
                }

                val blockCommands = g.elab(b).commands
                /**
                 * Then simply update the worklist using the helper functions defined above.
                 */
                nxt = when(val r = processBlockCompletion(b, processCommands(blockCommands.asSequence(), toIterate), nxt)) {
                    is Either.Right -> return r.withContext()
                    is Either.Left -> {
                        r.d
                    }
                }
            }
            worklist = nxt
        }
        if(resultStateMut == null || blockCount == 20) {
            return err("Exhausted worklist, but no end of state or fp write found ($blockCount)")
        }
        /**
         * We now have a principle success state.
         */
        val resultState = resultStateMut!!
        val successState = resultState.returnBufferState as? ReturnBufferState.PotentiallyCompleteState ?: return err("Have result state that is not success path state: ${resultState.returnBufferState}")
        val definiteFPWrite = successState.seenFPWrite ?: return err("Have a success state without a fp write: $successState")

        /**
         * See if the FP write we found looks like a return buffer allocation (i.e., a round up, or a constant
         * allocation).
         */
        val retMatch = g.elab(definiteFPWrite).maybeNarrow<TACCmd.Simple.AssigningCmd>()?.let {
            matcher.queryFrom(it)
        }?.toNullableResult() ?: return err("fp write at $definiteFPWrite was not a return-like pattern")
        /**
         * If the pointer to which we're adding the size we're allocating isn't a known alias of the free pointer, abort).
         */
        if(stateAccum[retMatch.fpOperand.ptr]?.fpAliases?.get(retMatch.fpOperand.v) == null) {
            return err("Operand ${retMatch.fpOperand} was not an alias of fp at alloc")
        }

        val stateAtAlloc = stateAccum[definiteFPWrite] ?: return err("don't have state at fp write $definiteFPWrite")

        /**
         * The final command of the block containing the alloc
         */
        val finalCommand = g.elab(definiteFPWrite.block).commands.last()
        val mca = MustBeConstantAnalysis(g)

        /**
         * Is the size of the allocation a known constant
         */
        val isConstantAlloc = mca.mustBeConstantAt(retMatch.szOperand.ptr, retMatch.szOperand.symbol) ?: stateAccum[retMatch.szOperand.ptr]?.intState?.get(retMatch.szOperand.symbol)?.x?.takeIf {
            it.isConstant
        }?.c

        val hasNonZeroOutSize = successState.declaredOutSize != BigInteger.ZERO

        /**
         * This state given by following the distinguished, non-reverting
         * path from the end of the block containing the FP write. If the analysis never reached
         * the end of the block (because it saw another update of the FP, there was a memory operation, etc.)
         * then this will return null for reasons explained in the documentation of [ReturnBufferState]
         */
        val postAllocState : TreapMap<TACSymbol.Var, QInt>? by lazy {
            val endState = blockOut[definiteFPWrite.block] ?: return@lazy null

            // see if the successor of the block with the allocation establishes the bound we need
            val (noRevert, revert) = g.succ(definiteFPWrite.block).partition {
                it !in g.cache.revertBlocks
            }
            if (noRevert.size != 1 || revert.size != 1) {
                return@lazy null
            }
            val pc = g.pathConditionsOf(definiteFPWrite.block)[noRevert.single()]!!
            val fpWriteBlock = g.elab(definiteFPWrite.block)
            propagationSemantics.propagate(
                pathCondition = pc,
                l = fpWriteBlock.commands.last(),
                s = endState.intState,
                w = endState
            )
        }

        /**
         * Special case for "allocation" of zero-sized buffer for return functions.
         *
         * Go home solidity, ur drunk.
         */
        if(isConstantAlloc == BigInteger.ZERO && successState.declaredOutSize == BigInteger.ZERO && successState.copyLocation == null) {
            return ReturnBufferAlloc(
                prunedPaths = prunedPaths,
                allocSort = AllocSort.Null(where = definiteFPWrite),
                successPathStart = successState.successPathStart,
                fpAliases = stateAtAlloc.fpAliases,
                dominanceBoundary = successState.dominanceBoundary
            ).toLeft()
        }

        /**
         * No explicit copy with a constant sized output buffer.
         */
        if(hasNonZeroOutSize && successState.copyLocation == null) {
            /**
             * Now see if the amount we are allocating is known to be at least as large as the
             * declared outsize, *AND* the returnsize is known to be big enough as well.
             *
             * State candidates here are the potential states which might have the bound information we need:
             * the state at the allocation, and if it is available, the state after reaching the end of the block
             * and following the non-revert path.
             */
            val stateCandidates = listOfNotNull(
                definiteFPWrite to stateAtAlloc.intState,
                finalCommand.ptr `to?` postAllocState
            )
            val hasAllocSize = isConstantAlloc == successState.declaredOutSize ||
                (retMatch.szOperand.symbol is TACSymbol.Var &&
                    stateCandidates.any { (relevantCommand, relevantState) ->
                        relevantState.any { (k, v) ->
                            k in g.cache.gvn.findCopiesAt(source = retMatch.szOperand.ptr to retMatch.szOperand.symbol, target = relevantCommand) && v.x.lb >= successState.declaredOutSize
                        }
                    }
                )

            val hasReturnSizeBound = stateCandidates.any { (where, stateWithBound) ->
                withEquivAt(where) {
                    stateWithBound.any { (k, qi) ->
                        (returnSizeEqualQual in qi.qual || k.isReturnSizeAlias() || qi.qual.any { q ->
                            (q as? AnalysisQual.ReturnSizeOrConstantLowerBound)?.k == successState.declaredOutSize
                        }) && qi.x.lb >= successState.declaredOutSize
                    }
                }
            }
            if(hasAllocSize && hasReturnSizeBound) {
                return ReturnBufferAlloc(
                    fpAliases = stateAtAlloc.fpAliases,
                    successPathStart = successState.successPathStart,
                    prunedPaths = prunedPaths,
                    allocSort = AllocSort.ExplicitSize(successState.declaredOutSize, fpWriteLocation = definiteFPWrite),
                    dominanceBoundary = successState.dominanceBoundary
                ).toLeft()
            }
        }
        /**
         * Otherwise, see if we have an explicit copy
         */
        val copyLength = successState.copyLocation?.let(g::elab)?.narrow<TACCmd.Simple.ByteLongCopy>()?.cmd?.length

        /**
         * If we have an explicit copy, was that copy of an explicit size?
         */
        val constantLength = copyLength?.let { len ->
            mca.mustBeConstantAt(successState.copyLocation!!, len)
        }
        /**
         * If so, and it matches the size of the block we are allocing, then this is the "explicit copy"
         * variant of the static block.
         */
        if(constantLength != null && constantLength == isConstantAlloc) {
            return ReturnBufferAlloc(
                fpAliases = stateAtAlloc.fpAliases,
                successPathStart = successState.successPathStart,
                prunedPaths = prunedPaths,
                allocSort = AllocSort.ExplicitSizeAndInit,
                dominanceBoundary = successState.dominanceBoundary
            ).toLeft()
        }
        /**
         * Otherwise, if we had an explicit copy, and the size of the block we're allocing is known to be the size
         * of the copy, tag this as a dynamic allocation.
         *
         * NB that we allow this to not necessarily copy the full returnsize, just use a consistent value between the
         * copy amount and the allocation amount, in practice this should always be the returnsize.
         */
        if(successState.copyLocation != null && copyLength is TACSymbol.Var && retMatch.szOperand.symbol in g.cache.gvn.findCopiesAt(retMatch.szOperand.ptr, successState.copyLocation!! to copyLength)) {
            return ReturnBufferAlloc(
                allocSort = AllocSort.Dynamic(
                    successState.copyLocation!!,
                ),
                successPathStart = successState.successPathStart,
                fpAliases = stateAtAlloc.fpAliases,
                prunedPaths = prunedPaths,
                dominanceBoundary = successState.dominanceBoundary
            ).toLeft()
        }
        return err("Did not recognize allocation pattern")
    }

    /**
     * A numeric state, with optional information about aliases given by the [EquivalenceClassProvider]
     */
    private data class StateWithEquivalence(
        val m: TreapMap<TACSymbol.Var, QInt>,
        val equiv: EquivalenceClassProvider?
    )

    /**
     * Is the constant [c] known to be a (non-strict) *lower* bound on the returnsize? Searches
     * for some `x` where `m[x].lb >= c` and `x` is known to be an alias of the returnsize.
     */
    private fun isReturnSizeLowerBoundConstant(state: StateWithEquivalence, c: BigInteger): Boolean {
        return state.m.any { (boundedVar, qi) ->
            (boundedVar == TACKeyword.RETURN_SIZE.toVar() || returnSizeEqualQual in qi.qual || state.equiv?.run {
                boundedVar.isReturnSizeAlias()
            } == true) && qi.x.lb >= c
        }
    }

    private fun reduce(predStates: List<StateWithEquivalence>): TreapMap<TACSymbol.Var, QInt> {
        if(predStates.size == 1) {
            return predStates.single().m
        }
        fun pairwiseReduce(s1: StateWithEquivalence, s2: StateWithEquivalence): StateWithEquivalence {
            return s1.m.intersect(s2.m) { k, q1, q2 ->
                val extraQuals = mutableListOf<AnalysisQual>()
                val q2IsReturnSize = returnSizeEqualQual in q2.qual || (
                        s2.equiv?.run {
                            k.isReturnSizeAlias()
                        } == true
                    )
                val q2IsReturnSzDisj : BigInteger? = q2.qual.mapNotNull {
                    (it as? AnalysisQual.ReturnSizeOrConstantLowerBound)?.k
                }.uniqueOrNull()

                val q2ReturnSizeBoundC = q2.x.takeIf { it.isConstant }?.c?.takeIf { c ->
                    isReturnSizeLowerBoundConstant(s2, c)
                }
                val q1ReturnSizeBoundC = q1.x.takeIf { it.isConstant }?.c?.takeIf { c ->
                    isReturnSizeLowerBoundConstant(s1, c)
                }

                val q1IsReturnSize = returnSizeEqualQual in q1.qual || (
                        s1.equiv?.run {
                            k.isReturnSizeAlias()
                        } == true
                    )
                val q1IsReturnSzDisj : BigInteger? = q1.qual.mapNotNull {
                    (it as? AnalysisQual.ReturnSizeOrConstantLowerBound)?.k
                }.uniqueOrNull()

                /**
                 * Facts to generate when the "left" operand is known to equal the return size
                 */
                fun joinEquality(
                    leftIsReturnSize: Boolean,
                    rightK: BigInteger?,
                    rightDisj: BigInteger?
                ) {
                    /*
                     Well, it wasn't equal to the returnsize, so nevermind
                     */
                    if(!leftIsReturnSize) {
                        return
                    }
                    /**
                     * [rightK] is a constant, and in that "right" state, that is a lower bound on the returnsize.
                     * Since, "left" is known to be equal to the returnsize, we can conclude that this variable bounds
                     * the returnsize.
                     */
                    if(rightK != null) {
                        extraQuals.add(AnalysisQual.ReturnSizeOrConstantLowerBound(rightK))
                    }
                    /**
                     * If [rightDisj] is non-null, then in the "right" state, the value is known to either be
                     * k (a lower bound on the returnsize) or the returnsize itself. As left is also the returnsize,
                     * any bound on the variable in the out state will also bound the returnsize.
                     */
                    if(rightDisj != null) {
                        extraQuals.add(AnalysisQual.ReturnSizeOrConstantLowerBound(rightDisj))
                    }
                }

                /**
                 * Facts to generate when the "left" operand is a
                 * [analysis.alloc.ReturnBufferAnalysis.AnalysisQual.ReturnSizeOrConstantLowerBound], that is,
                 * the "left" operand is known to be a constant that lower-bounds the returnsize *or* the returnsize
                 * itself.
                 */
                fun joinDisjunction(
                    leftDisj: BigInteger?,
                    rightK: BigInteger?,
                    rightDisj: BigInteger?,
                    rightIsReturnSize: Boolean
                ) {
                    /**
                     * Well, left wasn't actually this information anyway, so never mind
                     */
                    if(leftDisj == null) {
                        return
                    }
                    /**
                     * Allow us to keep this information if the "right" operand is also a constant-lower-bound-or-returnsize
                     * with the same constant lower-bound, OR it is returnsize, OR it is constant-lower-bound.
                     */
                    if((rightK != null && rightK == leftDisj) || rightDisj == leftDisj || rightIsReturnSize) {
                        extraQuals.add(AnalysisQual.ReturnSizeOrConstantLowerBound(leftDisj))
                    }
                }

                /**
                 * there is maybe a way to do this without just swapping the operands, but I'm
                 * not clever enough to figure it out.
                 */
                joinEquality(q1IsReturnSize, q2ReturnSizeBoundC, q2IsReturnSzDisj)
                joinEquality(q2IsReturnSize, q1ReturnSizeBoundC, q1IsReturnSzDisj)

                joinDisjunction(q1IsReturnSzDisj, q2ReturnSizeBoundC, q2IsReturnSzDisj, q2IsReturnSize)
                joinDisjunction(q2IsReturnSzDisj, q1ReturnSizeBoundC, q1IsReturnSzDisj, q1IsReturnSize)

                QInt(
                    q1.x.join(q2.x),
                    (q1.qual intersect q2.qual) + extraQuals
                )
            }.let {
                StateWithEquivalence(it, null)
            }
        }
        return predStates.reduce(::pairwiseReduce).m
    }
}
