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
import sbf.support.DerefOfAbsoluteAddressError
import sbf.support.PointerAnalysisError
import sbf.support.UnknownStackPointerError

class ScalarAnalysis<TNum: INumValue<TNum>, TOffset: IOffset<TOffset>>
    (cfg: SbfCFG,
     globals: GlobalVariables,
     memSummaries: MemorySummaries,
     sbfTypeFac : ISbfTypeFactory<TNum, TOffset>,
     isEntryPoint:Boolean = true) : GenericScalarAnalysis<TNum, TOffset, ScalarDomain<TNum,TOffset>>(
            cfg, globals, memSummaries, sbfTypeFac, ScalarDomainFactory(), isEntryPoint)


typealias TNumAdaptiveScalarAnalysis = ConstantSet
typealias TOffsetAdaptiveScalarAnalysis = ConstantSet

/**
 *  It runs first a scalar analysis where each register and stack content is over-approximated by a single value.
 *  Only if this analysis throws a [UnknownStackPointerError] or [DerefOfAbsoluteAddressError] exception
 *  then it repeats the analysis but this time by using a set of values.
 */
class AdaptiveScalarAnalysis
    (val cfg: SbfCFG,
     val globals: GlobalVariables,
     val memSummaries: MemorySummaries,
     isEntryPoint:Boolean = true
): IAnalysis<ScalarDomain<TNumAdaptiveScalarAnalysis, TOffsetAdaptiveScalarAnalysis>> {
    private var sbfTypesFac: ISbfTypeFactory<TNumAdaptiveScalarAnalysis, TOffsetAdaptiveScalarAnalysis>
    private val domainFac = ScalarDomainFactory<TNumAdaptiveScalarAnalysis, TOffsetAdaptiveScalarAnalysis>()

    @Suppress("SwallowedException")
    private val scalarAnalysis = try {
        sbfTypesFac = ConstantSetSbfTypeFactory(1UL)
        GenericScalarAnalysis(cfg, globals, memSummaries, sbfTypesFac, domainFac, isEntryPoint)
    } catch (e: PointerAnalysisError) {
        when(e) {
            is UnknownStackPointerError,
            is DerefOfAbsoluteAddressError -> {
                sbf.sbfLogger.warn {
                    "Scalar analysis was configured to track only a single value per stack offset. It cannot proceed without clearing the entire stack. " +
                        "Re-running scalar analysis configured to track up to ${SolanaConfig.ScalarMaxVals.get()} values."
                }
                sbfTypesFac = ConstantSetSbfTypeFactory(SolanaConfig.ScalarMaxVals.get().toULong())
                GenericScalarAnalysis(cfg, globals, memSummaries, sbfTypesFac, domainFac, isEntryPoint)
            }
            else -> throw e
        }
    }


    fun getSbfTypesFac() = sbfTypesFac
    override fun getPre(block: Label) = scalarAnalysis.getPre(block)
    override fun getPost(block: Label) = scalarAnalysis.getPost(block)
    override fun getCFG() = cfg
    override fun getMemorySummaries() = memSummaries
    override fun getGlobalVariableMap() = globals
}

open class GenericScalarAnalysis<TNum: INumValue<TNum>, TOffset: IOffset<TOffset>, ScalarDomain: AbstractDomain<ScalarDomain>>
    (val cfg: SbfCFG,
     val globals: GlobalVariables,
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
    override fun getGlobalVariableMap() = globals

    private fun run() {
        val entry = cfg.getEntry()
        val globalState = GlobalState(globals, memSummaries)
        val bot = domFac.mkBottom(sbfTypeFac, globalState)
        val top = domFac.mkTop(sbfTypeFac, globalState)
        val solverOpts = WtoBasedFixpointOptions(2U,1U)
        val fixpo = WtoBasedFixpointSolver(bot, top, solverOpts)
        if (isEntryPoint) {
            preMap[entry.getLabel()] = domFac.init(sbfTypeFac, globalState, addPreconditions = true)
        }
        fixpo.solve(cfg, preMap, postMap, liveMapAtExit = null, processor = null)
    }
}
