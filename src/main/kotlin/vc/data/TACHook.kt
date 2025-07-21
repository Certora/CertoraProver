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

@file:UseSerializers(BigIntegerSerializer::class)
package vc.data

import analysis.storage.DisplayPath
import analysis.storage.StorageAnalysis.Base
import analysis.storage.StorageAnalysisResult.AccessPaths
import analysis.storage.singleDisplayPathOrNull
import com.certora.collect.*
import datastructures.stdcollections.*
import evm.EVM_WORD_SIZE
import kotlinx.serialization.UseSerializers
import scene.IContractClass
import spec.cvlast.*
import spec.cvlast.Address
import spec.cvlast.Balance
import spec.cvlast.Basefee
import spec.cvlast.Blobbasefee
import spec.cvlast.Blobhash
import spec.cvlast.Blockhash
import spec.cvlast.Call
import spec.cvlast.Callcode
import spec.cvlast.Caller
import spec.cvlast.Callvalue
import spec.cvlast.Chainid
import spec.cvlast.Codecopy
import spec.cvlast.Codesize
import spec.cvlast.Coinbase
import spec.cvlast.Create1
import spec.cvlast.Create2
import spec.cvlast.Delegatecall
import spec.cvlast.Difficulty
import spec.cvlast.Extcodecopy
import spec.cvlast.Extcodehash
import spec.cvlast.Extcodesize
import spec.cvlast.Gas
import spec.cvlast.Gaslimit
import spec.cvlast.Gasprice
import spec.cvlast.Log0
import spec.cvlast.Log1
import spec.cvlast.Log2
import spec.cvlast.Log3
import spec.cvlast.Log4
import spec.cvlast.Msize
import spec.cvlast.Number
import spec.cvlast.Origin
import spec.cvlast.Revert
import spec.cvlast.Returndatasize
import spec.cvlast.Selfbalance
import spec.cvlast.Selfdestruct
import spec.cvlast.Staticcall
import spec.cvlast.Timestamp
import spec.cvlast.typedescriptors.FromVMContext
import spec.cvlast.typedescriptors.IHookParameter
import tac.TACStorageType
import utils.AmbiSerializable
import utils.BigIntegerSerializer
import utils.KSerializable
import utils.`impossible!`
import vc.data.tacexprutil.DefaultTACExprTransformer
import vc.data.tacexprutil.TACExprFreeVarsCollector
import java.io.Serializable
import java.math.BigInteger
import analysis.storage.StorageAnalysis.AnalysisPath as AccessPath

fun TACExpr.inject() : HookValue = HookValue.Direct(this)

@KSerializable
@Treapable
sealed class HookValue : AmbiSerializable, IHookParameter, TransformableSymEntityWithRlxSupport<HookValue> {
    @KSerializable
    data class Direct(val expr: TACExpr) : HookValue() {
        override fun transformSymbols(f: (TACSymbol) -> TACSymbol): HookValue {
            val expr = Transformer(f).transform(this.expr)
            return this.copy(expr = expr)
        }

        override val support: Set<TACSymbol.Var> get() = TACExprFreeVarsCollector.getFreeVars(this.expr)
    }
}

/**
 * Describes how well a tac command annotation matches a hook pattern. The storage analysis can infer multiple possible
 * paths for a given read or write, which is why it is possible to match some, all, or none.
 *
 * @property substitutions a map from "parameters" to the hook, to the values (from the evm program) which should be
 * substituted for those "parameters"
 */
sealed class HookMatch {

    abstract val substitutions: Map<VMParam.Named, HookValue>

    abstract fun withSubstitution(v: VMParam.Named, subst: TACExpr): HookMatch

    /**
     * Does not match at all.
     */
    object None : HookMatch() {
        override val substitutions: Map<VMParam.Named, HookValue> = emptyMap()
        override fun withSubstitution(v: VMParam.Named, subst: TACExpr): HookMatch = this
    }

    /**
     * Matches a create command.
     */
    data class Create(
        override val substitutions: Map<VMParam.Named, HookValue> = emptyMap()
    ) : HookMatch() {
        override fun withSubstitution(v: VMParam.Named, subst: TACExpr): HookMatch =
            this.copy(substitutions = substitutions.plus(v to subst.inject()))
    }

    sealed class OpcodeMatch : HookMatch() {
        abstract val executingContractVar: TACSymbol.Var
    }

