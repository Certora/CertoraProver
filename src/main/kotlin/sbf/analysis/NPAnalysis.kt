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
import sbf.disassembler.GlobalVariableMap
import sbf.domains.*

data class NPDomainState<TNum: INumValue<TNum>, TOffset: IOffset<TOffset>>(
    val state: NPDomain<ScalarDomain<TNum, TOffset>, TNum, TOffset>) {
    companion object {
        fun <TNum: INumValue<TNum>, TOffset: IOffset<TOffset>> mkBottom() = NPDomainState<TNum, TOffset>(NPDomain.mkBottom())
        fun <TNum: INumValue<TNum>, TOffset: IOffset<TOffset>> join(x: NPDomainState<TNum, TOffset>, y: NPDomainState<TNum, TOffset>) =
            NPDomainState(x.state.join(y.state))
        fun <TNum: INumValue<TNum>, TOffset: IOffset<TOffset>> equiv(x: NPDomainState<TNum, TOffset>, y: NPDomainState<TNum, TOffset>) =
            x.state.lessOrEqual(y.state) && y.state.lessOrEqual(x.state)
    }
}

private fun <TNum, TOffset> lattice(): JoinLattice<NPDomainState<TNum, TOffset>>
    where TNum : INumValue<TNum>, TOffset : IOffset<TOffset> =
    object : JoinLattice<NPDomainState<TNum, TOffset>> {
        override fun join(x: NPDomainState<TNum, TOffset>, y: NPDomainState<TNum, TOffset>) = NPDomainState.join(x, y)
        override fun equiv(x: NPDomainState<TNum, TOffset>, y: NPDomainState<TNum, TOffset>) = NPDomainState.equiv(x,y)
    }

private typealias TNum = TNumAdaptiveScalarAnalysis
private typealias TOffset= TOffsetAdaptiveScalarAnalysis

class NPAnalysis(
    val cfg: MutableSbfCFG,
    globalsMap: GlobalVariableMap,
    memSummaries: MemorySummaries) :
    SbfBlockDataflowAnalysis<NPDomainState<TNum, TOffset>>(
        cfg,
        lattice(),
        NPDomainState.mkBottom(),
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
    private val fwdAnalysis = ScalarAnalysis(cfg, globalsMap, memSummaries, ConstantSetSbfTypeFactory(SolanaConfig.ScalarMaxVals.get().toULong()))
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
    }

    fun getPreconditionsAtEntry(label: Label): NPDomain<ScalarDomain<TNum, TOffset>, TNum, TOffset>? {
        return blockOut[label]?.state
    }

    fun contains(np: NPDomain<ScalarDomain<TNum, TOffset>, TNum, TOffset>, cond: Condition): Boolean {
        return np.contains(NPDomain.getLinCons(cond, vFac))
    }

    fun isBottom(np: NPDomain<ScalarDomain<TNum, TOffset>, TNum, TOffset>, locInst: LocatedSbfInstruction, cond: Condition): Boolean {
        return np.analyzeAssume(cond, locInst, vFac, registerTypes).isBottom()
    }

    fun populatePreconditionsAtInstruction(label:Label): Map<LocatedSbfInstruction, NPDomain<ScalarDomain<TNum, TOffset>, TNum, TOffset>>{
        val block = cfg.getBlock(label)
        return if (block != null) {
            var outVal = if (block.getInstructions().any{ it is SbfInstruction.Exit}) {
                NPDomain.mkTrue<ScalarDomain<TNum, TOffset>, TNum, TOffset>()
            } else {
                NPDomain.mkBottom()
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

    override fun transform(inState: NPDomainState<TNum, TOffset>, block: SbfBasicBlock): NPDomainState<TNum, TOffset> {
        val inNPVal = if (exits.contains(block.getLabel())) {
            NPDomain.mkTrue()
        } else {
            inState.state
        }
       return NPDomainState(inNPVal.analyze(block, vFac, registerTypes))
    }
}
