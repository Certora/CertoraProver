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

package wasm.host.soroban.types

import analysis.CommandWithRequiredDecls
import analysis.CommandWithRequiredDecls.Companion.mergeMany
import com.certora.collect.*
import datastructures.stdcollections.*
import tac.*
import tac.generation.*
import utils.*
import vc.data.*
import wasm.WasmPipelinePhase
import wasm.WasmPostUnrollSummary
import wasm.analysis.memory.*
import wasm.host.soroban.*

/**
    Soroban map objects are arbitrary key/value mappings.

    The keys are always "digested" (via [Val.digest]) prior to lookup, so that keys that deeply equal other keys will
    match.
 */
@KSerializable
@Treapable
object MapType : MappingType() {
    override fun hashCode() = hashObject(this)

    override val tag = Val.Tag.MapObject
    override val sizes = TACKeyword.SOROBAN_MAP_SIZES.toVar()
    override val mappings = TACKeyword.SOROBAN_MAP_MAPPINGS.toVar()

    /**
        A key val plus the digest of its contents.  We use the digest as the actual key in the mapping to values,
        so that structurally equivalent keys map to the same value.
     */
    data class DigestedKey(val key: TACSymbol, val digest: TACSymbol)

    /** Maps (map, key) -> boolean, indicating whether the key is present in the map */
    private val keys = TACKeyword.SOROBAN_MAP_KEYS.toVar()
    /** Maps (map, digest) -> boolean, indicating whether the key digest is present in the map */
    private val keyDigests = TACKeyword.SOROBAN_MAP_KEY_DIGESTS.toVar()

    override fun init(): CommandWithRequiredDecls<TACCmd.Simple> = mergeMany(
        super.init(),
        assignHavoc(keys),
        assignHavoc(keyDigests),
        assignHavoc(keyToIndex),
        assignHavoc(indexToKey)
    )

    private fun addKey(newHandle: TACSymbol, oldHandle: TACSymbol, key: DigestedKey, present: Boolean) = mergeMany(
        defineMapping(newHandle, keys) { queryKey ->
            ite(
                queryKey eq key.key.asSym(),
                present.asTACExpr,
                selectFromPreviousMappings(oldHandle.asSym(), queryKey, keys)
            )
        },
        defineMapping(newHandle, keyDigests) { queryKey ->
            ite(
                queryKey eq key.digest.asSym(),
                present.asTACExpr,
                selectFromPreviousMappings(oldHandle.asSym(), queryKey, keyDigests)
            )
        },
    )

    private fun withPresence(
        handle: TACSymbol,
        key: DigestedKey,
        f: (TACSymbol.Var) -> CommandWithRequiredDecls<TACCmd.Simple>
    ) = TACExprFactUntyped {
        selectFromPreviousMappings(handle.asSym(), key.digest.asSym(), keyDigests)
    }.letVar("present", Tag.Bool) { present ->
        f(present.s)
    }

    override fun empty(newHandle: TACSymbol.Var) = mergeMany(
        super.empty(newHandle),
        defineMapping(newHandle, keys) { false.asTACExpr },
        defineMapping(newHandle, keyDigests) { false.asTACExpr },
        Val.setObjectDigest(tag, newHandle.asSym()) { listOf() }
    )

    private fun withKeyDigest(key: TACSymbol, f: (DigestedKey) -> CommandWithRequiredDecls<TACCmd.Simple>) =
        Val.withDigest(key.asSym()) { digest ->
            f(DigestedKey(key, digest.s))
        }

    /**
        Defines a new map whose values are those of an old map, except for the value at [key], which is [value].
     */
    fun put(newHandle: TACSymbol.Var, oldHandle: TACSymbol, key: TACSymbol, value: TACSymbol) =
        // DO NOT call setObjectDigest on the returned handle _without_ considering how it is being used
        // in newFromMemory
        withKeyDigest(key) @Suppress("name_shadowing") { key ->
            withSize(oldHandle) { oldSize ->
                withPresence(oldHandle, key) { oldPresence ->
                    mergeMany(
                        assume {
                            // oldPresence => oldSize > 0
                            not(oldPresence.asSym()) or (oldSize.asSym() gt 0.asTACExpr)
                        },
                        Trap.assert("Map size would exceed maximum") {
                            oldPresence.asSym() or (oldSize.asSym() lt UInt.MAX_VALUE.toLong().asTACExpr)
                        },
                        new(newHandle) {
                            // If this is a new key, increment the size
                            ite(
                                oldPresence.asSym(),
                                oldSize.asSym(),
                                oldSize.asSym() add 1.asTACExpr
                            )
                        },
                        defineMapping(newHandle) { queryKey ->
                            ite(
                                queryKey eq key.digest.asSym(),
                                value.asSym(),
                                selectFromPreviousMappings(oldHandle.asSym(), queryKey)
                            )
                        },
                        addKey(newHandle, oldHandle, key, true)
                    )
                }
            }
        }

