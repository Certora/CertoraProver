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

package report.calltrace

import datastructures.stdcollections.*
import log.*
import report.calltrace.formatter.CallTraceValueFormatter
import report.calltrace.formatter.FormatterType
import solver.CounterexampleModel
import spec.CVLExpToTACExprMeta
import spec.cvlast.CVLExp
import spec.cvlast.CVLExpTag
import spec.cvlast.CVLType
import utils.*
import vc.data.TACCmd
import vc.data.TACExpr
import vc.data.TACMeta
import vc.data.TACSymbol
import vc.data.state.TACValue
import verifier.SKOLEM_VAR_NAME

private val logger = Logger(LoggerTypes.COMMON)

/**
 * Returns a unique id for each subclass of [CVLExp], which is associative.
 * If this [CVLExp] is not associative, returns null.
 */
fun CVLExp.toAssociativeExpId(): Int? = when (this) {
    is CVLExp.BinaryExp.BwOrExp -> 0
    is CVLExp.BinaryExp.AddExp -> 1
    is CVLExp.BinaryExp.BwAndExp -> 2
    is CVLExp.BinaryExp.BwXOrExp -> 3
    is CVLExp.BinaryExp.IffExp -> 4
    is CVLExp.BinaryExp.LandExp -> 5
    is CVLExp.BinaryExp.LorExp -> 6
    is CVLExp.BinaryExp.MulExp -> 7
    else -> null
}


/**
 * Represents a CVL Expression whose concrete syntax is [expLabel] and its evaluation
 * w.r.t. an underlying [CounterexampleModel] is [formattedValue].
 */
data class EvaldCVLExp(
    val expLabel: CVLReportLabel.Exp,
    val formattedValue: TACValue?,
    val tacExpr: TACExpr,
    val subExpressions: List<EvaldCVLExp>
)

/**
 * The [CallTrace] uses an instance of this builder to rebuild/reproduce syntax-tree of CVL expressions that have been
 * compiled into TAC expressions and assign expression commands.
 */
class EValdCVLExpAstBuilder(val model: CounterexampleModel, val formatter: CallTraceValueFormatter) {

    /** Maps "output" variable symbols to their corresponding [EvaldCVLExp]s. */
    private val builderState: MutableMap<TACSymbol.Var, EvaldCVLExp> = mutableMapOf()

    /**
     * This method returns a list containing either the [EvaldCVLExp] itself or all of its subexpressions.
     * We compare the current expression to the previous expression, which should be the parsed expression so far.
     * For example, if we want to parse "A && B && C", we would first parse "A", then "B", then "A && B", then
     * "C", and then "(A && B) && C", and therefore our lhs expression would have the type "AND", and the current
     * expression would also have the type "AND", and therefore they should be combined.
     * To decide which one should be used, we check whether the previous expression was of the same type,
     * if it's associative and if it's the lhs expression, because if the rhs expression has subexpressions,
     * that means it's been evaluated already, and therefore it was inside brackets.
     */
    private fun getSubExpressionsIfAssociative(
        currEvaldExp: EvaldCVLExp,
        currExpAssociativeId: Int?,
        prevExpAssociativeId: Int?,
        index: Int
    ): List<EvaldCVLExp> =
        if (prevExpAssociativeId != null && currExpAssociativeId == prevExpAssociativeId && currEvaldExp.subExpressions.isNotEmpty() && index == 0) {
            currEvaldExp.subExpressions
        } else {
            listOf(currEvaldExp)
        }

