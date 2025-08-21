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

@file:kotlinx.serialization.UseSerializers(BigIntegerSerializer::class)
package report.dumps

import analysis.LTACCmd
import analysis.controlflow.PathCounter
import datastructures.stdcollections.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import statistics.SDCollectorFactory
import statistics.SDFeatureKey
import statistics.recordAny
import utils.AmbiSerializable
import utils.BigIntegerSerializer
import utils.KSerializable
import vc.data.CoreTACProgram
import vc.data.TACExpr
import java.math.BigInteger

private val jsonBuilder = Json { prettyPrint = true }

/**
 * Contains all the statistics data about the TACProgram to be collected by
 * the SDCollector and presented in statsdata.json
 */
@KSerializable
data class TACStatistics(
    val pathCount: BigInteger,
    val probablyNonLinearCounts: Map<String, Int>,
    val bitwiseCounts: Map<String, Int>,
    val blockCount: Int,
    val commandCount: Int,
    val loadCount: Int,
    val storeCount: Int,
    val branchCount: Int,
    val totalBlockDifficulty: Int,
    val difficultCmds: Int
): AmbiSerializable {

    @KSerializable
    data class SourceContract(
        val name: String,
        val address: BigInteger,
        val bytecodeHash: BigInteger
    )

    @KSerializable
    data class SourceStatistics(
        val contract: SourceContract,
        val blockBytecodeCounts: Map<Int, Int>
    ) : AmbiSerializable

    companion object {
        operator fun invoke(program: CoreTACProgram): TACStatistics {
            val graph = program.analysisCache.graph
            val pathCount = let {
                val key = graph.roots.firstOrNull()?.ptr?.block
                val nullableCount = key?.let { k -> PathCounter(graph).pathCounts[k] }
                nullableCount ?: BigInteger.ZERO
            }
            val probablyNonLinearCounts = countExprBy(graph.commands) { tacExpr ->
                when (tacExpr) {
                    is TACExpr.BinOp.Div,
                    is TACExpr.BinOp.Exponent,
                    is TACExpr.BinOp.IntDiv,
                    is TACExpr.BinOp.IntExponent,
                    is TACExpr.BinOp.Mod,
                    is TACExpr.BinOp.SDiv,
                    is TACExpr.BinOp.SMod,
                    is TACExpr.QuantifiedFormula,
                    is TACExpr.Vec.IntMul,
                    is TACExpr.Vec.Mul -> true

                    else -> false
                }
            }
            val bitwiseCounts = countExprBy(graph.commands) { tacExpr ->
                when (tacExpr) {
                    is TACExpr.BinOp.BWAnd,
                    is TACExpr.BinOp.BWOr,
                    is TACExpr.BinOp.BWXOr,
                    is TACExpr.BinOp.ShiftLeft,
                    is TACExpr.BinOp.ShiftRightArithmetical,
                    is TACExpr.BinOp.ShiftRightLogical,
                        -> true

                    else -> false
                }
            }
            val blockCount = graph.code.keys.size
            val commandCount = graph.code.values.sumOf { it.size }
            val loadCount = countExprBy(graph.commands) { it is TACExpr.Select }.values.sumOf { it }
            val storeCount = countExprBy(graph.commands) {
                when (it) {
                    is TACExpr.LongStore,
                    is TACExpr.MultiDimStore,
                    is TACExpr.Store -> true

                    else -> false
                }
            }.values.sumOf { it }
            val branchCount = graph.blockGraph.filterValues { it.size > 1 }.size

            val countDifficultOps = CountDifficultOps(program)
            val difficultBlocks = countDifficultOps.getDifficultBlocks().size
            val difficultCmds = countDifficultOps.getDifficultCmds().size

            return TACStatistics(
                pathCount,
                probablyNonLinearCounts,
                bitwiseCounts,
                blockCount,
                commandCount,
                loadCount,
                storeCount,
                branchCount,
                difficultBlocks,
                difficultCmds
            )
        }

        private fun countExprBy(commands: Sequence<LTACCmd>, filter: (TACExpr) -> Boolean): Map<String, Int> {
            val counts: MutableMap<String, Int> = mutableMapOf()
            for (c in commands) {
                c.cmd.subExprs()
                    .filter(filter)
                    .forEach { tacExpr ->
                        val name = tacExpr.javaClass.simpleName + "_" + tacExpr.tag.toString()
                        counts.compute(name) { _, v -> 1 + (v ?: 0) }
                    }
            }
            return counts
        }
    }

    override fun toString(): String {
        return jsonBuilder.encodeToString(this)
    }

    fun reportToStats(key: SDFeatureKey) {
        val collector = SDCollectorFactory.collector()
        collector.recordAny(this, "tac-statistics", key)
    }
}
