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

package cvl

import analysis.CommandWithRequiredDecls
import analysis.ip.InternalFuncValueLocation
import analysis.pta.abi.PartitionField
import datastructures.stdcollections.*
import evm.DEFAULT_SIGHASH_SIZE
import evm.EVM_MOD_GROUP256
import evm.EVM_WORD_SIZE
import evm.EVM_WORD_SIZE_INT
import net.jqwik.api.*
import net.jqwik.api.Tuple.Tuple4
import net.jqwik.api.lifecycle.BeforeProperty
import net.jqwik.kotlin.api.*
import org.junit.jupiter.api.Assertions
import spec.CVLCompiler
import spec.converters.*
import spec.converters.repr.CVLDataInput
import spec.converters.repr.CVLDataOutput
import spec.cvlast.*
import spec.cvlast.typechecker.CVLTypeTypeChecker
import spec.cvlast.typedescriptors.*
import spec.toProg
import tac.CallId
import tac.NBId
import tac.Tag
import tac.isSubtypeOf
import utils.CollectingResult
import utils.CollectingResult.Companion.bind
import utils.Range
import utils.uncheckedAs
import vc.data.*
import java.math.BigInteger
import java.util.*
import kotlin.collections.listOf
import kotlin.collections.toList

private const val MAX_TYPE_DEPTH = 2

abstract class ConverterTests {

    protected fun templateTestCorrespondenceImpliesConvertibility(
        evmType: EVMTypeDescriptor,
        ctxt: FromVMContext
    ) {
        val cvlType = CVLType.VM(evmType, ctxt).getPureCVLTypeToConvertTo().resultOrNull()
        Assume.that(
            cvlType != null
        )
        Assertions.assertTrue(
            ctxt.supportsConversion(evmType, cvlType!!).isResult()
        )
    }

    protected fun templateTestReflexivity(
        cvlType: CVLType.PureCVLType
    ) {
        Assertions.assertTrue(cvlType isSubtypeOf cvlType)
    }


    protected fun templateTestTransitivity(
        t: CVLType.PureCVLType,
        u: CVLType.PureCVLType,
        v: CVLType.PureCVLType
    ) {
        Assume.that(t isSubtypeOf u && u isSubtypeOf v)
        Assertions.assertTrue(t isSubtypeOf v)
    }

    protected fun templateTestTagSubtyping(
        t: CVLType.PureCVLType,
        u: CVLType.PureCVLType
    ) {
        val typeChecker = CVLTypeTypeChecker(CVLSymbolTable())
        Assume.that(t != CVLType.PureCVLType.Void) // Void doesn't have a tag
        Assume.that(typeChecker.typeCheck(t, Range.Empty(), CVLScope.AstScope) is CollectingResult.Result)
        Assume.that(typeChecker.typeCheck(u, Range.Empty(), CVLScope.AstScope) is CollectingResult.Result)
        Assume.that(t isSubtypeOf u)
        Assertions.assertTrue(t.toTag().isSubtypeOf(u.toTag()))
    }

    private fun CVLType.PureCVLType.inlineAliases(): CVLType.PureCVLType =
        when (this) {
            CVLType.PureCVLType.Bottom -> this
            is CVLType.PureCVLType.ArrayLiteral -> this.copy(elementTypes = this.elementTypes.map { it.inlineAliases() }, leastUpperBoundElementType = this.leastUpperBoundElementType.inlineAliases())
            CVLType.PureCVLType.DynamicArray.PackedBytes -> this
            CVLType.PureCVLType.DynamicArray.StringType -> this
            is CVLType.PureCVLType.DynamicArray.WordAligned -> this.copy(elementType = this.elementType.inlineAliases())
            is CVLType.PureCVLType.StaticArray -> this.copy(elementType = this.elementType.inlineAliases())
            is CVLType.PureCVLType.Enum -> this
            is CVLType.PureCVLType.Ghost.Function -> this.copy(inParams = this.inParams.map { it.inlineAliases() }, outParam = this.outParam.inlineAliases())
            is CVLType.PureCVLType.Ghost.Mapping -> this.copy(key = this.key.inlineAliases(), value = this.value.inlineAliases())
            CVLType.PureCVLType.Primitive.AccountIdentifier -> this
            CVLType.PureCVLType.Primitive.Bool -> this
            is CVLType.PureCVLType.Primitive.BytesK -> this
            is CVLType.PureCVLType.Primitive.CodeContract -> this
            CVLType.PureCVLType.Primitive.HashBlob -> this
            is CVLType.PureCVLType.Primitive.IntK -> this
            CVLType.PureCVLType.Primitive.Mathint -> this
            is CVLType.PureCVLType.Primitive.NumberLiteral -> this
            is CVLType.PureCVLType.Primitive.UIntK -> this
            is CVLType.PureCVLType.Sort -> this
            is CVLType.PureCVLType.Struct -> this.copy(fields = this.fields.map { it.copy(cvlType = it.cvlType.inlineAliases()) })
            is CVLType.PureCVLType.UserDefinedValueType -> this.base
            CVLType.PureCVLType.VMInternal.BlockchainState -> this
            CVLType.PureCVLType.VMInternal.RawArgs -> this
            CVLType.PureCVLType.Void -> this
            is CVLType.PureCVLType.VMInternal.BlockchainStateMap,
            is CVLType.PureCVLType.VMInternal.StorageReference -> this

            is CVLType.PureCVLType.TupleType -> CVLType.PureCVLType.TupleType(this.elements.map { it.inlineAliases() })
        }