    /**
     * If [boolCondSym] is a (Boolean) "output" variable of a CVL expression E (i.e., if [isCVLExpOutVar] returns true),
     * adds the syntax-tree of E to [currCallInstance], such that each syntax-tree node is a [CallInstance]
     * whose return value is the evaluation of the corresponding subexpression of E w.r.t. [model].
     */
    fun materializeCVLBoolCondExpInfo(
        boolCondSym: TACSymbol,
        currCallInstance: CallInstance.ScopeInstance,
    ) {
        fun traverseEvaldCvlExp(
            evaldExp: EvaldCVLExp,
            currExpCallInstance: CallInstance.ScopeInstance,
            seen: MutableSet<EvaldCVLExp>
        ) {
            if (evaldExp in seen) {
                val label = evaldExp.expLabel
                val errorExplanation =
                    CallInstance.CVLExpInstance.withStringExpAndValue(
                        "Cycle detected for $label",
                        range = label.range.nonEmpty(),
                        value = null,
                        formatterType = FormatterType.Value.Unknown("cycle detected type"), // XXX hope this is unused ..
                        formatter = formatter,
                    )
                currExpCallInstance.addChild(errorExplanation)
                return
            }

            val type =
                (evaldExp.expLabel.exp.getCVLType() as? CVLType.PureCVLType.CVLValueType)?.let { cvlType ->
                    FormatterType.Value.CVL(cvlType)
                } ?: FormatterType.Value.Unknown("unknown subexpression type")

            val evaldExpCallInstance = CallInstance.CVLExpInstance.withStringExpAndValue(
                exp = evaldExp.expLabel.toString(),
                range = evaldExp.expLabel.range.nonEmpty(),
                value = evaldExp.formattedValue,
                formatterType = type,
                formatter = formatter,
            )

            seen.add(evaldExp)
            currExpCallInstance.addChild(evaldExpCallInstance)

            evaldExp.subExpressions.forEach { evaldSubExp ->
                traverseEvaldCvlExp(evaldSubExp, evaldExpCallInstance, seen)
            }

            seen.remove(evaldExp)
        }

        if (boolCondSym is TACSymbol.Var && isCVLExpOutVar(boolCondSym)) {
            val condSymExp = evaldCVlExpOf(boolCondSym)
            Logger.regression { "Exp ${condSymExp.expLabel} has the sub expressions: ${condSymExp.subExpressions.map { it.expLabel }}" }
            traverseEvaldCvlExp(condSymExp, currCallInstance, mutableSetOf())
        }
    }

    /** Get the label of symbol [sym]. */
    fun labelForSymbol(sym: TACSymbol): CVLReportLabel? = if (sym is TACSymbol.Var && isCVLExpOutVar(sym)) {
        evaldCVlExpOf(sym).expLabel
    } else {
        null
    }

    /**
     * If the lhs of [cmd] denotes an "output" variable of a compiled CVL expression,
     * adds the corresponding [EvaldCVLExp] to this [EValdCVLExpAstBuilder].
     */
    fun step(cmd: TACCmd.Simple.AssigningCmd.AssignExpCmd) {
        val meta: CVLExpToTACExprMeta = cmd.meta[TACMeta.CVL_EXP] ?: return

        if (cmd.rhs is TACExpr.QuantifiedFormula) {
            val cvlExp = meta.exp as? CVLExp.QuantifierExp ?: throw IllegalStateException("CVLExp was expected to be CVLExp.QuantifierExp but got ${meta.exp}")

            // If forall is true, don't create parse tree.
            // note, this affects only outermost forall.
            // If we want to affect forall inside forall, this check should be moved to quantifiedFormulaStep.
            // However, such forall doesn't have TACSymbol, so we have to evaluate the whole expression.
            val evald = if (model.valueAsTACValue(cmd.lhs) == TACValue.True) {
                EvaldCVLExp(CVLReportLabel.Exp(cvlExp), TACValue.True, cmd.rhs, emptyList())
            } else {
                quantifiedFormulaStep(cmd.rhs, cvlExp)
            }

            builderState[cmd.lhs] = evald
        } else {
            val subExpressions = subExpressionsForExp(cmd.rhs, meta)
            step(sym = cmd.lhs, modelSym = cmd.lhs, exp = cmd.rhs, label = CVLReportLabel.Exp(meta.exp), subExpressions = subExpressions)
        }
    }

