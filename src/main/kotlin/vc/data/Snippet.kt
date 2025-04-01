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
package vc.data

import allocator.Allocator
import allocator.GenerateRemapper
import allocator.GeneratedBy
import analysis.*
import analysis.icfg.CallGraphBuilder
import analysis.storage.DisplayPath
import analysis.storage.StorageAnalysisResult
import com.certora.collect.*
import compiler.SourceSegment
import datastructures.stdcollections.*
import decompiler.SourceParseResult
import report.calltrace.CVLReportLabel
import report.calltrace.CallInstance
import spec.cvlast.*
import spec.cvlast.typedescriptors.EVMTypeDescriptor
import spec.cvlast.typedescriptors.VMTypeDescriptor
import tac.CallId
import tac.MetaKey
import tac.Tag
import utils.*
import vc.data.TACMeta.CVL_DISPLAY_NAME
import vc.data.TACMeta.SCOPE_SNIPPET_END
import java.io.Serializable
import java.math.BigInteger

/**
 * Result for a [ScopeSnippet]'s enforcement of some properties in a scope in a [TACProgram].
 */
sealed class EnforceScopeResult {

    object Success : EnforceScopeResult()

    /**
     * Failure of the scope's enforcement.
     * [scopeSnippetCmd] is the snippet [TACCmd] which enforces the scope, located in
     * [ptr] in the [TACProgram].
     * [errorMsgs] are the error messages of the causes for this [Failure].
     */
    data class Failure(val scopeSnippetCmd: ScopeSnippet, val ptr: CmdPointer, val errorMsgs: List<String>) :
        EnforceScopeResult() {

        constructor(scopeSnippetCmd: ScopeSnippet, ptr: CmdPointer, errorMsg: String) : this(
            scopeSnippetCmd,
            ptr,
            listOf(errorMsg)
        )
    }
}

/**
 * Joins this list of [EnforceScopeResult]s into an exception if at least one of the results is
 * [EnforceScopeResult.Failure].
 * The exception holds all the messages from all the [EnforceScopeResult.Failure]s.
 * If no [EnforceScopeResult.Failure] occurs in this list, null is returned.
 */
fun <E : Exception> List<EnforceScopeResult>.joinToExceptionOrNull(exceptionBuilder: (String) -> E): E? {
    return this.filterIsInstance<EnforceScopeResult.Failure>().takeIf { it.isNotEmpty() }
        ?.joinToString(separator = System.lineSeparator()) {
            "While enforcing the scope of snippet ${it.scopeSnippetCmd} @ ${it.ptr}, encountered the following failures:${
                System.lineSeparator()
            }${it.errorMsgs.joinToString(separator = System.lineSeparator())}"
        }?.let(exceptionBuilder)
}

/**
 * Maps this List of errors to an [EnforceScopeResult].
 * If there are no errors, a [EnforceScopeResult.Success] is returned.
 * Otherwise, an [EnforceScopeResult.Failure] with these errors is returned.
 */
fun List<String>.toEnforceResult(scopeSnippetCmd: ScopeSnippet, ptr: CmdPointer): EnforceScopeResult =
    when (this.size) {
        0 -> EnforceScopeResult.Success
        else -> EnforceScopeResult.Failure(
            scopeSnippetCmd, ptr, this
        )
    }

fun String.toEnforceScopeResultFailure(scopeSnippetCmd: ScopeSnippet, ptr: CmdPointer): EnforceScopeResult.Failure =
    EnforceScopeResult.Failure(scopeSnippetCmd, ptr, this)

/**
 * Snippets which scope set of subsequent [TACCmd]s.
 * Such a set is guaranteed to maintain a set of properties,
 * for each created [CoreTACProgram].
 */
interface ScopeSnippet: Serializable {

    companion object {
        /**
         * [MetaKey] used as an indicator where is the end of the enforced scope.
         */
        val endMetaKey = SCOPE_SNIPPET_END

        fun toAnnotation() = TACCmd.Simple.AnnotationCmd(endMetaKey)

    }

    /**
     * Enforces set of properties for some subsequent [TACCmd]s in [prog],
     * starting from [scopeSnippetPtr].
     * Returns [EnforceScopeResult] for the scope's enforcement of [scopeSnippetPtr] in [prog].
     * It is assumed that the scope snippet is located at [scopeSnippetPtr] in [prog].
     */
    fun enforceScope(scopeSnippetPtr: CmdPointer, prog: TACCommandGraph): EnforceScopeResult
}

/**
 * A snippet for which we derive a parse tree of a CVL expression, using the out symbol [cvlExpOutSym].
 */
interface WithParseTree {
    /* currently, the only use case where we would prefer to have in a snippet a TACSymbol.Var
    rather than a general TACSymbol, is where it is used as an out symbol of a CVL expression,
    to generate a corresponding parse tree in the CallTrace */
    val cvlExpOutSym: TACSymbol.Var
}

/**
 * A scoping snippet which describes an Assert command.
 */
sealed interface AssertSnippet<T : AssertSnippet<T>> : ScopeSnippet {
    /**
     * A condition the underlying Assert command verifies.
     * Expected to have a boolean tag.
     */
    val assertCond: TACSymbol

    /** Whether to display the snippet only if it failed or even if it passed. */
    val displayIfPassed: Boolean

    override fun enforceScope(
        scopeSnippetPtr: CmdPointer,
        prog: TACCommandGraph
    ): EnforceScopeResult =

        // iterate until encountering [endMetaKey]
        prog.iterateBlock(scopeSnippetPtr, excludeStart = true).takeUntil { lTACCmd ->
            (lTACCmd.cmd as? TACCmd.Simple.AnnotationCmd)?.annot?.k == ScopeSnippet.endMetaKey
        }?.let { snippetScope ->
            val singleAssert = snippetScope.singleOrNull { lTACCmd ->
                lTACCmd.cmd is TACCmd.Simple.AssertCmd && lTACCmd.cmd.o == assertCond
            }
            val errorMsgs: List<String> = emptyList<String>().takeIf { singleAssert != null }
                ?: listOf("Expected to find exactly one assert command in the snippet scope (found $snippetScope)")
            errorMsgs.toEnforceResult(this, scopeSnippetPtr)
        }
            ?: "While iterating over the given [TACCommandGraph], starting at ${
                scopeSnippetPtr
            }, failed to find an end annotation in block ${scopeSnippetPtr.block}".toEnforceScopeResultFailure(
                this,
                scopeSnippetPtr
            )
}


/**
 * These commands can be seen as "debug information" that is attached to TAC programs.
 * These commands are wrapped by annotation commands, so they will not be folded/disappear
 * during transformations/optimizations on the TAC program.
 * These commands should be used to build the CallTrace.
 */
@KSerializable
@Treapable
sealed class SnippetCmd: AmbiSerializable {
    /**
     * Lifts this [SnippetCmd] to an annotation command ([[TACCmd.Simple.AnnotationCmd]s]) if
     * [snippetsAreEnabled] returns true. Otherwise, returns an annotation command with the [SnippetCreationDisabled] snippet
     * that effectively prevents this [SnippetCmd] from occurring in the [TACProgram].
     */
    fun toAnnotation(isDisabled: Boolean = false) = if (!isDisabled) {
        TACCmd.Simple.AnnotationCmd(TACMeta.SNIPPET, this)
    } else {
        TACCmd.Simple.AnnotationCmd(
            TACMeta.SNIPPET, SnippetCreationDisabled
        )
    }

    /**
     * A Snippet that serves as a replacement dummy for other, actual snippets
     * whose generation was disabled e.g. through a config flag.
     */
    @KSerializable
    object SnippetCreationDisabled: SnippetCmd() {
        override fun hashCode() = hashObject(this)
        private fun readResolve(): Any = SnippetCreationDisabled
    }

    /**
     * Snippets which carry debug information from the EVM.
     */
    @KSerializable
    sealed class EVMSnippetCmd : SnippetCmd() {
        @KSerializable
        @GenerateRemapper
        data class EVMFunctionReturnWrite(
            val returnbufOffset: BigInteger,
            val returnValueSym: TACSymbol,
            @GeneratedBy(Allocator.Id.CALL_ID) val callIndex: Int
        ) : EVMSnippetCmd(), RemapperEntity<EVMFunctionReturnWrite>,
            TransformableSymEntityWithRlxSupport<EVMFunctionReturnWrite> {
            override fun transformSymbols(f: (TACSymbol) -> TACSymbol) = copy(returnValueSym = f(returnValueSym))
        }