    protected fun templateTestAntisymmetry(
        t: CVLType.PureCVLType,
        u: CVLType.PureCVLType
    ) {
        Assume.that(t isSubtypeOf u && u isSubtypeOf t)
        Assertions.assertTrue(t.inlineAliases() == u.inlineAliases())
    }

    /**
     * Extensible list of important cases to subtypingCoherent test
     *
     * A place to put any concrete cases which fail on CI that we want to make sure always get checked
     */
    @Data
    fun problematicSubtypingCoherentInConvertibilityToVMCases(): Iterable<Tuple.Tuple4<CVLType.PureCVLType, CVLType.PureCVLType, EVMTypeDescriptor, ToVMContext>> =
        listOf(
            Tuple.of(
                // static array should not be subtype of dynamic array
                CVLType.PureCVLType.StaticArray(CVLType.PureCVLType.Primitive.AccountIdentifier, BigInteger.ONE),
                CVLType.PureCVLType.DynamicArray.WordAligned(CVLType.PureCVLType.Primitive.AccountIdentifier),
                EVMTypeDescriptor.DynamicArrayDescriptor(EVMLocationSpecifier.calldata, EVMTypeDescriptor.address),
                ToVMContext.ArgumentPassing
            )
        )

    /**
     * Allows to specify an implication, and generate a test which will work both in cases where we do want to prune
     * cases, and cases where we don't (for example, if we have tests from data, and the test passes because the
     * antecedent is false, if we prune using [Assume] then jqwik will complain that we never got any valid cases.
     */
    data class ConverterProperty<T: Tuple>(
        val antecedent: (T) -> Boolean,
        val consequent: (T) -> Boolean
    ) {
        /**
         * Use with randomly generated cases to make sure you get "interasting" cases (i.e. not just "vacuously" true).
         */
        fun testWithPruning(input: T) {
            Assume.that(antecedent(input))
            Assertions.assertTrue(consequent(input))
        }

        fun testWithoutPruning(input: T) {
            Assertions.assertTrue(!antecedent(input) || consequent(input))
        }
    }

    private val subtypingCoherent = ConverterProperty<Tuple4<CVLType.PureCVLType, CVLType.PureCVLType, EVMTypeDescriptor, ToVMContext>>(
        { (t, u, v, ctxt) ->
            val typeChecker = CVLTypeTypeChecker(CVLSymbolTable())
            t isSubtypeOf u &&
                typeChecker.typeCheck(t, Range.Empty(), CVLScope.AstScope) is CollectingResult.Result &&
                typeChecker.typeCheck(u, Range.Empty(), CVLScope.AstScope) is CollectingResult.Result &&
                ctxt.supportsConversion(u, v).isResult()
        },
        { (t, _, v, ctxt) ->
            ctxt.supportsConversion(t, v).isResult()
        }
    )

    /**
     * If anyone can figure out how to *mix in* from data cases with random cases that would be *awesome*
     */
    protected fun templateSpecificTestSubtypingCoherentInConvertibilityToVM(
        t: CVLType.PureCVLType,
        u: CVLType.PureCVLType,
        v: EVMTypeDescriptor,
        ctxt: ToVMContext
    ) {
        subtypingCoherent.testWithoutPruning(Tuple.of(t, u, v, ctxt))
    }

