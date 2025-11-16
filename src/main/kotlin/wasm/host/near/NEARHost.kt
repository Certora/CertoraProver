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

package wasm.host.near

import datastructures.stdcollections.*
import analysis.CommandWithRequiredDecls
import analysis.opt.ConstantPropagator
import analysis.opt.ConstantPropagatorAndSimplifier
import analysis.split.BoolOptimizer
import config.ReportTypes
import instrumentation.transformers.TACDSA
import tac.generation.ITESummary
import vc.data.CoreTACProgram
import vc.data.TACCmd
import vc.data.TACSymbol
import verifier.CoreToCoreTransformer
import wasm.host.WasmHost
import wasm.host.near.opt.EliminateTrivialDrops
import wasm.ir.WasmName
import wasm.ir.WasmProgram

object NEARHost : WasmHost {
    override fun init(): CommandWithRequiredDecls<TACCmd.Simple> =
        CommandWithRequiredDecls()

    override fun importer(program: WasmProgram): WasmHost.Importer = object : WasmHost.Importer {
        override fun resolve(id: WasmName): Boolean {
            return false
        }

        override fun importFunc(
            id: WasmName,
            args: List<TACSymbol>,
            retVar: TACSymbol.Var?
        ): CommandWithRequiredDecls<TACCmd.Simple>? {
            return null
        }
    }

    override fun applyPreUnrollTransforms(tac: CoreTACProgram.Linear, wasm: WasmProgram): CoreTACProgram.Linear =
        tac
            .map(CoreToCoreTransformer(ReportTypes.MATERIALIZE_CONTROL_FLOW, ITESummary::materialize))
            .map(CoreToCoreTransformer(ReportTypes.ELIMINATE_DROPS, EliminateTrivialDrops::transform))

    override fun applyOptimizations(tac: CoreTACProgram.Linear, wasm: WasmProgram) =
        tac
            .mapIfAllowed(CoreToCoreTransformer(ReportTypes.OPTIMIZE_BOOL_VARIABLES) { BoolOptimizer(it).go() })
            .mapIfAllowed(CoreToCoreTransformer(ReportTypes.PROPAGATOR_SIMPLIFIER) { ConstantPropagatorAndSimplifier(it).rewrite() })
            .mapIfAllowed(CoreToCoreTransformer(ReportTypes.OPTIMIZE) { ConstantPropagator.propagateConstants(it,
                setOf()
            ) })
            .mapIfAllowed(CoreToCoreTransformer(ReportTypes.ALIAS, NEARStackOptimizer::transform))
            .mapIfAllowed(CoreToCoreTransformer(ReportTypes.DSA, TACDSA::simplify))
            .mapIfAllowed(CoreToCoreTransformer(ReportTypes.PROPAGATOR_SIMPLIFIER) { ConstantPropagatorAndSimplifier(it).rewrite() })
            .mapIfAllowed(CoreToCoreTransformer(ReportTypes.OPTIMIZE) { ConstantPropagator.propagateConstants(it, setOf()) })

}