        @KSerializable
        sealed class ContractSourceSnippet : EVMSnippetCmd(), TransformableVarEntityWithSupport<ContractSourceSnippet> {

            // a snippet generated whenever a named variable is assigned to or returned.
            @KSerializable
            data class AssignmentSnippet(val parse: SourceParseResult.Success, val lhs: TACSymbol.Var): ContractSourceSnippet() {
                override val support: Set<TACSymbol.Var> = setOf(lhs)
                override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var): ContractSourceSnippet = copy(lhs = f(lhs))
            }
        }

        sealed interface EVMStorageAccess {
            enum class AccessKind { STORE, LOAD, HAVOC }

            // fun instead of val so serialization plays nice with enum class
            fun kind(): AccessKind

            /** Symbol of the read/written value. */
            val value: TACSymbol?

            /** * Type of the value loaded/stored from/at the underlying storage slot. */
            val storageType: EVMTypeDescriptor.EVMValueType?

            /** Address of the contract whose storage is being accessed. */
            val contractInstance: BigInteger

            val range: Range.Range?
        }

        /**
         * These commands represent storage accesses done in the Solidity code.
         */
        @KSerializable
        sealed class StorageSnippet : EVMSnippetCmd(), EVMStorageAccess, TransformableSymEntityWithRlxSupport<StorageSnippet> {
            /**
             * Storage access path which will be displayed in the CallTrace.
             */
            abstract val displayPath: DisplayPath

            abstract override val value: TACSymbol // assert it's non-null below this class

            protected abstract fun copy(v: TACSymbol, disPath: DisplayPath): StorageSnippet

            override fun transformSymbols(f: (TACSymbol) -> TACSymbol): StorageSnippet = copy(
                v = f(value),
                disPath = displayPath.transformSymbols(f),
            )

            @KSerializable
            @GenerateRemapper
            data class LoadSnippet(
                override val value: TACSymbol,
                override val displayPath: DisplayPath,
                override val storageType: EVMTypeDescriptor.EVMValueType?,
                override val contractInstance: BigInteger,
                override val range: Range.Range?,
                val linkableStorageReadId: LinkableStorageReadId
            ) : StorageSnippet(), RemapperEntity<LoadSnippet> {
                constructor(
                    value: TACSymbol,
                    displayPath: DisplayPath,
                    storageType: EVMTypeDescriptor.EVMValueType?,
                    contractInstance: BigInteger,
                    range: Range.Range?,
                    storageLoadCmd: TACCmd.Simple.AssigningCmd
                ) : this(
                    value,
                    displayPath,
                    storageType,
                    contractInstance,
                    range,
                    LinkableStorageReadId(storageLoadCmd, displayPath)
                )

                /**
                 * Can the storage slot that corresponds to [displayPath], be
                 * --linked and make the target address of an external call, resolved?
                 */
                @KSerializable
                @Treapable
                sealed class LinkableStorageReadId : AmbiSerializable, UniqueIdEntity<LinkableStorageReadId> {
                    companion object {
                        /**
                         * Returns a [LinkableStorageReadId.Id] if [storageLoadCmd]
                         * (expected to be either a [TACCmd.Simple.AssigningCmd.WordLoad], or a [TACCmd.Simple.AssigningCmd.AssignExpCmd]
                         * whose rhs is a [TACSymbol.Var] representing a (split) storage slot read),
                         * was determined by the [CallGraphBuilder] to load an unresolved target address of an external call
                         * ([CallGraphBuilder.ContractStorageRead]), and [displayPath] suggests what this address
                         * could be resolved by the user using --link.
                         * Otherwise, returns [LinkableStorageReadId.None]
                         */
                        operator fun invoke(
                            storageLoadCmd: TACCmd.Simple.AssigningCmd,
                            displayPath: DisplayPath
                        ): LinkableStorageReadId =
                            storageLoadCmd.meta.find(CallGraphBuilder.ContractStorageRead.META_KEY)?.let {
                                // Currently, CVT only enables linking of either root storage slots or struct fields.
                                it.id.takeIf { (displayPath is DisplayPath.Root) || displayPath is DisplayPath.FieldAccess }
                                    ?.let(LoadSnippet::Id) ?: None
                            } ?: None
                    }

                }

                override fun copy(v: TACSymbol, disPath: DisplayPath): StorageSnippet {
                    return copy(value = v, displayPath = disPath)
                }

                @KSerializable
                object None : LinkableStorageReadId() {
                    fun readResolve(): Any = None
                    override fun hashCode() = hashObject(this)
                    override fun mapId(f: (Any, Int, () -> Int) -> Int): None {
                        return this
                    }
                }

                @GenerateRemapper
                @KSerializable
                data class Id(@GeneratedBy(Allocator.Id.STORAGE_READ) val id: Int) : LinkableStorageReadId(),
                    RemapperEntity<LinkableStorageReadId>

                override fun kind(): EVMStorageAccess.AccessKind = EVMStorageAccess.AccessKind.LOAD
            }

            @KSerializable
            data class StoreSnippet(
                override val value: TACSymbol,
                override val displayPath: DisplayPath,
                override val storageType: EVMTypeDescriptor.EVMValueType?,
                override val contractInstance: BigInteger,
                override val range: Range.Range?,
            ) : StorageSnippet() {
                override fun copy(v: TACSymbol, disPath: DisplayPath): StorageSnippet {
                    return copy(value = v, displayPath = disPath)
                }
            }

            /** a read via direct storage access from the path corresponding to [displayPath] at [contractInstance] */
            @KSerializable
            data class DirectStorageLoad(
                override val value: TACSymbol,
                override val displayPath: DisplayPath,
                override val storageType: EVMTypeDescriptor.EVMValueType?,
                override val contractInstance: BigInteger,
                override val range: Range.Range?,
            ) : StorageSnippet() {
                override fun copy(v: TACSymbol, disPath: DisplayPath) = copy(value = v, displayPath = disPath)
            }

            /** a havoc of direct storage path corresponding to [displayPath] at [contractInstance] */
            @KSerializable
            data class DirectStorageHavoc(
                override val value: TACSymbol,
                override val displayPath: DisplayPath,
                override val storageType: EVMTypeDescriptor.EVMValueType?,
                override val contractInstance: BigInteger,
                override val range: Range.Range?,
            ) : StorageSnippet() {
                override fun copy(v: TACSymbol, disPath: DisplayPath) = copy(value = v, displayPath = disPath)
            }

            override fun kind(): EVMStorageAccess.AccessKind = EVMStorageAccess.AccessKind.STORE
        }

        /**
         * Balance of address [addr] was accessed.
         * Amount is [balance].
         */
        @KSerializable
        data class ReadBalanceSnippet(val balance: TACSymbol, val addr: TACSymbol) : EVMSnippetCmd(), TransformableSymEntityWithRlxSupport<ReadBalanceSnippet> {
            override fun transformSymbols(f: (TACSymbol) -> TACSymbol): ReadBalanceSnippet = ReadBalanceSnippet(
                balance = f(balance),
                addr = f(addr)
            )
        }

        /** Balances were havoced. */
        @KSerializable
        object HavocBalanceSnippet : EVMSnippetCmd() {
            fun readResolve(): Any = HavocBalanceSnippet
            override fun hashCode() = hashObject(this)
        }

