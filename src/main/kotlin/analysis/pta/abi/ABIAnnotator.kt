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

package analysis.pta.abi

import datastructures.stdcollections.*
import allocator.Allocator
import allocator.GeneratedBy
import analysis.CmdPointer
import analysis.pta.ABICodeFinder
import analysis.pta.BoundaryInformation
import com.certora.collect.*
import instrumentation.transformers.InternalFunctionRerouter
import tac.MetaKey
import tac.NBId
import utils.*
import vc.data.*
import kotlin.collections.set

@KSerializable
@Treapable
data class SerializationRangeMarker(
    val sources: Set<ABICodeFinder.Source>,
    @GeneratedBy(Allocator.Id.ABI_RANGE, source = true)
    val id: Int
) : AmbiSerializable, UniqueIdEntity<SerializationRangeMarker> {
    override fun mapId(f: (Any, Int, () -> Int) -> Int): SerializationRangeMarker =
        copy(
            id = f(Allocator.Id.ABI_RANGE, id) { Allocator.getFreshId(Allocator.Id.ABI_RANGE) },
            sources = sources.map { it.mapId(f) }.toSet()
        )
}

class ABIAnnotator(
    private val completionPoints: Map<CmdPointer, TACCmd.Simple.AnnotationCmd.Annotation<*>>,
    boundaries: Collection<BoundaryInformation<ABICodeFinder.Source>>,
    private val relatedCommands: Map<CmdPointer, Set<ABICodeFinder.Source>>,
    private val blocks: Map<NBId, Set<ABICodeFinder.Source>>,
    private val reroutes: Map<CmdPointer, InternalFunctionRerouter.RerouteToMaterialize>
) {

    private val end : Map<CmdPointer, SerializationRangeMarker>
    private val start : Map<CmdPointer, SerializationRangeMarker>

    init {
        val endInit = mutableMapOf<CmdPointer, SerializationRangeMarker>()
        val startInit = mutableMapOf<CmdPointer, SerializationRangeMarker>()

        for(b in boundaries) {
            val id = Allocator.getFreshId(Allocator.Id.ABI_RANGE)
            startInit[b.start] = SerializationRangeMarker(
                sources = b.group,
                id = id
            )
            endInit[b.end] = SerializationRangeMarker(
                sources = b.group,
                id = id
            )
        }

        end = endInit
        start = startInit
    }
    companion object {
        val CODE_FOR = MetaKey.Nothing("abi.code.for")
        val REGION_START = MetaKey<SerializationRangeMarker>("abi.region.start")
        val REGION_END = MetaKey<SerializationRangeMarker>("abi.region.end")
    }

    fun annotate(
        p: SimplePatchingProgram
    ) {
        p.addVarDecl(TACKeyword.CALLDATA.toVar())
        val markCmd = makeMarker()
        val cmdPointers = mutableSetOf<CmdPointer>()
        blocks.keys.flatMapTo(cmdPointers) {
            if (p.isBlockStillInGraph(it)) {
                (0..p.originalCode[it]!!.lastIndex).map { ind ->
                    CmdPointer(it, ind)
                }
            } else {
                listOf()
            }
        }
        cmdPointers.addAll(relatedCommands.keys)
        cmdPointers.addAll(completionPoints.keys)
        cmdPointers.addAll(start.keys)
        cmdPointers.addAll(end.keys)

        cmdPointers.forEach {
            p.replace(it, markCmd)
        }
        for((where, annot) in reroutes) {
            p.replaceCommand(where, listOf(
                TACCmd.Simple.AnnotationCmd(
                    InternalFunctionRerouter.RerouteToMaterialize.META_KEY,
                    annot
                )
            ))
        }
    }

    private fun makeMarker(): (CmdPointer, TACCmd.Simple) -> List<TACCmd.Simple> {
        return blk@{ where: CmdPointer, simp: TACCmd.Simple ->
            val annot = if (blocks.contains(where.block) || relatedCommands.contains(where)) {
                simp.plusMeta(CODE_FOR)
            } else {
                simp
            }
            if (where !in start && where !in end && where !in completionPoints) {
                return@blk listOf(annot)
            }
            val l = mutableListOf<TACCmd.Simple>()
            if(where in end) {
                l.add(
                    TACCmd.Simple.AnnotationCmd(
                        REGION_END,
                        end[where]!!
                    )
                )
            }
            if(where in start) {
                l.add(
                    TACCmd.Simple.AnnotationCmd(
                        REGION_START,
                        start[where]!!
                    )
                )
            }
            l.add(annot)
            if(where in completionPoints) {
                l.add(
                    TACCmd.Simple.AnnotationCmd(
                        completionPoints[where]!!
                    )
                )
            }
            l
        }
    }
}