    /**
        Gets the value at [key] in the map identified by [handle], storing the result in [dest].
     */
    fun get(dest: TACSymbol.Var, handle: TACSymbol, key: TACSymbol) =
        withKeyDigest(key) @Suppress("name_shadowing") { key ->
            mergeMany(
                withPresence(handle, key) { presence ->
                    Trap.assert("Key not found in map") { presence.asSym() }
                },
                assign(dest) {
                    select(mappings.asSym(), handle.asSym(), key.digest.asSym())
                },
            )
        }

    /**
        Defines a new map whose values are those of an old map, but with [key] removed.
     */
    fun delete(newHandle: TACSymbol.Var, oldHandle: TACSymbol, key: TACSymbol) =
        // DO NOT call setObjectDigest without considering how it is being used in
        // newFromMemory
        withKeyDigest(key) @Suppress("name_shadowing") { key ->
            mergeMany(
                withPresence(oldHandle, key) { presence ->
                    Trap.assert("Key not found in map") { presence.asSym() }
                },
                withSize(oldHandle) { oldSize ->
                    new(newHandle) { oldSize.asSym() sub 1.asTACExpr }
                },
                defineMapping(newHandle) { queryKeyDigest -> selectFromPreviousMappings(oldHandle.asSym(), queryKeyDigest) },
                addKey(newHandle, oldHandle, key, false)
            )
        }

    /**
        Gets whether [key] is in the map identified by [handle].
     */
    fun contains(dest: TACSymbol.Var, handle: TACSymbol, key: TACSymbol) =
        withKeyDigest(key) @Suppress("name_shadowing") { key ->
            assign(dest) {
                selectFromPreviousMappings(handle.asSym(), key.digest.asSym(), keyDigests)
            }
        }


    /*
        Key ordering:

        Soroban maps are sorted according to some total ordering of all possible keys, defined in the Soroban host. We
        make no attempt to model this ordering precisely.  Instead, we assume only that there exist mappings from
        index->key, and key->index, which are consistent.  I.e., the following holds:

            keyToIndex[map, indexToKey[map, index]] == index && presences[map, indexToKey[index]] == true
     */
    private val keyToIndex = TACKeyword.SOROBAN_MAP_KEY_TO_INDEX.toVar()
    private val indexToKey = TACKeyword.SOROBAN_MAP_INDEX_TO_KEY.toVar()

    fun TACExprFact.indexMappingConsistent(handle: TACSymbol, index: TACSymbol) =
        (
            select(
                keyToIndex.asSym(),
                handle.asSym(),
                select(indexToKey.asSym(), handle.asSym(), index.asSym())
            ) eq index.asSym()
        ) and (
            select(
                keys.asSym(),
                handle.asSym(),
                select(indexToKey.asSym(), handle.asSym(), index.asSym())
            )
        )

    /**
        Gets the [index]'th key in the map identified by [handle], storing the result in [key].
     */
    fun getKeyByIndex(
        key: TACSymbol.Var,
        handle: TACSymbol,
        index: TACSymbol
    ) = mergeMany(
        withSize(handle) { size ->
            Trap.assert("Index out of bounds") {
                (0.asTACExpr le index.asSym()) and (index.asSym() lt size.asSym())
            }
        },
        assume { indexMappingConsistent(handle, index) },
        assign(key) {
            select(indexToKey.asSym(), handle.asSym(), index.asSym())
        }
    )

    /**
        Gets the value at the index'th key in the map identified by [handle], storing the result in [value].
     */
    fun getValueByIndex(
        value: TACSymbol.Var,
        handle: TACSymbol,
        index: TACSymbol
    ): CommandWithRequiredDecls<TACCmd.Simple> {
        val key = TACSymbol.Var("key", Tag.Bit256).toUnique("!")
        return mergeMany(
            getKeyByIndex(key, handle, index),
            get(value, handle, key)
        )
    }

    /**
        Gets a vector containing all keys in the map identified by [handle].
     */
    fun getKeys(dest: TACSymbol.Var, handle: TACSymbol) =
        withSize(handle) { size ->
            val assumptionIndex = TACSymbol.Var("index", Tag.Bit256).toUnique("!")
            mergeMany(
                // Create a new vector
                VecType.new(dest) { size.asSym() },
                // Assume index mappings are consistent for all valid index values
                assume {
                    forall(assumptionIndex) {
                        indexMappingConsistent(handle, assumptionIndex) or (assumptionIndex.asSym() ge size.asSym())
                    }
                },
                // vec[i] = indexToKey[i]
                VecType.defineMapping(dest) { queryIndex ->
                    select(indexToKey.asSym(), handle.asSym(), queryIndex)
                }
            ).merge(assumptionIndex)
        }

