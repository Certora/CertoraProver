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

@file:Suppress("NAME_SHADOWING") // Shadowing is used purposefully
package spec.cvlast.typechecker

import bridge.EVMExternalMethodInfo
import bridge.types.SolcLocation
import config.Config
import datastructures.stdcollections.*
import log.*
import scene.MethodAttribute
import spec.CVLKeywords
import spec.CVLWarningLogger
import spec.CastType
import spec.cvlast.*
import spec.cvlast.CVLExp.HavocTarget.Companion.downcastToHavocTarget
import spec.cvlast.CVLType.Companion.isComplex
import spec.cvlast.Function
import spec.cvlast.transformer.CVLExpTransformer
import spec.cvlast.typedescriptors.*
import tac.ITACSymbolVar
import utils.*
import utils.CollectingResult.Companion.asError
import utils.CollectingResult.Companion.bind
import utils.CollectingResult.Companion.flatten
import utils.CollectingResult.Companion.flattenToVoid
import utils.CollectingResult.Companion.lift
import utils.CollectingResult.Companion.map
import utils.CollectingResult.Companion.mapErrors
import utils.CollectingResult.Companion.ok
import utils.ErrorCollector.Companion.collectingErrors
import java.math.BigInteger

class CVLExpTypeCheckerWithContext(
    private val symbolTable: CVLSymbolTable,
    private val typeEnv: CVLTypeEnvironment
) : CVLExpTransformer<CVLError> {
    private val typeTypeChecker = CVLTypeTypeChecker(symbolTable)

    /**
     * Keep track of how deeply we're nested into the expressions. Specifically, this is used to catch expression which
     * aren't allowed to be subexpressions of other expressions (e.g. [CVLExp.ApplyExp.ContractFunction.Symbolic])
     */
    private var exprDepth: Int = 0

    override fun expr(exp: CVLExp): CollectingResult<CVLExp, CVLError> {
        exprDepth++
        val ret = super.expr(exp)
        exprDepth--
        return ret
    }

    /** this kludge exists because havoc target variables require special treatment */
    fun havocTarget(target: CVLExp.HavocTarget): CollectingResult<CVLExp.HavocTarget, CVLError> {
        exprDepth++

        val typeChecked: CollectingResult<CVLExp, CVLError> = when (target) {
            is CVLExp.ArrayDerefExp -> arrayderef(target)
            is CVLExp.FieldSelectExp -> fieldSel(target)
            is CVLExp.VariableExp -> havocTargetVariable(target)
        }

        exprDepth--

        return typeChecked.downcastToHavocTarget()
    }

    private fun fullWordTypeForNumberLit(t: CVLType.PureCVLType.Primitive.NumberLiteral) : CVLType.PureCVLType =
        if (t.n < BigInteger.ZERO) {
            CVLType.PureCVLType.Primitive.IntK(256)
        } else {
            CVLType.PureCVLType.Primitive.UIntK(256)
        }

    private fun <T : CVLExp> bitwiseWarning(exp: T): T =
        exp.also {
            CVLWarningLogger.syntaxWarning(
                "Usage of bitwise operation $exp is experimental. Use with caution.",
                exp.getRangeOrEmpty()
            )
        }

    private fun numberLitToLeastUpperBoundInt(t: CVLType.PureCVLType): CVLType.PureCVLType =
        if (t is CVLType.PureCVLType.Primitive.NumberLiteral) {
            t.smallestTypeForNumberLit()
        } else {
            t
        }

    private fun numberLitToLeastUpperBoundInt(t: CVLType): CVLType =
        (t as? CVLType.PureCVLType)?.let(::numberLitToLeastUpperBoundInt) ?: t

    /**
     * Types bitwise operations whose operands could be swapped.
     */
    private inline fun <reified T : CVLExp.BinaryExp> typeCommutativeBitwiseOp(exp: CollectingResult<T, CVLError>) =
        exp.bind { exp ->
            ensureValidBitwiseOperand(exp.l).bind(ensureValidBitwiseOperand(exp.r)) { _, _ ->
                val lType = exp.l.getCVLTypeOrNull()!!
                val rType = exp.r.getCVLTypeOrNull()!!
                exp.eval()?.let {
                    updateType(exp, CVLType.PureCVLType.Primitive.NumberLiteral(it.n))
                } ?: getPureOrCorrespondenceLeastUpperBound(exp.l, exp.r) {
                    // revisit if we ever end up with VM Types that could be used but don't have relevant "isomorphic"
                    // types, we can always check for convertibility to fallback types (uint256, int256, bytes32)
                    CVLError.Exp(exp, "One operand of $exp must be a subtype of the other, but got types $lType and $rType")
                }.bind { type ->
                    updateType(exp, type)
                }
            }
        }

    /**
     * Types Bool -> Bool -> Bool expressions
     */
    private inline fun <reified T : CVLExp.BinaryExp> typeBinaryBooleanExp(exp: CollectingResult<T, CVLError>) =
        exp.bind { exp -> ensureBoolean(exp.l, exp.r, exp) }

    /**
     * Types T -> S -> Mathint expressions where T <: Mathint and S <: Mathint
     */
    private inline fun <reified T : CVLExp.BinaryExp> typeMathintExp(exp: CollectingResult<T, CVLError>) =
        exp.bind { exp ->
            ensureInteger(exp.l, exp.r, exp)
        }

    private inline fun <reified T : CVLExp.BinaryExp> typeShiftOp(exp: CollectingResult<T, CVLError>) =
        exp.bind { exp ->
            ensureValidBitwiseOperand(exp.l).map(ensureIsConvertible(exp.r, CVLType.PureCVLType.Primitive.UIntK(256))) { _, _ ->
                exp
            }
        }.bind { exp ->
            exp.eval()?.let {
                // Since we're dealing with bitwise operations here, limit the width of the result from the operation to
                // within the VM's bitwidth. Relevant e.g. for lshift
                val n = it.n.remainder(Config.VMConfig.maxUint + BigInteger.ONE)
                updateType(exp, CVLType.PureCVLType.Primitive.NumberLiteral(n))
            } ?:
            exp.l.getOrInferPureCVLTypeDefaultError().bind { type ->
                if (type is CVLType.PureCVLType.Primitive.NumberLiteral) {
                    // already checked in ensureValidBitwiseOperand that the numberliteral is convertible to
                    // either an int256 or a uin256
                    updateType(exp, fullWordTypeForNumberLit(type))
                } else {
                    updateType(exp, type)
                }
            }
        }

    override fun bwand(exp: CVLExp.BinaryExp.BwAndExp) =
        super.bwand(exp).let(::typeCommutativeBitwiseOp).map(::bitwiseWarning)

    override fun bwlsh(exp: CVLExp.BinaryExp.BwLeftShiftExp): CollectingResult<CVLExp.BinaryExp.BwLeftShiftExp, CVLError> =
        super.bwlsh(exp).let(::typeShiftOp).map(::bitwiseWarning)

    override fun bwor(exp: CVLExp.BinaryExp.BwOrExp): CollectingResult<CVLExp.BinaryExp.BwOrExp, CVLError> =
        super.bwor(exp).let(::typeCommutativeBitwiseOp).map(::bitwiseWarning)

    override fun bwrsh(exp: CVLExp.BinaryExp.BwRightShiftExp): CollectingResult<CVLExp.BinaryExp.BwRightShiftExp, CVLError> =
        super.bwrsh(exp).let(::typeShiftOp).map(::bitwiseWarning)

    override fun bwrshwzeros(exp: CVLExp.BinaryExp.BwRightShiftWithZerosExp): CollectingResult<CVLExp.BinaryExp.BwRightShiftWithZerosExp, CVLError> =
        super.bwrshwzeros(exp).let(::typeShiftOp).map(::bitwiseWarning)

    override fun bwxor(exp: CVLExp.BinaryExp.BwXOrExp): CollectingResult<CVLExp.BinaryExp.BwXOrExp, CVLError> =
        super.bwxor(exp).let(::typeCommutativeBitwiseOp).map(::bitwiseWarning)

    override fun bwnot(exp: CVLExp.UnaryExp.BwNotExp): CollectingResult<CVLExp.UnaryExp.BwNotExp, CVLError> =
        super.bwnot(exp).bind { exp ->
            ensureValidBitwiseOperand(exp.e).map {
                exp
            }
        }.bind { exp ->
            exp.eval()?.let {
                updateType(exp, CVLType.PureCVLType.Primitive.NumberLiteral(it.n))
            } ?: exp.e.getOrInferPureCVLTypeDefaultError().bind { type ->
                updateType(exp, type)
            }
        }.map(::bitwiseWarning)

    override fun add(exp: CVLExp.BinaryExp.AddExp): CollectingResult<CVLExp.BinaryExp.AddExp, CVLError> =
        super.add(exp).let(::typeMathintExp)

    override fun mul(exp: CVLExp.BinaryExp.MulExp): CollectingResult<CVLExp.BinaryExp.MulExp, CVLError> =
        super.mul(exp).let(::typeMathintExp)

    override fun sub(exp: CVLExp.BinaryExp.SubExp): CollectingResult<CVLExp.BinaryExp.SubExp, CVLError> =
        super.sub(exp).let(::typeMathintExp)

    override fun div(exp: CVLExp.BinaryExp.DivExp): CollectingResult<CVLExp.BinaryExp.DivExp, CVLError> =
        super.div(exp)
            .bind { exp ->
                val rType = exp.r.getCVLType()
                if (rType is CVLType.PureCVLType.Primitive.NumberLiteral && rType.n == BigInteger.ZERO) {
                    // 1. prevent spinning SMT on finding a constant div by zero
                    // 2. ensure eval() for div doesn't throw exception
                    CVLError.Exp(exp, "dividing by zero is not supported").asError()
                } else {
                    exp.lift()
                }
            }
            .let(::typeMathintExp)

    override fun unaryMinus(exp: CVLExp.UnaryExp.UnaryMinusExp): CollectingResult<CVLExp.UnaryExp.UnaryMinusExp, CVLError> =
        super.unaryMinus(exp).bind { exp ->
            exp.eval()?.let {
                updateType(exp, CVLType.PureCVLType.Primitive.NumberLiteral(it.n))
            } ?: updateType(exp, CVLType.PureCVLType.Primitive.Mathint)
        }

    override fun exponent(exp: CVLExp.BinaryExp.ExponentExp): CollectingResult<CVLExp.BinaryExp.ExponentExp, CVLError> =
        super.exponent(exp).bind { typedExp ->
            val ty = typedExp.pow.getCVLType()
            if (ty is CVLType.PureCVLType.Primitive.NumberLiteral && ty.n < BigInteger.ZERO) {
                CVLError.Exp(exp, "negative exponent (${ty.n}) is not supported").asError()
            } else {
                typedExp.lift()
            }
        }.let(::typeMathintExp)

    override fun iff(exp: CVLExp.BinaryExp.IffExp): CollectingResult<CVLExp.BinaryExp.IffExp, CVLError> =
        super.iff(exp).let(::typeBinaryBooleanExp)

    override fun implies(exp: CVLExp.BinaryExp.ImpliesExp): CollectingResult<CVLExp.BinaryExp.ImpliesExp, CVLError> =
        super.implies(exp).let(::typeBinaryBooleanExp).let(::checkShortCircuitable)

    override fun land(exp: CVLExp.BinaryExp.LandExp): CollectingResult<CVLExp.BinaryExp.LandExp, CVLError> =
        super.land(exp).let(::typeBinaryBooleanExp).let(::checkShortCircuitable)

    override fun lor(exp: CVLExp.BinaryExp.LorExp): CollectingResult<CVLExp.BinaryExp.LorExp, CVLError> =
        super.lor(exp).let(::typeBinaryBooleanExp).let(::checkShortCircuitable)

    private fun <T: CVLExp.BinaryExp>checkShortCircuitable(exp: CollectingResult<T, CVLError>): CollectingResult<T, CVLError> {
        return exp.bind { exp ->
            val sideEffectSubExprsOfR = exp.r.subExpsWithSideEffects(symbolTable)
            if (sideEffectSubExprsOfR.isNotEmpty()) {
                if (Config.GroundQuantifiers.get() && exp.l.subExprsOfType<CVLExp.QuantifierExp>().isNotEmpty()) {
                    val suggestionStr = when (exp) {
                        is CVLExp.BinaryExp.ImpliesExp -> "rewrite the implication as `rhs || !lhs`"
                        else -> "flip the order of the lhs and rhs"
                    }
                    CVLError.Exp(
                        exp,
                        "Cannot compile this expression.\nThe rhs `${exp.r}` may have side-effects (in $sideEffectSubExprsOfR) so must be " +
                            "evaluated only conditionally, dependent on the evaluation of the lhs `${exp.l}`. But this lhs contains a quantified " +
                            "expression, and due to optimizations we perform on such expressions conditional evaluation must not depend on them.\n" +
                            "If possible, $suggestionStr in order to overcome this problem.\n" +
                            "Alternatively, disable the optimization by adding `--prover_args \"-smt_groundQuantifiers false\"` to your command-line/conf file.\n" +
                            "See https://docs.certora.com/en/latest/docs/prover/approx/grounding.html#limitations-on-grounding for more details."
                    ).asError()
                } else {
                    @Suppress("UNCHECKED_CAST") (exp.updateTag(exp.tag.copy(annotation = NeedsShortCiruiting)) as T).lift()
                }
            } else {
                exp.lift()
            }
        }
    }


    override fun mod(exp: CVLExp.BinaryExp.ModExp): CollectingResult<CVLExp.BinaryExp.ModExp, CVLError> =
        super.mod(exp).let(::typeMathintExp)

    override fun eq(exp: CVLExp.RelopExp.EqExp): CollectingResult<CVLExp.RelopExp.EqExp, CVLError> =
        super.eq(exp).bind { exp ->
            relopBinder(exp).bind { (l, r) ->
                updateType(exp.copy(l = l, r = r), CVLType.PureCVLType.Primitive.Bool)
            }
        }

    override fun setmem(exp: CVLExp.SetMemExp): CollectingResult<CVLExp.SetMemExp, CVLError> {
        return super.setmem(exp).bind { exp2 ->
            val e = if (exp2.e is CVLExp.FieldSelectExp
                && exp2.e.structExp is CVLExp.Constant.SignatureLiteralExp
                && exp2.e.fieldName == EVMExternalMethodInfo.selectorField
                // if ex2.e.structExp is a SignatureLiteralExp then we must be in pure cvl type land
                && exp2.e.getPureCVLType() isSubtypeOf EVMExternalMethodInfo.selectorType) {
                ok
            } else {
                CVLError.Exp(
                    exp2, "Set membership is only supported for signature literal " +
                            "selectors elements, while given element was ${exp2.e}"
                ).asError()
            }
            // TODO: do we actually use the CodeContract type?
            val set = if (exp2.set.getCVLType() is CVLType.PureCVLType.Primitive.CodeContract) {
                ok
            } else {
                CVLError.Exp(
                    exp2, "Set membership is only supported for sets of type " +
                            "${CVLType.PureCVLType.Primitive.CodeContract} while set ${exp2.set} has type ${exp2.set.getCVLType()}"
                ).asError()
            }
            e.map(set) { _, _ ->
                exp2.copy(tag = exp2.tag.updateType(CVLType.PureCVLType.Primitive.Bool))
            }
        }
    }

    override fun condexp(exp: CVLExp.CondExp): CollectingResult<CVLExp.CondExp, CVLError> = collectingErrors {
        val exp = bind(super.condexp(exp))

        if (exp.c.getCVLType() isNotConvertibleTo CVLType.PureCVLType.Primitive.Bool) {
            collectError(CVLError.Exp(
                exp,
                "Condition ${exp.c} of if then else expression must be a subtype of ${CVLType.PureCVLType.Primitive.Bool} not ${exp.c.getCVLTypeOrNull()}"
            ))
        }

        val thenType = exp.e1.getCVLType()
        val elseType = exp.e2.getCVLType()
        val leastUpperBound = bind(getPureOrCorrespondenceLeastUpperBound(thenType, elseType, exp.e1, exp.e2) {
            CVLError.Exp(
                exp,
                "One branch of conditional must be a subtype of the other, but got incompatible types $thenType and $elseType"
            )
        })

        val annot = if ((exp.e1.subExpsWithSideEffects(symbolTable) + exp.e2.subExpsWithSideEffects(symbolTable)).isNotEmpty()) {
            NeedsShortCiruiting
        } else {
            null
        }
        return@collectingErrors bind(
            updateType(
                exp,
                leastUpperBound
            )
        ).let { it.copy(tag = it.tag.copy(annotation = annot)) }
    }

    override fun quant(exp: CVLExp.QuantifierExp): CollectingResult<CVLExp.QuantifierExp, CVLError> {
        if (exp.qVarType !is CVLType.PureCVLType.CVLValueType && exp.qVarType !is CVLType.PureCVLType.Sort) {
            CVLError.Exp(
                exp,
                "Variable ${exp.qVarName} used in a quantified formula must have a value-type or 'sort' type, but instead has type ${exp.qVarType}"
            ).asError()
        }

        val newTypeEnv = typeEnv.pushParam(exp.param)
        return CVLExpTypeCheckerWithContext(symbolTable, newTypeEnv).expr(exp.body).bind { body ->
            if (body.getCVLType().isNotConvertibleTo(CVLType.PureCVLType.Primitive.Bool)) {
                NonBoolExpression(body, NonBoolExpression.Kind.QUANTIFIER_BODY).asError()
            } else {
                updateType(exp.copy(body = body), CVLType.PureCVLType.Primitive.Bool)
            }
        }
    }

    override fun sum(exp: CVLExp.SumExp): CollectingResult<CVLExp.SumExp, CVLError> = collectingErrors {
        exp.params.forEach {
            if (it.type !is CVLType.PureCVLType.CVLValueType) {
                collectError(SumVariableNotValueType(exp, it))
            }
        }

        exp.body.indices.filter { indexExp ->
            indexExp !is CVLExp.VariableExp && exp.params.any { param ->
                param.id in indexExp.getVarsExp().map { it.id }
            }
        }.forEach { nonVarExp ->
            if (nonVarExp is CVLExp.CastExpr && nonVarExp.toCastType == CVLType.PureCVLType.Primitive.HashBlob) {
                // The GhostApplicationRewriter inserts cast expressions in array derefs with type bytes,
                // so `sum bytes b. myGhost[b]` will produce a false error here (instead of only the
                // SumVariableNotValueType error above)
                return@forEach
            }
            collectError(SumNonBasicParamExpression(nonVarExp))
        }

        val newTypeEnv = typeEnv.pushParams(exp.params)
        val body = bind(CVLExpTypeCheckerWithContext(symbolTable, newTypeEnv).arrayderef(exp.body))

        if (body.baseArray.getCVLType() !is CVLType.PureCVLType.Ghost.Mapping) {
            collectError(SumOnNonGhostVariable(body))
        }

        val targetType = body.getCVLType()
        if (targetType isNotConvertibleTo CVLType.PureCVLType.Primitive.Mathint) {
            collectError(SumBodyNotSummable(body))
        }

        if (exp.isUnsigned &&
            targetType !is CVLType.PureCVLType.Primitive.Mathint &&
            targetType isNotConvertibleTo CVLType.PureCVLType.Primitive.UIntK(256)) {
            collectError(UnsignedSumOnSignedGhostType(exp.getRangeOrEmpty(), body))
        }

        val bodyVarNames = body.getVarsExp().map { it.id }
        if (!exp.params.all { it.id in bodyVarNames}) {
            collectError(SumUnusedArguments(exp, exp.params.filterNot { it.id in bodyVarNames}))
        }

        val subSums = body.subExprsOfType<CVLExp.SumExp>()
        if (subSums.isNotEmpty()) {
            collectError(NestedSumsExpressions(exp, subSums))
        }

        return@collectingErrors exp.copy(body = body).updateType(CVLType.PureCVLType.Primitive.Mathint) as CVLExp.SumExp
    }

    companion object {
        /**
         * Gets a [CVLType.PureCVLType] to which the type of [this] can be converted.
         *
         * Provides an error about [this] if the inference failed to produce a [CVLType.PureCVLType] to which [this]
         * can be converted.
         *
         * @see CVLType.getOrInferPureCVLType
         */
        fun CVLExp.getOrInferPureCVLTypeDefaultError(): CollectingResult<CVLType.PureCVLType, CVLError> =
            this.getCVLType().getOrInferPureCVLType(this)

        /**
         * Gets a [CVLType.PureCVLType] to which [this] can be converted. This is the only way to get a
         * [CVLType.PureCVLType] from a [CVLType] and is necessary when we do not have a target [CVLType.VM]
         * for a conversion.
         *
         * Provides an error about [exp] if the inference failed to produce a [CVLType.PureCVLType] to which [this]
         * can be converted.
         */
        fun CVLType.getOrInferPureCVLType(exp: CVLExp): CollectingResult<CVLType.PureCVLType, CVLError> =
            this.getOrInferPureCVLType { errors, descriptor ->
                CVLError.Exp(
                    exp,
                    "Could not infer a CVL Type to use for the VM Type $descriptor.\n" +
                        "Reason(s):\n${errors.joinToString(separator = "\n") { "\t$it" }}"
                )
            }
    }

    private fun getPureOrCorrespondenceLeastUpperBound(lExp: CVLExp, rExp: CVLExp, topLevelError: () -> CVLError): CollectingResult<CVLType.PureCVLType, CVLError> =
        getPureOrCorrespondenceLeastUpperBound(lExp.getCVLType(), rExp.getCVLType(), lExp, rExp, topLevelError)

    /**
     * Attempts to infer a least upper bound [CVLType.PureCVLType] to which [lType] and [rType] are convertible.
     * Because this relies on correspendence types and subtyping, this may fail where some [CVLType.PureCVLType] may
     * have actually worked. This is probably pretty unlikely.
     */
    private fun getPureOrCorrespondenceLeastUpperBound(lType: CVLType, rType: CVLType, lExp: CVLExp, rExp: CVLExp, topLevelError: () -> CVLError): CollectingResult<CVLType.PureCVLType, CVLError> =
        lType.getOrInferPureCVLType(lExp).bind(rType.getOrInferPureCVLType(rExp)) { lPure, rPure ->
            if (lPure isSubtypeOf rPure) {
                rPure.lift()
            } else if (rPure isSubtypeOf lPure) {
                lPure.lift()
            } else if (lPure is CVLType.PureCVLType.Primitive.NumberLiteral || rPure is CVLType.PureCVLType.Primitive.NumberLiteral) {
                // In case of number literals, we also try replacing them with their smallest containing type,
                // since the different literals are not subtypes of each other
                val lConverted = (lPure as? CVLType.PureCVLType.Primitive.NumberLiteral)?.smallestTypeForNumberLit() ?: lPure
                val rConverted = (rPure as? CVLType.PureCVLType.Primitive.NumberLiteral)?.smallestTypeForNumberLit() ?: rPure
                if (lConverted isSubtypeOf rConverted) {
                    rConverted.lift()
                } else if (rConverted isSubtypeOf lConverted) {
                    lConverted.lift()
                } else {
                    // if they were both unsigned ints of some sort, we should have succeeded already, so now try to find a signed bound
                    // we don't want to go from the already converted type to a signed type,
                    // since e.g. number literal 1 would be a uint8 -> int16, while it can fit in int8 if we convert directly from literal
                    signedUpperBoundForNumbers(lPure, rPure, topLevelError)
                }
            } else {
                signedUpperBoundForNumbers(lPure, rPure, topLevelError)
            }
        }

    /**
     * Attempts to find a signed integer type containing [l] and [r]
     */
    private fun signedUpperBoundForNumbers(
        l: CVLType.PureCVLType,
        r: CVLType.PureCVLType,
        topLevelError: () -> CVLError
    ): CollectingResult<CVLType.PureCVLType, CVLError> {
        fun toSigned(t: CVLType.PureCVLType): CVLType.PureCVLType? =
            when (t) {
                is CVLType.PureCVLType.Primitive.IntK -> t
                is CVLType.PureCVLType.Primitive.UIntK -> t.smallestContainingSignedInt()
                is CVLType.PureCVLType.Primitive.NumberLiteral -> t.smallestSignedTypeForNumberLit()
                else -> if (t isSubtypeOf CVLType.PureCVLType.Primitive.Mathint) {
                    CVLType.PureCVLType.Primitive.Mathint
                } else {
                    null
                }
            }
        return toSigned(l)?.let { signedL ->
            toSigned(r)?.let { signedR ->
                if (signedL isSubtypeOf signedR) {
                    signedR.lift()
                } else if (signedR isSubtypeOf signedL) {
                    signedL.lift()
                } else {
                    topLevelError().asError()
                }
            }
        } ?: topLevelError().asError()
    }

    private fun arithRelopBinder(exp: CVLExp.RelopExp): CollectingResult<Pair<CVLExp, CVLExp>, CVLError> = collectingErrors {
        val lType = exp.l.getCVLTypeOrNull()!!
        val rType = exp.r.getCVLTypeOrNull()!!

        when {
            lType isNotConvertibleTo CVLType.PureCVLType.Primitive.Mathint -> {
                if (lType isConvertibleTo CVLType.PureCVLType.Primitive.AccountIdentifier) {
                    if (rType isConvertibleTo CVLType.PureCVLType.Primitive.AccountIdentifier) {
                        // Apparently users actually have a use-case for arithmetic comparison of addresses, so we allow it here
                        return@collectingErrors bind(relopBinder(exp))
                    } else {
                        returnError(CVLError.Exp(exp, "${exp.l} of type 'address' can only be compared to another address, not $rType"))
                    }
                } else {
                    returnError(CVLError.Exp(exp, "${exp.l} isn't a subtype of mathint so isn't comparable"))
                }
            }

            rType isNotConvertibleTo CVLType.PureCVLType.Primitive.Mathint -> {
                if (rType isConvertibleTo CVLType.PureCVLType.Primitive.AccountIdentifier) {
                    returnError(CVLError.Exp(exp, "${exp.r} of type 'address' can only be compared to another address, not $lType"))
                } else {
                    returnError(CVLError.Exp(exp, "${exp.r} isn't a subtype of mathint so isn't comparable"))
                }
            }

            else -> return@collectingErrors bind(relopBinder(exp))
        }
    }

    /*
     * All relops must be performed on expressions of the same type. This is to avoid potential vacuity due to the way
     * we model uints. In particular, suppose a variable uint16 x, for example, when declared, implicitly adds
     * assumptions to the smt formula that 0 <= x <= max_uint16. Now suppose we have a mathint y, and the all reachable
     * paths constrain y > max_uint16. If we have require x > y, we've just introduced vacuity. Thus we require an
     * explicit cast where this may happen to ensure the user thinks about this case.
     *
     * Therefore, if the expressions do not have the same type, an explicit cast is required.
     * This function checks all of this, and provides (hopefully) useful errors otherwise.
     */
    private fun relopBinder(exp: CVLExp.RelopExp): CollectingResult<Pair<CVLExp, CVLExp>, CVLError> = collectingErrors {
        fun isComparableType(
            type: CVLType.PureCVLType,
            expOfType: CVLExp,
            comparedToType: CVLType.PureCVLType,
            expOfComparedToType: CVLExp
        ): VoidResult<CVLError> = collectingErrors {
            when (type) {
                is CVLType.PureCVLType.Bottom,
                is CVLType.PureCVLType.Primitive,
                is CVLType.PureCVLType.Enum,
                is CVLType.PureCVLType.Sort,
                is CVLType.PureCVLType.DynamicArray.Bytes1Array,
                is CVLType.PureCVLType.VMInternal.BlockchainState,
                is CVLType.PureCVLType.VMInternal.StorageReference -> ok

                is CVLType.PureCVLType.Struct -> {
                    if (type == comparedToType) {
                        fun checkNoArrayFields(base: CVLExp, field: CVLType.PureCVLType.Struct.StructEntry) {
                            when(val t = field.cvlType) {
                                is CVLType.PureCVLType.CVLArrayType ->
                                    if(t !is CVLType.PureCVLType.DynamicArray.Bytes1Array)
                                    {
                                        collectError(StructComparisonContainsArrays(exp, base, field.fieldName))
                                    }
                                is CVLType.PureCVLType.Struct -> t.fields.forEach { innerField ->
                                    checkNoArrayFields(CVLExp.FieldSelectExp(base, field.fieldName, CVLExpTag(base.getScope(), base.getRangeOrEmpty())), innerField)
                                }
                                else -> Unit
                            }
                        }
                        type.fields.forEach { field -> checkNoArrayFields(expOfType, field) }
                        ok
                    } else {
                        val msg = if (comparedToType is CVLType.PureCVLType.Struct) {
                            "Cannot compare structs of different types. Tried to compare $expOfType of type $type to $expOfComparedToType of type $comparedToType"
                        } else if (comparedToType is CVLType.PureCVLType.Primitive.UIntK && comparedToType.k == 32 && type.name == EVMBuiltinTypes.method.name) {
                            "Cannot compare $expOfComparedToType of type uint32 to method struct ${expOfType}. Did you forget to append a `.selector`?"
                        } else {
                            "Cannot compare $expOfComparedToType of type $comparedToType to a struct $type"
                        }
                        returnError(
                            CVLError.Exp(
                                exp,
                                msg
                            )
                        )
                    }
                }
                else -> returnError(CVLError.Exp(exp, "type $type is not comparable"))
            }
        }

        fun unwrapUserDefinedType(type: CVLType.PureCVLType) =
            when (type) {
                is CVLType.PureCVLType.UserDefinedValueType -> type.base
                else -> type
            }

        fun orderExps(
            l: Pair<CVLExp, CVLType.PureCVLType>,
            r: Pair<CVLExp, CVLType.PureCVLType>,
            ordering: (first: CVLType.PureCVLType, second: CVLType.PureCVLType) -> Boolean
        ): Pair<Pair<CVLExp, CVLType.PureCVLType>, Pair<CVLExp, CVLType.PureCVLType>>? {
            return if (ordering(l.second, r.second)) {
                l to r
            } else if (ordering(r.second, l.second)) {
                r to l
            } else {
                null
            }
        }

        // Note we require an isomorphic type inside relops because our relop type checking requires both sides to
        // be of equivalent types not subtypes (thus we do not want to be using
        // VMConversionChecker.convertibleInExpressionContext)
        val (lType, rType) = bind(exp.l.getOrInferPureCVLTypeDefaultError(), exp.r.getOrInferPureCVLTypeDefaultError()).let {
            unwrapUserDefinedType(it.first) to unwrapUserDefinedType(it.second)
        }
        if (lType == rType) {
            // bind just one to avoid double reporting (structs are incomparable even if it's the same struct on both sides)
            bind(isComparableType(lType, exp.l, rType, exp.r))
        } else {
            bind(isComparableType(lType, exp.l, rType, exp.r), isComparableType(rType, exp.r, lType, exp.l))
        }

        // do comparability checks
        when {
            lType is CVLType.PureCVLType.Bottom || rType is CVLType.PureCVLType.Bottom -> {
                // Note that `x == _` will return a value depending on context:
                // `assert x == _` and `assert x != _` will both fail, while `require x == _` and `require x != _` will both be satisfied.
                // The support exists because optional-and-not-found functions get type `Bottom` and we don't want to fail typechecking
                // When comparing to them because those rules will be skipped anyway.
                return@collectingErrors Pair(exp.l, exp.r)
            }
            lType isSubtypeOf CVLType.PureCVLType.Primitive.AccountIdentifier && rType isSubtypeOf CVLType.PureCVLType.Primitive.AccountIdentifier -> {
                // addresses and contract types are all interchangeable (e.g. comparing a function that returns an
                // interface and some contract instance)
            }


            !(lType isSubtypeOf CVLType.PureCVLType.Primitive.Mathint) || !(rType isSubtypeOf CVLType.PureCVLType.Primitive.Mathint) -> {
                orderExps(Pair(exp.l, lType), Pair(exp.r, rType)) { first, second ->
                    first is CVLType.PureCVLType.Enum && first.isCastableTo(second)
                }?.let { (enum, integer) ->
                    val integerType = if (integer.second is CVLType.PureCVLType.Primitive.NumberLiteral) {
                        (integer.second as CVLType.PureCVLType.Primitive.NumberLiteral).smallestTypeForNumberLit()
                    } else {
                        integer.second
                    }
                    returnError(
                        CVLError.Exp(
                            exp,
                            "Cannot directly compare enum type ${enum.second} with ${integer.first} (type $integerType). Use an explicit cast (e.g. assert_$integerType(${enum.first}))"
                        )
                    )
                }

                // For non-math-related types, we only allow comparing a type with
                // its subtype (e.g. AccountIdentifier and CodeContract)
                if (!(lType isSubtypeOf rType) && !(rType isSubtypeOf lType)) {
                    returnError(
                        CVLError.Exp(
                            exp, "Cannot compare between $lType and $rType"
                        )
                    )
                }
            }

            lType is CVLType.PureCVLType.Primitive.NumberLiteral &&
                rType is CVLType.PureCVLType.Primitive.NumberLiteral ->
                // Comparing number literals is fine
                Unit

            lType is CVLType.PureCVLType.Primitive.NumberLiteral -> {
                if (!(lType isSubtypeOf rType)) {
                    returnError(CVLError.Exp(exp, "${lType.n} cannot be expressed in a $rType"))
                }
            }

            rType is CVLType.PureCVLType.Primitive.NumberLiteral -> {
                if (!(rType isSubtypeOf lType)) {
                    returnError(CVLError.Exp(exp, "${rType.n} cannot be expressed in a $lType"))
                }
            }

            lType != rType -> {
                // not the same type, but both convertible to mathint, so it's ok.
                // must hold due to previous branch conditions, just for future modification safety
                check((lType isSubtypeOf CVLType.PureCVLType.Primitive.Mathint) && (rType isSubtypeOf CVLType.PureCVLType.Primitive.Mathint))
            }

            else -> {
                // Yay! They're of the same type :)
            }
        }

        // do well-formedness checks here
        if (lType == CVLType.PureCVLType.VMInternal.BlockchainState) {
            if (exp.l !is CVLExp.VariableExp) {
                collectError(
                    CVLError.Exp(
                        exp = exp.l,
                        message = "Whole storage comparison only supports variable operands"
                    )
                )
            }
            if (exp.r !is CVLExp.VariableExp) {
                collectError(
                    CVLError.Exp(
                        exp = exp.r,
                        message = "Whole storage comparison only supports variable operands"
                    )
                )
            }
        } else if (lType is CVLType.PureCVLType.VMInternal.StorageReference) {
            if (exp.l !is CVLExp.ArrayDerefExp) {
                collectError(
                    CVLError.Exp(
                        exp = exp.l,
                        message = "Storage basis references must be of the form storage[basis]"
                    )
                )
            }
            if (exp.r !is CVLExp.ArrayDerefExp) {
                collectError(
                    CVLError.Exp(
                        exp = exp.r,
                        message = "Storage basis references must be of the form storage[basis]"
                    )
                )
            }
        }
        return@collectingErrors Pair(exp.l, exp.r)
    }

    private fun <T: CVLExp> CollectingResult<T, CVLError>.markComplex(isComplex: Boolean) = this.map {
        if(!isComplex) {
            it
        } else {
            it.updateTag(
                it.tag.copy(annotation = ComplexMarker)
            ).uncheckedAs()
        }
    }

    private fun builtinFoundryCheatcode(typedExp: CVLExp.ApplyExp.CVLBuiltIn): CollectingResult<CVLExp.ApplyExp.CVLBuiltIn, CVLError> {
        val typedArgs = typedExp.args
        return when (typedExp.id) {
            CVLBuiltInName.FOUNDRY_PRANK -> {
                if (typedArgs.size != 1) {
                    return CVLError.Exp(typedExp, "the prank cheatcode only accepts a single (address) argument, got `$typedArgs`").asError()
                }
                if (typedArgs.single().getCVLType().isNotConvertibleTo(CVLType.PureCVLType.Primitive.AccountIdentifier)) {
                    return CVLError.Exp(typedExp, "the prank cheatcode argument must have type `address`, got `${typedArgs.single().getCVLType()}.").asError()
                }
                typedExp.updateType(CVLType.PureCVLType.Void).uncheckedAs<CVLExp.ApplyExp.CVLBuiltIn>().lift()
            }
            CVLBuiltInName.FOUNDRY_START_PRANK -> {
                if (typedArgs.size != 1) {
                    return CVLError.Exp(typedExp, "the `startPrank` cheatcode only accepts a single (address) argument, got `$typedArgs`").asError()
                }
                if (typedArgs.single().getCVLType().isNotConvertibleTo(CVLType.PureCVLType.Primitive.AccountIdentifier)) {
                    return CVLError.Exp(typedExp, "the `startPrank` cheatcode argument must have type `address`, got `${typedArgs.single().getCVLType()}.").asError()
                }
                typedExp.copy(args = typedArgs, tag = typedExp.tag.updateType(CVLType.PureCVLType.Void)).lift()
            }
            CVLBuiltInName.FOUNDRY_STOP_PRANK -> {
                if (typedArgs.size != 0) {
                    return CVLError.Exp(typedExp, "the `stopPrank` cheatcode accepts no arguments, got `$typedArgs`").asError()
                }
                typedExp.copy(args = typedArgs, tag = typedExp.tag.updateType(CVLType.PureCVLType.Void)).lift()
            }
            CVLBuiltInName.FOUNDRY_WARP -> {
                if (typedArgs.size != 1) {
                    return CVLError.Exp(typedExp, "the warp cheatcode only accepts a single (uint256) argument, got `$typedArgs`").asError()
                }
                if (typedArgs.single().getCVLType().isNotConvertibleTo(CVLType.PureCVLType.Primitive.UIntK(256))) {
                    return CVLError.Exp(typedExp, "the warp cheatcode argument must have type `uint256`, got `${typedArgs.single().getCVLType()}.").asError()
                }
                typedExp.updateType(CVLType.PureCVLType.Void).uncheckedAs<CVLExp.ApplyExp.CVLBuiltIn>().lift()
            }
            CVLBuiltInName.FOUNDRY_ROLL -> {
                if (typedArgs.size != 1) {
                    return CVLError.Exp(typedExp, "the roll cheatcode only accepts a single (uint256) argument, got `$typedArgs`").asError()
                }
                if (typedArgs.single().getCVLType().isNotConvertibleTo(CVLType.PureCVLType.Primitive.UIntK(256))) {
                    return CVLError.Exp(typedExp, "the roll cheatcode argument must have type `uint256`, got `${typedArgs.single().getCVLType()}.").asError()
                }
                typedExp.updateType(CVLType.PureCVLType.Void).uncheckedAs<CVLExp.ApplyExp.CVLBuiltIn>().lift()
            }
            CVLBuiltInName.FOUNDRY_MOCK_CALL -> {
                if (typedArgs.size != 4) {
                    return CVLError.Exp(typedExp, "the mockCall cheatcode accepts exactly 4 arguments (address, mathint, bytes, bytes), got `$typedArgs`").asError()
                }

                if (typedArgs[0].getCVLType().isNotConvertibleTo(CVLType.PureCVLType.Primitive.AccountIdentifier)) {
                    return CVLError.Exp(typedExp, "the mockCall cheatcode argument #1 must have type `address`, got `${typedArgs[0].getCVLType()}.").asError()
                }
                if (typedArgs[1].getCVLType().isNotConvertibleTo(CVLType.PureCVLType.Primitive.Mathint)) {
                    return CVLError.Exp(typedExp, "the mockCall cheatcode argument #2 must be a subtype of `mathint`, got `${typedArgs[1].getCVLType()}.").asError()
                }
                if (typedArgs[2].getCVLType().isNotConvertibleTo(CVLType.PureCVLType.DynamicArray.PackedBytes)) {
                    return CVLError.Exp(typedExp, "the mockCall cheatcode argument #3 must have type `bytes`, got `${typedArgs[2].getCVLType()}.").asError()
                }
                if (typedArgs[3].getCVLType().isNotConvertibleTo(CVLType.PureCVLType.DynamicArray.PackedBytes)) {
                    return CVLError.Exp(typedExp, "the mockCall cheatcode argument #4 must have type `bytes`, got `${typedArgs[3].getCVLType()}.").asError()
                }
                typedExp.updateType(CVLType.PureCVLType.Void).uncheckedAs<CVLExp.ApplyExp.CVLBuiltIn>().lift()
            }
            CVLBuiltInName.FOUNDRY_CLEAR_MOCKED_CALLS -> {
                if (typedArgs.isNotEmpty()) {
                    return CVLError.Exp(typedExp, "the `clearMockedCalls` cheatcode accepts no arguments, got `$typedArgs`").asError()
                }
                typedExp.copy(args = typedArgs, tag = typedExp.tag.updateType(CVLType.PureCVLType.Void)).lift()
            }
            CVLBuiltInName.FOUNDRY_EXPECT_EMIT -> {
                if (typedArgs.isNotEmpty()) {
                    return CVLError.Exp(typedExp, "the `expectEmit` cheatcode accepts no arguments, got `$typedArgs`").asError()
                }
                typedExp.copy(args = typedArgs, tag = typedExp.tag.updateType(CVLType.PureCVLType.Void)).lift()
            }

            CVLBuiltInName.SHA256,
            CVLBuiltInName.KECCAK256,
            CVLBuiltInName.ECRECOVER -> `impossible!`
        }
    }

    override fun builtin(exp: CVLExp.ApplyExp.CVLBuiltIn): CollectingResult<CVLExp.ApplyExp.CVLBuiltIn, CVLError> {
        return super.builtin(exp).bind { typedExp ->
            val typedArgs = typedExp.args
            when(typedExp.id) {
                CVLBuiltInName.SHA256,
                CVLBuiltInName.KECCAK256 -> {
                    if(typedArgs.size == 1 && (typedArgs[0].getCVLType() isConvertibleTo CVLType.PureCVLType.DynamicArray.PackedBytes || typedArgs[0].getCVLType() isConvertibleTo CVLType.PureCVLType.DynamicArray.StringType)) {
                        // this is fine
                        typedExp.updateTag(
                            newTag = typedExp.tag.copy(
                                type = CVLType.PureCVLType.Primitive.BytesK(32),
                                annotation = HashSort(isArray = true)
                            )
                        ).uncheckedAs<CVLExp.ApplyExp.CVLBuiltIn>().lift()
                    } else if(typedArgs.isEmpty()) {
                        CVLError.Exp(
                            exp = typedExp,
                            message = "${typedExp.id.bifName} requires at least one argument"
                        ).asError()
                    } else {
                        typedExp.args.map { arg ->
                            val ty = arg.getCVLType()
                            ty.getOrInferPureCVLType { errors, type ->
                                CVLError.Exp(
                                    arg, message = "Cannot infer CVL type for $type\n" +
                                        "Reason(s):${errors.joinToString(separator = "\n") { "\tit" }}"
                                )
                            }.bind { pureType ->
                                if(pureType is CVLType.PureCVLType.BufferEncodeableType) {
                                    ok
                                } else {
                                    if(pureType is CVLType.PureCVLType.DynamicArray.Bytes1Array) {
                                        CVLError.Exp(arg, message = "Cannot apply `${typedExp.id.bifName}` to `bytes` value alongside other arguments. Consider applying `${typedExp.id.bifName}` to `$arg` separately").asError()
                                    } else {
                                        CVLError.Exp(arg, message = "Cannot hash value of type $pureType").asError()
                                    }
                                }
                            }
                        }.flattenToVoid().map { _ ->
                            typedExp.updateTag(
                                newTag = typedExp.tag.copy(
                                    type = CVLType.PureCVLType.Primitive.BytesK(32),
                                    annotation = HashSort(isArray = false)
                                )
                            ).uncheckedAs()
                        }
                    }
                }
                CVLBuiltInName.ECRECOVER -> {
                    val bytes32Type = CVLType.PureCVLType.Primitive.BytesK(32)
                    if (typedArgs.size != 4) {
                        CVLError.Exp(typedExp, "ecrecover accepts exactly 4 arguments, got ${typedArgs.size}").asError()
                    } else if (!typedArgs[0].getCVLType().isConvertibleTo(bytes32Type) ||
                        !typedArgs[1].getCVLType().isConvertibleTo(CVLType.PureCVLType.Primitive.UIntK(8)) ||
                        !typedArgs[2].getCVLType().isConvertibleTo(bytes32Type) ||
                        !typedArgs[3].getCVLType().isConvertibleTo(bytes32Type)) {
                        CVLError.Exp(typedExp, "ecrecover's signature is `ecrecover(bytes32,uint8,bytes32,bytes32). " +
                            "One of the arguments has the wrong type (${typedArgs.joinToString { it.getCVLType().toString() }}).").asError()
                    } else {
                        exp.copy(
                            args = typedArgs, tag = typedExp.tag.updateType(CVLType.PureCVLType.Primitive.AccountIdentifier)
                        ).lift()
                    }
                }

                CVLBuiltInName.FOUNDRY_PRANK,
                CVLBuiltInName.FOUNDRY_START_PRANK,
                CVLBuiltInName.FOUNDRY_STOP_PRANK,
                CVLBuiltInName.FOUNDRY_WARP,
                CVLBuiltInName.FOUNDRY_ROLL,
                CVLBuiltInName.FOUNDRY_MOCK_CALL,
                CVLBuiltInName.FOUNDRY_CLEAR_MOCKED_CALLS,
                CVLBuiltInName.FOUNDRY_EXPECT_EMIT -> return@bind builtinFoundryCheatcode(typedExp)
            }
        }
    }

    private fun <T: CVLExp> CollectingResult<T, CVLError>.markStorage() = this.map {
        it.updateTag(it.tag.copy(annotation = StorageAccessMarker)).uncheckedAs<T>()
    }

    private fun <T: CVLExp> CollectingResult<T, CVLError>.markImmutable() = this.map {
        it.updateTag(it.tag.copy(annotation = ImmutableAccessMarker)).uncheckedAs<T>()
    }

    override fun arrayderef(exp: CVLExp.ArrayDerefExp): CollectingResult<CVLExp.ArrayDerefExp, CVLError> {
        return super.arrayderef(exp).bind { arrayDerefExp ->
            val arrayExp = arrayDerefExp.array
            val ind = arrayDerefExp.index
            val arrayTag = arrayExp.tag
            if(arrayTag.annotation == StorageAccessMarker) {
                check(arrayTag.type is CVLType.VM && arrayTag.type.context == FromVMContext.StateValue)
                val indexType = CVLType.PureCVLType.Primitive.UIntK(Config.VMConfig.registerBitwidth)
                return@bind when (arrayTag.type.descriptor) {
                    is VMStaticArrayType -> {
                        if (ind.getCVLType() isNotConvertibleTo indexType) {
                            return@bind CVLError.Exp(
                                ind,
                                "Expected index expression to be convertible to $indexType, got ${ind.getCVLType()}"
                            ).asError<CVLError.Exp>()
                        }
                        updateType(
                            arrayDerefExp, CVLType.VM(
                                arrayTag.type.descriptor.elementType, FromVMContext.StateValue
                            )
                        ).markStorage<CVLExp.ArrayDerefExp>()
                    }

                    is VMDynamicArrayTypeDescriptor -> {
                        if (ind.getCVLType() isNotConvertibleTo indexType) {
                            return@bind CVLError.Exp(
                                ind,
                                "Expected index expression to be convertible to $indexType, got ${ind.getCVLType()}"
                            ).asError()
                        } else if(arrayTag.type.descriptor.elementSize == BigInteger.ONE) {
                            return@bind CVLError.Exp(
                                ind,
                                "Cannot directly access individual byte elements of packed arrays in storage"
                            ).asError()
                        }
                        updateType(
                            arrayDerefExp, CVLType.VM(
                                arrayTag.type.descriptor.elementType, FromVMContext.StateValue
                            )
                        ).markStorage()
                    }

                    is VMMappingDescriptor -> {
                        ind.getOrInferPureCVLTypeDefaultError().bind { keyType ->
                            arrayTag.type.descriptor.keyType.converterFrom<IMappingKeyOutput, ITACSymbolVar, IStorageMapping>(
                                keyType,
                                ToVMContext.MappingKey.getVisitor()
                            ).mapErrors {
                                CVLError.Exp(
                                    ind,
                                    "Invalid key type: $it"
                                )
                            }.bind {
                                updateType(
                                    arrayDerefExp, CVLType.VM(
                                        arrayTag.type.descriptor.valueType,
                                        context = FromVMContext.StateValue
                                    )
                                ).markStorage()
                            }
                        }
                    }

                    else -> CVLError.Exp(
                        exp = exp,
                        message = "Cannot use indexing operation on expression with VM type ${arrayTag.type.descriptor.prettyPrint()}"
                    ).asError()
                }
            }
            // must infer because we can't enumerate the possible array target types and then check convertibility
            arrayExp.getOrInferPureCVLTypeDefaultError().bind baseBind@{ ty ->
                if (ty is CVLType.PureCVLType.Ghost.Mapping) {
                    val keyType = ty.key
                    if (ind.getCVLType() isNotConvertibleTo keyType) {
                        CVLError.Exp(
                            ind,
                            "Ghost mapping expected a key of type $keyType, instead got ${ind.getCVLType()}"
                        ).asError()
                    } else {
                        updateType(arrayDerefExp, ty.value)
                    }
                } else if(ty is CVLType.PureCVLType.VMInternal.BlockchainState) {
                    val genericBasisMessage =
                        "Basis of storage comparison must be a contract identifier imported with `using`, " +
                            "the `${CVLKeywords.nativeBalances.name}` or `${CVLKeywords.nativeCodesize.name}` keywords, or the name of a ghost"

                    fun indError(message: String) = CVLError.Exp(ind, message = message).asError()
                    // then this is a storage reference. We can enforce that the array exp here is a variable, required
                    // for later
                    if (arrayExp !is CVLExp.VariableExp) {
                        return@baseBind CVLError.Exp(
                            arrayDerefExp.array,
                            message = "Cannot use anything other than variables as the base for storage comparison"
                        ).asError()
                    }
                    if (ind !is CVLExp.VariableExp) {
                        return@baseBind indError(message = genericBasisMessage)
                    }
                    val basisTy = ind.getCVLType()
                    /*
                      What identifier *won't* have pure cvl type? I don't know, but let's encode all our assumptions instead
                      of crashing
                     */
                    if (basisTy !is CVLType.PureCVLType) {
                        return@baseBind indError(message = "Cannot use value of type $basisTy as a basis for storage comparison")
                    }
                    when (basisTy) {
                        is CVLType.PureCVLType.VMInternal.BlockchainStateMap -> {
                            // ensure that the operand is balances/extcodesize
                            when (ind.id) {
                                CVLKeywords.nativeBalances.name -> {
                                    updateType(
                                        arrayDerefExp, CVLType.PureCVLType.VMInternal.StorageReference(
                                        basis = StorageBasis.Balances
                                    )
                                    )
                                }
                                CVLKeywords.nativeCodesize.name -> {
                                    updateType(
                                        arrayDerefExp, CVLType.PureCVLType.VMInternal.StorageReference(
                                        basis = StorageBasis.ExternalCodesizes
                                    )
                                    )
                                }
                                else -> {
                                    return@baseBind indError(message = genericBasisMessage)
                                }
                            }
                        }

                        is CVLType.PureCVLType.Primitive.CodeContract -> {
                            updateType(
                                arrayDerefExp, CVLType.PureCVLType.VMInternal.StorageReference(
                                    basis = StorageBasis.ContractClass(basisTy.name)
                                )
                            )
                        }

                        is CVLType.PureCVLType.Ghost.Function -> {
                            /* this message is intentionally trash, because this is almost certainly something going
                               **very** wrong in our tool
                             */
                            val binding = symbolTable.lookUpFunctionLikeSymbol(ind.id, typeEnv = typeEnv)?.let {
                                it as? CVLSymbolTable.SymbolInfo.CVLFunctionInfo
                            }?.symbolValue?.let {
                                it as? CVLGhostDeclaration.Function
                            } ?: return@baseBind indError(message = "${ind.id} is not a ghost function")
                            updateType(
                                arrayDerefExp, CVLType.PureCVLType.VMInternal.StorageReference(
                                    StorageBasis.Ghost(binding)
                                )
                            )
                        }

                        else -> {
                            symbolTable.lookUpNonFunctionLikeSymbol(ind.id, typeEnv)?.let {
                                it as? CVLSymbolTable.SymbolInfo.CVLValueInfo
                            }?.symbolValue?.let {
                                it as? CVLGhostDeclaration.Variable
                            }?.let {
                                updateType(
                                    arrayDerefExp,
                                    CVLType.PureCVLType.VMInternal.StorageReference(StorageBasis.Ghost(it))
                                )
                            } ?: indError(genericBasisMessage)
                        }
                    }
                } else if (ty is CVLType.PureCVLType.VMInternal.BlockchainStateMap) {
                    if(ind.getCVLType() isNotConvertibleTo ty.paramType) {
                        return@baseBind CVLError.Exp(
                            exp = arrayExp,
                            message =  "Expected a key of type ${ty.paramType}, instead got ${ind.getCVLType()}"
                        ).asError()
                    }
                    updateType(arrayDerefExp, ty.outputType)
                } else if (ty !is CVLType.PureCVLType.CVLArrayType) {
                    CVLError.Exp(
                        arrayExp,
                        "Expected operand of array dereference to be of array or ghost mapping type, instead got: $ty"
                    ).asError()
                } else if (ind.getCVLType() isNotConvertibleTo CVLType.PureCVLType.CVLArrayType.indexType) {
                    CVLError.Exp(ind, "Expected array index to be subtype of ${CVLType.PureCVLType.CVLArrayType.indexType.toString()}, got ${ind.getCVLType()}")
                        .asError()
                } else {
                    val isComplex = ty.elementType.isComplex() || arrayTag.annotation == ComplexMarker
                    updateType(arrayDerefExp, ty.elementType).markComplex(isComplex)
                }
            }
        }
    }

    override fun arrayexp(exp: CVLExp.ArrayLitExp): CollectingResult<CVLExp.ArrayLitExp, CVLError> {
        return super.arrayexp(exp).bind { arrayLitExp ->
            val elementTypes = arrayLitExp.elements.map { element ->
                element.getOrInferPureCVLTypeDefaultError().bind { elementType ->
                    typeTypeChecker.typeCheckArrayLiteralElement(elementType, exp)
                }
            }.flatten()

            val leastUpperBound = elementTypes.bind lubBind@{ elementTypes  ->
                if(elementTypes.isEmpty()) {
                    return@lubBind CVLType.PureCVLType.Bottom.lift()
                }

                // Find the least upper bound type (or a "principle type") to which all elements of the array are convertible--this is required
                // for the CVLArrayType interface, which requires an element type.
                val hintType = ((exp.tag.annotation as? CVLExp.ArrayLitExp.ArrayLiteralTypeHint)?.type as? CVLType.PureCVLType.CVLArrayType)?.elementType
                if (hintType != null) {
                    // We have a hint, use it.
                    return@lubBind if (elementTypes.all { it isSubtypeOf hintType }) {
                        hintType.lift()
                    } else {
                        WrongArrayLiteralElementTypes(
                            exp.getRangeOrEmpty(),
                            elementTypes.filterNot { it isSubtypeOf hintType }.map { numberLitToLeastUpperBoundInt(it) },
                            hintType
                        ).asError()
                    }
                }

                if (elementTypes.all { it isSubtypeOf CVLType.PureCVLType.Primitive.AccountIdentifier }) {
                    // Check for subtypes of address as a special case in order to catch both cases where there are
                    // several contract aliases ([CodeContract]s) which aren't subtypes of each other, yet are subtypes
                    // of address, and cases with number literals that fit in an address - these can confuse the
                    // subtyping rules below because while they are considered subtypes of address, their corresponding
                    // [smallestTypeForNumberLit] is not.
                    return@lubBind CVLType.PureCVLType.Primitive.AccountIdentifier.lift()
                }

                val principleElementType = elementTypes.map {
                    // First, replace number literal types with their smallest upper-bound non-literal type.
                    numberLitToLeastUpperBoundInt(it)
                }.monadicFold { a, b ->
                    if(a isSubtypeOf b) {
                        return@monadicFold b
                    } else if(b isSubtypeOf a) {
                        return@monadicFold a
                    }

                    // OK, let's see what we can do with ints and uints that aren't directly subtypes of each
                    // other (e.g. intK and uintK for the same K).
                    val (int, uint) = when {
                        a is CVLType.PureCVLType.Primitive.IntK &&
                            b is CVLType.PureCVLType.Primitive.UIntK -> a to b
                        b is CVLType.PureCVLType.Primitive.IntK &&
                            a is CVLType.PureCVLType.Primitive.UIntK -> b to a
                        else -> null to null
                    }

                    if(int != null && uint != null) {
                        check(int.k <= uint.k) { // recall that if `int.k > uint.k` then `uint` is a subtype of `int`
                            "if $a and $b are integer types but not subtypes of each other they should have been one int and one uint"
                        }
                        if (uint.k < Config.VMConfig.registerBitwidth) {
                            // For int<k1> and uint<k2> we know at this stage that k1 <= k2. Therefore int<k2+8> is a supertype of both
                            return@monadicFold CVLType.PureCVLType.Primitive.IntK(uint.k + 8)
                        }
                    }
                    null
                } ?: return@lubBind WrongArrayLiteralElementTypes(exp.getRangeOrEmpty()).asError()
                principleElementType.lift()
            }

            leastUpperBound.bind { leastUpperBound ->
                typeTypeChecker.typeCheckArrayElement(leastUpperBound, exp)
            }.map(elementTypes) { leastUpperBound, elementTypes ->
                arrayLitExp.copy(
                    tag = exp.tag.updateType(
                        CVLType.PureCVLType.ArrayLiteral(
                            elementTypes,
                            leastUpperBound
                        )
                    )
                )
            }
        }
    }

    override fun ne(exp: CVLExp.RelopExp.NeExp): CollectingResult<CVLExp.RelopExp.NeExp, CVLError> =
        super.ne(exp).bind { exp ->
            relopBinder(exp).bind { (l, r) ->
                updateType(exp.copy(l = l, r = r), CVLType.PureCVLType.Primitive.Bool)
            }
        }

    override fun neg(exp: CVLExp.UnaryExp.LNotExp): CollectingResult<CVLExp.UnaryExp.LNotExp, CVLError> {
        return super.neg(exp).bind {
            ensureIsConvertible(it.e, CVLType.PureCVLType.Primitive.Bool).bind { _ ->
                updateType(it, CVLType.PureCVLType.Primitive.Bool)
            }
        }
    }

    override fun ge(exp: CVLExp.RelopExp.ArithRelopExp.GeExp): CollectingResult<CVLExp.RelopExp.ArithRelopExp.GeExp, CVLError> =
        super.ge(exp).bind { exp ->
            arithRelopBinder(exp).bind { (l, r) ->
                updateType(exp.copy(l = l, r = r), CVLType.PureCVLType.Primitive.Bool)
            }
        }

    override fun gt(exp: CVLExp.RelopExp.ArithRelopExp.GtExp): CollectingResult<CVLExp.RelopExp.ArithRelopExp.GtExp, CVLError> =
        super.gt(exp).bind { exp ->
            arithRelopBinder(exp).bind { (l, r) ->
                updateType(exp.copy(l = l, r = r), CVLType.PureCVLType.Primitive.Bool)
            }
        }

    override fun le(exp: CVLExp.RelopExp.ArithRelopExp.LeExp): CollectingResult<CVLExp.RelopExp.ArithRelopExp.LeExp, CVLError> =
        super.le(exp).bind { exp ->
            arithRelopBinder(exp).bind { (l, r) ->
                updateType(exp.copy(l = l, r = r), CVLType.PureCVLType.Primitive.Bool)
            }
        }

    override fun lt(exp: CVLExp.RelopExp.ArithRelopExp.LtExp): CollectingResult<CVLExp.RelopExp.ArithRelopExp.LtExp, CVLError> =
        super.lt(exp).bind { exp ->
            arithRelopBinder(exp).bind { (l, r) ->
                updateType(exp.copy(l = l, r = r), CVLType.PureCVLType.Primitive.Bool)
            }
        }

    override fun boolLit(exp: CVLExp.Constant.BoolLit): CollectingResult<CVLExp.Constant.BoolLit, CVLError> =
        super.boolLit(exp).bind { it.copy(tag = exp.tag.copy(type = CVLType.PureCVLType.Primitive.Bool)).lift() }

    override fun numberLit(exp: CVLExp.Constant.NumberLit): CollectingResult<CVLExp.Constant.NumberLit, CVLError> =
        super.numberLit(exp).bind {
            it.copy(tag = it.tag.copy(type = CVLType.PureCVLType.Primitive.NumberLiteral(exp.n))).lift()
        }

    override fun stringLit(exp: CVLExp.Constant.StringLit): CollectingResult<CVLExp.Constant.StringLit, CVLError> {
        return super.stringLit(exp).bind { exp ->
            updateType(exp, CVLType.PureCVLType.DynamicArray.StringType)
        }
    }

    override fun signatureLit(exp: CVLExp.Constant.SignatureLiteralExp): CollectingResult<CVLExp.Constant.SignatureLiteralExp, CVLError> {
        return super.signatureLit(exp).bind { exp ->
            (symbolTable.lookupMethodInContractEnv(exp.function.qualifiedMethodName.host, exp.function.qualifiedMethodName.methodId)?.lift()
                ?: CVLError.Exp(exp, "could not find method").asError()).bind { symbolInfo ->
                    if (symbolInfo !is CVLSymbolTable.SymbolInfo.CVLFunctionInfo || symbolInfo.impFuncs.any { impFunc -> impFunc !is ContractFunction }) {
                        CVLError.Exp(exp, "${exp.function.qualifiedMethodName.methodId} is not a contract function").asError()
                    } else {
                        symbolInfo.lift()
                    }
                }.bind { overloadings ->
                val matches = overloadings.impFuncs.filter { impFunc ->
                    val contractFunction = impFunc as ContractFunction
                    if (impFunc.annotation.visibility != Visibility.EXTERNAL) {
                        throw CertoraInternalException(CertoraInternalErrorType.CVL_TYPE_CHECKER, "unexpected internal " +
                            "function $impFunc registered in the symbol table")
                    }
                    contractFunction.paramTypes.zipPred(exp.function.paramTypes) { impParam, sigParam ->
                        // Note the use of `mergeWith` here instead of a simple equality check is to take into account
                        // possible differences in the location (memory, calldata, null, etc) between the descriptors
                        // which are actually compatible with each-other (e.g. memory and null).
                        impParam.mergeWith(sigParam).resultOrNull() != null
                    }
                }
                if (matches.isEmpty()) {
                    CVLError.Exp(exp, "no matching function was found").asError()
                } else if (matches.size > 1) {
                    throw CertoraInternalException(CertoraInternalErrorType.CVL_TYPE_CHECKER, "somehow multiple overloadings with the same arguments were registered in " +
                        "the symbol table for $exp")
                } else {
                    val func = (matches.single() as ContractFunction)
                    val annot = func.evmExternalMethodInfo?.toCvlStructLit(exp.getScope())
                        ?: withScopeAndRange(exp.getScope(), exp.getRangeOrEmpty()) {

                            val asExternal =
                                ExternalQualifiedMethodParameterSignature.fromNamedParameterSignatureContractId(
                                    exp.function,
                                    PrintingContext(func.annotation.library)
                                )

                            CVLExp.Constant.StructLit(
                                mapOf(
                                    EVMExternalMethodInfo.selectorField to CVLExp.Constant.NumberLit(
                                        asExternal.sighashInt?.n ?: EVMExternalMethodInfo.magicFallbackSelector,
                                        EVMExternalMethodInfo.selectorType.asTag(),
                                        "16"
                                    )
                                ),
                                /**
                                 * This type is used when we have a signature literal for an optional method for which
                                 * no implementation exists. In that case, the only information we can reliably provide
                                 * is the sighash; for other fields (payability, view, etc.) we refuse to provide an answer
                                 * by "hiding" those fields. Note this will *also* prevent **calling** such a method,
                                 * but if this is *really* a use case we want to support, we can adjust the function call
                                 * rules. see, https://certora.atlassian.net/browse/CERT-1277
                                 */
                                EVMBuiltinTypes.virtualMethod.asTag()
                            )
                        }

                    val type = annot.getCVLTypeOrNull()!!
                    exp.copy(tag = exp.tag.updateType(type).copy(annotation = annot)).lift()
                }
            }
        }
    }

    override fun variable(exp: CVLExp.VariableExp): CollectingResult<CVLExp.VariableExp, CVLError> {
        return if (exp.isWildCard()) {
            exp.copy(tag = CVLExpTag(typeEnv.scope, CVLType.PureCVLType.Bottom, typeEnv.range)).lift()
        } else {
            val symbolInfo = symbolTable.lookUpNonFunctionLikeSymbol(exp.id, typeEnv)
            variableExp(symbolInfo, exp, typeEnv)
        }
    }

    /**
     * these variables are handled separately from "regular" variables, like in [idLhs],
     * because the matching symbol may be in the function-like symbol table
     */
    private fun havocTargetVariable(exp: CVLExp.VariableExp): CollectingResult<CVLExp.VariableExp, CVLError> = collectingErrors {
        val symbolInfo = symbolTable.lookUpNonFunctionLikeSymbol(exp.id, typeEnv)
            ?: symbolTable.lookUpFunctionLikeSymbol(exp.id, typeEnv)
            ?: returnError(CVLError.Exp(exp, "unknown variable \"${exp.id}\""))
        val updatedTag = exp.tag.copy(type = symbolInfo.getCVLTypeOrNull())

        checkTwoState(exp, symbolInfo.isTwoState, exp.twoStateIndex).let(::bind).copy(tag = updatedTag)
    }

    override fun castExpression(exp: CVLExp.CastExpr): CollectingResult<CVLExp.CastExpr, CVLError> {
        return super.castExpression(exp).bind { exp ->
            when (exp.toCastType){
                is CVLType.PureCVLType.Primitive.BytesK -> {
                    exp.arg.getOrInferPureCVLTypeDefaultError().bind { type ->
                        when (type) {
                            is CVLType.PureCVLType.Primitive.UIntK ->
                                if (type.k != exp.toCastType.bitWidth) {
                                    CVLError.Exp(
                                        exp = exp,
                                        message = "Cannot cast from $type to ${exp.toCastType}:" +
                                            " casting to ${exp.toCastType} can only be done from uint${exp.toCastType.bitWidth}",
                                    ).asError()
                                } else {
                                    exp.lift()
                                }
                            is CVLType.PureCVLType.Primitive.NumberLiteral -> {
                                val numberLiteral = type.n
                                if (numberLiteral < BigInteger.ZERO) {
                                    CVLError.Exp(
                                        exp = exp,
                                        message = "Invalid value for number literal: $numberLiteral. Only non-negative values allowed.",
                                    ).asError()
                                } else if (numberLiteral.bitLength() > exp.toCastType.bitWidth) {
                                    CVLError.Exp(
                                        exp = exp,
                                        message = "Invalid range from number literal $numberLiteral to bytes${exp.toCastType.k}",
                                    ).asError()
                                } else {
                                    exp.lift()
                                }
                            }
                            is CVLType.PureCVLType.Primitive.AccountIdentifier -> {
                                if (exp.toCastType != CVLType.PureCVLType.Primitive.BytesK(32)) {
                                    CVLError.Exp(
                                        exp = exp,
                                        message = "Can only cast type address to a byte array of type bytes32, not ${exp.toCastType}",
                                    ).asError()
                                } else {
                                    exp.lift()
                                }
                            }
                            else ->
                                CVLError.Exp(
                                    exp = exp,
                                    message = "Only number literals or unsigned integers may be used in bytes conversion",
                                ).asError()
                        }
                    }
                }
                is CVLType.PureCVLType.Primitive.HashBlob -> {
                    // note there should be no concrete syntax for this cast
                    check(exp.castType == CastType.CONVERT) {
                        "Unexpected cast type for hash blob: ${exp.castType}"
                    }
                    when (val ty = exp.arg.getCVLType()) {
                        is CVLType.VM -> {
                            if(ty.isConvertibleTo(CVLType.PureCVLType.DynamicArray.StringType) || ty.isConvertibleTo(CVLType.PureCVLType.DynamicArray.PackedBytes)) {
                                exp.copy(
                                    tag = exp.tag.copy(
                                        type = CVLType.PureCVLType.Primitive.HashBlob,
                                        annotation = BoxingType(isIdentity = false)
                                    )
                                ).lift()
                            } else {
                                exp.copy(
                                    tag = exp.tag.copy(
                                        type = CVLType.PureCVLType.Primitive.HashBlob,
                                        annotation = BoxingType(isIdentity = true)
                                    )
                                ).lift()
                            }
                        }
                        is CVLType.PureCVLType ->
                            if (ty !is CVLType.PureCVLType.DynamicArray.Bytes1Array && ty !is CVLType.PureCVLType.Primitive.HashBlob) {
                                CVLError.Exp(
                                    exp = exp.arg,
                                    message = "Expected bytes or string for ghost application, got $ty"
                                ).asError()
                            } else {
                                exp.copy(
                                    tag = exp.tag.copy(
                                        type = CVLType.PureCVLType.Primitive.HashBlob,
                                        /*
                                        * If the source type is already hashblob, then annotate this cast expression as being
                                        * the identity transformation. A value of false for `isIdentity` will indicate that
                                        * the compiler needs to insert a hash expression.
                                        */
                                        annotation = BoxingType(isIdentity = ty is CVLType.PureCVLType.Primitive.HashBlob)
                                    )
                                ).lift()
                            }
                    }
                }
                is CVLType.PureCVLType.Primitive.AccountIdentifier -> {
                    exp.arg.getOrInferPureCVLTypeDefaultError().bind { type ->
                        when(type) {
                            is CVLType.PureCVLType.Primitive.AccountIdentifier -> {
                                exp.lift()
                            }

                            is CVLType.PureCVLType.Primitive.BytesK -> {
                                if (type != CVLType.PureCVLType.Primitive.BytesK(32)) {
                                    CVLError.Exp(
                                        exp = exp,
                                        message = "Can only cast to type address from bytes array type bytes32, not ${type}",
                                    ).asError()
                                } else {
                                    exp.lift()
                                }
                            }

                            else ->
                                CVLError.Exp(
                                    exp = exp,
                                    message = "can not cast $type into ${exp.toCastType}",
                                ).asError()
                        }
                    }
                }
                else -> { /* exp.toCastType is not HashBlob or BytesK */
                    check(exp.toCastType.isSubtypeOf(CVLType.PureCVLType.Primitive.Mathint)) {
                        "Unexpected cast target type ${exp.toCastType}"
                    }
                    exp.arg.getOrInferPureCVLTypeDefaultError().bind { fromPureType ->
                        if (fromPureType.isSubtypeOf(CVLType.PureCVLType.Primitive.Mathint) ||
                            fromPureType.isSubtypeOf(CVLType.PureCVLType.Primitive.AccountIdentifier) ||
                            fromPureType is CVLType.PureCVLType.Enum ||
                            (fromPureType is CVLType.PureCVLType.Primitive.BytesK && fromPureType.k == 32
                                && exp.toCastType is CVLType.PureCVLType.Primitive.UIntK && exp.toCastType.k == 256)
                        ) {
                            exp.lift()
                        } else {
                            CVLError.Exp(exp, "can not cast $fromPureType into ${exp.toCastType}").asError()
                        }
                    }
                }
            }
        }.bind { exp ->
            exp.copy(tag = exp.tag.copy(type = exp.toCastType)).lift()
        }
    }

    override fun call(exp: CVLExp.ApplyExp.CVLFunction): CollectingResult<CVLExp.ApplyExp.CVLFunction, CVLError> {
        return typeCheckCVLApplication<CVLFunction, CVLExp.ApplyExp.CVLFunction>(exp, exp.methodIdWithCallContext, "CVL function") { args, tag ->
            exp.copy(args = args, tag = tag)
        }
    }

    override fun ghost(exp: CVLExp.ApplyExp.Ghost): CollectingResult<CVLExp.ApplyExp.Ghost, CVLError> {
        return this.typeCheckCVLApplication<CVLGhostDeclaration.Function, CVLExp.ApplyExp.Ghost>(exp, exp.methodIdWithCallContext, sort = "ghost function", extraCheck = { app, _, symboInfo ->
            checkTwoState(
                exp = exp,
                isDeclaredTwoState = symboInfo.isTwoState,
                usageTwoStateIndex = app.twoStateIndex
            ).bind { ok }
        }) { args, tag ->
            exp.copy(args = args, tag = tag)
        }
    }

    inline private fun <reified T : SpecFunction, U: CVLExp.ApplyExp> typeCheckCVLApplication(
        exp: U,
        callee: SpecDeclaration,
        sort: String,
        noinline extraCheck: (U, T, CVLSymbolTable.SymbolInfo.CVLFunctionInfo) -> VoidResult<CVLError> = { _ , _, _ -> ok },
        noinline f: (List<CVLExp>, tag: CVLExpTag) -> U
    ): CollectingResult<U, CVLError> {
        val func = (symbolTable.lookUpFunctionLikeSymbol(
            id = callee.methodId,
            typeEnv
        ) as? CVLSymbolTable.SymbolInfo.CVLFunctionInfo)?.takeIf {
            it.impFuncs.size == 1 && it.symbolValue is T
        } ?: return CVLError.Exp(
            exp = exp,
            message = "No $sort with name ${callee.methodId} in scope"
        ).asError()
        return typeCheckCVLApplication(
            exp = exp,
            symbolInfo = func,
            callee = func.symbolValue as T,
            extraCheck = extraCheck,
            f = f
        )
    }

    private fun <T: SpecFunction, U: CVLExp.ApplicationExp> typeCheckCVLApplication(
        exp: U,
        callee: T,
        symbolInfo: CVLSymbolTable.SymbolInfo.CVLFunctionInfo,
        extraCheck: (U, T, CVLSymbolTable.SymbolInfo.CVLFunctionInfo) -> VoidResult<CVLError>,
        f: (List<CVLExp>, tag: CVLExpTag) -> U,
    ): CollectingResult<U, CVLError> {
        return exp.args.map {
            expr(it)
        }.flatten().bind(extraCheck(exp, callee, symbolInfo)) { it, _ ->
            resolve(
                argsMapped = it,
                callee = callee,
                exp = exp,
                gen = f
            )
        }
    }

    override fun definition(exp: CVLExp.ApplyExp.Definition): CollectingResult<CVLExp.ApplyExp.Definition, CVLError> {
        return typeCheckCVLApplication<CVLDefinition, CVLExp.ApplyExp.Definition>(exp, exp.methodIdWithCallContext, "definition", extraCheck = { _, cvlDefinition, _ ->
            val modifiesErrors = cvlDefinition.modifies.filter {
                symbolTable.lookUpFunctionLikeSymbol(it.id, typeEnv = typeEnv)?.isTwoState != true
            }.takeIf { it.isNotEmpty() }?.let {
                CVLError.Exp(
                    exp = exp,
                    message = "Definition of body ${cvlDefinition.id} uses ghosts ${it.joinToString(", ") {
                        it.id
                    }} in two-state context, but invocation is not itself in a two-state context"
                )
            }?.asError()
            val readsErrors = cvlDefinition.reads.filter {
                symbolTable.lookUpFunctionLikeSymbol(it.id, typeEnv = typeEnv)?.isTwoState == true
            }.takeIf { it.isNotEmpty() }?.let {
                CVLError.Exp(
                    exp = exp,
                    message = "Definition of body ${cvlDefinition.id} uses ghosts ${it.joinToString(", ") {
                        it.id
                    }} outside of two state context, but is being invoked in a two-state context"
                )
            }?.asError()
            listOfNotNull(readsErrors, modifiesErrors).flattenToVoid()
        }) { typeChecked, tag ->
            exp.copy(
                args = typeChecked,
                tag = tag
            )
        }
    }

    override fun fieldSel(exp: CVLExp.FieldSelectExp): CollectingResult<CVLExp.FieldSelectExp, CVLError> {
        return super.fieldSel(exp).bind { exp ->
            FieldSelectSemantics.fieldSemantics(
                exp,
                vmTypeHandler = vm@{ ty ->
                    ty.getOrInferPureCVLType(exp.structExp).bind {
                        if (exp.isDynamicArrayLengthExp()) {
                            if(ty.context == FromVMContext.StateValue && ty.descriptor !is VMDynamicArrayTypeDescriptor) {
                                CVLError.Exp(
                                    exp = exp,
                                    "Cannot read length of storage value that is not a dynamic array."
                                ).asError()
                            } else {
                                it.lift()
                            }
                        } else {
                            (it as? CVLType.PureCVLType.Struct)?.lift()
                                ?: CVLError.Exp(
                                    exp = exp,
                                    message = "VM type ${ty.descriptor} is not a valid base for a field lookup"
                                ).asError()
                        }
                    }
                },
                fieldHandler = { ty, field ->
                    ty.fields.find {
                        it.fieldName == field
                    }?.let {
                        updateType(exp, it.cvlType).markComplex(exp.structExp.tag.annotation == ComplexMarker)
                    } ?: CVLError.Exp(exp = exp, message = "Field with name ${exp.fieldName} does not appear in type $ty").asError()
                },
                codeContractNameHandler = { contract, fieldName ->
                    val contractScope = symbolTable.getContractScope(contract.name)
                        ?: error("Typed ${exp.structExp} as CodeContract but no contract exists in symbol table")
                    val symbolInfo = symbolTable.lookUpNonFunctionLikeSymbol(fieldName, contractScope)

                    if (symbolInfo?.symbolValue is ContractTypeDefinition) {
                        ((symbolInfo.symbolValue as ContractTypeDefinition).type as? CVLType.PureCVLType.Enum)?.let { enumType ->
                            updateType(exp, enumType)
                        } ?: CVLError.Exp(exp, "Expected $exp to be an enum type but got $contract").asError()
                    } else if (symbolInfo is CVLSymbolTable.SymbolInfo.ContractStorageType) {
                        val storageType = CVLType.VM(symbolInfo.storageType, FromVMContext.StateValue)
                        updateType(exp, storageType).markStorage()
                    } else if (symbolInfo is CVLSymbolTable.SymbolInfo.ContractImmutableType) {
                        val immutableType = CVLType.VM(symbolInfo.immutableType, FromVMContext.StateValue)
                        updateType(exp, immutableType).markImmutable()
                    } else {
                        CVLError.Exp(exp, "Expected $exp to be an enum type, storage variable or immutable variable, but got $contract")
                            .asError()
                    }
                },
                arrayLenHandler = { ty ->
                    if (ty !is CVLType.PureCVLType.CVLArrayType) {
                        CVLError.Exp(exp, "Expected operand of array length to have an array type, instead got: $ty")
                            .asError()
                    } else if (!exp.isArrayLengthFieldAccess()) {
                        ExpectedArrayLengthAccess(exp)
                            .asError()
                    } else {
                        val isComplex = exp.structExp.tag.annotation == ComplexMarker
                        updateType(exp, CVLType.PureCVLType.CVLArrayType.lengthType).markComplex(isComplex)
                    }
                },
                storageAccess = { ty, field ->
                    when (ty) {
                        is VMStructDescriptor -> {
                            ty.fieldTypes[field]?.let {
                                updateType(exp, CVLType.VM(
                                    context = FromVMContext.StateValue,
                                    descriptor = it
                                )).markStorage()
                            } ?: CVLError.Exp(exp = exp, message = "Field with name ${exp.fieldName} does not appear in type $ty").asError()
                        }
                        is VMDynamicArrayTypeDescriptor -> {
                            updateType(
                                exp, CVLType.VM(
                                    descriptor = ty.lengthType, context = FromVMContext.StateValue
                                )
                            ).markStorage()
                        }

                        else -> CVLError.Exp(exp, "Expected base ${exp.structExp} to be a struct with field ${exp.fieldName}, got ${ty.prettyPrint()}").asError()
                    }
                },
                badType = {
                    FieldSelectOnNonStruct(exp, it).asError()
                }
            )
        }
    }

    override fun invokeExp(exp: CVLExp.ApplyExp.ContractFunction): CollectingResult<CVLExp.ApplyExp.ContractFunction, CVLError> {
        return when (exp) {
            is CVLExp.ApplyExp.ContractFunction.Concrete -> {
                val lkp = symbolTable.lookUpWithMethodIdWithCallContext(methodIdWithCallContext = exp.methodIdWithCallContext, typeEnv) as? CVLSymbolTable.SymbolInfo.CVLFunctionInfo
                    ?: return CVLError.Exp(
                        exp,
                        message = "No contract method definitions found for ${exp.methodIdWithCallContext}"
                    ).asError()
                resolveContractApplication(
                    lkp,
                    exp = exp,
                    storageReference = exp.storage,
                    concrete = { ref, args, storage, tag ->
                        exp.copy(
                            args = args,
                            methodIdWithCallContext = ref,
                            tag = tag,
                            storage = storage
                        ).lift()
                    },
                    symbolic = { _, _, _, _ ->
                        CVLError.Exp(
                            exp = exp,
                            message = "Attempting to compile a concrete application of $exp, but it was symbolic"
                        ).asError()
                    }
                )
            }
            is CVLExp.ApplyExp.ContractFunction.Symbolic -> {
                processSymbolic(
                    exp, invokeStorage = exp.storage
                ) { arg, storageExp, tag ->
                    exp.copy(args = arg, storage = storageExp, tag = tag)
                }
            }
        }
    }

    override fun enumConstant(exp: CVLExp.Constant.EnumConstant): CollectingResult<CVLExp, CVLError> {
        val contract = symbolTable.getContractScope(exp.definingContract)
        // If the defining contract is unknown, then this is some enum from some library/abstract contract
        // that doesn't exist in the scene. In this case the enum should be available in the scope of all contracts,
        // and in particular in `currentContract`s scope.
            ?: symbolTable.getContractScope(CurrentContract)!!
        val valueInfo = symbolTable.lookUpNonFunctionLikeSymbol(exp.enumName, contract)
        if(valueInfo !is CVLSymbolTable.SymbolInfo.CVLValueInfo || valueInfo.symbolValue !is ContractTypeDefinition || (valueInfo.symbolValue as ContractTypeDefinition).type !is CVLType.PureCVLType.Enum) {
            return CVLError.Exp(
                exp = exp,
                message = "In enum constant $exp, ${exp.enumName} is not a valid enum type"
            ).asError()
        }
        val enumType = (valueInfo.symbolValue as ContractTypeDefinition).type as CVLType.PureCVLType.Enum
        if(!enumType.elements.contains(exp.memberName)) {
            return CVLError.Exp(
                exp = exp,
                message = "In enum constant $exp, ${exp.memberName} is not a valid member of the enum"
            ).asError()
        }
        return updateType(
            exp, enumType
        )
    }

    fun typeCheckApplyExp(
        exp: CVLExp.ApplyExp.Inlinable,
    ): CollectingResult<CVLExp.ApplyExp, CVLError> {
        when (exp) {
            is CVLExp.ApplyExp.CVLFunction -> {
                val sym = symbolTable.lookUpFunctionLikeSymbol(exp.methodIdWithCallContext.methodId, typeEnv)?.symbolValue as? CVLFunction
                    ?: return CVLError.Exp(
                        message = "No CVL function with name ${exp.methodIdWithCallContext} declared in scope",
                        exp = exp,
                    ).asError()
                return exp.args.map { expr(it) }.flatten().bind { typedArgs ->
                    if (sym.paramTypes.size != typedArgs.size) {
                        return@bind CVLError.Exp(
                            exp = exp,
                            message = "Function arity mismatch: expected ${sym.params.size} arguments, received ${typedArgs.size}"
                        ).asError()
                    }
                    typedArgs.zip(sym.params) { actual, formal ->
                        if (actual.getCVLType() isNotConvertibleTo  formal.type) {
                            CVLError.Exp(
                                exp = actual,
                                message = "Argument $actual of type ${actual.getCVLTypeOrNull()} for parameter ${formal.id} is not valid for expected type: ${formal.type}"
                            ).asError()
                        } else {
                            actual.lift()
                        }
                    }.flatten().bind {
                        exp.copy(
                            args = typedArgs,
                            tag = exp.tag.copy(type = sym.rets)
                        ).lift()
                    }
                }
            }
            is CVLExp.ApplyExp.ContractFunction.Concrete -> {
                /*
                 TODO(jtoman): revisit the use of host here
                 */
                val funcInfo = symbolTable.lookupMethodInContractEnv(exp.methodIdWithCallContext.host,
                    exp.methodIdWithCallContext.methodId)
                val impls = funcInfo as? CVLSymbolTable.SymbolInfo.CVLFunctionInfo ?: throw IllegalStateException("Could not find $exp")
                return resolveContractApplication(
                    impls, exp.storage, exp = exp, concrete = { ref: ConcreteContractMethod, f: List<CVLExp>, storageReference: CVLExp.VariableExp, t: CVLExpTag ->
                    exp.copy(
                        methodIdWithCallContext = ref,
                        tag = t,
                        storage = storageReference,
                        args = f
                    ).lift()
                }, symbolic = { ref: SymbolicContractMethod, args: List<CVLExp>, storage: CVLExp.VariableExp, tag: CVLExpTag ->
                    CVLExp.ApplyExp.ContractFunction.Symbolic(
                        args = args,
                        storage = storage,
                        tag = tag,
                        methodIdWithCallContext = ref,
                        noRevert = exp.noRevert,
                    ).lift()

                }
                )
            }
            is CVLExp.ApplyExp.ContractFunction.Symbolic -> {
                return processSymbolic(
                    callExp = exp,
                    invokeStorage = exp.storage,
                    mk = { args: List<CVLExp>, storage: CVLExp.VariableExp, cvlExpTag: CVLExpTag ->
                        exp.copy(
                            tag = cvlExpTag,
                            args = args,
                            storage = storage
                        )
                    }
                )
            }
        }
    }


    private fun variableExp(
        symbolInfo: CVLSymbolTable.SymbolInfo?,
        exp: CVLExp.VariableExp,
        typeEnv: CVLTypeEnvironment
    ) = if (symbolInfo != null && symbolInfo is CVLSymbolTable.SymbolInfo.WithCVLType) {
        checkTwoState(exp, symbolInfo.isTwoState, exp.twoStateIndex).map {
            it.copy(tag = it.tag.copy(scope = typeEnv.scope, type = symbolInfo.getCVLType()))
        }.map {
            val symbolValue = symbolInfo.symbolValue
            if (symbolValue is CVLImportedContract) {
                // If this variable is an alias of a contract (i.e. 'currentContract', or declared via 'using') then
                // annotate it with the instanceId that corresponds to the contract. This is used in order to statically
                // evaluate expression such as `f.contract == myContract` where `f` is a parametric method.
                val namespaceSymbol = symbolTable.lookUpContractIdentifierNamespaceSymbol(symbolValue.contractName, exp.getScope())!!
                val instanceId = (namespaceSymbol.symbolValue as CVLContractNamespace).instanceId
                it.copy(tag = it.tag.copy(annotation = CVLExp.VariableExp.ContractInstanceId(instanceId)))
            } else {
                it
            }
        }
    } else {
        val functionWithSameName: CVLSymbolTable.SymbolInfo? = symbolTable.lookUpFunctionLikeSymbol(exp.id, typeEnv)
        val additionalMessage =
            if (functionWithSameName != null) {
                "; Note that a function (and not a variable) named ${exp.id} has been declared; " +
                    "(ghost) functions need to be accessed with parentheses, e.g. \"${exp.id}(...)\"."
            } else {
                ""
            }

        CVLError.Exp(
            exp,
            "unknown variable \"${exp.id}\"" + additionalMessage
        ).asError()
    }

    private fun <T: CVLExp> checkTwoState(
        exp: T,
        isDeclaredTwoState: Boolean,
        usageTwoStateIndex: TwoStateIndex
    ): CollectingResult<T, CVLError> = checkTwoState(exp, isDeclaredTwoState, usageTwoStateIndex) { msg -> CVLError.Exp(exp, msg) }

    private fun <T: CVLLhs> checkTwoState(
        lhs: T,
        isDeclaredTwoState: Boolean,
    ): CollectingResult<T, CVLError> = checkTwoState(lhs, isDeclaredTwoState, TwoStateIndex.NEITHER) { msg -> CVLError.Lhs(lhs, msg) }

    /**
     * A havoc generates a so-called "two state context": the variable/ghost *before* the havoc (@old annotation) and
     * the variable *after* the havoc (@new annotation). This checks that [isDeclaredTwoState] and [usageTwoStateIndex]
     * are coherent.
     *
     * @param isDeclaredTwoState whether or not we are in a two state context
     * @param usageTwoStateIndex what two-state annotation is applied to [exp]
     */
    private fun <T> checkTwoState(
        exp: T,
        isDeclaredTwoState: Boolean,
        usageTwoStateIndex: TwoStateIndex,
        makeError: (String) -> CVLError
    ): CollectingResult<T, CVLError> {
        val errors = mutableListOf<CVLError>()
        if (isDeclaredTwoState && usageTwoStateIndex == TwoStateIndex.NEITHER) {
            errors.add(makeError("symbol bound in two-state context, but used without @new or @old annotations"))
        }

        if (!isDeclaredTwoState && usageTwoStateIndex == TwoStateIndex.NEW) {
            errors.add(makeError("symbol not bound in two-state context, but used with @new annotation"))
        }

        if (!isDeclaredTwoState && usageTwoStateIndex == TwoStateIndex.OLD) {
            errors.add(makeError("symbol not bound in two-state context, but used with @old annotation"))
        }
        return if (errors.isEmpty()) {
            exp.lift()
        } else {
            errors.asError()
        }
    }

    private fun ensureValidBitwiseOperand(e: CVLExp): VoidResult<CVLError> {
        val expType = e.getCVLTypeOrNull()!!.let {
            numberLitToLeastUpperBoundInt(it)
        }
        val int256 = CVLType.PureCVLType.Primitive.IntK(256)
        val uint256 = CVLType.PureCVLType.Primitive.UIntK(256)
        val isTypeChecked =
            expType isConvertibleTo int256
                || expType isConvertibleTo uint256
                || CVLType.PureCVLType.Primitive.BytesK.saturate().any { bytesK ->
                expType isConvertibleTo bytesK
            }
        return if (!isTypeChecked) {
            CVLError.Exp(
                e,
                "Expression $e must be a subtype of $int256 or $uint256 or be a bytesK type"
            ).asError()
        } else {
            ok
        }
    }

    /**
     * - Check that [l] and [r] are both convertible to `mathint`
     * - update [exp]'s type to either `NumberLiteral` (if it is constant) or `Mathint`
     * @return the updated expression
     */
    private inline fun <reified T:CVLExp> ensureInteger(l: CVLExp, r: CVLExp, exp: T): CollectingResult<T, CVLError> = collectingErrors {
        collect(ensureIsConvertible(l, CVLType.PureCVLType.Primitive.Mathint))
        collect(ensureIsConvertible(r, CVLType.PureCVLType.Primitive.Mathint))

        return@collectingErrors exp.eval()?.let {
            bind(updateType(exp, CVLType.PureCVLType.Primitive.NumberLiteral(it.n)))
        } ?: bind(updateType(exp, CVLType.PureCVLType.Primitive.Mathint))
    }

    private inline fun <reified T : CVLExp> ensureBoolean(l: CVLExp, r: CVLExp, exp: T): CollectingResult<T, CVLError> = collectingErrors {
        collect(ensureIsConvertible(l, CVLType.PureCVLType.Primitive.Bool))
        collect(ensureIsConvertible(r, CVLType.PureCVLType.Primitive.Bool))
        bind(updateType(exp, CVLType.PureCVLType.Primitive.Bool))
    }

    private fun <T : CVLExp> ensureIsConvertible(_exp: T, expected: CVLType.PureCVLType): VoidResult<CVLError> {
        val exp : CVLExp = _exp
        val currType = exp.getCVLType()

        if (currType isConvertibleTo expected) { return ok }

        if (exp is CVLExp.ApplyExp && exp.methodIdWithCallContext is SymbolicContractMethod) {
            return ParametricReturn(exp, exp.methodIdWithCallContext as SymbolicContractMethod).asError()
        }

        if (currType is CVLType.VM) {
            val messages = (currType.descriptor.converterTo(expected, currType.context.getVisitor()) as? CollectingResult.Error)?.messages?.joinToString()
                ?: error("currType isn't ConvertibleTo expected, yet there is a converter??")
            return CVLError.Exp(exp, messages).asError()
        }

        return NotConvertible(exp, expected).asError()
    }


    private inline fun <reified T : CVLExp> updateType(exp: T, expected: CVLType): CollectingResult<T, Nothing> {
        return (exp.updateTag(exp.tag.copy(type = expected)) as T).lift()
    }

    private fun emitUnnecessaryEnvWarn(range: Range, callee: ContractFunction) {
        val message = "Passed `env` argument to an `envfree` method ${callee.methodSignature.qualifiedMethodName.printQualifiedFunctionName()}"
        Logger.regression { message }
        CVLWarningLogger.syntaxWarning(message, range)
    }

    private fun <T : CVLExp.ApplicationExp> resolveContractApplication(
        functionInfo: CVLSymbolTable.SymbolInfo.CVLFunctionInfo,
        storageReference: CVLExp.VariableExp,
        exp: T,
        symbolic: (ref: SymbolicContractMethod, f: List<CVLExp>, storageReference: CVLExp.VariableExp, t: CVLExpTag) -> CollectingResult<T, CVLError>,
        concrete: (ref: ConcreteContractMethod, f: List<CVLExp>, storageReference: CVLExp.VariableExp, t: CVLExpTag) -> CollectingResult<T, CVLError>
    ): CollectingResult<T, CVLError> = collectingErrors {
        if (((functionInfo.symbolValue as? ContractFunction)?.methodSignature is UniqueMethod) &&
            // We do call the constructor in derived rules, e.g. in the instate rule of an invariant
            this@CVLExpTypeCheckerWithContext.typeEnv.scope.enclosingRule()?.isDerived() != true) {
            check(((functionInfo.symbolValue as ContractFunction).methodSignature as UniqueMethod).attribute == MethodAttribute.Unique.Constructor) {
                "All explicit references Unique functions other than the constructor should have been caught already"
            }

            returnError(CVLError.Exp(exp, "It is not allowed to call a constructor function directly"))
        }

        if (functionInfo.impFuncs.any { (it as ContractFunction).annotation.library }) {
            // If any of the implementations is a library function they all are - they all come from the same contract/library
            returnError(LibraryFunctionCallNotSupported(exp))
        }

        val storageReference = bind(variable(storageReference))
        collect(ensureIsConvertible(storageReference, CVLType.PureCVLType.VMInternal.BlockchainState))
        val argsMapped = collectAndFilter(exp.args.map(this@CVLExpTypeCheckerWithContext::expr))

        fun unresolvedVirtualFunc(func: Function<*, *>?): T {
            check(exp is CVLExp.UnresolvedApplyExp) {
                "How did we resolve $exp?"
            }
            return exp.copy(tag = exp.tag.copy(
                annotation = CVLExp.UnresolvedApplyExp.VirtualFunc,
                type = func?.let {
                    // If there's only a single virtual candidate, use its return type for the rest for the typechecking,
                    // Otherwise just use `Bottom` so the typechecker flow will not break.
                    getContractReturnType(it as ContractFunction)
                } ?: CVLType.PureCVLType.Bottom
            ), args = argsMapped, invokeStorage = storageReference).uncheckedAs<T>()
        }

        val hasEnv = argsMapped.firstOrNull()?.getCVLType() == EVMBuiltinTypes.env
        if (argsMapped.any { it.getCVLTypeOrNull() == CVLType.PureCVLType.VMInternal.RawArgs }) {
            if (functionInfo.impFuncs.all { (it as ContractFunction).annotation.virtual }) {
                return@collectingErrors unresolvedVirtualFunc(functionInfo.impFuncs.singleOrNull())
            }
            if (functionInfo.impFuncs.size == 1) {
                val callee = functionInfo.impFuncs.single() as ContractFunction
                val ret = getContractReturnType(callee)
                val envfree = callee.annotation.envFree
                collect(
                    checkCalldataConvention(
                        exp, argsMapped, envfreeAllowed = envfree,
                        errorStr = "Invalid calldataarg use: call convention must be ${exp.callableName}(${if (!callee.annotation.envFree) "env, " else ""}calldataarg)"
                    )
                )
                if (envfree && hasEnv) {
                    emitUnnecessaryEnvWarn(exp.getRangeOrEmpty(), callee)
                }
                return@collectingErrors bind(concrete(
                    callee.methodSignature.toCallableName(), argsMapped, storageReference, exp.tag.copy(
                        type = ret,
                        annotation = CallResolution.CalldataPassing(
                            target = callee,
                            hasEnv = hasEnv
                        )
                    )
                ))
            } else {
                val called = Overloading(
                    exp.callableName, functionInfo.symbolValue.functionIdentifier.host as SolidityContract
                )
                collect(checkCalldataConvention(
                    exp, argsMapped, envfreeAllowed = false,
                    errorStr = "Invalid calldataarg use: call convention must be ${exp.callableName}(env, calldataarg)"
                ))
                return@collectingErrors bind(symbolic(
                    called, argsMapped, storageReference, exp.tag.copy(type = CVLType.PureCVLType.Void))
                )
            }
        } else {
            val noEnv = if (hasEnv) {
                argsMapped.drop(1)
            } else {
                argsMapped
            }

            val funcs = functionInfo.impFuncs.filter { func ->
                (func as ContractFunction).argsMatch(noEnv).isResult()
            }

            if (funcs.isEmpty()) {
                returnError(CVLError.Exp(
                    exp = exp,
                    message = "Could not find an overloading of method ${functionInfo.symbolValue.functionIdentifier} " +
                        "that matches the given arguments: " +
                        "${noEnv.joinToString(", ") { it.getCVLTypeOrNull().toString() }}."
                ))
            }

            val nonVirtImplementations = funcs.filter { !(it as ContractFunction).annotation.virtual }
            if (nonVirtImplementations.size > 1) {
                returnError(CVLError.Exp(
                    exp = exp,
                    message = "Found multiple overloadings of method ${functionInfo.symbolValue.functionIdentifier} " +
                        "that match the given arguments: " +
                        "${noEnv.joinToString(", ") { it.getCVLTypeOrNull().toString() }}."
                ))
            }

            if (nonVirtImplementations.size == 1) {
                val candidate = nonVirtImplementations.single() as ContractFunction
                if (!hasEnv && !candidate.annotation.envFree) {
                    returnError(CVLError.Exp(
                        exp = exp,
                        message = "Missing environment parameter to non-envfree function ${candidate.methodSignature}"
                    ))
                }
                if (hasEnv && candidate.annotation.envFree) {
                    emitUnnecessaryEnvWarn(exp.getRangeOrEmpty(), candidate)
                }

                return@collectingErrors bind(concrete(
                    candidate.methodSignature.toCallableName(),
                    argsMapped, storageReference, exp.tag.copy(annotation = CallResolution.DirectPassing(target = candidate, hasEnv), type = getContractReturnType(candidate))
                ))
            }

            // all implementations are virtual
            return@collectingErrors unresolvedVirtualFunc(funcs.singleOrNull())
        }
    }

    private fun QualifiedMethodParameterSignature.toCallableName() =
        if (this is UniqueMethod) {
            this
        } else {
            ConcreteMethod(
                signature = (this as ExternalQualifiedMethodParameterSignature),
            )
        }

    override fun application(exp: CVLExp.ApplicationExp): CollectingResult<CVLExp, CVLError> {
        return when (exp) {
            is CVLExp.ApplyExp -> applyExp(exp)
            is CVLExp.UnresolvedApplyExp -> resolveApply(exp)
            is CVLExp.AddressFunctionCallExp -> `impossible!`
        }
    }

    private fun handleUnresolvedApplyIsParametricFunction(exp: CVLExp.UnresolvedApplyExp):  CollectingResult<CVLExp.ApplyExp.ContractFunction.Symbolic, CVLError> {
        return if (exp.twoStateIndex != TwoStateIndex.NEITHER) {
            CVLError.Exp(
                exp,
                message = "Cannot use two-state context on CVL function ${exp.methodId}"
            ).asError()
        } else {
            if (exp.base != null) {
                if (exp.base !is CVLExp.VariableExp) {
                    return CVLError.Exp(exp, "`method` type variables can only be called on contract aliases (created via `using` statements)").asError()
                }
                symbolTable.lookUpNonFunctionLikeSymbol(exp.base.id, exp.getScope())?.let { symbolInfo ->
                    when (val ty = symbolInfo.getCVLTypeOrNull()) {
                        is CVLType.PureCVLType.Primitive.CodeContract -> ty.name.lift()
                        is CVLType.PureCVLType.Bottom -> AllContracts.lift()
                        is CVLType.PureCVLType.Primitive.AccountIdentifier ->
                            NotAContractInstance(exp).asError()

                        else -> NoSuchContractInstance(exp).asError()
                    }
                } ?: return NoSuchContractInstance(exp).asError()
            } else {
                AllContracts.lift()
            }.bind { contract ->
                val context = ParametricMethod(exp.methodId, contract)
                if (exprDepth > 1) { ParametricReturn(exp, context).asError() }
                else {
                    processSymbolic(exp, exp.invokeStorage) { args, storage, tag ->
                        CVLExp.ApplyExp.ContractFunction.Symbolic(
                            args = args,
                            tag = tag,
                            storage = storage,
                            noRevert = exp.invokeIsSafe,
                            methodIdWithCallContext = context
                        )
                    }
                }
            }
        }
    }

    fun resolveApply(exp: CVLExp.UnresolvedApplyExp): CollectingResult<CVLExp.ApplicationExp, CVLError> = collectingErrors {
        // First, check if this is a parametric method
        symbolTable.lookUpFunctionLikeSymbol(exp.methodId, typeEnv.scope)?.let { resolved ->
            if (resolved is CVLSymbolTable.SymbolInfo.CVLValueInfo && resolved.getCVLType() == EVMBuiltinTypes.method) {
                return@collectingErrors bind(handleUnresolvedApplyIsParametricFunction(exp))
            }
        }

        val base = exp.base?.let { bind(expr(it)) }
        val baseType = base?.getOrInferPureCVLType()

        // Now check if it's an address function call
        if (baseType is CVLType.PureCVLType.Primitive.AccountIdentifier) {
            return@collectingErrors bind(handleAddressFunctionCall(exp, base))
        }

        val contractScope = if (base != null) {
            if (baseType is CVLType.PureCVLType.Primitive.CodeContract) {
                check(base is CVLExp.VariableExp) { "A code contract is always a VariableExp, no??" }
                // definitely calling a function from some contract, let's get the scope of that contract and look
                // for the function in that scope
                symbolTable.getContractScope(baseType.name) ?: error("the type is CodeContract but there's no such contract")
            } else {
                returnError(ApplyMethodOnNonContract(exp))
            }
        } else {
            symbolTable.getContractScope(CurrentContract)!!
        }

        val functionInfo = symbolTable.lookUpFunctionLikeSymbol(exp.methodId, contractScope)?.let { symInfo ->
            symInfo as? CVLSymbolTable.SymbolInfo.CVLFunctionInfo
        }

        val internalFunctionHarnessInfo = contractScope.enclosingContract()
            ?.contract
            ?.internalFunctionHarnesses
            ?.get(exp.methodId)
            ?.let { harnessName -> symbolTable.lookUpFunctionLikeSymbol(harnessName, contractScope) }
            ?.let { symInfo -> symInfo as? CVLSymbolTable.SymbolInfo.CVLFunctionInfo }

        val res = functionInfo?.let {
            internalFunctionHarnessInfo?.let {
                CVLSymbolTable.SymbolInfo.CVLFunctionInfo(functionInfo.symbolValue, functionInfo.impFuncs + internalFunctionHarnessInfo.impFuncs)
            } ?: functionInfo
        } ?: internalFunctionHarnessInfo

        if (res == null) {
            val errMessage = contractScope.enclosingContract()?.contract
                ?.internalFunctions?.values?.map { it.method }
                ?.find { method ->
                    method.name == exp.methodId && exp.args.drop(1).zipPred(method.fullArgs) { arg, type ->
                        val fakeType = if (type.location == SolcLocation.storage) {
                            // We want to see if the type is matching, the location is handled in the error strings below.
                            // If we'd leave the storage location then convertibleToVMType would always fail because we can't
                            // convert to storage-located variables.
                            type.copy(location = SolcLocation.memory)
                        } else {
                            type
                        }
                        bind(expr(arg)).getCVLType()
                            .convertibleToVMType(fakeType.toVMTypeDescriptor(), ToVMContext.ArgumentPassing).isResult()
                    }
                }
                ?.let { method ->
                    val storageInputIndex = method.fullArgs.indexOfFirst { it.location == SolcLocation.storage }
                    val storageOutputIndex = method.returns.indexOfFirst { it.location == SolcLocation.storage }
                    val reason = if (storageInputIndex >= 0) {
                        "because input parameter '${method.paramNames[storageInputIndex]}' has storage location"
                    } else if (storageOutputIndex >= 0) {
                        "because output parameter #$storageOutputIndex has storage location"
                    } else {
                        ""
                    }
                    "Cannot call internal function ${method.name} from spec $reason"
                }
                ?: (symbolTable.lookUpNonFunctionLikeSymbol(exp.methodId, typeEnv.scope)
                    ?.symbolValue as? CVLInvariant)?.let { "Cannot apply an invariant ${exp.methodId}, it can only be applied under requireInvariant" }
                ?: "No function-like entry for ${exp.methodId} was found in the symbol table. Perhaps something was misspelled?"

            returnError(CVLError.Exp(exp, errMessage))
        }

        check(res.symbolValue is ContractFunction) {
            "All other types of 'concrete' application should have already been resolved at this stage, got ${res.symbolValue}"
        }

        if (exp.twoStateIndex != TwoStateIndex.NEITHER) {
            returnError(CVLError.Exp(
                exp, message = "Cannot use two-state context on contract function ${exp.methodId}"
            ))
        }

        return@collectingErrors bind(resolveContractApplication(exp = exp,
            functionInfo = res,
            storageReference = exp.invokeStorage,
            symbolic = { called, argsMapped, storageExp, _ ->
                CVLExp.ApplyExp.ContractFunction.Symbolic(
                    args = argsMapped,
                    tag = exp.tag.copy(type = CVLType.PureCVLType.Void),
                    storage = storageExp,
                    methodIdWithCallContext = called,
                    noRevert = exp.invokeIsSafe,
                ).lift()
            },
            concrete = { called, argsMapped, storageExp, tag ->
                CVLExp.ApplyExp.ContractFunction.Concrete(
                    storage = storageExp,
                    isWhole = false,
                    noRevert = exp.invokeIsSafe,
                    args = argsMapped,
                    tag = tag,
                    methodIdWithCallContext = called
                ).lift()
            }
        ))
    }

    private fun handleAddressFunctionCall(exp: CVLExp.UnresolvedApplyExp, base: CVLExp): CollectingResult<CVLExp.ApplicationExp, CVLError> = collectingErrors {
        val (args, storage) = bind(exp.args.map(this@CVLExpTypeCheckerWithContext::expr).flatten(), variable(exp.invokeStorage))

        if (!exp.invokeIsSafe) {
            returnError(AddressFuncCallWithRevert(exp))
        }

        if (args.firstOrNull()?.getCVLType() != EVMBuiltinTypes.env) {
            returnError(AddressFuncCallNoEnv(exp))
        }
        val argsWithoutEnv = args.drop(1)

        val contractScopes = symbolTable.getAllContractScopes().toSet()
        val relevantFuncs = contractScopes
            .mapNotNull { symbolTable.lookUpFunctionLikeSymbol(exp.methodId, it)?.symbolValue as? ContractFunction }
            .filter { func ->
                if (argsWithoutEnv.singleOrNull()?.getCVLType() == CVLType.PureCVLType.VMInternal.RawArgs) {
                    true
                } else {
                    func.methodSignature.params.map { it.vmType }
                        .zipPred(argsWithoutEnv.map { it.getOrInferPureCVLType() }) { paramType, argType ->
                            val canConvert = paramType.getPureTypeToConvertFrom(ToVMContext.ArgumentPassing).map {
                                argType.isSubtypeOf(it)
                            }
                            canConvert.isResult() && canConvert.force()
                        }
                }
            }

        if (relevantFuncs.isEmpty() && !Config.Foundry.get()) {
            // In Foundry we use this feature to implement the vm.deal cheatcode. But it's only relevant if there are
            // ERC20 contracts in the scene, so we don't want to fail typechecking for foundry if we don't happen to
            // have such contracts in the scene.
            returnError(AddressFuncCallNoFuncs(exp))
        }

        val returnTypes = relevantFuncs.mapToSet { getContractReturnType(it) }
        val returnType = if (relevantFuncs.isNotEmpty()) {
            returnTypes.singleOrNull()
                ?: returnError(AddressFuncCallMultipleReturnTypes(exp, returnTypes))
        } else {
            CVLType.PureCVLType.Bottom
        }

        return@collectingErrors CVLExp.AddressFunctionCallExp(
            base,
            exp.methodId,
            args,
            storage,
            relevantFuncs,
            exp.tag.updateType(returnType)
        )
    }

    private fun getContractReturnType(callee: ContractFunction): CVLType {
        val ret = callee.rets.let { rets ->
            if (rets.isEmpty()) {
                CVLType.PureCVLType.Void
            } else if (rets.size == 1) {
                CVLType.VM(rets.first(), FromVMContext.ReturnValue)
            } else {
                CVLType.VM(VMFunctionReturn(rets), FromVMContext.ReturnValue)
            }
        }
        return ret
    }

    private fun <T : SpecFunction, U> resolve(
        argsMapped: List<CVLExp>,
        callee: T,
        exp: CVLExp.ApplicationExp,
        gen: (args: List<CVLExp>, tag: CVLExpTag) -> U
    ): CollectingResult<U, CVLError> {
        if (callee.paramTypes.size != argsMapped.size) {
            return CVLError.Exp(
                exp = exp,
                message = "Differing arities for function call to ${callee.functionIdentifier.methodId}, expected ${callee.paramTypes.size}, got ${argsMapped.size}"
            ).asError()
        }
        return argsMapped.zip(other = callee.paramTypes) { actual, formal ->
            ensureIsConvertible(actual, formal)
        }.flattenToVoid().map {
            gen(
                argsMapped, exp.tag.copy(type = callee.rets, annotation = callee)
            )
        }
    }

    private fun isParametricFunctionCall(args: List<CVLExp>, envfreeAllowed: Boolean): Boolean {
        return (args.size == 2 && args[0].getCVLTypeOrNull() == EVMBuiltinTypes.env && args[1].getCVLTypeOrNull() == CVLType.PureCVLType.VMInternal.RawArgs) ||
            (envfreeAllowed && args.size == 1 && args[0].getCVLTypeOrNull() == CVLType.PureCVLType.VMInternal.RawArgs)
    }

    private fun checkCalldataConvention(
        callExp: CVLExp.ApplicationExp,
        argsMapped: List<CVLExp>,
        envfreeAllowed: Boolean = false,
        errorStr: String
    ): VoidResult<CVLError> {
        if (!isParametricFunctionCall(argsMapped, envfreeAllowed)) {
            return CVLError.Exp(
                exp = callExp,
                message = errorStr
            ).asError()
        }
        return ok
    }

    private fun processSymbolic(
        callExp: CVLExp.ApplicationExp,
        invokeStorage: CVLExp.VariableExp,
        mk: (List<CVLExp>, CVLExp.VariableExp, CVLExpTag) -> CVLExp.ApplyExp.ContractFunction.Symbolic
    ): CollectingResult<CVLExp.ApplyExp.ContractFunction.Symbolic, CVLError> {
        return variable(invokeStorage).bind { storageExp ->
            ensureIsConvertible(storageExp, CVLType.PureCVLType.VMInternal.BlockchainState).bind {
                callExp.args.map { this.expr(it) }.flatten().bind { args ->
                    checkCalldataConvention(argsMapped = args, callExp = callExp,
                        errorStr = "Invalid parametric method application: call convention must be ${callExp.callableName}(env, calldataarg)"
                    ).map {
                        mk(args, storageExp, callExp.tag.copy(type = CVLType.PureCVLType.Void))
                    }
                }
            }
        }
    }

    fun typeCheck(lhs: CVLLhs): CollectingResult<CVLLhs, CVLError> =
        when (lhs) {
            is CVLLhs.Array -> arrayLhs(lhs)
            is CVLLhs.Id -> idLhs(lhs)
        }

    private fun wrapInCastExpression(exp: CVLExp): CVLExp =
        CVLExp.CastExpr(
            arg = exp,
            toCastType = CVLType.PureCVLType.Primitive.HashBlob,
            castType = CastType.CONVERT,
            tag = exp.tag.copy(type = CVLType.PureCVLType.Primitive.HashBlob,
                annotation = BoxingType(isIdentity = false))
        )
    override fun arrayLhs(arrayLhs: CVLLhs.Array): CollectingResult<CVLLhs.Array,CVLError> {
        val index = expr(arrayLhs.index)
        val innerLhs = typeCheck(arrayLhs.innerLhs)
        return index.bind(innerLhs) { index, lhsInner ->
            val mappingType = lhsInner.tag.getCVLTypeOrNull()
                ?: return@bind CVLError.Lhs(lhsInner, "Did not resolve a type for $lhsInner").asError()
            when (mappingType) {
                is CVLType.PureCVLType.Ghost.Mapping -> {
                    if(index.getCVLType() isConvertibleTo CVLType.PureCVLType.DynamicArray.PackedBytes && mappingType.key is CVLType.PureCVLType.Primitive.HashBlob){
                        arrayLhs.copy(innerLhs = lhsInner, index = wrapInCastExpression(index), tag = CVLExpTag(scope = arrayLhs.getScope(), mappingType.value, arrayLhs.range)).lift()
                    } else if (index.getCVLType() isNotConvertibleTo (mappingType.key)) {
                        CVLError.Lhs(
                            lhsInner,
                            "incompatible first parameter: ${index.getCVLType()} to " +
                                "${lhsInner.getCVLType()}"
                        ).asError()
                    } else {
                        arrayLhs.copy(innerLhs = lhsInner, index = index, tag = CVLExpTag(scope = arrayLhs.getScope(), mappingType.value, arrayLhs.range)).lift()
                    }
                }
                is CVLType.PureCVLType.CVLArrayType -> {
                    if(index.getCVLType() isNotConvertibleTo CVLType.PureCVLType.Primitive.UIntK(256)) {
                        CVLError.Lhs(
                            lhsInner,
                            "array index should be an integer type, got ${index.getCVLType()}"
                        ).asError()
                    } else if(mappingType.elementType !is CVLType.PureCVLType.CVLValueType) {
                        CVLError.Lhs(
                            lhs = arrayLhs,
                            "Cannot write elements for arrays with type $mappingType"
                        ).asError()
                    } else {
                        // this is a type error, but we let the cmd type checker report this as it gives better hints
                        arrayLhs.copy(
                            innerLhs = lhsInner,
                            index = index
                        ).lift()
                    }
                }
                else -> return@bind CVLError.Lhs(lhsInner, "\"$lhsInner\" is a function, not a mapping or array.").asError()
            }
        }
    }

    override fun idLhs(idLhs: CVLLhs.Id): CollectingResult<CVLLhs.Id, CVLError> {
        val symbolInfo: CVLSymbolTable.SymbolInfo? =
            symbolTable.lookUpNonFunctionLikeSymbol(idLhs.id, typeEnv.scope)
                ?: symbolTable.lookUpFunctionLikeSymbol(idLhs.id, typeEnv.scope)
        return if (symbolInfo != null) {
            // an assignment modifies the current variable--it makes no sense to be in a two-state context
            checkTwoState(idLhs, symbolInfo.isTwoState).map { _idLhs ->
                val newTag = _idLhs.tag.copy(scope = typeEnv.scope, type = symbolInfo.getCVLTypeOrNull())
                _idLhs.updateTag(newTag)
            }
        } else {
            CVLError.Lhs(
                idLhs,
                "unknown variable \"${idLhs.id}\""
            ).asError()
        }
    }
}

