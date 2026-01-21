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
package spec.cvlast.typedescriptors

import com.certora.collect.*
import evm.EVM_BITWIDTH256
import evm.EVM_WORD_SIZE
import evm.EVM_WORD_SIZE_INT
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import spec.EVMConfig
import spec.cvlast.CVLType
import spec.cvlast.abi.DataLayout
import spec.cvlast.abi.DataLayout.LengthTaggedTuple
import spec.cvlast.abi.PrimitiveBufferRepresentation
import spec.cvlast.typedescriptors.TerminalAction.Companion.sizeAsEncodedMember
import spec.cvlast.typedescriptors.VMTypeWithCorrespondence.NeedsConversionCheck.Companion.build
import tac.ITACCmd
import tac.ITACSymbolVar
import tac.Tag
import utils.*
import utils.CollectingResult.Companion.asError
import utils.CollectingResult.Companion.bind
import utils.CollectingResult.Companion.bindEither
import utils.CollectingResult.Companion.flatten
import utils.CollectingResult.Companion.lift
import utils.CollectingResult.Companion.map
import utils.CollectingResult.Companion.mapErrors
import utils.CollectingResult.Companion.ok
import utils.CollectingResult.Companion.reduceErrors
import java.math.BigInteger
import java.util.*
import kotlin.reflect.KProperty

private typealias EVMDataLayout = DataLayout<EVMTypeDescriptor.EVMValueType>

/**
 * Used to indicate that an array element (in an internal function summary context) should be converted
 * using a converter (left) or long copied with the given encoding (right).
 */
typealias InternalElementConverter<SRC, DST> = Either<ITypeDescriptorConverter<IProgramOutput, SRC, DST>, Tag.CVLArray.UserArray.ElementEncoding>

private typealias ConverterLayout<O, SRC, DST, I> = DataLayout<TerminalAction<O, SRC, DST, I>>

/**
 * Indicates a type allows querying the size of the element as an encoded member (in an ABI encoded buffer)
 */
sealed interface SupportsSizeQuery {
    fun sizeAsEncodedMember(): BigInteger
}

/**
 * Indicates that this converter operates on [DataLayout] instantiated with [TerminalAction].
 */
private sealed interface SupportsDataLayoutQuery {
    val dataLayout: DataLayout<TerminalAction<*, *, *, *>>
}

/**
 * [TerminalAction] is uses existential types and intersection types. [I] is constrained to be some type that
 * is a [ITypeDescriptorConverter] with the parameters [O], [SRC], and [DST], and is also constrained to be
 * [SupportsSizeQuery]. However, when we *use* the [TerminalAction] type, we always use `*` for the 4th type parameter,
 * leaving the actual choice of which type unconstrained. See [Composed].
 */
sealed interface TerminalAction<out O, in SRC, in DST, out I> : SupportsSizeQuery where I : ITypeDescriptorConverter<O, SRC, DST>, I : SupportsSizeQuery {
    /**
     * A [ValueType] is available to use as a terminal action in any conversion context, hence the extremely permissive types
     * in the [TerminalAction] super type.
     */
    data class ValueType(val ty: EVMTypeDescriptor.EVMValueType) : TerminalAction<Nothing, Any?, Any?, Nothing> {
        override fun sizeAsEncodedMember(): BigInteger {
            return ty.sizeAsEncodedMember()
        }
    }

    /**
     * This class simply wraps an instance of type [I]. This gives us an existential, intersection type:
     * If we have a `Composed<A, B, C, *>` then we know that [conv] is some type that supports a size query,
     * and which is an [ITypeDescriptorConverter] for A, B, C. Almost all consumers of the [TerminalAction] will
     * simply use `*`, letting us not expose exactly *how* we are composing converters.
     */
    data class Composed<O, SRC, DST, I>(val conv: I) : TerminalAction<O, SRC, DST, I> where I : ITypeDescriptorConverter<O, SRC, DST>, I: SupportsSizeQuery {
        override fun sizeAsEncodedMember(): BigInteger {
            return conv.sizeAsEncodedMember()
        }

    }

    companion object {

        fun <T: SupportsSizeQuery> DataLayout<T>.sizeAsEncodedMember(): BigInteger {
            return when(this) {
                is DataLayout.DynamicPointer -> EVMConfig.registerByteWidth.toBigInteger()
                is DataLayout.OpenScope -> this.next.sizeAsEncodedMember()
                is DataLayout.Terminal -> t.sizeAsEncodedMember()
                is DataLayout.TupleOf -> {
                    this.elements.map {
                        it.second.sizeAsEncodedMember()
                    }.reduce(BigInteger::add)
                }

                is DataLayout.StaticRepeatedOf -> {
                    this.num * this.elem.sizeAsEncodedMember()
                }
                is DataLayout.LengthTaggedTuple,
                is DataLayout.SequenceOf -> throw UnsupportedOperationException("Illegal layout $this for TerminalEncoding")
            }
        }
    }

}

/**
 * NB the use of `*` for the parameter I.
 */
typealias DecoderTerminal = TerminalAction<IProgramOutput, IExternalOutputBuffer, ICVLDataOutput, *>
typealias EncoderTerminal = TerminalAction<IExternalEncodeOutput, ICVLDataInput, IExternalInputBuffer, *>
typealias DecoderLayout = DataLayout<DecoderTerminal>
typealias EncoderLayout = DataLayout<EncoderTerminal>


private object SemanticsManager {
    private var semanticsInitialized = false

    private var theSemantics : EVMTypeDescriptor.ConverstionSemantics = object : EVMTypeDescriptor.ConverstionSemantics {
        override fun moveToHook(dest: ITACSymbolVar, destType: CVLType.PureCVLType, src: IHookParameter, srcType: VMValueTypeDescriptor, cb: (ITACCmd) -> ITACCmd): ISimpleOutput {
            uninitialized()
        }

        override fun moveFromStorage(dest: ITACSymbolVar, destType: CVLType.PureCVLType, src: ITACSymbolVar, srcType: VMValueTypeDescriptor, cb: (ITACCmd) -> ITACCmd) : ISimpleOutput{
            uninitialized()
        }

        override fun movePrimitiveToInternalReturnLocation(
            dest: EVMTypeDescriptor.EVMInternalSummaryReturn,
            destEncoding: Tag.CVLArray.UserArray.ElementEncoding,
            src: ICVLDataInput,
            srcType: CVLType.PureCVLType,
            cb: (ITACCmd) -> ITACCmd
        ): IProgramOutput {
            uninitialized()
        }

        override fun movePrimitiveFromInternalArg(
            dest: ICVLDataOutput,
            destType: CVLType.PureCVLType,
            src: EVMTypeDescriptor.EVMInternalSummaryInput,
            srcType: VMValueTypeDescriptor,
            cb: (ITACCmd) -> ITACCmd
        ): IProgramOutput {
            uninitialized()
        }

        override fun moveHashedArray(
            fromVar: IHookParameter,
            toVar: ITACSymbolVar,
            cb: (ITACCmd) -> ITACCmd,
        ): ISimpleOutput {
            uninitialized()
        }

        override fun moveStructToInternalReturn(
            dest: EVMTypeDescriptor.EVMInternalSummaryReturn,
            src: ICVLDataInput,
            fields: List<EVMTypeDescriptor.StructFieldConverter<ICVLDataInput, IInternalSummaryReturn>>,
            cb: (ITACCmd) -> ITACCmd
        ): IProgramOutput {
            uninitialized()
        }


        override fun encodeWithDataLayout(
            fromVar: ICVLDataInput,
            toVar: IExternalInputBuffer,
            layout: EncoderLayout,
            cb: (ITACCmd) -> ITACCmd
        ): IExternalEncodeOutput {
            uninitialized()
        }

        override fun decodeWithDataLayout(
            fromVar: IExternalOutputBuffer,
            toVar: ICVLDataOutput,
            encodingLayout: DecoderLayout,
            cb: (ITACCmd) -> ITACCmd
        ): IProgramOutput {
            uninitialized()
        }

        override fun moveArrayFromInternalArg(
            fromVar: EVMTypeDescriptor.EVMInternalSummaryInput,
            toVar: ICVLDataOutput,
            eSize: Int,
            cb: (ITACCmd) -> ITACCmd,
            elemConversion: InternalElementConverter<IInternalSummaryInput, ICVLDataOutput>
        ): IProgramOutput {
            uninitialized()
        }

        override fun moveArrayToInternalReturn(
            toVar: EVMTypeDescriptor.EVMInternalSummaryReturn,
            fromVar: ICVLDataInput,
            eSize: Int,
            cb: (ITACCmd) -> ITACCmd,
            elemConverter: InternalElementConverter<ICVLDataInput, IInternalSummaryReturn>
        ): IProgramOutput {
            uninitialized()
        }

        override fun moveStructFromInternalArg(
            fromVar: EVMTypeDescriptor.EVMInternalSummaryInput,
            toVar: ICVLDataOutput,
            fieldNameToOrdinal: Map<String, Int>,
            fields: List<EVMTypeDescriptor.StructFieldConverter<IInternalSummaryInput, ICVLDataOutput>>,
            cb: (ITACCmd) -> ITACCmd
        ): IProgramOutput {
            uninitialized()
        }

        override fun moveStaticArrayToInternalReturn(
            fromVar: ICVLDataInput,
            toVar: EVMTypeDescriptor.EVMInternalSummaryReturn,
            numFields: BigInteger,
            elementConverter: ITypeDescriptorConverter<IConverterOutput, ICVLDataInput, IInternalSummaryReturn>,
            cvlElementType: CVLType.PureCVLType,
            cb: (ITACCmd) -> ITACCmd
        ): IProgramOutput {
            uninitialized()
        }

        override fun moveStaticArrayFromInternalArg(
            fromVar: EVMTypeDescriptor.EVMInternalSummaryInput,
            toVar: ICVLDataOutput,
            elementType: CVLType.PureCVLType,
            numElements: BigInteger,
            elementConverter: ITypeDescriptorConverter<IConverterOutput, IInternalSummaryInput, ICVLDataOutput>,
            cb: (ITACCmd) -> ITACCmd
        ): IProgramOutput {
            uninitialized()
        }

        override fun movePrimitiveToMappingKey(
            fromVar: ITACSymbolVar,
            toVar: IStorageMapping,
            encoding: Tag.CVLArray.UserArray.ElementEncoding
        ): IMappingKeyOutput {
            uninitialized()
        }

        override fun reverseHashBlobStorageKey(fromVar: ITACSymbolVar, toVar: IStorageMapping): IMappingKeyOutput {
            uninitialized()
        }

        override fun movePackedBytesToStorageKey(fromVar: ITACSymbolVar, toVar: IStorageMapping): IMappingKeyOutput {
            uninitialized()
        }

        override fun moveCalldataArgToInternalArg(
            fromVar: EVMTypeDescriptor.EVMInternalSummaryInput,
            toVar: ICVLDataOutput,
            layout: DecoderLayout
        ): IProgramOutput {
            uninitialized()
        }

        override fun constrainNumericOutput(
            currConverter: IConverterOutput,
            cvlVar: ITACSymbolVar,
            srcVMType: EVMTypeDescriptor.EVMNumericType
        ): ISimpleOutput {
            uninitialized()
        }

        override fun movePrimitiveToPrimitive(
            fromType: CVLType.PureCVLType,
            fromVar: ITACSymbolVar,
            toType: EVMTypeDescriptor.EVMValueType,
            toVar: ITACSymbolVar
        ): ISimpleOutput {
            uninitialized()
        }

        private fun uninitialized() : Nothing {
            throw UnsupportedOperationException("conversion vtable uninitialized")
        }

    }
    operator fun setValue(thisRef: Nothing?,  property: KProperty<*>, value: EVMTypeDescriptor.ConverstionSemantics) {
        if(this.semanticsInitialized) {
            throw UnsupportedOperationException("conversion vtable already initialized")
        }
        this.semanticsInitialized = true
        this.theSemantics = value
    }

    operator fun getValue(thisRef: Nothing?, property: KProperty<*>) : EVMTypeDescriptor.ConverstionSemantics {
        return theSemantics
    }

    fun reset() {
        this.semanticsInitialized = false
    }
}

var theSemantics : EVMTypeDescriptor.ConverstionSemantics by SemanticsManager


/**
 * Named class to allow easier composition of converters that rely on the DataLayout (i.e., all external converters).
 */
private class DataLayoutEncoder(
    val encodingLayout: EncoderLayout
) : ITypeDescriptorConverter<IExternalEncodeOutput, ICVLDataInput, IExternalInputBuffer>, SupportsSizeQuery, SupportsDataLayoutQuery {
    override fun convertTo(
        fromVar: ICVLDataInput,
        toVar: IExternalInputBuffer,
        cb: (ITACCmd) -> ITACCmd
    ): IExternalEncodeOutput {
        return theSemantics.encodeWithDataLayout(fromVar, toVar, encodingLayout, cb)
    }

    override fun sizeAsEncodedMember(): BigInteger {
        return encodingLayout.sizeAsEncodedMember()
    }

    override val dataLayout: DataLayout<TerminalAction<*, *, *, *>>
        get() = encodingLayout
}

/**
 * As above for [DataLayoutEncoder], but for decoding.
 */
private class DataLayoutDecoder(
    val encodingLayout: DecoderLayout
) : ITypeDescriptorConverter<IProgramOutput, IExternalOutputBuffer, CallReturnTarget>, SupportsSizeQuery, SupportsDataLayoutQuery {
    override fun convertTo(
        fromVar: IExternalOutputBuffer,
        toVar: ICVLDataOutput,
        cb: (ITACCmd) -> ITACCmd
    ): IProgramOutput {
        return theSemantics.decodeWithDataLayout(fromVar, toVar, encodingLayout, cb)
    }

    override fun sizeAsEncodedMember(): BigInteger {
        return dataLayout.sizeAsEncodedMember()
    }

    override val dataLayout: DataLayout<TerminalAction<*, *, *, *>>
        get() = encodingLayout
}

