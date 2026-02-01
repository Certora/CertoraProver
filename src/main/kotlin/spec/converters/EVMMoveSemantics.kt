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

package spec.converters

import analysis.CommandWithRequiredDecls
import analysis.CommandWithRequiredDecls.Companion.withDecls
import analysis.merge
import config.Config
import datastructures.stdcollections.*
import evm.EVM_WORD_SIZE_INT
import spec.SafeMathCodeGen
import spec.converters.repr.CVLDataInput
import spec.converters.repr.CVLDataOutput
import spec.cvlast.CVLType
import spec.cvlast.typedescriptors.*
import spec.toProg
import tac.ITACCmd
import tac.ITACSymbol
import tac.ITACSymbolVar
import tac.Tag
import utils.SignUtilities
import utils.toValue
import vc.data.*
import vc.data.tacexprutil.*
import java.math.BigInteger

@Suppress("UNCHECKED_CAST")
object EVMMoveSemantics : EVMTypeDescriptor.ConverstionSemantics, SafeMathCodeGen {
    fun enarrow(it: IHookParameter) = it as HookValue

    fun enarrow(it: ITACSymbolVar) = it as TACSymbol.Var
    fun enarrow(it: ICVLDataInput) = it as CVLDataInput
    fun enarrow(it: ICVLDataOutput) = it as CVLDataOutput
    fun enarrow(it: ITACSymbol) = it as TACSymbol

    /**
     * Handles converting representations of signed integers and booleans between EVM and CVL
     * 1. All signed integers are represented as two's complement in EVM, but arbitrary precision integers
     *    in CVL
     * 2. Sometimes Bools are represented as 0/non-0 bit256 or simply Bools in EVM (per type rewriter) and are always
     *    represented as Bools in CVL
     */
    object ValueConverters {
        private val expFactory = TACExprFactTypeCheckedOnlyPrimitives

        fun convertValueTypeToCVL(dest: TACSymbol.Var, destType: CVLType.PureCVLType, src: TACSymbol.Var, srcType: VMValueTypeDescriptor): CommandWithRequiredDecls<TACCmd.Spec> =
                if(destType is CVLType.PureCVLType.UserDefinedValueType) {
                    convertValueTypeToCVL(dest, destType.base, src, srcType)
                } else if (srcType is VMSignedNumericValueTypeDescriptor) {
                    check(destType is CVLType.PureCVLType.Primitive.IntK || destType is CVLType.PureCVLType.Primitive.Mathint) {
                        "destType is actually $destType"
                    }
                    val f = TACBuiltInFunction.TwosComplement.Unwrap(Tag.Bit256)
                    CommandWithRequiredDecls(
                        listOf(TACCmd.Simple.AssigningCmd.AssignExpCmd(
                                dest, f.toTACFunctionSym(), listOf(src.asSym())
                        )), setOf(src, dest)
                    )
                } else if (destType is CVLType.PureCVLType.Primitive.Bool && src.tag != Tag.Bool) {
                    listOf(TACCmd.Simple.AssigningCmd.AssignExpCmd(dest,
                            TACExprFactTypeCheckedOnlyPrimitives
                                    .Ite(TACExprFactTypeCheckedOnlyPrimitives.Eq(src.asSym(), TACSymbol.lift(0).asSym()),
                                            TACSymbol.False.asSym(),
                                            TACSymbol.True.asSym()))).withDecls(dest, src)
                } else {
                    listOf(TACCmd.Simple.AssigningCmd.AssignExpCmd(
                            dest, src.asSym()
                    )).withDecls(dest, src)
                }

        private fun <T> numericCases(
            src: TACSymbol.Var,
            dest: TACSymbol.Var,
            intToBit: () -> T,
            bitToInt: () -> T,
            sameTag: () -> T,
            err: () -> Nothing
        ) : T {
            val targetTag = dest.tag
            val srcTag = src.tag
            if(targetTag == Tag.Int) {
                if(srcTag == Tag.Int) {
                    return sameTag()
                } else if(srcTag == Tag.Bit256) {
                    return bitToInt()
                } else {
                    err()
                }
            } else if(targetTag == Tag.Bit256) {
                if(srcTag == Tag.Bit256) {
                    return sameTag()
                } else if(srcTag == Tag.Int) {
                    return intToBit()
                } else {
                    err()
                }
            } else {
                err()
            }
        }

