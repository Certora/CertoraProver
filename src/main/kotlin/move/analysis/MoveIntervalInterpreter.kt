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

package move.analysis

import datastructures.stdcollections.*
import analysis.CmdPointer
import analysis.LTACCmd
import analysis.LTACCmdView
import analysis.PathCondition
import analysis.numeric.simplequalifiedint.SimpleQualifiedIntExpressionInterpreter
import analysis.numeric.simplequalifiedint.SimpleQualifiedIntAbstractInterpreter
import analysis.numeric.simplequalifiedint.SimpleQualifiedIntState as IntervalState
import analysis.numeric.simplequalifiedint.SimpleQualifiedIntQualifierManager
import analysis.numeric.simplequalifiedint.SimpleQualifiedInt
import analysis.numeric.simplequalifiedint.StatementInterpreter
import com.certora.collect.TreapMap
import com.certora.collect.TreapSet
import com.certora.collect.intersect
import com.certora.collect.removeAll
import com.certora.collect.treapMapBuilderOf
import com.certora.collect.treapSetOf
import move.MoveTACCommandGraph
import move.MoveTACProgram
import move.MoveTag
import tac.Tag
import utils.associateWithNotNull
import utils.filterToSet
import utils.foldFirstOrNull
import utils.`impossible!`
import utils.mapToSet
import utils.monadicMap
import utils.tryAs
import utils.uniqueOrNull
import utils.updateInPlace
import vc.data.TACCmd
import vc.data.TACExpr
import vc.data.TACSymbol
import vc.data.asTACSymbol
import kotlin.collections.component1
import kotlin.collections.component2

sealed class LocValue {
    abstract fun killAll(x: Collection<TACSymbol.Var>): LocValue?
    /**
     * @property length may-set of lengths
     */
    data class Vec(val length: SimpleQualifiedInt): LocValue() {
        override fun killAll(x: Collection<TACSymbol.Var>): LocValue? =
            Vec(length.copy(qual = length.qual.filterToSet { it.relatesAny(x) }))
    }

    /**
     * @property vecref must set of referenced vectors
     */
    data class Length(val vecref: TreapSet<TACSymbol.Var>): LocValue() {
        override fun killAll(x: Collection<TACSymbol.Var>): LocValue? =
            Length(vecref.removeAll(x)).takeIf {
                it.vecref.isNotEmpty()
            }
    }

    fun join(o: LocValue, widen: Boolean): LocValue? =
        when {
            this is Vec && o is Vec ->
                Vec(length.join(o.length, widen))

            this is Length && o is Length ->
                Length(vecref.intersect(o.vecref))

            else ->
                null
        }
}

/**
 * @property intState int value state (var |-> int approx)
 * @property loc approximation (var |-> ref U loc approx)
 */
data class MoveState(
    val intState: IntervalState,
    val vecState: TreapMap<TACSymbol.Var, LocValue>
) {
    fun kill(x: List<TACSymbol.Var>) = MoveState(
        intState = IntervalState(intState.s.removeAll(x)),

        vecState = vecState.parallelUpdateValues { _, v -> v.killAll(x) }
    )
    fun join(other: MoveState, widen: Boolean) =
        MoveState(
            intState = intState.join(other.intState, widen),
            vecState = vecState.parallelMerge(other.vecState) { _, l, r ->
                if (l == null || r == null) {
                    null
                } else {
                    l.join(r, widen)
                }
            }
        )
}

private class SimpleIntervalInterpreter(manager: SimpleQualifiedIntQualifierManager): SimpleQualifiedIntAbstractInterpreter<MoveState>(manager) {
    override fun postStep(stepped: IntervalState, l: LTACCmd): IntervalState = stepped

    override fun project(l: LTACCmd, w: MoveState): IntervalState = w.intState

    override val statementInterpreter = StatementInterpreter(
        pathSemantics = pathSemantics,
        expressionInterpreter = SimpleQualifiedIntExpressionInterpreter()
    )
}