    /**
     * Matches specific to storage reads and writes.
     */
    sealed class StorageMatch : HookMatch() {
        abstract val matchedStorageAccessPaths: Set<AccessPath>
        abstract val expectedSlotPattern: TACHookPattern.StorageHook.TACSlotPattern

        /**
         * the _single_ [DisplayPath] corresponding to this storage match.
         *
         * it may be that there could be several,
         * and in that case, it could also be that we would need
         * to filter some out, depending on whether or not we got a full match.
         * I'm not handling any of this right now.
         */
        abstract val displayPath: DisplayPath?

        /**
         * Every path inferred by the storage analysis matches the given hook.
         */
        data class AllPaths(
            override val expectedSlotPattern: TACHookPattern.StorageHook.TACSlotPattern,
            override val matchedStorageAccessPaths: Set<AccessPath>,
            override val displayPath: DisplayPath?,
            override val substitutions: Map<VMParam.Named, HookValue> = emptyMap()
        ) : StorageMatch() {
            override fun withSubstitution(v: VMParam.Named, subst: TACExpr): HookMatch =
                this.copy(substitutions = substitutions.plus(v to subst.inject()))
        }

        /**
         * Some, but not all paths inferred by the storage analysis match the given hook pattern. This could happen
         * due to a mismatch between storage analysis join semantics and less permissive hook matching semantics. (i.e.
         * for paths A and B and hook H, join(A, B) will give us { A, B } whereas matches(A, H) gives true while
         * matches(B, H) gives false.
         */
        data class SomePaths(
            override val expectedSlotPattern: TACHookPattern.StorageHook.TACSlotPattern,
            override val matchedStorageAccessPaths: Set<AccessPath>,
            val unmatched: Set<AccessPath>,
            override val displayPath: DisplayPath?,
            override val substitutions: Map<VMParam.Named, HookValue>
        ) : StorageMatch() {
            override fun withSubstitution(
                v: VMParam.Named,
                subst: TACExpr
            ): HookMatch = this
        }
    }
}


/**
 * A [TACHookPattern] is a pattern that specifies a read or write to storage
 *
 * Currently we assume that base is tacS [TACKeyword.STORAGE]
 */
sealed class TACHookPattern : Serializable {

    /**
     * Checks if the given [cmd] is matching the hook pattern.
     * @returns a [HookMatch] object describing the matching, including the original hook,
     * matches and non-matches, and substitutions (i.e., conversion of hook arguments to an expression over the command arguments)
     */
    abstract fun matches(cmd: TACCmd): HookMatch

    sealed class StorageHook : TACHookPattern() {
        companion object {
            fun CVLHookPattern.StoragePattern.Base.map() = when (this) {
                CVLHookPattern.StoragePattern.Base.STORAGE -> Base.STORAGE
                CVLHookPattern.StoragePattern.Base.TRANSIENT_STORAGE -> Base.TRANSIENT_STORAGE
            }
        }

        abstract val value: VMParam.Named
        abstract val base: CVLHookPattern.StoragePattern.Base
        abstract val slot: TACSlotPattern

        data class Store(
            override val base: CVLHookPattern.StoragePattern.Base, override val slot: TACSlotPattern,
            override val value: VMParam.Named, val previousValue: VMParam.Named?,
        ) : StorageHook(), Serializable {
            override fun toString() = "Hook ${base.map().prefixChar}store $slot $value $base"
            override fun matches(cmd: TACCmd): HookMatch = (if (cmd is TACCmd.Simple.AssigningCmd.AssignExpCmd && cmd.storeAddress(base) == slot.contract) {
                // some writes to storage get simplified into normal assignments
                    cmd.lhs.meta.find(TACMeta.SCALARIZATION_SORT)?.let { storageSort ->
                        matchSlotPattern(slot, storageSort).withSubstitution(
                            value, cmd.rhs
                        )
                    }
                } else if (cmd is TACCmd.Simple.WordStore &&
                    cmd.storeAddress(base) == slot.contract
                ) {
                    cmd.loc.toAccessPath(base.map())?.let { accessPaths ->
                        val displayPath = cmd.loc.singleDisplayPathOrNull()
                        matchSlotPattern(slot, accessPaths, displayPath).withSubstitution(
                            value, cmd.value.asSym()
                        )
                    }
                } else {
                    null
                }) ?: HookMatch.None
        }