        /**
         * Place a value from src into dest, transforming between their types according to [destEncoding].
         * That is, if [dest] is tagged with [Tag.Int] and [src] is a [Tag.Bit256], then the transformation from
         * [Tag.Bit256] to [Tag.Int] is done according
         * to [destEncoding]: if it is [tac.Tag.CVLArray.UserArray.ElementEncoding.Signed], then [src] is twos complement unwrapped,
         * if it is [tac.Tag.CVLArray.UserArray.ElementEncoding.Unsigned] then it is safe math promoted. Similarly, if
         * [src] is an [Tag.Int], and [dest] is [Tag.Bit256], then [src] is either safe math narrowed or twos complement encoded,
         * AFTER data bounds have been established.
         *
         * This operation is *very* low level, and does not reason about bitwidths of types that may be stored in larger values.
         */
        fun convertValueTypeToEncoding(
            dest: TACSymbol.Var,
            destEncoding: Tag.CVLArray.UserArray.ElementEncoding,
            src: TACSymbol.Var
        ) : CommandWithRequiredDecls<TACCmd.Spec> {
            val targetTag = dest.tag
            val srcTag = src.tag

            fun identityTransfer() = CommandWithRequiredDecls(listOf(TACCmd.Simple.AssigningCmd.AssignExpCmd(
                lhs = dest,
                rhs = src.asSym()
            )), setOf(dest, src))

            fun err(): Nothing = throw UnsupportedOperationException("Cannot convert from $src to $dest with encoding $destEncoding")

            when(destEncoding) {
                Tag.CVLArray.UserArray.ElementEncoding.Signed -> {
                    return numericCases(src = src, dest = dest, sameTag = ::identityTransfer, intToBit = {
                        val f = TACBuiltInFunction.TwosComplement.Wrap(Tag.Bit256)
                        val sanityVar = TACSymbol.Var("boundsCheck", Tag.Bool).toUnique(".")
                        CommandWithRequiredDecls(
                            listOf(
                                TACCmd.Simple.AssigningCmd.AssignExpCmd(sanityVar, CastToSignedInt(Config.VMConfig.registerBitwidth).safeCastExpr(src)),
                                TACCmd.Simple.AssertCmd(
                                    sanityVar,
                                    "sanity bounds check on cvl to vm encoding failed (signed int elements of a user array)",
                                ),
                                TACCmd.Simple.AssigningCmd.AssignExpCmd(
                                    dest, f.toTACFunctionSym(), listOf(src.asSym())
                                )
                            ), setOf(src, dest, sanityVar))
                    }, bitToInt = {
                        val f = TACBuiltInFunction.TwosComplement.Unwrap(Tag.Bit256)
                        CommandWithRequiredDecls(
                            listOf(
                                TACCmd.Simple.AssigningCmd.AssignExpCmd(
                                    dest, f.toTACFunctionSym(), listOf(src.asSym())
                                )
                        ), setOf(src, dest))
                    }, err = ::err)
                }
                Tag.CVLArray.UserArray.ElementEncoding.Unsigned -> {
                    return numericCases(src = src, dest = dest, sameTag = ::identityTransfer, intToBit = {
                        CastToUnsignedInt(Config.VMConfig.registerBitwidth).compileAssertCast(dest, src,
                            "sanity bounds check on cvl to vm encoding failed (unsigned int elements of a user array)"
                        )
                    }, err = ::err, bitToInt = ::identityTransfer)
                }
                Tag.CVLArray.UserArray.ElementEncoding.Boolean -> {
                    if(targetTag == Tag.Bool) {
                        if(srcTag == Tag.Bool) {
                            return CommandWithRequiredDecls(listOf(TACCmd.Simple.AssigningCmd.AssignExpCmd(
                                lhs = dest,
                                rhs = src.asSym()
                            )), dest, src)
                        } else if(srcTag == Tag.Bit256) {
                            return ExprUnfolder.unfoldPlusOneCmd("boolConvert", expFactory.Ite(
                                expFactory.Eq(
                                    src.asSym(),
                                    TACSymbol.Zero.asSym()
                                ),
                                TACSymbol.False.asSym(),
                                TACSymbol.True.asSym()
                            )) {
                                TACCmd.Simple.AssigningCmd.AssignExpCmd(
                                    lhs = dest,
                                    rhs = it
                                )
                            }.merge(src, dest)
                        }
                    } else if(targetTag == Tag.Bit256) {
                        if(srcTag == Tag.Bool) {
                            return CommandWithRequiredDecls(listOf(TACCmd.Simple.AssigningCmd.AssignExpCmd(
                                lhs = dest,
                                rhs = expFactory.Ite(
                                    src.asSym(),
                                    TACSymbol.One.asSym(),
                                    TACSymbol.Zero.asSym()
                                )
                            )), setOf(src, dest))
                        // :thinking_mspaint:
                        } else if(srcTag == Tag.Bit256) {
                            return ExprUnfolder.unfoldPlusOneCmd("boolConvert", expFactory.Ite(
                                expFactory.Eq(
                                    src.asSym(),
                                    TACSymbol.Zero.asSym()
                                ),
                                TACSymbol.Zero.asSym(),
                                TACSymbol.One.asSym()
                            )) {
                                TACCmd.Simple.AssigningCmd.AssignExpCmd(
                                    lhs = dest,
                                    rhs = it
                                )
                            }.merge(src, dest)

                        }
                    }
                }
            }
            err()
        }

