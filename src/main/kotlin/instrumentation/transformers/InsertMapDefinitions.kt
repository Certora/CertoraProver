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

package instrumentation.transformers

import analysis.CmdPointer
import analysis.forwardVolatileDagDataFlow
import com.certora.collect.*
import config.Config
import datastructures.stdcollections.*
import log.*
import tac.Tag
import utils.*
import utils.Color.Companion.blueBg
import utils.Color.Companion.magentaBg
import vc.data.*
import vc.data.TACCmd.Simple.AssigningCmd.AssignExpCmd
import vc.data.TACMeta.EVM_IMMUTABLE_ARRAY
import vc.data.TACMeta.EVM_MEMORY
import vc.data.tacexprutil.TACExprFactSimple
import vc.data.tacexprutil.asConstOrNull
import vc.data.tacexprutil.asVarOrNull
import java.math.BigInteger


private val logger = Logger(LoggerTypes.INIT_MAPS)

/**
 * Initializes unlinitialized bytemaps (memory, codedata) with havoc statements, or with [TACExpr.MapDefinition]s that
 * describe their initial state (e.g. all 0s in the case of memory).
 */
object InsertMapDefinitions {

    fun transform(code: CoreTACProgram): CoreTACProgram {
        val g = code.analysisCache.graph
        val patcher = ConcurrentPatchingProgram(code)

        val zeroMaps = mutableSetOf<TACSymbol.Var>()
        val boundMaps = mutableMapOf<TACSymbol.Var, TACSymbol>()
        val havocMaps = mutableSetOf<TACSymbol.Var>()

        forwardVolatileDagDataFlow<TreapSet<TACSymbol.Var>>(code) { b, predSets ->
            var initializedMaps = predSets.reduceOrNull { x, y -> x intersect y } ?: treapSetOf()

            g.lcmdSequence(b).forEach { (ptr, cmd) ->
                val rhsMaps = cmd.getFreeVarsOfRhsExtended()
                    .filter { it.tag is Tag.ByteMap }
                    .let { it.toTreapSet() - initializedMaps }
                rhsMaps.forEach { map ->
                    if (EVM_MEMORY in map.meta) {
                        runIf(!Config.HavocInitEVMMemory.get()) {
                            zeroMaps += map
                        }
                    } else {
                        map.meta[EVM_IMMUTABLE_ARRAY]?.sym?.let { bound ->
                            val oldBound = boundMaps.put(map, bound)
                            check(oldBound == null || oldBound == bound) {
                                "Map $map has two different bounds: $bound and $oldBound, new one is at $ptr, $cmd"
                            }
                        }
                    } ?: havocMaps.add(map)
                }
                initializedMaps += rhsMaps
                cmd.getLhs()?.takeIf { it.tag is Tag.ByteMap }?.let {
                    initializedMaps += it
                }
            }
            initializedMaps
        }

        val newCmds =
            zeroMaps.map {
                AssignExpCmd(it, TACExprFactUntyped.resetStore(Tag.ByteMap))
            } + boundMaps.map { (map, bound) ->
                bound.asVarOrNull?.let(patcher::addVar)
                val i = TACSymbol.Factory.getFreshAuxVar(
                    TACSymbol.Factory.AuxVarPurpose.MAP_DEF_INSERTER,
                    TACSymbol.Var("i", Tag.Bit256)
                ).asSym()
                val rhs = TACExpr.MapDefinition(
                    listOf(i),
                    TACExprFactSimple {
                        if (bound.asConstOrNull == BigInteger.ZERO) {
                            number(0)
                        } else {
                            ite(i lt bound.asSym(), unconstrained(Tag.Bit256), number(0))
                        }
                    },
                    Tag.ByteMap
                )
                AssignExpCmd(map, rhs)
            } + havocMaps.map { map ->
                TACCmd.Simple.AssigningCmd.AssignHavocCmd(map)
            }

        patcher.insertBefore(
            CmdPointer(g.rootBlockIds.single(), 0),
            newCmds
        )

        logger.debug {
            "zeroMaps = $zeroMaps\n" +
                "havocMaps = $havocMaps\n" +
                "boundMaps = $boundMaps\n"
        }

        logger.trace {
            patcher.debugPrinter()
                .extraLines {
                    it.cmd.freeVars()
                        .mapNotNull { it `to?` it.meta[EVM_IMMUTABLE_ARRAY] }
                        .map { it.magentaBg } +
                        it.cmd.freeVars()
                            .filter { EVM_MEMORY in it.meta }
                            .map { it.blueBg }
                }
                .toString(code, javaClass.simpleName)
        }

        return patcher.toCode()
    }
}
