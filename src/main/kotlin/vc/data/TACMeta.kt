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

package vc.data

import allocator.Allocator
import allocator.GeneratedBy
import analysis.icfg.AxiomInliner
import analysis.icfg.ExtCallSummarization
import analysis.icfg.Inliner
import analysis.opt.DiamondSimplifier.registerDestructivelyMergeableAnnot
import analysis.split.StorageTypeBounder
import analysis.storage.DisplayPaths
import analysis.storage.StorageAnalysisResult
import com.certora.collect.*
import instrumentation.StoragePathAnnotation
import instrumentation.transformers.FoundryCheatcodes
import report.calltrace.CVLReportLabel
import spec.CVLDefinitionSite
import spec.CVLExpToTACExprMeta
import spec.cvlast.*
import spec.cvlast.typedescriptors.EVMTypeDescriptor
import spec.cvlast.typedescriptors.VMTypeDescriptor
import tac.MetaKey
import tac.NBId
import utils.*
import vc.data.TACCmd.Simple.AssigningCmd
import java.math.BigInteger

// For Boolean keys the value shouldn't matter. (Unit is not serializable.) Only need to check for the key's existence
object TACMeta {
    val MCOPY_BUFFER = MetaKey.Nothing("evm.copy.buffer")

    /**
     * Attached to load commands to indicate that these loads have been added to effect storage comparisons, and should
     * not be hooked on.
     */
    val CVL_STORAGE_COMPARISON = MetaKey.Nothing("cvl.storage.comparison")

    /**
     * When attached to a variable, indicates that it is a variable used for read tracking. When attached
     * to a (wordload) command, indicates that the load is a synthetic read for the purposes of
     * dead storage tracking.
     */
    val STORAGE_READ_TRACKER = MetaKey.Nothing("tac.storage.storage_read_tracker")

    /**
     * The human readable representation of the storage location being accessed by a read/write of storage.
     * When reading/writing a scalarized location, attached to the scalarized storage variables; when
     * reading/writing "dynamic", unscalarized location, attached to the location variable.
     */
    val DISPLAY_PATHS: MetaKey<DisplayPaths> = MetaKey("tac.storage.pretty.paths", erased = true)

    /** commands with this meta will have the command passed to [StoragePathAnnotation.StoragePathPrinter] for
     * printing access paths during code map construction
     */
    val STORAGE_PRINTER: MetaKey<StoragePathAnnotation.StoragePathPrinter> = MetaKey("tac.storage.printer")
    val NON_ALIASED_LENGTH_READ = MetaKey.Nothing("tac.pta.non.aliased.length.read")
    val INLINE_ALLOC = MetaKey.Nothing("tac.alloc.byte")
    val RETURN_LINKING: MetaKey<ExtCallSummarization.ReturnLinkingInfo> =
        MetaKey("tac.return.linking.info")
    val RETURN_WRITE: MetaKey<ExtCallSummarization.CallSummaryReturnWriteInfo> =
        MetaKey("tac.return.write")
    @GeneratedBy(Allocator.Id.RETURN_SUMMARIES, source = true)
    val RETURN_SITE: MetaKey<Int> = MetaKey("tac.call.return.id")
    val RETURN_READ =
        MetaKey<ExtCallSummarization.CallSummaryReturnReadInfo>(
            "tac.call.summary.return"
        )
    val IS_RETURNDATA = MetaKey.Nothing("tac.is.returndata")

    @GeneratedBy(Allocator.Id.ABI)
    val LOG_ENCODING_ID : MetaKey<Int> = MetaKey("tac.log.encoding-id")

    val BIT_WIDTH = MetaKey<Int>("tac.storage.bit-width")

    // for variables. indicates this is the EVM heap called "memory"
    val EVM_MEMORY = MetaKey.Nothing("tac.is.memory")

    // indicates a piece of scalararized EVM_MEMORY
    val EVM_MEMORY_SCALARIZED = MetaKey.Nothing("tac.is.memory.scalarized")

    val EVM_IMMUTABLE_ARRAY = MetaKey<ImmutableBound>("tac.immutable.array")

    // For symbols: Indicates the original address symbol for a bytemap representing extcodedata
    val EXTCODE_DATA_KEY = MetaKey<TACSymbol>("tac.extcodedata.key")