        fun convertValueTypeToEVM(dest: TACSymbol.Var, destType: VMValueTypeDescriptor, src: TACSymbol.Var, srcType: CVLType.PureCVLType): CommandWithRequiredDecls<TACCmd.Simple> =
            when (destType) {
                is VMSignedNumericValueTypeDescriptor -> {
                    val f = TACBuiltInFunction.TwosComplement.Wrap(Tag.Bit256)
                    val sanityVar = TACSymbol.Var("boundsCheck", Tag.Bool).toUnique(".")
                    CommandWithRequiredDecls(
                        listOf(
                            TACCmd.Simple.AssigningCmd.AssignExpCmd(sanityVar, CastToSignedInt(destType.bitwidth).safeCastExpr(src)),
                            TACCmd.Simple.AssertCmd(
                                sanityVar,
                                "sanity bounds check on cvl to vm encoding failed (signed numeric value)",
                            ),
                            TACCmd.Simple.AssigningCmd.AssignExpCmd(
                                dest, f.toTACFunctionSym(), listOf(src.asSym())
                            )
                        ), setOf(src, dest, sanityVar)
                    )
                }
                is EVMTypeDescriptor.BytesK -> {
                    if(destType.bytewidth == Config.VMConfig.registerByteWidth && src.tag == Tag.Int) {
                        CastToUnsignedInt(Config.VMConfig.registerBitwidth).compileAssertCast(dest, src,
                            "sanity bounds check on cvl to vm encoding failed (bytesK)",
                        )
                    } else {
                        check(src.tag == Tag.Bit256 && dest.tag == Tag.Bit256) { "bytesk conversion got bad tags src: ${src.tag}, dest: ${dest.tag}" }
                        CommandWithRequiredDecls(
                            TACCmd.Simple.AssigningCmd.AssignExpCmd(dest, src.asSym()),
                            src, dest
                        )
                    }
                }
                else -> {
                    if (srcType is CVLType.PureCVLType.Primitive.Bool) {
                        listOf(
                            TACCmd.Simple.AssigningCmd.AssignExpCmd(
                                dest, if (dest.tag == Tag.Bool) {
                                    src.asSym()
                                } else {
                                    TACExprFactTypeCheckedOnlyPrimitives.Ite(
                                        src.asSym(),
                                        TACSymbol.lift(BigInteger.ONE).asSym(),
                                        TACSymbol.lift(BigInteger.ZERO).asSym()
                                    )
                                }
                            )
                        ).withDecls(src, dest)
                    } else if(srcType is CVLType.PureCVLType.UserDefinedValueType) {
                        convertValueTypeToEVM(dest, destType, src, srcType.base)
                    } else {
                        CastToUnsignedInt(Config.VMConfig.registerBitwidth).compileAssertCast(dest, src,
                            "sanity bounds check on cvl to vm encoding failed (unsigned int)"
                        )
                    }
                }
            }
    }