        data class Load(
            override val base: CVLHookPattern.StoragePattern.Base,
            override val slot: TACSlotPattern,
            override val value: VMParam.Named,
        ) : StorageHook(), Serializable {
            override fun toString() = "Hook ${base.map().prefixChar}load $value $slot $base"
            override fun matches(cmd: TACCmd): HookMatch =
                (if (cmd is TACCmd.Simple.AssigningCmd.AssignExpCmd &&
                    cmd.rhs is TACExpr.Sym.Var && cmd.rhs.s.meta[base.map().storageKey] == slot.contract
                ) {
                    // some reads from storage get simplified into normal assignments
                    cmd.rhs.s.meta.find(TACMeta.SCALARIZATION_SORT)?.let { storageSort ->
                        matchSlotPattern(slot, storageSort).withSubstitution(
                            value, cmd.lhs.asSym()
                        )
                    }
                } else if (cmd is TACCmd.Simple.AssigningCmd.WordLoad &&
                    cmd.storeAddress(base) == slot.contract
                ) {
                    cmd.loc.toAccessPath(base.map())?.let { accessPaths ->
                        val displayPath = cmd.loc.singleDisplayPathOrNull()
                        matchSlotPattern(slot, accessPaths, displayPath).withSubstitution(
                            value, cmd.lhs.asSym()
                        )
                    }
                } else {
                    null
                }) ?: HookMatch.None
        }

        /**
         * Get the address of the contract whose storage is being written to/read from
         */
        fun TACCmd.Simple.storeAddress(base: CVLHookPattern.StoragePattern.Base) = when (this) {
            is TACCmd.Simple.WordStore -> this.base.meta.find(base.map().storageKey)
            is TACCmd.Simple.AssigningCmd.WordLoad -> this.base.meta.find(base.map().storageKey)
            is TACCmd.Simple.AssigningCmd.AssignExpCmd -> this.lhs.meta.find(base.map().storageKey)
            else -> null
        }

        /**
         * Does an individual access path [potentialMatch], inferred by the storage analysis, match the given [slot]
         * as defined in a hook pattern?
         */
        fun isMatchingSlot(slot: TACSlotPattern, potentialMatch: AccessPath): Boolean =
            when {
                slot is TACSlotPattern.MapAccess && potentialMatch is AccessPath.MapAccess -> {
                    isMatchingSlot(
                        slot.mapping,
                        potentialMatch.base
                    )
                }
                slot is TACSlotPattern.ArrayAccess && potentialMatch is AccessPath.ArrayAccess ->
                    isMatchingSlot(slot.array, potentialMatch.base)
                slot is TACSlotPattern.StructAccess && potentialMatch is AccessPath.StructAccess ->
                    // slot.offset.value is the slot offset value in bytes
                    slot.offset.value == potentialMatch.offset.bytes && isMatchingSlot(slot.base, potentialMatch.base)
                slot is TACSlotPattern.Static && potentialMatch is AccessPath.Root ->
                    slot.index.value == potentialMatch.slot && slot.offsetMatchesBits(BigInteger.ZERO) && potentialMatch.matchesBase(slot.base)
                slot is TACSlotPattern.ArrayAccess && potentialMatch is AccessPath.StaticArrayAccess ->
                    isMatchingSlot(slot.array, potentialMatch = potentialMatch.base)
                else -> false
            }

