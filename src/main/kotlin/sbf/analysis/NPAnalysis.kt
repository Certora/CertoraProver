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

package sbf.analysis

import analysis.Direction
import analysis.JoinLattice
import sbf.cfg.*
import sbf.disassembler.Label
import datastructures.stdcollections.*
import sbf.SolanaConfig
import sbf.disassembler.GlobalVariables
import sbf.domains.*

/**
 * This **backward** analysis computes a fixpoint using the [NPDomain].
 *
 * The [NPDomain] computes all the necessary preconditions (NP) at the entry of the CFG to reach every assertion in the CFG.
 * These NPs can be used to refine the forward invariants, by excluding abstract states that cannot reach any assertion.
 *
 * For the fixpoint engine we use the existing [BlockDataFlowAnalysis].
 * Alternatively, we could have also used the WTO-based fixpoint and reverse the CFG.
 */
data class NPDomainState<TNum: INumValue<TNum>, TOffset: IOffset<TOffset>, ScalarDomain>(
    val state: NPDomain<ScalarDomain, TNum, TOffset>)
    where ScalarDomain: AbstractDomain<ScalarDomain>, ScalarDomain: ScalarValueProvider<TNum, TOffset> {

    companion object {
        fun <TNum: INumValue<TNum>, TOffset: IOffset<TOffset>, ScalarDomain> mkBottom(
            sbfTypeFac: ISbfTypeFactory<TNum, TOffset>
        ) where ScalarDomain: AbstractDomain<ScalarDomain>, ScalarDomain: ScalarValueProvider<TNum, TOffset> =
            NPDomainState<TNum, TOffset, ScalarDomain>(NPDomain.mkBottom(sbfTypeFac))

        fun <TNum: INumValue<TNum>, TOffset: IOffset<TOffset>, ScalarDomain> join(
            x: NPDomainState<TNum, TOffset, ScalarDomain>, y: NPDomainState<TNum, TOffset, ScalarDomain>
        ) where ScalarDomain: AbstractDomain<ScalarDomain>, ScalarDomain: ScalarValueProvider<TNum, TOffset> =
            NPDomainState(x.state.join(y.state))

        fun <TNum: INumValue<TNum>, TOffset: IOffset<TOffset>, ScalarDomain> equiv(
            x: NPDomainState<TNum, TOffset, ScalarDomain>, y: NPDomainState<TNum, TOffset, ScalarDomain>
        ) where ScalarDomain: AbstractDomain<ScalarDomain>, ScalarDomain: ScalarValueProvider<TNum, TOffset> =
            x.state.lessOrEqual(y.state) && y.state.lessOrEqual(x.state)
    }
}

private fun <TNum, TOffset, ScalarDomain> lattice(): JoinLattice<NPDomainState<TNum, TOffset, ScalarDomain>>
    where TNum : INumValue<TNum>, TOffset : IOffset<TOffset>,
          ScalarDomain: AbstractDomain<ScalarDomain>, ScalarDomain: ScalarValueProvider<TNum, TOffset> =
    object : JoinLattice<NPDomainState<TNum, TOffset, ScalarDomain>> {
        override fun join(x: NPDomainState<TNum, TOffset, ScalarDomain>, y: NPDomainState<TNum, TOffset, ScalarDomain>) = NPDomainState.join(x, y)
        override fun equiv(x: NPDomainState<TNum, TOffset, ScalarDomain>, y: NPDomainState<TNum, TOffset, ScalarDomain>) = NPDomainState.equiv(x,y)
    }

// For simplicity, [NPAnalysis] is not parametric
// We choose here the scalar domain used by the forward analysis
private typealias TNum = ConstantSet
private typealias TOffset= ConstantSet
private typealias TScalarDomain = ScalarDomain<TNum, TOffset>

typealias NPDomainT = NPDomain<TScalarDomain, TNum, TOffset>
private typealias NPDomainStateT = NPDomainState<TNum, TOffset, TScalarDomain>

class NPAnalysis(
    val cfg: MutableSbfCFG,
    globals: GlobalVariables,
    memSummaries: MemorySummaries,
    val sbfTypeFac: ISbfTypeFactory<TNum, TOffset> = ConstantSetSbfTypeFactory(SolanaConfig.ScalarMaxVals.get().toULong())
) :
    SbfBlockDataflowAnalysis<NPDomainStateT>(
        cfg,
        lattice(),
        NPDomainState.mkBottom(sbfTypeFac),
        Direction.BACKWARD
    )  {

    /** Used by the NPDomain to represent contents of stack slots **/
    private val vFac = VariableFactory()
    /**
     * We run a forward analysis for two reasons:
     * 1) the backward analysis needs to know whether a pointer points to the
     *    stack or not.
     * 2) remove unreachable blocks.
     *    Note that to make the analysis more precise, we use set-value abstraction in scalar analysis.
     **/
    private val fwdAnalysis = ScalarAnalysis(cfg, globals, memSummaries, sbfTypeFac)
    val registerTypes = AnalysisRegisterTypes(fwdAnalysis)
    /** Exit blocks of the cfg **/
    val exits: MutableSet<Label> = mutableSetOf()

    init {
        // Annotate the cfg with extra info that might help analysis precision
        propagateAssumptions(cfg, registerTypes) /* this requires cfg to be mutable */

        // Collect exits of the cfg
        for (b in cfg.getBlocks().values) {
            if (b.getInstructions().any {inst -> inst.isAssertOrSatisfy()}) {
                exits.add(b.getLabel())
            }
        }

        // run the backward analysis
        runAnalysis()

        // Remove the annotations inserted by `propagateAssumptions`
        cfg.removeAnnotations(listOf(SbfMeta.EQUALITY_REG_AND_STACK))
    }

    fun getPreconditionsAtEntry(label: Label): NPDomainT? {
        return blockOut[label]?.state
    }

    fun contains(np: NPDomainT, cond: Condition): Boolean {
        return np.contains(NPDomain.getLinCons(cond, vFac))
    }

    fun isBottom(np: NPDomainT, locInst: LocatedSbfInstruction, cond: Condition, useForwardInvariants: Boolean = true): Boolean {
        return np.analyzeAssume(cond, locInst, vFac, if (useForwardInvariants) {registerTypes} else {null} ).isBottom()
    }

    fun populatePreconditionsAtInstruction(label:Label): Map<LocatedSbfInstruction, NPDomainT>{
        val block = cfg.getBlock(label)
        return if (block != null) {
            var outVal = if (block.getInstructions().any{ it is SbfInstruction.Exit}) {
                NPDomain.mkTrue<TScalarDomain, TNum, TOffset>(sbfTypeFac)
            } else {
                NPDomain.mkBottom<TScalarDomain, TNum, TOffset>(sbfTypeFac)
            }
            for (succ in block.getSuccs()) {
                val succVal = getPreconditionsAtEntry(succ.getLabel())
                if (succVal != null) {
                    outVal = outVal.join(succVal)
                }
            }
            outVal.analyze(block, vFac, registerTypes, propagateOnlyFromAsserts = true, computeInstMap = true).second
        } else {
            mutableMapOf()
        }
    }

    override fun transform(inState: NPDomainStateT, block: SbfBasicBlock): NPDomainStateT {
        val inNPVal = if (exits.contains(block.getLabel())) {
            NPDomain.mkTrue<TScalarDomain, TNum, TOffset>(sbfTypeFac)
        } else {
            inState.state
        }
       return NPDomainState(inNPVal.analyze(block, vFac, registerTypes))
    }
}
