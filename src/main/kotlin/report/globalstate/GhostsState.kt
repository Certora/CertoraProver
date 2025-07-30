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

package report.globalstate

import analysis.CmdPointer
import analysis.storage.InstantiatedDisplayPath
import datastructures.stdcollections.*
import log.*
import report.calltrace.CallInstance
import report.calltrace.CallTrace
import report.calltrace.formatter.CallTraceValueFormatter
import report.calltrace.formatter.CallTraceValue
import solver.CounterexampleModel
import solver.CounterexampleModel.ResolvingFailure
import spec.CVLKeywords
import spec.QUANTIFIED_VAR_TYPE
import utils.Range
import spec.cvlast.CVLType
import spec.cvlast.GhostSort
import utils.*
import vc.data.SnippetCmd.CVLSnippetCmd.*
import vc.data.SnippetCmd.EVMSnippetCmd.StorageGlobalChangeSnippet
import vc.data.TACBuiltInFunction
import vc.data.TACSymbol
import vc.data.state.ConcreteTacValue
import vc.data.state.TACValue
import verifier.SKOLEM_VAR_NAME

internal data class GhostId(val name: String, val sort: GhostSort)
private typealias KnownValuesAtPath = MutableMap<InstantiatedDisplayPath.Root, State>
@JvmInline
private value class AllKnownValues(val map : MutableMap<GhostId, KnownValuesAtPath>)
    : MutableMap<GhostId, KnownValuesAtPath> by map {
    fun deepCopy() = AllKnownValues(
        map.toMutableMap().also { freshMap -> freshMap.mapValuesInPlace { (_, v) -> v.toMutableMap() } }
    )
}


/**
 * A part of [GlobalState].
 * Represents the state of all ghosts (variables, mappings, functions) within a [GlobalState].
 */