    protected fun templateFuzzTestSubtypingCoherentInConvertibilityToVM(
        t: CVLType.PureCVLType,
        u: CVLType.PureCVLType,
        v: EVMTypeDescriptor,
        ctxt: ToVMContext
    ) {
        subtypingCoherent.testWithPruning(Tuple.of(t, u, v, ctxt))
    }

    protected fun templateTestSubtypingCoherentInConvertibilityFromVM(
        t: CVLType.PureCVLType,
        u: CVLType.PureCVLType,
        v: EVMTypeDescriptor,
        ctxt: FromVMContext
    ) {
        val typeChecker = CVLTypeTypeChecker(CVLSymbolTable())
        Assume.that(t isSubtypeOf u &&
            typeChecker.typeCheck(t, Range.Empty(), CVLScope.AstScope) is CollectingResult.Result &&
            typeChecker.typeCheck(u, Range.Empty(), CVLScope.AstScope) is CollectingResult.Result &&
            ctxt.supportsConversion(v, t).isResult())
        Assertions.assertTrue(ctxt.supportsConversion(v, u).isResult())
    }

    protected fun templateTestMergingCommutative(
        t: EVMTypeDescriptor,
        u: EVMTypeDescriptor
    ) {
        val v = t.mergeWith(u).resultOrNull()

        // we could remove this assumption, but it is useful to make sure we aren't getting too large a set where
        // the types do not merge. However, we can still catch cases where one direction returns not-null and the other
        // returns null
        Assume.that(
            v != null
        )
        Assertions.assertEquals(v, u.mergeWith(t).resultOrNull())
    }

    protected fun templateTestMergingAssociative(
        t: EVMTypeDescriptor,
        u: EVMTypeDescriptor,
        v: EVMTypeDescriptor
    ) {
        val w = t.mergeWith(u).bind { t_u -> t_u.mergeWith(v) }.resultOrNull()
        Assume.that(w != null)
        val x = u.mergeWith(v).bind { u_v -> t.mergeWith(u_v) }.resultOrNull()
        Assertions.assertEquals(x, w)
    }


    protected fun templateTestConvertibilityImpliesCodeGenWorks(
        cvlType: CVLType.PureCVLType,
        evmType: EVMTypeDescriptor,
        ctxt: ConversionSpec<*, *, *>
    ) {
        val typeChecker = CVLTypeTypeChecker(CVLSymbolTable())
        Assume.that(
            typeChecker.typeCheck(cvlType, scope = CVLScope.AstScope, range = Range.Empty()) is CollectingResult.Result
        )
        fun <T, SRC, DST> open(c: ConversionSpec<T, SRC, DST>) {
            val conv = c.conversionCheck(
                evmType,
                cvlType,
                c.context
            )
            Assume.that(
                conv is CollectingResult.Result
            )
            val gen = { ->
                conv.force().convertTo(
                    cb = { it },
                    toVar = c.dst(cvlType, evmType),
                    fromVar = c.src(cvlType, evmType)
                )
            }
            if (cvlType.withoutValue()) {
                Assertions.assertThrows(IllegalArgumentException::class.java) { gen() }
            } else {
                Assertions.assertDoesNotThrow(gen)
            }
        }
        open(ctxt)
    }

    protected fun templateTestToTagTotal(
        cvlType: CVLType.PureCVLType
    ) {
        val typeChecker = CVLTypeTypeChecker(CVLSymbolTable())
        Assume.that(typeChecker.typeCheck(cvlType, Range.Empty(), CVLScope.AstScope) is CollectingResult.Result && cvlType != CVLType.PureCVLType.Void)
        Assertions.assertDoesNotThrow(cvlType::toTag)
    }


    private val contractNames = Arbitraries.of(
        "Contract1",
        "Contract2"
    )

    data class ConversionSpec<T, SRC, DST>(
        val context: T,
        val conversionCheck: (VMTypeDescriptor, CVLType.PureCVLType, T) -> CollectingResult<ITypeDescriptorConverter<IConverterOutput, SRC, DST>, String>,
        val src: (CVLType.PureCVLType, VMTypeDescriptor) -> SRC,
        val dst: (CVLType.PureCVLType, VMTypeDescriptor) -> DST
    )

    private val dummyCVLInput = { ty: CVLType.PureCVLType, _: VMTypeDescriptor ->
        CVLDataInput(
            callId = NBId.ROOT_CALL_ID,
            v = TACSymbol.Var("srcVar", ty.toTag())
        )
    }