        /**
         * Give the [HookMatch] between [slot] and [potentialMatch]. Note because this is a scalar, there is only a
         * single slot being reference, so either [HookMatch.All] or [HookMatch.None] should be returned but not
         * [HookMatch.Some].
         */
        fun matchSlotPattern(slot: TACSlotPattern, potentialMatch: ScalarizationSort): HookMatch {
            val canonSlot = slot.canonicalize()
            fun slotEqual(slotIndex: TACSymbol.Const, matchOffsetBits: (BigInteger) -> Boolean): HookMatch {
                val (index, offset) = when (potentialMatch) {
                    is ScalarizationSort.Split -> TACSymbol.Const(potentialMatch.idx) to BigInteger.ZERO
                    is ScalarizationSort.Packed -> {
                        val const = (potentialMatch.packedStart as? ScalarizationSort.Split)?.idx ?: return HookMatch.None
                        TACSymbol.Const(const) to potentialMatch.start.toBigInteger()
                    }
                    is ScalarizationSort.UnscalarizedBuffer -> return HookMatch.None
                }
                return if (index == slotIndex && matchOffsetBits(offset)) {
                    // TODO: what to do with offset :[]
                    // no values are added to the HookMatch's substitutions since we only have lhs and rhs to this
                    // command which are explicitly part of the command.
                    HookMatch.StorageMatch.AllPaths(
                        expectedSlotPattern = canonSlot,
                        matchedStorageAccessPaths = setOf(AccessPath.Root(index.value, canonSlot.isBasedOn().map())),
                        displayPath = null, // is this right?
                        substitutions = emptyMap(),
                    )
                } else {
                    HookMatch.None
                }
            }
            return when (canonSlot) {
                is TACSlotPattern.Static -> slotEqual(canonSlot.index, canonSlot::offsetMatchesBits)
                is TACSlotPattern.MapAccess, is TACSlotPattern.ArrayAccess, is TACSlotPattern.StructAccess -> HookMatch.None
            }
        }

        private fun AccessPath.Root.matchesBase(base: CVLHookPattern.StoragePattern.Base) = this.base == base.map()

        /**
         * Return the [HookMatch] between [slot] and the set of [accessPaths] as inferred by the storage analysis.
         * @return a mapping from pattern variables to matched program variables
         */
        fun matchSlotPattern(slot: TACSlotPattern, accessPaths: AccessPaths, displayPath: DisplayPath?): HookMatch {
            /**
             * Generate the [HookMatch] between [slot] and [accessPaths]. Note this assumes that [slot] and
             * [accessPaths] do indeed match, and simply fills in the appropriate [HookMatch.substitutions].
             * The assumptions made byt this function should be established within [isMatchingSlot].
             *
             * It may be worth it to eventually merge this function with [isMatchingSlot].
             */
            fun matchPattern(
                slot: TACSlotPattern,
                accessPath: AccessPath,
                map: Map<VMParam.Named, HookValue>
            ): Map<VMParam.Named, HookValue> =
                when {
                    slot is TACSlotPattern.MapAccess && accessPath is AccessPath.MapAccess -> {
                        matchPattern(slot.mapping, accessPath.base, map.plus(slot.key to accessPath.key.asSym().inject()))
                    }
                    slot is TACSlotPattern.ArrayAccess && accessPath is AccessPath.ArrayAccess -> {
                        check(accessPath.index != null) {
                            "Internal error: index of access path $accessPath was " +
                                    "not filled in by index calculation. Please report to Certora."
                        }
                        matchPattern(slot.array, accessPath.base, map.plus(slot.index to accessPath.index.asSym().inject()))
                    }
                    slot is TACSlotPattern.ArrayAccess && accessPath is AccessPath.StaticArrayAccess -> {
                        check(accessPath.index != null) {
                            "Internal error: index of access path $accessPath was " +
                                    "not filled in by index calculation. Please report to Certora."
                        }
                        matchPattern(slot.array, accessPath.base, map + (slot.index to accessPath.index.asSym().inject()))
                    }
                    slot is TACSlotPattern.StructAccess && accessPath is AccessPath.StructAccess &&
                            // slot.offset.value is the slot offset value in bytes
                            slot.offset.value == accessPath.offset.bytes ->
                        matchPattern(slot.base, accessPath.base, map)
                    slot is TACSlotPattern.Static && accessPath is AccessPath.Root &&
                            slot.index.value == accessPath.slot && slot.offset.value == BigInteger.ZERO
                        && accessPath.matchesBase(slot.base) ->
                        map
                    else -> error(
                        "Internal error: this function should not be called if the slot wasn't already " +
                                "determined to have a match. Perhaps the condition was updated for the matching decision " +
                                "function and not this function."
                    )
                }

            val canonicalSlot = slot.canonicalize()
            val (matches, nonMatch) = accessPaths.paths.partition { path -> isMatchingSlot(canonicalSlot, path) }
            return if (nonMatch.isEmpty()) {
                // all match
                HookMatch.StorageMatch.AllPaths(
                    expectedSlotPattern = slot,
                    matchedStorageAccessPaths = accessPaths.paths,
                    displayPath = displayPath,
                    substitutions = matchPattern(canonicalSlot, accessPaths.paths.first(), emptyMap()),
                )
            } else if (matches.isNotEmpty()) {
                HookMatch.StorageMatch.SomePaths(
                    expectedSlotPattern = slot,
                    matchedStorageAccessPaths = matches.toSet(),
                    unmatched = nonMatch.toSet(),
                    displayPath = displayPath,
                    substitutions = emptyMap(),
                )
            } else {
                HookMatch.None
            }
        }