        /**
         * These commands represent changes in the balances of a receiver and a sender when a transfer is made.
         * [srcAccountInfo] and [trgAccountInfo] are operands for the sender and the receiver respectively.
         * [transferredAmount] is the amount being transferred.
         */
        @KSerializable
        data class TransferSnippet(
            val srcAccountInfo: AccountInfo,
            val trgAccountInfo: AccountInfo,
            val transferredAmount: TACSymbol
        ) : EVMSnippetCmd(), TransformableSymEntityWithRlxSupport<TransferSnippet> {

            constructor(
                srcBalanceOld: TACSymbol,
                srcBalanceNew: TACSymbol,
                trgBalanceOld: TACSymbol,
                trgBalanceNew: TACSymbol,
                addrSrc: TACSymbol,
                addrTrg: TACSymbol,
                transferredAmount: TACSymbol
            ) : this(
                AccountInfo(srcBalanceOld, srcBalanceNew, addrSrc),
                AccountInfo(trgBalanceOld, trgBalanceNew, addrTrg),
                transferredAmount
            )

            /**
             * [old] is a symbol that represents a balance before transfer is made.
             * [new] is a symbol that represents a balance after transfer is made.
             * [addr] is a symbol that represents the address of the sender/receiver of the transfer.
             */
            @KSerializable
            @Treapable
            data class AccountInfo(val old: TACSymbol, val new: TACSymbol, val addr: TACSymbol) :
                TransformableSymEntity<AccountInfo>, Serializable {
                override fun transformSymbols(f: (TACSymbol) -> TACSymbol): AccountInfo = copy(
                    old = f(old),
                    new = f(new),
                    addr = f(addr)
                )
            }
            override fun transformSymbols(f: (TACSymbol) -> TACSymbol): TransferSnippet = copy(
                srcAccountInfo = srcAccountInfo.transformSymbols(f),
                trgAccountInfo = trgAccountInfo.transformSymbols(f),
                transferredAmount = f(transferredAmount)
            )
        }

        /**
         * These commands represent global changes in the storage (aka storage of more than just one display path).
         */
        @KSerializable
        sealed class StorageGlobalChangeSnippet : EVMSnippetCmd(),
            TransformableVarEntity<StorageGlobalChangeSnippet> {
            override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var) = this