class MoveIntervalInterpreter(val g: MoveTACCommandGraph) {
    private val qualifierManager = SimpleQualifiedIntQualifierManager()
    private val referenceAnalysis = g.cache.references
    private val simpleInterpreter = SimpleIntervalInterpreter(qualifierManager)

    private fun refToLocVars(ref: TACSymbol.Var, where: CmdPointer) =
        refToLocs(ref, where).map {
            referenceAnalysis.idToVar[it]!!
        }

    private fun refToLocs(ref: TACSymbol.Var, where: CmdPointer) =
        refTargets(ref, where)?.mapToSet { tgt ->
            tgt.locId
        }.orEmpty()

    private fun refTargets(ref: TACSymbol.Var, where: CmdPointer) =
        referenceAnalysis.refTargets[where]?.get(ref)

    private fun TACCmd.Move.modified(where: CmdPointer): Collection<TACSymbol.Var> = when(this) {
        is TACCmd.Move.VecPopBackCmd -> setOf(dst) + refToLocVars(ref, where)
        is TACCmd.Move.VecPushBackCmd -> refToLocVars(ref, where)
        is TACCmd.Move.WriteRefCmd -> refToLocVars(ref, where)
        else -> modifiedVars
    }

    private fun killVars(where: CmdPointer, vs: Collection<TACSymbol.Var>, m: MoveState): MoveState {
        val s = m.intState

        val killedState = vs.fold(s) { st, v ->
            qualifierManager.killLHS(v, null, st, where)
        }.s.removeAll(vs).let(::IntervalState)

        val killedVecs = m.vecState
            .removeAll(vs)
            .parallelUpdateValues { _, xs ->
                xs.killAll(vs)
            }
        return MoveState(killedState, killedVecs)
    }

    private fun stepMoveCmd(ptr: CmdPointer, cmd: TACCmd.Move, m: MoveState): MoveState {
        val toStep = killVars(ptr, cmd.modified(ptr), m)
        return when (cmd) {
            is TACCmd.Move.ReadRefCmd -> {
                refToLocVars(cmd.ref, ptr).monadicMap {
                    toStep.intState.s[it]
                }?.reduceOrNull(SimpleQualifiedInt::join)?.let { av ->
                    toStep.copy(intState = IntervalState(toStep.intState.s.put(cmd.dst, av)))
                } ?: toStep
            }

            is TACCmd.Move.VecLenCmd -> {
                val targets = refTargets(cmd.ref, ptr) ?: return toStep

                // Can't really do anything with a nested vector
                if (targets.any { it.path.contains(ReferenceAnalysis.PathComponent.VecElem) }) {
                    return toStep
                }

                val locs = refToLocVars(cmd.ref, ptr)

                // See if we can bound the length by searching the vec state for
                // all TACSymbols that might be the length of this vec.
                val intState = locs.monadicMap {
                    // For each possible loc, see if we recorded its length
                    m.vecState[it]
                        ?.tryAs<LocValue.Vec>()
                        ?.length
                }?.foldFirstOrNull { l, r -> l.join(r) }?.let {
                    // Update cmd.lhs with this value
                    IntervalState(m.intState.s.put(cmd.dst, it))
                } ?: IntervalState(m.intState.s.put(cmd.dst, SimpleQualifiedInt.nondet))

                val vecState = toStep.vecState.put(cmd.dst, LocValue.Length(treapSetOf(cmd.ref)))

                MoveState(intState, vecState)
            }

            is TACCmd.Move.VecPackCmd -> {
                toStep.copy(vecState = toStep.vecState.put(
                    cmd.dst, LocValue.Vec(toStep.intState.interpret(cmd.srcs.size.asTACSymbol()))
                ))
            }

            is TACCmd.Move.VecPopBackCmd -> {
                // Can search for singleton constant lengths and subtract 1
                toStep
            }

            is TACCmd.Move.VecPushBackCmd -> {
                // Can search for singleton constant lengths and add 1
                toStep
            }

            is TACCmd.Move.WriteRefCmd -> {
                val av = toStep.intState.s[cmd.src] ?: SimpleQualifiedInt.nondet
                val vs = refToLocVars(cmd.ref, ptr)
                if (vs.size == 1) {
                    return toStep.copy(intState = IntervalState(toStep.intState.s.put(vs.single(), av)))
                }

                val mayWrite = vs.associateWithNotNull { av }
                val joined = toStep.intState.s.parallelIntersect(mayWrite) { _, v1, v2 ->
                    v1.join(v2)
                }
                toStep.copy(intState = IntervalState(joined))
            }

            is TACCmd.Move.EqCmd -> {
                if (cmd.a.tag is Tag.Bits) {
                    val asLTACCmd = LTACCmdView<TACCmd.Simple.AssigningCmd.AssignExpCmd>(
                        LTACCmd(
                            ptr,
                            TACCmd.Simple.AssigningCmd.AssignExpCmd(
                                cmd.dst,
                                TACExpr.BinRel.Eq(cmd.a.asSym(), cmd.b.asSym())
                            )
                        )
                    )
                    simpleInterpreter
                        .statementInterpreter
                        .expressionInterpreter
                        .stepAssignEq(cmd.dst, cmd.a, cmd.b, toStep.intState, toStep.intState, toStep, asLTACCmd)
                        .let {
                            toStep.copy(intState = it)
                        }
                } else {
                    toStep
                }
            }

            else -> toStep
        }

    }