    private val dummyCVLOutput = { ty: CVLType.PureCVLType, _: VMTypeDescriptor ->
        CVLDataOutput(
            id = NBId.ROOT_CALL_ID,
            v = TACSymbol.Var("dstVar", ty.toTag())
        )
    }

    private val dummyCVLVar = { ty: CVLType.PureCVLType, _: VMTypeDescriptor -> TACSymbol.Var("srcVar", ty.toTag()) }

    private val dummyTacVar1 = TACSymbol.Var("dummy1", Tag.Bit256)
    private val dummyTacVar2 = TACSymbol.Var("dummy2", Tag.Bit256)
    private val dummyTacVar3 = TACSymbol.Var("dummy3", Tag.Bit256)

    private val dummyBufferVar = TACSymbol.Var("dummyBuffer", Tag.ByteMap)

    private val fromVMContext = listOf(
        FromVMContext.HookValue,
        FromVMContext.ReturnValue,
        FromVMContext.ExternalSummaryArgBinding,
        FromVMContext.InternalSummaryArgBinding
    ).anyValue()

    private val toVMContext = listOf(
        ToVMContext.ArgumentPassing,
        ToVMContext.ExternalSummaryReturn,
        ToVMContext.InternalSummaryReturn
    ).anyValue()

    private val context = object : CVLCompiler.CallIdContext {
        override val baseCallId: CallId
            get() = NBId.ROOT_CALL_ID

    }

    private fun LowLevelEncoder.captureReserved(vmType: VMTypeDescriptor) : LowLevelEncoder {
        /*
         * This black magic incantation relies on the fact that the reserve implementation we're calling here
         * returns a stateless object that will still "work" outside of its scope. This is obviously not something you should
         * do in general, but this is a test so its fine
         */
        lateinit var captured : LowLevelEncoder
        this.advanceNext((vmType as EVMTypeDescriptor).sizeAsEncodedMember()) {
            it.saveScope {
                captured = it
                EncoderOutput(object : WithOutputPointer {
                    override val outputPointer: TACSymbol.Var
                        get() = TACSymbol.Var("dummy", Tag.Bit256)

                }, CommandWithRequiredDecls(listOf(TACCmd.Simple.NopCmd)).toProg("whatever", context))
            }
        }
        return captured
    }

    private fun Pair<LowLevelDecoder, CommandWithRequiredDecls<TACCmd.Spec>>.captureScope() : LowLevelDecoder {
        lateinit var captured: LowLevelDecoder
        this.first.saveScope {
            captured = it
            CommandWithRequiredDecls(TACCmd.Simple.NopCmd).toProg("empty", context)
        }
        return captured
    }

    private fun internalRetForType(
        ty: EVMTypeDescriptor
    ) : EVMInternalReturnData {
        return when(val x = fieldForType(ty)) {
            is InternalFuncValueLocation.PrimitiveField -> EVMInternalReturnData.invoke(dummyTacVar1)
            is InternalFuncValueLocation.ReferenceField -> EVMInternalReturnData.invoke(dummyTacVar2, x.fields, 0)
        }
    }

    @OptIn(ConverterTestOnly::class)
    private fun internalArgForType(
        ty: EVMTypeDescriptor
    ) : EVMTypeDescriptor.EVMInternalSummaryInput {
        if(ty is EVMTypeDescriptor.EVMReferenceType && ty.location == EVMLocationSpecifier.calldata) {
            if(ty is EVMTypeDescriptor.EVMValueArrayConverter) {
                return EVMInternalCalldataArg.DecomposedArrayPointers(
                    lengthVar = dummyTacVar1,
                    elemPointerVar = dummyTacVar2,
                    calldataSize = dummyTacVar3,
                    buffer = dummyBufferVar,
                    scalars = mapOf()
                )
            } else {
                return EVMInternalCalldataArg.BasicCalldataPointer(
                    pointer = dummyTacVar1,
                    calldataSize = dummyTacVar2,
                    buffer = dummyBufferVar,
                    scalars = mapOf()
                )
            }
        }
        return when(val x = fieldForType(ty)) {
            is InternalFuncValueLocation.PrimitiveField -> {
                EVMInternalArgData.forPrimitive(dummyTacVar1)
            }
            is InternalFuncValueLocation.ReferenceField -> {
                EVMInternalArgData.forReference(dummyTacVar2, x.fields)
            }
        }
    }