    private fun quantifiedFormulaStep(exp: TACExpr.QuantifiedFormula, cvlExp: CVLExp.QuantifierExp): EvaldCVLExp {
        if (exp.quantifiedVars.all { SKOLEM_VAR_NAME !in it.meta }) {
            return EvaldCVLExp(CVLReportLabel.Exp(cvlExp), null, exp, emptyList())
        }

        exp.quantifiedVars.forEach { qVar ->
            val skolemName = qVar.meta[SKOLEM_VAR_NAME]
            if (skolemName == null) {
                logger.warn { "$qVar was not skolemized" }
                throw IllegalStateException("$qVar was not skolemized")
            }

            val cvlQVar =
                CVLExp.VariableExp(
                    cvlExp.param.id,
                    CVLExpTag(cvlExp.getScope(), cvlExp.param.type, cvlExp.param.range)
                )

            val modelSym = TACSymbol.Var(skolemName, qVar.tag)
            step(sym = qVar, modelSym = modelSym, exp = exp, label = CVLReportLabel.Exp(cvlQVar), subExpressions = emptyList())
        }

        val expBody = exp.body
        val cvlExpBody = cvlExp.body
        val body = if (expBody is TACExpr.QuantifiedFormula && cvlExpBody is CVLExp.QuantifierExp) {
            quantifiedFormulaStep(expBody, cvlExpBody)
        } else {
            inlinedExpStep(expBody, cvlExpBody)
        }

        val subExpressions = exp.quantifiedVars.mapNotNull { builderState[it] } + body
        val evald = EvaldCVLExp(CVLReportLabel.Exp(cvlExp), null, exp, subExpressions)
        return evald
    }

    private fun inlinedExpStep(exp: TACExpr, cvlExp: CVLExp): EvaldCVLExp {
        return when (exp) {
            is TACExpr.Sym -> {
                EvaldCVLExp(CVLReportLabel.Exp(cvlExp), null, exp, emptyList())
            }
            is TACExpr.QuantifiedFormula -> {
                if (exp.isForall && cvlExp is CVLExp.QuantifierExp) {
                    quantifiedFormulaStep(exp, cvlExp)
                } else {
                    logger.info { "Incompatible TACExpr $exp and CVLExp $cvlExp" }
                    EvaldCVLExp(CVLReportLabel.Exp(cvlExp), null, exp, emptyList())
                }
            }
            is TACExpr.BinExp -> {
                if (cvlExp is CVLExp.BinaryExp || cvlExp is CVLExp.RelopExp) {
                    val leftExp = exp.o1
                    val rightExp = exp.o2

                    val (leftCvlExp, rightCvlExp) = when (cvlExp) {
                        is CVLExp.BinaryExp -> cvlExp.l to cvlExp.r
                        is CVLExp.RelopExp -> cvlExp.l to cvlExp.r
                        else -> `impossible!`
                    }

                    val leftEvald = inlinedExpStep(leftExp, leftCvlExp)
                    val rightEvald = inlinedExpStep(rightExp, rightCvlExp)
                    EvaldCVLExp(CVLReportLabel.Exp(cvlExp), null, exp, listOf(leftEvald, rightEvald))
                } else {
                    logger.info { "Incompatible TACExpr $exp and CVLExp $cvlExp" }
                    EvaldCVLExp(CVLReportLabel.Exp(cvlExp), null, exp, emptyList())
                }
            }
            is TACExpr.Apply.Binary,
            is TACExpr.Apply.Nary,
            is TACExpr.Apply.Unary,
            is TACExpr.LongStore,
            is TACExpr.MapDefinition,
            is TACExpr.MultiDimStore,
            is TACExpr.Select,
            is TACExpr.SimpleHash,
            is TACExpr.Store,
            is TACExpr.StructAccess,
            is TACExpr.StructConstant,
            is TACExpr.TernaryExp.AddMod,
            is TACExpr.TernaryExp.Ite,
            is TACExpr.TernaryExp.MulMod,
            is TACExpr.UnaryExp.BWNot,
            is TACExpr.UnaryExp.LNot,
            is TACExpr.AnnotationExp<*>,
            is TACExpr.Unconstrained -> {
                logger.info { "Unsupported TACExpr $exp and CVLExp $cvlExp" }
                EvaldCVLExp(CVLReportLabel.Exp(cvlExp), null, exp, emptyList())
            }
        }
    }