/**
 * Turn terminals over value types into encoders/decoders over that type.
 */
private fun <I> DataLayout.Terminal<I>.liftEncoder() where I : EVMTypeDescriptor.EVMValueType = DataLayoutEncoder(DataLayout.Terminal(TerminalAction.ValueType(this.t))).lift()
private fun <I> DataLayout.Terminal<I>.liftDecoder() where I : EVMTypeDescriptor.EVMValueType = DataLayoutDecoder(DataLayout.Terminal(TerminalAction.ValueType(this.t))).lift()

/**
 * Turn layouts into encoders/decoders over those layouts
 */
private fun EncoderLayout.liftEncoder() = DataLayoutEncoder(this).lift()
private fun DecoderLayout.liftDecoder() = DataLayoutDecoder(this).lift()

@Serializable
sealed class EVMTypeDescriptor : VMTypeDescriptor {
    companion object {
        private operator fun DataLayout.Terminal.Companion.invoke(ty: EVMValueType) = DataLayout.Terminal(ty)

        private fun EVMTypeDescriptor.wrapScope(f: () -> EVMDataLayout) : EVMDataLayout =
            if(this.isDynamicType()) {
                DataLayout.OpenScope(f())
            } else {
                f()
            }

        /**
         * Returns how the type (descriptor) represented by this is encoded in a buffer, specifically,
         * a return and calldata buffer. The result of this function is used to ensure that CVL and the EVM
         * are assuming the same layout of data, in principle, we could aggressively move the argument passing/return decoding
         * semantics to use this data as well. The layout here is an almost direct transcribing of https://docs.soliditylang.org/en/develop/abi-spec.html
         */
        fun EVMTypeDescriptor.getDataLayout() : CollectingResult<EVMDataLayout, String> {
            return when(this) {
                is EVMValueType -> {
                    DataLayout.Terminal(ty = this).lift()
                }

                is DynamicArrayDescriptor -> this.elementType.getDataLayout().map { elemEncoding ->
                    LengthTaggedTuple(
                        this.elementType.wrapScope {
                            DataLayout.SequenceOf(
                                DataLayout.SequenceElement.Elements(
                                    elemEncoding
                                )
                            )
                        }
                    )
                }
                is EVMStructDescriptor -> {
                    this.fields.map { fld ->
                        fld.fieldType.getDataLayout().map { enc ->
                            fld.fieldName to enc
                        }
                    }.flatten().map { fldEncodings ->
                        this.wrapScope {
                            DataLayout.TupleOf(fldEncodings)
                        }
                    }
                }
                is PackedBytes1Array -> {
                    LengthTaggedTuple<EVMValueType>(
                        DataLayout.SequenceOf(DataLayout.SequenceElement.PackedBytes1(isString = this is StringType))
                    ).lift()
                }
                is StaticArrayDescriptor -> {
                    this.elementType.getDataLayout().map { elem ->
                        this.elementType.wrapScope {
                            DataLayout.StaticRepeatedOf(
                                elem, this@getDataLayout.numElements
                            )
                        }
                    }
                }

                is EVMMappingDescriptor -> "Cannot describe mapping type $this in an ABI buffer".asError()
                is FunctionDescriptor -> "Cannot describe function type $this in an ABI buffer".asError()
            }.map {
                if(this.isDynamicType()) {
                    DataLayout.DynamicPointer(it)
                } else {
                    it
                }
            }
        }


        // for testing
        fun resetVTable() {
            SemanticsManager.reset()
        }
    }

    fun sourceCorrespondence(toVMContext: ToVMContext) = (this as? VMTypeWithCorrespondence)?.correspondenceTypeToConvertFrom(toVMContext) ?: "${this.prettyPrint()} has no corresponding CVL type".asError()
    fun targetCorrespondence(fromVMContext: FromVMContext) = (this as? VMTypeWithCorrespondence)?.correspondenceTypeToConvertTo(fromVMContext) ?: "${this.prettyPrint()} has no corresponding CVL type".asError()

    abstract override fun prettyPrint(): String
    final override fun canonicalString(ctxt: PrintingContext): String {
        return this.canonicalString(EVMPrintingContext(ctxt.isLibrary, false))
    }

    protected abstract fun canonicalString(ctxt: EVMPrintingContext) : String

    abstract override fun mergeWith(other: VMTypeDescriptor): CollectingResult<VMTypeDescriptor, String>

    data class StructFieldConverter<SRC, DST>(
        val name: String,
        val tag: Tag,
        val descriptor: EVMTypeDescriptor,
        val conv: ITypeDescriptorConverter<IProgramOutput, SRC, DST>
    )

    abstract fun sizeAsEncodedMember() : BigInteger

    interface EVMInternalSummaryReturn : IInternalSummaryReturn
    interface EVMInternalSummaryInput : IInternalSummaryInput

    @Serializable
    sealed class EVMValueType : EVMTypeDescriptor(), VMValueTypeDescriptor, PrimitiveBufferRepresentation, SupportsSizeQuery {
        abstract val expectedBufferEncoding : Tag.CVLArray.UserArray.ElementEncoding

        override val bufferEncoding: Tag.CVLArray.UserArray.ElementEncoding
            get() = expectedBufferEncoding

        override fun sizeAsEncodedMember(): BigInteger = EVM_WORD_SIZE
        override fun getMinimumEncodingSize(): BigInteger = EVM_WORD_SIZE

        override fun prettyPrint(): String {
            return this.toString()
        }

        override fun mergeWith(other: VMTypeDescriptor): CollectingResult<VMTypeDescriptor, String> {
            if (other != this) {
                return "Cannot merge ${this.prettyPrint()} with ${other.prettyPrint()}".asError()
            }
            return this.lift()
        }

        inner class ToEVMValueTypeContextVisitor(val fromType: CVLType.PureCVLType, val toType: EVMValueType) : ToVMContext.Visitor {
            override fun internalSummaryReturnContext(): CollectingResult<ITypeDescriptorConverter<IProgramOutput, ICVLDataInput, IInternalSummaryReturn>, Nothing> {
                return object : ITypeDescriptorConverter<IProgramOutput, ICVLDataInput, IInternalSummaryReturn> {
                    override fun convertTo(
                        fromVar: ICVLDataInput,
                        toVar: IInternalSummaryReturn,
                        cb: (ITACCmd) -> ITACCmd,
                    ): IProgramOutput {
                        return theSemantics.movePrimitiveToInternalReturnLocation(
                            dest = toVar as EVMInternalSummaryReturn,
                            destEncoding = toType.expectedBufferEncoding,
                            src = fromVar,
                            srcType = fromType,
                            cb
                        )
                    }
                }.lift()
            }

            override fun directPassingContext(): CollectingResult<ITypeDescriptorConverter<ISimpleOutput, ITACSymbolVar, ITACSymbolVar>, String> {
                return object : ITypeDescriptorConverter<ISimpleOutput, ITACSymbolVar, ITACSymbolVar> {
                    override fun convertTo(
                        fromVar: ITACSymbolVar,
                        toVar: ITACSymbolVar,
                        cb: (ITACCmd) -> ITACCmd
                    ): ISimpleOutput {
                        return theSemantics.movePrimitiveToPrimitive(fromType, fromVar, toType, toVar)
                    }

                }.lift()
            }

            override fun argumentPassingContext(): CollectingResult<ITypeDescriptorConverter<IExternalEncodeOutput, ICVLDataInput, IExternalInputBuffer>, Nothing> {
                return DataLayout.Terminal(this@EVMValueType).liftEncoder()
            }

            override fun storageMappingKeyContext(): CollectingResult<ITypeDescriptorConverter<IMappingKeyOutput, ITACSymbolVar, IStorageMapping>, String> {
                return object : ITypeDescriptorConverter<IMappingKeyOutput, ITACSymbolVar, IStorageMapping> {
                    override fun convertTo(
                        fromVar: ITACSymbolVar,
                        toVar: IStorageMapping,
                        cb: (ITACCmd) -> ITACCmd
                    ): IMappingKeyOutput {
                        return theSemantics.movePrimitiveToMappingKey(
                            fromVar, toVar, expectedBufferEncoding
                        )
                    }

                }.lift()
            }
        }
    }

    @Serializable
    sealed class EVMIsomorphicValueType : EVMValueType(), ISOVMTypeDescriptor {

        override fun <O, SRC, DST> converterFrom(
            fromType: CVLType.PureCVLType,
            i: ToVMContextDispatcher<O, SRC, DST>,
        ): CollectingResult<ITypeDescriptorConverter<O, SRC, DST>, String> {
            if (!(fromType isSubtypeOf isomorphicType.force())) {
                return "Cannot convert $fromType to ${this@EVMIsomorphicValueType}".asError()
            }
            return i.accept(ToEVMValueTypeContextVisitor(fromType, this))
        }

        override fun <O, SRC, DST> converterTo(
            toType: CVLType.PureCVLType,
            i: FromVMContextDispatcher<O, SRC, DST>,
        ): CollectingResult<ITypeDescriptorConverter<O, SRC, DST>, String> {
            if (!(isomorphicType.force() isSubtypeOf toType)) {
                return "${this@EVMIsomorphicValueType} is not convertible to $toType".asError()
            }
            return i.accept(object : FromVMContext.Visitor {
                override fun returnValueContext(): CollectingResult<ITypeDescriptorConverter<IProgramOutput, IExternalOutputBuffer, CallReturnTarget>, Nothing> {
                    return DataLayout.Terminal(this@EVMIsomorphicValueType).liftDecoder()
                }

                override fun hookValueContext(): CollectingResult<ITypeDescriptorConverter<ISimpleOutput, IHookParameter, ITACSymbolVar>, Nothing> {
                    return object : ITypeDescriptorConverter<ISimpleOutput, IHookParameter, ITACSymbolVar> {
                        override fun convertTo(
                            fromVar: IHookParameter, toVar: ITACSymbolVar, cb: (ITACCmd) -> ITACCmd
                        ): ISimpleOutput {
                            return theSemantics.moveToHook(toVar, toType, fromVar, this@EVMIsomorphicValueType, cb)
                        }
                    }.lift()
                }

                override fun stateValueContext(): CollectingResult<ITypeDescriptorConverter<ISimpleOutput, ITACSymbolVar, ITACSymbolVar>, String> {
                    return object : ITypeDescriptorConverter<ISimpleOutput, ITACSymbolVar, ITACSymbolVar> {
                        override fun convertTo(
                            fromVar: ITACSymbolVar, toVar: ITACSymbolVar, cb: (ITACCmd) -> ITACCmd
                        ): ISimpleOutput {
                            return theSemantics.moveFromStorage(toVar, toType, fromVar, this@EVMIsomorphicValueType, cb)
                        }
                    }.lift()
                }

                override fun internalSummaryArgBindingContext(): CollectingResult<ITypeDescriptorConverter<IProgramOutput, IInternalSummaryInput, ICVLDataOutput>, Nothing> {
                    return object : ITypeDescriptorConverter<IProgramOutput, IInternalSummaryInput, ICVLDataOutput> {
                        override fun convertTo(
                            fromVar: IInternalSummaryInput,
                            toVar: ICVLDataOutput,
                            cb: (ITACCmd) -> ITACCmd,
                        ): IProgramOutput {
                            return theSemantics.movePrimitiveFromInternalArg(
                                toVar,
                                toType,
                                fromVar as EVMInternalSummaryInput,
                                this@EVMIsomorphicValueType,
                                cb
                            )
                        }
                    }.lift()
                }
            })
        }
    }

    /**
     * @author John Toman
     *
     * This interface establishes everything that the VM Type Descriptor conversions need to complete their conversions
     * once they decide that the conversions are feasible.
     *
     * Note, this interface does not use real code generation types, but interface types (for example [ITACCmd] or
     * [ITACSymbolVar]). This is because type checking still needs to know whether or not we can do the conversions, but
     * is in a separate module and thus does not have access to the real types.
     */
    interface ConverstionSemantics {
        fun moveToHook(dest: ITACSymbolVar, destType: CVLType.PureCVLType, src: IHookParameter, srcType: VMValueTypeDescriptor, cb: (ITACCmd) -> ITACCmd) : ISimpleOutput

        fun moveFromStorage(dest: ITACSymbolVar, destType: CVLType.PureCVLType, src: ITACSymbolVar, srcType: VMValueTypeDescriptor, cb: (ITACCmd) -> ITACCmd) : ISimpleOutput

        fun movePrimitiveToInternalReturnLocation(
            dest: EVMInternalSummaryReturn,
            destEncoding: Tag.CVLArray.UserArray.ElementEncoding,
            src: ICVLDataInput,
            srcType: CVLType.PureCVLType,
            cb: (ITACCmd) -> ITACCmd
        ) : IProgramOutput

        fun movePrimitiveFromInternalArg(
            dest: ICVLDataOutput,
            destType: CVLType.PureCVLType,
            src: EVMInternalSummaryInput,
            srcType: VMValueTypeDescriptor,
            cb: (ITACCmd) -> ITACCmd,
        ): IProgramOutput

        fun moveHashedArray(fromVar: IHookParameter, toVar: ITACSymbolVar, cb: (ITACCmd) -> ITACCmd): ISimpleOutput

        fun moveStructToInternalReturn(
            dest: EVMInternalSummaryReturn,
            src: ICVLDataInput,
            fields: List<StructFieldConverter<ICVLDataInput, IInternalSummaryReturn>>,
            cb: (ITACCmd) -> ITACCmd,
        ): IProgramOutput

        fun encodeWithDataLayout(
            fromVar: ICVLDataInput,
            toVar: IExternalInputBuffer,
            layout: EncoderLayout,
            cb: (ITACCmd) -> ITACCmd
        ) : IExternalEncodeOutput