    val ENV_BIT_WIDTH = MetaKey<Int>("tac.env.known-bit-width")

    // For LoopSummarization
    val MEM_INCARN = MetaKey<Int>("loop.summarization.mem.incarn")

    /**
     * Marker for an mstore command which updates the length of an array for which we've generated
     * a runtime proof obligation.
     */
    val VALIDATED_LENGTH_UPDATE = MetaKey.Nothing("pta.length.write.validated")


    /**
     * instanceId of a contract that a storage variable represents. If the storage variable is map,
     * attached to the base map variable of WordLoad/Stores, for scalarized storage locations, attached to the scalarized symbol
     */
    val STORAGE_KEY =
        MetaKey<BigInteger>("tac.storage")
    val TRANSIENT_STORAGE_KEY =
        MetaKey<BigInteger>("tac.transient_storage")
    val CODEDATA_KEY =
        MetaKey<BigInteger>("tac.codedata")

    /**
     * Indicates
     */
    val CODESIZE_KEY = MetaKey<BigInteger>("tac.codesize")

    val NO_CODE_REWRITE = MetaKey.Nothing("tac.creation.no-rewrite-codedata")

    /**
     * Indicates that the variable this is attached to represent the constructor code data
     */
    val CONSTRUCTOR_CODE_KEY =
        MetaKey.Nothing("tac.constructor.codedata")

    // holds the size of bytecode in number of bytes
    val CODESIZE_VALUE =
        MetaKey<Int>("tac.codesize")
    /**
     * The [VMTypeDescriptor] of a storage location, currently known to be [EVMValueType]. Found within storage read/write commands.
     * If the load/store is a WordLoad/Store, this is attached to the base map variable (e.g. [AssigningCmd.WordLoad.base])
     * otherwise it is attached to the scalarized location.
     */
    val STORAGE_TYPE =
        MetaKey<EVMTypeDescriptor.EVMValueType>("tac.slot.type")

    /**
     * The sort (split? unpacked? etc.) of a storage location. Attached to the storage base map for unscalarized locations,
     * attached to the scalarized variable otherwise.
     */
    val SCALARIZATION_SORT =
        MetaKey<ScalarizationSort>("tac.scalarization.sort")

    /**
     * Represents the [analysis.storage.StorageAnalysisResult.NonIndexedPath] associated with this base map or scalar.
     * NB: only present if storage splitting (but not necessarily unpacking) succeeded.
     */
    val STABLE_STORAGE_PATH = MetaKey<StorageAnalysisResult.NonIndexedPath>("tac.storage.non-indexed-path")

    /**
     * Indicates the family of non-indexed access paths represented by a storage VARIABLE. Will persist post unpacking/splitting.
     * (Compare to [STORAGE_PATHS] which is attached to commands, and may not persist post unpacking/splitting).
     */
    val STABLE_STORAGE_FAMILY = MetaKey<StorageAnalysisResult.StoragePaths>("tac.storage.non-indexed-path.family")
    val ORIGINAL_STORAGE_KEY = MetaKey<BigInteger>("tac.original.storage")
    val ORIGINAL_TRANSIENT_STORAGE_KEY = MetaKey<BigInteger>("tac.original.transient.storage")
    val IS_CALLDATA = MetaKey.Nothing("tac.is.calldata")
    val CALLDATA_OFFSET = MetaKey<BigInteger>("tac.calldata.offset") // the offset for unpacked calldata variable
    val IS_EXTCODESIZE = MetaKey.Nothing("tac.is.extcodesize")
    val WORDMAP_KEY = MetaKey<BigInteger>("tac.wordmap-key")
    val RESERVED_MEMORY_SLOT = MetaKey<BigInteger>("tac.is.reserved.memory.slot.var")

    /**
     * Marker annotation used to delimit code that corresponds to the decompilation of a single call* command.
     */
    val CALL_GROUP_START = MetaKey.Nothing("tac.decompiler.call-start").registerDestructivelyMergeableAnnot()

    /**
     * Marker annotation used to delimit code that corresponds to the decompilation of a single call* command.
     * These should be "well-matched" with [CALL_GROUP_START].
     */
    val CALL_GROUP_END = MetaKey.Nothing("tac.decompiler.call-end").registerDestructivelyMergeableAnnot()

