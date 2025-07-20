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

package spec

import analysis.CommandWithRequiredDecls
import analysis.merge
import analysis.storage.DisplayPath
import analysis.storage.StorageAnalysis.Base
import analysis.storage.StorageAnalysis.AnalysisPath
import analysis.storage.StorageAnalysis.Offset
import analysis.storage.StorageAnalysisResult
import config.Config
import datastructures.stdcollections.*
import evm.EVM_WORD_SIZE
import evm.MASK_SIZE
import evm.alignToEVMWord
import scene.IScene
import spec.converters.EVMMappingKeyOutput
import spec.converters.EVMMoveSemantics
import spec.converters.EVMStorageMapping
import spec.cvlast.*
import spec.cvlast.typedescriptors.*
import tac.CallId
import tac.TACStorageType
import tac.Tag
import utils.*
import utils.CollectingResult.Companion.reduceErrors
import vc.data.*
import vc.data.ParametricInstantiation.Companion.toSimple
import vc.data.SnippetCmd.EVMSnippetCmd.StorageSnippet
import vc.data.TACMeta.ACCESS_PATHS
import vc.data.TACMeta.DIRECT_STORAGE_ACCESS
import vc.data.TACMeta.DIRECT_STORAGE_ACCESS_TYPE
import vc.data.TACMeta.SCALARIZATION_SORT
import vc.data.tacexprutil.ExprUnfolder
import java.math.BigInteger


private fun AnalysisPath.extendOffset(v: BigInteger): AnalysisPath =
    when (this) {
        is AnalysisPath.Root ->
            // We implicitly treat each field of a toplevel struct as if it were simply its own root
            AnalysisPath.Root(
                slot = this.slot + v.div(EVM_WORD_SIZE),
                base = this.base
            )

        is AnalysisPath.StructAccess ->
            AnalysisPath.StructAccess(base, Offset.Bytes(offset.bytes + v))

        else ->
            AnalysisPath.StructAccess(this, Offset.Bytes(v))
    }

private fun AnalysisPath.extendAggregate(f: (AnalysisPath) -> AnalysisPath) : AnalysisPath {
    return if(this is AnalysisPath.Root || this is AnalysisPath.StructAccess) {
        f(this)
    } else {
        f(AnalysisPath.StructAccess (this, BigInteger.ZERO))
    }
}

/**
 * Accumulator object that (unsurprisingly) accumulates the steps of compiling a complex storage access. This accumulator
 * is extended and then passed to the "next" continuation as the recursion of [StorageAccessCompiler.compileInternal] unwinds,
 * see that method for details.
 *
 * Conceptually the accumulator represents the state of the "in-flight" access: as each portion of the access
 * path is traversed, the accumulator is updated to reflect the most recent "step" of the path. Thus, each accumulator object
 * represents the state of the access after some number of steps of the access; the instance passed to the final continuation
 * is the state after the access path is completely processed. Put another way, the accumulator represents a "pointer" into
 * storage, where each step moves the pointer farther along the access path. Thus, it makes sense to view this
 * pointer as "pointing" some conceptual value. For example, if `a` is a mapping, then while processing `contract.a[key]`
 * the accumulator produced after simply processing `contract.a` (NB, no array dereference) "points to" the mapping value,
 * despite that not being a discrete, scalar value in the EVM or CVT.
 *
 * In the following documentation for member fields, it should be understood that the state being described is immediately
 * after some most recent "step" along the access path, and that step has positioned the "pointer" represented by
 * the accumulator at some (conceptual) value.
 *
 * [path] is the [analysis.storage.StorageAnalysisResult.NonIndexedPath] of the value pointed to by the accumulator. For an intermediate
 * accumulator, this path may not necessarily correspond to a decomposed path found in a contract's state variables. However, when
 * the traversal terminates, it *is* expected that this [analysis.storage.StorageAnalysisResult.NonIndexedPath] will
 * be found on one of the contract's state variables, see [StorageAccessCompiler.toStorageVar] for more details.
 *
 * [storageType] is the TAC storage type of the value pointed to by the accumulator; this is primarily used to
 * resolve sub-word offset information and to determine slot offsets for struct fields.
 *
 * [vmType] is the [VMTypeDescriptor] version of the [storageType]; it is an invariant that these two fields must have
 * the same "shape", i.e., if [vmType] is a [VMStructDescriptor] then [storageType] will be [TACStorageType.Struct].
 *
 * [ptr] is the symbol which holds the current location of the pointer in storage. As with the [path], the value of this symbol
 * has no meaningful interpretation for an in-flight accumulator, but once all steps of the
 * access are processed, the pointer is expected be a valid index into storage which corresponds to the location of
 * the value being accessed.
 *
 * [code] holds the program fragment which effects the updates to [ptr] and holds the compilation of the map keys and
 * array indices encountered during traversal.
 *
 * [offsetInBytes] (if non-null) gives the sub-word offset of a scalar value. This will only be non-null for integral
 * types (which, by definition, only occur at the terminus of the access path).
 *
 * [displayPath] is used much later in the pipeline, for the purpose of identifying and pretty-printing this storage access.
 * Unfortunately, while at first glance this may look similar to [path], we cannot directly compute this from a [StorageAnalysisResult.NonIndexedPath].
 */
private data class Accumulator(
    val path: StorageAnalysisResult.NonIndexedPath,
    val storageType: TACStorageType,
    val vmType: VMTypeDescriptor,
    val ptr: TACSymbol,
    val code: ParametricInstantiation<CVLTACProgram>,
    val offsetInBytes: Int?,
    val displayPath: DisplayPath,
    val analysisPath: AnalysisPath
) {
    val ptrWithMeta by lazy {
        val finalPath = analysisPath.let {
            if (it !is AnalysisPath.Root) {
                it.extendOffset(BigInteger.ZERO)
            } else {
                it
            }
        }
        (ptr as TACSymbol.Var).withMeta(ACCESS_PATHS, StorageAnalysisResult.AccessPaths(setOf(finalPath)))
    }
}

/**
 * Additional metadata needed at the end of [StorageAccessCompiler.compileInternal], but that (unlike [Accumulator]) isn't mutated
 * throughout the compilation process and is instead passed along the chain of continuations unchanged.
 * While these *could* have been folded into [Accumulator], they have been split out to make it clear that they're treated differently.
 *
 * [storageVarCandidates] is eventually used to find the variable which holds the value being read - see [StorageAccessCompiler.toStorageVar].
 * [contractInstanceId] is the address of the instance of the contract being accessed, needed for snippets.
 */