    private fun signExtendEVMValue(
        src: TACExpr,
        srcType: VMSignedNumericValueTypeDescriptor
    ) : TACExpr {
        return TACExpr.BinOp.SignExtend(
            o1 = TACSymbol.lift((srcType.bitwidth / 8 ) - 1).asSym(),
            o2 = src
        )
    }

    override fun moveToHook(dest: ITACSymbolVar, destType: CVLType.PureCVLType, src: IHookParameter, srcType: VMValueTypeDescriptor, cb: (ITACCmd) -> ITACCmd): ISimpleOutput {
        val destNarrowed = enarrow(dest)
        val srcNarrowed = enarrow(src) as HookValue.Direct
        return moveValueTypeToCVL(destNarrowed, destType, srcNarrowed.expr, srcType, cb)
    }


    override fun moveFromStorage(dest: ITACSymbolVar, destType: CVLType.PureCVLType, src: ITACSymbolVar, srcType: VMValueTypeDescriptor, cb: (ITACCmd) -> ITACCmd): ISimpleOutput {
        val destNarrowed = enarrow(dest)
        val srcNarrowed = enarrow(src)
        return moveValueTypeToCVL(destNarrowed, destType, srcNarrowed.asSym(), srcType, cb)
    }

    override fun constrainNumericOutput(
        currConverter: IConverterOutput,
        cvlVar: ITACSymbolVar,
        srcVMType: EVMTypeDescriptor.EVMNumericType
    ): ISimpleOutput {
        val currentConv = currConverter as CommandWithRequiredDecls<TACCmd.Spec>
        val outVar = enarrow(cvlVar)
        check(outVar.tag == Tag.Int) {
            "Converting type $srcVMType to a CVL variable that should hold it, that must be a CVL (U)IntK, which has tag Int, but instead" +
                " we got ${outVar.tag}?"
        }
        val bounds = when(srcVMType) {
            is EVMTypeDescriptor.IntK -> {
                ExprUnfolder.unfoldPlusOneCmd("boundsConstraint", TACExpr.BinBoolOp.LAnd(
                    TACExpr.BinRel.Ge(
                        outVar.asSym(),
                        SignUtilities.minSignedValueOfBitwidth(srcVMType.bitwidth).asTACExpr(Tag.Int)
                    ),
                    TACExpr.BinRel.Le(
                        outVar.asSym(),
                        SignUtilities.maxSignedValueOfBitwidth(srcVMType.bitwidth).asTACExpr(Tag.Int)
                    )
                )
                ) {
                    TACCmd.Simple.AssumeCmd(
                        it.s, "constrainNumericOutput for Int"
                    )
                }
            }
            is EVMTypeDescriptor.UIntK -> {
                ExprUnfolder.unfoldPlusOneCmd("boundsConstraint", TACExpr.BinRel.Le(
                    outVar.asSym(),
                    SignUtilities.maxUnsignedValueOfBitwidth(srcVMType.bitwidth).asTACExpr
                )) {
                    TACCmd.Simple.AssumeCmd(it.s, "constrainNumericOutput for UInt")
                }
            }
        }
        return currentConv.merge(bounds)
    }

    override fun movePrimitiveToPrimitive(
        fromType: CVLType.PureCVLType,
        fromVar: ITACSymbolVar,
        toType: EVMTypeDescriptor.EVMValueType,
        toVar: ITACSymbolVar
    ): ISimpleOutput {
        return ValueConverters.convertValueTypeToEVM(dest = enarrow(toVar), destType = toType, src = enarrow(fromVar), srcType = fromType)
    }