        sealed class TACSlotPattern: Serializable {
            abstract val contract: BigInteger
            infix fun matches(other: TACSlotPattern): Boolean = this.canonicalize() == other.canonicalize()

            private var canonicalCache : TACSlotPattern? = null

            /**
             * Flatten nested struct accesses and introduce 0-offset struct accesses in certain places. Important
             * since the storage analysis can't always get the real affsets correct so it's easier to compare to a
             * canonicalized offset.
             */
            fun canonicalize() : TACSlotPattern {
                if(canonicalCache == null) {
                    canonicalCache = this.canonicalizeInternal().let {
                        if(it is MapAccess || it is ArrayAccess) {
                            StructAccess(
                                offset = BigInteger.ZERO.asTACSymbol(),
                                base = it
                            )
                        } else {
                            it
                        }
                    }
                }
                return canonicalCache!!
            }

            private fun canonicalizeInternal() : TACSlotPattern = when(this) {
                is ArrayAccess -> {
                    val parent = this.array.canonicalize()
                    ArrayAccess(
                        array = if (parent !is Static && parent !is StructAccess) {
                            StructAccess(
                                base = parent,
                                offset = BigInteger.ZERO.asTACSymbol()
                            )
                        } else { parent },
                        index = this.index
                    )
                }
                is MapAccess -> {
                    val mapping = this.mapping.canonicalize()
                    MapAccess(
                        mapping = if(mapping !is Static && mapping !is StructAccess) {
                            StructAccess(
                                base = mapping,
                                offset = BigInteger.ZERO.asTACSymbol()
                            )
                        } else { mapping },
                        key = this.key
                    )
                }
                is Static -> this
                is StructAccess -> {
                    when (val parent = this.base.canonicalize()) {
                        is StructAccess -> {
                            parent.copy(
                                offset = (this.offset.value + parent.offset.value).asTACSymbol()
                            )
                        }
                        is Static -> {
                            parent.copy(
                                offset = (parent.offset.value + this.offsetPackingBytes).asTACSymbol(),
                                index = (parent.index.value + this.offsetWords).asTACSymbol()
                            )
                        }
                        else -> {
                            StructAccess(
                                base = parent,
                                offset = this.offset
                            )
                        }
                    }
                }
            }

            fun isBasedOn(): CVLHookPattern.StoragePattern.Base = when(this) {
                is Static -> this.base
                is ArrayAccess -> this.array.isBasedOn()
                is MapAccess -> this.mapping.isBasedOn()
                is StructAccess -> this.base.isBasedOn()
            }

            /**
             * Represents a storage variable at a static slot offset.
             *
             * @property contract the address of the contract whose storage we are referring to
             * @property index the base slot in bytes (though this value must be a multiple of 32)
             * @property offset the offset into a specific word (due to packing), in bytes
             */
            data class Static(override val contract: BigInteger, val index: TACSymbol.Const, val offset: TACSymbol.Const, val base: CVLHookPattern.StoragePattern.Base) :
                TACSlotPattern() {
                override fun toString() = "$index.$offset $base"

                fun offsetMatchesBits(bits: BigInteger) = offset.value.times(BigInteger.valueOf(8)) == bits
            }

            /**
             * Represents an access into mapping [mapping] with key [key].
             */
            data class MapAccess(val mapping: TACSlotPattern, val key: VMParam.Named) : TACSlotPattern() {
                override fun toString() = "$mapping[key ${key.name}]"
                override val contract: BigInteger
                    get() = mapping.contract

            }

            /**
             * Represents an access into array [array] with index [index].
             */
            data class ArrayAccess(val array: TACSlotPattern, val index: VMParam.Named) : TACSlotPattern() {
                override fun toString() = "$array[index ${index.name}]"
                override val contract: BigInteger
                    get() = array.contract

            }

            /**
             * Represents an access into struct at [base] with offset [offset] in bytes.
             */
            data class StructAccess(val base: TACSlotPattern, val offset: TACSymbol.Const) : TACSlotPattern() {
                override fun toString(): String = "($base).$offset"
                override val contract: BigInteger
                    get() = base.contract

