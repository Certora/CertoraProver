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

@file:kotlinx.serialization.UseSerializers(utils.BigIntegerSerializer::class)
package analysis.storage

import analysis.CmdPointer
import analysis.numeric.IntValue
import analysis.storage.StorageAnalysis.AnalysisPath
import analysis.storage.StorageAnalysisResult.NonIndexedPath
import com.certora.collect.*
import tac.NBId
import utils.AmbiSerializable
import utils.KSerializable
import utils.`impossible!`
import utils.unsupported
import vc.data.TACSymbol
import vc.data.TransformableSymEntityWithRlxSupport
import java.math.BigInteger

sealed class StorageAnalysisResult {

    /** The correctness of the storage analysis depends on
     * side conditions being satisfied. This denotes the
     * condition that [v] \in [range].
     */
    data class SideCondition(val v: TACSymbol, val range: IntValue)

    @KSerializable
    @Treapable
    data class AccessPaths(val paths: Set<AnalysisPath>) : AmbiSerializable,
        TransformableSymEntityWithRlxSupport<AccessPaths> {

        fun getUsedVariables(): Set<TACSymbol.Var> = paths.flatMap { path -> path.getUsedVariables() }.toSet()
        fun map(f: (AnalysisPath) -> AnalysisPath) = this.copy(paths = paths.map(f).toSet())

        override fun transformSymbols(f: (TACSymbol) -> TACSymbol): AccessPaths {
            tailrec fun mapAccess(x: AnalysisPath, cont: (AnalysisPath) -> AnalysisPath) : AnalysisPath {
                return when(x) {
                    is AnalysisPath.ArrayAccess -> {
                        mapAccess(x.base) {
                            cont(AnalysisPath.ArrayAccess(
                                base = it,
                                index = x.index?.let { f(it) },
                                baseSlot = f(x.baseSlot)
                            ))
                        }
                    }
                    is AnalysisPath.MapAccess -> {
                        mapAccess(x.base) {
                            cont(AnalysisPath.MapAccess(
                                base = it,
                                key = f(x.key),
                                hashResult = f(x.hashResult),
                                baseSlot = f(x.baseSlot)
                            ))
                        }
                    }
                    is AnalysisPath.Root -> cont(x)
                    is AnalysisPath.StaticArrayAccess -> {
                        mapAccess(x.base) {
                            cont(AnalysisPath.StaticArrayAccess(
                                base = it,
                                index = x.index?.let(f)
                            ))
                        }
                    }
                    is AnalysisPath.StructAccess -> {
                        mapAccess(x.base) {
                            cont(AnalysisPath.StructAccess(
                                base = it,
                                offset = x.offset
                            ))
                        }
                    }
                    is AnalysisPath.WordOffset -> {
                        mapAccess(x.parent) {
                            cont(AnalysisPath.WordOffset(
                                parent = it,
                                constOffset = x.constOffset
                            ))
                        }
                    }
                }
            }

            return this@AccessPaths.map { path ->
                mapAccess(path) { it }
            }
        }


    }

    @KSerializable
    @Treapable
    /**
     * An abstraction representing the pointer values used at a particular storage access.
     * (In particular, if [storagePaths] is empty then it must be the case that the
     *  related command was unreachable)
     */
    data class StoragePaths(val storagePaths: Set<NonIndexedPath>): AmbiSerializable

    @KSerializable
    @Treapable
    sealed class NonIndexedPath: AmbiSerializable {
        @KSerializable
        data class Root(val slot: BigInteger) : NonIndexedPath() {
            override fun toString(): String = "Root_slot$slot"
        }
        @KSerializable
        data class MapAccess(val base: NonIndexedPath) : NonIndexedPath() {
            override fun toString(): String = "MapAccess_base.$base"
        }
        @KSerializable
        data class StructAccess(val base: NonIndexedPath, val offset: BigInteger) : NonIndexedPath() {
            override fun toString(): String = "StructAccess_offset${offset}_base.${base}"
        }
        @KSerializable
        data class ArrayAccess(override val base: NonIndexedPath) : NonIndexedPath(), ArrayLikePath {
            override fun toString(): String = "ArrayAccess_base.$base"
        }
        @KSerializable
        data class StaticArrayAccess(override val base: NonIndexedPath) : NonIndexedPath(), ArrayLikePath {
            override fun toString(): String = "StaticArrayAccess_base.$base"
        }

        sealed interface ArrayLikePath {
            val base: NonIndexedPath
        }
    }

    data class JoinInstrumentation(
        val flagSet: Map<Pair<NBId, NBId>, Map<TACSymbol.Var, TACSymbol>>,
    )

    data class HashInstrumentation(
        val hashResults: Map<Pair<Set<CmdPointer>, TACSymbol>, TACSymbol.Var>,
    )

    data class Complete(
        val contractTree: Set<StorageTree.Root>,
        val accessedPaths: Map<CmdPointer, AccessPaths>,
        val joinInstrumentation: JoinInstrumentation,
        val hashInstrumentation: HashInstrumentation,
        val sideConditions: Map<CmdPointer, SideCondition>,
        val unreachable: Set<NBId>
    ) : StorageAnalysisResult()

    /* The analysis was skipped because the contract is a library */
    data object SkippedLibrary : StorageAnalysisResult()

    data class Failure(val reason: Throwable) : StorageAnalysisResult()
}

object StorageTree {
    data class Root(val slot: BigInteger, val types: Type)

    sealed class Type {
        object Word : Type()

        // the unknown type (unable to infer any further structure from this point)
        object Bottom : Type()

        data class Mapping(val codomain: Type) : Type()