    private fun moveValueTypeToCVL(
        dest: TACSymbol.Var,
        destType: CVLType.PureCVLType,
        src: TACExpr,
        srcType: VMValueTypeDescriptor,
        cb: (ITACCmd) -> ITACCmd
    ): ISimpleOutput  {

        val srcTag = src.tagAssumeChecked
        // it turns out bools aren't always tagged as bools, should I be using the bool rewriter, or should I just get
        // the tag from the expression?
        val srcExp = when(srcType) {
            is VMSignedNumericValueTypeDescriptor -> {
                check(srcTag == Tag.Bit256)
                signExtendEVMValue(
                    src = src,
                    srcType = srcType
                )
            }
            else -> src
        }
        val tmp = dest.updateTagUnique(srcTag, ".")
        return listOf(cb(TACCmd.Simple.AssigningCmd.AssignExpCmd(tmp, srcExp)) as TACCmd.Simple)
            .withDecls(src.getFreeVarsAsSyms().map { sym -> sym.s }, dest)
            .merge(ValueConverters.convertValueTypeToCVL(dest, destType, tmp, srcType))
    }

    override fun movePrimitiveToInternalReturnLocation(
        dest: EVMTypeDescriptor.EVMInternalSummaryReturn,
        destEncoding: Tag.CVLArray.UserArray.ElementEncoding,
        src: ICVLDataInput,
        srcType: CVLType.PureCVLType,
        cb: (ITACCmd) -> ITACCmd
    ): IProgramOutput {
        val srcVar = enarrow(src)
        return (dest as EVMPrimitiveReturnData).movePrimitiveValue(srcVar, destEncoding)
    }

    private fun EVMTypeDescriptor.unwrapUserTypes() : EVMTypeDescriptor = if(this is EVMTypeDescriptor.UserDefinedValueType) {
        this.baseType.unwrapUserTypes()
    } else {
        this
    }

    override fun movePrimitiveFromInternalArg(
        dest: ICVLDataOutput,
        destType: CVLType.PureCVLType,
        src: EVMTypeDescriptor.EVMInternalSummaryInput,
        srcType: VMValueTypeDescriptor,
        cb: (ITACCmd) -> ITACCmd
    ): IProgramOutput {
        val baseSrcType = (srcType as EVMTypeDescriptor).unwrapUserTypes()
        val srcMethod = src as EVMInternalArgData
        return srcMethod.expectPrimitive(enarrow(dest), (srcType as EVMTypeDescriptor.EVMValueType).expectedBufferEncoding) { srcVar ->
            val tmp = srcVar.toUnique(".")
            val rhs = when (baseSrcType) {
                is EVMTypeDescriptor.UIntK -> {
                    if(baseSrcType.bitwidth < Config.VMConfig.registerBitwidth) {
                        TACExpr.BinOp.BWAnd(
                            o1 = srcVar.asSym(),
                            o2 = SignUtilities.maxUnsignedValueOfBitwidth(baseSrcType.bitwidth).asTACExpr
                        )
                    } else {
                        srcVar.asSym()
                    }
                }
                is EVMTypeDescriptor.IntK -> {
                    if(baseSrcType.bitwidth < Config.VMConfig.registerBitwidth) {
                        signExtendEVMValue(src = srcVar.asSym(), srcType = baseSrcType)
                    } else {
                        srcVar.asSym()
                    }
                }
                is EVMTypeDescriptor.bool -> {
                    srcVar.asSym()
                }
                else -> srcVar.asSym()
            }
            val assignCmd = cb(TACCmd.Simple.AssigningCmd.AssignExpCmd(tmp, rhs)) as TACCmd.Simple
            tmp to CommandWithRequiredDecls(listOf(
                assignCmd
            ), setOf(tmp, srcVar))
        }
    }