        fun decodeWithDataLayout(
            fromVar: IExternalOutputBuffer,
            toVar: ICVLDataOutput,
            encodingLayout: DecoderLayout,
            cb: (ITACCmd) -> ITACCmd
        ): IProgramOutput

        fun moveArrayFromInternalArg(
            fromVar: EVMInternalSummaryInput,
            toVar: ICVLDataOutput,
            eSize: Int,
            cb: (ITACCmd) -> ITACCmd,
            elemConversion: InternalElementConverter<IInternalSummaryInput, ICVLDataOutput>
        ) : IProgramOutput

        fun moveArrayToInternalReturn(
            toVar: EVMInternalSummaryReturn,
            fromVar: ICVLDataInput,
            eSize: Int,
            cb: (ITACCmd) -> ITACCmd,
            elemConverter: InternalElementConverter<ICVLDataInput, IInternalSummaryReturn>
        ) : IProgramOutput

        fun moveStructFromInternalArg(
            fromVar: EVMInternalSummaryInput,
            toVar: ICVLDataOutput,
            fieldNameToOrdinal: Map<String, Int>,
            fields: List<StructFieldConverter<IInternalSummaryInput, ICVLDataOutput>>,
            cb: (ITACCmd) -> ITACCmd
        ) : IProgramOutput

        fun moveStaticArrayToInternalReturn(
            fromVar: ICVLDataInput,
            toVar: EVMInternalSummaryReturn,
            numFields: BigInteger,
            elementConverter: ITypeDescriptorConverter<IConverterOutput, ICVLDataInput, IInternalSummaryReturn>,
            cvlElementType: CVLType.PureCVLType,
            cb: (ITACCmd) -> ITACCmd
        ) : IProgramOutput

        fun moveStaticArrayFromInternalArg(
            fromVar: EVMInternalSummaryInput,
            toVar: ICVLDataOutput,
            elementType: CVLType.PureCVLType,
            numElements: BigInteger,
            elementConverter: ITypeDescriptorConverter<IConverterOutput, IInternalSummaryInput, ICVLDataOutput>,
            cb: (ITACCmd) -> ITACCmd
        ) : IProgramOutput

        fun movePrimitiveToMappingKey(fromVar: ITACSymbolVar, toVar: IStorageMapping, encoding: Tag.CVLArray.UserArray.ElementEncoding): IMappingKeyOutput
        fun reverseHashBlobStorageKey(fromVar: ITACSymbolVar, toVar: IStorageMapping): IMappingKeyOutput
        fun movePackedBytesToStorageKey(fromVar: ITACSymbolVar, toVar: IStorageMapping): IMappingKeyOutput
        fun moveCalldataArgToInternalArg(fromVar: EVMInternalSummaryInput, toVar: ICVLDataOutput, layout: DecoderLayout) : IProgramOutput

        fun constrainNumericOutput(currConverter: IConverterOutput, cvlVar: ITACSymbolVar, srcVMType: EVMNumericType) : ISimpleOutput
        fun movePrimitiveToPrimitive(
            fromType: CVLType.PureCVLType,
            fromVar: ITACSymbolVar,
            toType: EVMValueType,
            toVar: ITACSymbolVar
        ): ISimpleOutput
    }

    protected fun <SRC> composeWithNumericConstraint(toWrap: ITypeDescriptorConverter<ISimpleOutput, SRC, ITACSymbolVar>, boundedType: EVMNumericType) : ITypeDescriptorConverter<ISimpleOutput, SRC, ITACSymbolVar> {
        return object : ITypeDescriptorConverter<ISimpleOutput, SRC, ITACSymbolVar> {
            override fun convertTo(fromVar: SRC, toVar: ITACSymbolVar, cb: (ITACCmd) -> ITACCmd): ISimpleOutput {
                return theSemantics.constrainNumericOutput(
                    toWrap.convertTo(fromVar, toVar, cb),
                    toVar,
                    srcVMType = boundedType
                )
            }

        }
    }

    fun isDynamicType() = (this is EVMReferenceType && this.isDynamicSize())

    abstract fun getMinimumEncodingSize(): BigInteger

    @Serializable
    data class EVMContractTypeDescriptor(val contractName: String) : EVMValueType(), VMTypeWithCorrespondence {
        override fun prettyPrint(): String {
            return "Contract $contractName"
        }

        override val expectedBufferEncoding: Tag.CVLArray.UserArray.ElementEncoding
            get() = Tag.CVLArray.UserArray.ElementEncoding.Unsigned

        override fun canonicalString(ctxt: EVMPrintingContext): String {
            if (ctxt.isLibrary) {
                return contractName
            }
            return "address"
        }

        override fun mergeWith(other: VMTypeDescriptor): CollectingResult<VMTypeDescriptor, String> {
            return when (other) {
                this,
                address -> this.lift()
                else -> "Cannot merge ${this.prettyPrint()} with ${other.prettyPrint()}".asError()
            }
        }

        override fun toTag() = Tag.Bit256

        override val bitwidth = 160
        override fun correspondenceTypeToConvertTo(fromVMContext: FromVMContext): CollectingResult.Result<VMTypeWithCorrespondence.NeedsConversionCheck> {
            return VMTypeWithCorrespondence.NeedsConversionCheck(CVLType.PureCVLType.Primitive.AccountIdentifier).lift()
        }

        override fun correspondenceTypeToConvertFrom(toVMContext: ToVMContext): CollectingResult.Result<VMTypeWithCorrespondence.NeedsConversionCheck> {
            return VMTypeWithCorrespondence.NeedsConversionCheck(CVLType.PureCVLType.Primitive.AccountIdentifier).lift()
        }

        override fun <O, SRC, DST> converterFrom(
            fromType: CVLType.PureCVLType,
            i: ToVMContextDispatcher<O, SRC, DST>,
        ): CollectingResult<ITypeDescriptorConverter<O, SRC, DST>, String> {
            if(fromType !is CVLType.PureCVLType.Primitive.AccountIdentifier &&
                (fromType !is CVLType.PureCVLType.Primitive.CodeContract)) {
                return "Cannot convert $fromType to a contract with type ${this.contractName}".asError()
            }
            // Note that we allow conversion between contracts with different [this.contractName] because
            // of our subtyping rules:
            //  * address is convertible to CodeContract(foo) for all foo
            //  * CodeContract(bar) is a subtype of address for all bar
            //  * Therefore CodeContract(bar) should be convertible to CodeContract(bar) for all foo and bar
            // Out another way, since `address a = fooContractAlias; acceptsBarContract(a);` passes
            // typechecking, `acceptBarContract(fooContractAlias);` should too.
            return i.accept(object : ToVMContext.Visitor {
                override fun internalSummaryReturnContext(): CollectingResult<ITypeDescriptorConverter<IProgramOutput, ICVLDataInput, IInternalSummaryReturn>, String> {
                    return object : ITypeDescriptorConverter<IProgramOutput, ICVLDataInput, IInternalSummaryReturn> {
                        override fun convertTo(
                            fromVar: ICVLDataInput,
                            toVar: IInternalSummaryReturn,
                            cb: (ITACCmd) -> ITACCmd
                        ): IProgramOutput {
                            return theSemantics.movePrimitiveToInternalReturnLocation(
                                src = fromVar,
                                dest = toVar as EVMInternalSummaryReturn,
                                destEncoding = this@EVMContractTypeDescriptor.expectedBufferEncoding,
                                srcType = fromType,
                                cb = cb
                            )
                        }

                    }.lift()
                }

                override fun directPassingContext(): CollectingResult<ITypeDescriptorConverter<ISimpleOutput, ITACSymbolVar, ITACSymbolVar>, String> {
                    return object : ITypeDescriptorConverter<ISimpleOutput, ITACSymbolVar, ITACSymbolVar> {
                        override fun convertTo(
                            fromVar: ITACSymbolVar,
                            toVar: ITACSymbolVar,
                            cb: (ITACCmd) -> ITACCmd
                        ): ISimpleOutput {
                            return theSemantics.movePrimitiveToPrimitive(
                                fromVar = fromVar,
                                toVar = toVar,
                                fromType = fromType,
                                toType = this@EVMContractTypeDescriptor
                            )
                        }

                    }.lift()
                }

                override fun storageMappingKeyContext(): CollectingResult<ITypeDescriptorConverter<IMappingKeyOutput, ITACSymbolVar, IStorageMapping>, String> {
                    return object : ITypeDescriptorConverter<IMappingKeyOutput, ITACSymbolVar, IStorageMapping> {
                        override fun convertTo(
                            fromVar: ITACSymbolVar,
                            toVar: IStorageMapping,
                            cb: (ITACCmd) -> ITACCmd
                        ): IMappingKeyOutput {
                            return theSemantics.movePrimitiveToMappingKey(fromVar, toVar, expectedBufferEncoding)
                        }

                    }.lift()
                }

                override fun argumentPassingContext(): CollectingResult<ITypeDescriptorConverter<IExternalEncodeOutput, ICVLDataInput, IExternalInputBuffer>, String> {
                    return DataLayout.Terminal(this@EVMContractTypeDescriptor).liftEncoder()
                }

            })
        }

        override fun <O, SRC, DST> converterTo(
            toType: CVLType.PureCVLType,
            i: FromVMContextDispatcher<O, SRC, DST>
        ): CollectingResult<ITypeDescriptorConverter<O, SRC, DST>, String> {
            if(toType !is CVLType.PureCVLType.Primitive.AccountIdentifier && toType !is CVLType.PureCVLType.Primitive.CodeContract) {
                return "Cannot convert ${this.contractName} to type $toType".asError()
            }
            return i.accept(object : FromVMContext.Visitor {
                override fun returnValueContext(): CollectingResult<ITypeDescriptorConverter<IProgramOutput, IExternalOutputBuffer, CallReturnTarget>, String> {
                    return DataLayout.Terminal(this@EVMContractTypeDescriptor).liftDecoder()
                }

                override fun hookValueContext(): CollectingResult<ITypeDescriptorConverter<ISimpleOutput, IHookParameter, ITACSymbolVar>, String> {
                    return object : ITypeDescriptorConverter<ISimpleOutput, IHookParameter, ITACSymbolVar> {
                        override fun convertTo(
                            fromVar: IHookParameter,
                            toVar: ITACSymbolVar,
                            cb: (ITACCmd) -> ITACCmd
                        ): ISimpleOutput {
                            return theSemantics.moveToHook(
                                dest = toVar,
                                srcType = this@EVMContractTypeDescriptor,
                                destType = toType,
                                src = fromVar,
                                cb = cb
                            )
                        }

                    }.lift()
                }

                override fun stateValueContext(): CollectingResult<ITypeDescriptorConverter<ISimpleOutput, ITACSymbolVar, ITACSymbolVar>, String> {
                    return object : ITypeDescriptorConverter<ISimpleOutput, ITACSymbolVar, ITACSymbolVar> {
                        override fun convertTo(
                            fromVar: ITACSymbolVar, toVar: ITACSymbolVar, cb: (ITACCmd) -> ITACCmd
                        ): ISimpleOutput {
                            return theSemantics.moveFromStorage(
                                dest = toVar,
                                srcType = this@EVMContractTypeDescriptor,
                                destType = toType,
                                src = fromVar,
                                cb = cb
                            )
                        }
                    }.lift()
                }

                override fun internalSummaryArgBindingContext(): CollectingResult<ITypeDescriptorConverter<IProgramOutput, IInternalSummaryInput, ICVLDataOutput>, String> {
                    return object : ITypeDescriptorConverter<IProgramOutput, IInternalSummaryInput, ICVLDataOutput> {
                        override fun convertTo(
                            fromVar: IInternalSummaryInput,
                            toVar: ICVLDataOutput,
                            cb: (ITACCmd) -> ITACCmd
                        ): IProgramOutput {
                            return theSemantics.movePrimitiveFromInternalArg(
                                dest = toVar,
                                src = fromVar as EVMInternalSummaryInput,
                                cb = cb,
                                destType = toType,
                                srcType = this@EVMContractTypeDescriptor
                            )
                        }
                    }.lift()
                }

            })
        }
    }

    @Suppress("ClassName")
    @Serializable
    object address : EVMIsomorphicValueType() {
        override fun hashCode() = hashObject(this)
        private fun readResolve(): Any = address

        override val bitwidth: Int get() = 160
        override fun canonicalString(ctxt: EVMPrintingContext): String {
            return toString()
        }

        override val expectedBufferEncoding: Tag.CVLArray.UserArray.ElementEncoding
            get() = Tag.CVLArray.UserArray.ElementEncoding.Unsigned

        override fun mergeWith(other: VMTypeDescriptor): CollectingResult<VMTypeDescriptor, String> {
            return when (other) {
                this,
                is EVMContractTypeDescriptor -> other.lift()
                else -> "Cannot merge ${this.prettyPrint()} with ${other.prettyPrint()}".asError()
            }
        }

        override fun toString(): String = "address"

        override fun toTag(): Tag = Tag.Bit256

        override val isomorphicType: CollectingResult.Result<CVLType.PureCVLType>
            get() = CVLType.PureCVLType.Primitive.AccountIdentifier.lift()
    }

    sealed interface EVMNumericType : VMNumericValueTypeDescriptor

    @Serializable
    data class UIntK(override val bitwidth: Int): EVMIsomorphicValueType(), VMUnsignedNumericValueTypeDescriptor, EVMNumericType {
        override val expectedBufferEncoding: Tag.CVLArray.UserArray.ElementEncoding
            get() = Tag.CVLArray.UserArray.ElementEncoding.Unsigned

        override fun canonicalString(ctxt: EVMPrintingContext): String {
            return toString()
        }

        override fun toString(): String = "uint$bitwidth"

        override val minValue: BigInteger
            get() = BigInteger.ZERO
        override val maxValue: BigInteger
            get() = SignUtilities.maxUnsignedValueOfBitwidth(bitwidth)
        override val isBigEndian: Boolean
            get() = true