    // attached to long copy commands that have been shown to copy an exact constant (from codedata)
    val CONSTANT_SCALARIZATION = MetaKey<BigInteger>("long-copy.constant.scalarization")

    /**
     * Indicates the (low-level) access path of a storage/read or write.
     * For reads/writes to scalarized locations, this information does not appear.
     * For non-scalarized reads/writes, this is attached to the location variable of the WordLoad/Store.
     */
    val ACCESS_PATHS =
        MetaKey<StorageAnalysisResult.AccessPaths>(
            "tac.storage.access-paths"
        )

    /**
     * Indicates the non-indexed access path of a storage/read or write. Attached to the COMMAND
     * that reads or writes storage (may not persist post unpacking/splitting)
     */
    val STORAGE_PATHS = MetaKey<StorageAnalysisResult.StoragePaths>("tac.storage.node")

    // For symbols: Explicitly avoid adding callindex to these variables
    val NO_CALLINDEX = MetaKey.Nothing("tac.no.callindex")

    // marks bool variables that originally had a different type, and were replaced with bools by an optimization
    // currently only `BoolRewriter` uses it.
    val REPLACED_WITH_BOOL = MetaKey.Nothing("tac.was.replaced.with.bool")

    // Marker that a command is explicitly manipulating another contract's storage due to
    // contract creation
    val DYNAMIC_STORAGE_MANAGEMENT = MetaKey.Nothing("tac.dynamic-scene.storage-reset")

    val REVERT_MANAGEMENT = MetaKey.Nothing("tac.is.revert.management")
    val LAST_STORAGE_UPDATE = MetaKey.Nothing("tac.is.last.storage.update")
    val RESET_STORAGE = MetaKey.Nothing("tac.is.reset.storage")
    // ghost save and restore based on checkpoint
    val GHOST_SAVE = MetaKey<VariableCheckpoint>("tac.ghost.save")
    val GHOST_RESTORE = MetaKey<VariableCheckpoint>("tac.ghost.restore")
    val GHOST_HAVOC = MetaKey.Nothing("tac.ghost.havoc")

    // Holds a symbol that can be returned instead of the memory buffer (already properly assigned)
    // For return commands, this means it can be replaced with ReturnSym commands.
    val SYM_RETURN = MetaKey<TACSymbol>("tac.sym.return")

    // Identifies the returned address from a call to the Solidity Create() function
    val IS_CREATED_ADDRESS = MetaKey.Nothing("tac.is.created.address")

    val CREATE2_COMPUTATION = MetaKey.Nothing("tac.is-create2.computation")

    // for commands. is it accessing (read/write) a storage variable?
    val IS_STORAGE_ACCESS = MetaKey.Nothing("tac.storage.access")
    val IS_TRANSIENT_STORAGE_ACCESS = MetaKey.Nothing("tac.transient.storage.access")

    // For commands - indicates command was generated by transferBalance
    val TRANSFERS_BALANCE = MetaKey.Nothing("tac.transfers.balance")

    // Summaries
    val REVERT_SAVE = MetaKey<Inliner.SaveValuesSummary>("tac.revert.save")
    val REVERT_RESTORE = MetaKey<Inliner.RestoreValueSummary>("tac.revert.restore")

    val CVL_STATE_RESIDUAL = MetaKey<CVLToSimpleCompiler.StorageMovement>("cvl.state.residual", erased = true)

    /**
     * Marks a variable as being a global variable used for internal summaries, and thus should not be given a
     * call index
     */
    val SUMMARY_GLOBAL = MetaKey.Nothing("tac.summary.global-value")

    val REVERT_PATH = MetaKey.Nothing("tac.revert.path")
    val RETURN_PATH = MetaKey.Nothing("tac.return.path")

    // CVL related