    private fun fieldForType(
        ty: EVMTypeDescriptor
    ) : InternalFuncValueLocation.PointerLayoutInfo {
        return when(ty) {
            is EVMTypeDescriptor.DynamicArrayDescriptor -> {
                InternalFuncValueLocation.ReferenceField(
                    partitionVariable = TACSymbol.Var("tacMArrayField", Tag.ByteMap),
                    fields = mapOf(
                        PartitionField.ArrayLength to InternalFuncValueLocation.PrimitiveField(TACSymbol.Var("tacMArrayLength", Tag.ByteMap)),
                        PartitionField.ArrayElement(elementSize = EVM_WORD_SIZE_INT) to fieldForType(ty.elementType)
                    )
                )
            }
            is EVMTypeDescriptor.EVMMappingDescriptor,
            is EVMTypeDescriptor.FunctionDescriptor -> Assertions.fail("Cannot mock $ty")
            is EVMTypeDescriptor.EVMStructDescriptor -> {
                InternalFuncValueLocation.ReferenceField(
                    partitionVariable = TACSymbol.Var("tacMStructField", Tag.ByteMap),
                    fields = ty.fields.withIndex().associate {
                        val offs = it.index.toBigInteger() * EVM_WORD_SIZE
                        PartitionField.StructField(offs) to fieldForType(it.value.fieldType)
                    }
                )
            }
            is EVMTypeDescriptor.PackedBytes1Array -> {
                InternalFuncValueLocation.ReferenceField(
                    partitionVariable = TACSymbol.Var("tacMArrayField", Tag.ByteMap),
                    fields = mapOf(
                        PartitionField.ArrayLength to InternalFuncValueLocation.PrimitiveField(TACSymbol.Var("tacMArrayLength", Tag.ByteMap)),
                        PartitionField.ArrayElement(elementSize = 1) to InternalFuncValueLocation.PrimitiveField(TACSymbol.Var("tacMBytesData", Tag.ByteMap))
                    )
                )
            }
            is EVMTypeDescriptor.StaticArrayDescriptor -> {
                val elementLayout = fieldForType(ty.elementType)
                InternalFuncValueLocation.ReferenceField(
                    partitionVariable = TACSymbol.Var("tacMStaticArrayField", Tag.ByteMap),
                    fields = (0 until ty.numElements.intValueExact()).associate {
                        val offset = it.toBigInteger() * EVM_WORD_SIZE
                        PartitionField.StructField(offset) to elementLayout
                    }
                )
            }
            is EVMTypeDescriptor.EVMValueType -> InternalFuncValueLocation.PrimitiveField(
                partitionVariable = TACSymbol.Var("tacMPrimitiveField", Tag.ByteMap)
            )
        }

    }


    private val contexts = listOf<ConversionSpec<*, *, *>>(
        ConversionSpec(
            ToVMContext.ArgumentPassing.getVisitor(),
            VMTypeDescriptor::converterFrom,
            dummyCVLInput
        ) { _, ty ->
            LowLevelEncoder(
                buffer = TACKeyword.CALLDATA.toVar(),
                scalars = mapOf(DEFAULT_SIGHASH_SIZE to TACSymbol.Var("scalar", Tag.Bit256)),
                offset = BigInteger.ZERO.asTACSymbol()
            ).first.captureReserved(ty)
        },
        ConversionSpec(
            ToVMContext.ExternalSummaryReturn.getVisitor(),
            VMTypeDescriptor::converterFrom,
            dummyCVLInput,
            dst = { _, ty ->
                LowLevelEncoder(
                    buffer = TACKeyword.RETURNDATA.toVar(),
                    scalars = mapOf(),
                    offset = BigInteger.ZERO.asTACSymbol()
                ).first.captureReserved(ty)
            }
        ),
        ConversionSpec(
            ToVMContext.InternalSummaryReturn.getVisitor(),
            VMTypeDescriptor::converterFrom,
            dummyCVLInput,
            dst = { _, vmType ->
                Assertions.assertTrue(vmType is EVMTypeDescriptor)
                internalRetForType(vmType as EVMTypeDescriptor)
            }
        ),
        ConversionSpec(
            FromVMContext.ReturnValue.getVisitor(),
            VMTypeDescriptor::converterTo,
            dst = dummyCVLOutput,
            src =  { _, _ ->
                LowLevelDecoder(
                    buffer = TACKeyword.MEMORY.toVar(),
                    offset = dummyTacVar1,
                ).captureScope()
            }
        ),
        ConversionSpec(
            FromVMContext.HookValue.getVisitor(),
            VMTypeDescriptor::converterTo,
            dst = dummyCVLVar,
            src = { _, _ ->
                    HookValue.Direct(dummyTacVar2.asSym())
            }
        ),
        ConversionSpec(
            FromVMContext.InternalSummaryArgBinding.getVisitor(),
            VMTypeDescriptor::converterTo,
            dst = dummyCVLOutput,
            src = { _, vmType ->
                Assertions.assertTrue(vmType is EVMTypeDescriptor)
                internalArgForType(vmType as EVMTypeDescriptor)
            }
        ),
        ConversionSpec(
            FromVMContext.ExternalSummaryArgBinding.getVisitor(),
            VMTypeDescriptor::converterTo,
            dst = dummyCVLOutput,
            src = { _, _ ->
                LowLevelDecoder(
                    buffer = TACKeyword.MEMORY.toVar(),
                    offset = dummyTacVar1,
                ).captureScope()
            }
        )
    ).anyValue()