        override fun toTag(): Tag = Tag.Bit256

        override val isomorphicType: CollectingResult.Result<CVLType.PureCVLType>
            get() = CVLType.PureCVLType.Primitive.UIntK(bitwidth).lift()

        override fun <O, SRC, DST> converterTo(
            toType: CVLType.PureCVLType,
            i: FromVMContextDispatcher<O, SRC, DST>
        ): CollectingResult<ITypeDescriptorConverter<O, SRC, DST>, String> {
            fun <O2, SRC2, DST2> superCall(dst: (FromVMContext.Visitor) -> CollectingResult<ITypeDescriptorConverter<O2, SRC2, DST2>, String>) : CollectingResult<ITypeDescriptorConverter<O2, SRC2, DST2>, String> {
                return super<EVMIsomorphicValueType>.converterTo(toType, object : FromVMContextDispatcher<O2, SRC2, DST2> {
                    override fun accept(visitor: FromVMContext.Visitor): CollectingResult<ITypeDescriptorConverter<O2, SRC2, DST2>, String> {
                        return dst(visitor)
                    }
                })
            }
            return i.accept(object : FromVMContext.Visitor {
                override fun returnValueContext(): CollectingResult<ITypeDescriptorConverter<IProgramOutput, IExternalOutputBuffer, CallReturnTarget>, String> {
                    return superCall(FromVMContext.Visitor::returnValueContext)
                }

                override fun hookValueContext(): CollectingResult<ITypeDescriptorConverter<ISimpleOutput, IHookParameter, ITACSymbolVar>, String> {
                    return superCall(FromVMContext.Visitor::hookValueContext).map {
                        composeWithNumericConstraint(
                            it,
                            this@UIntK
                        )
                    }
                }

                override fun stateValueContext(): CollectingResult<ITypeDescriptorConverter<ISimpleOutput, ITACSymbolVar, ITACSymbolVar>, String> {
                    return superCall(FromVMContext.Visitor::stateValueContext)
                }

                override fun internalSummaryArgBindingContext(): CollectingResult<ITypeDescriptorConverter<IProgramOutput, IInternalSummaryInput, ICVLDataOutput>, String> {
                    return superCall(FromVMContext.Visitor::internalSummaryArgBindingContext)
                }
            })
        }
    }

    @Serializable
    data class IntK(override val bitwidth: Int) : EVMIsomorphicValueType(), VMSignedNumericValueTypeDescriptor, EVMNumericType {
        override val isomorphicType: CollectingResult.Result<CVLType.PureCVLType>
            get() = CVLType.PureCVLType.Primitive.IntK(bitwidth).lift()
        override val expectedBufferEncoding: Tag.CVLArray.UserArray.ElementEncoding
            get() = Tag.CVLArray.UserArray.ElementEncoding.Signed

        override fun canonicalString(ctxt: EVMPrintingContext): String {
            return toString()
        }

        override fun toString(): String = "int$bitwidth"

        override val minValue: BigInteger
            get() = SignUtilities.minSignedValueOfBitwidth(bitwidth)
        override val maxValue: BigInteger
            get() = SignUtilities.maxSignedValueOfBitwidth(bitwidth)
        override val isBigEndian: Boolean
            get() = true

        override fun toTag(): Tag = Tag.Bit256

        override fun <O, SRC, DST> converterTo(
            toType: CVLType.PureCVLType,
            i: FromVMContextDispatcher<O, SRC, DST>
        ): CollectingResult<ITypeDescriptorConverter<O, SRC, DST>, String> {
            fun <O2, SRC2, DST2> superCall(dst: (FromVMContext.Visitor) -> CollectingResult<ITypeDescriptorConverter<O2, SRC2, DST2>, String>) : CollectingResult<ITypeDescriptorConverter<O2, SRC2, DST2>, String> {
                return super<EVMIsomorphicValueType>.converterTo(toType, object : FromVMContextDispatcher<O2, SRC2, DST2> {
                    override fun accept(visitor: FromVMContext.Visitor): CollectingResult<ITypeDescriptorConverter<O2, SRC2, DST2>, String> {
                        return dst(visitor)
                    }
                })
            }
            return i.accept(object : FromVMContext.Visitor {
                override fun returnValueContext(): CollectingResult<ITypeDescriptorConverter<IProgramOutput, IExternalOutputBuffer, CallReturnTarget>, String> {
                    return superCall(FromVMContext.Visitor::returnValueContext)
                }

                override fun hookValueContext(): CollectingResult<ITypeDescriptorConverter<ISimpleOutput, IHookParameter, ITACSymbolVar>, String> {
                    return superCall(FromVMContext.Visitor::hookValueContext).map {
                        composeWithNumericConstraint(it, this@IntK)
                    }
                }

                override fun stateValueContext(): CollectingResult<ITypeDescriptorConverter<ISimpleOutput, ITACSymbolVar, ITACSymbolVar>, String> {
                    return superCall(FromVMContext.Visitor::stateValueContext)
                }

                override fun internalSummaryArgBindingContext(): CollectingResult<ITypeDescriptorConverter<IProgramOutput, IInternalSummaryInput, ICVLDataOutput>, String> {
                    return superCall(FromVMContext.Visitor::internalSummaryArgBindingContext)
                }

            })
        }
    }

    @Serializable
    data class BytesK(val bytewidth: Int): EVMIsomorphicValueType() {
        override val bitwidth: Int get() = bytewidth * 8
        override val isomorphicType: CollectingResult.Result<CVLType.PureCVLType>
            get() = CVLType.PureCVLType.Primitive.BytesK(bytewidth).lift()
        override val expectedBufferEncoding: Tag.CVLArray.UserArray.ElementEncoding
            get() = Tag.CVLArray.UserArray.ElementEncoding.Unsigned

        override fun canonicalString(ctxt: EVMPrintingContext): String {
            return toString()
        }

        override fun toString(): String = "bytes$bytewidth"

        override fun toTag(): Tag = Tag.Bit256
    }

    @Serializable
    data class FunctionDescriptor(val name: String): EVMTypeDescriptor() {
        override fun prettyPrint() = "function $name"

        override fun canonicalString(ctxt: EVMPrintingContext) = prettyPrint()

        override fun mergeWith(other: VMTypeDescriptor): CollectingResult<VMTypeDescriptor, String> {
            return "Cannot merge function type $this with $other".asError()
        }

        override fun sizeAsEncodedMember(): BigInteger {
            return EVM_WORD_SIZE
        }

        override fun getMinimumEncodingSize(): BigInteger {
            return EVM_WORD_SIZE
        }

        override fun <O, SRC, DST> converterFrom(
            fromType: CVLType.PureCVLType, i: ToVMContextDispatcher<O, SRC, DST>
        ): CollectingResult<ITypeDescriptorConverter<O, SRC, DST>, String> {
            return "There's currently no support for converting to function types".asError()
        }

        override fun <O, SRC, DST> converterTo(
            toType: CVLType.PureCVLType, i: FromVMContextDispatcher<O, SRC, DST>
        ): CollectingResult<ITypeDescriptorConverter<O, SRC, DST>, String> {
            return "There's currently no support for converting from function types".asError()
        }
    }

    // maybe VM Numeric Type Descriptor??
    /**
     * @property name The name of the user-defined-value-type, including the defining contract (if it's not a top-level definition)
     */
    @Serializable
    data class UserDefinedValueType(val canonicalId: String, val name: String, val baseType: EVMIsomorphicValueType) : EVMIsomorphicValueType() {

        override val bitwidth: Int
            get() = baseType.bitwidth
        override fun canonicalString(ctxt: EVMPrintingContext) = baseType.canonicalString(ctxt)

        override fun toString(): String = name

        override fun mergeWith(other: VMTypeDescriptor): CollectingResult<VMTypeDescriptor, String> {
            if (other !is UserDefinedValueType) {
                return "Cannot merge $this with $other, they are different types".asError()
            }
            if (this.name != other.name) {
                return "Cannot merge $this with $other: their names are ambiguous".asError()
            }
            return baseType.mergeWith(other.baseType).bind {
                check(it is EVMIsomorphicValueType)
                UserDefinedValueType(canonicalId, name, it).lift()
            }
        }

        override val isomorphicType: CollectingResult.Result<CVLType.PureCVLType>
            get() = CVLType.PureCVLType.UserDefinedValueType(name, baseType.isomorphicType.force() as CVLType.PureCVLType.Primitive).lift()
        override val expectedBufferEncoding: Tag.CVLArray.UserArray.ElementEncoding
            get() = baseType.expectedBufferEncoding

        override fun sizeAsEncodedMember(): BigInteger {
            return baseType.sizeAsEncodedMember()
        }

        override fun getMinimumEncodingSize(): BigInteger {
            return baseType.getMinimumEncodingSize()
        }

        override fun toTag(): Tag {
            return baseType.toTag()
        }
    }

    interface EVMReferenceType : VMReferenceTypeDescriptor {
        override fun equals(other: Any?): Boolean
        override fun hashCode(): Int


        /**
         * Expects this converter to also support size queries. If so, we wrap this converter in
         * [spec.cvlast.typedescriptors.TerminalAction.Composed], and package this into
         * a [spec.cvlast.abi.DataLayout.Terminal]
         */
        fun <O, SRC, DST> ITypeDescriptorConverter<O, SRC, DST>.rewrapConverter(): CollectingResult<ConverterLayout<O, SRC, DST, *>, String> {
            return if(this is SupportsSizeQuery) {
                /**
                 * As an optimization, instead of `Composed(ConverterOfValueType)`
                 * just plumb through the the value type directly (as [spec.cvlast.typedescriptors.TerminalAction.ValueType]
                 * is valid for any converter context)
                 */
                if(this is SupportsDataLayoutQuery && this.dataLayout.let { layout ->
                        layout is DataLayout.Terminal && layout.t is TerminalAction.ValueType
                    }) {
                    return DataLayout.Terminal<TerminalAction<O, SRC, DST, *>>((this.dataLayout as DataLayout.Terminal).t as TerminalAction.ValueType).lift()
                }
                val tmp = TerminalAction.Composed(this)
                DataLayout.Terminal(tmp).lift()
            } else {
                "Invalid converter, this is an internal error, please report to Certora".asError()
            }
        }

        override val location: EVMLocationSpecifier?

        fun convertibleInExternalCallContext() =
            if (location == null || location == EVMLocationSpecifier.memory || location == EVMLocationSpecifier.calldata) {
                ok
            } else {
                "A parameter of type $this which is located in $location cannot be used as input to an external function summary. Only `memory` and `calldata` locations are supported in the context of external function summaries".asError()
            }

        fun convertibleInInternalMemoryContext() = if(location == EVMLocationSpecifier.memory) {
            ok
        } else {
            "A parameter of type $this which is located in $location cannot be used as input to an internal function summary. Only `memory` location is supported in the context of internal function summaries".asError()
        }

        fun checkCoherence(containedType: EVMTypeDescriptor) {
            if(containedType is EVMReferenceType) {
                check(location == containedType.location) {
                    "Different types for containing parent reference type $location in ($this) and child type $containedType@${containedType.location}"
                }
            }
        }

        fun tryConvertCalldataInternalArg(toType: CVLType.PureCVLType) : CollectingResult<ITypeDescriptorConverter<IProgramOutput, IInternalSummaryInput, ICVLDataOutput>, String> {
            require(location == EVMLocationSpecifier.calldata) {
                "Invalid attempt to convert, must be a calldata pointer"
            }
            val err by lazy {
                "Cannot convert calldata pointer of type ${this@EVMReferenceType} to $toType in the context of internal summary arguments".asError()
            }
            return this.converterTo(toType, FromVMContext.ReturnValue.getVisitor()).bindEither(errorCallback = {
                err
            }, resultCallback = {
                /**
                 * XXX(jtoman): this should *probably* use the [SupportsDataLayoutQuery] type instead?
                 */
                (it as? DataLayoutDecoder)?.encodingLayout?.let { layout ->
                    val unwrapped = (layout as? DataLayout.DynamicPointer)?.next ?: layout
                    object : ITypeDescriptorConverter<IProgramOutput, IInternalSummaryInput, ICVLDataOutput> {
                        override fun convertTo(
                            fromVar: IInternalSummaryInput,
                            toVar: ICVLDataOutput,
                            cb: (ITACCmd) -> ITACCmd
                        ): IProgramOutput {
                            return theSemantics.moveCalldataArgToInternalArg(
                                toVar = toVar,
                                fromVar = fromVar as EVMInternalSummaryInput,
                                layout = unwrapped
                            )
                        }
                    }.lift()
                 } ?: err
            })
        }
    }

    @Suppress("ClassName")
    @Serializable
    object bool : EVMIsomorphicValueType() {
        override val expectedBufferEncoding: Tag.CVLArray.UserArray.ElementEncoding
            get() = Tag.CVLArray.UserArray.ElementEncoding.Boolean

        override fun canonicalString(ctxt: EVMPrintingContext): String {
            return toString()
        }

        override fun hashCode() = hashObject(this)
        private fun readResolve(): Any = bool
        override fun toString(): String = "bool"

        override fun toTag(): Tag {
            return Tag.Bool
        }

        override val bitwidth: Int
            get() = 1

        override val isomorphicType: CollectingResult.Result<CVLType.PureCVLType>
            get() = CVLType.PureCVLType.Primitive.Bool.lift()

    }

    interface EVMArrayLikeType : EVMReferenceType, VMArrayTypeDescriptor

    // XXX(jtoman): Figure out what this was for and get rid of it
    interface EVMValueArrayConverter: EVMArrayLikeType