internal class GhostsState(
    private val seqGen: SequenceGenerator,
    private val model: CounterexampleModel,
    private val variablesState: VariablesState,
) : DebugAdapterVariableState{
    /* contains data of non-persistent ghosts, and may change from snapshot loads */
    private val idToNonPersistent = AllKnownValues(mutableMapOf())
    /* contains data of persistent ghosts, does not change from snapshots loads */
    private val idToPersistent: MutableMap<GhostId, KnownValuesAtPath> = mutableMapOf()

    private val ghostAccessData = GhostAccessData(seqGen, model)

    private val snapshots: MutableMap<String, AllKnownValues> = mutableMapOf()
    private val backupSnapshots: MutableMap<Int, AllKnownValues> = mutableMapOf()

    private var printCounter = 0

    init {
        initializeAllPathsToDontCare()
        invalidateToFirstHavoc()
    }

    /**
     * returns the values of a [id], as a [MutableMap].
     * in other words, this returned map allows changing the stored values.
     */
    private fun valuesOf(id: GhostId): KnownValuesAtPath {
        val target = if (this.ghostAccessData.isPersistent[id] == true) {
            this.idToPersistent
        } else {
            this.idToNonPersistent
        }

        return target.getOrPut(id, ::mutableMapOf)
    }

    private val allIds get() = idToNonPersistent + idToPersistent

        /**
     * the [InstantiatedDisplayPath] of each [GhostAccess] here is the "last" one seen in the code,
     * thus the only one we expect to be valid.
     * any [GhostAccess] that failed to instantiate will not appear in [ghostAccessData] and will be skipped.
     * the same criteria is used to reject those [GhostAccess] later, when iterating over the code in [CallTrace].
     */
    private fun initializeAllPathsToDontCare() {
        for ((access, idp) in ghostAccessData.instantiatedDisplayPaths) {
            valuesOf(access.toGhostId()).put(idp, State.DontCare())
        }
    }

    private fun invalidateToFirstHavoc() {
        val uniqueIds = seqGen
            .snippets()
            .filterIsInstance<GhostAccess>()
            .mapToSet(GhostAccess::toGhostId)

        for (ghostId in uniqueIds) {
            invalidateToNextHavoc(ghostId, zeroPtr)
        }
    }

    /**
     * returns a new [KnownValuesAtPath], with all [State] replaced with havoc
     * (if the ghost is read before the next time the ghost is havoced) or don't care (otherwise).
     * also preserves [State.changed] for each value.
     *
     * for each ghost G, the program code will be split by havocs of G.
     * the commands from program start up to the first havoc are processed by [invalidateToFirstHavoc].
     * additional sections will be dealt by [handleHavoc], as each havoc command is read by [CallTrace]
     */
    private fun invalidateToNextHavoc(ghostId: GhostId, afterPreviousHavoc: CmdPointer) {
        val oldValues = valuesOf(ghostId).toMap()

        // take a mutable "pointer" to the values for this ghost ID,
        // then wipe it and fill it with updated values
        val newValues = valuesOf(ghostId)
        newValues.clear()

        val accessesUntilHavoc = seqGen
            .snippets(afterPreviousHavoc)
            .takeWhile { it !is GhostHavocSnippet || it.toGhostId() != ghostId }
            .mapNotNull { it as? GhostAccess }
            .filter { it.toGhostId() == ghostId }

        /**
         * update the data for each [InstantiatedDisplayPath] if it was not seen yet.
         */
        for (access in accessesUntilHavoc) {
            val idp = ghostAccessData
                .instantiatedDisplayPaths[access]
                ?.takeUnless { idp -> idp in newValues }
                ?: continue

            newValues[idp] = when (access) {
                is GhostAssignment,
                is SumGhostUpdate -> State.DontCare()

                is GhostRead,
                is SumGhostRead -> {
                    // failure here is unexpected, but let's try again with the next access.
                    val (tv, cvlType) = valueAndPureCVLType(access.accessed) ?: continue

                    State.WithValue(ComputationalTypes.HAVOC, tv, cvlType)
                }
            }
        }

        /** fill the rest with [State.DontCare], also preserve previous [State.changed] value for entire map */
        for ((idp, oldState) in oldValues - newValues) {
            val state = State.DontCare()
            state.changed = oldState.changed
            newValues[idp] = state
        }
    }

    private fun valueAndPureCVLType(v: TACSymbol.Var): Pair<TACValue, CVLType.PureCVLType>? =
        model.valueAndPureCVLType(v)
            .onRight { err ->
                logger.warn { "while checking model for symbol in ghost access: got $err. the symbol may be dangling." }
            }
            .leftOrNull()


    /** updates data for ghost from the observed value in snippet */
    fun handleGhostAccess(access: GhostAccess) {
        val idp = ghostAccessData.instantiatedDisplayPaths[access] ?: return
        val ghostId = access.toGhostId()
        val knownValues = valuesOf(ghostId)

        val computationalType = when (access) {
            is GhostAssignment -> variablesState.computationalTypeForRHS(setOf(access.accessed))
            is GhostRead -> computationalTypeForRead(variablesState[access.accessed]?.computationalType, ghostId)
            is SumGhostRead -> computationalTypeForRead(variablesState[access.accessed]?.computationalType, ghostId)
            is SumGhostUpdate -> variablesState.computationalTypeForRHS(setOf(access.accessed))
        }

        check(computationalType != ComputationalTypes.DONT_CARE) { "$ghostId has been accessed, but has state DONT_CARE" }

        val (tv, cvlType) = valueAndPureCVLType(access.accessed) ?: return
        val state = State.WithValue(computationalType, tv, cvlType)

        when (access) {
            is SumGhostUpdate,
            is GhostAssignment -> {
                state.changed = true
                knownValues[idp] = state
            }

            is SumGhostRead,
            is GhostRead -> {
                val previousState = knownValues[idp]
                check(previousState is State.WithValue) { "$ghostId has been read, but has state $previousState" }

                if (state.tv != previousState.tv) {
                    logger.warn { "last seen value of ghost $ghostId was ${previousState.tv}, but has unexpectedly changed to ${state.tv}" }
                    knownValues[idp] = state
                }
            }
        }
    }

    private fun computationalTypeForRead(fromVariable: ComputationalTypes?, ghostId: GhostId) =
        if (fromVariable != null) {
            fromVariable
        } else {
            logger.warn { "display path of ghost $ghostId not found in VariablesStates" }
            ComputationalTypes.UNKNOWN
        }

    fun handleHavoc(sc: GhostHavocSnippet, afterHavoc: CmdPointer) {
        val ghostId = sc.toGhostId()

        /** CERT-4155 it is possible that the havoc command matching this snippet has been removed due to some optimization */
        if (ghostId in idToNonPersistent) {
            invalidateToNextHavoc(ghostId, afterHavoc)
        }
    }

    fun handleAllHavocs(afterHavoc: CmdPointer) {
        // assumes already been populated
        for (ghostId in this.allIds.keys) {
            invalidateToNextHavoc(ghostId, afterHavoc)
        }
    }

    /**
     * this table is constructed once on class init.
     */

    internal fun handleStorageGlobalChanges(sc: StorageGlobalChangeSnippet) {
        when (sc) {
            is StorageGlobalChangeSnippet.StorageTakeSnapshot -> {
                snapshots[sc.lhs.namePrefix] = if (sc.rhs == null || sc.rhs.namePrefix == CVLKeywords.lastStorage.keyword) {
                    idToNonPersistent.deepCopy()
                } else {
                    snapshots[sc.rhs.namePrefix]?.deepCopy()
                            ?: throw IllegalStateException("Failed to find the storage snapshot of ${sc.rhs} when handling its assignment to ${sc.lhs}")
                }
            }
            is StorageGlobalChangeSnippet.StorageRestoreSnapshot -> {
                val snapshot = snapshots[sc.name.namePrefix]
                    ?: throw IllegalStateException("Failed to find the storage snapshot of ${sc.name} when restoring.")
                idToNonPersistent.overwriteWith(snapshot)
            }
            is StorageGlobalChangeSnippet.StorageBackupPoint -> {
                backupSnapshots[sc.calleeTxId] = idToNonPersistent.deepCopy()
            }
            is StorageGlobalChangeSnippet.StorageRevert -> {
                val snapshot = backupSnapshots[sc.calleeTxId]
                    ?: throw IllegalStateException("Failed to revert the storage to state before call #${sc.calleeTxId}.")
                idToNonPersistent.overwriteWith(snapshot)
            }
            is StorageGlobalChangeSnippet.StorageHavocContract,
            is StorageGlobalChangeSnippet.StorageResetContract -> { /** not my department */ }
        }
        }

    internal fun addGhostsStateToCallTrace(
        globalStateCallInstance: CallInstance.StorageTitleInstance,
        storageToShowSym: TACSymbol.Var?,
        formatter: CallTraceValueFormatter,
    ) {
        printCounter += 1

        val nonPersistent = if (storageToShowSym != null) {
            snapshots[storageToShowSym.namePrefix] ?: throw IllegalStateException("Unknown storage ${storageToShowSym.namePrefix}.")
        } else {
            idToNonPersistent
        }

        val toPrint = nonPersistent + this.idToPersistent

        val sorted = toPrint
            .values
            .flatMap(KnownValuesAtPath::toList)
            .sortedBy { (idp, _) -> idp }

        val parent = CallInstance.StorageTitleInstance("Ghosts State")

        for ((idp, state) in sorted) {
            val idpString = idp.toFormattedString(formatter)

            val observedValue = when (state) {
                is State.DontCare -> CallTraceValue.Empty
                is State.WithValue -> CallTraceValue.CVLType(state.tv, state.cvlType)
            }

            val changed = state.changed
            state.changed = false

            val value = CallInstance.StorageValue(idpString, observedValue)
            val range = ghostAccessData.idpToRange[idp] as? Range.Range
            val child = CallInstance.GhostValueInstance(state.compType.callEndStatus, range, value, changed, formatter)
            parent.addChild(child)
            Logger.regression { "CallTrace: Ghosts State added access path for ${idp.name} (at ${range})." }
        }

        if (parent.children.isNotEmpty()) {
            globalStateCallInstance.addChild(parent)
        }
    }

    /** the logger is used here in cases that should never be happening but are still checked in the interest of not swallowing errors */
    private fun MutableMap<GhostId, KnownValuesAtPath>.overwriteWith(other: Map<GhostId, KnownValuesAtPath>) {
        val isPersistent = this@GhostsState.ghostAccessData.isPersistent

        for (ghostId in this.keys) {
            when (isPersistent[ghostId]) {
                true -> { /** persistent ghosts don't change on storage overwrite */ }

                false -> {
                    val newKnownValues = other[ghostId]
                    if (newKnownValues != null) {
                        this[ghostId] = newKnownValues
                    } else {
                        logger.warn { "$ghostId must be present in map since it was added at init, but is missing from storage snapshot" }
                    }
                }

                null -> logger.warn { "for ghost $ghostId: ghost exists in storage snapshot but was not detected during initialization" }
            }
        }
    }

    override fun toInstantiateDisplayWithValue(): Map<InstantiatedDisplayPath, TACValue> {
        return (idToPersistent + idToNonPersistent.map).entries.flatMap {(id, kvp) ->
            kvp.entries.map {idpToState ->
                when(id.sort){
                    is GhostSort.Variable -> InstantiatedDisplayPath.Root(id.name)
                    is GhostSort.Mapping,
                    is GhostSort.Function -> idpToState.key
                } to when (val v = idpToState.value) {
                    is State.WithValue -> v.tv
                    is State.DontCare -> TACValue.Uninitialized
                }
            }
        }.toMap()
    }
}