        data class Array(val element: Type, val elementSize: BigInteger) : Type()

        data class Struct(val elements: Map<BigInteger, Type>) : Type()

        data class StaticArray(val numElements: BigInteger, val elementSize: BigInteger, val element: Type) : Type()
    }
}


fun AnalysisPath.toNonIndexed() : NonIndexedPath = when(this) {
    is AnalysisPath.ArrayAccess -> NonIndexedPath.ArrayAccess(
        base = this.base.toNonIndexed()
    )
    is AnalysisPath.MapAccess -> NonIndexedPath.MapAccess(base = this.base.toNonIndexed())
    is AnalysisPath.Root -> NonIndexedPath.Root(this.slot)
    is AnalysisPath.StructAccess -> NonIndexedPath.StructAccess(
        base = this.base.toNonIndexed(),
        offset = this.offset.words
    )
    is AnalysisPath.WordOffset -> throw UnsupportedOperationException("Unresolved word offset $this")
    is AnalysisPath.StaticArrayAccess -> NonIndexedPath.StaticArrayAccess(
        base = this.base.toNonIndexed()
    )
}

val AnalysisPath.baseOrNull
    get() =
        when (this) {
            is AnalysisPath.ArrayAccess -> base
            is AnalysisPath.MapAccess -> base
            is AnalysisPath.Root -> null
            is AnalysisPath.StaticArrayAccess -> base
            is AnalysisPath.StructAccess -> base
            is AnalysisPath.WordOffset -> unsupported("Don't call baseOrNull on ${javaClass.simpleName}")
        }

fun AnalysisPath.indices() : List<TACSymbol>? {
    var missingIndex = false
    return buildList {
        fun rec(path: AnalysisPath) {
            fun addIndex(s: TACSymbol?) {
                if (s != null) {
                    add(s)
                } else {
                    missingIndex = true
                }
            }
            path.baseOrNull?.let(::rec)
            when (path) {
                is AnalysisPath.Root, is AnalysisPath.StructAccess, is AnalysisPath.WordOffset -> Unit
                is AnalysisPath.ArrayAccess -> addIndex(path.index)
                is AnalysisPath.StaticArrayAccess -> addIndex(path.index)
                is AnalysisPath.MapAccess -> addIndex(path.key)
            }
        }
        rec(this@indices)
    }.takeIf { !missingIndex }
}



/**
 * This class is a simple wrapper for either [AnalysisPath] or [NonIndexedPath], so algorithms can be written once for
 * both of them, instead of duplicated.
 */
sealed class StoragePath {

    companion object {
        operator fun invoke(path: NonIndexedPath) = when (path) {
            is NonIndexedPath.ArrayAccess -> ArrayAccess.NonIndexed(path)
            is NonIndexedPath.MapAccess -> MapAccess.NonIndexed(path)
            is NonIndexedPath.Root -> Root.NonIndexed(path)
            is NonIndexedPath.StaticArrayAccess -> StaticArrayAccess.NonIndexed(path)
            is NonIndexedPath.StructAccess -> StructAccess.NonIndexed(path)
        }

        operator fun invoke(path: AnalysisPath) = when (path) {
            is AnalysisPath.ArrayAccess -> ArrayAccess.Analysis(path)
            is AnalysisPath.MapAccess -> MapAccess.Analysis(path)
            is AnalysisPath.Root -> Root.Analysis(path)
            is AnalysisPath.StaticArrayAccess -> StaticArrayAccess.Analysis(path)
            is AnalysisPath.StructAccess -> StructAccess.Analysis(path)
            is AnalysisPath.WordOffset -> `impossible!`
        }
    }

    sealed class Root : StoragePath() {
        abstract val slot: BigInteger

        class NonIndexed(val path: NonIndexedPath.Root) : Root() {
            override val slot get() = path.slot
        }

        class Analysis(val path: AnalysisPath.Root) : Root() {
            override val slot get() = path.slot
        }
    }

    sealed class ArrayAccess : StoragePath() {
        abstract val base: StoragePath

        class NonIndexed(val path: NonIndexedPath.ArrayAccess) : ArrayAccess() {
            override val base get() = StoragePath(path.base)
        }

        class Analysis(val path: AnalysisPath.ArrayAccess) : ArrayAccess() {
            override val base get() = StoragePath(path.base)
            val index get() = path.index
        }
    }

    sealed class StaticArrayAccess : StoragePath() {
        abstract val base: StoragePath

        class NonIndexed(val path: NonIndexedPath.StaticArrayAccess) : StaticArrayAccess() {
            override val base get() = StoragePath(path.base)
        }

        class Analysis(val path: AnalysisPath.StaticArrayAccess) : StaticArrayAccess() {
            override val base get() = StoragePath(path.base)
            val index get() = path.index
        }
    }

    sealed class MapAccess : StoragePath() {
        abstract val base: StoragePath

        class NonIndexed(val path: NonIndexedPath.MapAccess) : MapAccess() {
            override val base get() = StoragePath(path.base)
        }

        class Analysis(val path: AnalysisPath.MapAccess) : MapAccess() {
            override val base get() = StoragePath(path.base)
            val key get() = path.key
        }
    }

    sealed class StructAccess : StoragePath() {
        abstract val base: StoragePath
        abstract val offset: BigInteger

        class NonIndexed(val path: NonIndexedPath.StructAccess) : StructAccess() {
            override val base get() = StoragePath(path.base)
            override val offset get() = path.offset
        }

        class Analysis(val path: AnalysisPath.StructAccess) : StructAccess() {
            override val base get() = StoragePath(path.base)
            override val offset get() = path.offset.words
        }
    }
}