    protected fun mergeLocations(l1: EVMReferenceType, l2: EVMReferenceType, f: (EVMLocationSpecifier?) -> EVMTypeDescriptor) : CollectingResult<VMTypeDescriptor, String> {
        return if(l1.location == null) {
            f(l2.location).lift()
        } else if(l2.location == null) {
            check(l1.location != null)
            f(l1.location).lift()
        } else {
            if(l1.location != l2.location) {
                "Cannot merge $l1 and $l2: their locations are different (${l1.location} vs ${l2.location})".asError()
            } else {
                f(l1.location).lift()
            }
        }
    }

    @Serializable
    data class PackedBytes(override val location: EVMLocationSpecifier?): PackedBytes1Array() {
        override fun prettyPrint() = "bytes"
        override fun toString() = "bytes"

        override fun mergeWith(other: VMTypeDescriptor): CollectingResult<VMTypeDescriptor, String> {
            if (other !is PackedBytes) {
                return "Cannot merge $this and $other, they are different types".asError()
            }
            return mergeLocations(this, other) {
                PackedBytes(location = it)
            }
        }
        override fun correspondenceTypeToConvertTo(fromVMContext: FromVMContext): CollectingResult.Result<VMTypeWithCorrespondence.NeedsConversionCheck> =
            CVLType.PureCVLType.DynamicArray.PackedBytes.needsConversionCheck()

        override fun correspondenceTypeToConvertFrom(toVMContext: ToVMContext): CollectingResult.Result<VMTypeWithCorrespondence.NeedsConversionCheck> =
            CVLType.PureCVLType.DynamicArray.PackedBytes.needsConversionCheck()

        override fun <O, SRC, DST> converterFrom(
            fromType: CVLType.PureCVLType, i: ToVMContextDispatcher<O, SRC, DST>
        ): CollectingResult<ITypeDescriptorConverter<O, SRC, DST>, String> {
            if (fromType !is CVLType.PureCVLType.DynamicArray.PackedBytes && fromType !is CVLType.PureCVLType.Primitive.HashBlob) {
                return "Cannot convert $fromType to ${this@PackedBytes}".asError()
            }
            return super.converterFrom(fromType, i)
        }

        override fun <SRC, DST, O> converterTo(
            toType: CVLType.PureCVLType,
            i: FromVMContextDispatcher<SRC, DST, O>
        ): CollectingResult<ITypeDescriptorConverter<SRC, DST, O>, String> {
            if (toType !is CVLType.PureCVLType.Primitive.HashBlob && toType !is CVLType.PureCVLType.DynamicArray.PackedBytes) {
                return "Cannot convert ${this@PackedBytes} to $toType".asError()
            }
            return super.converterTo(toType, i)
        }

        override fun hashCode() = hash { it + location}
    }

    @Serializable
    data class StringType(override val location: EVMLocationSpecifier?): PackedBytes1Array(){
        override fun prettyPrint() = "string"

        override fun mergeWith(other: VMTypeDescriptor): CollectingResult<VMTypeDescriptor, String> {
            if (other !is StringType) {
                return "Cannot merge $this and $other, they are different types".asError()
            }
            return mergeLocations(this, other) {
                StringType(location = it)
            }
        }

        override fun correspondenceTypeToConvertTo(fromVMContext: FromVMContext): CollectingResult.Result<VMTypeWithCorrespondence.NeedsConversionCheck> =
            CVLType.PureCVLType.DynamicArray.StringType.needsConversionCheck()

        override fun correspondenceTypeToConvertFrom(toVMContext: ToVMContext): CollectingResult.Result<VMTypeWithCorrespondence.NeedsConversionCheck> =
            CVLType.PureCVLType.DynamicArray.StringType.needsConversionCheck()

        override fun <O, SRC, DST> converterFrom(
            fromType: CVLType.PureCVLType, i: ToVMContextDispatcher<O, SRC, DST>
        ): CollectingResult<ITypeDescriptorConverter<O, SRC, DST>, String> {
            if (fromType !is CVLType.PureCVLType.DynamicArray.StringType && fromType !is CVLType.PureCVLType.Primitive.HashBlob) {
                return "Cannot convert $fromType to ${this@StringType}".asError()
            }
            return super<PackedBytes1Array>.converterFrom(fromType, i)
        }

        override fun <SRC, DST, O> converterTo(
            toType: CVLType.PureCVLType,
            i: FromVMContextDispatcher<SRC, DST, O>
        ): CollectingResult<ITypeDescriptorConverter<SRC, DST, O>, String> {
            if (toType !is CVLType.PureCVLType.Primitive.HashBlob && toType !is CVLType.PureCVLType.DynamicArray.StringType) {
                return "Cannot convert ${this@StringType} to $toType".asError()
            }
            return super<PackedBytes1Array>.converterTo(toType, i)
        }

        override fun hashCode() = hash { it + location}
    }

    /**
     * As CVL types, we encode EVM's string and bytes types in the same manner - as a dynamic array of BytesK(1). However,
     * since they are different primitive type in Solidity, we need to differentiate between them in order to create
     * correct ABI signatures with these types as arguments.
     */
    @Serializable
    sealed class PackedBytes1Array:
        EVMValueArrayConverter, EVMTypeDescriptor(), VMTypeWithCorrespondence {
        /**
         * Because [spec.cvlast.abi.DataLayout.SequenceElement.PackedBytes1] is usable in any context,
         * use this function to get a "polymorphic" representation of the encoding data, i.e.,
         * the user can ask for whatever [T] they want, the layout of the packed bytes array doesn't
         * actually change.
         */
        private fun <T> encodingData() : DataLayout<T> = DataLayout.DynamicPointer<T>(
            LengthTaggedTuple(DataLayout.SequenceOf(DataLayout.SequenceElement.PackedBytes1(isString = this is StringType)))
        )

        override val elementSize: BigInteger get() = BigInteger.ONE
        override val elementType: VMTypeDescriptor get() = BytesK(1)

        override fun canonicalString(ctxt: EVMPrintingContext): String {
            return if (this.location == EVMLocationSpecifier.storage && ctxt.isLibrary && !ctxt.isNested) {
                this.prettyPrint() + " storage"
            } else {
                this.prettyPrint()
            }
        }

        override fun sizeAsEncodedMember(): BigInteger {
            return EVM_WORD_SIZE
        }

        /*
           One for the length, one for the offset
         */
        override fun getMinimumEncodingSize(): BigInteger {
            if(this.location == EVMLocationSpecifier.storage) {
                return EVM_WORD_SIZE
            }
            return EVM_WORD_SIZE * BigInteger.TWO
        }

        override fun <O, SRC, DST> converterFrom(
            fromType: CVLType.PureCVLType,
            i: ToVMContextDispatcher<O, SRC, DST>
        ): CollectingResult<ITypeDescriptorConverter<O, SRC, DST>, String> {
            return i.accept(object : ToVMContext.Visitor {
                override fun internalSummaryReturnContext(): CollectingResult<ITypeDescriptorConverter<IProgramOutput, ICVLDataInput, IInternalSummaryReturn>, String> {
                    if(fromType is CVLType.PureCVLType.Primitive.HashBlob) {
                        return "Cannot convert hashblob in context of internal summary returns".asError()
                    }
                    check(fromType is CVLType.PureCVLType.DynamicArray.Bytes1Array)
                    return convertibleInInternalMemoryContext().map {
                        object : ITypeDescriptorConverter<IProgramOutput, ICVLDataInput, IInternalSummaryReturn> {
                            override fun convertTo(
                                fromVar: ICVLDataInput,
                                toVar: IInternalSummaryReturn,
                                cb: (ITACCmd) -> ITACCmd
                            ): IProgramOutput {
                                return theSemantics.moveArrayToInternalReturn(
                                    toVar = toVar as EVMInternalSummaryReturn,
                                    fromVar = fromVar,
                                    eSize = 1,
                                    cb = cb,
                                    elemConverter = Tag.CVLArray.UserArray.ElementEncoding.Unsigned.toRight()
                                )
                            }
                        }
                    }
                }

                override fun argumentPassingContext(): CollectingResult<ITypeDescriptorConverter<IExternalEncodeOutput, ICVLDataInput, IExternalInputBuffer>, String> {
                    if(fromType is CVLType.PureCVLType.Primitive.HashBlob) {
                        return "Cannot convert hashblob in context of argument passing".asError()
                    }
                    check(fromType is CVLType.PureCVLType.DynamicArray.Bytes1Array)
                    return encodingData<TerminalAction<IExternalEncodeOutput, ICVLDataInput, IExternalInputBuffer, *>>().liftEncoder()
                }

                override fun storageMappingKeyContext(): CollectingResult<ITypeDescriptorConverter<IMappingKeyOutput, ITACSymbolVar, IStorageMapping>, String> {
                    if(fromType is CVLType.PureCVLType.Primitive.HashBlob) {
                        return object : ITypeDescriptorConverter<IMappingKeyOutput, ITACSymbolVar, IStorageMapping> {
                            override fun convertTo(
                                fromVar: ITACSymbolVar,
                                toVar: IStorageMapping,
                                cb: (ITACCmd) -> ITACCmd
                            ): IMappingKeyOutput {
                                return theSemantics.reverseHashBlobStorageKey(
                                    fromVar, toVar
                                )
                            }

                        }.lift()
                    } else {
                        check(fromType is CVLType.PureCVLType.DynamicArray.Bytes1Array)
                        return object : ITypeDescriptorConverter<IMappingKeyOutput, ITACSymbolVar, IStorageMapping> {
                            override fun convertTo(
                                fromVar: ITACSymbolVar,
                                toVar: IStorageMapping,
                                cb: (ITACCmd) -> ITACCmd
                            ): IMappingKeyOutput {
                                return theSemantics.movePackedBytesToStorageKey(
                                    fromVar, toVar
                                )
                            }

                        }.lift()
                    }
                }
            })
        }

        override fun <SRC, DST, O> converterTo(toType: CVLType.PureCVLType, i: FromVMContextDispatcher<SRC, DST, O>): CollectingResult<ITypeDescriptorConverter<SRC, DST, O>, String> {
            return i.accept(object : FromVMContext.Visitor {
                override fun returnValueContext(): CollectingResult<ITypeDescriptorConverter<IProgramOutput, IExternalOutputBuffer, CallReturnTarget>, String> {
                    if(toType !is CVLType.PureCVLType.DynamicArray.Bytes1Array) {
                        return "Cannot convert ${this@PackedBytes1Array} to $toType in context of return values".asError()
                    }
                    return encodingData<TerminalAction<IProgramOutput, IExternalOutputBuffer, CallReturnTarget, *>>().liftDecoder()
                }

                override fun hookValueContext(): CollectingResult<ITypeDescriptorConverter<ISimpleOutput, IHookParameter, ITACSymbolVar>, String> {
                    if(toType !is CVLType.PureCVLType.DynamicArray.Bytes1Array) {
                        return "Cannot convert ${this@PackedBytes1Array} to $toType in context of hook values".asError()
                    }
                    return object : ITypeDescriptorConverter<ISimpleOutput, IHookParameter, ITACSymbolVar> {
                        override fun convertTo(
                            fromVar: IHookParameter,
                            toVar: ITACSymbolVar,
                            cb: (ITACCmd) -> ITACCmd
                        ): ISimpleOutput {
                            return theSemantics.moveHashedArray(fromVar, toVar, cb)
                        }
                    }.lift()
                }

                override fun stateValueContext(): CollectingResult<ITypeDescriptorConverter<ISimpleOutput, ITACSymbolVar, ITACSymbolVar>, String> {
                    return "Cannot convert ${this@PackedBytes1Array} to $toType in context of storage values".asError()
                }

                override fun internalSummaryArgBindingContext(): CollectingResult<ITypeDescriptorConverter<IProgramOutput, IInternalSummaryInput, ICVLDataOutput>, String> {
                    if(toType !is CVLType.PureCVLType.DynamicArray.Bytes1Array) {
                        return "Cannot convert ${this@PackedBytes1Array} to $toType in context of internal summary binding".asError()
                    }
                    if(location == EVMLocationSpecifier.calldata) {
                        return tryConvertCalldataInternalArg(toType)
                    }
                    return convertibleInInternalMemoryContext().map {
                        object : ITypeDescriptorConverter<IProgramOutput, IInternalSummaryInput, ICVLDataOutput> {
                            override fun convertTo(
                                fromVar: IInternalSummaryInput,
                                toVar: ICVLDataOutput,
                                cb: (ITACCmd) -> ITACCmd
                            ): IProgramOutput {
                                return theSemantics.moveArrayFromInternalArg(
                                    fromVar = fromVar as EVMInternalSummaryInput,
                                    toVar = toVar,
                                    eSize = 1,
                                    cb = cb,
                                    elemConversion = Tag.CVLArray.UserArray.ElementEncoding.Unsigned.toRight()
                                )
                            }
                        }
                    }
                }
            })
        }

        override fun isDynamicSize(): Boolean = true
    }

    protected data class EVMPrintingContext(override val isLibrary: Boolean, val isNested: Boolean) : PrintingContext


    object ArrayHelpers {
        fun message(from: String, to: String, suffix: String) = "Cannot convert from $from to $to: $suffix".asError()

        /**
         * If this returns a non-error, then the following must hold:
         * 1. [pure] is a CVLArrayType
         * 2. evm and pure use the same low-level encoding for representing their elements. If [evm] is a bytes array,
         * [pure] must also be a bytes array. Further, we must have that the byte-level representations are equivalent,
         * so it is safe to bytelong copy the values in [pure] into an external buffer.
         *
         * Note that this does NOT check that the top-level structure of the types are the same. That is, this
         * will return a success result if [evm] is a [DynamicArrayDescriptor] and [pure] is a [spec.cvlast.CVLType.PureCVLType.StaticArray].
         *
         * It is up to callers to check that the top-level shape is correct.
         */
        fun basicChecks(evm: VMArrayTypeDescriptor, pure: CVLType.PureCVLType, from: String, to: String) : CollectingResult<CVLType.PureCVLType.CVLArrayType, String> {
            if (pure !is CVLType.PureCVLType.CVLArrayType) {
                return ArrayHelpers.message(from, to, suffix = "$pure is not an array type")
            }
            require(evm is EVMArrayLikeType)
            if(evm is PackedBytes1Array != pure is CVLType.PureCVLType.DynamicArray.Bytes1Array) {
                return message(from, to, suffix = "mismatched element packing")
            }
            return pure.lift()
        }
    }

