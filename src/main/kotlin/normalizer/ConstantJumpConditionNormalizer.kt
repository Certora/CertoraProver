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

package normalizer

import analysis.PatternDSL
import analysis.PatternMatcher
import analysis.getNaturalLoops
import analysis.maybeNarrow
import utils.mapNotNull
import utils.mapToSet
import utils.`to?`
import vc.data.CoreTACProgram
import vc.data.TACCmd
import vc.data.TACSymbol

/**
 * Checks whether jump conditions that are not loop heads have conditions of the form `a == b`
 * where both a and b are known constants. In this case, we can prune branches by evaluating `a == b`
 */
object ConstantJumpConditionNormalizer {
    private val constantCond = PatternDSL.build {
        (Const `==` Const).withAction { a, b -> a to b }
    }

    fun normalize(c: CoreTACProgram) : CoreTACProgram {
        val loopHeads = getNaturalLoops(c.analysisCache.graph).mapToSet {
            it.head
        }
        val comp = PatternMatcher.compilePattern(c.analysisCache.graph, constantCond)
        val toSelect = c.parallelLtacStream().mapNotNull {
            it.maybeNarrow<TACCmd.Simple.JumpiCmd>()?.takeIf {
                it.cmd.cond is TACSymbol.Var && it.ptr.block !in loopHeads
            }
        }.mapNotNull {
            it `to?` comp.query(it.cmd.cond as TACSymbol.Var, it.wrapped).toNullableResult()
        }.map {
            it.first to (it.second.first == it.second.second)
        }.toList()
        if(toSelect.isEmpty()) {
            return c
        }
        return c.patching { patcher ->
            patcher.selectConditionalEdges(toSelect)
        }
    }
}