            /**
             * Storage snapshot was taken: "storage [lhs] = [rhs]", for example "storage init = lastStorage".
             * When rhs is null we take the existing storage state
             */
            @KSerializable
            data class StorageTakeSnapshot(val lhs: TACSymbol.Var, val rhs: TACSymbol.Var? = null) :
                StorageGlobalChangeSnippet() {
                override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var) =
                    StorageTakeSnapshot(lhs = f(lhs), rhs = rhs?.let(f))
            }

            /**
             * Storage was restored to a previous state: "function() at [name]", for example "inc() at init".
             */
            @KSerializable
            data class StorageRestoreSnapshot(val name: TACSymbol.Var) :
                StorageGlobalChangeSnippet() {
                override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var) =
                    StorageRestoreSnapshot(f(name))
            }

            /**
             * Storage state was saved before a function call, [calleeTxId] is an auto generated unique index for the call.
             */
            @KSerializable
            @GenerateRemapper
            data class StorageBackupPoint(@GeneratedBy(Allocator.Id.CALL_ID) val calleeTxId: Int) :
                RemapperEntity<StorageBackupPoint>, StorageGlobalChangeSnippet()

            /**
             * Storage was reverted to state before the function call with index [calleeTxId].
             */
            @KSerializable
            @GenerateRemapper
            data class StorageRevert(@GeneratedBy(Allocator.Id.CALL_ID) val calleeTxId: Int) :
                RemapperEntity<StorageRevert>, StorageGlobalChangeSnippet()

            /**
             * Storage of contract [addr] was havoc'd.
             */
            @KSerializable
            data class StorageHavocContract(val addr: BigInteger) : StorageGlobalChangeSnippet()

            /**
             * Storage of contract [addr] was reset.
             */
            @KSerializable
            data class StorageResetContract(val addr: BigInteger) : StorageGlobalChangeSnippet()
        }

        /**
         * Snippets for annotating loops in EVM code.
         */
        @KSerializable
        sealed class LoopSnippet : EVMSnippetCmd() {

            /**
             * A start of a loop.
             * [loopSource] is the loop's source code.
             */
            @GenerateRemapper
            @KSerializable
            data class StartLoopSnippet(
                @GeneratedBy(Allocator.Id.LOOP) val loopIndex: Int,
                val loopSource: String
            ) : RemapperEntity<StartLoopSnippet>, LoopSnippet()

            /**
             * An end of a loop, which is expected to close a [StartLoopSnippet].
             */
            @GenerateRemapper
            @KSerializable
            data class EndLoopSnippet(@GeneratedBy(Allocator.Id.LOOP) val loopId: Int) : RemapperEntity<EndLoopSnippet>, LoopSnippet()

            /**
             * A start of an iteration in a loop.
             */
            @GenerateRemapper
            @KSerializable
            data class StartIter(val iteration: Int, @GeneratedBy(Allocator.Id.LOOP) val loopId: Int) : RemapperEntity<StartIter>, LoopSnippet()

            /**
             * An end of an iteration in a loop, which is expected to close a [StartIter].
             */
            @GenerateRemapper
            @KSerializable
            data class EndIter(val iteration: Int, @GeneratedBy(Allocator.Id.LOOP) val loopId: Int) : RemapperEntity<EndIter>, LoopSnippet()

            /**
             * Optimistic loop assertion.
             */
            @KSerializable
            data class AssertUnwindCond(override val assertCond: TACSymbol, val msg: String) : LoopSnippet(),
                TransformableSymEntity<AssertUnwindCond>, AssertSnippet<AssertUnwindCond> {
                override fun transformSymbols(f: (TACSymbol) -> TACSymbol): AssertUnwindCond = copy(
                    assertCond = f(assertCond)
                )

                override val displayIfPassed: Boolean
                    get() = true
            }

            /**
             * Copy-Loop, generated by the Solidity compiler.
             */
            @KSerializable
            object CopyLoop : LoopSnippet() {
                fun readResolve(): Any = CopyLoop
                override fun hashCode() = hashObject(this)
            }
        }

        /**
         * Snippets for annotating if/else branches (may sometimes detect loop branches too, but we try to avoid it)
         */
        @KSerializable
        sealed class BranchSnippet : EVMSnippetCmd() {
            /**
             * A start of a branch just before the branch point.
             * [branchSource] is the branch's source code.
             */
            @GenerateRemapper
            @KSerializable
            data class StartBranchSnippet(
                @GeneratedBy(Allocator.Id.BRANCH) val branchIndex: Int,
                val branchSource: SourceSegment
            ) : RemapperEntity<StartBranchSnippet>, BranchSnippet()

            /**
             * An end of a branch (potentially both if and else cases), which is expected to close a [StartBranchSnippet].
             */
            @GenerateRemapper
            @KSerializable
            data class EndBranchSnippet(@GeneratedBy(Allocator.Id.BRANCH) val branchIndex: Int) : RemapperEntity<EndBranchSnippet>, BranchSnippet()
        }

        /**
         * snippets for source finders
         * See https://www.notion.so/certora/Logging-Solidity-in-calltrace-135fe5c14fd380eb8453cfd3c8629449?pvs=4 for more details.
         */
        @KSerializable
        sealed class SourceFinderSnippet : EVMSnippetCmd(), TransformableSymEntityWithRlxSupport<SourceFinderSnippet>  {
            @KSerializable
            data class LocalAssignmentSnippet(val lhs: String, val finderType: Int, val range: Range.Range?, val value: TACSymbol) : SourceFinderSnippet() {
                override fun transformSymbols(f: (TACSymbol) -> TACSymbol): LocalAssignmentSnippet = LocalAssignmentSnippet(
                    lhs=lhs,
                    finderType=finderType,
                    range=range,
                    value = f(value)
                )
            }
        }

        @KSerializable
        sealed class HaltSnippet : EVMSnippetCmd() {
            abstract val range: Range.Range?

            @KSerializable
            data class Return(override val range: Range.Range?) : HaltSnippet()

            @KSerializable
            data class Revert(override val range: Range.Range?) : HaltSnippet()
        }

        /**
         * Snippets for when we want to display storage accesses even when storage analysis failed. [sym] is the
         * location (number, or internally an skey) in the wordmap that the access is located at. A [range] can be given
         * if available (used in the jump-to-source feature).
         */
        @KSerializable
        sealed class RawStorageAccess :
            EVMSnippetCmd(), EVMStorageAccess, TransformableSymEntityWithRlxSupport<RawStorageAccess> {

            abstract val isLoad: Boolean // if false, it's a store

            override fun kind(): EVMStorageAccess.AccessKind =
                ite(isLoad, EVMStorageAccess.AccessKind.LOAD, EVMStorageAccess.AccessKind.STORE)

            @KSerializable
            data class WithPath(
                override val isLoad: Boolean,
                val path: StorageAnalysisResult.NonIndexedPath,
                override val contractInstance: BigInteger,
                override val value: TACSymbol?,
                override val storageType: EVMTypeDescriptor.EVMValueType?,
                override val range: Range.Range?
            ) : RawStorageAccess() {
                override fun transformSymbols(f: (TACSymbol) -> TACSymbol): RawStorageAccess =
                    copy(value = value?.let { f(it) })
            }

            @KSerializable
            data class WithLocSym(
                override val isLoad: Boolean,
                val loc: TACSymbol,
                override val contractInstance: BigInteger,
                override val value: TACSymbol?,
                override val storageType: EVMTypeDescriptor.EVMValueType?,
                override val range: Range.Range?
            ) : RawStorageAccess() {
                override fun transformSymbols(f: (TACSymbol) -> TACSymbol): RawStorageAccess =
                    copy(loc = f(loc), value = value?.let { f(it) })
            }
        }
    }

    /**
     * Snippets which carry debug information from the CVL.
     */
    @KSerializable
    sealed class CVLSnippetCmd : SnippetCmd() {
        @KSerializable
        @GenerateRemapper
        data class EVMFunctionInvCVLValueTypeArg(
            val calldataOffset: BigInteger,
            val calldataSym: TACSymbol.Var,
            @GeneratedBy(Allocator.Id.CALL_ID)
            val callIndex: Int
        ) : CVLSnippetCmd(), TransformableVarEntityWithSupport<EVMFunctionInvCVLValueTypeArg>,
            RemapperEntity<EVMFunctionInvCVLValueTypeArg> {
            override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var) = copy(calldataSym = f(calldataSym))

            override val support: Set<TACSymbol.Var> = setOf(calldataSym)
        }

        /**
         * the id associated with the start and end of a CVL event. see for example [TACMeta.CVL_LABEL_END]
         */
        interface EventID {
            /** XXX: we should get rid of the nullability here. see the comment in the constructor of [CallInstance.LabelInstance] */
            val id: Int?
        }

        protected fun allFields(exp: TACExpr, symProcessor: (TACSymbol.Var) -> Set<TACSymbol.Var>): Set<TACSymbol.Var> {
            val tag = exp.tagAssumeChecked
            val syms = when  {
                tag is Tag.UserDefined.Struct -> tag.fields.flatMapToSet { (name, type) ->
                    allFields(TACExpr.StructAccess(exp, name, type), symProcessor)
                }
                exp is TACExpr.StructAccess -> setOf(exp.toSymbol() as TACSymbol.Var)
                else -> {
                    throw CertoraInternalException(CertoraInternalErrorType.TAC_TRANSFORMER_EXCEPTION,
                        "Expected exp to be a struct access but got $exp")
                }
            }
            return syms.flatMapToSet { symProcessor(it) }
        }

        /**
         * We add snippets for arguments and return values of CVL functions and rules in an early stage,
         * before compound types assignments are split to separate symbols.
         * So we put snippet Any ([CVLArg.AnyArg] or [CVLRet.AnyRet]) and change them in a later phase
         * to a more accurate snippet.
         * Array is for arrays and calldataarg.
         * Struct is for structs and env.
         * BlockchainState is for storage.
         *
         * All snippets contain the callIndex, which is the id of the CVL function or rule this snippet is related.
         * Argument snippets contain the index of the argument in the arguments list, it's name and type.
         * Return snippets also contain the index and the return type.
         */

        sealed interface CVLArgRetAny {
            val sym: TACSymbol.Var
            val type: CVLType.PureCVLType
            fun toPrimitive(): SnippetCmd
            fun toStruct(symProcessor: (TACSymbol.Var) -> Set<TACSymbol.Var>): SnippetCmd
            fun toArray(dataSyms: Set<TACSymbol.Var>, len: TACSymbol.Var): SnippetCmd
            fun toBlockchainState(symbols: Set<TACSymbol.Var>): SnippetCmd
        }

        @KSerializable
        sealed class CVLArg: CVLSnippetCmd() {
            abstract val callIndex: Int
            abstract val index: Int
            abstract val param: CVLParam
            abstract val support: Set<TACSymbol.Var>

            @KSerializable
            @GenerateRemapper
            data class AnyArg(
                @GeneratedBy(Allocator.Id.CALL_ID) override val callIndex: Int,
                override val index: Int,
                override val sym: TACSymbol.Var,
                override val param: CVLParam
            ) : CVLArg(), CVLArgRetAny, TransformableVarEntityWithSupport<AnyArg>, RemapperEntity<AnyArg> {
                override val type
                    get() = param.type

                override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var) = copy(sym = f(sym))

                override val support: Set<TACSymbol.Var> = setOf(sym)

                override fun toPrimitive() = PrimitiveArg(callIndex, index, sym, param)

                override fun toStruct(symProcessor: (TACSymbol.Var) -> Set<TACSymbol.Var>) = StructArg(callIndex, index, allFields(sym.asSym(), symProcessor), param)

                override fun toArray(dataSyms: Set<TACSymbol.Var>, len: TACSymbol.Var) = ArrayArg(callIndex, index, dataSyms, len, param)

                override fun toBlockchainState(symbols: Set<TACSymbol.Var>) = BlockchainStateArg(callIndex, index, symbols, param)
            }

            @KSerializable
            @GenerateRemapper
            data class PrimitiveArg(
                @GeneratedBy(Allocator.Id.CALL_ID) override val callIndex: Int,
                override val index: Int,
                val sym: TACSymbol.Var,
                override val param: CVLParam
            ) : CVLArg(), TransformableVarEntityWithSupport<PrimitiveArg>, RemapperEntity<PrimitiveArg> {
                override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var) = copy(sym = f(sym))

                override val support: Set<TACSymbol.Var> = setOf(sym)
            }

            @KSerializable
            @GenerateRemapper
            data class StructArg(
                @GeneratedBy(Allocator.Id.CALL_ID) override val callIndex: Int,
                override val index: Int,
                val symbols: Set<TACSymbol.Var>,
                override val param: CVLParam
            ) : CVLArg(), TransformableVarEntityWithSupport<StructArg>, RemapperEntity<StructArg> {
                override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var) = copy(symbols = symbols.mapToSet(f))

                override val support: Set<TACSymbol.Var>
                    get() = symbols
            }

            @KSerializable
            @GenerateRemapper
            data class ArrayArg(
                @GeneratedBy(Allocator.Id.CALL_ID) override val callIndex: Int,
                override val index: Int,
                val data: Set<TACSymbol.Var>,
                val len: TACSymbol.Var,
                override val param: CVLParam
            ) : CVLArg(), TransformableVarEntityWithSupport<ArrayArg>, RemapperEntity<ArrayArg> {
                override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var) = copy(data = data.mapToSet(f), len = f(len))

                override val support: Set<TACSymbol.Var> = data + len
            }

            @KSerializable
            @GenerateRemapper
            data class BlockchainStateArg(
                @GeneratedBy(Allocator.Id.CALL_ID) override val callIndex: Int,
                override val index: Int,
                val symbols: Set<TACSymbol.Var>,
                override val param: CVLParam
            ) : CVLArg(), TransformableVarEntityWithSupport<BlockchainStateArg>, RemapperEntity<BlockchainStateArg> {
                override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var) = copy(symbols = symbols.mapToSet(f))

                override val support: Set<TACSymbol.Var>
                    get() = symbols
            }
        }

        @KSerializable
        sealed class CVLRet: CVLSnippetCmd() {
            abstract val callIndex: Int
            abstract val index: Int
            abstract val type: CVLType.PureCVLType
            abstract val label: CVLReportLabel.Return

            @KSerializable
            @GenerateRemapper
            data class AnyRet(
                @GeneratedBy(Allocator.Id.CALL_ID) override val callIndex: Int,
                override val index: Int,
                override val sym: TACSymbol.Var,
                override val type: CVLType.PureCVLType,
                override val label: CVLReportLabel.Return,
            ) : CVLRet(), CVLArgRetAny, TransformableVarEntityWithSupport<AnyRet>, RemapperEntity<AnyRet> {
                override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var) = copy(sym = f(sym))

                override val support: Set<TACSymbol.Var> = setOf(sym)

                override fun toPrimitive() = PrimitiveRet(callIndex, index, sym, type, label)

                override fun toStruct(symProcessor: (TACSymbol.Var) -> Set<TACSymbol.Var>): SnippetCmd = StructRet(callIndex, index, allFields(sym.asSym(), symProcessor), type, label)

                override fun toArray(dataSyms: Set<TACSymbol.Var>, len: TACSymbol.Var): SnippetCmd = ArrayRet(callIndex, index, dataSyms, len, type, label)

                override fun toBlockchainState(symbols: Set<TACSymbol.Var>) = BlockchainStateRet(callIndex, index, symbols, type, label)
            }

            @KSerializable
            @GenerateRemapper
            data class PrimitiveRet(
                @GeneratedBy(Allocator.Id.CALL_ID) override val callIndex: Int,
                override val index: Int,
                val sym: TACSymbol.Var,
                override val type: CVLType.PureCVLType,
                override val label: CVLReportLabel.Return,
            ) : CVLRet(), TransformableVarEntityWithSupport<PrimitiveRet>, RemapperEntity<PrimitiveRet> {
                override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var) = copy(sym = f(sym))

                override val support: Set<TACSymbol.Var> = setOf(sym)
            }

            @KSerializable
            @GenerateRemapper
            data class StructRet(
                @GeneratedBy(Allocator.Id.CALL_ID) override val callIndex: Int,
                override val index: Int,
                val symbols: Set<TACSymbol.Var>,
                override val type: CVLType.PureCVLType,
                override val label: CVLReportLabel.Return,
            ) : CVLRet(), TransformableVarEntityWithSupport<StructRet>, RemapperEntity<StructRet> {
                override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var) = copy(symbols = symbols.mapToSet(f))

                override val support: Set<TACSymbol.Var>
                    get() = symbols
            }

            @KSerializable
            @GenerateRemapper
            data class ArrayRet(
                @GeneratedBy(Allocator.Id.CALL_ID) override val callIndex: Int,
                override val index: Int,
                val data: Set<TACSymbol.Var>,
                val len: TACSymbol.Var,
                override val type: CVLType.PureCVLType,
                override val label: CVLReportLabel.Return,
            ) : CVLRet(), TransformableVarEntityWithSupport<ArrayRet>, RemapperEntity<ArrayRet> {
                override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var) = copy(data = data.mapToSet(f), len = f(len))

                override val support: Set<TACSymbol.Var> = data + len
            }

            @KSerializable
            @GenerateRemapper
            data class BlockchainStateRet(
                @GeneratedBy(Allocator.Id.CALL_ID) override val callIndex: Int,
                override val index: Int,
                val symbols: Set<TACSymbol.Var>,
                override val type: CVLType.PureCVLType,
                override val label: CVLReportLabel.Return,
            ) : CVLRet(), TransformableVarEntityWithSupport<BlockchainStateRet>, RemapperEntity<BlockchainStateRet> {
                override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var) = copy(symbols = symbols.mapToSet(f))

                override val support: Set<TACSymbol.Var>
                    get() = symbols
            }
        }

        @KSerializable
        @GenerateRemapper
        data class CVLFunctionStart(
            @GeneratedBy(Allocator.Id.CALL_ID) val callIndex: CallId,
            val name: String,
            val range: Range,
            val isNoRevert: Boolean
        ): CVLSnippetCmd(), RemapperEntity<CVLFunctionStart>

        @KSerializable
        @GenerateRemapper
        data class CVLFunctionEnd(@GeneratedBy(Allocator.Id.CALL_ID) val callIndex: CallId, val name: String): CVLSnippetCmd(), RemapperEntity<CVLFunctionEnd>

        /**
         * Snippet for internal assert command we add to the TAC program for a given
         * division operation, to verify that the dominator is not equal to zero.
         * [assertCond] expected to be exactly the same as the condition of the enforced [TACCmd.Simple.AssertCmd] command
         * this [SnippetCmd] refers to, as a sanity check to make sure this [SnippetCmd] indeed tracks
         * the correct assert command following transformations on the program.
         * [assertCmdLabel] is the string representation of the [CVLExp] we compare to zero.
         * [range] is the range of divisor in the cmd.
         */
        @KSerializable
        data class DivZero(
            override val cvlExpOutSym: TACSymbol.Var,
            override val assertCond: TACSymbol,
            val range: Range,
            val assertCmdLabel: String
        ) : CVLSnippetCmd(), TransformableSymAndVarEntityWithSupport<DivZero>, AssertSnippet<DivZero>, WithParseTree {

            override val strictSupport: Set<TACSymbol.Var> = setOf(cvlExpOutSym)

            override fun transformVariables(f: (TACSymbol.Var) -> TACSymbol.Var): DivZero = copy(
                cvlExpOutSym = f(cvlExpOutSym)
            )

            override fun transformSymbols(f: (TACSymbol) -> TACSymbol): DivZero = copy(
                assertCond = f(assertCond)
            )

            override val displayIfPassed: Boolean
                get() = true
        }

        /**
         * A cast check derived for an assert_type(exp) CVL command, to check
         * whether an expression is in the bounds and is convertible to the desired type.
         * [cvlExpOutSym] is used to build the parse-tree for the CVL expression itself been cast.
         *
         * @param range can be used to identify the CVL location of the cast that this assertion was generated for;
         *    this is useful for identifying an assertion, e.g., to tell apart several assertions in a given block
         */
        @KSerializable
        data class AssertCast(
            override val cvlExpOutSym: TACSymbol.Var,
            override val assertCond: TACSymbol,
            val range: Range,
        ) : CVLSnippetCmd(), TransformableSymAndVarEntityWithSupport<AssertCast>, AssertSnippet<AssertCast>, WithParseTree {

            override val strictSupport: Set<TACSymbol.Var> = setOf(cvlExpOutSym)

            override fun transformVariables(f: (TACSymbol.Var) -> TACSymbol.Var): AssertCast =
                copy(cvlExpOutSym = f(cvlExpOutSym))

            override fun transformSymbols(f: (TACSymbol) -> TACSymbol): AssertCast = copy(assertCond = f(assertCond))

            override val displayIfPassed: Boolean
                get() = true
        }

        /**
         * marks the beginning of [CVLCmd.Composite.If] commands.
         * it is terminated by a [TACMeta.CVL_LABEL_END] annotation
         * containing an id equal to [id].
         * [condVar] is the symbol the predicate was compiled into.
         * [range] is the range of the entire `if` command.
         */
        @KSerializable
        data class IfStart(
            val condVar: TACSymbol.Var,
            val cond: CVLExp,
            @GeneratedBy(Allocator.Id.CVL_EVENT, source = true) override val id: Int,
            val range: Range,
        ) : CVLSnippetCmd(), TransformableVarEntityWithSupport<IfStart>, EventID, UniqueIdEntity<IfStart> {
            override val support get() = setOf(condVar)
            override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var) = copy(condVar = f(condVar))
            override fun mapId(f: (Any, Int, () -> Int) -> Int): IfStart {
                return this.copy(
                    id = remapEventId(this.id, f),
                )
            }
        }

        /**
         * marks the beginning of a `then`/`else` branch (see [kind]),
         * belonging to the `if` command with id [ifStartId].
         *
         * it should be terminated by a [TACMeta.CVL_LABEL_END] annotation
         * containing an id equal to [id].
         */
        @KSerializable
        data class BranchStart(
            val kind: Kind,
            @GeneratedBy(Allocator.Id.CVL_EVENT, source = true) override val id: Int,
            @GeneratedBy(Allocator.Id.CVL_EVENT, source = false) val ifStartId: Int,
            val range: Range,
        ) : CVLSnippetCmd(), EventID, UniqueIdEntity<BranchStart> {

            enum class Kind { THEN, ELSE }

            override fun hashCode() = hash { it + kind + id + ifStartId + range }

            override fun equals(other: Any?): Boolean {
                return when {
                    this === other -> true

                    other !is BranchStart -> false

                    else -> this.kind == other.kind
                        && this.id == other.id
                        && this.ifStartId == other.ifStartId
                        && this.range == other.range
                }
            }

            override fun mapId(f: (Any, Int, () -> Int) -> Int): BranchStart {
                return this.copy(
                    id = remapEventId(this.id, f),
                    ifStartId = remapEventId(this.ifStartId, f),
                )
            }
        }

        sealed interface GhostAccess {
            val indices: List<TACSymbol.Var?>
            val accessed: TACSymbol.Var
            val name: String
            val sort: GhostSort
            val persistent: Boolean
            val range: Range
        }

        /**
         * Generated when a ghost named [name] has been read. Used in [CallTrace].
         * [readValue] is the value which was read, when the ghost was called with [indices]
         * (unless this is a ghost variable, in which cases [indices] would be empty).
         * [readExpr] is a formatted String of the read expression, for UI purposes.
         */
        @KSerializable
        data class GhostRead(
            val readValue: TACSymbol.Var,
            override val indices: List<TACSymbol.Var>,
            override val name: String,
            override val sort: GhostSort,
            override val persistent: Boolean,
            override val range: Range,
            val readExpr: String,
        ) : CVLSnippetCmd(), TransformableVarEntityWithSupport<GhostRead>, GhostAccess {
            override val accessed: TACSymbol.Var get() = readValue

            companion object {
                operator fun invoke(
                    returnValueSym: TACSymbol.Var,
                    returnValueExp: CVLExp,
                    indices: List<TACSymbol.Var> = emptyList(),
                    sort: GhostSort,
                    persistent: Boolean,
                ): GhostRead {
                    val name = when (returnValueExp) {
                        is CVLExp.VariableExp -> returnValueExp.id
                        is CVLExp.ApplyExp.Ghost -> returnValueExp.id
                        is CVLExp.ArrayDerefExp -> returnValueExp.baseArray.toString()
                        else -> throw IllegalArgumentException("Ghost has unexpected type: ${returnValueExp::class}")
                    }

                    val readExpr = CVLReportLabel.Exp(returnValueExp).toString()

                    return GhostRead(returnValueSym, indices, name, sort, persistent, returnValueExp.getRangeOrEmpty(), readExpr)
                }
            }

            override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var) =
                copy(readValue = f(readValue), indices = indices.map(f))

            override val support: Set<TACSymbol.Var> get() = setOf(readValue).plus(indices)
        }

        /**
         * Generated when a ghost named [name] has been assigned to. Used in [CallTrace].
         * [lhs] is a ghost, and is the target of the assignment. if this is a ghost map,
         * [indices] are the indices for the store operation (otherwise, this would be empty).
         * [assignmentExpr] is a formatted String of the assignment expression, for UI purposes.
         */
        @KSerializable
        data class GhostAssignment(
            val lhs: TACSymbol.Var,
            override val indices: List<TACSymbol.Var>,
            override val name: String,
            override val sort: GhostSort,
            override val persistent: Boolean,
            override val range: Range,
            val assignmentExpr: String,
        ): CVLSnippetCmd(), TransformableVarEntityWithSupport<GhostAssignment>, GhostAccess {
            override val accessed: TACSymbol.Var get() = lhs

            companion object {
                operator fun invoke(
                    lhs: TACSymbol.Var,
                    rhsExp: CVLExp,
                    indices: List<Pair<TACSymbol.Var, CVLExp>> = emptyList(),
                    sort: GhostSort,
                    persistent: Boolean,
                    multiAssignIndex: Int? = null,
                ): GhostAssignment {
                    val (storeIndicesSym, storeIndicesExp) = indices.unzip()

                    val name = lhs.meta[CVL_DISPLAY_NAME]
                        ?: throw IllegalStateException("Ghost name not found in ghost var meta")

                    val lhsSubscripts = storeIndicesExp.joinToString(separator = "") { exp ->
                        CVLReportLabel.Exp(exp).toString().withBrackets()
                    }
                    val rhsAsString = CVLReportLabel.Exp(rhsExp).toString()
                    val rhsSubscript = multiAssignIndex?.withBrackets().orEmpty()

                    val assignmentExpr = "$name$lhsSubscripts = $rhsAsString$rhsSubscript"

                    return GhostAssignment(lhs, storeIndicesSym, name, sort, persistent, rhsExp.getRangeOrEmpty(), assignmentExpr)
                }

                private fun Any.withBrackets() = "[$this]"
            }

            override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var) =
                copy(lhs = f(lhs), indices = indices.map(f))

            override val support: Set<TACSymbol.Var> get() = setOf(lhs).plus(indices)
        }

        /**
         * a ghost function/variable was havoced.
         * both [name] and [sort] are needed to disambiguate here:
         * shadowing rules prohibit two ghosts with the same name and [GhostSort] in the same scope,
         * but we do allow things like `ghost foo() returns int` and `ghost bool foo` to coexist
         */
        @KSerializable
        data class GhostHavocSnippet(val name: String, val sort: GhostSort) : CVLSnippetCmd()


        /** All Ghosts were havoced. */
        @KSerializable
        object HavocAllGhostsSnippet : CVLSnippetCmd() {
            fun readResolve(): Any = HavocAllGhostsSnippet
            override fun hashCode() = hashObject(this)
        }

        @KSerializable
        data class SumGhostRead(
            val lhs: TACSymbol.Var,
            val baseGhostName: String,
            override val indices: List<TACSymbol.Var?>,
            override val persistent: Boolean
        ) : CVLSnippetCmd(), TransformableVarEntityWithSupport<SumGhostRead>, GhostAccess {
            override val accessed: TACSymbol.Var get() = lhs

            override val name: String get() ="Sum of $baseGhostName"

            // We actually care about the sort of the summed ghost (since that's who we'll be printing to the calltrace)
            // and that is always a mapping
            override val sort: GhostSort = GhostSort.Mapping

            override val range: Range
                get() = Range.Empty()

            override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var): SumGhostRead =
                copy(lhs = f(lhs), indices = indices.map { it?.let(f) })

            override val support: Set<TACSymbol.Var>
                get() = setOf(lhs) + indices.filterNotNull()
        }

        @KSerializable
        data class SumGhostUpdate(
            val lhs: TACSymbol.Var,
            val baseGhostName: String,
            override val indices: List<TACSymbol.Var?>,
            override val persistent: Boolean
        ) : CVLSnippetCmd(), TransformableVarEntityWithSupport<SumGhostUpdate>, GhostAccess {
            override val accessed: TACSymbol.Var get() = lhs

            override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var): SumGhostUpdate =
                copy(lhs = f(lhs), indices = indices.map { it?.let(f) })

            override val support: Set<TACSymbol.Var>
                get() = setOf(lhs) + indices.filterNotNull()

            override val name: String get() = "Sum of $baseGhostName"

            // We actually care about the sort of the summed ghost (since that's what we'll be printing to the calltrace)
            // and that is always a mapping
            override val sort: GhostSort = GhostSort.Mapping

            override val range: Range
                get() = Range.Empty()
        }

        /**
         * Any snippet used for showing counter-examples for storage comparisons. There are usually
         * multiple such annotations for a single comparison, one for each component of storage being compared.
         * [resultSymbol] is the boolean valued variable indicating if a particular component of storage compared
         * equal or not.
         *
         * [p1] and [p2] are a map from the cvl representation of the CVL name of the storage variable being compared
         * to the variables holding the value at that storage for the purposes of display in the counter examples.
         * The interpretation of "the value at that storage"
         * will differ depending on whether the storage component being compared is scalarized or a map.
         * In the former case, then the variables will just be the scalarized storage location. If, instead, the
         * storage component is a map, then the variables will be generated variables that hold the witness
         * to inequality (if any), see [StorageWitnessComparison] for details.
         *
         * [basis] provides some information about what is being compared within `p1` and `p2`, balances (for [spec.cvlast.StorageBasis.Balances]),
         * the state of some contract [spec.cvlast.StorageBasis.ContractClass] (but not more information about what state within a contract),
         * or a specific ghost function/map [spec.cvlast.StorageBasis.Ghost].
         *
         * A common feature of all of these snippets is that they should be ignored if [resultSymbol] is not false
         * in a counter example model. If it is true, then the fields have the same interpretations, but they don't hold
         * interesting or useful information.
         */
        sealed interface StorageComparison {
            val resultSymbol: TACSymbol.Var
            val p1: Pair<String, TACSymbol.Var>
            val p2: Pair<String, TACSymbol.Var>
            val basis: StorageBasis
        }

        /**
         * A refinement of [StorageComparison] which is comparing values of VM type, and includes an optional
         * [typeValue] field which indicates the [VMTypeDescriptor] for values being compared in storage.
         */
        sealed interface VMStorageComparison : StorageComparison {
            val typeValue: VMTypeDescriptor?
        }

        /**
         * A witness comparison generated for skolemized storage equality comparisons.
         * See the documentation on [vc.data.compilation.storage.SkolemizationGenerator] for an explanation
         * for what skolemized equality looks like and why it works.
         * Then [witnessSymbol] holds (one of) the keys at which the component storage maps differ.
         * The variables in [p1] and [p2] hold the value of the compared maps at that witness. In other words, we must
         * have that
         * `!model(resultSymbol) => model(p1.second) != model(p2.second)`
         * where `model()` retrieves the value of the variable in the current counter example model.
         * Further, we are guaranteed that `p1.second = m1[witnessSymbol]` and `p2.second = m2[witnessSymbol]`.
         *
         * Note that because for the purposes of counter example display, once we have the differing values (stored
         * in the model under the variables in `p1` and `p2`) then we don't need the base maps: we keep one around
         * (in [baseMap]) for the purposes of extracting scalarization and bitwidth information in the
         * [instrumentation.transformers.StorageDisplayPathGenerator].
         *
         * The [basis] field provides information about what is being compared. [spec.cvlast.StorageBasis.VMStorageBasis]
         * must be either a [spec.cvlast.StorageBasis.ContractClass] or [spec.cvlast.StorageBasis.Balances].
         * In general, knowing exactly which component of a contract's storage is being compared requires knowing
         * the exact value of the witness skey itself (see [spec.cvlast.StorageBasis] for a
         * discussion of this point), so that information is not recorded here.
         */
        @KSerializable
        data class StorageWitnessComparison(
            override val resultSymbol: TACSymbol.Var,
            val witnessSymbol: TACSymbol.Var,
            val baseMap: TACSymbol.Var,
            override val typeValue: VMTypeDescriptor?,
            override val p1: Pair<String, TACSymbol.Var>,
            override val p2: Pair<String, TACSymbol.Var>,
            override val basis: StorageBasis.VMStorageBasis
        ) : CVLSnippetCmd(), TransformableVarEntityWithSupport<StorageWitnessComparison>, VMStorageComparison {
            override val support: Set<TACSymbol.Var>
                get() = setOf(resultSymbol, witnessSymbol, baseMap, p1.second, p2.second)

            override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var): StorageWitnessComparison {
                return StorageWitnessComparison(
                    resultSymbol = f(resultSymbol),
                    witnessSymbol = f(witnessSymbol),
                    baseMap = f(baseMap),
                    typeValue = typeValue,
                    p1 = p1.first to f(p1.second),
                    p2 = p2.first to f(p2.second),
                    basis = basis
                )
            }
        }

        /**
         * A comparison of scalarized contract storage. Unlike the witness version, the scalarized storage variables
         * are compared directly, thus `p1.second` and `p2.second` are just the scalarized storage variables.
         * Like the witness case, we are guarnateed that
         * `!model(resultSymbol) => model(p1.second) != model(p2.second)`
         *
         * The interpretation of [basis] is the same as in [StorageWitnessComparison]
         */
        @KSerializable
        data class ScalarStorageComparison(
            override val resultSymbol: TACSymbol.Var,
            override val typeValue: VMTypeDescriptor?,
            override val p1: Pair<String, TACSymbol.Var>,
            override val p2: Pair<String, TACSymbol.Var>,
            override val basis: StorageBasis
        ) : CVLSnippetCmd(), TransformableVarEntityWithSupport<ScalarStorageComparison>, VMStorageComparison {
            override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var): ScalarStorageComparison {
                return ScalarStorageComparison(
                    resultSymbol = f(resultSymbol),
                    typeValue = typeValue,
                    basis = basis,
                    p1 = p1.mapSecond(f),
                    p2 = p2.mapSecond(f)
                )
            }

            override val support: Set<TACSymbol.Var>
                get() = setOf(
                    resultSymbol, p1.second, p2.second
                )
        }

        /**
         * A generalized version of [StorageWitnessComparison] for capturing a witness of ghost map/function
         * inequality. Like the storage case, [p1].second and [p2].second are variables
         * which hold different values in th model if [resultSymbol] itself holds `false` in the model.
         * Similarly, [p1].second and [p2].second are both defined to be selected out of the two versions of the ghost function/map
         * at some witness parameters. Unlike the storage case, there may be multiple parameters of arbitrary types
         * (compared to [StorageWitnessComparison.witnessSymbol], of which there is just one and is known to be an skey).
         *
         * The [parameters] field holds a list of variables which are the parameters/keys at which the ghost function/map
         * differs at the different storages.
         */
        @KSerializable
        data class GhostWitnessComparison(
            override val resultSymbol: TACSymbol.Var,
            override val p1: Pair<String, TACSymbol.Var>,
            override val p2: Pair<String, TACSymbol.Var>,
            override val basis: StorageBasis.Ghost,
            val parameters: List<TACSymbol.Var>,
        ) : CVLSnippetCmd(), StorageComparison, TransformableVarEntityWithSupport<GhostWitnessComparison> {
            init {
                check(parameters.size == basis.params.size) {
                    "Mismatched tag sizes $parameters vs ${basis.params}"
                }
            }

            override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var): GhostWitnessComparison {
                return GhostWitnessComparison(
                    resultSymbol = f(resultSymbol),
                    p1 = p1.mapSecond(f),
                    p2 = p2.mapSecond(f),
                    basis = basis,
                    parameters = parameters.map(f)
                )
            }

            val typedParameters : List<Pair<TACSymbol.Var, CVLType.PureCVLType>> get() = parameters.zip(basis.params)

            override val support: Set<TACSymbol.Var> = parameters.mapToSet {
                it
            } + p1.second + p2.second + resultSymbol
        }

        /**
         * The scalar version of ghost comparisons, and the analogue of [ScalarStorageComparison].
         */
        @KSerializable
        data class ScalarGhostComparison(
            override val resultSymbol: TACSymbol.Var,
            override val p1: Pair<String, TACSymbol.Var>,
            override val p2: Pair<String, TACSymbol.Var>,
            override val basis: StorageBasis.Ghost,
            val sort: GhostSort
        ) : CVLSnippetCmd(), StorageComparison, TransformableVarEntityWithSupport<ScalarGhostComparison> {
            override val support: Set<TACSymbol.Var>
                get() = setOf(resultSymbol, p1.second, p2.second)

            override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var): ScalarGhostComparison {
                return ScalarGhostComparison(
                    resultSymbol = f(resultSymbol),
                    basis = basis,
                    p1 = p1.mapSecond(f),
                    p2 = p2.mapSecond(f),
                    sort = sort
                )
            }
        }

        @KSerializable
        data class ViewReentrancyAssert(
            override val assertCond: TACSymbol,
            val functions: List<String>?,
            val range: Range.Range?,
        ): CVLSnippetCmd(), TransformableSymEntity<ViewReentrancyAssert>, AssertSnippet<ViewReentrancyAssert> {
            override fun transformSymbols(f: (TACSymbol) -> TACSymbol): ViewReentrancyAssert = copy(
                assertCond = f(assertCond)
            )

            val msg: String
                get() = "Possible view reentrancy weakness" + functions?.joinToString(separator = ", ", prefix = " ").orEmpty()

            override val displayIfPassed: Boolean
                get() = false
        }

        /** Display storage [sym] in the CallTrace. */
        @KSerializable
        data class StorageDisplay(val sym: TACSymbol.Var) : CVLSnippetCmd(), TransformableVarEntity<StorageDisplay> {
            override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var) =
                StorageDisplay(sym = f(sym))
        }

        /**
         * Describes a hook that was matched and inlined. Used to mark where hooks have been inlined so the calltrace can
         * show the user where hooks have been instrumented.
         *
         * XXX: it would be better to split this into storage and non-storage hooks, but it's a hassle.
         */
        @KSerializable
        data class InlinedHook(
            val cvlPattern: CVLHookPattern,
            val substitutions: Map<VMParam.Named, HookValue>,
            val displayPath: DisplayPath?, // only (sometimes?) available for storage hooks
        ) : CVLSnippetCmd(), TransformableSymEntityWithRlxSupport<InlinedHook> {
            override val support: Set<TACSymbol.Var>
                get() = substitutions.values.flatMapToSet(HookValue::support) + displayPath?.support.orEmpty()

            override fun transformSymbols(f: (TACSymbol) -> TACSymbol): InlinedHook {
                val substitutions = this
                    .substitutions
                    .mapValues { (_, hookValue) -> hookValue.transformSymbols(f) }
                val displayPath = this.displayPath?.transformSymbols(f)

                return this.copy(substitutions = substitutions, displayPath = displayPath)
            }

            /** for storage hooks: the value of this storage location, prior to the hook invocation */
            fun previousStorageHookValue(): HookValue? = when (cvlPattern) {
                is CVLHookPattern.StoragePattern.Load -> this.substitutions[this.cvlPattern.value]
                is CVLHookPattern.StoragePattern.Store -> this.substitutions[this.cvlPattern.previousValue]
                else -> null
            }
        }
    }

    /**
     * Snippets which carry debug information from Solana.
     */
    @KSerializable
    sealed class SolanaSnippetCmd : SnippetCmd() {

        @KSerializable
        data class CexPrintValues(val displayMessage: String, val symbols: List<TACSymbol.Var>): SolanaSnippetCmd() , TransformableVarEntityWithSupport<CexPrintValues> {
            override val support: Set<TACSymbol.Var> get() = symbols.toSet()
            override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var) =
                CexPrintValues(displayMessage = displayMessage, symbols = symbols.map{f(it)})
        }

        /**
         * [low] is the lower 64-bits, [high] is the higher 64-bits.
         * Those two are separate because a 128 bits number is represented as a pair of two 64-bits registers.
         */
        @KSerializable
        data class CexPrint128BitsValue(val displayMessage: String, val low: TACSymbol.Var, val high: TACSymbol.Var, val signed: Boolean) : SolanaSnippetCmd(), TransformableVarEntityWithSupport<CexPrint128BitsValue> {
            override val support: Set<TACSymbol.Var> get() = setOf(low, high)
            override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var) =
                CexPrint128BitsValue(displayMessage = displayMessage, low = f(low), high = f(high), signed = signed)
        }

        @KSerializable
        data class CexPrintU64AsFixed(val displayMessage: String, val unscaledVal: TACSymbol.Var, val scale: TACSymbol.Var): SolanaSnippetCmd(), TransformableVarEntityWithSupport<CexPrintU64AsFixed> {
            override val support: Set<TACSymbol.Var> get() = setOf(unscaledVal, scale)
            override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var) =
                CexPrintU64AsFixed(displayMessage = displayMessage, unscaledVal = f(unscaledVal), scale = f(scale))
        }

        @KSerializable
        data class CexPrintLocation(val filepath: String, val lineNumber: UInt): SolanaSnippetCmd()

        @KSerializable
        data class CexAttachLocation(val filepath: String, val lineNumber: UInt): SolanaSnippetCmd()

        @KSerializable
        data class CexPrintTag(val displayMessage: String): SolanaSnippetCmd()

        @KSerializable
        data class ExternalCall(val displayMessage: String, val symbols: List<TACSymbol.Var>): SolanaSnippetCmd() , TransformableVarEntityWithSupport<ExternalCall> {
            override val support: Set<TACSymbol.Var> get() = symbols.toSet()
            override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var) =
                ExternalCall(displayMessage = displayMessage, symbols = symbols.map{f(it)})
        }

        @KSerializable
        /**
         * [symbol] is the boolean operand of the assertion.
         * By asking the SMT model we can tell whether this assertion was proven or not.
         **/
        data class Assert(val displayMessage: String,  val symbol: TACSymbol.Var, val fromSatisfy: Boolean = false): SolanaSnippetCmd() , TransformableVarEntityWithSupport<Assert> {
            override val support: Set<TACSymbol.Var> get() = setOf(symbol)
            override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var) =
                Assert(displayMessage = displayMessage, symbol = f(symbol), fromSatisfy = fromSatisfy)
        }
    }
}