    // For symbols: mark CVL variables
    val CVL_VAR = MetaKey<Boolean>("cvl")
    // For symbols: maintains the CVL types of the CVL variables
    val CVL_TYPE = MetaKey<CVLType.PureCVLType>("cvl.type", restore = true)
    // For symbols: if the symbol denotes a member in a CVL struct (e.g., env), maintains the "struct path" to this member
    val CVL_STRUCT_PATH = MetaKey<CVLStructPathNode>("cvl.struct.path", restore = true)
    // For symbols: mark local variables to be presented under "locals"
    val CVL_DEF_SITE = MetaKey<CVLDefinitionSite>("cvl.def.site")
    // For cmds: location of the corresponding CVL cmd
    val CVL_RANGE = MetaKey<Range>("cvl.range", restore = true)
    // for symbols: mark variables that are the predicate of an `if` command
    val CVL_IF_PREDICATE = MetaKey.Nothing("cvl.if.predicate")
    // For cmds: unique identifier (index) for an assert TACCmd
    @GeneratedBy(Allocator.Id.ASSERTS, source = true)
    val ASSERT_ID : MetaKey<Int> = MetaKey<Int>("tac.assert.id")
    // For cmds: an indicator on asserts that says that if multiassert check is enabled, they are nop'd instead of assumed
    // when other similar asserts are checked
    val ISOLATED_ASSERT = MetaKey.Nothing("is.isolated.assert")
    // For cmds: an indicator that this assert or assume are a result of a
    // satisfy cmd, and thus should be ignored when working on asserts or other
    // satisfy cmds. The integer represents a unique identifier for each satisfy.
    @GeneratedBy(Allocator.Id.SATISFIES, source = true)
    val SATISFY_ID : MetaKey<Int> = MetaKey<Int>("satisfy.id")
    // For ghosts: mark ghosts
    val CVL_GHOST = MetaKey.Nothing("cvl.ghost")

    val GHOST_SUM_ASSIGNMENT = MetaKey.Nothing("ghost.sum.assignment")

    /** Marks symbols that contain the length of some dynamic CVL array (bytes, string, ..), the meta value contains the
     * display of that CVL array. */
    val CVL_LENGTH_OF = MetaKey<String>("cvl.lengthof")
    /** Marks symbols that contain the contents of some dynamic CVL array (bytes, string, ..), the meta value contains
     * the display of that CVL array. */
    val CVL_DATA_OF = MetaKey<String>("cvl.dataof")
    // If we have a CVL_VAR and CVL_DISPLAY_NAME exists, then we should assume that the same variable
    // with the display name value is assigned the same value
    val CVL_DISPLAY_NAME = MetaKey<String>("cvl.display", restore = true)
    // For symbols: marks out-symbols of (compiled) CVL expressions
    val CVL_EXP = MetaKey<CVLExpToTACExprMeta>("cvl.exp", restore = true)
    // For Vars: mark as containing contract's symbolic address and include the instance ID
    val CONTRACT_ADDR_KEY =
            MetaKey<BigInteger>("tac.contract.sym.addr", erased = true)
    // For Vars: mark the original contract name
    val CONTRACT_ADDR_KEY_NAME =
            MetaKey<String>("tac.contract.sym.addr.name", erased = true)
    // Marks assert commands that come directly from a CVL `assert` command written in the spec file
    // Note that the presence of a range meta entry is no longer (but was previously) an indicator for a user
    // defined assert.
    val CVL_USER_DEFINED_ASSERT = MetaKey.Nothing("cvl.user.defined.assert")
    // Similarly, mark assume commands that come from a `require` in the spec file, and if they contained a user defined reason
    val CVL_USER_DEFINED_ASSUME = MetaKey<Boolean>("cvl.user.defined.assume")

    // Metas for the autogenerated array hashing bound asserts/assumes and the lengths themselves
    val HASHING_BOUND_ASSERT = MetaKey<TACSymbol>("hashing.bound.assert")
    val HASHING_BOUND_ASSUME= MetaKey<TACSymbol>("hashing.bound.assume")
    val HASHING_BOUND_LENGTH = MetaKey.Nothing("tac.assert.hashingbound.length")

    val DECOMPOSED_USER_HASH = MetaKey.Nothing("tac.user.hash.decomposition")

    /** Marks "havocme" mappings, as introduced in [TACSimpleSimple]. */
    val TACSIMPLESIMPLE_HAVOCME = MetaKey.Nothing("tacsimplesimple.havocme")