    @Serializable
    data class DynamicArrayDescriptor(
        override val location: EVMLocationSpecifier?,
        override val elementType: EVMTypeDescriptor,
    ): EVMTypeDescriptor(), EVMValueArrayConverter, VMTypeWithCorrespondence, VMDynamicArrayTypeDescriptor {
        init {
            checkCoherence(elementType)
        }

        /**
         * Given a function [elemBuilder] that can build the type descriptors for
         * elements, construct a [DataLayout] describing a dynamic array of these elements
         * (including the open scope if need be).
         */
        private fun <T> buildLayout(
            elemBuilder: () -> CollectingResult<DataLayout<T>, String>
        ) : CollectingResult<DataLayout<T>, String> {
            return elemBuilder().map { nxt ->
                val seq = DataLayout.SequenceOf(DataLayout.SequenceElement.Elements(nxt))
                DataLayout.DynamicPointer(
                    next = DataLayout.LengthTaggedTuple(
                        if(elementType.isDynamicType()) {
                            DataLayout.OpenScope(seq)
                        } else {
                            seq
                        }
                    )
                )
            }
        }

        override fun hashCode() = hash { it + location + elementType }
        override val lengthType: UIntK
            get() = UIntK(EVM_BITWIDTH256)

        override val elementSize: BigInteger get() = EVM_WORD_SIZE
        override fun isDynamicSize(): Boolean = true

        private fun buildArray(elementPureType: CVLType.PureCVLType) =
            CVLType.PureCVLType.DynamicArray.WordAligned(elementPureType).lift()

        override fun correspondenceTypeToConvertFrom(toVMContext: ToVMContext): CollectingResult<VMTypeWithCorrespondence.NeedsConversionCheck, String> =
            elementType.sourceCorrespondence(toVMContext).bind { it.build(::buildArray) }

        override fun correspondenceTypeToConvertTo(fromVMContext: FromVMContext): CollectingResult<VMTypeWithCorrespondence.NeedsConversionCheck, String> {
            return if(fromVMContext == FromVMContext.HookValue) {
                "no correspondance type to convert to in hook context".asError()
            } else {
                elementType.targetCorrespondence(fromVMContext).bind { it.build(::buildArray) }
            }
        }


        /**
         * Checks the following:
         * 1. [fromType] is an array
         * 2. `this` [DynamicArrayDescriptor] is convertible in an external context (we are not passing to a storage)
         * 3. [fromType] and `this` use the same element packing
         * 4. [fromType] is a [spec.cvlast.CVLType.PureCVLType.DynamicArray.WordAligned] or [spec.cvlast.CVLType.PureCVLType.ArrayLiteral]
         */
        override fun <O, SRC, DST> converterFrom(
            fromType: CVLType.PureCVLType,
            i: ToVMContextDispatcher<O, SRC, DST>
        ): CollectingResult<ITypeDescriptorConverter<O, SRC, DST>, String> {
            /**
             * Establishes 1 & 3 above
             */
            return ArrayHelpers.basicChecks(this, fromType, fromType.toString(), this@DynamicArrayDescriptor.prettyPrint()).bind { fromArrayType ->
                /**
                 * Establishes condition 4
                 */
                when(fromArrayType) {
                    is CVLType.PureCVLType.DynamicArray.Bytes1Array -> `impossible!`
                    is CVLType.PureCVLType.ArrayLiteral,
                    is CVLType.PureCVLType.DynamicArray.WordAligned -> fromArrayType.lift()
                    is CVLType.PureCVLType.StaticArray -> "Cannot convert static arrays into dynamic arrays".asError()
                }
            }.bind { arr ->
                i.accept(object : ToVMContext.Visitor {
                    override fun internalSummaryReturnContext(): CollectingResult<ITypeDescriptorConverter<IProgramOutput, ICVLDataInput, IInternalSummaryReturn>, String> {
                        val elementConverter = this@DynamicArrayDescriptor.elementType.converterFrom(arr.elementType, ToVMContext.InternalSummaryReturn.getVisitor())
                        return convertibleInInternalMemoryContext().map(elementConverter) { _, conv ->
                            /**
                             * If the buffer encodings for the array matches, and we have a primtive type, use a long
                             * copy, otherwise iterate and use the converter.
                             */
                            val eitherConv : InternalElementConverter<ICVLDataInput, IInternalSummaryReturn> =
                                if(elementType is EVMValueType && (arr.elementType as? CVLType.PureCVLType.MaybeBufferEncodeableType)?.getEncodingOrNull() == elementType.bufferEncoding) {
                                    elementType.expectedBufferEncoding.toRight()
                                } else {
                                    conv.toLeft()
                                }
                            object : ITypeDescriptorConverter<IProgramOutput, ICVLDataInput, IInternalSummaryReturn> {
                                override fun convertTo(
                                    fromVar: ICVLDataInput,
                                    toVar: IInternalSummaryReturn,
                                    cb: (ITACCmd) -> ITACCmd
                                ): IProgramOutput {
                                    return theSemantics.moveArrayToInternalReturn(
                                        toVar = toVar as EVMInternalSummaryReturn,
                                        fromVar = fromVar,
                                        cb = cb,
                                        eSize = EVM_WORD_SIZE_INT,
                                        elemConverter = eitherConv
                                    )
                                }

                            }
                        }
                    }

                    override fun argumentPassingContext(): CollectingResult<ITypeDescriptorConverter<IExternalEncodeOutput, ICVLDataInput, IExternalInputBuffer>, String> {
                        /**
                         * Establishes condition 2
                         */
                        return convertibleInExternalCallContext().bind(elementType.converterFrom(arr.elementType, ToVMContext.ArgumentPassing.getVisitor())) { _, conv ->
                            buildLayout {
                                conv.rewrapConverter()
                            }
                        }.bind { it.liftEncoder() }
                    }

                    override fun storageMappingKeyContext(): CollectingResult<ITypeDescriptorConverter<IMappingKeyOutput, ITACSymbolVar, IStorageMapping>, String> {
                        return "Cannot use dynamic array as storage mapping key".asError()
                    }
                })
            }
        }

        /**
         * Checks the following:
         * 1. [toType] is an array
         * 2. `this` [DynamicArrayDescriptor] is convertible in an external context (we are not returning a storage pointer)
         * 3. [toType] and `this` have the same low-level buffer encoding
         * 4. [toType] is a [spec.cvlast.CVLType.PureCVLType.DynamicArray.WordAligned] dynamic array
         * 5. [toType] has "primitive" types as it's elements
         *
         * 1-4 mirror the conditions in [converterFrom]. NB that 5 is new, and more restrictive. This is intentional;
         * when copying INTO the EVM, we control the ABI encoded blob, and know a priori its size. When consuming
         * data FROM the EVM, discovering exactly how large the blob is would require either traversing the
         * encoded blob (not possible in general without loops) or making some *very* unsafe assumptions w.r.t. how the
         * EVM chooses to encode data. Thus, when taking data out of the EVM we narrow the supported types to those where we
         * can compute their size in a static number of accesses.
         */
        override fun <O, SRC, DST> converterTo(
            toType: CVLType.PureCVLType,
            i: FromVMContextDispatcher<O, SRC, DST>
        ): CollectingResult<ITypeDescriptorConverter<O, SRC, DST>, String> {
            /** [ArrayHelpers.basicChecks] establishes 1 & 3 above */
            return ArrayHelpers.basicChecks(this, toType, this@DynamicArrayDescriptor.prettyPrint(), toType.toString()).bind { toArrayType ->
                /**
                 * Establishes 4
                 */
                if (toArrayType !is CVLType.PureCVLType.DynamicArray) {
                    "Cannot convert a dynamic array to a static array".asError()
                } else if(toArrayType is CVLType.PureCVLType.DynamicArray.Bytes1Array) {
                    "Cannot convert a dynamic, unpacked array into a packed array".asError()
                } else {
                    toArrayType.lift()
                }
            }.bind { arr ->
                i.accept(object : FromVMContext.Visitor {
                    override fun returnValueContext(): CollectingResult<ITypeDescriptorConverter<IProgramOutput, IExternalOutputBuffer, CallReturnTarget>, String> {
                        /**
                          And finally, establish condition 2
                         */
                        return convertibleInExternalCallContext().bind(this@DynamicArrayDescriptor.elementType.converterTo(arr.elementType, FromVMContext.ReturnValue.getVisitor())) { _, conv ->
                            buildLayout {
                                conv.rewrapConverter()
                            }
                        }.bind { it.liftDecoder() }
                    }

                    override fun hookValueContext(): CollectingResult<ITypeDescriptorConverter<ISimpleOutput, IHookParameter, ITACSymbolVar>, String> {
                        return "Cannot convert ${this@DynamicArrayDescriptor} to $toType in context of hook values".asError()
                    }

                    override fun stateValueContext(): CollectingResult<ITypeDescriptorConverter<ISimpleOutput, ITACSymbolVar, ITACSymbolVar>, String> {
                        return "Cannot convert ${this@DynamicArrayDescriptor} to $toType in context of storage values".asError()
                    }

                    override fun internalSummaryArgBindingContext(): CollectingResult<ITypeDescriptorConverter<IProgramOutput, IInternalSummaryInput, ICVLDataOutput>, String> {
                        if(location == EVMLocationSpecifier.calldata) {
                            return tryConvertCalldataInternalArg(toType)
                        }
                        val elemConv = elementType.converterTo(arr.elementType, FromVMContext.InternalSummaryArgBinding.getVisitor())
                        return convertibleInInternalMemoryContext().map(elemConv) { _, eConv ->
                            val convImpl : InternalElementConverter<IInternalSummaryInput, ICVLDataOutput> =
                                if(elementType is EVMValueType && (arr.elementType as? CVLType.PureCVLType.MaybeBufferEncodeableType)?.getEncodingOrNull() == elementType.expectedBufferEncoding) {
                                    elementType.expectedBufferEncoding.toRight()
                                } else {
                                    eConv.toLeft()
                                }
                            object : ITypeDescriptorConverter<IProgramOutput, IInternalSummaryInput, ICVLDataOutput> {
                                override fun convertTo(
                                    fromVar: IInternalSummaryInput,
                                    toVar: ICVLDataOutput,
                                    cb: (ITACCmd) -> ITACCmd
                                ): IProgramOutput {
                                    return theSemantics.moveArrayFromInternalArg(
                                        eSize = EVM_WORD_SIZE_INT,
                                        toVar = toVar,
                                        fromVar = fromVar as EVMInternalSummaryInput,
                                        cb = cb,
                                        elemConversion = convImpl
                                    )
                                }

                            }
                        }
                    }
                })
            }
        }

        override fun prettyPrint(): String {
            return "${this.elementType.prettyPrint()}[]"
        }

        override fun toString() = "${this.elementType}[]"

        override fun canonicalString(ctxt: EVMPrintingContext): String {
            val baseType = elementType.canonicalString(ctxt.copy(isNested = true)) + "[]"
            return if(!ctxt.isNested && ctxt.isLibrary && this.location == EVMLocationSpecifier.storage) {
                "$baseType storage"
            } else {
                baseType
            }
        }

        override fun mergeWith(other: VMTypeDescriptor): CollectingResult<VMTypeDescriptor, String> {
            if(other !is DynamicArrayDescriptor) {
                return "Could not merge $this with $other, they are different types".asError()
            }
            return this.elementType.mergeWith(other.elementType).bind {elem ->
                mergeLocations(this, other) { l ->
                    DynamicArrayDescriptor(elementType = elem as EVMTypeDescriptor, location = l)
                }
            }
        }


        override fun sizeAsEncodedMember(): BigInteger {
            return EVM_WORD_SIZE
        }

        override fun getMinimumEncodingSize(): BigInteger {
            return if(this.location == EVMLocationSpecifier.storage) {
                EVM_WORD_SIZE
            } else {
                EVM_WORD_SIZE * BigInteger.TWO
            }
        }
    }