class CVLExpTypeChecker(
    private val symbolTable: CVLSymbolTable
) {
    private fun typeCheckerWithEnv(typeEnv: CVLTypeEnvironment) = CVLExpTypeCheckerWithContext(symbolTable, typeEnv)

    fun typeCheck(exp: CVLExp, typeEnv: CVLTypeEnvironment): CollectingResult<CVLExp, CVLError> = typeCheckerWithEnv(typeEnv).expr(exp)

    fun typeCheck(target: CVLExp.HavocTarget, typeEnv: CVLTypeEnvironment): CollectingResult<CVLExp.HavocTarget, CVLError> = typeCheckerWithEnv(typeEnv).havocTarget(target)

    fun typeCheck(lhs: CVLLhs, typeEnv: CVLTypeEnvironment): CollectingResult<CVLLhs, CVLError> = typeCheckerWithEnv(typeEnv).typeCheck(lhs)

    // A specific function that gets and returns CVLLhs.Id, The function above is more generic and includes this one
    fun typeCheck(lhs: CVLLhs.Id, typeEnv: CVLTypeEnvironment): CollectingResult<CVLLhs.Id, CVLError>{
        return CVLExpTypeCheckerWithContext(symbolTable, typeEnv).idLhs(lhs)
    }

    fun typeCheckApplyExp(exp: CVLExp.ApplyExp.Inlinable, typeEnv: CVLTypeEnvironment): CollectingResult<CVLExp.ApplyExp, CVLError> {
        return typeCheckerWithEnv(typeEnv).typeCheckApplyExp(exp)
    }

    fun typeCheckUnresolved(
        exp: CVLExp.UnresolvedApplyExp,
        typeEnv: CVLTypeEnvironment,
    ): CollectingResult<CVLExp.ApplicationExp, CVLError> {
        return typeCheckerWithEnv(typeEnv).resolveApply(exp)
    }
}