    private fun subExpressionsForExp(exp: TACExpr, meta: CVLExpToTACExprMeta): List<EvaldCVLExp> {

        val associativeExpId = meta.exp.toAssociativeExpId()
        return meta.getOperands().flatMapIndexed { operandIdx: Int, operand: CVLExpToTACExprMeta.Operand ->
            val evaldOfOperand = evaldCVlExpOfOrNull(operand)

            if (evaldOfOperand != null && evaldOfOperand.matchesOperandLabel(operand)) {
                getSubExpressionsIfAssociative(evaldOfOperand, operand.exp.toAssociativeExpId(), associativeExpId, operandIdx)
            } else {
                // The [EvaldCVLExp] of [operand] is not in [builderState].
                // In this case, we Simply create a new [EvaldCVLExp] for [operand]
                listOf(EvaldCVLExp(
                    expLabel = CVLReportLabel.Exp(operand.exp),
                    formattedValue = if (operand.isCVLConstExp) {
                        // Do not show a "return value" (i.e., evaluation) for
                        // literal/constant expressions in the calltrace
                        null
                    } else {
                        model.valueAsTACValue(operand.out)
                    },
                    tacExpr = exp,
                    subExpressions = emptyList()
                ).apply {
                    // [operand] is neither variable nor const (so it's a non-terminal expression),
                    // but it's not already in [buildResult].
                    if (!(operand.isCVLVariableExp || operand.isCVLConstExp)) {
                        logger.warn {  // TODO: CERT-2333
                            /* Eyal H: one case (not the only one) in which this happens, is when there is an [AssignExpCmd]
                            where the rhs represent a read from a struct field. This is because we first compile [FieldSelectExp]
                            in the CVLExpressionCompiler, which basically creates a [VariableExp] representing the struct, and only
                            later, in the CVLToSimpleCompiler, this struct variable is flattened to its fields, by creating [VariableExp]
                            for each field in the struct. When this is done, it is not possible to update the [CVL_Exp] meta inside the
                            [AssignExpCmd] properly (we can do an ugly best effort). I don't know why we do CVL compilation and then
                            CVLToSimple compilation in two steps, but it is hard to maintain the meta that way */
                            /* SG: there's more to it.
                            It happens on compilation of arg-passing from Sol to CVL exp summaries, that uses StructConstants.
                            I believe the impact here is actually small because we have other ways to construct the expression tree.
                             */
                            "Got a non-constant, non-variable operand ($operand) that is not in the builderState." +
                                " Failed to construct its complete syntax-tree"
                        }
                    }
                }
                )
            }
        }
    }

    /**
     * Create [EvaldCVLExp] for simple expression (a.k.a. not quantified formula).
     * [sym] the symbol to use as key in [builderState].
     * [modelSym] the symbol in the model. Usually equal to [sym], except for skolemized variables.
     */
    private fun step(
        sym: TACSymbol.Var,
        modelSym: TACSymbol.Var,
        exp: TACExpr,
        label: CVLReportLabel.Exp,
        subExpressions: List<EvaldCVLExp>
    ) {
        evaldCVlExpOfOrNull(sym)?.let { prevEvaldExp ->
            throw IllegalStateException("Was about to overwrite the EvaldCVLExp ($prevEvaldExp) of the symbol $sym")
        }

        val evaldExp = EvaldCVLExp(
            expLabel = label,
            formattedValue = model.valueAsTACValue(modelSym),
            tacExpr = exp,
            subExpressions = subExpressions
        )

        builderState[sym] = evaldExp
    }

    private fun isCVLExpOutVar(v: TACSymbol.Var): Boolean = v in builderState

    private fun evaldCVlExpOfOrNull(operand: CVLExpToTACExprMeta.Operand): EvaldCVLExp? {
        val outVar = operand.out as? TACSymbol.Var ?: return null
        return evaldCVlExpOfOrNull(outVar)
    }

    /** checks that the syntax of this [EvaldCVLExp] matches that of the label of [operand] */
    private fun EvaldCVLExp.matchesOperandLabel(operand: CVLExpToTACExprMeta.Operand): Boolean {
        val expFromLabel = expLabel.tryAs<CVLReportLabel.Exp>()?.exp
        return expFromLabel == operand.exp
    }

    private fun evaldCVlExpOfOrNull(outVar: TACSymbol.Var): EvaldCVLExp? = builderState[outVar]

    private fun evaldCVlExpOf(outVar: TACSymbol.Var): EvaldCVLExp = builderState.getValue(outVar)

}