    override fun moveHashedArray(fromVar: IHookParameter, toVar: ITACSymbolVar, cb: (ITACCmd) -> ITACCmd): ISimpleOutput {
        val fromVarNarrowed = enarrow(fromVar) as HookValue.Direct
        val toVarNarrowed = enarrow(toVar)

        /**
         * Holds the the non-deterministic buffer which we will constrain to hash the same as [fromVar]
         */
        val havocBuffer = TACKeyword.TMP(Tag.ByteMap, "!nondetBuffer").toUnique("!")

        /**
         * The length of this non-deterministic buffer.
         */
        val havocLength = TACKeyword.TMP(Tag.Bit256, "!nondetLength").toUnique("!")


        /**
         * The result of hash(havocBuffer, havocLength)
         */
        val hashResult = TACKeyword.TMP(Tag.Bit256, "!comp").toUnique("!")


        /**
         * Compute hash(havocBuffer, havocLength)
         */
        val hashCommand = TACCmd.Simple.AssigningCmd.AssignSha3Cmd(
            lhs = hashResult,
            op1 = TACSymbol.Zero,
            op2 = havocLength,
            memBaseMap = havocBuffer
        )


        /**
         * The result of hash(havocBuffer, havocLength) == [fromVarNarrowed] (which we will assume to be true)
         */
        val compResult = TACKeyword.TMP(Tag.Bool, "!comp").toUnique("!")


        /**
         * compResult = [fromVarNarrowed] == hashResult (the reversed hash from above)
         */
        val assignCmd = TACCmd.Simple.AssigningCmd.AssignExpCmd(
            lhs = compResult,
            rhs = TACExpr.BinRel.Eq(
                hashResult.asSym(),
                fromVarNarrowed.expr
            )
        )

        /**
         * Assume compResult to be true
         */
        val assumeCmd = TACCmd.Simple.AssumeCmd(cond = compResult, msg = "moveHashedArray")

        /**
         * Copy all data, i.e. the [havocBuffer] and [havocLength] into the [toVarNarrowed] array for the user to access after applying the hook.
         */
        val arrayCopy = TACCmd.CVL.ArrayCopy(
            dst = TACCmd.CVL.BufferPointer(
                TACSymbol.Zero,
                buffer = TACCmd.CVL.Buffer.CVLBuffer(
                    root = toVarNarrowed,
                    dataPath = listOf(tac.DataField.ArrayData)
                )
            ),
            src = TACCmd.CVL.BufferPointer(
                TACSymbol.Zero,
                buffer = TACCmd.CVL.Buffer.EVMBuffer(
                    t = havocBuffer
                )
            ),
            logicalLength =  havocLength,
            elementSize = 1
        )
        val arrayLenSet = TACCmd.CVL.SetArrayLength(
            len = havocLength,
            lhs = toVarNarrowed
        )

        return listOf( cb(hashCommand) as TACCmd.Simple, cb(assignCmd) as TACCmd.Simple, cb(assumeCmd) as TACCmd.Simple,arrayCopy, arrayLenSet)
            .withDecls(fromVarNarrowed.expr.getFreeVars(), toVarNarrowed, havocBuffer, havocLength, hashResult, compResult)
    }

    override fun moveStructToInternalReturn(
        dest: EVMTypeDescriptor.EVMInternalSummaryReturn,
        src: ICVLDataInput,
        fields: List<EVMTypeDescriptor.StructFieldConverter<ICVLDataInput, IInternalSummaryReturn>>,
        cb: (ITACCmd) -> ITACCmd
    ): IProgramOutput {
        require(dest is EVMReferenceReturnData)
        require(src is CVLDataInput)
        val reader = src.struct<CVLTACProgram>()
        return dest.allocateAndAssign(EVMReferenceReturnData::allocateStruct, fields.size) { ind, _, fld ->
            val conv = fields[ind]
            val fieldConversion = reader.readField(conv.name) { fieldReader ->
                conv.conv.convertTo(
                    fromVar = fieldReader,
                    toVar = fld,
                    cb = cb
                ) as CVLTACProgram
            }
            fieldConversion
        }
    }

