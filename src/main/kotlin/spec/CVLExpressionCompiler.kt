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
import analysis.maybeAnnotation
import analysis.merge
import bridge.EVMExternalMethodInfo
import com.certora.collect.*
import config.Config
import config.Config.MaxStringSizeForComparison
import config.realOpt
import datastructures.stdcollections.*
import instrumentation.transformers.FoundryCheatcodes
import evm.*
import log.*
import report.CVTAlertReporter
import report.CVTAlertSeverity
import report.CVTAlertType
import spec.ProgramGenMixin.Companion.andThen
import spec.ProgramGenMixin.Companion.emptyProgram
import spec.converters.*
import spec.converters.repr.*
import spec.cvlast.*
import spec.cvlast.StorageComparisonChecker.isStorageOperand
import spec.cvlast.transformations.GhostTypeRewriter
import spec.cvlast.typedescriptors.EVMTypeDescriptor
import spec.cvlast.typedescriptors.FromVMContext
import tac.MetaKey
import tac.MetaMap
import tac.Tag
import utils.*
import utils.CollectingResult.Companion.lift
import utils.CollectingResult.Companion.safeForce
import vc.data.*
import vc.data.ParametricInstantiation.Companion.andThen
import vc.data.ParametricInstantiation.Companion.bind
import vc.data.ParametricInstantiation.Companion.flatten
import vc.data.ParametricInstantiation.Companion.getSimple
import vc.data.ParametricInstantiation.Companion.mergeMany
import vc.data.ParametricInstantiation.Companion.toSimple
import vc.data.ParametricMethodInstantiatedCode.addSink
import vc.data.ParametricMethodInstantiatedCode.merge
import vc.data.ParametricMethodInstantiatedCode.mergeIf
import vc.data.ParametricMethodInstantiatedCode.mergeProgs
import vc.data.TACMeta.CVL_DISPLAY_NAME
import vc.data.TACMeta.CVL_EXP
import vc.data.TACMeta.CVL_TYPE
import vc.data.TACMeta.CVL_USER_DEFINED_ASSERT
import vc.data.TACMeta.CVL_VAR
import vc.data.TACProgramCombiners.andThen
import vc.data.TACSymbol.Var.Companion.KEYWORD_ENTRY
import vc.data.compilation.storage.InternalCVLCompilationAPI
import vc.data.tacexprutil.CastToUnsignedInt
import vc.data.tacexprutil.DefaultTACExprTransformer
import vc.data.tacexprutil.TACUnboundedHashingUtils
import vc.data.tacexprutil.getTACCastExpressionHelpers
import vc.gen.TACSimpleSimple
import java.io.Serializable
import java.math.BigInteger
import java.util.stream.Collectors
import kotlin.streams.asSequence

private val logger = Logger(LoggerTypes.SPEC)

