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

package report.calltrace.generator

import cli.Ecosystem
import config.Config
import report.calltrace.CallEndStatus
import report.calltrace.CallTrace
import report.calltrace.formatter.CallTraceValueFormatter
import scene.ISceneIdentifiers
import solver.CounterexampleModel
import spec.cvlast.IRule
import vc.data.CoreTACProgram

/**
 * Generates the call trace for [rule] by traversing the control flow graph of the program [program],
 * in topological order. Only blocks that were chosen by the counter-example [model] are visited.
 * The traversal records all the call instances and method summaries that are encountered using a stack,
 * as well as passing of parameters, return values, and return status ([CallEndStatus]).
 * The traversal ends when the first violated assert command is reached.
 */
fun generateCallTrace(
    rule: IRule,
    model: CounterexampleModel,
    program: CoreTACProgram,
    formatter: CallTraceValueFormatter,
    scene: ISceneIdentifiers,
    ruleCallString: String,
): CallTrace {
    val generator: CallTraceGenerator = when (Config.ActiveEcosystem.get()) {
        Ecosystem.EVM -> EVMCallTraceGenerator(rule.declarationId, model, program, formatter, scene, ruleCallString)
        Ecosystem.SOLANA -> SolanaCallTraceGenerator(rule.declarationId, model, program, formatter, scene, ruleCallString)
        Ecosystem.SOROBAN -> WasmCallTraceGenerator(rule.declarationId, model, program, formatter, scene, ruleCallString)
    }
    return generator.safeGenerate()
}
