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

import datastructures.stdcollections.*
import sbf.SolanaConfig
import sbf.cfg.*
import sbf.disassembler.*
import sbf.domains.*
import sbf.fixpoint.WtoBasedFixpointOptions
import sbf.fixpoint.WtoBasedFixpointSolver
import sbf.support.UnknownStackPointerError

class ScalarAnalysis<TNum: INumValue<TNum>, TOffset: IOffset<TOffset>>
    (cfg: SbfCFG,
     globalsMap: GlobalVariableMap,
     memSummaries: MemorySummaries,
     sbfTypeFac : ISbfTypeFactory<TNum, TOffset>,
     isEntryPoint:Boolean = true) : GenericScalarAnalysis<TNum, TOffset, ScalarDomain<TNum,TOffset>>(
            cfg, globalsMap, memSummaries, sbfTypeFac, ScalarDomainFactory(), isEntryPoint)


typealias TNumAdaptiveScalarAnalysis = ConstantSet
typealias TOffsetAdaptiveScalarAnalysis = ConstantSet

/**
 *  It runs first a scalar analysis where each register and stack content is over-approximated by a single value.
 *  Only if this analysis throws a [UnknownStackPointerError] exception then it repeats the analysis but this time by
 *  using a set of values.
 */
class AdaptiveScalarAnalysis
    (val cfg: SbfCFG,
     val globalsMap: GlobalVariableMap,
     val memSummaries: MemorySummaries,
     isEntryPoint:Boolean = true
): IAnalysis<ScalarDomain<TNumAdaptiveScalarAnalysis, TOffsetAdaptiveScalarAnalysis>> {
    private var sbfTypesFac: ISbfTypeFactory<TNumAdaptiveScalarAnalysis, TOffsetAdaptiveScalarAnalysis>
    private val domainFac = ScalarDomainFactory<TNumAdaptiveScalarAnalysis, TOffsetAdaptiveScalarAnalysis>()

    @Suppress("SwallowedException")
    private val scalarAnalysis = try {
        sbfTypesFac = ConstantSetSbfTypeFactory(1UL)
        GenericScalarAnalysis(cfg, globalsMap, memSummaries, sbfTypesFac, domainFac, isEntryPoint)
    } catch (e: UnknownStackPointerError) {
        sbf.sbfLogger.warn {
                "Scalar analysis was configured to track only a single value per stack offset. It cannot proceed without clearing the entire stack. " +
                "Re-running scalar analysis configured to track up to ${SolanaConfig.ScalarMaxVals.get()} values."
        }
        sbfTypesFac = ConstantSetSbfTypeFactory(SolanaConfig.ScalarMaxVals.get().toULong())
        GenericScalarAnalysis(cfg, globalsMap, memSummaries, sbfTypesFac, domainFac, isEntryPoint)
    }

    fun getSbfTypesFac() = sbfTypesFac
    override fun getPre(block: Label) = scalarAnalysis.getPre(block)
    override fun getPost(block: Label) = scalarAnalysis.getPost(block)
    override fun getCFG() = cfg
    override fun getMemorySummaries() = memSummaries
    override fun getGlobalVariableMap() = globalsMap
}

interface IScalarDomainFactory<TNum: INumValue<TNum>, TOffset: IOffset<TOffset>, ScalarDomain> {
    fun mkTop(fac: ISbfTypeFactory<TNum, TOffset>): ScalarDomain
    fun mkBottom(fac: ISbfTypeFactory<TNum, TOffset>): ScalarDomain
    /**
     *  Return the initial abstract state.
     *
     *  If [addPreconditions] is true then it adds some facts that are true when the SBF program
     *  is loaded (e.g., `r10` must point to the top of the stack)
     */
    fun init(fac: ISbfTypeFactory<TNum, TOffset>, addPreconditions: Boolean): ScalarDomain
}

class ScalarDomainFactory<TNum: INumValue<TNum>, TOffset: IOffset<TOffset>>: IScalarDomainFactory<TNum, TOffset, ScalarDomain<TNum, TOffset>> {
    override fun mkTop(fac: ISbfTypeFactory<TNum, TOffset>) = ScalarDomain.makeTop(fac)
    override fun mkBottom(fac: ISbfTypeFactory<TNum, TOffset>) = ScalarDomain.makeBottom(fac)
    override fun init(fac: ISbfTypeFactory<TNum, TOffset>, addPreconditions: Boolean) = ScalarDomain(fac, addPreconditions)
}

class ScalarStackStridePredicateDomainFactory<TNum: INumValue<TNum>, TOffset: IOffset<TOffset>>
    : IScalarDomainFactory<TNum, TOffset, ScalarStackStridePredicateDomain<TNum, TOffset>> {
    override fun mkTop(fac: ISbfTypeFactory<TNum, TOffset>) = ScalarStackStridePredicateDomain.makeTop(fac)
    override fun mkBottom(fac: ISbfTypeFactory<TNum, TOffset>) = ScalarStackStridePredicateDomain.makeBottom(fac)
    override fun init(fac: ISbfTypeFactory<TNum, TOffset>, addPreconditions: Boolean) = ScalarStackStridePredicateDomain(fac, addPreconditions)
}

open class GenericScalarAnalysis<TNum: INumValue<TNum>, TOffset: IOffset<TOffset>, ScalarDomain: AbstractDomain<ScalarDomain>>
    (val cfg: SbfCFG,
     val globalsMap: GlobalVariableMap,
     val memSummaries: MemorySummaries,
     private val sbfTypeFac : ISbfTypeFactory<TNum, TOffset>,
     private val domFac: IScalarDomainFactory<TNum, TOffset, ScalarDomain>,
     private val isEntryPoint:Boolean = true): IAnalysis<ScalarDomain> {

    private val preMap = mutableMapOf<Label, ScalarDomain>()
    private val postMap =  mutableMapOf<Label, ScalarDomain>()

    init { run() }

    override fun getPre(block:Label) = preMap[block]
    override fun getPost(block:Label) = postMap[block]
    override fun getCFG() = cfg
    override fun getMemorySummaries() = memSummaries
    override fun getGlobalVariableMap() = globalsMap

    private fun run() {
        val entry = cfg.getEntry()
        val bot = domFac.mkBottom(sbfTypeFac)
        val top = domFac.mkTop(sbfTypeFac)
        val solverOpts = WtoBasedFixpointOptions(2U,1U)
        val fixpo = WtoBasedFixpointSolver(bot, top, solverOpts, globalsMap, memSummaries)
        if (isEntryPoint) {
            preMap[entry.getLabel()] = domFac.init(sbfTypeFac, addPreconditions = true)
        }
        fixpo.solve(cfg, preMap, postMap, liveMapAtExit = null, processor = null)
    }
}