    fun step(l: MoveTACProgram.LCmd, s: MoveState): MoveState {
        return when (val cmd = l.cmd) {
            is TACCmd.Move -> {
               stepMoveCmd(l.ptr, cmd, s)
            }

            is TACCmd.Simple.AssigningCmd.AssignExpCmd -> {
                val state = simpleInterpreter.step(LTACCmd(l.ptr, cmd), s)
                if (cmd.rhs !is TACExpr.Sym.Var) {
                    return s.copy(intState = state)
                }
                val locToLen = s.vecState[cmd.rhs.s]?.let { s.vecState.put(cmd.lhs, it) } ?: s.vecState
                return MoveState(intState = state, vecState = locToLen)
            }

            is TACCmd.Simple.AssumeCmd ->
                s.copy(intState = simpleInterpreter.step(LTACCmd(l.ptr, cmd), s)).let {
                    meetLengths(l.ptr, it)
                }

            is TACCmd.Simple ->
                s.copy(intState = simpleInterpreter.step(LTACCmd(l.ptr, cmd), s))

            else ->
                `impossible!`
        }
    }

    private fun meetLengths(where: CmdPointer, st: MoveState): MoveState {
        // Now 'meet' the state by looing for x : Length(ptr) such that
        // ptr points to the singleton loc {l},
        // and if we have l : Vector, then refine the length there with x's abstraction
        val refinements = treapMapBuilderOf<TACSymbol.Var, SimpleQualifiedInt>()
        st.vecState.entries.forEach { (x, xv) ->
            val ref = xv.tryAs<LocValue.Length>()?.vecref?.singleOrNull() ?: return@forEach
            val v = refToLocVars(ref, where).uniqueOrNull() ?: return@forEach
            if (v.tag !is MoveTag.Vec) {
                return@forEach
            }
            val vecAbsLen = st.vecState[v]?.tryAs<LocValue.Vec>()?.length ?: SimpleQualifiedInt.nondet
            val abs = vecAbsLen.meet(st.intState.interpret(x))
            refinements.updateInPlace(v, abs) { oldAbs ->
                oldAbs.meet(abs)
            }
        }
        return st.copy(
            vecState = st.vecState.parallelMerge(
                refinements.mapValues { LocValue.Vec(it.value) }
            ) { _, a, b -> b ?: a }
        )
    }

    fun propagate(l: MoveTACProgram.LCmd, s: MoveState, pc: PathCondition): MoveState? =
        when (val cmd = l.cmd) {
            is TACCmd.Simple ->
                simpleInterpreter.propagate(LTACCmd(l.ptr, cmd), s, pc)?.let {
                    s.copy(intState = it)
                }

            else ->
                s
        }?.let { meetLengths(l.ptr, it) }
}