    /**
     * For indicating commands that originated with [CVLCmd.Simple.Label]
     */
    val CVL_LABEL_START = MetaKey<CVLReportLabel>("cvl.label.start", restore = true).registerDestructivelyMergeableAnnot()
    @GeneratedBy(Allocator.Id.CVL_EVENT, source = true)
    val CVL_LABEL_START_ID: MetaKey<Int> = MetaKey<Int>("cvl.label.start.id")
    @GeneratedBy(Allocator.Id.CVL_EVENT, source = false)
    val CVL_LABEL_END: MetaKey<Int> = MetaKey<Int>("cvl.label.end").registerDestructivelyMergeableAnnot()
    @KSerializable
    @Treapable
    data class RequireInvariant(val id: Int, val name: String): AmbiSerializable
    val CVL_ASSUME_INVARIANT_TARGET = MetaKey.Nothing("cvl.assume.invariant.target")
    val CVL_ASSUME_INVARIANT_CMD_START = MetaKey<RequireInvariant>("cvl.assume.invariant.start", erased = true)
    val CVL_ASSUME_INVARIANT_CMD_END = MetaKey<RequireInvariant>("cvl.assume.invariant.end",  erased = true)

    /**
     * VC generation related.
     * Marks (Leino encoding) ReachVar variables (symbols) referenced in TAC commands.
     * The meta map entry stores the block that the marked ReachVar corresponds to.
     */
    val LEINO_REACH_VAR = MetaKey<NBId>("ReachVar")

    /**
     * VC generation related.
     * Marks (Leino encoding) "OK" variables (symbols) referenced in TAC commands.
     * The meta map entry stores the block that the marked OK var corresponds to.
     */
    val LEINO_OK_VAR = MetaKey<NBId>("OkVar")

    @GeneratedBy(Allocator.Id.CONTRACT_CREATION, source = true)
    val CONTRACT_CREATION : MetaKey<Int> = MetaKey<Int>("evm.inst.creation")

    /**
     * Snippet commands' annotations.
     */
    val SNIPPET = MetaKey<SnippetCmd>("snippet.cmd", restore = true).registerDestructivelyMergeableAnnot()

    /**
     * End annotation for [ScopeSnippet].
     * This scopes the range of TAC commands that should be iterated over to enforce
     * the snippet's property.
     */
    val SCOPE_SNIPPET_END = MetaKey.Nothing("snippet.cmd.scope.end").registerDestructivelyMergeableAnnot()

    /**
     * This meta can be added to a [TACCmd] when we want the callTrace to ignore it.
     */
    val IGNORE_IN_CALLTRACE = MetaKey.Nothing("ignore.in.calltrace")

    /**
     * Annotations for the loops entering/exit labels
     */
    @GeneratedBy(Allocator.Id.LOOP)
    val START_LOOP : MetaKey<Int> = MetaKey<Int>("start_loop.cmd").registerDestructivelyMergeableAnnot()

    val END_LOOP = MetaKey.Nothing("end_loop.cmd").registerDestructivelyMergeableAnnot()

    /**
     * Used to tag assume/assert false sink commands that are inserted during unrolling
     */
    @GeneratedBy(Allocator.Id.LOOP)
    val SYNTHETIC_LOOP_END : MetaKey<Int> = MetaKey<Int>("loop.synthetic.end")

    /** Attached to store commands that went through [StorageTypeBounder]'s optimizations successfully */
    val SIGN_EXTENDED_STORE = MetaKey.Nothing("tac.sign.extended.store")

    val DIRECT_STORAGE_ACCESS = MetaKey.Nothing("tac.direct.storage.access")

    val AXIOM_INLINED = MetaKey<AxiomInliner.PlacementInfo>("tac.axiom.inline")

    val DIRECT_STORAGE_ACCESS_TYPE = MetaKey<CVLType.PureCVLType>("tac.direct.storage.access.type", restore = true)

    /** Foundry cheatcode meta */
    val FOUNDRY_CHEATCODE = MetaKey<FoundryCheatcodes>("foundry.cheatcode")
    val FOUNDRY_PROTECTED = MetaKey.Nothing("foundry.protected")

    /** Soroban Env */
    val SOROBAN_ENV = MetaKey.Nothing("soroban.env")
}