/** Returns the start annotation and end annotations related to an assert if they are there */
fun assertAnnotations(code: CoreTACProgram, ptr: CmdPointer):
    Pair<LTACCmdView<TACCmd.Simple.AnnotationCmd>, LTACCmdView<TACCmd.Simple.AnnotationCmd>>? {
    if (ptr.pos == 0) {
        return null
    }
    val g = code.analysisCache.graph
    val assertCmd = g.toCommand(ptr) as TACCmd.Simple.AssertCmd
    val o = assertCmd.o
    val start = g.blockCmdsBackwardFrom(ptr - 1)
        .takeWhile { (_, cmd) -> cmd !is TACCmd.Simple.AssertCmd }
        .firstNotNullOfOrNull { lcmd -> // finds the first assert snippet
            lcmd.maybeNarrow<TACCmd.Simple.AnnotationCmd>()?.takeIf {
                it.cmd.annot.v is AssertSnippet<*>
            }
        }
        ?.takeIf { //and it must match
            (it.cmd.annot.v as AssertSnippet<*>).assertCond == o
        }
        ?: return null

    val end = g.blockCmdsForwardFrom(ptr + 1)
        .takeWhile { (_, cmd) ->
            // if there are any asserts or asserts snippets in between then the annotation we found before
            // then it is not related to this assert, i.e., this assert is does not have snippets related to it.
            cmd !is TACCmd.Simple.AssertCmd &&
                !(cmd is TACCmd.Simple.AnnotationCmd && cmd.annot.v is AssertSnippet<*>)
        }
        .firstNotNullOfOrNull { lcmd ->
            lcmd.maybeNarrow<TACCmd.Simple.AnnotationCmd>()?.takeIf {
                it.cmd.annot.k == SCOPE_SNIPPET_END
            }
        }
        ?: error("No closing snippet for $start found")

    return start to end
}