private data class Invariants(
    val storageVarCandidates: Set<TACSymbol.Var>,
    val contractInstanceId: BigInteger,
)

/**
 * Utility class for compiling a storage access path, i.e., a [CVLExp], into a sequence of TAC commands that effect that
 * storage access. The [expCompiler] is used to compile array indices and map keys, the [scene] is used to resolve storage
 * variable information. The [callId] is used to tag the temporary variables produced by this utility, future versions may tag the
 * blocks with this call id. Finally the [allocation] callback is provided by this class' caller (that is, the [CVLExpressionCompiler])
 * to allocate a temporary variable/parameter with the given type (passing through the machinery for this class to do it
 * itself is *way* harder than just passing in this closure). Similarly, [cvlTypeConstraints] is used to add type-appropriate
 * constraints for the newly-produced CVL variable, but the logic of this is entirely provided by [CVLCompiler].
 */
class StorageAccessCompiler(
    private val expCompiler: CVLExpressionCompiler,
    private val scene: IScene,
    val callId: CallId,
    private val allocation: (CVLType.PureCVLType) -> Pair<CVLParam, TACSymbol.Var>,
    private val cvlTypeConstraints: (CVLType.PureCVLType, TACSymbol.Var) -> CommandWithRequiredDecls<TACCmd.Spec>,
) {
    private fun <T: TACCmd.Spec> CommandWithRequiredDecls<T>.toProg(nm: String) = this.toProg(nm, object : CVLCompiler.CallIdContext {
        override val baseCallId: CallId
            get() = callId
    })

    /**
     * If [storageVar] doesn't denote a packed value, then return the (offset, size) of the value
     * denoted by [acc]
     */
    private fun needsMasking(storageVar: TACSymbol.Var, acc: Accumulator): Pair<Int, Int>? {
        val maskStart = acc.offsetInBytes ?: 0
        val maskSize = acc.storageType.tryAs<TACStorageType.IntegralType>()?.numBytes
            ?: Config.VMConfig.registerByteWidth.toBigInteger()


        return (maskStart to maskSize.toInt()).takeIf {
            storageVar.meta.find(SCALARIZATION_SORT) !is ScalarizationSort.Packed
                && (maskStart != 0 || it.second != Config.VMConfig.registerByteWidth)
        }?.also { (start, size) ->
           check(start == 0 || size < Config.VMConfig.registerByteWidth) {
               "Trying to compile a nonsensical storage access of length ${size} at byte offset $start "
           }
        }
    }

    /**
     * Mask out the range [offsetInBytes*8..(offsetInBytes+sizeInBytes-1)*8]from [word], sign extending if necessary
     */
    private fun extractSubword(tmpSuffix: String, word: TACExpr, offsetInBytes: Int, sizeInBytes: Int, signed: Boolean):
        Pair<TACSymbol.Var, CommandWithRequiredDecls<TACCmd.Spec>> {
        val maskResult = TACKeyword.TMP(Tag.Bit256, tmpSuffix)
        // Extract the subword at offset by shifting right and
        // (1) sign extending (if signed) masking out the top bits
        // (2) masking the upper bites (if unsigned)
        val shrAmount = 8*offsetInBytes
        val shr = TACExpr.BinOp.ShiftRightLogical(word, shrAmount.asTACExpr, Tag.Bit256)
        val signedOrMasked = if (signed) {
            TACExpr.BinOp.SignExtend(
                (sizeInBytes-1).asTACExpr, shr, Tag.Bit256
            )
        } else {
            val mask = MASK_SIZE(8*sizeInBytes)
            TACExpr.BinOp.BWAnd(
                shr, mask.asTACExpr, Tag.Bit256
            )
        }
        val doMask = ExprUnfolder.unfoldTo(signedOrMasked, maskResult)

        return maskResult to doMask
    }

    /**
     * A terminating step. Produces a program which reads the current value of the access path [storageVar],
     * including any necessary conversions.
     *
     * [acc] is the [Accumulator] whose state matches [storageVar], expected to have fully resolved following
     * the computation of [compileInternal].
     *
     * [out] is a variable that holds the value being read; the value being read is expected to be convertible to the
     * pure CVL type [outType].
     */
    private fun readCurrentValue(
        acc: Accumulator,
        storageVar: TACSymbol.Var,
        out: TACSymbol.Var,
        outType: CVLType.PureCVLType,
        contractInstanceId: BigInteger,
        range: Range.Range?,
    ): ParametricInstantiation<CVLTACProgram> {
        /**
         * Temp variable to hold the raw value being read out of storage.
         */
        val rawResult = TACKeyword.TMP(Tag.Bit256, "!rawResult").toUnique("!").at(callId)

        val storageVarType = outType.takeIf {
            needsMasking(storageVar, acc) == null
        } ?: CVLType.PureCVLType.Primitive.UIntK(Config.VMConfig.registerBitwidth)

        /**
         * If this is a top-level, scalarized storage variable, then that will be reflected in its tag, in which case
         * we use an [vc.data.TACCmd.Simple.AssigningCmd.AssignExpCmd], otherwise we use [vc.data.TACCmd.Simple.AssigningCmd.WordLoad].
         */
        val rawRead : CommandWithRequiredDecls<TACCmd.Spec> = if(storageVar.tag == Tag.Bit256) {
            CommandWithRequiredDecls(listOf(
                TACCmd.Simple.AssigningCmd.AssignExpCmd(
                    lhs = rawResult,
                    rhs = storageVar.withMeta(DIRECT_STORAGE_ACCESS_TYPE, storageVarType),
                ).plusMeta(DIRECT_STORAGE_ACCESS)
            ), setOf(rawResult, storageVar))
        } else {
            check(storageVar.tag == Tag.WordMap)
            CommandWithRequiredDecls(listOf(
                TACCmd.Simple.AssigningCmd.WordLoad(
                    lhs = rawResult,
                    base = storageVar.withMeta(DIRECT_STORAGE_ACCESS_TYPE, storageVarType),
                    loc = acc.ptrWithMeta
                ).plusMeta(DIRECT_STORAGE_ACCESS)
            ), setOfNotNull(rawResult, storageVar, acc.ptr as? TACSymbol.Var))
        }

        val outIsSigned = outType is CVLType.PureCVLType.Primitive.IntK

        // Explicitly extract the sub-word data in case storage splitting was not precise enough
        // (i.e., in case [storageVar] denotes more than the packed value we're after
        val (maskedRead, maskCmds) = needsMasking(storageVar, acc)?.let { (maskStart, maskSize) ->
            extractSubword("masked", rawResult.asSym(), maskStart, maskSize, signed = outIsSigned)
        } ?: rawResult to CommandWithRequiredDecls<TACCmd.Spec>(listOf(), setOf())

        /**
         * Now actually convert the raw value (held in [maskedRead]) and place it into [out].
         *
         * NB: that this basically assumes that the conversion is only done on scalar values, which is how the converters
         * (and by extension, the typechecker) works now. In other words, attempts to convert non-scalar values should
         * fail type checking, and so this "assumption" is actually checked.
         *
         * When we (inevitably) need to support reading out structs or arrays, this will have to be made
         * more complicated A LA the ABI encoding/decoding done for external call args/returns.
         */
        val valueConversion = acc.vmType.converterTo(
            outType, FromVMContext.StateValue.getVisitor()
        ).force().convertTo(
            fromVar = maskedRead,
            toVar = out,
            cb = { it }
        ).uncheckedAs<CommandWithRequiredDecls<TACCmd.Spec>>()

        /**
         * the read must also account for the result type's type constraints.
         * note that these are constraints for a CVL representation of the type,
         * but are valid here because the type has been converted to to a CVL representation.
         */
        val constraints = cvlTypeConstraints(outType, out)

        val snippet = compileSnippet(SnippetKind.LOAD, out, acc, contractInstanceId, range)

        val readAndConvert = rawRead
            .merge(maskCmds)
            .merge(valueConversion)
            .merge(constraints)
            .merge(snippet)
            .toProg("read and convert")
            .toSimple()

        /**
         * Finally, the converter code is appended to the code in [acc]'s code field, which
         * holds the code that navigated us to the location in question.
         */
        return ParametricMethodInstantiatedCode.mergeProgs(acc.code, readAndConvert)
    }

    /**
     * A terminating step. Produces a program which havocs the value for the access path given in [storageVar].
     *
     * [acc] is the [Accumulator] whose state matches [storageVar], expected to have fully resolved following
     * the computation of [compileInternal]. the value being havoced is a expected to be convertible to the pure CVL type [valueType].
     *
     * to havoc [storageVar], we
     *   1. generate a CVL variable, havoc that variable and add CVL type constraints
     *   2. generate a VM intermediary variable and convert the CVL variable above to this VM variable
     *   3. write the value of the VM intermediary to [storageVar]
     */
    private fun havocAccessPath(
        acc: Accumulator,
        storageVar: TACSymbol.Var,
        valueType: CVLType.PureCVLType,
        contractInstanceId: BigInteger,
        range: Range.Range?,
    ): ParametricInstantiation<CVLTACProgram> {
        val havocedCVLVar = TACKeyword.TMP(valueType.toTag(), "!havoced").toUnique("!").at(callId)
        val havocOfCVLVar = CommandWithRequiredDecls(
            TACCmd.Simple.AssigningCmd.AssignHavocCmd(havocedCVLVar),
            havocedCVLVar
        )
        val cvlConstraints = cvlTypeConstraints(valueType, havocedCVLVar)

        val intermediaryVar = TACKeyword.TMP(Tag.Bit256, "!intermediary").toUnique("!").at(callId)

        /** we currently only allow havocing of value types or arrays of value types at a specific index, therefore expect a [VMValueTypeDescriptor] */
        val vmValueType = validateVMValueType(acc.vmType, valueType)

        val convertToEVM = EVMMoveSemantics.ValueConverters.convertValueTypeToEVM(
            intermediaryVar,
            vmValueType,
            havocedCVLVar,
            valueType
        )

        val writeDecls = mutableSetOf(storageVar, intermediaryVar)

        /* Now compute
             toWriteVar := ~MASK(old_value) | MASK(havoc_value)
           When we're havocing a sub-word value
         */
        val (toWriteVar, combineCmds) = needsMasking(storageVar, acc)?.let { (maskStart, maskSize) ->
            val havocMask = MASK_SIZE(8*maskSize).shiftLeft(8*maskStart)
            // oldMask = ~havocMask, but BigInteger doesn't have a convenient bitflip
            val topAmount = Config.VMConfig.registerByteWidth - (maskStart+maskSize)
            val lowerMask = MASK_SIZE(8*maskStart)
            val upperMask = MASK_SIZE(8*topAmount).shiftLeft(8*(maskStart+maskSize))
            val oldMask = upperMask.or(lowerMask)
            val (oldValueVar, oldValueCmds) = when (storageVar.tag) {
                is Tag.Bit256 -> storageVar to CommandWithRequiredDecls<TACCmd.Spec>()
                is Tag.WordMap -> {
                    val tmp = TACKeyword.TMP(Tag.Bit256, "!havocOldValue").toUnique("!").at(callId)
                    tmp to CommandWithRequiredDecls(
                        listOf(TACCmd.Simple.AssigningCmd.WordLoad(lhs = tmp, loc = acc.ptrWithMeta, base = storageVar)),
                        tmp
                    )
                }
                else -> error("got unexpected tag ${storageVar.tag} for storage variable $storageVar")
            }
            ExprUnfolder.unfoldToSingleVar("!combined", TACExpr.BinOp.BWOr(
                TACExpr.BinOp.BWAnd(havocMask.asTACExpr, intermediaryVar.asSym(), Tag.Bit256),
                TACExpr.BinOp.BWAnd(oldMask.asTACExpr, oldValueVar.asSym(), Tag.Bit256),
                Tag.Bit256
            )).let {
                val dest = it.e.s as TACSymbol.Var
                writeDecls.addAll(it.newVars)
                dest to oldValueCmds.merge(it.cmds)
            }
        } ?: intermediaryVar to CommandWithRequiredDecls<TACCmd.Spec>(listOf(), setOf())

        val writeCmd = when (storageVar.tag) {
            Tag.Bit256 -> TACCmd.Simple.AssigningCmd.AssignExpCmd(lhs = storageVar, rhs = toWriteVar)
            Tag.WordMap -> {
                if (acc.ptr is TACSymbol.Var) {
                    writeDecls.add(acc.ptr)
                }
                TACCmd.Simple.WordStore(loc = acc.ptrWithMeta, value = toWriteVar, base = storageVar)
            }
            else -> error("got unexpected tag ${storageVar.tag} for storage variable $storageVar")
        }.plusMeta(DIRECT_STORAGE_ACCESS)
        val writeHavoc = CommandWithRequiredDecls(writeCmd, writeDecls)

        /**
         * a [TACSymbol.Var] of the havoced value is required for this snippet -
         * rather than create a copy, we use the existing intermediary, since it's a snapshot of the value,
         * post-havoc, and already has all the required constraints in place.
         */
        val snippet = compileSnippet(SnippetKind.HAVOC, intermediaryVar, acc, contractInstanceId, range)

        return ParametricMethodInstantiatedCode.merge(
            listOf(
                acc.code,
                havocOfCVLVar.toProg("havoc CVL temporary").toSimple(),
                cvlConstraints.toProg("constraints for havoced temporary").toSimple(),
                convertToEVM.toProg("convert CVL temporary to EVM").toSimple(),
                combineCmds.toProg("combine havoced sub-word into old value").toSimple(),
                writeHavoc.toProg("write EVM intermediary to storage path").toSimple(),
                snippet.toProg("storage havoc snippet").toSimple(),
            ),
            "havoc storage variable"
        )
    }

    private fun validateVMValueType(vmType: VMTypeDescriptor, expectedValueType: CVLType.PureCVLType): VMValueTypeDescriptor {
        if (vmType !is VMValueTypeDescriptor) {
            compilationException { "Expected VM type of havoced storage path to be a value type, but got $vmType" }
        }

        val asPureCVL = vmType.getPureTypeToConvertTo(FromVMContext.StateValue).reduceErrors { compilationException { "A havoced storage value must be convertable to PureCVL (got $vmType)" } }.force()
        if (asPureCVL != expectedValueType) {
            compilationException { "Type mismatch between computed storage path variable ($vmType) and expected CVL type ($expectedValueType)" }
        }

        return vmType
    }

    private fun compilationException(
        message: () -> String
    ): Nothing = throw CertoraInternalException(CertoraInternalErrorType.CVL_COMPILER, message())

    fun compileRead(
        expression: CVLExp,
        out: TACSymbol.Var,
        outType: CVLType.PureCVLType
    ) : ParametricInstantiation<CVLTACProgram> {
        return compileInternal(expression) { invariants: Invariants, acc: Accumulator ->
            val storageVar = acc.toStorageVar(invariants.storageVarCandidates)
                ?: throw CertoraException(
                    CertoraErrorType.DIRECT_STORAGE_ACCESS,
                    "Could not compile access to contract storage $expression @ ${expression.getRangeOrEmpty()} due to missing context information. " +
                        "This is likely caused by failures elsewhere in the tool; please check the Global Problems view and contact Certora for help."
                )

            readCurrentValue(acc, storageVar, out, outType, invariants.contractInstanceId, expression.getRangeOrEmpty() as? Range.Range)
        }
    }

    private fun StorageAnalysisResult.NonIndexedPath.extendOffset(v: BigInteger) =
        when(this) {
            is StorageAnalysisResult.NonIndexedPath.Root ->
                // We implicitly treat each field of a toplevel struct as if it were simply its own root
                StorageAnalysisResult.NonIndexedPath.Root(
                    slot = this.slot + v,
                    base = this.base
                )

            is StorageAnalysisResult.NonIndexedPath.StructAccess ->
                // Nested structs are flattened
                this.copy(
                    offset = this.offset + v
                )

            else ->
                StorageAnalysisResult.NonIndexedPath.StructAccess(
                    offset = v,
                    base = this
                )
        }

    fun compileHavoc(
        expression: CVLExp,
        type: CVLType.PureCVLType,
    ) : ParametricInstantiation<CVLTACProgram> {
        return compileInternal(expression) { invariants: Invariants, acc: Accumulator ->
            val storageVar = acc.toStorageVar(invariants.storageVarCandidates)
                ?: throw CertoraException(
                    CertoraErrorType.DIRECT_STORAGE_ACCESS,
                    "Could not compile havoc of contract storage $expression @ ${expression.getRangeOrEmpty()} due to missing context information. " +
                        "This is likely caused by failures elsewhere in the tool; please check the Global Problems view and contact Certora for help."
                )

            havocAccessPath(acc, storageVar, type, invariants.contractInstanceId, expression.getRangeOrEmpty() as? Range.Range)
        }
    }



    /**
     * Extend a [analysis.storage.StorageAnalysisResult.NonIndexedPath] according to the same alternation used
     * for [analysis.storage.StorageAnalysis.AnalysisPath], i.e., an "aggregate" non-indexed path (static array access,
     * map access, or array access) must have as its parent a struct access or root. [f] is expected to extend
     * its [analysis.storage.StorageAnalysisResult.NonIndexedPath] argument with this aggregate constructor; this function
     * simply extends the path represented by `this` with a [analysis.storage.StorageAnalysisResult.NonIndexedPath.StructAccess]
     * constructor if need be.
     */
    private fun StorageAnalysisResult.NonIndexedPath.extendAggregate(f: (StorageAnalysisResult.NonIndexedPath) -> StorageAnalysisResult.NonIndexedPath) : StorageAnalysisResult.NonIndexedPath {
        return if(this is StorageAnalysisResult.NonIndexedPath.Root || this is StorageAnalysisResult.NonIndexedPath.StructAccess) {
            f(this)
        } else {
            f(StorageAnalysisResult.NonIndexedPath.StructAccess(
                base = this,
                offset = BigInteger.ZERO
            ))
        }
    }


    /**
     * Common code for compiling a static or dynamic array access. [acc] is the accumulator
     * for the access up to the point of the array indexing operation. [indexType] is
     * the CVL type of the index; the result of compiling the index is held in [indexVar] and the
     * code that holds that computation is in [indCompilation]. It is expected that [indexType] should
     * be convertible as a value type (via [EVMMoveSemantics.ValueConverters]) to the EVM index type, i.e. `uint256`.
     *
     * The boolean flag [shouldHashBase] indicates whether the pointer to the array held in the [Accumulator.ptr]
     * field of [acc] should be hashed before adding the element offset. For dynamic arrays, this flag is always true;
     * for static arrays, the flag is set depending on whether we predict that the splitter has done this rewrite,
     * i.e., the element sizes of the static array are potentially packed (less than half word size).
     *
     * Finally, the [elementSizeInWords] gives the size of elements in words. Note that this code assumes (and does NOT and
     * CANNOT check) that the storage splitter has spread out sub half-word size elements.
     */
    private fun compileArrayAccess(
        acc: Accumulator,
        indexType: CVLType.PureCVLType,
        indexVar: TACSymbol.Var,
        indCompilation: ParametricInstantiation<CVLTACProgram>,
        shouldHashBase: Boolean,
        elementSizeInWords: BigInteger,
        static: Boolean
    ) : Triple<TACSymbol.Var, ParametricInstantiation<CVLTACProgram>, AnalysisPath> {
        check(elementSizeInWords >= BigInteger.ONE) {
            "Broken invariant, have zero size elements?"
        }
        val evmIndexType = EVMTypeDescriptor.UIntK(256) // hard coded because this is all EVM specific anyway
        check(evmIndexType.toTag() == Tag.Bit256)
        val evmIndex = TACKeyword.TMP(Tag.Bit256, "!index").toUnique("!").at(callId)
        /*
          mathint land (CVL) -> twos complement land (EVM)

          This is a rare (and unfortunate??) example of skipping the converter infra. We could, in principle, add a new
          "to vm context" for array indices much like we did for map keys, but we would *only* ever expect it to be used
          on a uint256 anyway. So just hardcode this logic here.
         */
        val indexAsBit = EVMMoveSemantics.ValueConverters.convertValueTypeToEVM(
            dest = evmIndex,
            destType = evmIndexType,
            src = indexVar,
            srcType = indexType
        )

        /**
         * The start location in storage of the array elements. May be the pointer of [acc], or the hash of it
         * depending on the value of [shouldHashBase].
         */
        val arrayStart = TACKeyword.TMP(Tag.Bit256, "!start").toUnique("!").at(callId)

        /**
         * The "result" as in, the result of the indexing operation, i.e., this variable
         * holds the location in storage of the start of the element being accessed.
         */
        val result = TACKeyword.TMP(Tag.Bit256, "!elementStart").toUnique("!").at(callId)

        /**
         * Holds the logical index multiplied by the element size (again, in words).
         */
        val scaledIndex = TACKeyword.TMP(Tag.Bit256, "!elementOffset").toUnique("!").at(callId)
        val offsetCode = CommandWithRequiredDecls(listOf(
            /*
             Either hash or identity assignment, depending on whether the caller told us to hash.
             */
            if(shouldHashBase) {
                TACCmd.Simple.AssigningCmd.AssignSimpleSha3Cmd(
                    lhs = arrayStart,
                    length = EVM_WORD_SIZE.asTACSymbol(),
                    args = listOf(acc.ptr)
                )
            } else {
                TACCmd.Simple.AssigningCmd.AssignExpCmd(
                    lhs = arrayStart,
                    rhs = acc.ptr.asSym()
                )
            },
            TACCmd.Simple.AssigningCmd.AssignExpCmd(
                lhs = scaledIndex,
                rhs = TACExpr.Vec.Mul(
                    evmIndex.asSym(),
                    elementSizeInWords.asTACExpr
                )
            ),
            TACCmd.Simple.AssigningCmd.AssignExpCmd(
                lhs = result,
                rhs = TACExpr.Vec.Add(
                    scaledIndex.asSym(),
                    arrayStart.asSym()
                )
            )
        ), setOfNotNull(acc.ptr as? TACSymbol.Var, result, scaledIndex, arrayStart, evmIndex))

        return Triple(
            result,
            ParametricMethodInstantiatedCode.merge(
                listOf(
                    acc.code,
                    indCompilation,
                    indexAsBit.toProg("index comp").toSimple(),
                    offsetCode.toProg("offset").toSimple()
                ), "array access"
            ),
            acc.analysisPath.extendAggregate {
                if (static) {
                    AnalysisPath.StaticArrayAccess(it, indexVar)
                } else {
                    AnalysisPath.ArrayAccess(it, indexVar, arrayStart)
                }
            }
        )
    }

    /**
     * The recursive handler of direct storage access expressions. [expression] is expected to have the shape described
     * by the grammar given in the documentation for [spec.cvlast.StorageAccessMarker]. As with the implementation
     * of [spec.cvlast.abi.LayoutTraversal], during the recursion the expression is traversed "outward in", i.e., the
     * last step of the access is accessed first. However, the compilation of the access must be processed inside out, that
     * is from the inner most portion of the access path. Thus, as in [spec.cvlast.abi.LayoutTraversal],
     * we build a chain of continuations outward in, whereby the innermost step of the access triggers its continuation,
     * which itself triggers the continuation produced by the second-to-innermost step of the access, and so on.
     *
     * Each continuation along the chain updates the [Accumulator] argument according to the [CVLExp] representing the
     * step of the access; this update effects the "movement" of the pointer along the access path towards the value
     * ultimately being accessed. By then triggering the next continuation with the (updated) [Accumulator], the (current)
     * continuation passes responsibility for moving the pointer to the continuation generated by its caller, i.e.
     * one level up in the recursive call stack, i.e., the "next" step along the access path.
     *
     * With the conceptual groundwork out of the way, some implementation details. [cont] actually takes two arguments,
     * the [Accumulator] which is updated by each step, and [Invariants], which is data that would remain constant throughout
     * the compilation process and should not be modified by [cont].
     */
    private tailrec fun compileInternal(expression: CVLExp, cont: (Invariants, Accumulator) -> ParametricInstantiation<CVLTACProgram>) : ParametricInstantiation<CVLTACProgram> {
        /**
         * As mentioned above, [expression] is expected to be one of [spec.cvlast.CVLExp.FieldSelectExp], [spec.cvlast.CVLExp.ArrayDerefExp],
         * or [spec.cvlast.CVLExp.ArrayLengthExp] (this is checked by the [spec.cvlast.StorageAccessChecker]). The aggressive use
         * of `else` below is more defensive programming, and hard failing on any unexpected format is the only reasonable thing to do.
         */
        return when(expression) {
            is CVLExp.FieldSelectExp -> {
                /**
                 * Here we know that the recursion *must* terminate at a field select expression on a [spec.cvlast.CVLType.PureCVLType.Primitive.CodeContract]
                 * typed variable. Initial versions of this class actually tried to treat the [spec.cvlast.CVLExp.VariableExp] portion
                 * of this access as a separate step, but it was way more trouble than its worth. The result is we have
                 * folded the base case into a sub-case of one of the general recursive cases; not ideal, but not the worst
                 * thing to be done in this codebase.
                 */
                if (expression.isArrayLengthExp()) {
                    compileInternal(expression.structExp) { invariants, acc ->
                        if(acc.storageType !is TACStorageType.Array) {
                            compilationException {
                                "Expected base operand of length to be an array, got ${acc.storageType}"
                            }
                        }
                        if(acc.vmType !is VMDynamicArrayTypeDescriptor) {
                            compilationException {
                                "Expected match between TAC and VM typing, but got ${acc.vmType}"
                            }
                        }
                        val desc = acc.vmType.lengthType
                        cont(invariants, Accumulator(
                            vmType = desc,
                            /**
                             * Here we fake the storage type. It's fine.
                             */
                            storageType = TACStorageType.IntegralType(
                                descriptor = desc,
                                numBytes = EVM_WORD_SIZE,
                                typeLabel = "uint256",
                                upperBound = null,
                                lowerBound = null
                            ),
                            offsetInBytes = null,
                            /**
                             * The non-indexed path of the length of an array is just the path to the array, there is no
                             * separate "array length" constructor.
                             */
                            path = acc.path,
                            code = acc.code,
                            ptr = acc.ptr,

                            /** TODO CERT-4606 - currently the array itself (without a subscript) is the path for the array length. we might want to change this */
                            displayPath = acc.displayPath,
                            analysisPath = acc.analysisPath
                        ))
                    }

                } else if(expression.structExp is CVLExp.VariableExp) {
                    val ty = (expression.structExp.getCVLType() as? CVLType.PureCVLType.Primitive.CodeContract) ?: compilationException {
                        "Expected root ${expression.structExp} of storage access to be a CodeContract, got ${expression.structExp.getCVLType()}"
                    }
                    val contract = scene.getContract(ty.name)
                    val storage = contract.storage as StorageInfoWithReadTracker
                    val transientStorage = contract.transientStorage
                    val storageLayout = contract.getStorageLayout() ?: compilationException {
                        "Cannot compile storage access without storage type information"
                    }
                    /** find the [TACStorageType] for the top-level field being accessed. This null access is asserted
                     * because the type checker accepted this code, which means this lookup *should* succeed.
                     */
                    val (stateVar, base) = storageLayout.slots[expression.fieldName]?.let { it to Base.STORAGE }
                        ?: ((contract.getTransientStorageLayout() ?: compilationException {
                            "Cannot compile storage access of ${expression.fieldName} which was not found in normal storage without transient storage type information"
                        }).slots[expression.fieldName]!! to Base.TRANSIENT_STORAGE)

                    /**
                     * the invariants are created here (and only here), at the base of the recursion,
                     * then passed along the continuations unchanged.
                     * in fact, we *must* create them here: this is data that has been extracted from the contract invocation,
                     * which we only visit once.
                     */
                    val invariants = Invariants(
                        storageVarCandidates = storage.storageVariables.keys + transientStorage.stateVars(),
                        contractInstanceId = contract.instanceId,
                    )

                    /** Kick off the chain of continuations - this is our base case.*/
                    cont(invariants, Accumulator(
                        /**
                         * Path starts at the slot number given to us by the Solidity compiler
                         */
                        path = StorageAnalysisResult.NonIndexedPath.Root(stateVar.slot, base),
                        /**
                         * likewise, the pointer begins at this constant slot
                         */
                        ptr = stateVar.slot.asTACSymbol(),
                        /**
                         * The vm type and storage tyeps are both given to us by the compiler.
                         */
                        vmType = stateVar.typeDescriptor!!,
                        storageType = stateVar.storageType!!,
                        /**
                         * There is no code yet, but we need a non-empty program for the accumulator anyway
                         */
                        code = CommandWithRequiredDecls(listOf(TACCmd.Simple.NopCmd)).toProg("seed").toSimple(),
                        /**
                         * And finally, if the type of this field is primitive field, pass along the sub-word offset if any.
                         */
                        offsetInBytes = stateVar.offsetInBytes.takeIf {
                            stateVar.storageType is TACStorageType.IntegralType
                        },

                        /**
                         * note well - the root here is the top-level access and not the contract alias.
                         * this is because [DisplayPath]s currently have no concept of contract access (for historical reasons?)
                         */
                        displayPath = DisplayPath.Root(expression.fieldName),
                        analysisPath = AnalysisPath.Root(stateVar.slot, base)
                    ))
                } else {
                    /**
                     * Otherwise this is just another regular struct access along the overall path.
                     */
                    compileInternal(expression.structExp) { invariants, acc ->
                        /**
                         * Assert that the base expression for the struct field access returned, you know, a struct.
                         */
                        if(acc.storageType !is TACStorageType.Struct) {
                            compilationException {
                                "Expected a struct type as base for field lookup of ${expression.fieldName} on ${expression.structExp}, got ${acc.storageType}"
                            }
                        }
                        /**
                         * As stated in the documentation for [Accumulator], we expect the storage and vm types to be
                         * "in sync".
                         */
                        if(acc.vmType !is VMStructDescriptor) {
                            compilationException {
                                "Expected a vm struct type for field lookup of ${expression.fieldName} on ${expression.structExp}, got ${acc.vmType}"
                            }
                        }
                        /**
                         * Find the field in the tac storage type and vm type. Again, the correspondence of the two types
                         * means that if the tac storage member lookup succeeds, we expect the vm type lookup to succeed.
                         *
                         * We also expect both of these lookups to succeed (because the type checker accepted this expression)
                         * but give a good error message if our invariants are broken.
                         */
                        val tacMember = acc.storageType.members[expression.fieldName] ?: compilationException {
                            "Expected to find field ${expression.fieldName} in result of ${expression.structExp}, did not"
                        }
                        val vmMember = acc.vmType.fieldTypes[expression.fieldName] ?: compilationException {
                            "Expected to find field ${expression.fieldName} in result of ${expression.structExp}, did not"
                        }

                        /**
                         * As the tmp name suggests, this variable will hold the next pointer after shifting to the next
                         * field.
                         */
                        val tmp = TACKeyword.TMP(Tag.Bit256, "!nextOffset").toUnique("!").at(callId)
                        cont(invariants, Accumulator(
                            /**
                             * The storage and vm types are what were read above for the type of the field being read
                             */
                            storageType = tacMember.type,
                            vmType = vmMember,
                            /**
                             * the pointer to the location of this field is held in tmp, which is thus passed through
                             * to our caller's continuation in the ptr field.
                             */
                            ptr = tmp,
                            /**
                             * Extend the path to reflect the shifted offset.
                             */
                            path = acc.path.extendOffset(
                                tacMember.slot
                            ),
                            code = ParametricMethodInstantiatedCode.mergeProgs(
                                acc.code,
                                CommandWithRequiredDecls(listOf(
                                    TACCmd.Simple.AssigningCmd.AssignExpCmd(
                                        lhs = tmp,
                                        rhs = TACExpr.Vec.Add(
                                            acc.ptr.asSym(),
                                            op2 = tacMember.slot.asTACExpr()
                                        )
                                    )
                                ), tmp).toProg("struct offset").toSimple()
                            ),
                            /**
                             * If this is an integral type, pass along the sub-word offset within the slot
                             * of the field
                             */
                            offsetInBytes = tacMember.offset.takeIf {
                                tacMember.type is TACStorageType.IntegralType
                            },

                            displayPath = DisplayPath.FieldAccess(expression.fieldName, acc.displayPath),
                            analysisPath = acc.analysisPath.extendOffset(tacMember.slot * EVM_WORD_SIZE + tacMember.offset)
                        ))
                    }
                }
            }
            is CVLExp.ArrayDerefExp -> {
                compileInternal(expression.array) { invariants, acc ->
                    /**
                     * Index (or key) compilation will always happen, so lift it out here.
                     */
                    val indexType = expression.index.getOrInferPureCVLType()
                    /**
                     * Here is where we use the allocation callback; unfortunately
                     * the method within CVLExpressionCompiler that allocates a temp variable automatically
                     * for the type of the expression output is private, so we need to make the output variable/param
                     * ourselves (... by calling a callback that just uses the same mechanism within the expression compiler
                     * in the private method mentioned above. This is a Rube Goldberg device.)
                     */
                    val (indexParam, indexVar) = allocation(indexType)
                    val indCompilation = expCompiler.compileExp(
                        indexParam, expression.index
                    )
                    /**
                     * Now determine what type of access this is based on the storage type of the "array" expression (which actually
                     * could be map).
                     */
                    when(acc.storageType) {
                        is TACStorageType.Struct,
                        is TACStorageType.IntegralType -> compilationException {
                            "Expected an indexable type for base ${expression.array} with index ${expression.index}, but got ${acc.storageType}"
                        }
                        is TACStorageType.Array -> {
                            /**
                             * An array of the dynamic flavor. Figure out the size of the elements in words (again, we assume
                             * that the splitter will spread out packed words, so this will always be at least one).
                             */
                            val elementSizeInWords = acc.storageType.elementSizeInBytes.alignToEVMWord() / EVM_WORD_SIZE
                            /**
                             * Compile the array access, incorporating the index compilation and type info.
                             */
                            val (result, code, analysisPath) = compileArrayAccess(
                                acc = acc,
                                indexType = indexType,
                                indexVar = indexVar,
                                indCompilation = indCompilation,
                                shouldHashBase = true, // we always hash the base of a dynamic array
                                elementSizeInWords = elementSizeInWords,
                                static = false
                            )
                            cont(invariants, Accumulator(
                                path = acc.path.extendAggregate(StorageAnalysisResult.NonIndexedPath::ArrayAccess),
                                offsetInBytes = null, // no offset info, because there's no sub-word packing
                                ptr = result,
                                code = code,
                                storageType = acc.storageType.elementType,
                                vmType = (acc.vmType as? VMDynamicArrayTypeDescriptor)?.elementType ?: compilationException {
                                    "Mismatch between vm and tac types, expected dynamic array, got ${acc.vmType}"
                                },
                                displayPath = DisplayPath.ArrayAccess(indexVar, acc.displayPath),
                                analysisPath = analysisPath
                            ))
                        }
                        /**
                         * Reading actual elements of bytes/strings arrays does not work.
                         */
                        TACStorageType.Bytes -> compilationException {
                            "Unsupported indexing operation into a packed bytes array."
                        }
                        is TACStorageType.Mapping -> {
                            val accMapping = (acc.vmType as? VMMappingDescriptor) ?: compilationException {
                                "Disagreement between tac and vm typing of ${expression.array}, have mapping ${acc.storageType}, but vm type is ${acc.vmType}"
                            }

                            /**
                             * Here we use the converter infrastructure handle the key compilation. The details of how to
                             * set up the hash and keys are *very* dependent on the input/output types, so we use the converter
                             * infrastructure to handle this for us.
                             */
                            val mapOutput = accMapping.keyType.converterFrom(
                                indexType,
                                ToVMContext.MappingKey.getVisitor()
                            ).force().convertTo(
                                fromVar = indexVar,
                                toVar = EVMStorageMapping(
                                    callId = callId,
                                    mappingSlot = acc.ptr
                                ),
                                cb = { it }
                            ) as EVMMappingKeyOutput
                            /**
                             * The mapping key output type is like the [spec.converters.WithOutputPointer] type, in that
                             * it wraps code and some variable. Unlike in that case where the variable represented the
                             * next "free" location in a encoding operation, here the variable holds the result of the key
                             * computation operations, i.e., the keccak.
                             */
                            cont(invariants, Accumulator(
                                vmType = accMapping.valueType,
                                storageType = acc.storageType.valueType,
                                ptr = mapOutput.output,
                                offsetInBytes = null,
                                path = acc.path.extendAggregate(StorageAnalysisResult.NonIndexedPath::MapAccess),
                                code = ParametricMethodInstantiatedCode.merge(listOf(
                                    acc.code,
                                    indCompilation,
                                    mapOutput.crd.toProg("mapaccess").toSimple()
                                ), "map access"),
                                displayPath = DisplayPath.MapAccess(mapOutput.indexVar, acc.storageType.keyType, acc.displayPath),
                                analysisPath = acc.analysisPath.extendAggregate {
                                    // the Zero at the end not correct, but it's difficult to get the correct value,
                                    // and nobody seems to need it.
                                    AnalysisPath.MapAccess(it, indexVar, acc.ptr, TACSymbol.Zero)
                                }
                            ))
                        }
                        is TACStorageType.StaticArray -> {
                            /**
                             * Static version of arrays. Very similar to the dynamic case, except the decision of whether
                             * to hash or not is determined by whether we think the splitter has spread out the static array elements,
                             * i.e., if the element sizes were originally less than half-word size (see below for details).
                             *
                             *
                             * As in dynamic arrays, Solidity will pack elements of static arrays if the elements take up
                             * at most one half word.
                             * For dynamic arrays, we can unpack these elements easily, and "spread" them out throughout storage.
                             * This is always safe to do, because the "base" of the dynamic array is the result of a hash
                             * so we won't end up colliding with other storage values by using up even more slots in storage.
                             *
                             * However, static arrays are stored "in place", so if we spread out the elements for sub half-word
                             * elements then we'll end up colliding with other values in storage, e.g., if we have
                             * ```
                             * struct Foo {
                             *     uint96[3] a;
                             *     uint b;
                             * }
                             * ```
                             * then naively spreading out the elements of a will cause element 1 to end up sharing a
                             * storage slot with b.
                             *
                             * So, when Yoav's splitter spreads out the static arrays, it also applies a hash to the
                             * base of the static array to place it "somewhere else" in storage to avoid this collision. However,
                             * this hash application is *only* applied when the original element sizes are inferred to be
                             * at most half-word width in size. So, we use the same criteria here to decide hash vs not.
                             */
                            val (newPointer, code, analysisPath) = compileArrayAccess(
                                acc = acc,
                                indexVar = indexVar,
                                indexType = indexType,
                                shouldHashBase = acc.storageType.elementSizeInBytes < EVM_WORD_SIZE / BigInteger.TWO,
                                /**
                                 * As in the dynamic case, we expect and assume the splitter to always
                                 * spread out the elements, thus every element consumes at least one word.
                                 */
                                elementSizeInWords = acc.storageType.elementSizeInBytes.alignToEVMWord() / EVM_WORD_SIZE,
                                indCompilation = indCompilation,
                                static = true
                            )

                            cont(invariants, Accumulator(
                                path = acc.path.extendAggregate(StorageAnalysisResult.NonIndexedPath::StaticArrayAccess),
                                offsetInBytes = null,
                                storageType = acc.storageType.elementType,
                                ptr = newPointer,
                                vmType = (acc.vmType as? VMStaticArrayType)?.elementType ?: compilationException {
                                    "Mismatch between typing of ${expression.array}, have ${acc.storageType} but vm type is ${acc.vmType}"
                                },
                                code = code,
                                displayPath = DisplayPath.ArrayAccess(indexVar, acc.displayPath),
                                analysisPath = analysisPath
                            ))
                        }
                    }
                }
            }
            else -> compilationException {
                "Unexpected form of expression $expression for storage access"
            }
        }
    }

    private enum class SnippetKind {
        LOAD,
        HAVOC,
    }

    private fun compileSnippet(
        kind: SnippetKind,
        currentValue: TACSymbol.Var,
        acc: Accumulator,
        contractInstanceId: BigInteger,
        range: Range.Range?,
    ): CommandWithRequiredDecls<TACCmd.Simple.AnnotationCmd> {
        val storageType = acc.vmType as? EVMTypeDescriptor.EVMValueType

        val snippet = when (kind) {
            SnippetKind.LOAD -> StorageSnippet.DirectStorageLoad(currentValue, acc.displayPath, storageType, contractInstanceId, range)
            SnippetKind.HAVOC -> StorageSnippet.DirectStorageHavoc(currentValue, acc.displayPath, storageType, contractInstanceId, range)
        }

        return CommandWithRequiredDecls(snippet.toAnnotation(), currentValue)
    }

    /**
     * A finishing step expected to be called as a continuation after [compileInternal] has finished resolving.
     * The [Accumulator] here is the result of "unwinding" the intermediate steps of the access path.
     * In other words, when processing `foo.f1[x].f2.f3`, the accumulator object will be the one produced immediately after
     * processing `f3`, i.e., it will represent the state in which the actual read out of storage happens
     * (other intermediate accumulators produced are just that, intermediate, expressing the in-flight
     * computation necessary for reaching the value we ultimately are reading out).
     *
     * [storageVarCandidates] is the set of storage variables for the contract whose storage is being accessed. The storage variable
     * resolution process looks for a unique variable in this set whose associated [analysis.storage.StorageAnalysisResult.NonIndexedPath] family
     * (embedded with the [TACMeta.STABLE_STORAGE_FAMILY] meta) matches
     * that in the accumulator, *and* whose sub-word offset is consistent with the offset information present in the [Accumulator] (if any).
     *
     * If no match could be found, or if the offset is not sufficient to narrow the candidates down to a single match, returns null.
     */
    private fun Accumulator.toStorageVar(storageVarCandidates: Set<TACSymbol.Var>): TACSymbol.Var? {
        /**
         * All "value" paths must be a hash base, AKA [analysis.storage.StorageAnalysisResult.NonIndexedPath.StructAccess]
         * or [analysis.storage.StorageAnalysisResult.NonIndexedPath.Root] (these are the NIP analogues of
         * the [analysis.storage.StorageAnalysis.HashBase] constructors in the [analysis.storage.StorageAnalysis.AnalysisPath]
         * hierarchy). So add this component now if need be.
         */
        val nip = if (path !is StorageAnalysisResult.NonIndexedPath.Root) {
            path.extendOffset(BigInteger.ZERO)
        } else {
            path
        }

        /**
         * Find the storage variable(s) tagged with the NIP produced above. This process will not necessarily
         * yield a unique variable, so we must disambiguate
         */
        return storageVarCandidates.filter {
            it.meta.find(TACMeta.STABLE_STORAGE_FAMILY)?.storagePaths?.contains(nip) == true
        }.let {
            if (it.size == 1) {
                it.single()
            } else {
                /**
                 * Then we have multiple storage variables that have this NIP. This can happen when multiple
                 * scalar values are packed into a single logical word. In this case, disambiguate with the
                 * [ScalarizationSort] information attached to the storage variables, using the [Accumulator.offsetInBytes]
                 * field. If this is not sufficient to disambiguate (we don't have the sub-word offset information,
                 * or we don't have the expected scalarization sort), then this will return null
                 */
                it.singleOrNull {
                    offsetInBytes != null && (it.meta.find(TACMeta.SCALARIZATION_SORT) as? ScalarizationSort.Packed)?.start == (offsetInBytes * 8)
                }
            }
        }
    }
}