    /**
        Gets a vector containing all values in the map identified by [handle], in order.
     */
    fun getValues(dest: TACSymbol.Var, handle: TACSymbol) =
        withSize(handle) { size ->
            val assumptionIndex = TACSymbol.Var("index", Tag.Bit256).toUnique("!")
            mergeMany(
                // Create a new vector
                VecType.new(dest) { size.asSym() },
                // Assume index mappings are consistent for all valid index values
                assume {
                    /*
                        TODO CERT-6734: this "forall" cannot be grounded, so this fails unless grounding is disabled,
                        and presumably leads to likely solver timeouts with grounding disabled.  If this ends up being
                        a problem, we should explore other approaches.
                     */
                    forall(assumptionIndex) {
                        indexMappingConsistent(handle, assumptionIndex) or (assumptionIndex.asSym() ge size.asSym())
                    }
                },
                // vec[i] = map[indexToKey[i]]
                VecType.defineMapping(dest) { queryIndex ->
                    select(
                        mappings.asSym(),
                        handle.asSym(),
                        Val.digest(
                            select(indexToKey.asSym(), handle.asSym(), queryIndex)
                        )
                    )
                }
            ).merge(keyToIndex, indexToKey, assumptionIndex)
        }

    /**
        Allocates a new map object, storing the result in [newHandle], containing [length] keys and values from memory
        starting at [keysPos] and [valsPos].

        We initially emit a summary command, which will be replaced with the actual implementation later.  This allows
        us to find the (hopefully constant) value of [length] before generating the actual implementation, so that we
        can emit an unrolled sequence to unpack the map.
     */
    fun newFromMemory(newHandle: TACSymbol.Var, keysPos: TACSymbol, valsPos: TACSymbol, length: TACSymbol) =
        NewMapFromMemorySummary(newHandle, keysPos, valsPos, length).toCmd()

    @KSerializable
    data class NewMapFromMemorySummary(
        val newHandle: TACSymbol.Var,
        val keysPos: TACSymbol,
        val valsPos: TACSymbol,
        val length: TACSymbol,
    ): WasmPostUnrollSummary(phase = WasmPipelinePhase.PreOptimization) {
        override val inputs get() = listOf(keysPos, valsPos, length, TACKeyword.MEMORY.toVar())
        override val mustWriteVars get() = listOf(newHandle, mappings, sizes, keys, keyDigests)

        protected override fun transformSymbols(f: Transformer) = NewMapFromMemorySummary(
            newHandle = f(newHandle),
            keysPos = f(keysPos),
            valsPos = f(valsPos),
            length = f(length)
        )

        protected override fun gen(
            simplifiedInputs: List<TACExpr>,
            analysisCache: AnalysisCache
        ) = simplifiedInputs.let { (keysPos, valsPos, length) ->
            val staticData = analysisCache[StaticMemoryAnalysis]

            // Try to get the length as a constant
            val constLength = length.getAsConst()?.toIntOrNull()
            if (constLength == null) {
                // No constant length;  Just return a havoc'd map.
                new(newHandle) { length }
            } else {
                val keys = (0 ..< constLength).map { TACSymbol.Var("key", Tag.Bit256).toUnique("!") }
                val values = (0 ..< constLength).map { TACSymbol.Var("value", Tag.Bit256).toUnique("!") }
                val kvs = keys.zip(values)
                // Compute the offsets of each key/value pair
                val offsets = (0..<constLength).map { it * Val.sizeInBytes }
                // Build a new map from the key/value pairs
                val initialHandle = TACSymbol.Var("newMap", Tag.Bit256).toUnique("!")
                val (builtMap, mapBuilder) = offsets.zip(kvs).fold(
                    initialHandle to listOf(empty(initialHandle))
                ) { (oldHandle, prev), (offset, kv) ->
                    val (key, value) = kv
                    val newHandle = TACKeyword.TMP(Tag.Bit256)
                    val keyLoc = TACExprFactUntyped { keysPos add offset.asTACExpr }
                    newHandle to (
                        prev + mergeMany(
                            SymbolType.newFromStrPtr(key, keyLoc, staticData),
                            Val.assertValid(key, "map", "map_new_from_linear_memory"),
                            assign(value) {
                                select(
                                    TACKeyword.MEMORY.toVar().asSym(),
                                    valsPos add offset.asTACExpr
                                )
                            },
                            put(newHandle, oldHandle, key, value)
                        )
                    )
                }

                val setDigest = Val.setObjectDigest(
                    tag,
                    builtMap.asSym(),
                    )  {
                    kvs.flatMap { (key, value) ->
                        listOf(Val.digest(key.asSym()), Val.digest(value.asSym()))
                    }
                }

                mergeMany(
                    mergeMany(mapBuilder),
                    setDigest,
                    assign(newHandle) { builtMap.asSym() }
                )
            }
        }
    }