    @Serializable
    class StaticArrayDescriptor(
        override val location: EVMLocationSpecifier?,
        override val elementType: EVMTypeDescriptor,
        override val numElements: BigInteger
    ): VMStaticArrayType, EVMArrayLikeType, VMTypeWithCorrespondence, EVMTypeDescriptor() {
        override val elementSize: BigInteger = 32.toBigInteger()

        /**
         * Fulfils a similar role to the function in [DynamicArrayDescriptor], builds
         * the [DataLayout] for this static array given an [elemBuilder] callback which generates a layout
         * for the element types (which are meant to "correspond" to the [elementType] in an intentially
         * unspecified way)
         */
        private fun <T> buildLayout(elemBuilder: () -> CollectingResult<DataLayout<T>, String>) : CollectingResult<DataLayout<T>, String> {
            return elemBuilder().map {
                val elemStructure = DataLayout.StaticRepeatedOf(
                    num = numElements,
                    elem = it
                )
                if(elementType.isDynamicType()) {
                    DataLayout.DynamicPointer(
                        DataLayout.OpenScope(
                            elemStructure
                        )
                    )
                } else {
                    elemStructure
                }
            }
        }

        init {
            checkCoherence(elementType)
        }


        private fun convertible(type: CVLType.PureCVLType, from: String, to: String) = convertibleInExternalCallContext().bind {
            ArrayHelpers.basicChecks(this, type, from, to)
        }

        private fun buildArray(elementPureType: CVLType.PureCVLType) =
            CVLType.PureCVLType.StaticArray(elementPureType, numElements).lift()

        override fun correspondenceTypeToConvertFrom(toVMContext: ToVMContext): CollectingResult<VMTypeWithCorrespondence.NeedsConversionCheck, String> =
            elementType.sourceCorrespondence(toVMContext).bind { it.build(::buildArray) }

        override fun correspondenceTypeToConvertTo(fromVMContext: FromVMContext): CollectingResult<VMTypeWithCorrespondence.NeedsConversionCheck, String> {
            if(fromVMContext == FromVMContext.HookValue) {
                return "no corresponding CVL type to convert to in hook value context".asError()
            }
            return elementType.targetCorrespondence(fromVMContext).bind { it.build(::buildArray) }
        }

        private fun <O, SRC, DST> internalConverter(
            cvlType: CVLType.PureCVLType,
            contextName: String,
            ordering: (CVLType.PureCVLType, EVMTypeDescriptor) -> String,
            f: (CVLType.PureCVLType) -> CollectingResult<ITypeDescriptorConverter<O, SRC, DST>, String>
        ) : CollectingResult<ITypeDescriptorConverter<O, SRC, DST>, String> {
            if(cvlType !is CVLType.PureCVLType.StaticArray) {
                return "Cannot convert ${ordering(cvlType, this)} in context of $contextName".asError()
            }
            if(cvlType.logicalLength != this@StaticArrayDescriptor.numElements) {
                return "Mismatched number of elements for $cvlType and ${this@StaticArrayDescriptor}".asError()
            }
            return f(cvlType.elementType)
        }


        /**
         * The converter rules for static arrays are broadly similar to those of [DynamicArrayDescriptor].
         * We can convert under the following conditions
         * 1. [fromType] is an Array
         * 2. [fromType] and this [StaticArrayDescriptor] use the same buffer level representation for their elements.
         * 3. [fromType] is an array literal with length equal to [numElements], or is a static array with [numElements]
         * 4. this [StaticArrayDescriptor] is convertible in an external context (i.e., it is not a storage pointer)
         */
        override fun <O, SRC, DST> converterFrom(
            fromType: CVLType.PureCVLType,
            i: ToVMContextDispatcher<O, SRC, DST>
        ): CollectingResult<ITypeDescriptorConverter<O, SRC, DST>, String> {
            return i.accept(object : ToVMContext.Visitor {
                override fun internalSummaryReturnContext(): CollectingResult<ITypeDescriptorConverter<IProgramOutput, ICVLDataInput, IInternalSummaryReturn>, String> {
                    return internalConverter(fromType, "internal summary returns", { a, b -> "$a to $b"}) { elemType ->
                        elementType.converterFrom(elemType, ToVMContext.InternalSummaryReturn.getVisitor()).map { elementConverter ->
                            object : ITypeDescriptorConverter<IProgramOutput, ICVLDataInput, IInternalSummaryReturn> {
                                override fun convertTo(
                                    fromVar: ICVLDataInput,
                                    toVar: IInternalSummaryReturn,
                                    cb: (ITACCmd) -> ITACCmd
                                ): IProgramOutput {
                                    return theSemantics.moveStaticArrayToInternalReturn(
                                        fromVar = fromVar,
                                        toVar = toVar as EVMInternalSummaryReturn,
                                        numFields = numElements,
                                        cvlElementType = elemType,
                                        cb = cb,
                                        elementConverter = elementConverter
                                    )
                                }

                            }
                        }
                    }
                }
                override fun argumentPassingContext(): CollectingResult<ITypeDescriptorConverter<IExternalEncodeOutput, ICVLDataInput, IExternalInputBuffer>, String> {
                    /**
                     * convertible establishes 1, 2, and 4
                     */
                    return convertible(fromType, fromType.toString(), this@StaticArrayDescriptor.prettyPrint()).bind { fromType ->
                        /**
                         * This establishes 3
                         */
                        when (fromType) {
                            is CVLType.PureCVLType.StaticArray -> fromType.logicalLength.lift()
                            is CVLType.PureCVLType.ArrayLiteral -> fromType.logicalLength.lift()
                            is CVLType.PureCVLType.DynamicArray -> "cannot convert from a dynamic array to a static array".asError()
                        }.bind { logicalLength ->
                            if (logicalLength != this@StaticArrayDescriptor.numElements) {
                                "cannot convert from $fromType to ${this@StaticArrayDescriptor}, sizes don't match".asError()
                            } else {
                                fromType.lift()
                            }
                        }
                    }.bind { arr ->
                        elementType.converterFrom(arr.elementType, ToVMContext.ArgumentPassing.getVisitor())
                    }.bind { conv ->
                        buildLayout {
                            conv.rewrapConverter()
                        }
                    }.bind { it.liftEncoder() }
                }

                override fun storageMappingKeyContext(): CollectingResult<ITypeDescriptorConverter<IMappingKeyOutput, ITACSymbolVar, IStorageMapping>, String> {
                    return "Cannot use static array as a storage mapping key".asError()
                }
            })
        }

        /**
         * Similar to the [converterFrom], but with the added restriction that the elements of the static array being
         * decoded must be of "static" size. This restriction mirrors the that in [DynamicArrayDescriptor.converterTo]
         */
        override fun <O, SRC, DST> converterTo(
            toType: CVLType.PureCVLType,
            i: FromVMContextDispatcher<O, SRC, DST>,
        ): CollectingResult<ITypeDescriptorConverter<O, SRC, DST>, String> {
            @Suppress("NAME_SHADOWING")
            return convertible(toType, this@StaticArrayDescriptor.prettyPrint(), toType.toString()).bind { toType ->
                when (toType) {
                    is CVLType.PureCVLType.ArrayLiteral -> ArrayHelpers.message(this@StaticArrayDescriptor.prettyPrint(), toType.toString(),
                        "array literal cannot be assigned to.")
                    is CVLType.PureCVLType.StaticArray ->
                        if (toType.logicalLength != this@StaticArrayDescriptor.numElements) {
                            ArrayHelpers.message(this@StaticArrayDescriptor.prettyPrint(), toType.toString(), "sizes don't match.")
                        } else {
                            toType.lift()
                        }
                    is CVLType.PureCVLType.DynamicArray -> ArrayHelpers.message(this@StaticArrayDescriptor.prettyPrint(), toType.toString(),
                    "dynamic arrays cannot be converted from static arrays.")
                }
            }.bind { toType ->
                i.accept(object : FromVMContext.Visitor {
                    override fun returnValueContext(): CollectingResult<ITypeDescriptorConverter<IProgramOutput, IExternalOutputBuffer, CallReturnTarget>, String> {
                        return elementType.converterTo(toType.elementType, FromVMContext.ReturnValue.getVisitor()).bind { conv ->
                            buildLayout {
                                conv.rewrapConverter()
                            }
                        }.bind { it.liftDecoder() }
                    }

                    override fun hookValueContext(): CollectingResult<ITypeDescriptorConverter<ISimpleOutput, IHookParameter, ITACSymbolVar>, String> {
                        return "Cannot convert ${this@StaticArrayDescriptor} to $toType in context of hook values".asError()
                    }

                    override fun stateValueContext(): CollectingResult<ITypeDescriptorConverter<ISimpleOutput, ITACSymbolVar, ITACSymbolVar>, String> {
                        return "Cannot convert ${this@StaticArrayDescriptor} to $toType in context of storage values".asError()
                    }

                    override fun internalSummaryArgBindingContext(): CollectingResult<ITypeDescriptorConverter<IProgramOutput, IInternalSummaryInput, ICVLDataOutput>, String> {
                        if(location == EVMLocationSpecifier.calldata) {
                            return tryConvertCalldataInternalArg(toType)
                        }
                        return internalConverter(toType, "internal summary arguments", { a, b -> "$b to $a"}) { elemType ->
                            this@StaticArrayDescriptor.elementType.converterTo(elemType, FromVMContext.InternalSummaryArgBinding.getVisitor()).map { elemConverter ->
                                object : ITypeDescriptorConverter<IProgramOutput, IInternalSummaryInput, ICVLDataOutput> {
                                    override fun convertTo(
                                        fromVar: IInternalSummaryInput,
                                        toVar: ICVLDataOutput,
                                        cb: (ITACCmd) -> ITACCmd
                                    ): IProgramOutput {
                                        return theSemantics.moveStaticArrayFromInternalArg(
                                            fromVar = fromVar as EVMInternalSummaryInput,
                                            toVar = toVar,
                                            numElements = numElements,
                                            elementConverter = elemConverter,
                                            cb = cb,
                                            elementType = elemType
                                        )
                                    }

                                }
                            }
                        }
                    }
                })
            }
        }


        override fun equals(other: Any?): Boolean {
            return other is StaticArrayDescriptor && other.location == this.location && this.elementType == other.elementType && other.numElements == this.numElements
        }


        override fun hashCode() = hash { it + location + elementType + elementSize }

        override fun isDynamicSize(): Boolean = (elementType as? EVMReferenceType)?.isDynamicSize() == true
        override fun prettyPrint(): String {
            return "${this.elementType.prettyPrint()}[${this.numElements}]"
        }

        override fun toString() = "${this.elementType}[${this.numElements}]"

        override fun canonicalString(ctxt: EVMPrintingContext): String {
            val baseType = this.elementType.canonicalString(ctxt.copy(isNested = true)) + "[${this.numElements}]"
            return if(ctxt.isLibrary && !ctxt.isNested && this.location == EVMLocationSpecifier.storage) {
                "$baseType storage"
            } else {
                baseType
            }
        }

        override fun mergeWith(other: VMTypeDescriptor): CollectingResult<VMTypeDescriptor, String> {
            if(other !is StaticArrayDescriptor) {
                return "Cannot merge $other with $this: they are different types".asError()
            }
            if(other.numElements != this.numElements) {
                return "Cannot merge $other with $this: they have differing sizes".asError()
            }
            return elementType.mergeWith(other.elementType).bind { elem ->
                mergeLocations(this, other) { l ->
                    StaticArrayDescriptor(
                        location = l,
                        elementType = elem as EVMTypeDescriptor,
                        numElements = numElements
                    )
                }
            }
        }

        override fun sizeAsEncodedMember(): BigInteger {
            return if(isDynamicSize() || this.location == EVMLocationSpecifier.storage) {
                EVM_WORD_SIZE
            } else {
                elementType.sizeAsEncodedMember() * numElements
            }
        }

        override fun getMinimumEncodingSize(): BigInteger {
            if(this.location == EVMLocationSpecifier.storage) {
                return EVM_WORD_SIZE
            }
            val base = this.elementType.getMinimumEncodingSize() * numElements
            return if(this.isDynamicType()) {
                base + EVM_WORD_SIZE // one for the pointer to this
            } else {
                base
            }
        }
    }