    private val primitiveCVLGenerator: List<(Int) -> Arbitrary<CVLType.PureCVLType.Primitive>> = listOf(
        // uint
        cvlBitwidthTypeGenerator(CVLType.PureCVLType.Primitive::UIntK),
        // int
        cvlBitwidthTypeGenerator(CVLType.PureCVLType.Primitive::IntK),
        // bytesk
        { _ -> byteWidths.map(CVLType.PureCVLType.Primitive::BytesK) },
        CVLType.PureCVLType.Primitive.Mathint.liftSingleton(),
        CVLType.PureCVLType.Primitive.Bool.liftSingleton(),
        CVLType.PureCVLType.Primitive.HashBlob.liftSingleton(),
        CVLType.PureCVLType.Primitive.AccountIdentifier.liftSingleton(),
        { _ ->
            contractNames.map {
                CVLType.PureCVLType.Primitive.CodeContract(name = SolidityContract(it))
            }
        }
    )

    private fun <T : Any> List<T>.arbitraryForEach() = this.map {
        it.liftSingleton()
    }

    private val exoticTypes = listOf(
        CVLType.PureCVLType.VMInternal.BlockchainState,
        CVLType.PureCVLType.VMInternal.RawArgs,
        CVLType.PureCVLType.Void,
    ).arbitraryForEach()

    private val cvlBaseCases: List<(Int) -> Arbitrary<CVLType.PureCVLType>> =
        (primitiveCVLGenerator.uncheckedAs<List<(Int) -> Arbitrary<CVLType.PureCVLType>>>()) + CVLType.PureCVLType.DynamicArray.StringType.liftSingleton<CVLType.PureCVLType>() +
            CVLType.PureCVLType.DynamicArray.PackedBytes.liftSingleton<CVLType.PureCVLType>() + { _ ->
            enumNames.map { (nm, members) ->
                @Suppress("USELESS_CAST") // needed for type inference to work worth a damn
                CVLType.PureCVLType.Enum(
                    name = nm,
                    elements = members
                ) as CVLType.PureCVLType
            }
        } + { _ ->
            flatCombine(userValueNames, primitiveCVLGenerator.anyValue()) { udtName, tyGenerator ->
                tyGenerator(0).map { ty ->
                    @Suppress("USELESS_CAST")
                    CVLType.PureCVLType.UserDefinedValueType(
                        base = ty,
                        name = udtName
                    ) as CVLType.PureCVLType
                }
            }
        }

    private val cvlAggregateGenerator : List<(Int) -> Arbitrary<CVLType.PureCVLType>> = listOf(
        { depth ->
            cvlTypeGenerator(depth + 1).map {
                CVLType.PureCVLType.DynamicArray.WordAligned(
                    elementType = it
                )
            }
        },
        { depth ->
            combine(staticArraySizes, cvlTypeGenerator(depth + 1)) { sz, ty ->
                CVLType.PureCVLType.StaticArray(
                    elementType = ty,
                    logicalLength = sz.toBigInteger()
                )
            }
        },
        { depth ->
            structNames.flatMap { (nm, fields) ->
                combine(fields.map {
                    cvlTypeGenerator(depth + 1).map { ty ->
                        CVLType.PureCVLType.Struct.StructEntry(
                            fieldName = it,
                            cvlType = ty
                        )
                    }
                }) { flds ->
                    CVLType.PureCVLType.Struct(
                        name = nm,
                        fields = flds
                    )
                }
            }
        }
    )