class CVLExpressionCompiler(
    private val cvlCompiler: CVLCompiler,
    private val allocatedTACSymbols: TACSymbolAllocation,
    private val compilationEnvironment: CVLCompiler.CompilationEnvironment
) {

    internal fun <T : TACCmd.Spec> CommandWithRequiredDecls<T>.toProgWithCurrEnv(id: String) =
        this.toProg(id, compilationEnvironment)

    inner class CompiledProgramWithOut(val prog: ParametricInstantiation<CVLTACProgram>, val out: TACSymbol.Var) {

        operator fun component1() = prog
        operator fun component2() = out

        fun bind(f: (TACSymbol.Var) -> CompiledProgramWithOut): CompiledProgramWithOut {
            val (p2, out) = f(this.out)
            return CompiledProgramWithOut(mergeProgs(this.prog, p2), out)
        }

        fun bindCmd(f: (TACSymbol.Var) -> CommandWithRequiredDecls<TACCmd.Spec>): ParametricInstantiation<CVLTACProgram> {
            return this.bindProg {
                f(it).toProgWithCurrEnv("next")
            }
        }

        fun bindProg(f: (TACSymbol.Var) -> CVLTACProgram): ParametricInstantiation<CVLTACProgram> {
            return this.bindParam { it: TACSymbol.Var ->
                f(it).asSimple()
            }
        }

        fun bindParam(f: (TACSymbol.Var) -> ParametricInstantiation<CVLTACProgram>): ParametricInstantiation<CVLTACProgram> {
            val p2 = f(this.out)
            return mergeProgs(this.prog, p2)
        }

        fun <T : Serializable> withMeta(key: MetaKey<T>, v: T): CompiledProgramWithOut {
            val outWithMeta = out.withMeta(key, v)
            return CompiledProgramWithOut(prog = prog, out = outWithMeta)
        }
        fun withMeta(key: MetaKey<Nothing>): CompiledProgramWithOut {
            val outWithMeta = out.withMeta(key)
            return CompiledProgramWithOut(prog = prog, out = outWithMeta)
        }
    }

    private fun List<CompiledProgramWithOut>.unzip() = this.map { it.prog to it.out }.unzip()

    private val storageAccessCompiler by lazy {
        val allocation = { type: CVLType.PureCVLType ->
            allocatedTACSymbols.generateTransientUniqueCVLParam(cvlCompiler.cvl.symbolTable, "temp", type)
        }

        StorageAccessCompiler(
            expCompiler = this,
            cvlCompiler.scene,
            compilationEnvironment.baseCallId,
            allocation,
            cvlCompiler::typeConstraints,
        )
    }

    private fun compileUnary(
        out: TACSymbol.Var,
        o: CVLExp,
        exprConstructor: (TACExpr) -> TACExpr,
        exp: CVLExp
    ) = compileExp(o).let { oCompiled ->
        TACCmd.Simple.AssigningCmd.AssignExpCmd(out, oCompiled.out.asSym(), exprConstructor).plusMeta(
            CVL_EXP,
            CVLExpToTACExprMeta.UnaryCVLExp(
                exp = exp,
                oOut = oCompiled.out,
                oExp = o
            )
        ).let { cmd ->
            oCompiled.prog.addSink(CommandWithRequiredDecls(cmd, out, oCompiled.out), compilationEnvironment)
        }
    }

    private fun maskLowerKBits(outVar: TACSymbol.Var, inVar: TACSymbol.Var, k: Int): CommandWithRequiredDecls<TACCmd.Spec> =
        CommandWithRequiredDecls(
            TACCmd.Simple.AssigningCmd.AssignExpCmd(
                outVar,
                cvlCompiler.exprFact.BWAnd(inVar.asSym(), TACSymbol.lift(MASK_SIZE(k)).asSym())
            ), outVar
        )

    private fun maskUpperKBits(outVar: TACSymbol.Var, inVar: TACSymbol.Var, k: Int): CommandWithRequiredDecls<TACCmd.Spec> =
        CommandWithRequiredDecls(
            TACCmd.Simple.AssigningCmd.AssignExpCmd(
                outVar,
                cvlCompiler.exprFact.BWAnd(
                    inVar.asSym(),
                    TACSymbol.lift(
                        Config.VMConfig.maxUint - MASK_SIZE(Config.VMConfig.registerBitwidth - k)
                    ).asSym())
            ), outVar
        )

    private fun doWrapping(inVar: TACSymbol.Var, t: CVLType.PureCVLType): Pair<TACSymbol.Var, CommandWithRequiredDecls<TACCmd.Spec>> {
        val outVar = inVar.copy(tag = Tag.Bit256).toUnique("!")
        val actualType = when(t){
            is CVLType.PureCVLType.UserDefinedValueType -> t.base
            else -> t
        }
        return if (actualType is CVLType.PureCVLType.Primitive.IntK ||
            (actualType is CVLType.PureCVLType.Primitive.NumberLiteral && actualType.n < BigInteger.ZERO)) {
            outVar to CommandWithRequiredDecls<TACCmd.Spec>(
                listOf(TACCmd.Simple.AssigningCmd.AssignExpCmd(
                    // DSA
                    outVar,
                    cvlCompiler.exprFact.Apply(
                        TACBuiltInFunction.TwosComplement.Wrap(Tag.Bit256).toTACFunctionSym(),
                        listOf(inVar.asSym())
                    )
                )), setOf(inVar, outVar))
        } else if (actualType is CVLType.PureCVLType.Primitive.BytesK) {
            inVar to CommandWithRequiredDecls()
        } else {
            check((actualType is CVLType.PureCVLType.Primitive.NumberLiteral && actualType.n >= BigInteger.ZERO)
                || actualType is CVLType.PureCVLType.Primitive.UIntK
                || actualType is CVLType.PureCVLType.Primitive.AccountIdentifier) {
                "unexpected type $t asked to be encoded as two's complement"
            }
            outVar to CastToUnsignedInt(Config.VMConfig.registerBitwidth).compileAssertCast(outVar, inVar)
        }
    }

    private fun doUnwrapping(outVar: TACSymbol.Var, inVar: TACSymbol.Var): CommandWithRequiredDecls<TACCmd.Spec> =
        CommandWithRequiredDecls<TACCmd.Spec>(
            listOf(TACCmd.Simple.AssigningCmd.AssignExpCmd(
                outVar,
                cvlCompiler.exprFact.Apply(
                    TACBuiltInFunction.TwosComplement.Unwrap(Tag.Bit256).toTACFunctionSym(),
                    listOf(inVar.asSym())
                )
            )), setOf(outVar, inVar)
        )


    private fun nopPostOpManipulation(outVar: TACSymbol.Var, inVar: TACSymbol.Var, @Suppress("UNUSED_PARAMETER") t: CVLType.PureCVLType.Primitive): CommandWithRequiredDecls<TACCmd.Spec> =
        CommandWithRequiredDecls(TACCmd.Simple.AssigningCmd.AssignExpCmd(outVar, inVar), outVar, inVar)

    /**
     * Compiles any bitwise operation whose left and right hand operands are both considered as bitvectors (&, |, ^)
     *
     * If an operand is of a signed type, this function handles converting the operand from arbitrary precision integer
     * form to two's complement bitvector form before performing the bitwise operation.
     *
     * If the return type is a signed integer the result type will be converted back to arbitrary precision form from
     * two's complement bitvector form. Note, no matter the int size (int8, int16 etc.), the higher order bits will
     * always match the kth (sign) bit of the intk and so the output will always fit inside the resulting intk type.
     *
     * If the returned type is a uintk, mask to preserve lower k bits.
     */
    private fun compileCommutativeBinaryBitwiseOp(
        out: TACSymbol.Var,
        exp: CVLExp.BinaryExp,
        exprConstructor: (TACExpr, TACExpr) -> TACExpr
    ): ParametricInstantiation<CVLTACProgram> =
        compileBinaryBitwiseOp(
            out,
            exp, exprConstructor,
            preOpLhsManipulation = ::doWrapping,
            preOpRhsManipulation = ::doWrapping,
            postOpIntManipulation = { outVar, inVar, _ -> doUnwrapping(outVar, inVar) },

            // The masking is required for the UintK and BytesK cases because we over approximate the bw operations so
            // the result may contain irrelevant bits set.
            // For example, bwand is never supposed to flip a bit from 0 to 1, but due to the over approximation this could
            // happen.
            postOpUIntManipulation = { outVar, inVar, t ->
                maskLowerKBits(outVar, inVar, t.k)
            },
            postOpBytesKManipulation = { outVar, inVar, t ->
                maskUpperKBits(outVar, inVar, t.bitWidth)
            }
        )

    private fun compileBinaryBitwiseOp(
        out: TACSymbol.Var,
        exp: CVLExp.BinaryExp,
        exprConstructor: (TACExpr, TACExpr) -> TACExpr,
        preOpLhsManipulation: (inVar: TACSymbol.Var, CVLType.PureCVLType) -> Pair<TACSymbol.Var, CommandWithRequiredDecls<TACCmd.Spec>>,
        preOpRhsManipulation: (inVar: TACSymbol.Var, CVLType.PureCVLType) -> Pair<TACSymbol.Var, CommandWithRequiredDecls<TACCmd.Spec>>,
        postOpIntManipulation: (TACSymbol.Var, TACSymbol.Var, CVLType.PureCVLType.Primitive.IntK) -> CommandWithRequiredDecls<TACCmd.Spec>,
        postOpUIntManipulation: (TACSymbol.Var, TACSymbol.Var, CVLType.PureCVLType.Primitive.UIntK) -> CommandWithRequiredDecls<TACCmd.Spec>,
        postOpBytesKManipulation: (TACSymbol.Var, TACSymbol.Var, CVLType.PureCVLType.Primitive.BytesK) -> CommandWithRequiredDecls<TACCmd.Spec>
    ): ParametricInstantiation<CVLTACProgram> {
        compileConstantBinary(out, exp)?.also { return it }

        val lCompiled = compileExp(exp.l)
        val rCompiled = compileExp(exp.r)
        val lType = exp.l.getOrInferPureCVLType()
        val rType = exp.r.getOrInferPureCVLType()
        val outType = exp.getPureCVLType()
        val (lwrapped, preOpLhsManipulationCommands) = preOpLhsManipulation(lCompiled.out, lType)
        val (rwrapped, preOpRhsManipulationCommands) = preOpRhsManipulation(rCompiled.out, rType)

        val preOpManipulation = CommandWithRequiredDecls.mergeMany(listOf(
            preOpLhsManipulationCommands,
            // using this function for shift ops is okay because rhs is always UIntK so this wrapping is a NOP
            preOpRhsManipulationCommands
        ))

        val outBitvector = out.copy(tag = Tag.Bit256).toUnique("!")

        val bitwiseOpCommands = CommandWithRequiredDecls<TACCmd.Spec>(
            TACCmd.Simple.AssigningCmd.AssignExpCmd(outBitvector, lwrapped, rwrapped, exprConstructor).plusMeta(
                CVL_EXP,
                CVLExpToTACExprMeta.BinaryCVLExp(
                    exp = exp,
                    o1Out = lCompiled.out,
                    o1Exp = exp.l,
                    o2Out = rCompiled.out,
                    o2Exp = exp.r
                )
            ),
            outBitvector, lwrapped, rwrapped
        )

        val postOpManipulation = if (outType is CVLType.PureCVLType.Primitive.IntK) {
            postOpIntManipulation(out, outBitvector, outType)
        } else if (outType is CVLType.PureCVLType.Primitive.UIntK) {
            postOpUIntManipulation(out, outBitvector, outType)
        } else if (outType is CVLType.PureCVLType.Primitive.BytesK) {
            postOpBytesKManipulation(out, outBitvector, outType)
        } else if (outType is CVLType.PureCVLType.Primitive.NumberLiteral) {
            nopPostOpManipulation(out, outBitvector, outType as CVLType.PureCVLType.Primitive)
        } else {
            throw IllegalArgumentException("received bad result type $outType")
        }

        return mergeProgs(lCompiled.prog, rCompiled.prog).addSink(
            CommandWithRequiredDecls.mergeMany(
                listOf(
                    preOpManipulation,
                    bitwiseOpCommands,
                    postOpManipulation
                )
            ),
            compilationEnvironment
        )
    }

    private fun compileBinary(
        out: TACSymbol.Var,
        exp: CVLExp,
        l: CVLExp,
        r: CVLExp,
        exprConstructor: (TACExpr, TACExpr) -> TACExpr,
    ) = compileExp(l).let { lCompiled ->
        compileExp(r).let { rCompiled ->
            TACCmd.Simple.AssigningCmd.AssignExpCmd(out, lCompiled.out, rCompiled.out, exprConstructor).plusMeta(
                CVL_EXP,
                CVLExpToTACExprMeta.BinaryCVLExp(
                    exp = exp,
                    o1Out = lCompiled.out,
                    o1Exp = l,
                    o2Out = rCompiled.out,
                    o2Exp = r
                )
            ).let { cmd ->
                mergeProgs(lCompiled.prog, rCompiled.prog).addSink(
                    CommandWithRequiredDecls(
                        cmd, out, lCompiled.out, rCompiled.out
                    ),
                    compilationEnvironment
                )
            }
        }
    }

    private fun compileBinary(
        out: TACSymbol.Var,
        exp: CVLExp.BinaryExp,
        exprConstructor: (TACExpr, TACExpr) -> TACExpr,
    ) : ParametricInstantiation<CVLTACProgram> =
        compileConstantBinary(out, exp) ?:
        compileBinary(out, exp, exp.l, exp.r, exprConstructor)

    private fun compileBinary(
        out: TACSymbol.Var,
        exp: CVLExp.RelopExp,
        exprConstructor: (TACExpr, TACExpr) -> TACExpr,
    ) : ParametricInstantiation<CVLTACProgram> =
        compileBinary(out, exp, exp.l, exp.r, exprConstructor)

    private fun compileConstantBinary(
        out: TACSymbol.Var,
        exp: CVLExp.BinaryExp,
    ) : ParametricInstantiation<CVLTACProgram>? {
        val type = exp.getPureCVLType()
        if (type is CVLType.PureCVLType.Primitive.NumberLiteral) {
            check(exp.l.getCVLType() is CVLType.PureCVLType.Primitive.NumberLiteral && exp.r.getCVLType() is CVLType.PureCVLType.Primitive.NumberLiteral) {
                "A binary operation should only be typed as a constant if both its operands are constants"
            }
            return getSimple(compileNumberLit(out, CVLExp.Constant.NumberLit(type.n, exp.tag), type))
        }
        return null
    }

    private fun compileCondExp(
        out: TACSymbol.Var,
        exp: CVLExp.CondExp
    ): ParametricInstantiation<CVLTACProgram> {
        return compileExp(exp.c).let { c ->
            compileExp(exp.e1).let { e1 ->
                compileExp(exp.e2).let { e2 ->
                    val tacProgram =
                        if (exp.tag.annotation == NeedsShortCiruiting) {
                            mergeIf(
                                c.prog,
                                TACCmd.Simple.JumpiCmd(e1.prog.getHead(), e2.prog.getHead(), c.out),
                                e1.prog, e2.prog
                            )
                        } else {
                            // There are no side-effects to either branch of this condition, so don't short-circuit
                            mergeProgs(c.prog, mergeProgs(e1.prog, e2.prog))
                        }

                    val iteCmd = TACCmd.Simple.AssigningCmd.AssignExpCmd(
                        out, c.out.asSym(), e1.out.asSym(), e2.out.asSym(),
                        cvlCompiler.exprFact::Ite
                    ).plusMeta(
                        CVL_EXP,
                        CVLExpToTACExprMeta.TernaryCVLExp(
                            exp = exp,
                            o1Out = c.out,
                            o1Exp = exp.c,
                            o2Out = e1.out,
                            o2Exp = exp.e1,
                            o3Out = e2.out,
                            o3Exp = exp.e2
                        )
                    )

                    tacProgram.addSink(
                        CommandWithRequiredDecls(iteCmd, out, c.out, e1.out, e2.out),
                        compilationEnvironment
                    )
                }
            }
        }
    }

    private fun compileAddExp(
        out: TACSymbol.Var,
        exp: CVLExp.BinaryExp.AddExp
    ) = compileBinary(
        out,
        exp,
        cvlCompiler.exprFact::IntAdd,
    )

    private fun compileSubExp(
        out: TACSymbol.Var,
        exp: CVLExp.BinaryExp.SubExp
    ) = compileBinary(
        out,
        exp,
        cvlCompiler.exprFact::IntSub,
    )

    private fun compileConstantUnary(
        out: TACSymbol.Var,
        exp: CVLExp.UnaryExp,
    ) : ParametricInstantiation<CVLTACProgram>? {
        val type = exp.getPureCVLType()
        if (type is CVLType.PureCVLType.Primitive.NumberLiteral) {
            check(exp.e.getCVLType() is CVLType.PureCVLType.Primitive.NumberLiteral) {
                "A binary operation should only be typed as a constant if both its operands are constants"
            }
            return getSimple(compileNumberLit(out, CVLExp.Constant.NumberLit(type.n, exp.tag), type))
        }
        return null
    }

    private fun compileUnaryMinusExp(
        out: TACSymbol.Var,
        exp: CVLExp.UnaryExp.UnaryMinusExp
    ) = compileConstantUnary(out, exp) ?: compileSubExp(
        out,
        CVLExp.BinaryExp.SubExp(
            CVLExp.Constant.NumberLit(
                BigInteger.ZERO,
                CVLExpTag.transient(CVLType.PureCVLType.Primitive.NumberLiteral(BigInteger.ZERO))
            ),
            exp.e,
            CVLExpTag.transient(CVLType.PureCVLType.Primitive.Mathint)
        )
    )

    private fun compileMulExp(
        out: TACSymbol.Var,
        exp: CVLExp.BinaryExp.MulExp
    ) = compileBinary(
        out,
        exp,
        cvlCompiler.exprFact::IntMul,
    )

    private fun compileDivExp(
        out: TACSymbol.Var,
        exp: CVLExp.BinaryExp.DivExp
    ): ParametricInstantiation<CVLTACProgram> {
        return compileConstantBinary(out, exp)
            ?: compileExp(exp.l).let { l ->
                compileExp(exp.r).let { r ->
                    val assertNotDividingByZero = compileDivByZeroCheck(r, exp.r)
                    TACCmd.Simple.AssigningCmd.AssignExpCmd(out, l.out, r.out, cvlCompiler.exprFact::IntDiv)
                        .plusMeta(
                            CVL_EXP,
                            CVLExpToTACExprMeta.BinaryCVLExp(exp, l.out, exp.l, r.out, exp.r)
                        )
                        .let { cmd ->
                            mergeProgs(l.prog, r.prog).addSink(
                                assertNotDividingByZero.merge(cmd, l.out, r.out, out),
                                compilationEnvironment
                            )
                        }
                }
            }
    }

    private fun compileDivByZeroCheck(compiledDivisor: CompiledProgramWithOut, divisor: CVLExp): CommandWithRequiredDecls<TACCmd.Spec> {
        val divisionByZeroCheckVar = TACSymbol.Var(CVLReservedVariables.certoraDivisionByZero.name, Tag.Bool, keyword = CVLReservedVariables.certoraDivisionByZero.name)
        val divisorIsZeroCvlExp = CVLExp.RelopExp.EqExp(
            l = divisor,
            r = CVLExp.Constant.NumberLit(
                BigInteger.ZERO, CVLExpTag.transient(
                    CVLType.PureCVLType.Primitive.NumberLiteral(
                        BigInteger.ZERO
                    )
                )
            ),
            tag = divisor.tag.copy(type = CVLType.PureCVLType.Primitive.Bool)
        )
        val divisionByZeroCheckNegatedVar = TACKeyword.TMP(Tag.Bool).toUnique()
        val assertNotDividingByZero = listOf(
            TACCmd.Simple.AssigningCmd.AssignExpCmd(
                divisionByZeroCheckVar,
                compiledDivisor.out,
                TACSymbol.Const(BigInteger.ZERO),
                cvlCompiler.exprFact::Eq,
                meta = MetaMap(
                    CVL_EXP to CVLExpToTACExprMeta.BinaryCVLExp(
                        exp = divisorIsZeroCvlExp,
                        o1Exp = divisor,
                        o1Out = compiledDivisor.out,
                        o2Exp = CVLExp.Constant.NumberLit(
                            BigInteger.ZERO,
                            tag = CVLExpTag(
                                scope = divisor.tag.scope,
                                type = CVLType.PureCVLType.Primitive.NumberLiteral(BigInteger.ZERO),
                                range = divisor.tag.range
                            )
                        ),
                        o2Out = TACSymbol.Const(BigInteger.ZERO),
                    )
                )
            ),
            TACCmd.Simple.AssigningCmd.AssignExpCmd(divisionByZeroCheckNegatedVar, TACExpr.UnaryExp.LNot(divisionByZeroCheckVar.asSym())),
            SnippetCmd.CVLSnippetCmd.DivZero(
                cvlExpOutSym = divisionByZeroCheckVar,
                assertCond = divisionByZeroCheckNegatedVar,
                range = divisor.getRangeOrEmpty(),
                assertCmdLabel = "assertNot $divisorIsZeroCvlExp"
            ).toAnnotation(),
            TACCmd.Simple.AssertCmd(
                divisionByZeroCheckNegatedVar,
                "Division by zero"
            ),
            ScopeSnippet.toAnnotation()
        )
        return CommandWithRequiredDecls(
            assertNotDividingByZero,
            divisionByZeroCheckVar,
            divisionByZeroCheckNegatedVar,
            compiledDivisor.out
        )
    }

    private fun compileModExp(
        out: TACSymbol.Var,
        exp: CVLExp.BinaryExp.ModExp
    ): ParametricInstantiation<CVLTACProgram> {
        return compileExp(exp.l).let { l ->
            compileExp(exp.r).let { r ->
                val assertNotDividingByZero = compileDivByZeroCheck(r, exp.r)
                TACCmd.Simple.AssigningCmd.AssignExpCmd(out, l.out, r.out, cvlCompiler.exprFact::IntMod).let { cmd ->
                    mergeProgs(l.prog, r.prog).addSink(
                        assertNotDividingByZero.merge(cmd, l.out, r.out, out),
                        compilationEnvironment
                    )
                }
            }
        }
    }

    private fun compileExponentExp(
        out: TACSymbol.Var,
        exp: CVLExp.BinaryExp.ExponentExp
    ) = compileBinary(
        out,
        exp,
        cvlCompiler.exprFact::IntExponent,
    )

    private fun compileBwOrExp(
        out: TACSymbol.Var,
        exp: CVLExp.BinaryExp.BwOrExp
    ) = compileCommutativeBinaryBitwiseOp(
        out,
        exp,
        cvlCompiler.exprFact::BWOr
    )

    private fun compileBwXOrExp(
        out: TACSymbol.Var,
        exp: CVLExp.BinaryExp.BwXOrExp
    ) = compileCommutativeBinaryBitwiseOp(
        out,
        exp,
        cvlCompiler.exprFact::BWXOr
    )

    private fun compileBwAndExp(
        out: TACSymbol.Var,
        exp: CVLExp.BinaryExp.BwAndExp
    ) = compileCommutativeBinaryBitwiseOp(
        out,
        exp,
        cvlCompiler.exprFact::BWAnd
    )

    private fun compileBwNotExp(
        out: TACSymbol.Var,
        exp: CVLExp.UnaryExp.BwNotExp
    ): ParametricInstantiation<CVLTACProgram> {
        compileConstantUnary(out, exp)?.also { return it }

        val innerCompiled = compileExp(exp.e)
        val outType = exp.getPureCVLType()
        val (wrappedInner, preOpManipulation) = doWrapping(innerCompiled.out, exp.e.getOrInferPureCVLType())
        val outBitvector = out.copy(tag = Tag.Bit256).toUnique("!")
        val bitwisOp = CommandWithRequiredDecls<TACCmd.Spec>(TACCmd.Simple.AssigningCmd.AssignExpCmd(outBitvector, wrappedInner.asSym(), cvlCompiler.exprFact::BWNot).plusMeta(
            CVL_EXP,
            CVLExpToTACExprMeta.UnaryCVLExp(
                exp = exp,
                oOut = innerCompiled.out,
                oExp = exp.e
            )
        ), outBitvector)

        val postOpManipulation = if (outType is CVLType.PureCVLType.Primitive.IntK) {
            doUnwrapping(out, outBitvector)
        } else if (outType is CVLType.PureCVLType.Primitive.UIntK) {
            // a bwnot may turn some 0 bits into 1 bits, we must mask these out
            maskLowerKBits(out, outBitvector, outType.k)
        } else if (outType is CVLType.PureCVLType.Primitive.BytesK) {
            // a bwnot may turn some 0 bits into 1 bits, we must mask these out
            maskUpperKBits(out, outBitvector, outType.bitWidth)
        } else if (outType is CVLType.PureCVLType.Primitive.NumberLiteral) {
            nopPostOpManipulation(out, outBitvector, outType)
        } else {
            throw IllegalArgumentException("bad type received $outType")
        }

        return innerCompiled.prog.addSink(
            CommandWithRequiredDecls.mergeMany(
                listOf(
                    preOpManipulation,
                    bitwisOp,
                    postOpManipulation
                )
            ),
            compilationEnvironment
        )
    }

    private fun compileBwLeftShiftExp(
        out: TACSymbol.Var,
        exp: CVLExp.BinaryExp.BwLeftShiftExp
    ): ParametricInstantiation<CVLTACProgram> =
        compileBinaryBitwiseOp(
            out,
            exp,
            cvlCompiler.exprFact::ShiftLeft,
            preOpLhsManipulation = ::doWrapping,
            // rhs is uint256 interpreted as a number
            preOpRhsManipulation = { inVar, _ -> inVar to CommandWithRequiredDecls() },
            // a left shift will ruin sign extension of intk: must re-establish
            postOpIntManipulation = { outVar, inVar, t ->
                    CommandWithRequiredDecls(
                        TACCmd.Simple.AssigningCmd.AssignExpCmd(outVar,
                            cvlCompiler.exprFact.SignExtend(inVar.asSym(), TACSymbol.lift(t.k - 1).asSym()))
                    )
            },
            // a left shift can pollute upper bits of uintk, mask those out
            postOpUIntManipulation = { outVar, inVar, t ->
                maskLowerKBits(outVar, inVar, t.k)
            },
            // since bytesK is right padded, a left shift will not pollute any bits which should be 0
            postOpBytesKManipulation = ::nopPostOpManipulation
        )

    private fun compileBwRightShiftLogicalExp(
        out: TACSymbol.Var,
        exp: CVLExp.BinaryExp.BwRightShiftWithZerosExp // Logical
    ) = compileBinaryBitwiseOp(
        out,
        exp,
        cvlCompiler.exprFact::ShiftRightLogical,
        preOpLhsManipulation = { inVar, t ->
            doWrapping(inVar, t).let { (outVar, wrapCommands) ->
                val prog = if (t is CVLType.PureCVLType.Primitive.IntK) {
                    // logical right shift should start from bit k
                    wrapCommands.merge(maskLowerKBits(outVar, inVar, t.k))
                } else {
                    wrapCommands
                }

                outVar to prog
            }
        },
        // rhs is a subtype of uint256 interpreted as a number
        preOpRhsManipulation = { inVar, _ -> inVar to CommandWithRequiredDecls() },
        postOpIntManipulation = { outVar, inVar, t ->
            CommandWithRequiredDecls(
                // preOpManipulation broke sign extension, we must bring it back
                TACCmd.Simple.AssigningCmd.AssignExpCmd(outVar, cvlCompiler.exprFact.SignExtend(inVar.asSym(), TACSymbol.lift(t.k - 1).asSym()))
            )
        },
        // logical right shift will not bring 1's into upper bits
        postOpUIntManipulation = ::nopPostOpManipulation,
        postOpBytesKManipulation = { outVar, inVar, t ->
            // shifting right can send 1's into lower order bits
            maskUpperKBits(outVar, inVar, t.bitWidth)
        }

    )

    private fun compileBwRightShiftArithmeticalExp(
        out: TACSymbol.Var,
        exp: CVLExp.BinaryExp.BwRightShiftExp
    ) = compileBinaryBitwiseOp(
        out,
        exp,
        if (exp.l.getOrInferPureCVLType() is CVLType.PureCVLType.Primitive.IntK) {
            // only signed integers really do arithmetical rshift, and at the lexpression level we assume arithmetical
            // rshift is being performed on a number encoded as two's-complement, i.e. on a signed integer.
            cvlCompiler.exprFact::ShiftRightArithmetical
        } else {
            // in case of unsigned int and bytesK we don't interpret the highest bit as a sign bit, so arithmetical rshift
            // in that case is really just a logical rshift.
            cvlCompiler.exprFact::ShiftRightLogical
        },
        preOpLhsManipulation = ::doWrapping,
        // subtype of uint256 interpreted as number
        preOpRhsManipulation = { inVar, _ -> inVar to CommandWithRequiredDecls() },
        // higher order bits preserved
        postOpIntManipulation = ::nopPostOpManipulation,
        // higher order bits preserved
        postOpUIntManipulation = ::nopPostOpManipulation,
        postOpBytesKManipulation = { outVar, inVar, t ->
            // shifting right can send 1's into lower order bits
            maskUpperKBits(outVar, inVar, t.bitWidth)
        }
    )

    private fun getDisplayName(exp: CVLExp) = "${exp.tag.range}: ${exp}"

    private fun compileIffExp(
        out: TACSymbol.Var,
        exp: CVLExp.BinaryExp.IffExp
    ) = compileBinary(
        out.withMeta(CVL_VAR, true),
        exp,
        cvlCompiler.exprFact::Eq,
    )

    private fun compileShortCircuitableExp(
        out: TACSymbol.Var,
        exp: CVLExp.BinaryExp,
        expThen : CVLExp,
        expElse : CVLExp,
        exprConstructor: (TACExpr, TACExpr) -> TACExpr
    ): ParametricInstantiation<CVLTACProgram>  =
        if (exp.tag.annotation == NeedsShortCiruiting) {
            val e = CVLExp.CondExp(exp.l, expThen, expElse, exp.tag.copy())
            compileCondExp(out, e)
        } else {
            compileBinary(
                out,
                exp,
                exprConstructor
            )
        }

    private fun compileImpliesExp(
        out: TACSymbol.Var,
        exp: CVLExp.BinaryExp.ImpliesExp
    ): ParametricInstantiation<CVLTACProgram> =
        // Check for short circuit, If so compile it as condExp, else compile as BinaryExp:
        compileShortCircuitableExp(out.withMeta(CVL_VAR, true), exp, exp.r,
            CVLExp.Constant.BoolLit(true, exp.tag.copy())
        ) { cond, implication -> cvlCompiler.exprFact.LOr(cvlCompiler.exprFact.LNot(cond), implication) }


    private fun compileLandExp(
        out: TACSymbol.Var,
        exp: CVLExp.BinaryExp.LandExp
    ): ParametricInstantiation<CVLTACProgram> =
        // Check for short circuit, If so compile it as condExp, else compile as BinaryExp:
        compileShortCircuitableExp(out.withMeta(CVL_VAR, true), exp, exp.r,
            CVLExp.Constant.BoolLit(false, exp.tag.copy()),
            cvlCompiler.exprFact::LAnd)

    private fun compileLorExp(
        out: TACSymbol.Var,
        exp: CVLExp.BinaryExp.LorExp
    ): ParametricInstantiation<CVLTACProgram> =
        // Check for short circuit, If so compile it as condExp, else compile as BinaryExp:
        compileShortCircuitableExp(out.withMeta(CVL_VAR, true), exp,
            CVLExp.Constant.BoolLit(true, exp.tag.copy()), exp.r,
            cvlCompiler.exprFact::LOr)

    private fun compileLnotExp(
        out: TACSymbol.Var,
        exp: CVLExp.UnaryExp.LNotExp
    ) = compileUnary(
        out.withMeta(CVL_VAR, true),
        exp.e,
        cvlCompiler.exprFact::LNot,
        exp
    )

    /**
     * Returns null if it's not possible to do, which could happen if the expression has side-effects (usually an indication
     * of a call to some function which will prevent sanely merging the program into a single expression, or when there
     * are multiple blocks in the code).
     */
    fun compileCvlExpToSingleTACExp(exp: CVLExp, allocatedTACSymbols: TACSymbolAllocation) : Pair<TACExpr, List<TACCmd.Spec>>? {
        if (exp.subExpsWithSideEffects(this.cvlCompiler.cvl.symbolTable).isNotEmpty()) {
            return null
        }

        // Compile the expression as usual
        val (prog, bodyOut) = CVLExpressionCompiler(cvlCompiler, allocatedTACSymbols, compilationEnvironment).compileExp(exp)

        return prog.getAsSimple().let { p ->
            val tacProgram = p.mergeBlocks()

            if (tacProgram.blockgraph.size > 1) {
                // We don't really have how to deal with jumps here (yet)
                return null
            }

            // Transform the program to a simplesimple program, because this transforms all the more complex assigning
            // commands to simple AssignExpCmd.
            // Note we're also filtering out the AssignVMParam commands - we'll re-add them to the program in the end.
            val coreProg = tacProgram.copy(code = tacProgram.code.mapValues {
                    (_, list) -> list.filterNot { it is TACCmd.CVL.AssignVMParam }
            }).let {
                CVLToSimpleCompiler(cvlCompiler.scene).compile(it)
            }
            val simpleSimpleProg = TACSimpleSimple.toSimpleSimple(coreProg)

            // Construct a mapping from assigned vars to the expression that's assigned to them.
            val assignmentsToFold = simpleSimpleProg.analysisCache.graph.commands.filter {
                it.cmd is TACCmd.Simple.AssigningCmd
            }.mapNotNull { lcmd ->
                when (lcmd.cmd) {
                    is TACCmd.Simple.AssigningCmd.AssignExpCmd -> lcmd.cmd.lhs to lcmd.cmd.rhs
                    is TACCmd.Simple.AssigningCmd.AssignHavocCmd -> null // ignore havoc commands - unassigned vars get havoc'ed anyway
                    else -> error("Command ${lcmd.cmd} should have been simplified already")
                }
            }.toMap()

            check(bodyOut in assignmentsToFold) {
                "The body out variable should have been assigned to"
            }

            // ghost access annotations need to be kept so the values will be printed in the GhostState,
            // and we want them as expressions in the final expression so they are within the scope of any quantified vars mentioned inside
            val annotationsToKeep = tacProgram.parallelLtacStream().mapNotNull { l ->
                when(l.cmd) {
                        is TACCmd.Simple.AnnotationCmd -> l.cmd.annot.takeIf { it.v is SnippetCmd.CVLSnippetCmd.GhostAccess }
                        else -> null
                    }
            }.collect(Collectors.toList())

            // we wrap the annotations around the out symbol before folding the assignments, so that vars mentioned inside get transformed with if needed
            val wrapped = annotationsToKeep.fold(bodyOut.asSym()) { acc : TACExpr, annot -> TACExpr.AnnotationExp(acc, annot) }

            // Folds the assignments from the compiled program into one long TACExpr
            // e.g.
            // AssignExp(tmp1, TACExpr.And(x y))
            // AssignExp(tmp2, z)
            // AssignExp(tmp3, TACExpr.Or(tmp1 tmp2))
            // AssignExp(out, tmp3)
            // will become
            // TACExpr.Or(TACExpr.And(x, y), z)
            val assignmentFolder = object : DefaultTACExprTransformer() {
                override fun transformVar(exp: TACExpr.Sym.Var): TACExpr {
                    return if (exp.s in assignmentsToFold) {
                        return super.transform(assignmentsToFold[exp.s]!!)
                    } else {
                        exp
                    }
                }

                override fun <@Treapable T : Serializable> transformAnnotationExp(o: TACExpr, k: MetaKey<T>, v: T) =
                    TACExpr.AnnotationExp(transform(o), k, mapMetaPair(k, v))

                // note that if a symbol in an annotation is not mapped to a symbol then it's not mapped.
                // There is no simple solution here for this problem, and one should be aware of it.
                fun <@Treapable T : Serializable> mapMetaPair(k: MetaKey<T>, v: T) : T =
                    applyTransEntityMappers(v, k,
                        mapSymbol = { (transform(it.asSym()) as? TACExpr.Sym)?.s ?: it },
                        mapVar = { (transformVar(it.asSym()) as? TACExpr.Sym.Var)?.s ?: it }
                    ).uncheckedAs()
            }
            val folded = assignmentFolder.transform(wrapped)

            // We need the conversion logic for VM params, so keep these commands too
            val extraCmds = tacProgram.parallelLtacStream().mapNotNull { l ->
                l.cmd as? TACCmd.CVL.AssignVMParam
            }.collect(Collectors.toList())

            // Sanity - check that the variables that need translation from vm to cvl weren't lost during the folding
            extraCmds
                .map { it.lhsVar }
                .forEach { v ->
                    check(folded.usesVar(v)) { "Variable $v needs translation from VM to CVL type but was lost during expression folding" }
                }
            folded to extraCmds
        }
    }

    @Suppress("NAME_SHADOWING")
    private fun compileQuantifierExpLegacy(
        out: TACSymbol.Var,
        exp: CVLExp.QuantifierExp,
    ): ParametricInstantiation<CVLTACProgram> {
        val qe = CVLExpToTACExpr(cvlCompiler).compileToBooleanTacExpr(exp, allocatedTACSymbols, compilationEnvironment)
        return qe.transformCode { qe ->
            check(qe.exp !is TACExpr.QuantifiedFormula ||
                !qe.newCmds.any { cmd ->
                    cmd.getFreeVarsOfRhs().any { freeVar -> freeVar in qe.exp.quantifiedVars }
                }
            ) {
                "one of the new commands (${qe.newCmds}) refers to a quantified variable -- it does not make sense " +
                    "to declare them outside of the quantifier"
            }
            buildTACFromListOfCommands(
                qe.newCmds +
                    TACCmd.Simple.AssigningCmd.AssignExpCmd(
                        out.withMeta(CVL_VAR, true),
                        qe.exp
                    ).plusMeta(
                        CVL_EXP,
                        CVLExpToTACExprMeta.UnaryCVLExp(
                            exp = exp,
                            oOut = out,
                            oExp = exp
                        )
                    ),
                compilationEnvironment
            ).let { it.copy(symbolTable = it.symbolTable.mergeDecls(qe.newDecls)) }
        }
    }

    private fun compileQuantifierExp(
        out: TACSymbol.Var,
        exp: CVLExp.QuantifierExp,
    ): ParametricInstantiation<CVLTACProgram> {
        val bodyTACSymbolAllocation = allocatedTACSymbols.shadow(exp)
        val singleTacExp = compileCvlExpToSingleTACExp(exp.body, bodyTACSymbolAllocation)
            ?: return compileQuantifierExpLegacy(out, exp)

        val (foldedExp, extraCmds) = singleTacExp

        return buildTACFromListOfCommands(
            extraCmds +
                TACCmd.Simple.AssigningCmd.AssignExpCmd(
                    out,
                    TACExpr.QuantifiedFormula(
                        exp.isForall,
                        bodyTACSymbolAllocation.get(exp.qVarName, exp.qVarType.toTag())
                            .withMeta(QUANTIFIED_VAR_TYPE, exp.qVarType)
                            .withMeta(CVL_VAR, true)
                            .withMeta(CVL_DISPLAY_NAME, exp.originalName),
                        foldedExp
                    )
                ).plusMeta(
                    CVL_EXP,
                    CVLExpToTACExprMeta.UnaryCVLExp(
                        exp = exp,
                        oOut = out,
                        oExp = exp
                    )
                ),
            compilationEnvironment
        ).toSimple()
    }

    private fun compileSumExp(
        out: CVLParam,
        exp: CVLExp.SumExp,
    ): ParametricInstantiation<CVLTACProgram> {
        val thisGhost = cvlCompiler.cvl.ghosts.find { it.id == exp.sumGhostName } ?: error("should have found the ghost")
        check(thisGhost is CVLGhostDeclaration.Sum) { "the sum ghost is supposed to be a variable and not a function" }

        fun generateSumAccessExp(
            indices: List<CVLExp>,
            type: CVLType.PureCVLType
        ): CVLExp {
            if (indices.isEmpty()) {
                return withScopeAndRange(exp.getScope(), Range.Empty()) {
                    CVLExp.VariableExp(exp.sumGhostName, type.asTag())
                }
            }

            val last = indices.last()
            return withScopeAndRange(exp.getScope(), Range.Empty()) {
                val array = generateSumAccessExp(
                    indices.dropLast(1),
                    CVLType.PureCVLType.Ghost.Mapping(last.getOrInferPureCVLType(), type)
                )
                CVLExp.ArrayDerefExp(
                    array,
                    last,
                    type.asTag()
                )
            }
        }

        val sumGhostAccessExp = generateSumAccessExp(exp.body.indices.filterIndexed { i, _ -> i in exp.nonSummedIndices }, CVLType.PureCVLType.Primitive.Mathint)
        return compileExp(out, sumGhostAccessExp).transformCode { code ->
            val ghostReadSnippetAnnot = code.ltacStream().asSequence().single { it.maybeAnnotation(TACMeta.SNIPPET) is SnippetCmd.CVLSnippetCmd.GhostRead }
            val snippet = ghostReadSnippetAnnot.maybeAnnotation(TACMeta.SNIPPET) as SnippetCmd.CVLSnippetCmd.GhostRead
            code.patching { it.replaceCommand(ghostReadSnippetAnnot.ptr, listOf(SnippetCmd.CVLSnippetCmd.SumGhostRead(
                allocatedTACSymbols.get(out.id).withMeta(CVL_TYPE, CVLType.PureCVLType.Primitive.Mathint),
                thisGhost.origGhost.id,
                thisGhost.placeItemsInNonSummedIndices(snippet.indices),
                thisGhost.persistent
            ).toAnnotation())) }
        }
    }

    private fun compileFieldSelect(
        out: TACSymbol.Var,
        exp: CVLExp.FieldSelectExp,
    ): ParametricInstantiation<CVLTACProgram> {
        /* Not clear if this case will ever occur for us, but..
          .. if one of the subexpressions of [exp] contains a call and, at the same time, [exp] appears inside a
           quantified expression, then compileToSimple will not suffice and we need to switch to compileToTacExpr and
           handle the case when there is a [Result.WithSummary]. See [compileGhostExp] for inspiration. */
        return FieldSelectSemantics.fieldSemantics(exp,
            vmTypeHandler = { vm: CVLType.VM ->
                check(vm.getPureCVLTypeToConvertTo().isResult()) { "typechecker should have failed" }
                vm.getPureCVLTypeToConvertTo() as CollectingResult.Result
            },
            fieldHandler = { structType: CVLType.PureCVLType.Struct, fieldName: String ->
                check(structType.fields.find { it.fieldName == fieldName }!!.cvlType.toTag() == out.tag) {
                    "Disagreement about tags for $fieldName, have ${out.tag} for output where formal type is $structType"
                }
                compileExp(exp.structExp).bindCmd { output ->
                    CommandWithRequiredDecls<TACCmd.Simple>(
                        _cmds = listOf(
                            TACCmd.Simple.AssigningCmd.AssignExpCmd(
                                lhs = out,
                                rhs = TACExpr.StructAccess(
                                    fieldName = fieldName,
                                    struct = output.asSym(),
                                    tag = out.tag
                                )
                            ).plusMeta(CVL_EXP, CVLExpToTACExprMeta.NullaryCVLExp(exp))
                        ), _decl = setOf(out, output)
                    ).merge(CVLCompiler.Companion.TraceMeta.ValueTraversalCmd(lhs = out, ap = listOf(CVLCompiler.Companion.TraceMeta.CVLAccessPathStep.Field(exp.fieldName)), base = output))
                }.lift()
            },
            codeContractNameHandler = { codeContract, storageVariable ->


                val contract = cvlCompiler.scene.getContract(codeContract.name)
                val storageVars =
                    (cvlCompiler.scene.getStorageUniverse()[contract.instanceId] as? StorageInfoWithReadTracker)?.storageVariables?.keys
                        ?: throw IllegalStateException("Expected Storage Variables object to exist in contract $contract")

                val storageSlot =
                    cvlCompiler.scene.getContractOrNull(codeContract.name)?.getStorageLayout()?.get(storageVariable)
                        ?: throw IllegalStateException("Expected the storage variable '$storageVariable' to be present in the storage layout.")
                val srcType = storageSlot.typeDescriptor
                    ?: throw IllegalStateException("The storage variable '$storageVariable' is missing a type description.")
                val storageVariableSymbol = when (srcType) {
                    is EVMTypeDescriptor.EVMIsomorphicValueType,
                    is EVMTypeDescriptor.EVMContractTypeDescriptor -> {
                        storageVars.find {
                            val scalarizationSort = it.meta[TACMeta.SCALARIZATION_SORT]
                            if (scalarizationSort == null) {
                                false
                            } else {
                                val slot = when (scalarizationSort) {
                                    is ScalarizationSort.Split -> scalarizationSort.idx
                                    is ScalarizationSort.Packed -> (scalarizationSort.packedStart as? ScalarizationSort.Split)?.idx
                                    else -> null
                                }
                                val packed = scalarizationSort as? ScalarizationSort.Packed
                                slot == storageSlot.slot
                                    && (packed?.start == storageSlot.offsetInBytes || (packed?.start == null && storageSlot.offset == 0))
                            }
                        }
                    }

                    else -> {
                        throw CertoraInternalException(
                            CertoraInternalErrorType.CVL_COMPILER,
                            "direct storage access is only supported for value types at this moment"
                        )
                    }
                }
                if (storageVariableSymbol != null) {
                    val desType = srcType.getPureTypeToConvertTo(FromVMContext.StateValue).force()
                    val converter = srcType.converterTo(desType, FromVMContext.StateValue.getVisitor()).force()
                    val crd = converter.convertTo(storageVariableSymbol, out) { it }.toCRD()
                    getSimple(codeFromCommandWithVarDecls(crd, compilationEnvironment)).lift()
                } else {
                    throw CertoraException(
                        CertoraErrorType.CVL,
                        "Storage analysis ${if (!Config.EnableStorageAnalysis.get()) { "is disabled" } else { "failed" } } so direct access to storage is not possible. Use getter functions instead."
                    )
                }

            },
            arrayLenHandler = {
                compileExp(exp.structExp).bindCmd {
                    CommandWithRequiredDecls(TACCmd.CVL.ArrayLengthRead(
                        lhs = out,
                        rhs = it
                    ), out, it) andThen CommandWithRequiredDecls(CVLCompiler.Companion.TraceMeta.ValueTraversalCmd(lhs = out, base = it, ap = listOf(CVLCompiler.Companion.TraceMeta.CVLAccessPathStep.ArrayLength)))
                }.lift()
            },
            storageAccess = { _, _ -> `impossible!` },
            badType = { `impossible!` }).safeForce()
    }

    private fun compileSetMemExp(
        out: TACSymbol.Var,
        exp: CVLExp.SetMemExp
    ): CVLTACProgram {
        fun getSighashFromSelectExp(e: CVLExp.FieldSelectExp): SighashInt = when (e.structExp) {
            is CVLExp.Constant.SignatureLiteralExp -> {
                val struct = e.structExp.tag.annotation as CVLExp.Constant.StructLit
                val sighash = struct.fieldNameToEntry[e.fieldName]!! as CVLExp.Constant.NumberLit
                SighashInt(sighash.n)
            }

            else -> {
                throw CertoraInternalException(
                    CertoraInternalErrorType.CVL_COMPILER,
                    "In set membership expression the element must be a signature literal expression, " +
                            "but got ${e.structExp}--this should be checked by the type checker"
                )
            }
        }


        // currently we support only method in contract
        check(exp.set is CVLExp.VariableExp && exp.e is CVLExp.FieldSelectExp && (exp.e as CVLExp.FieldSelectExp).fieldName == EVMExternalMethodInfo.selectorField) {
            "Set membership currently supported only for selectors"
        }

        val method = exp.e as CVLExp.FieldSelectExp
        val contract = (exp.set.getPureCVLType() as CVLType.PureCVLType.Primitive.CodeContract).name
        val found = cvlCompiler.scene.hasMethod(contract, getSighashFromSelectExp(method).n)

        return buildTACFromCommand(
            TACCmd.Simple.AssigningCmd.AssignExpCmd(
                out,
                if (found) TACSymbol.True else TACSymbol.False
            )
        )
    }

    private fun compileLtExp(
        out: TACSymbol.Var,
        exp: CVLExp.RelopExp.ArithRelopExp.LtExp
    ) = compileBinary(
        out,
        exp,
        cvlCompiler.exprFact::Lt,
    )

    private fun compileLeExp(
        out: TACSymbol.Var,
        exp: CVLExp.RelopExp.ArithRelopExp.LeExp
    ) = compileBinary(
        out,
        exp,
        cvlCompiler.exprFact::Le,
    )

    private fun compileGtExp(
        out: TACSymbol.Var,
        exp: CVLExp.RelopExp.ArithRelopExp.GtExp
    ) = compileBinary(
        out,
        exp,
        cvlCompiler.exprFact::Gt,
    )

    private fun compileGeExp(
        out: TACSymbol.Var,
        exp: CVLExp.RelopExp.ArithRelopExp.GeExp
    ) = compileBinary(
        out,
        exp,
        cvlCompiler.exprFact::Ge,
    )

    private fun CVLExp.isBytesOperand() = this.getCVLType() isConvertibleTo CVLType.PureCVLType.DynamicArray.PackedBytes ||  this.getCVLType() isConvertibleTo CVLType.PureCVLType.DynamicArray.StringType
    private fun compileEqExp(
        out: TACSymbol.Var,
        exp: CVLExp.RelopExp.EqExp
    ): ParametricInstantiation<CVLTACProgram> {
        return when {
            exp.l.isStorageOperand() -> {
                compileStorageComparison(
                    comparisonType = exp.tag.annotation as ComparisonType,
                    /**
                     * This syntactic restriction is enforced by the type checker, see [spec.cvlast.typechecker.CVLExpTypeCheckerWithContext]
                     */
                    ref1 = exp.l.extractStorageRef(),
                    ref2 = exp.r.extractStorageRef(),
                    outVar = out,
                    isEquality = true
                )
            }
            exp.l.isBytesOperand() && exp.r.isBytesOperand() -> {
                compileBytes1ArrayComparison(
                    left = exp.l,
                    right = exp.r,
                    outVar = out
                )
            }
            exp.l.getOrInferPureCVLType { _, _ -> }.resultOrNull() is CVLType.PureCVLType.Struct -> {
                /**
                 * Accepts two expressions representing structs (of the same type).
                 * @return a list of [CVLExpressionCompiler.CompiledProgramWithOut] where each element is a comparison of one of the struct's fields
                 * Note that if the field is itself a struct this function will recursively expand it to a list of comparisons of _it's_ fields.
                 */
                fun compileFieldEqExp(baseExpL: CVLExp, baseExpR: CVLExp): List<CVLExpressionCompiler.CompiledProgramWithOut> {
                    check(baseExpL.getOrInferPureCVLType() == baseExpR.getOrInferPureCVLType()) {
                        "The typechecker should have made  sure the right- and left-hand sides of the comparison are of the same type." +
                            "got ${baseExpL.getOrInferPureCVLType()} and ${baseExpR.getOrInferPureCVLType()}"
                    }
                    val structType = baseExpL.getOrInferPureCVLType() as CVLType.PureCVLType.Struct
                    return withScopeAndRange(baseExpL.getScope(), baseExpL.getRangeOrEmpty()) {
                        structType.fields.flatMap { field ->
                            when (val fieldVal = field.cvlType) {
                                is CVLType.PureCVLType.Struct -> {
                                    // An inner struct. Recursively compile its fields
                                    compileFieldEqExp(
                                        CVLExp.FieldSelectExp(baseExpL, field.fieldName, fieldVal.asTag()),
                                        CVLExp.FieldSelectExp(baseExpR, field.fieldName, fieldVal.asTag())
                                    )
                                }

                                else -> {
                                    if (fieldVal is CVLType.PureCVLType.CVLArrayType && fieldVal !is CVLType.PureCVLType.DynamicArray.Bytes1Array) {
                                        error("typechecker should have prevented this case")
                                    }
                                    // compile `baseExpL.fieldName == baseExpR.fieldName`
                                    listOf(compileExp(CVLExp.RelopExp.EqExp(
                                        CVLExp.FieldSelectExp(baseExpL, field.fieldName, fieldVal.asTag()),
                                        CVLExp.FieldSelectExp(baseExpR, field.fieldName, fieldVal.asTag()),
                                        CVLType.PureCVLType.Primitive.Bool.asTag()
                                    )))
                                }
                            }
                        }
                    }
                }

                val baseCVLTACProgram = CommandWithRequiredDecls(TACCmd.Simple.LabelCmd("→ struct comparison ($exp)")).toProgWithCurrEnv("structComparison")
                val fieldComparisons = compileFieldEqExp(exp.l, exp.r)
                val (mergedFieldComparisons, conjunction) = fieldComparisons.fold(baseCVLTACProgram to TACSymbol.True.asSym() as TACExpr) { acc, compiledWithOut ->
                    acc.first.merge(compiledWithOut.prog.getAsSimple()) to TACExprFactUntyped {
                        acc.second and compiledWithOut.out.asSym()
                    }
                }

                mergedFieldComparisons.addSink(
                    CommandWithRequiredDecls(
                        TACCmd.Simple.AssigningCmd.AssignExpCmd(out.withMeta(CVL_VAR, true), conjunction),
                        listOf(out)
                    ).merge(TACCmd.Simple.LabelCmd("←")),
                    compilationEnvironment
                ).first.toSimple()
            }

            else -> {
                compileBinary(
                    out.withMeta(CVL_VAR, true),
                    exp,
                    cvlCompiler.exprFact::Eq,
                )
            }
        }
    }

    private fun CVLExp.extractStorageRef() : CVLExp.VariableExp {
        require(this.isStorageOperand())
        return when(this.getPureCVLType()) {
            is CVLType.PureCVLType.VMInternal.BlockchainState -> {
                /**
                 * enforced by the typing rules for storage comparisons, see [spec.cvlast.typechecker.CVLExpTypeCheckerWithContext.relopBinder]
                 */
                check(this is CVLExp.VariableExp)
                this
            }
            is CVLType.PureCVLType.VMInternal.StorageReference -> {
                /**
                 * enfocred by the typing rules for storage references, see [spec.cvlast.typechecker.CVLExpTypeCheckerWithContext.arrayderef]
                 */
                check(this is CVLExp.ArrayDerefExp && this.array is CVLExp.VariableExp)
                this.array as CVLExp.VariableExp
            }
            else -> `impossible!`
        }
    }

    private fun compileNeExp(
        out: TACSymbol.Var,
        exp: CVLExp.RelopExp.NeExp
    ): ParametricInstantiation<CVLTACProgram> {
        if(exp.l.isStorageOperand()) {
            return compileStorageComparison(
                comparisonType = exp.tag.annotation as ComparisonType,
                outVar = out,
                /**
                 * As above this is safe due to [spec.cvlast.typechecker.CVLExpTypeChecker]
                 */
                ref1 = exp.l.extractStorageRef(),
                ref2 = exp.r.extractStorageRef(),
                isEquality = false
            )
        }
        return compileUnary(
            out,
            CVLExp.RelopExp.EqExp(exp.l, exp.r, exp.tag),
            { eq -> cvlCompiler.exprFact.LNot(eq) },
            exp
        )
    }

    /* TODO MOVE THIS */
    private fun buildTACFromListOfCommands(
        l: List<TACCmd.Spec>,
        compilationEnvironment: CVLCompiler.CallIdContext,
        // nasty stuff .. (note that before pulling this into comments, using these field of cvlCompiler was just
        // hardcoded in this function's body --> CVL rewrite will make things better, I suppose
        symbolTable: TACSymbolTable = cvlCompiler.tacSymbolTable,
    ) =
        codeFromListOfCommandsWithTypeInfo(
            rootId = compilationEnvironment.newBlockId(),
            cmds = l,
            name = "",
            symbolTable = symbolTable,
        )

    private fun castToBytesK(
        exp: CVLExp.CastExpr,
        out: TACSymbol.Var,
        fromCastType: CVLType.PureCVLType.Primitive,
    ): ParametricInstantiation<CVLTACProgram> {
        require(fromCastType is CVLType.PureCVLType.Primitive.UIntK
            || fromCastType is CVLType.PureCVLType.Primitive.NumberLiteral
            || fromCastType is CVLType.PureCVLType.Primitive.AccountIdentifier)

        when (fromCastType) {
            is CVLType.PureCVLType.Primitive.UIntK -> {
                check(fromCastType.bitWidth == (exp.toCastType as CVLType.PureCVLType.Primitive.BytesK).bitWidth) {
                    "We only allow casting from uintN with the same bitwidth of the target bytesK. The typchecker should have caught this."
                }

                //left shift by 256-k bytes
                val shiftAmount = (Config.VMConfig.registerBitwidth - fromCastType.k).toBigInteger()
                val bwLeftShiftExp = CVLExp.BinaryExp.BwLeftShiftExp(
                    exp.arg, CVLExp.Constant.NumberLit(
                        shiftAmount,
                        CVLExpTag(
                            exp.getScope(),
                            CVLType.PureCVLType.Primitive.NumberLiteral(shiftAmount),
                            exp.getRangeOrEmpty()
                        )
                    ),
                    exp.tag
                )
                return compileBwLeftShiftExp(out, bwLeftShiftExp)
            }
            is CVLType.PureCVLType.Primitive.NumberLiteral -> {
                val constVal = CodeGenUtils.numAsBytesKConst(
                    fromCastType.n,
                    (exp.toCastType as CVLType.PureCVLType.Primitive.BytesK).bitWidth
                )

                return getSimple(
                    buildTACFromCommand(
                        TACCmd.Simple.AssigningCmd.AssignExpCmd(
                            out,
                            constVal
                        )
                    )
                )
            }
            is CVLType.PureCVLType.Primitive.AccountIdentifier -> {
                // We could also check that the bitwidth is at least 160, but for now we only allow casting
                // to bytes32.
                check((exp.toCastType as CVLType.PureCVLType.Primitive.BytesK).bitWidth == 256) {
                    "We only allow casting from address to bytes32. The typchecker should have caught this."
                }

                val compiledExpression = compileExp(exp.arg)

                return mergeProgs(
                    compiledExpression.prog,
                    CommandWithRequiredDecls(
                        listOf(
                            TACCmd.Simple.AssigningCmd.AssignExpCmd(
                                lhs = out,
                                rhs = TACExpr.Apply(
                                    TACBuiltInFunction.SafeMathNarrow.Implicit(Tag.Bit256),
                                    listOf(compiledExpression.out.asSym()),
                                    Tag.Bit256
                                )
                            )
                        ), setOf(out, compiledExpression.out)
                    ).toProgWithCurrEnv("safe narrow address to bv256").toSimple()
                )
            }

            else ->`impossible!`
        }

    }

    private fun buildTACFromCommand(c: TACCmd.Spec) =
        buildTACFromListOfCommands(listOf(c), compilationEnvironment)

    /* END MOVE THIS */
    private fun compileCastExpression(
        out: TACSymbol.Var,
        exp: CVLExp.CastExpr
    ): ParametricInstantiation<CVLTACProgram> {
        val toCast = exp.toCastType
        // lazy because this will FAIL for VM tagged with bytes (no correspondence)
        val fromCastType by lazy {
            exp.arg.getOrInferPureCVLType()
        }
        return if (toCast is CVLType.PureCVLType.Primitive.BytesK) {
            val fromType = fromCastType
            check((fromType is CVLType.PureCVLType.Primitive.NumberLiteral && fromType.bitLength <= toCast.bitWidth)
                || (fromType is CVLType.PureCVLType.Primitive.UIntK && fromType.bitWidth == toCast.bitWidth)
                || (fromType is CVLType.PureCVLType.Primitive.AccountIdentifier && toCast.bitWidth == 256)) {
                "The type checker should have triggered a type-error for: a cast of $fromType to $toCast is not allowed" +
                    " - they must have the same bit-width "
            }
            castToBytesK(
                exp, out, fromType as CVLType.PureCVLType.Primitive
            )
        } else if(toCast == CVLType.PureCVLType.Primitive.HashBlob && exp.castType == CastType.CONVERT) {
            val identityBoxingType = (exp.tag.annotation as? BoxingType)?.isIdentity ?: false;
            if (identityBoxingType) {
                val (outParam, res) = allocatedTACSymbols.generateTransientUniqueCVLParam(
                    symbolTable = this.cvlCompiler.cvl.symbolTable,
                    id = "tmp",
                    type = CVLType.PureCVLType.Primitive.HashBlob
                )
                val argRes = compileExp(outParam, exp.arg)
                mergeProgs(
                    argRes,
                    CommandWithRequiredDecls(
                        listOf(
                            TACCmd.Simple.AssigningCmd.AssignExpCmd(
                                lhs = out,
                                rhs = res
                            )
                        ), setOf(out, res)
                    ).toProgWithCurrEnv("identity cast").toSimple()
                )
            } else {
                compileExp(
                    exp.arg,
                ).bindParam { outArray ->
                    CommandWithRequiredDecls(
                        listOf(
                            TACCmd.CVL.AssignBytesBlob(
                                lhs = out,
                                rhs = outArray,
                                meta = MetaMap()
                            )
                        ), setOf(out, outArray)
                    ).toProgWithCurrEnv("hashing").asSimple()
                }
            }
        } else {
            compileExp(exp.arg).let { inner ->
                val toCastType = exp.toCastType
                val f = getTACCastExpressionHelpers(fromCastType, toCastType)
                if (f == null) {
                    TACCmd.Simple.AssigningCmd.AssignExpCmd(out, inner.out).let { cmd ->
                        inner.prog.addSink(
                            CommandWithRequiredDecls(cmd, out, inner.out),
                            compilationEnvironment
                        )
                    }
                } else {

                    val cmds = mutableListOf<TACCmd.Simple>()

                    // Build an assume/assert command to verify the safety of the cast
                    val castCheckVar = TACSymbol.Var("cast_check", Tag.Bool)
                    cmds.add(TACCmd.Simple.AssigningCmd.AssignExpCmd(castCheckVar, f.safeCastExpr(inner.out)))
                    when (exp.castType) {
                        CastType.REQUIRE -> {
                            cmds.add(TACCmd.Simple.AssumeCmd(castCheckVar, "require cast"))
                        }

                        CastType.ASSERT -> {
                            cmds.add(
                                SnippetCmd.CVLSnippetCmd.AssertCast(
                                    inner.out,
                                    castCheckVar,
                                    exp.getRangeOrEmpty(),
                                ).toAnnotation()
                            )
                            cmds.add(
                                TACCmd.Simple.AssertCmd(
                                    castCheckVar,
                                    "Cast safety of ${exp.arg} ($fromCastType) to $toCastType (${exp.getRangeOrEmpty()})"
                                    + (exp.inCVLBlockId?.let { " (uid $it)" }.orEmpty())
                                ).plusMeta(CVL_USER_DEFINED_ASSERT)
                            )
                            cmds.add(
                                ScopeSnippet.toAnnotation()
                            )
                        }

                        else -> {}
                    }
                    cmds.add(TACCmd.Simple.AssigningCmd.AssignExpCmd(out, inner.out))
                    inner.prog.addSink(
                        CommandWithRequiredDecls(
                            cmds, castCheckVar, out, inner.out,
                        ), compilationEnvironment
                    )
                }
            }
        }
    }


    private fun compileGhostAppExp(
        out: TACSymbol.Var,
        exp: CVLExp.ApplyExp.Ghost
    ): ParametricInstantiation<CVLTACProgram> {
        val persistent = cvlCompiler.fetchGhostDeclaration(exp.id)?.persistent == true

        return GhostCompilation.handleGhostApplication(
            cvlCompiler, exp, {
                val cmds = listOf(
                    TACCmd.Simple.AssigningCmd.AssignExpCmd(
                        lhs = out,
                        it.asSym()
                    ),
                    SnippetCmd.CVLSnippetCmd.GhostRead(
                        returnValueSym = out,
                        returnValueExp = exp,
                        sort = GhostSort.Function,
                        persistent = persistent,
                    ).toAnnotation()
                )
                getSimple(buildTACFromListOfCommands(cmds, compilationEnvironment))
            }
        ) { baseVar, args, ty ->
            compileGhostAccess(
                baseVar = baseVar,
                args = args,
                out = out,
                ghostAccessExp = exp,
                types = ty,
                sort = GhostSort.Function,
                persistent = persistent
            )
        }
    }

    private fun compileVarExp(
        out: CVLParam,
        exp: CVLExp.VariableExp
    ): ParametricInstantiation<CVLTACProgram> {
        if (exp.id.isWildcard()) {
            // constrain out variable, if it's not a wildcard too
            return if (out.id.isWildcard()) {
                // nothing to do
                getSimple(
                    buildTACFromCommand(
                        TACCmd.Simple.NopCmd
                    )
                )
            } else {
                getSimple(
                    cvlCompiler.typeConstraints(out.type,
                        allocatedTACSymbols.get(out.id, out.type.toTag()))
                        .toProgWithCurrEnv("wildcard-constraint")
                )
            }
        }

        CVLKeywords.toValue(exp.id)?.let { value ->
            return getSimple(
                buildTACFromCommand(
                    TACCmd.Simple.AssigningCmd.AssignExpCmd(
                        allocatedTACSymbols.get(out.id),
                        TACSymbol.lift(value)
                    ).plusMeta(
                        CVL_EXP,
                        CVLExpToTACExprMeta.NullaryCVLExp(exp)
                    )
                )
            )
        }

        val assignment = when (val rhsType = exp.getCVLType()) {
            is CVLType.VM -> buildTACFromCommand(TACCmd.CVL.AssignVMParam(allocatedTACSymbols.get(out.id), out.type, exp.id, rhsType))
            is CVLType.PureCVLType -> {
                val outTag = if (out.id.isWildcard()) {
                    check(out.type == CVLType.PureCVLType.Bottom) { "wildcard lhs should have been typed with 'Bottom'" }
                    // assigning to a wildcard, this assignment is sort of bogus, since it can't be used later. Just use
                    // the rhs tag to make the TAC typechecker happy later.
                    rhsType.toTag()
                } else {
                    out.type.toTag()
                }

                val sym = allocatedTACSymbols.get(exp.id)

                val cmds: MutableList<TACCmd.Spec> = mutableListOf(
                    TACCmd.Simple.AssigningCmd.AssignExpCmd(
                        allocatedTACSymbols.get(out.id, outTag),
                        sym
                    ).plusMeta(
                        CVL_EXP,
                        CVLExpToTACExprMeta.NullaryCVLExp(exp)
                    ),
                )

                val ghost = cvlCompiler.fetchGhostDeclaration(exp.id)
                if (ghost is CVLGhostDeclaration.Variable || ghost is CVLGhostDeclaration.Sum) {
                    cmds.add(
                        SnippetCmd.CVLSnippetCmd.GhostRead(
                            returnValueSym = sym,
                            returnValueExp = exp,
                            sort = GhostSort.Variable,
                            persistent = ghost.persistent
                        ).toAnnotation()
                    )
                }

                buildTACFromListOfCommands(cmds, compilationEnvironment)
            }
        }

        return getSimple(assignment)
    }

    private fun compileBoolLit(
        out: TACSymbol.Var,
        exp: CVLExp.Constant.BoolLit
    ): CVLTACProgram {
        return buildTACFromCommand(
            TACCmd.Simple.AssigningCmd.AssignExpCmd(
                out,
                compileBoolLit(exp)
            ).plusMeta(
                CVL_EXP,
                CVLExpToTACExprMeta.NullaryCVLExp(exp)
            )
        )
    }

    private fun compileNumberLit(
        out: TACSymbol.Var,
        exp: CVLExp.Constant.NumberLit,
        @Suppress("UNUSED_PARAMETER") type: CVLType.PureCVLType
    ): CVLTACProgram {
        val sym = compileNumberLit(exp)

        check(sym.tag != Tag.Int || out.tag == Tag.Int) { "Trying to assign an int $exp to a variable that cannot hold it $out" }
        return buildTACFromCommand(
            TACCmd.Simple.AssigningCmd.AssignExpCmd(
                out,
                sym
            ).plusMeta(
                CVL_EXP,
                CVLExpToTACExprMeta.NullaryCVLExp(exp)
            )
        )
    }

    private fun compileStringLit(
        out: TACSymbol.Var,
        exp: CVLExp.Constant.StringLit
    ): ParametricInstantiation<CVLTACProgram> {
        if (exp.s.length > MaxStringSizeForComparison.get()) {
            logger.error("Got a string \"${exp.s}\" of size ${exp.s.length} which is greater than the max supported ${MaxStringSizeForComparison.get()}. " +
                "Try to increase with setting -${MaxStringSizeForComparison.option.realOpt()}")
        }

        val stringAsBytes = exp.s.toByteArray()
        val stringAssignment = CommandWithRequiredDecls(
            listOf(
                TACCmd.CVL.SetArrayLength(
                    out,
                    stringAsBytes.size.asTACSymbol()
                )
            ) +
            (0..stringAsBytes.size step 32).map { portionIdx ->
                val end = stringAsBytes.size.coerceAtMost(portionIdx + 32)
                val stringPortion = stringAsBytes.slice(portionIdx until end).toMutableList()
                if (end < portionIdx + 32) {
                    // The last portion, need to add padding
                    stringPortion += (stringPortion.size until 32).map { 0.toByte() }
                }
                check(stringPortion.size == 32) {
                    "Chunk size should be 32 but got $stringPortion (${stringPortion.size})"
                }
                TACCmd.CVL.WriteElement(
                    out,
                    physicalIndex = TACSymbol.Const(portionIdx.toBigInteger()),
                    value = TACSymbol.Const(stringPortion.joinToString(separator = "", transform = Byte::toHexString).toBigInteger(16)),
                    useEncoding = Tag.CVLArray.UserArray.ElementEncoding.Unsigned,
                    outputPath = listOf(tac.DataField.ArrayData)
                )
            },
            out
        )

        return getSimple(stringAssignment.toProgWithCurrEnv("string_assign"))
    }

    fun compileStorageHavocTarget(target: CVLExp.HavocTarget): ParametricInstantiation<CVLTACProgram> {
        val exp = target.asExp
        val ty = exp.getOrInferPureCVLType()

        return storageAccessCompiler.compileHavoc(exp, ty)
    }

    private fun compileExp(
        exp: CVLExp
    ): CompiledProgramWithOut {
        val expType = exp.getOrInferPureCVLType()
        /*check(
            expType is CVLType.PureCVLType.Primitive
                    || expType is CVLType.PureCVLType.Enum
                    || expType is CVLType.PureCVLType.Ghost.Mapping // TODO: how is this a simple type?
        ) { "Expression $exp must have a simple type (got $expType)" }*/

//        Leaving commented version for meta
//        val tmp = TACKeyword.TMP(tag)
//            .toUnique()
//            .withMeta(CVL_DISPLAY_NAME, preeevaluatedExpression.toString())
//            .withMeta(CVL_VAR, true)
//            .withMetaIfNotNull(CVL_TYPE, exp.getCVLType())

        val (tmpCVL, tmpTAC) = allocatedTACSymbols.generateTransientUniqueCVLParam(cvlCompiler.cvl.symbolTable, "tmp", expType)

        val expAssignedToTmp =
            compileExp(
                tmpCVL,
                exp
            )

        val tmpConstrained = expAssignedToTmp

        return CompiledProgramWithOut(tmpConstrained, tmpTAC)
    }

    fun compileExp(
        out: CVLParam,
        exp: CVLExp
    ): ParametricInstantiation<CVLTACProgram> {
        val outType = if (out.id.isWildcard()) {
            // then this is an assignment to a wildcard. this assignment is sort of bogus, since it can't be used later.
            // since the lhs here is untyped, infer the type from the rhs
            exp.getOrInferPureCVLType()
        } else {
            out.type
        }

        // this may be called for an assignment to wildcard, in which case a tag is required to disambiguate
        fun getOutVar(): TACSymbol.Var = allocatedTACSymbols.get(out.id, outType.toTag())

        when (exp.tag.annotation) {
            ComplexMarker -> return compileComplexAccess(
                getOutVar(), exp
            )
            StorageAccessMarker -> return storageAccessCompiler.compileRead(
                exp, getOutVar(), outType
            )
            ImmutableAccessMarker -> return compileImmutableAccess(getOutVar(), exp)
        }

        return when (exp) {
            is CVLExp.RelopExp.ArithRelopExp.GeExp -> compileGeExp(getOutVar(), exp)
            is CVLExp.RelopExp.ArithRelopExp.GtExp -> compileGtExp(getOutVar(), exp)
            is CVLExp.RelopExp.ArithRelopExp.LeExp -> compileLeExp(getOutVar(), exp)
            is CVLExp.RelopExp.ArithRelopExp.LtExp -> compileLtExp(getOutVar(), exp)
            is CVLExp.RelopExp.EqExp -> compileEqExp(getOutVar(), exp)
            is CVLExp.RelopExp.NeExp -> compileNeExp(getOutVar(), exp)
            is CVLExp.Constant.BoolLit -> getSimple(compileBoolLit(getOutVar(), exp))
            is CVLExp.Constant.NumberLit -> getSimple(compileNumberLit(getOutVar(), exp, out.type))
            is CVLExp.Constant.StringLit -> compileStringLit(getOutVar(), exp)
            is CVLExp.Constant.StructLit -> compileStructLiteralExpr(getOutVar(), exp)
            is CVLExp.Constant.SignatureLiteralExp -> compileSignatureliteralExp(out, exp)
            is CVLExp.BinaryExp.AddExp -> compileAddExp(getOutVar(), exp)
            is CVLExp.BinaryExp.DivExp -> compileDivExp(getOutVar(), exp)
            is CVLExp.BinaryExp.ModExp -> compileModExp(getOutVar(), exp)
            is CVLExp.BinaryExp.ExponentExp -> compileExponentExp(getOutVar(), exp)
            is CVLExp.ArrayLitExp -> compileArrayLitExp(getOutVar(), exp)
            is CVLExp.BinaryExp.IffExp -> compileIffExp(getOutVar(), exp)
            is CVLExp.BinaryExp.ImpliesExp -> compileImpliesExp(getOutVar(), exp)
            is CVLExp.BinaryExp.BwOrExp -> compileBwOrExp(getOutVar(), exp)
            is CVLExp.BinaryExp.BwXOrExp -> compileBwXOrExp(getOutVar(), exp)
            is CVLExp.BinaryExp.BwAndExp -> compileBwAndExp(getOutVar(), exp)
            is CVLExp.BinaryExp.BwLeftShiftExp -> compileBwLeftShiftExp(getOutVar(), exp)
            is CVLExp.BinaryExp.BwRightShiftExp -> compileBwRightShiftArithmeticalExp(getOutVar(), exp)
            is CVLExp.BinaryExp.BwRightShiftWithZerosExp -> compileBwRightShiftLogicalExp(getOutVar(), exp)
            is CVLExp.UnaryExp.BwNotExp -> compileBwNotExp(getOutVar(), exp)
            is CVLExp.BinaryExp.LandExp -> compileLandExp(getOutVar(), exp)
            is CVLExp.BinaryExp.LorExp -> compileLorExp(getOutVar(), exp)
            is CVLExp.BinaryExp.MulExp -> compileMulExp(getOutVar(), exp)
            is CVLExp.UnaryExp.LNotExp -> compileLnotExp(getOutVar(), exp)
            is CVLExp.QuantifierExp -> compileQuantifierExp(getOutVar(), exp)
            is CVLExp.SumExp -> compileSumExp(out, exp)
            is CVLExp.FieldSelectExp -> compileFieldSelect(getOutVar(), exp)
            is CVLExp.SetMemExp -> getSimple(compileSetMemExp(getOutVar(), exp))
            is CVLExp.ArrayDerefExp -> compileArrayDerefExp(getOutVar(), exp)
            is CVLExp.BinaryExp.SubExp -> compileSubExp(getOutVar(), exp)
            is CVLExp.UnaryExp.UnaryMinusExp -> compileUnaryMinusExp(getOutVar(), exp)
            is CVLExp.VariableExp -> compileVarExp(out, exp)
            is CVLExp.ApplyExp.Ghost -> compileGhostAppExp(getOutVar(), exp)
            is CVLExp.ApplyExp.Definition -> error(
                "Definition application $exp should already have been expanded during " +
                        "pre-compilation"
            )
            is CVLExp.ApplyExp.CVLFunction -> cvlCompiler.compileCVLFunctionApplication(
                exp,
                listOf(out),
                allocatedTACSymbols,
                compilationEnvironment
            )
            is CVLExp.ApplyExp.ContractFunction -> {
                cvlCompiler.compileContractFunctionInvocation(exp, listOf(out), allocatedTACSymbols, compilationEnvironment)
            }
            is CVLExp.CastExpr -> compileCastExpression(getOutVar(), exp)
            is CVLExp.CondExp -> compileCondExp(getOutVar(), exp)
            is CVLExp.UnresolvedApplyExp -> error("Should not happen, type checking should resolve applications")
            is CVLExp.Constant.EnumConstant -> compileEnumConstant(getOutVar(), exp)
            is CVLExp.ApplyExp.CVLBuiltIn -> {
                compileCVLBuiltIn(getOutVar(), exp)
            }
            is CVLExp.AddressFunctionCallExp -> {
                cvlCompiler.compileAddressFunctionApplication(exp, listOf(out), allocatedTACSymbols, compilationEnvironment)
            }
        }.let {
            it.transformCode { c -> c.copy(name = "CVLExp_${getDisplayName(exp)}") }
        }
    }

    private fun compileImmutableAccess(out: TACSymbol.Var, exp: CVLExp): ParametricInstantiation<CVLTACProgram> {
        // must be a field select
        if (exp !is CVLExp.FieldSelectExp) {
            throw CertoraInternalException(
                CertoraInternalErrorType.CVL_COMPILER,
                "Unexpected form of expression $exp for immutable access"
            )
        }
        // base is contract
        val contractExp = exp.structExp as CVLExp.VariableExp

        val ty =
            (contractExp.getCVLType() as? CVLType.PureCVLType.Primitive.CodeContract)
                ?: throw CertoraInternalException(
                    CertoraInternalErrorType.CVL_COMPILER,
                    "Expected expression $contractExp of immutable access to be a CodeContract, got ${contractExp.getCVLType()}"
                )

        val contract = cvlCompiler.scene.getContract(ty.name)
        val immutName = exp.fieldName

        val tag = when (val cvlTag = exp.getOrInferPureCVLType().toTag()) {
            is Tag.Bool -> cvlTag
            else -> Tag.Bit256 // only Bit256 or Bool are possible currently for immutables
        }

        // hilariously (or maybe not so), our TAC variables for immutables can in fact only be Tag.Bit256 at the moment.
        val immutTacSym = TACKeyword.IMMUTABLE(Tag.Bit256 /* see comment above*/, immutName, contract.name)
        return CommandWithRequiredDecls(
            TACCmd.Simple.AssigningCmd.AssignExpCmd(
                out,
                if (tag == Tag.Bool) {
                    immutTacSym.asSym().convertToBool()
                } else {
                    immutTacSym.asSym()
                }
            ).plusMeta(
                CVL_EXP,
                CVLExpToTACExprMeta.NullaryCVLExp(exp)
            ),
            setOf(out, immutTacSym)
        ).toProgWithCurrEnv("immutable").toSimple()
    }

    @OptIn(InternalCVLCompilationAPI::class)
    private fun compileCVLBuiltinFoundryCheatcode(
        outVar: TACSymbol.Var?,
        exp: CVLExp.ApplyExp.CVLBuiltIn
    ): ParametricInstantiation<CVLTACProgram> {
        return when (exp.id) {
            CVLBuiltInName.FOUNDRY_PRANK -> {
                check(outVar == null) { "Foundry cheatcode prank doesn't return anything" }
                check(exp.args.size == 1) { "Foundry cheatcode prank should have just one argument" }
                val arg = exp.args.single()
                val (argProg, argOut) = compileExp(arg)
                val narrowed = TACSymbol.Var("prankedMsgSender", Tag.Bit256).toUnique("!")
                argProg.addSink(
                    CommandWithRequiredDecls(
                        listOf(
                            TACCmd.Simple.AssigningCmd.AssignExpCmd(
                                narrowed,
                                TACBuiltInFunction.SafeMathNarrow.Implicit(Tag.Bit256).toTACFunctionSym(),
                                listOf(argOut.asSym())
                            ),
                            TACCmd.Simple.AnnotationCmd(TACMeta.FOUNDRY_CHEATCODE, FoundryCheatcodes.Prank(narrowed))
                        ),
                        setOf(narrowed, argOut)
                    ),
                    compilationEnvironment
                )
            }

            CVLBuiltInName.FOUNDRY_START_PRANK -> {
                check(outVar == null) { "Foundry cheatcode startPrank doesn't return anything" }
                check(exp.args.size == 1) { "Foundry cheatcode startPrank should have just one argument" }
                val arg = exp.args.single()
                val (argProg, argOut) = compileExp(arg)
                val narrowed = TACSymbol.Var("startPrankedMsgSender", Tag.Bit256).toUnique("!")
                argProg.addSink(
                    CommandWithRequiredDecls(
                        listOf(
                            TACCmd.Simple.AssigningCmd.AssignExpCmd(
                                narrowed,
                                TACBuiltInFunction.SafeMathNarrow.Implicit(Tag.Bit256).toTACFunctionSym(),
                                listOf(argOut.asSym())
                            ),
                            TACCmd.Simple.AnnotationCmd(TACMeta.FOUNDRY_CHEATCODE, FoundryCheatcodes.StartPrank(narrowed))
                        ),
                        setOf(narrowed, argOut)
                    ),
                    compilationEnvironment
                )
            }

            CVLBuiltInName.FOUNDRY_STOP_PRANK -> {
                check(outVar == null) { "Foundry cheatcode stopPrank doesn't return anything" }
                check(exp.args.isEmpty()) { "Foundry cheatcode stopPrank should have just one argument" }
                    CommandWithRequiredDecls(
                        listOf(
                            TACCmd.Simple.AnnotationCmd(TACMeta.FOUNDRY_CHEATCODE, FoundryCheatcodes.StopPrank)
                        )
                    ).toProgWithCurrEnv("stopPrank cheatcode").toSimple()
            }

            CVLBuiltInName.FOUNDRY_WARP -> {
                check(outVar == null) { "Foundry cheatcode warp doesn't return anything" }
                check(exp.args.size == 1) { "Foundry cheatcode warp should have just one argument" }
                val arg = exp.args.single()
                val (argProg, argOut) = compileExp(arg)
                val narrowed = TACSymbol.Var("prankedNewTimestamp", Tag.Bit256).toUnique("!")
                argProg.addSink(
                    CommandWithRequiredDecls(
                        listOf(
                            TACCmd.Simple.AssigningCmd.AssignExpCmd(
                                narrowed,
                                TACBuiltInFunction.SafeMathNarrow.Implicit(Tag.Bit256).toTACFunctionSym(),
                                listOf(argOut.asSym())
                            ),
                            TACCmd.Simple.AnnotationCmd(TACMeta.FOUNDRY_CHEATCODE, FoundryCheatcodes.Warp(narrowed))
                        ),
                        setOf(narrowed, argOut)
                    ),
                    compilationEnvironment
                )
            }

            CVLBuiltInName.FOUNDRY_ROLL -> {
                check(outVar == null) { "Foundry cheatcode roll doesn't return anything" }
                check(exp.args.size == 1) { "Foundry cheatcode roll should have just one argument" }
                val arg = exp.args.single()
                val (argProg, argOut) = compileExp(arg)
                val narrowed = TACSymbol.Var("prankedNewblocknum", Tag.Bit256).toUnique("!")
                argProg.addSink(
                    CommandWithRequiredDecls(
                        listOf(
                            TACCmd.Simple.AssigningCmd.AssignExpCmd(
                                narrowed,
                                TACBuiltInFunction.SafeMathNarrow.Implicit(Tag.Bit256).toTACFunctionSym(),
                                listOf(argOut.asSym())
                            ),
                            TACCmd.Simple.AnnotationCmd(TACMeta.FOUNDRY_CHEATCODE, FoundryCheatcodes.Roll(narrowed))
                        ),
                        setOf(narrowed, argOut)
                    ),
                    compilationEnvironment
                )
            }

            CVLBuiltInName.FOUNDRY_MOCK_CALL -> {
                check(outVar == null) { "Foundry cheatcode mockCall doesn't return anything" }
                check(exp.args.size == 4) { "Foundry cheatcode mockCall should have exactly 4 arguments" }

                val (argProgs, argOuts) = exp.args.map { arg ->
                    val (argProg, argOut) = compileExp(arg)
                    argProg to argOut
                }.unzip()

                val argsProg = merge(argProgs, "inputs")

                val (callee, value, data, returnData) = argOuts

                val (narrowValueCmd, narrowedValue) = if (exp.args[1].getCVLType() != CVLType.PureCVLType.Primitive.NumberLiteral(BigInteger.ONE.negate())) {
                    val narrowed = TACSymbol.Var("mockCallValue", Tag.Bit256).toUnique("!")
                    TACCmd.Simple.AssigningCmd.AssignExpCmd(
                        narrowed,
                        TACBuiltInFunction.SafeMathNarrow.Implicit(Tag.Bit256).toTACFunctionSym(),
                        listOf(value.asSym())
                    ) to narrowed
                } else {
                    TACCmd.Simple.NopCmd to null
                }

                argsProg.addSink(
                    CommandWithRequiredDecls(
                        listOf(
                            narrowValueCmd,
                            TACCmd.Simple.AnnotationCmd(TACMeta.FOUNDRY_CHEATCODE, FoundryCheatcodes.MockCall(
                                callee,
                                narrowedValue,
                                CVLToSimpleCompiler.exposeArrayDataVar(data),
                                CVLToSimpleCompiler.exposeArrayLengthVar(data),
                                CVLToSimpleCompiler.exposeArrayDataVar(returnData),
                                CVLToSimpleCompiler.exposeArrayLengthVar(returnData)
                            ))
                        ),
                        setOfNotNull(narrowedValue, callee, value, data, returnData)
                    ),
                    compilationEnvironment
                )
            }

            CVLBuiltInName.FOUNDRY_CLEAR_MOCKED_CALLS -> {
                check(outVar == null) { "Foundry cheatcode clearMockedCalls doesn't return anything" }
                check(exp.args.isEmpty()) { "Foundry cheatcode clearMockedCalls should have just one argument" }
                CommandWithRequiredDecls(
                    listOf(
                        TACCmd.Simple.AnnotationCmd(TACMeta.FOUNDRY_CHEATCODE, FoundryCheatcodes.ClearMockedCalls)
                    )
                ).toProgWithCurrEnv("clearMockedCalls cheatcode").toSimple()
            }

            CVLBuiltInName.FOUNDRY_EXPECT_EMIT -> {
                CVTAlertReporter.reportAlert(
                    CVTAlertType.FOUNDRY,
                    CVTAlertSeverity.WARNING,
                    jumpToDefinition = null,
                    "Rule ${cvlCompiler.ruleName} uses `exectEmit` - this check is currently ignored by the Prover"
                )
                CommandWithRequiredDecls(TACCmd.Simple.NopCmd).toProgWithCurrEnv("foundry expectEmit").toSimple()
            }

            CVLBuiltInName.SHA256,
            CVLBuiltInName.KECCAK256,
            CVLBuiltInName.ECRECOVER -> `impossible!`
        }
    }

    /**
     * Compile the hash built ins (i.e., [CVLBuiltInName.SHA256] and [CVLBuiltInName.KECCAK256].
     *
     * These built-ins have two modes of operation: hashing a single bytes/string array, or hashing a variable number of
     * primitives. The type checker records which type of invocation [exp] is in with the [HashSort] annotation. This
     * function takes care of switching over that information, compiling the arguments and then exposing them
     * to the underlying hash. This function is used for both the keccak and sha256 functions, which are constructed in
     * different ways. Thus, the actual construction of the hash application is left to the two callbacks, [arrayCase]
     * and [primitiveCase].
     *
     * [arrayCase] is called when the invocation type is on a single bytes array; the first
     * argument to the callback is the logical length of the elements, and the second argument is the (0-indexed)
     * bytemap which contains the data to be hashed.
     *
     * [primitiveCase] is called when the hash is applied to a series of primitive values. In this case, the "length" of the
     * hash buffer (required for the hash families) is passed as the first argument. The second argument is a list of variables
     * that contain the compilation of the primitive arguments that have also been converted into their "buffer encoding",
     * i.e., values that are expressed in CVL with [Tag.Int] are translated into the [Tag.Bit256] representation.
     *
     * Both callbacks are expected to return code that effects the hash, this code is conjoined with the compilation/conversion
     * code and returned.
     */
    @OptIn(InternalCVLCompilationAPI::class)
    private fun compileBuiltinHash(
        exp: CVLExp.ApplyExp.CVLBuiltIn,
        arrayCase: (len: TACSymbol.Var, baseMap: TACSymbol.Var) -> CommandWithRequiredDecls<TACCmd.Simple>,
        primitiveCase: (len: TACSymbol.Const, syms: List<TACSymbol.Var>) -> CommandWithRequiredDecls<TACCmd.Simple>
    ) : ParametricInstantiation<CVLTACProgram> {
        val sort = exp.tag.annotation as HashSort
        return if (sort.isArray) {
            val arg = exp.args.single()
            val (param, tacVar) = allocatedTACSymbols.generateTransientUniqueCVLParam(
                cvlCompiler.cvl.symbolTable,
                "tmp",
                CVLType.PureCVLType.DynamicArray.PackedBytes
            )
            compileExp(param, arg).bind { prog ->
                val length = TACKeyword.TMP(Tag.Bit256, "!len").toUnique("!")
                val baseVar = CVLToSimpleCompiler.exposeArrayDataVar(tacVar)
                val hashCommands = arrayCase(length, baseVar)
                prog.appendToSinks(
                    CommandWithRequiredDecls(
                        listOf(
                            TACCmd.CVL.ArrayLengthRead(
                                lhs = length,
                                rhs = tacVar
                            ),
                        ), setOf(tacVar, length, baseVar)
                    ).merge(hashCommands)
                ).toSimple()
            }
        } else {
            exp.args.map {
                val (prog, out) = compileExp(it)
                val ty = it.getOrInferPureCVLType()
                check(ty is CVLType.PureCVLType.BufferEncodeableType) {
                    "expected a buffer-encodable type, got $ty"
                }
                val output = TACKeyword.TMP(Tag.Bit256, "enc").toUnique("!")
                prog.bind { p ->
                    p.appendToSinks(
                        EVMMoveSemantics.ValueConverters.convertValueTypeToEncoding(
                            dest = output,
                            src = out,
                            destEncoding = ty.getEncoding()
                        )
                    ).toSimple()
                } to output
            }.unzip().let { (programs, syms) ->
                ParametricMethodInstantiatedCode.merge(programs, "hash compilation").bind { p ->
                    p.appendToSinks(
                        primitiveCase(
                            TACSymbol.Const(syms.size.toBigInteger() * EVM_WORD_SIZE), syms
                        )
                    ).toSimple()
                }
            }
        }
    }

    fun compileCVLBuiltIn(
        outVar: TACSymbol.Var?,
        exp: CVLExp.ApplyExp.CVLBuiltIn
    ): ParametricInstantiation<CVLTACProgram> {
        return when(exp.id) {
            CVLBuiltInName.SHA256 -> {
                check(outVar != null) {
                    "Expected $exp to return a value"
                }
                compileBuiltinHash(
                    exp = exp,
                    arrayCase = { len: TACSymbol.Var, baseMap: TACSymbol.Var ->
                        TACUnboundedHashingUtils.fromByteMapRange(
                            hashFamily = HashFamily.Sha2,
                            start = TACExpr.zeroExpr,
                            length = len.asSym(),
                            map = baseMap
                        ).let { comp ->
                            CommandWithRequiredDecls(
                                comp.cmdsToAdd + TACCmd.Simple.AssigningCmd.AssignExpCmd(
                                    lhs = outVar,
                                    rhs = comp.exp
                                ),
                                comp.declsToAdd + outVar
                            )
                        }
                    },
                    primitiveCase = { len: TACSymbol.Const, syms: List<TACSymbol.Var> ->
                        CommandWithRequiredDecls(listOf(
                            TACCmd.Simple.AssigningCmd.AssignExpCmd(
                                lhs = outVar,
                                rhs = TACExpr.SimpleHash(
                                    hashFamily = HashFamily.Sha2,
                                    length = len.asSym(),
                                    args = syms.map { it.asSym() }
                                )
                            )
                        ), setOf(outVar) + syms)
                    }
                )
            }
            CVLBuiltInName.KECCAK256 -> {
                check(outVar != null) {
                    "Expected $exp to return a value"
                }
                compileBuiltinHash(
                    exp = exp,
                    arrayCase = { len: TACSymbol.Var, baseMap: TACSymbol.Var ->
                        CommandWithRequiredDecls(listOf(
                            TACCmd.Simple.AssigningCmd.AssignSha3Cmd(
                                lhs = outVar,
                                memBaseMap = baseMap,
                                op1 = TACSymbol.Zero,
                                op2 = len
                            )
                        ), setOf(len, baseMap, outVar))
                    },
                    primitiveCase = { len: TACSymbol.Const, syms: List<TACSymbol.Var> ->
                        CommandWithRequiredDecls(listOf(
                            TACCmd.Simple.AssigningCmd.AssignSimpleSha3Cmd(
                                lhs = outVar,
                                length = len,
                                args = syms
                            )
                        ), setOf(outVar) + syms)
                    }
                )
            }
            CVLBuiltInName.ECRECOVER -> {
                check(outVar != null) { "this bif should return a value" }
                check(exp.args.size == 4) {
                    "The typechecker should have verified the right number of arguments to ecrecover"
                }
                exp.args.map {
                    val(prog, out) = compileExp(it)
                    prog to out
                }.unzip().let { (progs, syms) ->
                    merge(progs, "ecrecover arguments compilation").bind { p ->
                        p.appendToSinks(
                            CommandWithRequiredDecls(
                                listOf(
                                    TACCmd.Simple.AssigningCmd.AssignExpCmd(
                                        outVar,
                                        TACExpr.Apply(
                                            TACBuiltInFunction.PrecompiledECRecover.toTACFunctionSym(),
                                            listOf(
                                                syms[0].asSym(),
                                                syms[1].asSym(),
                                                syms[2].asSym(),
                                                syms[3].asSym()
                                            ),
                                            Tag.Bit256
                                        )
                                    )
                                ),
                                outVar
                            )
                        ).toSimple()
                    }
                }
            }

            CVLBuiltInName.FOUNDRY_PRANK,
            CVLBuiltInName.FOUNDRY_START_PRANK,
            CVLBuiltInName.FOUNDRY_STOP_PRANK,
            CVLBuiltInName.FOUNDRY_WARP,
            CVLBuiltInName.FOUNDRY_ROLL,
            CVLBuiltInName.FOUNDRY_MOCK_CALL,
            CVLBuiltInName.FOUNDRY_CLEAR_MOCKED_CALLS,
            CVLBuiltInName.FOUNDRY_EXPECT_EMIT -> compileCVLBuiltinFoundryCheatcode(outVar, exp)
        }
    }

    private data class Inst(val v: ParametricInstantiation<CVLTACProgram>) : WithCVLProgram<Inst> {
        override fun mapCVLProgram(f: (CVLTACProgram) -> CVLTACProgram): Inst {
            return Inst(v.transformCode(f))
        }
    }

    /**
     * Compiles the complex access of [exp]. With [typeDirectedMovement], we could actually skip this handling,
     * except it would *dreadfully* slow, as we would be *constantly* relocating pointers. Instead, fully traverse [outVar]
     * to yield a [CVLDataInput], and then use [typeDirectedMovement] just once.
     */
    private fun compileComplexAccess(outVar: TACSymbol.Var, exp: CVLExp) : ParametricInstantiation<CVLTACProgram> {

        /**
         * As should be readily apparent by now, this traverses the expression in the "reverse" order of traversal (from outer
         * expression to innermost non-complex expression) by building up a chain of continuations in [input].
         * [exp] is the current expression, [input] is the current continuation that describes how to traverse a [CVLDataInput]
         * according to the "outer" expressions that lead to [exp]. It also accepts the list of accesses
         * represented by each step of [exp] produced. This is initialized with a continuation that
         * simply moves the resulting input into the output for [outVar] and adds the annotation to the result.
         */
        tailrec fun traversal(
                exp: CVLExp,
                outerMovement: CVLDataOutput?,
                input: (TACSymbol.Var, List<CVLCompiler.Companion.TraceMeta.CVLAccessPathStep>, CVLDataInput) -> Inst,
        ) : Inst {
            return when(exp) {
                is CVLExp.FieldSelectExp -> {
                    traversal(exp.structExp, null) { r, ap, structInput ->
                        if(exp.isDynamicArrayLengthExp()) {
                            structInput.dynamicArray { len, _, _ ->
                                /**
                                 * This must be non-null, because no "later" accesses can occur after `length`,
                                 * so when processing an array length expression, we must be the first traversal
                                 * invocation, which means we can move the value directly into [outerMovement], aka
                                 * [outVar].
                                 */
                                check(outerMovement != null) {
                                    "Invariant broken; length access is not the last step of complex expression"
                                }
                                Inst(outerMovement.movePrimitive { resVar, _ ->
                                    CommandWithRequiredDecls(listOf(
                                        TACCmd.Simple.AssigningCmd.AssignExpCmd(
                                            lhs = resVar,
                                            rhs = len.asSym()
                                        ),
                                        CVLCompiler.Companion.TraceMeta.ValueTraversalCmd(
                                            lhs = resVar, ap = ap + CVLCompiler.Companion.TraceMeta.CVLAccessPathStep.ArrayLength, base = r
                                        )
                                    ),  setOf(len, resVar)).toProgWithCurrEnv("move")
                                }.asSimple())
                            }
                        } else {
                            structInput.struct<Inst>().readField(exp.fieldName) {
                                input(r, ap + CVLCompiler.Companion.TraceMeta.CVLAccessPathStep.Field(exp.fieldName), it)
                            }
                        }
                    }
                }
                is CVLExp.ArrayDerefExp -> {
                    val ind = compileExp(exp.index)
                    val baseArrayType = exp.array.getOrInferPureCVLType()
                    val staticSize = (baseArrayType as? CVLType.PureCVLType.StaticArray)?.logicalLength

                    /**
                     * Effectively continues to call `input`, but conjoins whatever code input generates with
                     * the code to compile the index.
                     */
                    fun CVLDataInput.doAccess(r: TACSymbol.Var, ap: List<CVLCompiler.Companion.TraceMeta.CVLAccessPathStep>): Inst {
                        return Inst(ind.bindParam { idxVar ->
                            val (cmds, indSym) = indexCompiler(idxVar)
                            val cb = { ci: CVLDataInput ->
                                input(r, ap + CVLCompiler.Companion.TraceMeta.CVLAccessPathStep.ArrayElem(indSym), ci)
                            }
                            (cmds andThen (if(staticSize == null) {
                                this.dynamicArray { _, _, reader ->
                                    reader.readElem(indSym, cb)
                                }
                            } else {
                                this.staticArray(staticSize) { reader ->
                                    reader.readElem(indSym, cb)
                                }
                            })).v
                        })
                    }
                    if(exp.array.tag.annotation == ComplexMarker) {
                        traversal(exp.array, null) { r, ap, baseArray ->
                            baseArray.doAccess(r, ap)
                        }
                    } else {
                        /**
                         * The base case! just compile the expression, and then call doAccess
                         * which kicks off the continuation chain.
                         */
                        Inst(compileExp(exp.array).bindParam { arrayVar ->
                            CVLDataInput(arrayVar, compilationEnvironment.baseCallId).doAccess(arrayVar, listOf()).v
                        })
                    }
                }
                else -> throw IllegalArgumentException("Cannot use $exp")
            }
        }

        /**
         * Construct the object that we will move the result of the access to.
         */
        val outerMovement = CVLDataOutput(outVar, compilationEnvironment.baseCallId)
        return traversal(exp, outerMovement) { r, ap, input ->
            val concat = typeDirectedMovement(input, outerMovement, ty = exp.getOrInferPureCVLType()) andThen CommandWithRequiredDecls(
                CVLCompiler.Companion.TraceMeta.ValueTraversalCmd(
                    lhs = outVar,
                    ap = ap,
                    base = r
                )
            )
            Inst(concat.asSimple())
        }.v
    }

    private fun compileStorageComparison(
        comparisonType: ComparisonType,
        ref1: CVLExp.VariableExp,
        ref2: CVLExp.VariableExp,
        outVar: TACSymbol.Var,
        isEquality: Boolean
    ) : ParametricInstantiation<CVLTACProgram> {
        val v1 = allocatedTACSymbols.get(ref1.id, Tag.BlockchainState)
        val v2 = allocatedTACSymbols.get(ref2.id, Tag.BlockchainState)
        return getSimple(
            CommandWithRequiredDecls(listOf(
                TACCmd.CVL.CompareStorage(
                    outVar,
                    v1,
                    v2,
                    storageBasis = comparisonType.basis,
                    isEquality = isEquality,
                    useSkolemizaton = isEquality && comparisonType.trySkolem
                )
            ), outVar, v1, v2).toProgWithCurrEnv("storage compare")
        )
    }
    private fun compileBytes1ArrayComparison(
        left: CVLExp,
        right: CVLExp,
        outVar: TACSymbol.Var
    ) : ParametricInstantiation<CVLTACProgram> {
        compileExp(left).let { lCompiled ->
            compileExp(right).let { rCompiled ->
                val res = lCompiled.prog.getAsSimple()
                    .merge(rCompiled.prog.getAsSimple())
                    .merge(CommandWithRequiredDecls(listOf(
                        TACCmd.CVL.CompareBytes1Array(
                            outVar,
                            lCompiled.out,
                            rCompiled.out
                        )
                    ), outVar, lCompiled.out, rCompiled.out).toProgWithCurrEnv("array compare"))

                return getSimple(res)
            }
        }
    }
    private fun compileEnumConstant(
        outVar: TACSymbol.Var,
        exp: CVLExp.Constant.EnumConstant
    ): ParametricInstantiation<CVLTACProgram> {
        val enumType = exp.getCVLType() as CVLType.PureCVLType.Enum
        val ind = enumType.elements.indexOf(exp.memberName)
        check(ind != -1) {
            "Invalid enum constant name? Type checker should have caught this"
        }
        return getSimple(
            CommandWithRequiredDecls(listOf(
                TACCmd.Simple.AssigningCmd.AssignExpCmd(
                    lhs = outVar,
                    rhs = ind.asTACExpr
                )
            ), outVar).toProgWithCurrEnv("enum constant")
        )
    }

    private fun compileStructLiteralExpr(
        outVar: TACSymbol.Var,
        exp: CVLExp.Constant.StructLit
    ): ParametricInstantiation<CVLTACProgram> {
        fun traverseLiteral(
            exp: CVLExp.Constant.StructLit
        ) : ParametricInstantiation<Pair<TACExpr.StructConstant, CVLTACProgram>> {
            return exp.fieldNameToEntry.map { (fld, exp) ->
                if(exp is CVLExp.Constant.StructLit) {
                    traverseLiteral(exp).transformCode {
                        it.mapFirst { const ->
                            fld to const
                        }
                    }
                } else {
                    val fieldValue = compileExp(exp)
                    fieldValue.prog.transformCode {
                        (fld to fieldValue.out.asSym()) to it
                    }
                }
            }.flatten().transformCode {
                val (kv, argCompilation) = it.unzip()
                TACExpr.StructConstant(
                    kv.toMap(),
                tag = exp.getPureCVLType().toTag()
                ) to argCompilation.reduce(::mergeCodes)
            }
        }

        return traverseLiteral(exp).transformCode { (structConst, setup) ->
            setup.appendToSinks(
                CommandWithRequiredDecls(listOf(
                    TACCmd.Simple.AssigningCmd.AssignExpCmd(
                        lhs = outVar,
                        rhs = structConst
                    )
                ), outVar)
            )
        }
    }

    /**
     * Move data from [input] to [output], assuming that both point to values with the given [ty]. [ty] informs
     * which accessors are called on [input] and [output].
     */
    private fun typeDirectedMovement(
        input: CVLDataInput,
        output: CVLDataOutput,
        ty: CVLType.PureCVLType
    ) : CVLTACProgram {
        return when(ty) {
            is CVLType.PureCVLType.CVLValueType -> {
                input.readPrimitive { inPrim, ctxt ->
                    /**
                     * Read a primitive from [inPrim]
                     */
                    output.movePrimitive { outPrim, _ ->
                        /**
                         * And then move that into [outPrim]
                         */
                        CommandWithRequiredDecls(listOf(
                            TACCmd.Simple.AssigningCmd.AssignExpCmd(
                                lhs = outPrim,
                                rhs = inPrim
                            )
                        ), setOf(outPrim, inPrim)).toProg("move", ctxt)
                    }
                }
            }
            is CVLType.PureCVLType.Struct -> {
                input.struct<CVLTACProgram>().let { reader ->
                    /**
                     * Get the [reader] for a struct, and then write a struct...
                     */
                    output.startStruct(object : StructWriter {
                        override fun generateField(
                            output: CVLDataOutput,
                            fieldName: String,
                            fieldIndex: Int
                        ): CVLTACProgram {
                            /**
                             * Using the CVL input returned by [reader]
                             */
                            return reader.readField(fieldName) { input ->
                                typeDirectedMovement(
                                    input, output, ty.fields.single {
                                        it.fieldName == fieldName
                                    }.cvlType
                                )
                            }
                        }
                    })
                }
            }
            is CVLType.PureCVLType.DynamicArray.Bytes1Array -> {
                input.dynamicArray { sz, _, reader ->
                    reader.toOutput { buff ->
                        /**
                         * Allocate a dynamic array of length [sz] and copy the buffer pointer
                         * from [reader] into [ArrayWriter] returned from output
                         */
                        output.startDynamicArray(sz).longCopy(buff)
                    }
                }
            }
            is CVLType.PureCVLType.StaticArray -> {
                input.staticArray(ty.logicalLength) { reader ->
                    /**
                     * Get a [reader] for the static array, and then allocate a static array with that length
                     */
                    output.startStaticArray(ty.logicalLength).foreachElem { i, elemOut ->
                        /**
                         * For each element of this array, read that element from [reader] and write it into the [elemOut]
                         */
                        reader.readElem(i) { elemIn ->
                            typeDirectedMovement(
                                output = elemOut,
                                input = elemIn,
                                ty = ty.elementType
                            )
                        }
                    }
                }
            }
            is CVLType.PureCVLType.DynamicArray -> {
                input.dynamicArray { len, _, reader ->
                    /**
                     * Read the dynamic array which has length [len], and then get the writer for an array with
                     * that length.
                     */
                    val writer = output.startDynamicArray(len = len)
                    if(ty.elementType is CVLType.PureCVLType.CVLValueType) {
                        /**
                         * If this is a value type, just long copy
                         */
                        reader.toOutput {
                            writer.longCopy(it)
                        }
                    } else {
                        /**
                         * Otherwise, iterate in the writer, and using the [reader] to get the reader for that
                         * element.
                         *
                         * Note that this requires unrolling, which means we can run into an unrolling bound.
                         */
                        writer.foreachElem { i, elemOut ->
                            reader.readElem(i) { elemIn ->
                                typeDirectedMovement(
                                    output = elemOut,
                                    input = elemIn,
                                    ty = ty.elementType
                                )
                            }
                        }
                    }
                }
            }

            CVLType.PureCVLType.Bottom -> {
                CommandWithRequiredDecls(TACCmd.Simple.NopCmd).toProg("empty", compilationEnvironment)
            }
            is CVLType.PureCVLType.ArrayLiteral,
            is CVLType.PureCVLType.Ghost,
            is CVLType.PureCVLType.Sort,
            is CVLType.PureCVLType.TransientTypes,
            is CVLType.PureCVLType.VMInternal -> throw UnsupportedOperationException("Do not know how to move type $ty")
        }
    }

    /**
     * A much more efficient implementation here would actually use [CVLDataOutput] in the call to [compileExp], but that
     * will be an *enormous* pain in the ass.
     */
    private fun compileArrayLitExp(
        out: TACSymbol.Var,
        exp: CVLExp.ArrayLitExp
    ): ParametricInstantiation<CVLTACProgram> {
        val ty = exp.getPureCVLType()
        check(ty is CVLType.PureCVLType.ArrayLiteral) {
            "Attempting to compile an expression that is not typed as an array literal"
        }
        val dataOutput = CVLDataOutput(
            v = out,
            id = compilationEnvironment.baseCallId
        )

        /**
         * Note that we don't *actually* know whether this is a dynamic or static array, but it's harmless
         * to set the length of a static array.
         */
        val writer = dataOutput.startDynamicArray(exp.elements.size.asTACSymbol())
        val (elemSyms, elemCompilation) = exp.elements.map {
            val x = compileExp(it)
            x.out to x.prog
        }.unzip()
        val l = writer.foreachElem { i, output ->
            /**
             * We expect this to pass, because this loop unrolls up to the literal array length when it can,
             * which we have in this case (see the call to [CVLDataOutput.startDynamicArray] above which uses
             * a const symbol)
             */
            check(i < exp.elements.size) {
                "Unexpected invariant violation, unrolling iteration $i but we only have ${exp.elements.size} elements?"
            }
            val input = CVLDataInput(elemSyms[i], compilationEnvironment.baseCallId)
            typeDirectedMovement(input = input, output = output, ty.leastUpperBoundElementType)
        }
        return mergeMany(elemCompilation + getSimple(l), compilationEnvironment.baseCallId.emptyProgram(), ::mergeCodes)
    }

    private fun compileArrayDerefExp(
        out: TACSymbol.Var,
        exp: CVLExp.ArrayDerefExp
    ): ParametricInstantiation<CVLTACProgram> {

        return when (val ty = exp.array.getOrInferPureCVLType()) {
            is CVLType.PureCVLType.Ghost.Mapping -> compileGhostMappingExp(out, exp)
            is CVLType.PureCVLType.VMInternal.BlockchainStateMap -> {
                compileExp(exp.array).bindParam { mapVar ->
                    compileExp(exp.index).bindCmd { i ->
                        check(i.tag == Tag.Bit256 || i.tag == Tag.Int)

                        val idxAsBitvector = i.copy(tag = Tag.Bit256).toUnique(".")
                        val base = exp.baseCVLKeyword()?.let { TACKeyword.findByCVLCorrespondence(it)?.let { mapVar.withMeta(KEYWORD_ENTRY, it.keywordEntry) } } ?: mapVar
                        CommandWithRequiredDecls.mergeMany(
                            CastToUnsignedInt(Config.VMConfig.registerBitwidth).compileAssertCast(
                                idxAsBitvector,
                                i
                            ),
                            CommandWithRequiredDecls(
                                listOfNotNull(
                                    TACCmd.Simple.AssigningCmd.WordLoad(
                                        lhs = out,
                                        base = base,
                                        loc = idxAsBitvector
                                    ),
                                    if (exp.isBalances()) {
                                        SnippetCmd.EVMSnippetCmd.ReadBalanceSnippet(out, idxAsBitvector).toAnnotation()
                                    } else {
                                        null
                                    }
                                ), setOf(mapVar, i, out, idxAsBitvector)
                            ),
                            cvlCompiler.ensureBitWidth(exp.getPureCVLType(), out)
                        )
                    }
                }
            }
            is CVLType.PureCVLType.CVLArrayType -> {
                compileExp(exp.array).bindParam { arrVar ->
                    compileExp(exp.index).bindProg { i ->
                        // an array index is type checked to be UInt256 and so is guaranteed to fit into a Bit256
                        val idxAsBitvector = i.copy(tag = Tag.Bit256).toUnique(".")
                        val idxNarrow = CastToUnsignedInt(Config.VMConfig.registerBitwidth).compileAssertCast(
                            idxAsBitvector,
                            i
                        )
                        val (idxCmp, idx) = indexCompiler(idx = idxAsBitvector)
                        val outWriter = CVLDataOutput(out, compilationEnvironment.baseCallId)
                        val inWriter = CVLDataInput(arrVar, compilationEnvironment.baseCallId)
                        val moveData = inWriter.dynamicArray { _, _, reader ->
                            reader.readElem(idx) { elemReader ->
                                typeDirectedMovement(input = elemReader, output = outWriter, ty.elementType)
                            }
                        }

                        val constrainOutput = when (ty) {
                            is CVLType.PureCVLType.DynamicArray.PackedBytes -> {
                                // In the case of a packed bytes array, we cannot just ensure the bitWidth with assumes (as `ensureBitWidth` does).
                                // This is because the read of an element is not a clean Bytes1, it reads whatever is in the array starting from the accessed element,
                                // but reading a full 256 bits. So here we replace the lower bits with zero, to have a clean BytesK representation,
                                // as we expect it. If we do it with assumes, we kill traces where the array is not zeroed out after the read element,
                                // typically creating vacuity.
                                check(ty.elementType is CVLType.PureCVLType.Primitive.BytesK) { "Expected PackedBytes to have a BytesK elemType, got ${ty.elementType}" }
                                maskUpperKBits(out, out, (ty.elementType as CVLType.PureCVLType.Primitive.BytesK).bitWidth)
                            }

                            else -> cvlCompiler.ensureBitWidth(exp.getPureCVLType(), out)
                        }

                        idxNarrow andThen idxCmp andThen moveData.appendToSinks(constrainOutput) andThen CommandWithRequiredDecls(
                            CVLCompiler.Companion.TraceMeta.ValueTraversalCmd(lhs = out, listOf(CVLCompiler.Companion.TraceMeta.CVLAccessPathStep.ArrayElem(idx)), base = arrVar)
                        )
                    }
                }
            }
            else -> throw IllegalStateException("Array is not an array type ${exp.array}")
        }
        // note: no out of bounds checking - will just havoc
    }

    private fun compileSignatureliteralExp(
            out: CVLParam,
            exp: CVLExp.Constant.SignatureLiteralExp
    ): ParametricInstantiation<CVLTACProgram> {
        val structLit = exp.tag.annotation as CVLExp.Constant.StructLit
        return compileExp(out, structLit)
    }

    private fun compileGhostMappingExp(
        out: TACSymbol.Var,
        exp: CVLExp.ArrayDerefExp,
    ): ParametricInstantiation<CVLTACProgram> {
        val array = exp.baseArray as? CVLExp.VariableExp
            ?: throw UnsupportedOperationException("expecting a variable here ($exp), got ${exp.array}")
        val arrayType = array.getPureCVLType() as? CVLType.PureCVLType.Ghost.Mapping
            ?: error("variable $array must have CVLMappingType at this point (got ${array.getCVLType()}")
        val arrayTacSym: TACSymbol.Var = allocatedTACSymbols
            .get(array.id, arrayType.toTag())
            .withMeta(CVL_TYPE, arrayType)

        return compileGhostAccess(
            baseVar = arrayTacSym,
            args = exp.indices,
            out = out,
            ghostAccessExp = exp,
            types = arrayType.getKeys(),
            sort = GhostSort.Mapping,
            persistent = cvlCompiler.fetchGhostDeclaration(array.id)?.persistent == true
        )
    }

    @OptIn(InternalCVLCompilationAPI::class)
    private fun compileGhostAccess(
        baseVar: TACSymbol.Var,
        args: List<CVLExp>,
        out: TACSymbol.Var,
        ghostAccessExp: CVLExp,
        types: List<CVLType.PureCVLType>,
        sort: GhostSort,
        persistent: Boolean
    ) : ParametricInstantiation<CVLTACProgram> {
        val compiledArgs = args.map { argExp ->
            compileExp(argExp).withMeta(CVL_TYPE, argExp.getOrInferPureCVLType())
        }

        val declsToRegister = mutableSetOf<TACSymbol.Var>()
        val newCmdsToAdd = mutableListOf<TACCmd.Spec>()

        val wrappedIndex = compiledArgs.mapIndexed { ind, a ->
            if(GhostTypeRewriter.isByteBlobCandidate(args[ind].getCVLType()) && types[ind] == CVLType.PureCVLType.Primitive.HashBlob) {
                // TODO(jtoman): use new assigning hash command
                val (hashExp, newDecls, newCmds) =
                    TACExpr.SimpleHash.fromByteMapRange(HashFamily.Keccack, a.out)
                declsToRegister.addAll(newDecls)
                newCmdsToAdd.addAll(newCmds)
                hashExp
            } else {
                a.out.asSym()
            }
        }

        val cmds = listOf(
            TACCmd.Simple.AssigningCmd.AssignExpCmd(
                lhs = out,
                rhs = TACExpr.Select.buildMultiDimSelect(baseVar.asSym(), wrappedIndex)
            ),
            SnippetCmd.CVLSnippetCmd.GhostRead(
                returnValueSym = out,
                returnValueExp = ghostAccessExp,
                indices = compiledArgs.map { it.out },
                sort = sort,
                persistent = persistent
            ).toAnnotation(),
        )
        val ghostAccessProg = buildTACFromListOfCommands(
            newCmdsToAdd + cmds,
            compilationEnvironment,
            symbolTable = cvlCompiler.tacSymbolTable.mergeDecls(declsToRegister) // ugh, this "global" symbolTable variable seems _bad_..
            )

        val compiledGhost = mergeProgs(
            compiledArgs.map { it.prog }.reduce(::mergeProgs),
            getSimple(ghostAccessProg),
        )

        val outputType = ghostAccessExp.getPureCVLType()
        check(outputType is CVLType.PureCVLType.GhostMappingKeyType) {
            "Internal error: output type $outputType of ghost application $ghostAccessExp is not a ghost mapping result"
        }
        return when(outputType) {
            is CVLType.PureCVLType.Sort -> compiledGhost
            is CVLType.PureCVLType.CVLValueType -> compiledGhost.addSink(
                cvlCompiler.ensureValueBitwidth(
                    outputType,
                    toConstrain = out.asSym()
                ),
                compilationEnvironment
            )
        }
    }

    /**
     * Generates a temporary variable, and returns it (as an [CVLLhs.Id] plus code that assigns it to the [lhs]. The
     * caller must assign to the temporary variable the rhs of the original assignment, and append the returned code to
     * that. So the end result will look like:
     * ```
     * tempVar = rhs // The caller must do this
     * userArray[index] = tempVar // This function creates this
     * ```
     */
    private fun compileArrayAssignment(
        lhs: CVLLhs.Array,
        allocatedTACSymbols: TACSymbolAllocation
    ): Pair<CVLLhs.Id, ParametricInstantiation<CVLTACProgram>> {
        val array = lhs.getIdLhs()
        val indices = lhs.indices
        val arrayType = when (val typ = array.getCVLType()) {
            is CVLType.PureCVLType.Ghost.Mapping -> typ
            else -> throw CertoraInternalException(
                CertoraInternalErrorType.CVL_COMPILER,
                "Attempt to assign to array of type $typ which is currently unsupported"
            )
        }
        val arraySym = allocatedTACSymbols.get(array.id, arrayType.toTag())
        val (indexProgs, indexVars) = indices.map { compileExp(it).withMeta(CVL_TYPE, it.getOrInferPureCVLType()) }.unzip()

        val lhsType = lhs.getPureCVLType()
        val (cvlParam, tmpOutVar) = allocatedTACSymbols.generateTransientUniqueCVLParam(
            cvlCompiler.cvl.symbolTable,
            CVLParam(lhsType, "tmpGhostMappingAssignment", lhs.getRangeOrEmpty())
        ).mapSecond {
            it.withMeta(CVL_VAR, true)
                .withMeta(CVL_TYPE, lhsType)
                .withMeta(CVL_DISPLAY_NAME, array.id)
        }
        val tmpOutCVLExp = CVLExp.VariableExp(cvlParam.id, CVLExpTag.transient(lhsType))

        val ghostSnippet = buildTACFromCommand(
            SnippetCmd.CVLSnippetCmd.GhostAssignment(
                lhs = tmpOutVar,
                rhsExp = tmpOutCVLExp,
                indices = indexVars.zip(indices),
                sort = GhostSort.Mapping,
                persistent = cvlCompiler.fetchGhostDeclaration(array.id)?.persistent == true,
            ).toAnnotation()
        )

        val assignAsProg = buildTACFromCommand(
            TACCmd.Simple.AssigningCmd.AssignExpCmd(
                arraySym,
                cvlCompiler.exprFact.Store(
                    arraySym.asSym(),
                    indexVars.map { it.asSym() },
                    tmpOutVar.asSym()
                )
            )
        )
        val prog = indexProgs
            .reduce { acc, indexProg -> mergeProgs(acc, indexProg) }
            .andThen(assignAsProg)
            .andThen(ghostSnippet)

        return tmpOutCVLExp.toLhs() as CVLLhs.Id to prog
    }

    fun compileAssignment(
        assignedTo: List<CVLLhs>,
        allocatedTACSymbols: TACSymbolAllocation,
        exp: CVLExp
    ): ParametricInstantiation<CVLTACProgram> {
        val (lhsIds, progsToAppend) = assignedTo.mapIndexed { idx, lhs ->
            when (lhs) {
                is CVLLhs.Id -> {
                    val ghost = cvlCompiler.fetchGhostDeclaration(lhs.id)
                    if(ghost is CVLGhostDeclaration.Variable){
                        lhs to buildTACFromCommand(
                            SnippetCmd.CVLSnippetCmd.GhostAssignment(
                                lhs = allocatedTACSymbols.get(lhs.id),
                                rhsExp = exp,
                                multiAssignIndex = idx.takeIf { assignedTo.size > 1 },
                                sort = GhostSort.Variable,
                                persistent = ghost.persistent,
                            ).toAnnotation()
                        ).toSimple()
                    } else {
                        /**
                         * In the case of a CVL function that assigns a value, the variable
                         * that is assigned to should _not_ be within the two commands
                         * [SnippetCmd.CVLSnippetCmd.CVLFunctionStart] and [SnippetCmd.CVLSnippetCmd.CVLFunctionEnd].
                         * Therefore, we introduce temp variables that [compileCVLFunctionApplication] will assign to.
                         *
                         */
                        if(exp is CVLExp.ApplyExp.CVLFunction){
                            val lhsType = (exp.tag.type as? CVLType.PureCVLType.TupleType)?.elements?.get(idx) ?: exp.tag.type as CVLType.PureCVLType
                            val (cvlParam, tmpOutVar) = allocatedTACSymbols.generateTransientUniqueCVLParam(
                                cvlCompiler.cvl.symbolTable,
                                CVLParam(lhsType, "tmpVariableToAssignTo", lhs.getRangeOrEmpty())
                            ).mapSecond {
                                it.withMeta(CVL_VAR, true)
                                    .withMeta(CVL_TYPE, lhsType)
                            }
                            val tmpOutCVLExp = CVLExp.VariableExp(cvlParam.id, CVLExpTag.transient(lhsType))
                            val outputVar = allocatedTACSymbols.get(lhs.id, tmpOutVar.tag)
                            tmpOutCVLExp.toLhs() as CVLLhs.Id to buildTACFromCommand(
                                TACCmd.Simple.AssigningCmd.AssignExpCmd(
                                    outputVar,
                                    tmpOutVar
                                ),
                            ).toSimple()
                        } else {
                            lhs to buildTACFromCommand(TACCmd.Simple.NopCmd).toSimple()
                        }
                    }
                }
                is CVLLhs.Array -> {
                    compileArrayAssignment(lhs,allocatedTACSymbols)
                }
            }
        }.unzip()

        val params = lhsIds.map { it.toParamPureType() }

        val expProg = when (exp) {
            is CVLExp.ApplyExp.ContractFunction -> cvlCompiler.compileContractFunctionInvocation(exp, params, allocatedTACSymbols, compilationEnvironment)

            is CVLExp.ApplyExp.CVLFunction -> cvlCompiler.compileCVLFunctionApplication(exp, params, allocatedTACSymbols, compilationEnvironment)

            is CVLExp.AddressFunctionCallExp -> cvlCompiler.compileAddressFunctionApplication(exp, params, allocatedTACSymbols, compilationEnvironment)

            is CVLExp.UnresolvedApplyExp -> {
                check(exp.tag.annotation == CVLExp.UnresolvedApplyExp.VirtualFunc) {
                    "The only unresolvedApply expressions that make sense here are of virtual functions"
                }
                throw CertoraException(
                    CertoraErrorType.CVL,
                    "The function `${exp.methodId}` was marked `optional` in the spec file, yet this rule tried to inline it (perhaps within some function summary?), so this rule cannot run."
                )
            }

            else -> {
                val param = params.singleOrNull()
                    ?: throw IllegalStateException("For exp $exp, expecting to assign to just a single variable, got $assignedTo")
                compileExp(param, exp)
            }
        }

        val lastStorageUpdate = buildTACFromCommand(
            TACCmd.CVL.CopyBlockchainState(
                lhs = CVLKeywords.lastStorage.toVar(),
                meta = MetaMap(TACMeta.LAST_STORAGE_UPDATE)
            )
        ).toSimple()

        val prog = (progsToAppend + listOf(lastStorageUpdate)).fold(expProg) { acc, toAppend ->
            mergeProgs(acc, toAppend)
        }

        return prog
    }

    companion object {
        /**
         * Generates code to transform a logical index [idx] in a "native math" representation
         * into a low-level index representation. The first
         * component performs the conversion (and bounds the length), the second is the symbol which holds the result
         * of the conversion.
         */
        fun indexCompiler(idx: TACSymbol.Var) : Pair<CommandWithRequiredDecls<TACCmd.Simple>, TACSymbol.Var> {
            val (prefix, bitIdx) = if(idx.tag is Tag.Int) {
                val symbol = CVLReservedVariables.certoraTmp.toVar(Tag.Bit256).toUnique("!")
                val idxAssumeSym = TACKeyword.TMP(Tag.Bool, "!lenBound").toUnique("!")
                CommandWithRequiredDecls<TACCmd.Simple>(
                    listOf(
                        /*
                          This safe narrowing is, well, safe because the typechecker guarantees that the idx has a type that is
                          a subtype of uint256, which, despite being represented with an Int will:
                          1. not require twos complement conversion
                          2. Be less than 2^256
                         */
                        TACCmd.Simple.AssigningCmd.AssignExpCmd(
                            lhs = symbol,
                            rhs = TACExpr.Apply(
                                TACBuiltInFunction.SafeMathNarrow.Implicit(Tag.Bit256),
                                listOf(idx.asSym()),
                                Tag.Bit256
                            )
                        ),
                        TACCmd.Simple.AssigningCmd.AssignExpCmd(
                            lhs = idxAssumeSym,
                            rhs = TACExpr.BinRel.Lt(
                                symbol.asSym(),
                                BigInteger.TWO.pow(Config.VMConfig.maxArraySizeFactor).asTACExpr,
                                Tag.Bool
                            )
                        ),
                        TACCmd.Simple.AssumeCmd(idxAssumeSym, "indexCompiler")
                    ), setOfNotNull(symbol, idx as? TACSymbol.Var, idxAssumeSym)
                ) to symbol
            } else {
                CommandWithRequiredDecls<TACCmd.Simple>() to idx
            }
            return prefix to bitIdx
        }

        fun compileBoolLit(c: CVLExp.Constant.BoolLit): TACSymbol.Const = TACSymbol.Const(c.b, Tag.Bool)

        fun compileNumberLit(c: CVLExp.Constant.NumberLit): TACSymbol.Const {
            val tag = if (c.n < BigInteger.ZERO || c.n >= EVM_MOD_GROUP256) {
                Tag.Int
            } else {
                Tag.Bit256
            }
            return TACSymbol.Const(c.n, tag)
        }

        fun compileConstCastExp(exp: CVLExp.CastExpr): TACSymbol.Const {
            require(exp.toCastType is CVLType.PureCVLType.Primitive.BytesK && exp.arg is CVLExp.Constant.NumberLit)
            return CodeGenUtils.numAsBytesKConst(
                (exp.arg as CVLExp.Constant.NumberLit).n,
                (exp.toCastType as CVLType.PureCVLType.Primitive.BytesK).bitWidth
            )
        }
    }
}