    override fun encodeWithDataLayout(
        fromVar: ICVLDataInput,
        toVar: IExternalInputBuffer,
        layout: EncoderLayout,
        cb: (ITACCmd) -> ITACCmd
    ): IExternalEncodeOutput {
        val target = fromVar as CVLDataInput
        val enc = toVar as LowLevelEncoder
        return EncodingInterpreter.encode(
            l = enc,
            fromVar = target,
            layout = layout
        )
    }

    override fun decodeWithDataLayout(
        fromVar: IExternalOutputBuffer,
        toVar: ICVLDataOutput,
        encodingLayout: DecoderLayout,
        cb: (ITACCmd) -> ITACCmd
    ): IProgramOutput {
        return DecodingInterpreter.decode(
            target = toVar as CVLDataOutput,
            layout = encodingLayout,
            l = fromVar as LowLevelDecoder
        )
    }

    override fun moveArrayFromInternalArg(
        fromVar: EVMTypeDescriptor.EVMInternalSummaryInput,
        toVar: ICVLDataOutput,
        eSize: Int,
        cb: (ITACCmd) -> ITACCmd,
        elemConversion: InternalElementConverter<IInternalSummaryInput, ICVLDataOutput>
    ): IProgramOutput {
        return (fromVar as EVMInternalArgData).expectArray(enarrow(toVar), eSize) { offset, part, writer, gen ->
            elemConversion.toValue({ conv ->
                writer.foreachElem { ind, elemWriter ->
                    val elemOffset = TACKeyword.TMP(Tag.Bit256, "!gen")
                    val addition = safeAdd(elemOffset, (ind * EVM_WORD_SIZE_INT).asTACSymbol(), offset)
                    val pointer = TACKeyword.TMP(Tag.Bit256, "!pointer")
                    val prefix = addition.merge(CommandWithRequiredDecls(listOf(
                        TACCmd.Simple.AssigningCmd.ByteLoad(
                            lhs = pointer,
                            base = part,
                            loc = elemOffset
                        )
                    ), setOf(pointer, part, elemOffset)))
                    val elemReader = gen(pointer)
                    (conv.convertTo(fromVar = elemReader, toVar = elemWriter, cb) as CVLTACProgram).prependToBlock0(prefix)
                }
            }, { _ ->
                writer.longCopy(
                    TACCmd.CVL.BufferPointer(
                    offset = offset,
                    buffer = TACCmd.CVL.Buffer.EVMBuffer(part)
                ))
            })
        }
    }

    override fun moveArrayToInternalReturn(
        toVar: EVMTypeDescriptor.EVMInternalSummaryReturn,
        fromVar: ICVLDataInput,
        eSize: Int,
        cb: (ITACCmd) -> ITACCmd,
        elemConverter: InternalElementConverter<ICVLDataInput, IInternalSummaryReturn>
    ): IProgramOutput {
        val input = enarrow(fromVar)
        return input.dynamicArray { arrayLen, context, reader ->
            (toVar as EVMReferenceReturnData).allocateAndAssign(EVMReferenceReturnData::allocateArray, arrayLen, eSize) { loc: TACSymbol.Var, part: TACSymbol.Var, gen ->
                elemConverter.toValue({ conv ->
                    reader.foreachElem { ind, elemReader ->
                        val offset = (ind * EVM_WORD_SIZE_INT).toBigInteger()
                        val newLoc = TACKeyword.TMP(Tag.Bit256, "!res")
                        val offsetComp = safeAdd(newLoc, offset.asTACSymbol(), loc)
                        val elemConversion = conv.convertTo(
                            elemReader, gen(newLoc), cb
                        ) as CVLTACProgram
                        elemConversion.prependToBlock0(offsetComp)
                    }
                }, {
                    reader.toOutput { pointer ->
                        CommandWithRequiredDecls(listOf(
                            TACCmd.CVL.ArrayCopy(
                                elementSize = eSize,
                                logicalLength = arrayLen,
                                src = pointer,
                                dst = TACCmd.CVL.BufferPointer(loc, TACCmd.CVL.Buffer.EVMBuffer(part)),
                            )
                        ), setOf(arrayLen, loc, part) + pointer.getReferencedSyms()).toProg("copy", context)
                    }
                })
            }
        }
    }