    private val nestableCVLTypes = cvlAggregateGenerator + cvlBaseCases

    private val cvlGenerators = nestableCVLTypes + exoticTypes +  { _: Int ->
        arbitraryLiterals.map {
            @Suppress("USELESS_CAST")
            CVLType.PureCVLType.Primitive.NumberLiteral(it) as CVLType.PureCVLType
        }
    }

    private val arbitraryLiterals = listOf(
        BigInteger.ZERO,
        BigInteger.ONE,
        BigInteger.TWO,
        BigInteger.TEN,
        EVM_MOD_GROUP256, // out of bounds for all evm types
        BigInteger.ONE.negate() // suck on this converters
    ).anyValue()

    private fun cvlTypeGenerator(depth: Int): Arbitrary<CVLType.PureCVLType> {
        val generators = if(depth > MAX_TYPE_DEPTH) {
            cvlBaseCases
        } else if(depth == 0) {
            cvlGenerators
        } else {
            nestableCVLTypes
        }
        return generators.anyValue().flatMap {gen ->
            gen(depth)
        }
    }

    private fun CVLType.PureCVLType.withoutValue() = this is CVLType.PureCVLType.Void || this is CVLType.PureCVLType.Bottom

    @Provide("cvlType")
    fun cvlTypeGenerator(): Arbitrary<CVLType.PureCVLType> {
        return cvlTypeGenerator(0)
    }

    @BeforeProperty
    fun setup() {
        EVMTypeDescriptor.resetVTable() // due to weird interaction with TestCVL
        theSemantics = EVMMoveSemantics
    }

    @Provide("contexts")
    fun contexts() = contexts

    @Provide("toVMContext")
    fun toVMContexts() = toVMContext
    @Provide("fromVMContext")
    fun fromVMContexts() = fromVMContext

    private fun <T : Any> T.liftSingleton(): (Int) -> Arbitrary<T> = { _ -> Arbitraries.of(this) }

    private val bitWidths: Arbitrary<Int> = Arbitraries.of((8..256).step(8).toList())
    private val byteWidths : Arbitrary<Int> = Int.any(1..32)
    private val locations: Arbitrary<EVMLocationSpecifier?> =
        Arbitraries.of("calldata", "memory", "storage").map(EVMLocationSpecifier::fromString).orNull(0.25)

    private fun <T> evmBitwidthTypeGenerator(f: (Int) -> T): (Int, Optional<EVMLocationSpecifier>?) -> Arbitrary<T> where T : EVMTypeDescriptor.EVMValueType =
        { _, _ -> bitWidths.map(f) }

    private fun <T> cvlBitwidthTypeGenerator(f: (Int) -> T): (Int) -> Arbitrary<T> where T : CVLType.PureCVLType.Primitive =
        { _ -> bitWidths.map(f) }

    private val enumNames = listOf(
        "Yeet" to listOf(
            "m1", "m2", "m3"

        ),
        "Yolo" to listOf("n1", "n2" /* REI!!!! */),
    ).anyValue()

    private fun <T : Any> T.liftEVMSingleton(): (Int, Optional<EVMLocationSpecifier>?) -> Arbitrary<T> = { _, _ -> Arbitraries.of(this) }

    private val evmIsoValueTypes: List<(Int, Optional<EVMLocationSpecifier>?) -> Arbitrary<EVMTypeDescriptor.EVMIsomorphicValueType>> = listOf(
        // uint
        evmBitwidthTypeGenerator(EVMTypeDescriptor::UIntK),
        // int
        evmBitwidthTypeGenerator(EVMTypeDescriptor::IntK),
        // bytesk
        { _, _ -> byteWidths.map(EVMTypeDescriptor::BytesK) },
        // bool
        EVMTypeDescriptor.bool.liftEVMSingleton(),
        // address
        EVMTypeDescriptor.address.liftEVMSingleton(),
    )


    private val evmPrimitiveGenerator: List<(Int, Optional<EVMLocationSpecifier>?) -> Arbitrary<EVMTypeDescriptor.EVMIsomorphicValueType>> =
        (evmIsoValueTypes + { _, _ ->
            enumNames.map { (nm, members) ->
                EVMTypeDescriptor.EVMEnumDescriptor(
                    canonicalId = nm, name = nm, elements = members
                )
            }
        })