    /**
     * @property name The name of the struct, including the defining contract (if it's not a top-level definition)
     */
    @Serializable
    class EVMStructDescriptor(
        val canonicalId: String,
        override val location: EVMLocationSpecifier?,
        val fields: List<EVMStructEntry>,
        val name: String,
    ): EVMReferenceType, VMTypeWithCorrespondence, EVMTypeDescriptor(), VMStructDescriptor {
        override val fieldTypes: Map<String, VMTypeDescriptor>
            get() = fields.associate {
                it.fieldName to it.fieldType
            }

        @Serializable
        @Treapable
        data class EVMStructEntry(
            val fieldName: String,
            val fieldType: EVMTypeDescriptor
        ): AmbiSerializable

        init {
            fields.forEach {
                checkCoherence(it.fieldType)
            }
        }

        override fun equals(other: Any?): Boolean {
            return other is EVMStructDescriptor && other.location == this.location &&
                this.name == other.name && this.fields == other.fields && this.canonicalId == other.canonicalId
        }

        override fun hashCode() = hash { it + canonicalId + location + fields + name }

        override fun isDynamicSize(): Boolean {
            return fields.any {
                (it.fieldType as? EVMReferenceType)?.isDynamicSize() == true
            }
        }

        /**
         * Should not be used internally for any semantic information. Does affect the out.json of regression tests
         */
        override fun prettyPrint() = name

        override fun canonicalString(ctxt: EVMPrintingContext): String {
            return if(ctxt.isLibrary) {
                if(ctxt.isNested || location != EVMLocationSpecifier.storage) {
                    name
                } else {
                    "$name storage"
                }
            } else {
                fields.joinToString(separator = ",", prefix = "(", postfix = ")") {
                    it.fieldType.canonicalString(ctxt.copy(isNested = true))
                }
            }
        }

        override fun toString(): String = prettyPrint()

        override fun mergeWith(other: VMTypeDescriptor): CollectingResult<VMTypeDescriptor, String> {
            if(other !is EVMStructDescriptor || other.canonicalId != this.canonicalId) {
                return "Cannot merge $this with $other: they are different types".asError()
            }
            if(other.name != this.name) {
                return "Cannot merge $this with $other: while the same type, the canonical name is not known".asError()
            }
            if(other.fields.size != this.fields.size) {
                return "Cannot merge $this with $other: their fields are different".asError()
            }
            return this.fields.mapIndexed { idx, f ->
                val otherF = other.fields[idx]
                if(otherF.fieldName != f.fieldName) {
                    return "Could not merge $this with $other: the names of field $idx differ: ${f.fieldName} vs ${otherF.fieldName}".asError()
                }
                f.fieldType.mergeWith(otherF.fieldType).map { f to it }
            }.flatten().bind { fields ->
                val mergedFields = fields.map { (f, fieldType) ->
                    f.copy(fieldType = fieldType as EVMTypeDescriptor)
                }
                mergeLocations(this, other) { l ->
                    EVMStructDescriptor(canonicalId, l, mergedFields, name)
                }
            }
        }

        override fun sizeAsEncodedMember(): BigInteger {
            return if(isDynamicSize() || this.location == EVMLocationSpecifier.storage) {
                EVM_WORD_SIZE
            } else {
                fields.sumOf {
                    it.fieldType.sizeAsEncodedMember()
                }
            }
        }

        override fun getMinimumEncodingSize(): BigInteger {
            return if (this.location == EVMLocationSpecifier.storage) {
                EVM_WORD_SIZE
            } else {
                fields.sumOf { it.fieldType.getMinimumEncodingSize() } + if(isDynamicType()) {
                    EVM_WORD_SIZE
                } else {
                    BigInteger.ZERO
                }
            }
        }

        private  fun buildStruct(elementTypes: List<CVLType.PureCVLType>) =
            CVLType.PureCVLType.Struct(name, fields.zip(elementTypes).map { (field, pureType) ->
                CVLType.PureCVLType.Struct.StructEntry(field.fieldName, pureType)
            })

        override fun correspondenceTypeToConvertTo(fromVMContext: FromVMContext): CollectingResult<VMTypeWithCorrespondence.NeedsConversionCheck, String> =
            fields.map { structField ->
                structField.fieldType.targetCorrespondence(fromVMContext).reduceErrors { "field `${structField.fieldName}`: $it" }
            }.flatten()
                .reduceErrors { "${this.prettyPrint()} has no target type to convert to because some of its fields can't be converted: $it" }
                .bind { it.build(::buildStruct) }

        override fun correspondenceTypeToConvertFrom(toVMContext: ToVMContext): CollectingResult<VMTypeWithCorrespondence.NeedsConversionCheck, String> =
            fields.map { structField ->
                structField.fieldType.sourceCorrespondence(toVMContext)
            }.flatten()
                .reduceErrors { "${this.prettyPrint()} has no source type to convert from because some of its fields can't be converted: ${it.joinToString()}" }
                .bind { it.build(::buildStruct) }


        override fun <O, SRC, DST> converterFrom(
            fromType: CVLType.PureCVLType,
            i: ToVMContextDispatcher<O, SRC, DST>,
        ): CollectingResult<ITypeDescriptorConverter<O, SRC, DST>, String> {
            if(fromType !is CVLType.PureCVLType.Struct) {
                return "Cannot convert from $fromType to ${this@EVMStructDescriptor}".asError()
            }
            if(fromType.fields.size != this.fields.size) {
                return "Cannot convert from $fromType to ${this@EVMStructDescriptor}: num-of-fields mismatch (${fromType.fields.size} vs ${this.fields.size})".asError()
            }
            val fromTag = fromType.toTag()
            return i.accept(object : ToVMContext.Visitor {
                override fun internalSummaryReturnContext(): CollectingResult<ITypeDescriptorConverter<IProgramOutput, ICVLDataInput, IInternalSummaryReturn>, String> {
                    val fldConverters = fields.map { (nm, fTy) ->
                        val s = fromType.fields.singleOrNull {
                            it.fieldName == nm
                        }?.cvlType
                        if (s == null) {
                            "Field name mismatch for $nm".asError()
                        } else {
                            check(fromTag.getField(nm)?.type == s.toTag())
                            fTy.converterFrom(s, ToVMContext.Visitor::internalSummaryReturnContext).map { c ->
                                StructFieldConverter(nm, fromTag.getField(nm)!!.type, fTy, c)
                            }
                        }
                    }.flatten()
                    return convertibleInInternalMemoryContext().map(fldConverters) { _, fields ->
                        object : ITypeDescriptorConverter<IProgramOutput, ICVLDataInput, IInternalSummaryReturn> {
                            override fun convertTo(
                                fromVar: ICVLDataInput,
                                toVar: IInternalSummaryReturn,
                                cb: (ITACCmd) -> ITACCmd
                            ): IProgramOutput {
                                return theSemantics.moveStructToInternalReturn(
                                    dest = toVar as EVMInternalSummaryReturn,
                                    src = fromVar,
                                    cb = cb,
                                    fields = fields
                                )
                            }

                        }
                    }
                }

                override fun argumentPassingContext(): CollectingResult<ITypeDescriptorConverter<IExternalEncodeOutput, ICVLDataInput, IExternalInputBuffer>, String> {
                    return convertibleInExternalCallContext().bind {
                        fields.map { ent ->
                            val fromTy = fromType.fields.singleOrNull {
                                it.fieldName == ent.fieldName
                            }?.cvlType ?: return@map "Field name mismatch for $ent".asError()
                            ent.fieldType.converterFrom(fromTy, ToVMContext.Visitor::argumentPassingContext).bind { conv ->
                                (ent.fieldName `to?` (conv as? DataLayoutEncoder)?.encodingLayout)?.lift() ?: "Cannot convert field ${ent.fieldName} of type ${ent.fieldType} in external argument context".asError()
                            }
                        }.flatten().map { fieldConv ->
                            DataLayout.TupleOf(fieldConv)
                        }.bind { tuple ->
                            if(this@EVMStructDescriptor.isDynamicType()) {
                                DataLayout.DynamicPointer(DataLayout.OpenScope(tuple))
                            } else {
                                tuple
                            }.liftEncoder()
                        }
                    }
                }

                override fun storageMappingKeyContext(): CollectingResult<ITypeDescriptorConverter<IMappingKeyOutput, ITACSymbolVar, IStorageMapping>, String> {
                    return "Cannot use struct as storage mapping key".asError()
                }
            })
        }

        override fun <O, SRC, DST> converterTo(
            toType: CVLType.PureCVLType,
            i: FromVMContextDispatcher<O, SRC, DST>,
        ): CollectingResult<ITypeDescriptorConverter<O, SRC, DST>, String> {
            if(toType !is CVLType.PureCVLType.Struct) {
                return "Cannot convert ${this@EVMStructDescriptor} to $toType".asError()
            }

            if(toType.fields.size != this.fields.size) {
                return "Cannot convert ${this@EVMStructDescriptor} to $toType: num-of-fields mismatch (${this.fields.size} vs ${toType.fields.size})".asError()
            }

            if(this.name != toType.name) {
                return "Cannot convert ${this@EVMStructDescriptor} to $toType: Different struct names".asError()
            }
            return i.accept(object : FromVMContext.Visitor {
                override fun returnValueContext(): CollectingResult<ITypeDescriptorConverter<IProgramOutput, IExternalOutputBuffer, CallReturnTarget>, String> {
                    return convertibleInExternalCallContext().bind {
                        fields.map { ent ->
                            val tgt = toType.fields.singleOrNull {
                                it.fieldName == ent.fieldName
                            }?.cvlType ?: return@map "Field name mismatch for $ent".asError()
                            ent.fieldType.converterTo(tgt, FromVMContext.Visitor::returnValueContext)
                                .mapErrors { err -> "Cannot decode field '${ent.fieldName}' of struct '${this@EVMStructDescriptor.name}': $err" }
                                .bind { conv ->
                                    (ent.fieldName `to?` (conv as? DataLayoutDecoder)?.encodingLayout)?.lift() ?: "Cannot decode ${ent.fieldName} of type ${ent.fieldType} in an external return context".asError()
                                }
                        }.flatten().map { fieldConv ->
                            DataLayout.TupleOf(fieldConv)
                        }.bind {
                            if(this@EVMStructDescriptor.isDynamicType()) {
                                DataLayout.DynamicPointer(DataLayout.OpenScope(it))
                            } else {
                                it
                            }.liftDecoder()
                        }
                    }
                }

                override fun hookValueContext(): CollectingResult<ITypeDescriptorConverter<ISimpleOutput, IHookParameter, ITACSymbolVar>, String> {
                    return "Cannot convert ${this@EVMStructDescriptor} to $toType in context of hook values".asError()
                }

                override fun stateValueContext(): CollectingResult<ITypeDescriptorConverter<ISimpleOutput, ITACSymbolVar, ITACSymbolVar>, String> {
                    return "Cannot convert ${this@EVMStructDescriptor} to $toType in context of storage values".asError()
                }


                override fun internalSummaryArgBindingContext(): CollectingResult<ITypeDescriptorConverter<IProgramOutput, IInternalSummaryInput, ICVLDataOutput>, String> {
                    // special case, then we are actually *decoding* an ABI encoded object. This is the same flow as returns.
                    if(location == EVMLocationSpecifier.calldata) {
                        return tryConvertCalldataInternalArg(toType)
                    }
                    val fldContext = fields.map { ent ->
                        val tgt = toType.fields.singleOrNull {
                            it.fieldName == ent.fieldName
                        } ?: return@map "Field name mismatch for $ent".asError()
                        ent.fieldType.converterTo(tgt.cvlType, FromVMContext.Visitor::internalSummaryArgBindingContext).map { conv ->
                            StructFieldConverter(
                                name = ent.fieldName, conv = conv, tag = tgt.cvlType.toTag(), descriptor = ent.fieldType
                            )
                        }
                    }.flatten()
                    return convertibleInInternalMemoryContext().map(fldContext) { _, flds ->
                        object : ITypeDescriptorConverter<IProgramOutput, IInternalSummaryInput, ICVLDataOutput> {
                            override fun convertTo(
                                fromVar: IInternalSummaryInput,
                                toVar: ICVLDataOutput,
                                cb: (ITACCmd) -> ITACCmd
                            ): IProgramOutput {
                                return theSemantics.moveStructFromInternalArg(
                                    fromVar = fromVar as EVMInternalSummaryInput,
                                    toVar = toVar,
                                    cb = cb,
                                    fields = flds,
                                    fieldNameToOrdinal = this@EVMStructDescriptor.fields.withIndex().associate {
                                        it.value.fieldName to it.index
                                    }
                                )
                            }

                        }
                    }
                }
            })
        }

    }

    /**
     * @property name The name of the enum, including the defining contract (if it's not a top-level definition)
     */
    @Serializable
    data class EVMEnumDescriptor (
        val canonicalId: String,
        val name: String,
        val elements: List<String>
    ) : EVMIsomorphicValueType() {
        override fun hashCode() = hash { it + canonicalId + name + elements }

        override val bitwidth: Int = 8
        override val expectedBufferEncoding: Tag.CVLArray.UserArray.ElementEncoding
            get() = Tag.CVLArray.UserArray.ElementEncoding.Unsigned

        override fun prettyPrint(): String {
            return name
        }

        override fun canonicalString(ctxt: EVMPrintingContext): String {
            return if(ctxt.isLibrary) {
                name
            } else {
                "uint8"
            }
        }

        override fun mergeWith(other: VMTypeDescriptor): CollectingResult<VMTypeDescriptor, String> {
            if(other !is EVMEnumDescriptor || other.canonicalId != this.canonicalId) {
                return "Cannot merge $this with $other: they different types".asError()
            }
            if(this.name != other.name) {
                return "Cannot merge $this with $other: the canonical name is ambiguous (${this.name} vs ${other.name}".asError()
            }
            if(this.elements != other.elements) {
                return "Cannot merge $this with $other: their elements are different".asError()
            }
            return this.lift()
        }

        override val isomorphicType: CollectingResult.Result<CVLType.PureCVLType>
            get() = CVLType.PureCVLType.Enum(name, elements).lift()

        override fun toTag(): Tag {
            return Tag.Bit256
        }

    }

    @Serializable
    data class EVMMappingDescriptor(
        override val keyType: EVMTypeDescriptor,
        override val valueType: EVMTypeDescriptor,
        override val location: EVMLocationSpecifier?
    ): EVMReferenceType, EVMTypeDescriptor(), VMMappingDescriptor {
        init {
            check(keyType is PackedBytes1Array || keyType is EVMValueType) {
                "unexpected key type $keyType"
            }
            checkCoherence(valueType)
            check(location == null || location == EVMLocationSpecifier.storage) {
                "Got an unexpected location for mapping $location"
            }
        }

        override fun hashCode(): Int = hash { it + keyType + valueType + location }



        override fun isDynamicSize(): Boolean {
            return false
        }

        override fun prettyPrint(): String {
            return "mapping (${this.keyType.prettyPrint()} => ${this.valueType.prettyPrint()})"
        }

        override fun canonicalString(ctxt: EVMPrintingContext): String {
            if(!ctxt.isLibrary) {
                throw UnsupportedOperationException("Attempting to compute the signature representation for a mapping outside of a library context: this is impossible")
            }
            val kS = this.keyType.canonicalString(ctxt.copy(isNested = true))
            val vS = this.valueType.canonicalString(ctxt.copy(isNested = true))
            // location is expected to be storage location
            val suffix = if (ctxt.isNested) {
                ""
            } else {
                " storage"
            }
            return "mapping($kS => $vS)$suffix"
        }

        override fun mergeWith(other: VMTypeDescriptor): CollectingResult<VMTypeDescriptor, String> {
            if(other !is EVMMappingDescriptor) {
                return "Cannot merge $this with $other: they are different types".asError()
            }
            return other.keyType.mergeWith(other.keyType).bind { mergedK ->
                other.valueType.mergeWith(other.valueType).bind { mergedV ->
                    this.mergeLocations(this, other) { l ->
                        EVMMappingDescriptor(
                            mergedK as EVMTypeDescriptor, mergedV as EVMTypeDescriptor,
                            l
                        )
                    }
                }
            }
        }

        override fun sizeAsEncodedMember(): BigInteger {
            return EVM_WORD_SIZE
        }

        override fun getMinimumEncodingSize(): BigInteger {
            return EVM_WORD_SIZE
        }

        override fun <O, SRC, DST> converterFrom(
            fromType: CVLType.PureCVLType,
            i: ToVMContextDispatcher<O, SRC, DST>,
        ): CollectingResult<ITypeDescriptorConverter<O, SRC, DST>, String> {
            return "Cannot convert from $fromType to ${this@EVMMappingDescriptor}".asError()
        }

        override fun <O, SRC, DST> converterTo(
            toType: CVLType.PureCVLType,
            i: FromVMContextDispatcher<O, SRC, DST>,
        ): CollectingResult<ITypeDescriptorConverter<O, SRC, DST>, String> {
            return "Cannot convert from ${this@EVMMappingDescriptor} to $toType".asError()
        }
    }
}
