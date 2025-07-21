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

package analysis.split

import analysis.storage.DisplayPaths
import analysis.storage.StorageAnalysis
import analysis.storage.StorageAnalysis.AnalysisPath
import analysis.storage.StorageAnalysisResult
import analysis.storage.StorageAnalysisResult.NonIndexedPath
import datastructures.stdcollections.*
import evm.EVM_BYTE_SIZE
import tac.MetaMap
import utils.monadicMap
import vc.data.ScalarizationSort
import vc.data.TACMeta
import vc.data.TACSymbol
import vc.data.find
import java.math.BigInteger

/**
 * Generates the meta information we need when splitting storage.
 */
class StorageMetaInfo(private val cx: SplitContext) {

    fun pathVarMeta(path: NonIndexedPath, range: BitRange.NonEmpty, equivClass: Set<NonIndexedPath>): MetaMap {
        check(path in equivClass)

        var metaMap = MetaMap()
        metaMap += TACMeta.BIT_WIDTH to range.width
        metaMap += path.storageBase().storageKey to cx.contract.instanceId

        val innerSort = if (path is NonIndexedPath.Root) {
            ScalarizationSort.Split(idx = path.slot)
        } else {
            ScalarizationSort.UnscalarizedBuffer
        }
        metaMap += TACMeta.SCALARIZATION_SORT to
            if (range == BitRange.all) {
                innerSort
            } else {
                ScalarizationSort.Packed(start = range.lowBit, packedStart = innerSort)
            }

        cx.layout.integralTypeAt(path, range)?.let { type ->
            metaMap += TACMeta.STORAGE_TYPE to type.toDescriptor()
            if (path is NonIndexedPath.Root) {
                metaMap += TACMeta.DISPLAY_PATHS to DisplayPaths(
                    setOf(cx.layout.toDisplayPath(AnalysisPath.Root(path.slot, path.base), range)!!)
                )
            }
        }

        metaMap += TACMeta.STABLE_STORAGE_PATH to path
        metaMap += TACMeta.STABLE_STORAGE_FAMILY to StorageAnalysisResult.StoragePaths(equivClass)
        return metaMap
    }

    fun addLocationMeta(v: TACSymbol, range: BitRange.NonEmpty): TACSymbol {
        if (v !is TACSymbol.Var) {
            return v
        }
        var metaMap = MetaMap()
        val originalAccessPaths = v.meta.find(TACMeta.ACCESS_PATHS)
            ?: return v

        originalAccessPaths.paths.monadicMap { path ->
            cx.layout.toDisplayPath(path, range)
        }?.let { displayPaths ->
            metaMap += TACMeta.DISPLAY_PATHS to DisplayPaths(displayPaths.toSet())
        }

        if (range != BitRange.all) {
            metaMap += TACMeta.ACCESS_PATHS to originalAccessPaths.map {
                if (it is AnalysisPath.StructAccess) {
                    AnalysisPath.StructAccess(
                        it.base,
                        StorageAnalysis.Offset.Bytes(range.lowBit.toBigInteger() / EVM_BYTE_SIZE + it.offset.bytes)
                    )
                } else {
                    it
                }
            }
        }
        return v.withMeta { v.meta + metaMap }
    }

    companion object {
        fun changeArrayIndex(meta: MetaMap, newIndex: TACSymbol): MetaMap {
            val originalPaths = meta.find(TACMeta.ACCESS_PATHS) ?: return meta
            val newPaths =
                originalPaths.map { struct ->
                    require(struct is AnalysisPath.StructAccess)
                    val array = struct.base
                    require(array is AnalysisPath.ArrayLikePath)
                    val copy =
                        when (array) {
                            is AnalysisPath.ArrayAccess -> array.copy(index = newIndex)
                            is AnalysisPath.StaticArrayAccess -> array.copy(index = newIndex)
                        }
                    AnalysisPath.StructAccess(copy, BigInteger.ZERO)
                }
            return meta + (TACMeta.ACCESS_PATHS to newPaths)
        }
    }

}