    /**
     * Simply delegates to the concrete implementation of [EVMInternalArgData]
     */
    override fun moveStructFromInternalArg(
        fromVar: EVMTypeDescriptor.EVMInternalSummaryInput,
        toVar: ICVLDataOutput,
        fieldNameToOrdinal: Map<String, Int>,
        fields: List<EVMTypeDescriptor.StructFieldConverter<IInternalSummaryInput, ICVLDataOutput>>,
        cb: (ITACCmd) -> ITACCmd
    ): IProgramOutput {
        return (fromVar as EVMInternalArgData).expectStruct(enarrow(toVar), fieldNameToOrdinal) { ind, fld, arg ->
            fields[ind].conv.convertTo(
                toVar = fld,
                fromVar = arg,
                cb = cb
            ) as CVLTACProgram
        }
    }

    override fun moveStaticArrayToInternalReturn(
        fromVar: ICVLDataInput,
        toVar: EVMTypeDescriptor.EVMInternalSummaryReturn,
        numFields: BigInteger,
        elementConverter: ITypeDescriptorConverter<IConverterOutput, ICVLDataInput, IInternalSummaryReturn>,
        cvlElementType: CVLType.PureCVLType,
        cb: (ITACCmd) -> ITACCmd
    ): IProgramOutput {
        val source = enarrow(fromVar)
        return source.staticArray(numFields) { reader ->
            (toVar as EVMReferenceReturnData).allocateAndAssign(EVMReferenceReturnData::allocateStruct, numFields.intValueExact()) { ind: Int, _: BigInteger, forElementOutput: EVMInternalReturnData ->
                reader.readElem(ind) { elemReader ->
                    elementConverter.convertTo(
                        fromVar = elemReader,
                        toVar = forElementOutput,
                        cb
                    ) as CVLTACProgram
                }
            }
        }
    }

    override fun moveStaticArrayFromInternalArg(
        fromVar: EVMTypeDescriptor.EVMInternalSummaryInput,
        toVar: ICVLDataOutput,
        elementType: CVLType.PureCVLType,
        numElements: BigInteger,
        elementConverter: ITypeDescriptorConverter<IConverterOutput, IInternalSummaryInput, ICVLDataOutput>,
        cb: (ITACCmd) -> ITACCmd
    ): IProgramOutput {
        return (fromVar as EVMInternalArgData).expectStaticArray(
            numElements = numElements,
            elementTag = elementType.toTag(),
            output = enarrow(toVar)
        ) { fld, arg ->
            elementConverter.convertTo(arg, fld, cb) as CVLTACProgram
        }
    }

    override fun movePrimitiveToMappingKey(
        fromVar: ITACSymbolVar,
        toVar: IStorageMapping,
        encoding: Tag.CVLArray.UserArray.ElementEncoding
    ): IMappingKeyOutput {
        return (toVar as EVMStorageMapping).hashPrimitiveKey(
            fromVar as TACSymbol.Var,
            encoding
        )
    }

    override fun reverseHashBlobStorageKey(fromVar: ITACSymbolVar, toVar: IStorageMapping): IMappingKeyOutput {
        return (toVar as EVMStorageMapping).hashBlobKey(fromVar as TACSymbol.Var)
    }

    override fun movePackedBytesToStorageKey(fromVar: ITACSymbolVar, toVar: IStorageMapping): IMappingKeyOutput {
        return (toVar as EVMStorageMapping).hashBytesKey(fromVar as TACSymbol.Var)
    }

    override fun moveCalldataArgToInternalArg(
        fromVar: EVMTypeDescriptor.EVMInternalSummaryInput,
        toVar: ICVLDataOutput,
        layout: DecoderLayout
    ): IProgramOutput {
        return (fromVar as EVMInternalCalldataArg).decodeTo(
            enarrow(toVar),
            layout = layout
        )
    }
}