private fun GhostAccess.toGhostId() = GhostId(name, sort)
private fun GhostHavocSnippet.toGhostId() = GhostId(name, sort)

private sealed class State {
    abstract val compType: ComputationalTypes
    var changed: Boolean = false

    class DontCare : State() {
        override val compType get() = ComputationalTypes.DONT_CARE
    }

    data class WithValue(override val compType: ComputationalTypes, val tv: TACValue, val cvlType: CVLType.PureCVLType) : State() {
        init {
            require(compType != ComputationalTypes.DONT_CARE) { "$compType is not allowed to have a value" }
        }
    }
}


/**
 * data computed from [GhostAccess] snippets at init of [GhostsState], remains valid throughout the duration of the rule
 *
 * getting the model value can fail, if [TACValue]s contain unexpected values, or do not have a value in [CounterexampleModel].
 * the boilerplate of dealing with fallibility is kept within the scope of this class
 * */
internal class GhostAccessData(seqGen: SequenceGenerator, model: CounterexampleModel) {
    val isPersistent: MutableMap<GhostId, Boolean> = mutableMapOf()
    val instantiatedDisplayPaths: MutableMap<GhostAccess, InstantiatedDisplayPath.Root> = mutableMapOf()
    val idpToRange: MutableMap<InstantiatedDisplayPath.Root, Range> = mutableMapOf()