/** Returns a new assert annotation command with the [newCond]. Returns null if nothing changed. */
fun TACCmd.Simple.AnnotationCmd.mapCond(newCond: TACSymbol): TACCmd.Simple.AnnotationCmd? {
    val (k, snippet) = annot
    require(snippet is AssertSnippet<*>)
    if (snippet.assertCond == newCond) {
        return null
    }

    // I'm sure there is a better way to do this, but I couldn't figure it out...
    val newAnnot = when (snippet) {
        is SnippetCmd.CVLSnippetCmd.AssertCast ->
            TACCmd.Simple.AnnotationCmd.Annotation(
                k.uncheckedAs(), snippet.transformSymbols { newCond }
            )

        is SnippetCmd.CVLSnippetCmd.DivZero ->
            TACCmd.Simple.AnnotationCmd.Annotation(
                k.uncheckedAs(), snippet.transformSymbols { newCond }
            )

        is SnippetCmd.EVMSnippetCmd.LoopSnippet.AssertUnwindCond ->
            TACCmd.Simple.AnnotationCmd.Annotation(
                k.uncheckedAs(), snippet.transformSymbols { newCond }
            )

        is SnippetCmd.CVLSnippetCmd.ViewReentrancyAssert ->
            TACCmd.Simple.AnnotationCmd.Annotation(
                k.uncheckedAs(), snippet.transformSymbols { newCond }
            )
    }

    return copy(annot = newAnnot)
}

private fun remapEventId(
    currentId: Int,
    mapper: (allocatorId: Allocator.Id, currentId: Int, generateNew: () -> Int) -> Int
): Int {
   val allocatorId = Allocator.Id.CVL_EVENT
   return mapper(allocatorId, currentId) { Allocator.getFreshId(allocatorId) }
}