    /**
        Copies [length] values to memory, starting at [valsPos].  The values are obtained by looking up the keys found
        in memory starting at [keysPos].

        We initially emit a summary command, which will be replaced with the actual implementation later.  This allows
        us to find the (hopefully constant) value of [length] before generating the actual implementation, so that we
        can emit an unrolled sequence to unpack the map values.
     */
    fun unpackToMemory(handle: TACSymbol, keysPos: TACSymbol, valsPos: TACSymbol, length: TACSymbol) =
        UnpackMapToMemorySummary(handle, keysPos, valsPos, length).toCmd()

    @KSerializable
    data class UnpackMapToMemorySummary(
        val handle: TACSymbol,
        val keysPos: TACSymbol,
        val valsPos: TACSymbol,
        val length: TACSymbol
    ) : WasmPostUnrollSummary(phase = WasmPipelinePhase.PreOptimization) {
        override val inputs get() = listOf(handle, keysPos, valsPos, length, TACKeyword.MEMORY.toVar())
        override val mustWriteVars get() = listOf(TACKeyword.MEMORY.toVar())

        protected override fun transformSymbols(f: Transformer) = UnpackMapToMemorySummary(
            handle = f(handle),
            keysPos = f(keysPos),
            valsPos = f(valsPos),
            length = f(length),
        )

        protected override fun gen(
            simplifiedInputs: List<TACExpr>,
            analysisCache: AnalysisCache
        ) = simplifiedInputs.let { (_, keysPos, valsPos, length) ->
            val staticData = analysisCache[StaticMemoryAnalysis]
            val constLength = length.getAsConst()?.toIntOrNull()
            if (constLength == null) {
                // Havoc the target memory
                val havocBytes = TACKeyword.TMP(Tag.ByteMap)
                mergeMany(
                    assignHavoc(havocBytes),
                    TACExprFactUntyped {
                        length mul Val.sizeInBytes.asTACExpr
                    }.letVar("memLen") { memLen ->
                        TACCmd.Simple.ByteLongCopy(
                            srcBase = havocBytes,
                            srcOffset = TACSymbol.Zero,
                            dstBase = TACKeyword.MEMORY.toVar(),
                            dstOffset = this.valsPos,
                            length = memLen.s,
                            meta = MetaMap(LONG_COPY_STRIDE to Val.sizeInBytes)
                        ).withDecls(TACKeyword.MEMORY.toVar())
                    }
                )
            } else {
                val offsets = (0..<constLength).map { it * Val.sizeInBytes }
                val keyLocs = offsets.map { TACExprFactUntyped {keysPos add it.asTACExpr }}
                val valLocs = offsets.map { TACExprFactUntyped { valsPos add it.asTACExpr }}
                val keyVars = (0..<constLength).map { TACKeyword.TMP(Tag.Bit256) }
                val valVars = (0..<constLength).map { TACKeyword.TMP(Tag.Bit256) }
                mergeMany(
                    // Read all keys from memory and convert to vals (symbol)
                    mergeMany(
                        keyVars.zip(keyLocs).map { (key, loc) ->
                            SymbolType.newFromStrPtr(key, loc, staticData)
                        }
                    ),
                    // Check that all keys are valid and present in the map
                    mergeMany(
                        keyVars.map { key ->
                            withKeyDigest(key) { digest ->
                                withPresence(handle, digest) { presence ->
                                    Trap.assert("Key not found in map") { presence.asSym() }
                                }
                            }
                        }
                    ),
                    // Read all values from the map
                    mergeMany(
                        keyVars.zip(valVars).map { (key, value) ->
                            assign(value) {
                                select(
                                    mappings.asSym(),
                                    handle.asSym(),
                                    Val.digest(key.asSym())
                                )
                            }
                        }
                    ),
                    // Write the values to memory
                    mergeMany(
                        valVars.zip(valLocs).map { (value, loc) ->
                            loc.letVar { locVar ->
                                TACCmd.Simple.AssigningCmd.ByteStore(
                                    locVar.s,
                                    value,
                                    TACKeyword.MEMORY.toVar()
                                ).withDecls(TACKeyword.MEMORY.toVar())
                            }
                        }
                    ),
                )
            }
        }
    }
}