    init {
        val ghostAccesses = seqGen.snippets().filterIsInstance<GhostAccess>()

        for (access in ghostAccesses) {
            val ghostId = access.toGhostId()
            val persistent = access.persistent

            val existingData = isPersistent[ghostId]
            if (existingData != null) {
                check(existingData == persistent) {
                    "internal invariant violated while reading snippet for ghost ${access.name}: ghost changed from persistent to non-persistent (or vice-versa)"
                }
            }

            isPersistent[ghostId] = persistent

            access.toInstantiatedDisplayPath(model)
                .onLeft { idp ->
                    instantiatedDisplayPaths[access] = idp
                    idpToRange[idp] = access.range
                }
                .onRight { err ->
                    logger.warn { "while instantiating ghost access $access: got $err. ghost access will be ignored" }
                }
        }
    }
}

/**
 * Instantiates a [GhostAccess] into an [InstantiatedDisplayPath] using a [CounterexampleModel].
 *
 * If all [TACValue]s referenced in this [GhostAccess] are still valid, returns an [InstantiatedDisplayPath].
 * this method can fail, if [TACValue]s contain unexpected values, or do not have a value in [model].
 * The boilerplate of dealing with fallibility is kept within the scope of this function.
 */
fun GhostAccess.toInstantiatedDisplayPath(model: CounterexampleModel): Either<InstantiatedDisplayPath.Root, ResolvingFailure> {
    fun instantiateIndex(idx: TACSymbol.Var?): Either<Pair<TACValue, CVLType.PureCVLType>, ResolvingFailure> =
        if (idx != null) {
            model.valueAndPureCVLType(idx).bindRight { _ ->
                val value = idx.meta[SKOLEM_VAR_NAME]?.let { vn ->
                    idx.copy(namePrefix = vn)
                }?.let { model.valueAsTACValue(it) } ?: return@bindRight ResolvingFailure.NotFound(idx).toRight()
                val cvlType = idx.meta[QUANTIFIED_VAR_TYPE]
                    ?: return@bindRight ResolvingFailure.MissingMetaKey(QUANTIFIED_VAR_TYPE).toRight()
                Pair(value, cvlType).toLeft()
            }.bindLeft { (tv, cvlType) ->
                if (tv is ConcreteTacValue && idx.tag == TACBuiltInFunction.Hash.skeySort) {
                    model.storageKeyToInteger(tv).mapLeft { it to cvlType }
                } else {
                    (tv to cvlType).toLeft()
                }
            }
        } else {
            (TACValue.SumIndex to CVLType.PureCVLType.Bottom).toLeft()
        }

    return indices.map { instantiateIndex(it) }
        .fold(
            Either.Left<List<Pair<TACValue, CVLType.PureCVLType>>>(listOf()) as Either<List<Pair<TACValue, CVLType.PureCVLType>>, ResolvingFailure>
        ) { acc, either -> acc.bindLeft { list -> either.mapLeft { list + it } }
        }.mapLeft { indices ->
            InstantiatedDisplayPath.Root(
                name = name,
                next = InstantiatedDisplayPath.GhostAccess(
                    indices = indices,
                    sort = this.sort,
                    next = null
                )
            )
        }
}