                val offsetWords: BigInteger
                    get() = offset.value.divide(EVM_WORD_SIZE)

                val offsetPackingBytes: BigInteger
                    get() = offset.value.mod(EVM_WORD_SIZE)
            }
        }
    }

    data class Create(val value: VMParam.Named) : TACHookPattern() {
        override fun matches(cmd: TACCmd): HookMatch =
            if (cmd is TACCmd.Simple.AssigningCmd) {
                if (cmd.lhs.meta.containsKey(TACMeta.IS_CREATED_ADDRESS)) {
                    HookMatch.Create().withSubstitution(
                        value, cmd.lhs.asSym()
                    )
                } else {
                    HookMatch.None
                }
            } else {
                HookMatch.None
            }
    }

    /**
     * Populated using [CVLHookProcessor]
     */
    sealed class OpcodeHook : TACHookPattern()

    companion object {
        /**
         * Get the type of the slot pattern referred to by [cvlSlotPattern]. [extract] is extended at each recursive call
         * to traverse the type according to the step indicated in [cvlSlotPattern]
         */
        private tailrec fun getTypeAt(
            cvlSlotPattern: CVLSlotPattern,
            scope: CVLScope,
            contracts: List<IContractClass>,
            extract: (TACStorageType) -> TACStorageType
        ): TACStorageType {
            return when (cvlSlotPattern) {
                is CVLSlotPattern.ArrayAccess -> {
                    getTypeAt(cvlSlotPattern.base, scope, contracts) {
                        ((it as? TACStorageType.Array)?.elementType ?: (it as? TACStorageType.StaticArray)?.elementType
                        ?: error("not an array, typechecker should have caught this")).let(extract)
                    }
                }

                is CVLSlotPattern.FieldAccess -> {
                    getTypeAt(cvlSlotPattern.base, scope, contracts) {
                        (it as? TACStorageType.Struct)?.members?.get(cvlSlotPattern.field)?.type?.let(extract)
                            ?: error("no struct with field ${cvlSlotPattern.field}, typechecker should have caught this")
                    }
                }

                is CVLSlotPattern.MapAccess -> {
                    getTypeAt(cvlSlotPattern.base, scope, contracts) {
                        (it as? TACStorageType.Mapping)?.valueType?.let(extract)
                            ?: error("not a map, typechecker should have caught this")
                    }
                }

                is CVLSlotPattern.Static.Indexed -> {
                    error("Trying to get named fields from a raw index slot, typechecker should have caught this")
                }

                is CVLSlotPattern.Static.Named -> {
                    contracts.find { it.name == cvlSlotPattern.solidityContract.name }?.getStorageLayout()
                        ?.get(cvlSlotPattern.name)?.storageType?.let(extract)
                        ?: error("Tried to get untyped or non-existent root ${cvlSlotPattern.name}, typechecker should have caught this")
                }

                is CVLSlotPattern.StructAccess -> {
                    error("Trying to use raw offsets with named fields, typechecker should have caught this")
                }
            }
        }

        private fun handleSlotPattern(
            cvlSlotPattern: CVLSlotPattern,
            scope: CVLScope,
            contracts: List<IContractClass>,
            base: CVLHookPattern.StoragePattern.Base
        ): StorageHook.TACSlotPattern =
            when (cvlSlotPattern) {
                is CVLSlotPattern.Static -> {
                    val contractClass = contracts.find { it.name == cvlSlotPattern.solidityContract.name }!!
                    val contract = contractClass.instanceId
                    when (cvlSlotPattern) {
                        is CVLSlotPattern.Static.Named -> {
                            val solidityStorage = when (base) {
                                CVLHookPattern.StoragePattern.Base.STORAGE -> contractClass.getStorageLayout()
                                CVLHookPattern.StoragePattern.Base.TRANSIENT_STORAGE -> contractClass.getTransientStorageLayout()
                            }
                            solidityStorage?.get(cvlSlotPattern.name)?.let { slot ->
                                StorageHook.TACSlotPattern.Static(
                                    contract,
                                    TACSymbol.Const(slot.slot),
                                    TACSymbol.Const(slot.offset.toBigInteger()),
                                    base
                                )
                            } ?: error("Requested variable not in storage layout: this should be caught by type checker")
                        }

                        is CVLSlotPattern.Static.Indexed ->
                            StorageHook.TACSlotPattern.Static(
                                contract, TACSymbol.Const(cvlSlotPattern.index),
                                TACSymbol.Const(cvlSlotPattern.offset),
                                base
                            )
                    }
                }

                is CVLSlotPattern.MapAccess -> {
                    StorageHook.TACSlotPattern.MapAccess(
                        handleSlotPattern(cvlSlotPattern.base, scope, contracts, base),
                        cvlSlotPattern.key
                    )
                }

                is CVLSlotPattern.StructAccess ->
                    StorageHook.TACSlotPattern.StructAccess(
                        handleSlotPattern(cvlSlotPattern.base, scope, contracts, base),
                        TACSymbol.Const(cvlSlotPattern.offset)
                    )

                is CVLSlotPattern.ArrayAccess -> {
                    StorageHook.TACSlotPattern.ArrayAccess(
                        handleSlotPattern(cvlSlotPattern.base, scope, contracts, base),
                        cvlSlotPattern.index
                    )
                }

                is CVLSlotPattern.FieldAccess -> {
                    val ty = getTypeAt(cvlSlotPattern.base, scope, contracts) { it }
                    when (ty) {
                        is TACStorageType.Array -> {
                            check(cvlSlotPattern.field == "length") {
                                "illegal field access of an array, typechecker should have caught this"
                            }
                            StorageHook.TACSlotPattern.StructAccess(
                                base = handleSlotPattern(cvlSlotPattern.base, scope, contracts, base),
                                offset = TACSymbol.Const(BigInteger.ZERO) // the length field is at offset 0 of an array
                            )
                        }
                        is TACStorageType.Struct -> {
                            val fld = ty.members[cvlSlotPattern.field] ?: error("no field ${cvlSlotPattern.field} in the struct, typechecker should have caught this")
                            StorageHook.TACSlotPattern.StructAccess(
                                offset = (fld.slot * EVM_WORD_SIZE + fld.offset.toBigInteger()).asTACSymbol(),
                                base = handleSlotPattern(cvlSlotPattern.base, scope, contracts, base)
                            )
                        }
                        else -> `impossible!`
                    }
                }
            }

        fun cvlHookPatternToTACHookPattern(pattern: CVLHookPattern, scope: CVLScope, contracts: List<IContractClass>): TACHookPattern {
            return when (pattern) {
                is CVLHookPattern.StoragePattern -> {
                    val loc = handleSlotPattern(
                        pattern.slot,
                        scope,
                        contracts,
                        pattern.base
                    )
                    val v = pattern.value
                    when (pattern) {
                        is CVLHookPattern.StoragePattern.Load -> {
                            StorageHook.Load(pattern.base, loc, v)
                        }
                        is CVLHookPattern.StoragePattern.Store -> {
                            StorageHook.Store(
                                pattern.base,
                                loc,
                                v,
                                pattern.previousValue?.also { previousValue ->
                                    val prevValueType = previousValue.vmType.getPureTypeToConvertTo(FromVMContext.HookValue).force()
                                    check(prevValueType is CVLType.PureCVLType.CVLValueType) { "previousValue should be of a value type, not $prevValueType" }
                                }
                            )
                        }
                    }
                }

                is CVLHookPattern.Create -> Create(pattern.value)
                is CVLHookPattern.Opcode -> {
                    when (pattern) {
                        is Log0 -> vc.data.Log0(pattern.offset, pattern.size)
                        is Log1 -> vc.data.Log1(pattern.offset, pattern.size, pattern.topic1)
                        is Log2 -> vc.data.Log2(pattern.offset, pattern.size, pattern.topic1, pattern.topic2)
                        is Log3 -> vc.data.Log3(pattern.offset, pattern.size, pattern.topic1, pattern.topic2, pattern.topic3)
                        is Log4 -> vc.data.Log4(pattern.offset, pattern.size, pattern.topic1, pattern.topic2, pattern.topic3, pattern.topic4)
                        is Address -> vc.data.Address(pattern.value)
                        is Balance -> vc.data.Balance(pattern.value, pattern.addr)
                        is Codecopy -> vc.data.Codecopy(pattern.destOffset,pattern.offset,pattern.length)
                        is Extcodecopy -> vc.data.Extcodecopy(
                            addr = pattern.addr,
                            destOffset = pattern.destOffset,
                            srcOffset = pattern.offset,
                            length = pattern.length
                        )
                        is Basefee -> vc.data.Basefee(pattern.value)
                        is Blobbasefee -> vc.data.Blobbasefee(pattern.value)
                        is Blobhash -> vc.data.Blobhash(pattern.value, pattern.index)
                        is Blockhash -> vc.data.Blockhash(pattern.value, pattern.blockNum)
                        is Caller -> vc.data.Caller(pattern.value)
                        is Callvalue -> vc.data.Callvalue(pattern.value)
                        is Chainid -> vc.data.Chainid(pattern.value)
                        is Codesize -> vc.data.Codesize(pattern.value)
                        is Coinbase -> vc.data.Coinbase(pattern.value)
                        is Difficulty -> vc.data.Difficulty(pattern.value)
                        is Extcodehash -> vc.data.Extcodehash(pattern.value, pattern.addr)
                        is Extcodesize -> vc.data.Extcodesize(pattern.value, pattern.addr)
                        is Gas -> vc.data.Gas(pattern.value)
                        is Gaslimit -> vc.data.Gaslimit(pattern.value)
                        is Gasprice -> vc.data.Gasprice(pattern.value)
                        is Msize -> vc.data.Msize(pattern.value)
                        is Number -> vc.data.Number(pattern.value)
                        is Origin -> vc.data.Origin(pattern.value)
                        is Selfbalance -> vc.data.Selfbalance(pattern.value)
                        is Timestamp -> vc.data.Timestamp(pattern.value)
                        is Revert -> vc.data.Revert(pattern.offset, pattern.size)
                        is Selfdestruct -> vc.data.Selfdestruct(pattern.addr)
                        is All_sload -> AllSload(pattern.value, pattern.loc)
                        is All_sstore -> AllSstore(pattern.loc, pattern.v)
                        is All_tload -> AllTload(pattern.value, pattern.loc)
                        is All_tstore -> AllTstore(pattern.loc, pattern.v)
                        is Create1 -> vc.data.Create1(pattern.value,pattern.callValue, pattern.offset, pattern.len, pattern.pc)
                        is Create2 -> vc.data.Create2(pattern.value,pattern.callValue, pattern.offset, pattern.len, pattern.salt, pattern.pc)
                        is Call -> vc.data.Call(
                            output = pattern.value,
                            gas = pattern.gas,
                            callee = pattern.addr,
                            value = pattern.callValue,
                            argsOffset = pattern.argsOffset,
                            argsSize = pattern.argsLength,
                            retOffset = pattern.retOffset,
                            retSize = pattern.retLength,
                            sighash = pattern.selector,
                            pc = pattern.pc
                        )
                        is Callcode -> vc.data.Callcode(
                            output = pattern.value,
                            gas = pattern.gas,
                            addr = pattern.addr,
                            value = pattern.callValue,
                            argsOffset = pattern.argsOffset,
                            argsLength = pattern.argsLength,
                            retOffset = pattern.retOffset,
                            retLength = pattern.retLength,
                            sighash = pattern.selector,
                            pc = pattern.pc
                        )
                        is Delegatecall -> vc.data.Delegatecall(
                            output = pattern.value,
                            gas = pattern.gas,
                            addr = pattern.addr,
                            argsOffset = pattern.argsOffset,
                            argsLength = pattern.argsLength,
                            retOffset = pattern.retOffset,
                            retLength = pattern.retLength,
                            sighash = pattern.selector,
                            pc = pattern.pc
                        )
                        is Staticcall -> vc.data.Staticcall(
                            output = pattern.value,
                            gas = pattern.gas,
                            addr = pattern.addr,
                            argsOffset = pattern.argsOffset,
                            argsLength = pattern.argsLength,
                            retOffset = pattern.retOffset,
                            retLength = pattern.retLength,
                            sighash = pattern.selector,
                            pc = pattern.pc
                        )
                        is Returndatasize -> vc.data.Returndatasize(pattern.value)
                    }
                }
            }
        }
    }
}

private class Transformer(val f: (TACSymbol) -> TACSymbol) : DefaultTACExprTransformer() {
    override fun transformVar(exp: TACExpr.Sym.Var): TACExpr {
        return this.f(exp.s).asSym()
    }
}