    private val userValueNames = listOf(
        "UD1",
        "UD2"
    ).anyValue()

    // packed bytes are a base case "primitive" because they don't recurse
    private val evmBaseCases: List<(Int, Optional<EVMLocationSpecifier>?) -> Arbitrary<EVMTypeDescriptor>> =
        (evmPrimitiveGenerator.uncheckedAs<List<(Int, Optional<EVMLocationSpecifier>?) -> Arbitrary<EVMTypeDescriptor>>>()) + listOf({ _, parentLoc: Optional<EVMLocationSpecifier>? ->
            parentLoc.generate { loc ->
                Boolean.any().map {isString ->
                    if(isString) {
                        EVMTypeDescriptor.StringType(loc)
                    } else {
                        EVMTypeDescriptor.PackedBytes(loc)
                    }
                }
            }
        }, { _, _ ->
            // call these functions map and bind you COWARD
            flatCombine(userValueNames, evmIsoValueTypes.anyValue()) { nm, generator ->
                generator(0, null).map { valueType ->
                    EVMTypeDescriptor.UserDefinedValueType(
                        name = nm,
                        canonicalId = nm,
                        baseType = valueType
                    )
                }
            }
        })

    private val staticArraySizes = Arbitraries.of(1, 5, 8, 20)

    private val structNames = listOf(
        "Foo" to listOf("f1", "f2", "f3"),
        "Bar" to listOf("f1", "f2")
    ).anyValue()

    private fun <T : Any> Optional<EVMLocationSpecifier>?.generate(f: (EVMLocationSpecifier?) -> Arbitrary<T>) : Arbitrary<T> = if(this != null) {
        this.orElse(null).let(f)
    } else {
        locations.flatMap { l ->
            f(l)
        }
    }

    private val evmAggregateGenerators: List<(Int, Optional<EVMLocationSpecifier>?) -> Arbitrary<EVMTypeDescriptor>> = listOf(
        // array
        { depth, parentLoc ->
            parentLoc.generate { loc ->
                evmDescriptorGenerator(depth + 1, Optional.ofNullable(loc)).map { elemType ->
                    EVMTypeDescriptor.DynamicArrayDescriptor(loc, elemType)
                }
            }

        },
        // static arrays
        { depth, parentLoc ->
            parentLoc.generate { loc ->
                combine(evmDescriptorGenerator(depth + 1, loc), staticArraySizes) { elemType, staticSize ->
                    EVMTypeDescriptor.StaticArrayDescriptor(
                        location = loc,
                        elementType = elemType,
                        numElements = staticSize.toBigInteger()
                    )
                }
            }
        },
        // structs
        { depth, parentLoc ->
            parentLoc.generate { loc ->
                structNames.flatMap { (nm, fields) ->
                    contractNames.flatMap { contract ->
                        combine(fields.map { fName ->
                            evmDescriptorGenerator(depth + 1, loc).map { fieldType ->
                                EVMTypeDescriptor.EVMStructDescriptor.EVMStructEntry(
                                    fieldName = fName,
                                    fieldType = fieldType
                                )
                            }
                        }) { fieldDefs ->
                            val name = "$contract.$nm"
                            EVMTypeDescriptor.EVMStructDescriptor(
                                canonicalId = name,
                                name = name,
                                location = loc,
                                fields = fieldDefs,
                            )
                        }
                    }
                }
            }
        }
    )

    private val evmGenerators: List<(Int, Optional<EVMLocationSpecifier>?) -> Arbitrary<EVMTypeDescriptor>> = evmBaseCases + evmAggregateGenerators

    private fun evmDescriptorGenerator(depth: Int, loc: EVMLocationSpecifier?): Arbitrary<EVMTypeDescriptor> = evmDescriptorGenerator(depth, Optional.ofNullable(loc))

    private fun evmDescriptorGenerator(depth: Int, loc: Optional<EVMLocationSpecifier>?): Arbitrary<EVMTypeDescriptor> {
        val genSelector = if (depth > MAX_TYPE_DEPTH) {
            evmBaseCases
        } else {
            evmGenerators
        }
        return genSelector.anyValue().flatMap { gen ->
            gen(depth, loc)
        }
    }

    @Provide("evmType")
    fun evmDescriptorGenerator(): Arbitrary<EVMTypeDescriptor> {
        return evmDescriptorGenerator(0, null as Optional<EVMLocationSpecifier>?)
    }
}
