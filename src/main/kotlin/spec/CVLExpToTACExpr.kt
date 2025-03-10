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

import analysis.TACExprWithRequiredCmdsAndDecls
import config.Config
import datastructures.stdcollections.*
import evm.DEFAULT_SIGHASH_SIZE
import spec.CVLExpToTACExpr.InternalResult.Standard
import spec.CVLExpToTACExpr.InternalResult.WithSummary
import spec.cvlast.*
import spec.cvlast.StorageComparisonChecker.isStorageOperand
import spec.cvlast.transformations.GhostTypeRewriter
import tac.MetaKey
import tac.Tag
import utils.*
import vc.data.*
import vc.data.ParametricInstantiation.Companion.bind
import vc.data.ParametricInstantiation.Companion.flatten
import vc.data.ParametricInstantiation.Companion.merge
import vc.data.ParametricInstantiation.Companion.toSimple
import vc.data.TACMeta.CVL_DISPLAY_NAME
import vc.data.compilation.storage.InternalCVLCompilationAPI
import vc.data.tacexprutil.TACExprFactLogicalAndSelectSimplification
import vc.data.tacexprutil.TACExprFreeVarsCollector
import vc.summary.ComputeTACSummaryProjectAndPrune
import vc.summary.SummarizeProgram
import vc.summary.TACSummaryVar
import java.math.BigInteger

/** to switch on/off simplifications, just replace the factory here */
private val tacExprFactory = TACExprFactLogicalAndSelectSimplification

val QUANTIFIED_VAR_TYPE = MetaKey<CVLType.PureCVLType>("quantified.var.type")

/**
 * @param cvlCompiler currently has two uses:
 *  - needed when an invoke expression needs to be converted (then it is needed for obtaining the body of the invoked method)
 *  - needed when a FunctionIdExp needs to be converted (then it is used for getting access to the symbol table which
 *    holds the necessary [MethodInfo] where the values can be looked up)
 */
@OptIn(InternalCVLCompilationAPI::class)
class CVLExpToTACExpr(private val cvlCompiler: CVLCompiler) {


    /**
     * For internal compilation results within this class.
     * The base case is [Standard], which just contains a [TACExpr].
     * The other case is [WithSummary], which, along with the usual [TACExpr] contains a formula that summarizes a call
     * to a contract function that happens in a subexpression of the currently compiled expression.
     * This summary needs to be moved "up" to the Boolean level of the formula where it can be integrated into the
     *  resulting [TACExpr].
     *
     *  Example:
     *  Let m be a field in the contract of map type.
     *  We translate the expression <code>m(i) + m(j) > 0</code> (m(1)/m(2) begin a call to the getter of m) as follows:
     *    exists aux1, aux2. sum-m[i, aux1] /\ sum-m[j, aux2] /\ aux1 + aux2 > 0
     *  Where sum-m is the summary of m's getter (a formula that relates the getter-input i, or j resp., to the output,
     *   aux1, or aux2 resp..
     *
     */
    private sealed interface InternalResult {
        val exp: TACExpr

        /** new auxiliary variables that were created during compilation of [exp]; they will need to be registered in
         * the type scope of the compiled tac program, or be quantified if we're in a quantified context (don't confuse
         * with `WithSummary.connectingAuxVars`). */
        val newDecls: Set<TACSymbol.Var>

        val newCmds: List<TACCmd.Spec>

        val asCVLExpToTacExprResult get() = Result(exp, newDecls, newCmds)

        fun getVarsToQuantify(): Set<TACSymbol.Var> =
            when (this) {
                is Standard -> setOf()
                is WithSummary -> connectingAuxVars
            }

        fun getConstraintFormula(): TACExpr =
            when (this) {
                is Standard -> TACSymbol.True.asSym()
                is WithSummary -> formula
            }

        fun getCombinedConstraint(other: InternalResult, combineExps: (TACExpr, TACExpr) -> TACExpr): TACExpr {
            check(!this.getVarsToQuantify().containsAny(other.getVarsToQuantify())) { "name clash!" }
            return tacExprFactory {
                exists((this@InternalResult.getVarsToQuantify() + other.getVarsToQuantify()).toList()) {
                    and(
                        this@InternalResult.getConstraintFormula(),
                        other.getConstraintFormula(),
                        combineExps(this@InternalResult.exp, other.exp)
                    )
                }
            }
        }


        /** Base case, only contains a [TACExpr]. */
        @JvmInline
        value class Standard(private val te: TACExprWithRequiredCmdsAndDecls<TACCmd.Spec>) : InternalResult {
            override val exp: TACExpr
                get() = te.exp
            override val newDecls: Set<TACSymbol.Var>
                get() = te.declsToAdd
            override val newCmds: List<TACCmd.Spec>
                get() = te.cmdsToAdd

            companion object {
                operator fun invoke(
                    exp: TACExpr,
                    newDecls: Set<TACSymbol.Var> = setOf(),
                    newCmds: List<TACCmd.Spec> = listOf(),
                ): Standard = Standard(TACExprWithRequiredCmdsAndDecls(exp, newDecls, newCmds))
            }
        }

        /** Case when there was a contract function call inside a subexpression and we're not on Boolean level yet.
         * In addition to the usual [TACExpr] also contains the summary's formula and information how connect it with
         * the normal result once we're on Boolean level. See also documentation of [InternalResult].
         *
         *  @param connectingAuxVars auxVars that connect exp and formula
         */
        data class WithSummary(
            private val tacExprWith: TACExprWithRequiredCmdsAndDecls<TACCmd.Spec>,
            val formula: TACExpr,
            val connectingAuxVars: Set<TACSymbol.Var>
        ) : InternalResult {
            override val exp: TACExpr
                get() = tacExprWith.exp
            override val newDecls: Set<TACSymbol.Var>
                get() = tacExprWith.declsToAdd
            override val newCmds: List<TACCmd.Spec>
                get() = tacExprWith.cmdsToAdd

            companion object {
                operator fun invoke(
                    exp: TACExpr,
                    newDecls: Set<TACSymbol.Var> = setOf(),
                    newCmds: List<TACCmd.Spec> = listOf(),
                    formula: TACExpr,
                    connectingAuxVars: Set<TACSymbol.Var>,
                ): WithSummary =
                    WithSummary(
                        TACExprWithRequiredCmdsAndDecls(exp, newDecls, newCmds),
                        formula,
                        connectingAuxVars,
                    )
            }
        }

        companion object {
           fun combineToBoolean(
                l: ParametricInstantiation<InternalResult>,
                r: ParametricInstantiation<InternalResult>,
                combineExps: (TACExpr, TACExpr) -> TACExpr
            ): ParametricInstantiation<Standard> =
                l.bind { lE: InternalResult ->
                    r.transformCode { rE: InternalResult ->
                        Standard(
                            lE.getCombinedConstraint(rE, combineExps),
                            newDecls = lE.newDecls + rE.newDecls,
                            newCmds = lE.newCmds + rE.newCmds,
                        )
                    }
                }
        }

    }

    private fun InternalResult.lift() = this.toSimple()

    /** Result of compiling a CVLExp to a TACExpr, consists of:
     *  - the TACExpr [exp]
     *  - auxiliary variables [newDecls], the caller must register these in the type scope
     */
    data class Result(val exp: TACExpr, val newDecls: Set<TACSymbol.Var>, val newCmds: List<TACCmd.Spec>)

    /** For uses when we want to translate a simple [CVLExp] (no invokes) to a [TACExpr].
     * Current use case: Translate a [CVLExp.FieldSelectExp] to a [TACExpr.StructAccess]. */
    fun compileToSimpleTacExpr(
        e: CVLExp,
        allocatedTACSymbols: TACSymbolAllocation,
        env: CVLCompiler.CompilationEnvironment
    ): ParametricInstantiation<Result> =
        compileToTacExpr(e, allocatedTACSymbols, env).transformCode { compiled ->
            when (compiled) {
                is Standard -> compiled.asCVLExpToTacExprResult
                is WithSummary -> throw IllegalArgumentException(
                    "translation of $e produced a summary; use other " +
                        "method (inside CVLExpToTACExpr) for that "
                )
            }
        }

    fun compileToBooleanTacExpr(
        e: CVLExp,
        allocatedTACSymbols: TACSymbolAllocation,
        env: CVLCompiler.CompilationEnvironment
    ): ParametricInstantiation<Result> =
        compileToTacExpr(e, allocatedTACSymbols, env).transformCode { compiled ->
            when (compiled) {
                is Standard -> compiled.asCVLExpToTacExprResult
                is WithSummary -> constructSummaryAndExpFormula(compiled)
            }
        }

    /**
     * Applies the [TACBuiltInFunction.TwosComplement.Wrap] on the TAC expression in [compiled].
     */
    private fun wrapExpr(compiled: InternalResult): InternalResult {
        val wrapped = cvlCompiler.exprFact.Apply(TACBuiltInFunction.TwosComplement.Wrap(Tag.Bit256).toTACFunctionSym(),  listOf(compiled.exp))
        return when (compiled) {
            is Standard -> Standard(wrapped)
            is WithSummary -> WithSummary(
                exp = wrapped,
                newDecls = compiled.newDecls,
                newCmds = compiled.newCmds,
                formula = compiled.formula,
                connectingAuxVars = compiled.connectingAuxVars)
        }
    }
    private fun constructSummaryAndExpFormula(compiled: WithSummary): Result {
        return Result(
                makeQuantifiedFormula(
                    false,
                    compiled.connectingAuxVars.toList(),
                    cvlCompiler.exprFact.LAnd(compiled.exp, compiled.formula),
                    compiled.newDecls,
                ),
            setOf(),
            compiled.newCmds,
        )
    }

    private fun makeQuantifiedFormula(
        isForall: Boolean,
        quantifiedVariables: List<TACSymbol.Var>,
        body: TACExpr,
        newDecls: Set<TACSymbol.Var>,
    ) = TACExprFactUntyped.QuantifiedFormula(
        isForall,
        quantifiedVariables,
        TACExprFactUntyped.QuantifiedFormula(
            false,
            newDecls.toList(),
            body
        ),
    )

    private fun compileToTacExpr(
        e: CVLExp,
        allocatedTACSymbols: TACSymbolAllocation,
        env: CVLCompiler.CompilationEnvironment
    ): ParametricInstantiation<InternalResult> {
        return when (e) {
            is CVLExp.Constant.BoolLit ->
                Standard(TACSymbol.Const(e.b, Tag.Bool).asSym()).lift()
            is CVLExp.VariableExp -> {
                val type = e.getCVLType()
                when (type) {
                    is CVLType.VM -> {
                        val pureType = e.getOrInferPureCVLType()
                        val (_, tacSymbol) = allocatedTACSymbols.generateTransientUniqueCVLParam(cvlCompiler.cvl.symbolTable, e.id, pureType)
                        val commands = listOf(
                            TACCmd.CVL.AssignVMParam(tacSymbol, pureType, e.id, type)
                        )
                        Standard.invoke(tacSymbol.asSym(), newDecls = setOf(), newCmds = commands).lift()
                    }
                    is CVLType.PureCVLType -> {
                        val ret = CVLKeywords.toValue(e.id)?.let {
                    Standard(TACSymbol.lift(it).asSym())
                        } ?: e.tag.getCVLTypeOrNull()?.let { cvlTypeWithEnums ->
                            // TODO: I think I would like to guarantee these types are Pure CVL Types
                            val oldExpectedTag = (cvlTypeWithEnums as? CVLType.PureCVLType)?.toTag()
                            val v = allocatedTACSymbols.get(e.id)
                            check(v.tag == oldExpectedTag) { "Internal invariant broken" }
                            Standard(cvlCompiler.exprFact.Sym(v))
                        } ?: Standard(allocatedTACSymbols.get(e.id).asSym())
                        ret.lift()
                    }
                }
            }

            is CVLExp.BinaryExp.AddExp -> combineSummariesNonBooleanExp(
                compileToTacExpr(e.l, allocatedTACSymbols, env),
                compileToTacExpr(e.r, allocatedTACSymbols, env),
                cvlCompiler.exprFact::IntAdd
            )


            is CVLExp.BinaryExp.DivExp -> combineSummariesNonBooleanExp(
                compileToTacExpr(e.l, allocatedTACSymbols, env),
                compileToTacExpr(e.r, allocatedTACSymbols, env),
                cvlCompiler.exprFact::IntDiv
            )


            is CVLExp.BinaryExp.ModExp -> combineSummariesNonBooleanExp(
                compileToTacExpr(e.l, allocatedTACSymbols, env),
                compileToTacExpr(e.r, allocatedTACSymbols, env),
                cvlCompiler.exprFact::IntMod
            )

            is CVLExp.BinaryExp.ExponentExp -> combineSummariesNonBooleanExp(
                compileToTacExpr(e.l, allocatedTACSymbols, env),
                compileToTacExpr(e.r, allocatedTACSymbols, env),
                cvlCompiler.exprFact::IntExponent
            )

            is CVLExp.ArrayLitExp -> throw UnsupportedOperationException("Cannot compile ArrayExp here to TAC")
            is CVLExp.BinaryExp.IffExp -> {
                // if e.l or e.r contains a quantified formula, compiling it to TACExpr and using it in both parts of the Lor expression
                // results in double definition of the quantified variables.
                // to prevent this, we create the extended cvl expression and compile it.
                // we don't use Eq expression (e.l == e.r) because it has double polarity so using it may prevent further optimizations.
                val bothTrue = CVLExp.BinaryExp.LandExp(e.l, e.r, e.tag)
                val bothFalse = CVLExp.BinaryExp.LandExp(CVLExp.UnaryExp.LNotExp(e.l, e.l.tag), CVLExp.UnaryExp.LNotExp(e.r, e.r.tag), e.tag)
                val lor = CVLExp.BinaryExp.LorExp(bothTrue, bothFalse, e.tag)
                compileToTacExpr(lor, allocatedTACSymbols, env)
            }
            is CVLExp.BinaryExp.ImpliesExp -> InternalResult.combineToBoolean(
                compileToTacExpr(e.l, allocatedTACSymbols, env),
                compileToTacExpr(e.r, allocatedTACSymbols, env)
            ) { op1, op2 -> cvlCompiler.exprFact.LOr(TACExpr.UnaryExp.LNot(op1), op2) }

            is CVLExp.ApplyExp.ContractFunction -> {
                check(e.noRevert) { "must use sinvoke rather than invoke inside quantifiers (function invocation must be side effect-free)" }

                val resultVarName = "certFunRes"

                val returnType = e.getOrInferPureCVLType()

                if (returnType == CVLType.PureCVLType.Void) {
                    throw BadSpecException("Invoke of a void function inside a quantified expression: $e")
                }

                // it is okay not to task the allocator with generating a name here since resultVar will be
                // quantified over (during sumamry generation)
                val (resultVarCVL, resultVarTAC) = allocatedTACSymbols.generateTransientUniqueCVLParam(
                    cvlCompiler.cvl.symbolTable,
                    resultVarName,
                    returnType
                )

                getTACCodeForFunction(e,  resultVarCVL, allocatedTACSymbols, env).transformCode { calledProg ->
                    val tacCommandGraph = calledProg.transformToCore(cvlCompiler.scene).analysisCache.graph

                    // add built in function to the symbolTable
                    // TODO: if there's any way to get rid of this... I don't like modifying the global symbol
                    //       table
                    cvlCompiler.tacSymbolTable = cvlCompiler.tacSymbolTable.merge(calledProg.symbolTable)
                    val rawSummary = try {
                        SummarizeProgram.summarizeTAC(
                            tacCommandGraph,
                            ComputeTACSummaryProjectAndPrune(
                                setOf(resultVarTAC),
                                tacCommandGraph.sinks,
                                ComputeTACSummaryProjectAndPrune.Settings.Default
                            )
                        )
                    } catch (e: Exception) {
                        throw CertoraException(
                            CertoraErrorType.TRANSFORMULA_SUMMARIES,
                            "Encountered a problem with computing a formula representation (aka. a summary) for the " +
                                "function call $e.\n" +
                                "If $e does not depend on any quantified variable or ghost function/constant, you " +
                                "can try to work around this problem by moving the function call outside of the " +
                                "quantifier/ghost by assigning it to a fresh variable beforehand (e.g. " +
                                "`require forall x. foo(y)` would become `z = foo(y); require forall x. z`).",
                            e
                        )
                    }

                    val rawSumSimplified = tacExprFactory.rebuildExpr(rawSummary.tfSum.transFormula.exp)

                    val summaryFormulaAuxVarsQuantified = tacExprFactory.QuantifiedFormula(
                        false,
                        rawSummary.tfSum.transFormula.auxVars.map { it.s },
                        rawSumSimplified,
                        null
                    )

                    /** The variable in the summary formula that corresponds to the function call's result*/
                    val resultVarInstanceInSummary: TACSymbol.Var =
                        rawSummary.tfSum.transFormula.outVars[TACSummaryVar(resultVarTAC)]?.s
                            ?: error("result var should occur in outVars")
                    if (resultVarInstanceInSummary !in TACExprFreeVarsCollector.getFreeVars(rawSumSimplified))
                        throw CertoraInternalException(
                            CertoraInternalErrorType.TRANSFORMULA_SUMMARIES,
                            "the result var needs to occur in the summary in the version (ssa-renaming..) that we expect " +
                                "(but doesn't)"
                        )

                    /* Quantify all left-over variables -- typically, these are the local variables of the summarized method */
                    val varsToExistentiallyQuantify: List<TACSymbol.Var> =
                        rawSummary.tfSum.transFormula.auxVars.map { it.s }

                    val resultFormula =
                        tacExprFactory {
                            exists(varsToExistentiallyQuantify) {
                                summaryFormulaAuxVarsQuantified
                            }
                        }

                    /*
                     * Let foo be the invoked function.
                     * Let sum[certoraOutVar0] be the summary of foo's function body.
                     * Let psi is the smallest boolean subformula encasing this invokeExpression
                     *
                     * Then, the result of this should be roughly like this:
                     *  (exists certoraOutVar0. (and sum[certoraOutVar0] psi[certoraOutVar0]))
                     *
                     * Furthermore, note that the global variables occurring in sum are free (in addition to
                     *  certoraOutVar0), while the rest (local variables, auxiliary variables) are existentially quantified.
                     */
                    WithSummary(
                        exp = resultVarInstanceInSummary.asSym(),
                        formula = resultFormula,
                        connectingAuxVars =setOf(resultVarInstanceInSummary),
                    )
                }

            }

            is CVLExp.BinaryExp.LandExp -> InternalResult.combineToBoolean(
                compileToTacExpr(e.l, allocatedTACSymbols, env),
                compileToTacExpr(e.r, allocatedTACSymbols, env),
                cvlCompiler.exprFact::LAnd
            )

            is CVLExp.BinaryExp.LorExp -> InternalResult.combineToBoolean(
                compileToTacExpr(e.l, allocatedTACSymbols, env),
                compileToTacExpr(e.r, allocatedTACSymbols, env),
                cvlCompiler.exprFact::LOr
            )

            is CVLExp.BinaryExp.MulExp -> combineSummariesNonBooleanExp(
                compileToTacExpr(e.l, allocatedTACSymbols, env),
                compileToTacExpr(e.r, allocatedTACSymbols, env),
                cvlCompiler.exprFact::IntMul
            )


            is CVLExp.UnaryExp.LNotExp -> {
                compileToTacExpr(e.e, allocatedTACSymbols, env).transformCode { compiled ->
                    val unaryOp = TACExpr.UnaryExp::LNot
                    when (compiled) {
                        is Standard -> Standard(
                            unaryOp(compiled.exp, null),
                            compiled.newDecls,
                            compiled.newCmds
                        )
                        is WithSummary -> Standard(
                            exp = unaryOp(
                                    makeQuantifiedFormula(
                                        false,
                                        compiled.getVarsToQuantify().toList(),
                                        cvlCompiler.exprFact.LAnd(
                                            compiled.getConstraintFormula(),
                                            compiled.exp
                                        ),
                                        compiled.newDecls,
                                    ),
                                    null
                                ),
                            newCmds = compiled.newCmds,
                        )
                    }
                }
            }

            is CVLExp.Constant.NumberLit -> Standard(TACSymbol.Const(e.n).asSym()).lift()
            is CVLExp.QuantifierExp -> {
                val bodyAllocatedTACSymbols = allocatedTACSymbols.shadow(e)
                compileToTacExpr(e.body, bodyAllocatedTACSymbols, env).transformCode { compiled ->
                check(compiled.newCmds.filterNot { cmd -> cmd is TACCmd.CVL.AssignVMParam }.isEmpty()) { "attempting to add an assume/assert inside a quantifier scope " +
                        "-- we'll need to revise this (cmds: \"${compiled.newCmds}\")" }

                    val unaryOp = { body: TACExpr ->
                        makeQuantifiedFormula(
                            e.isForall,
                            listOf(bodyAllocatedTACSymbols.get(e.qVarName, e.qVarType.toTag()).let {
                                it.copy(
                                    meta = it.meta + (QUANTIFIED_VAR_TYPE to e.qVarType) + (CVL_DISPLAY_NAME to e.originalName)
                                )
                            }),
                            body,
                            compiled.newDecls,
                        )

                    }
                    when (compiled) {
                    is Standard -> Standard(unaryOp(compiled.exp), newCmds = compiled.newCmds, newDecls = compiled.newDecls)
                        is WithSummary -> Standard(
                            unaryOp(
                                makeQuantifiedFormula(
                                isForall = false,
                                quantifiedVariables = compiled.getVarsToQuantify().toList(),
                                    body = cvlCompiler.exprFact.LAnd(
                                        compiled.getConstraintFormula(),
                                        compiled.exp
                                    ),
                                newDecls = setOf(), // newAuxVars are dealt with in unaryOp
                                )
                            ),
                            newDecls = compiled.newDecls,
                            newCmds = compiled.newCmds

                        )

                    }
                }
            }
            is CVLExp.SumExp -> { throw UnsupportedOperationException("Don't support sums here yet")}
            is CVLExp.RelopExp -> {
                if(e.r.isStorageOperand() || e.l.isStorageOperand()) {
                    // I mean, we totally could, but no one wants to deal with that, amirite
                    throw UnsupportedOperationException("Cannot compare storage within quantifiers")
                }
                when (e.relop) {
                    // TODO what about the TACExpr.IntGe .. etc methods -- when to use them?
                    CVLExp.RelopExp.CVLERelop.EQ -> InternalResult.combineToBoolean(
                        compileToTacExpr(e.l, allocatedTACSymbols, env),
                        compileToTacExpr(e.r, allocatedTACSymbols, env),
                        cvlCompiler.exprFact::Eq
                    )

                    CVLExp.RelopExp.CVLERelop.NE -> InternalResult.combineToBoolean(
                        compileToTacExpr(e.l, allocatedTACSymbols, env),
                        compileToTacExpr(e.r, allocatedTACSymbols, env)
                    ) { e1: TACExpr, e2: TACExpr ->
                        TACExpr.UnaryExp.LNot(
                            cvlCompiler.exprFact.Eq(
                                e1,
                                e2
                            )
                        )
                    }

                    CVLExp.RelopExp.CVLERelop.LT -> InternalResult.combineToBoolean(
                        compileToTacExpr(e.l, allocatedTACSymbols, env),
                        compileToTacExpr(e.r, allocatedTACSymbols, env),
                        cvlCompiler.exprFact::Lt
                    )

                    CVLExp.RelopExp.CVLERelop.GT -> InternalResult.combineToBoolean(
                        compileToTacExpr(e.l, allocatedTACSymbols, env),
                        compileToTacExpr(e.r, allocatedTACSymbols, env),
                        cvlCompiler.exprFact::Gt
                    )

                    CVLExp.RelopExp.CVLERelop.GE -> InternalResult.combineToBoolean(
                        compileToTacExpr(e.l, allocatedTACSymbols, env),
                        compileToTacExpr(e.r, allocatedTACSymbols, env),
                        cvlCompiler.exprFact::Ge
                    )

                    CVLExp.RelopExp.CVLERelop.LE -> InternalResult.combineToBoolean(
                        compileToTacExpr(e.l, allocatedTACSymbols, env),
                        compileToTacExpr(e.r, allocatedTACSymbols, env),
                        cvlCompiler.exprFact::Le
                    )
                }
            }

            is CVLExp.FieldSelectExp -> {
                val t = e.getOrInferPureCVLType()
                val structExp = e.structExp
                if (t is CVLType.PureCVLType.Enum &&
                    structExp.getPureCVLType() is CVLType.PureCVLType.Enum &&
                    structExp is CVLExp.FieldSelectExp &&
                    structExp.structExp.getPureCVLType() is CVLType.PureCVLType.Primitive.CodeContract) {
                    // <contract name>.<enum name>.<enum member>--as enforced by type checker should be only way to
                    // represent enum literals
                    // from readthedocs
                    // The data representation is the same as for enums in C: The options are represented by
                    // subsequent unsigned integer values starting from 0.
                    val value = t.elements.indexOf(e.fieldName)
                    check(value != -1) { "type checker promised $t contained ${e.fieldName} but it didn't"}
                    ParametricInstantiation.getSimple(
                        Standard(cvlCompiler.exprFact.Sym(TACSymbol.Const(value.toBigInteger(), t.toTag())), setOf(), emptyList())
                    )
                } else {
                    compileToTacExpr(structExp, allocatedTACSymbols, env).transformCode { structCompiled ->
                        val exp = cvlCompiler.exprFact.StructAccess(structCompiled.exp, e.fieldName)
                        when (structCompiled) {
                    is Standard -> Standard(exp, structCompiled.newDecls, newCmds = structCompiled.newCmds)
                            is WithSummary -> WithSummary(
                        exp = exp,
                        newDecls = structCompiled.newDecls,
                        newCmds = structCompiled.newCmds,
                        formula = structCompiled.formula,
                        connectingAuxVars = structCompiled.connectingAuxVars,
                            )
                        }
                    }
                }

            }
            is CVLExp.SetMemExp -> {
                throw UnsupportedOperationException("No compilation to TAC of setmem expression ${e}")
            }

            is CVLExp.ArrayDerefExp -> when (val ty = e.array.getOrInferPureCVLType()) {
                is CVLType.PureCVLType.Ghost.Mapping -> combineSummariesNonBooleanExp(
                    compileToTacExpr(e.array, allocatedTACSymbols, env),
                    compileGhostParameter(e.index, 0, allocatedTACSymbols, ty.key, env),
                    cvlCompiler.exprFact::Select
                )

                else -> throw CertoraInternalException(
                    CertoraInternalErrorType.CVL_COMPILER,
                    "No compilation to TAC of Array deref expression ${e}"
                )
            }

            is CVLExp.BinaryExp.SubExp -> combineSummariesNonBooleanExp(
                compileToTacExpr(e.l, allocatedTACSymbols, env),
                compileToTacExpr(e.r, allocatedTACSymbols, env),
                cvlCompiler.exprFact::IntSub
            )
            is CVLExp.Constant.SignatureLiteralExp -> {
                val struct = e.tag.annotation as CVLExp.Constant.StructLit
                compileToTacExpr(struct, allocatedTACSymbols, env)

            }

            is CVLExp.ApplyExp.Definition -> error(
                "Definition application $e should already have been expanded before " +
                    "compiling to TAC"
            )

            is CVLExp.ApplyExp.Ghost -> {
                GhostCompilation.handleGhostApplication(
                    cvlCompiler,
                    exp = e,
                    singleton = {
                        Standard(it.asSym(), setOf(), listOf()).lift()
                    }
                ) { base, args, paramTypes ->
                    args.mapIndexed { ind, it ->
                        compileGhostParameter(it, ind, allocatedTACSymbols, paramTypes[ind], env)
                    }.flatten().bind { argsCompiled ->
                        val newDecls = argsCompiled.flatMapTo(mutableSetOf()) { it.newDecls }
                        val newCmds = argsCompiled.flatMapTo(mutableListOf()) { it.newCmds }
                        val ghostExp = TACExpr.Select.buildMultiDimSelect(
                            base.asSym(),
                            argsCompiled.map { it.exp }
                        )

                        if (e.tag.getCVLTypeOrNull() == CVLType.PureCVLType.Primitive.Bool) {
                            if (argsCompiled.any { it is WithSummary }) {
                                val constraintFormula =
                                    makeQuantifiedFormula(
                                        false,
                                        argsCompiled.flatMapTo(mutableSetOf()) { it.getVarsToQuantify() }.toList(),
                                        tacExprFactory.LAnd(argsCompiled.map { it.getConstraintFormula() } + listOf(
                                            ghostExp
                                        ),
                                            null),
                                        newDecls,
                                    )
                            Standard(constraintFormula, setOf(), // vars have been quantified at this point
                                newCmds
                            )
                        } else {
                            Standard(ghostExp, newDecls, newCmds)
                            }
                        } else {
                            if (argsCompiled.any { it is WithSummary }) {
                                WithSummary(
                                exp = ghostExp,
                                newDecls = newDecls,
                                newCmds = newCmds,
                                formula = tacExprFactory.LAnd(argsCompiled.map { it.getConstraintFormula() }, null),
                                connectingAuxVars = argsCompiled.flatMapTo(mutableSetOf()) { it.getVarsToQuantify() },
                                )
                            } else {
                            Standard(ghostExp, newDecls, newCmds)
                            }
                        }.lift()
                    }
                }
            }
            is CVLExp.CastExpr -> when (e.castType) {
                CastType.REQUIRE,
                CastType.ASSERT -> throw CertoraInternalException(CertoraInternalErrorType.CVL_COMPILER, "Can't compile Cast Expression $e to TAC Expression")
                CastType.TO -> when (val toCastType = e.toCastType) {
                            is CVLType.PureCVLType.Primitive.BytesK -> {
                                when (val fromCastType = e.arg.getCVLType()){
                                    is CVLType.PureCVLType.Primitive.UIntK -> {
                                        // left shift by 256-k bytes
                                        val shiftAmount = (Config.VMConfig.registerBitwidth - fromCastType.k).toBigInteger()
                                        val bwLeftShiftExp = CVLExp.BinaryExp.BwLeftShiftExp(e.arg, CVLExp.Constant.NumberLit(shiftAmount,
                                            CVLExpTag(e.getScope(), CVLType.PureCVLType.Primitive.NumberLiteral(shiftAmount), e.getRangeOrEmpty())),
                                            e.tag)
                                        compileToTacExpr(bwLeftShiftExp, allocatedTACSymbols, env)
                                    }
                                    is CVLType.PureCVLType.Primitive.NumberLiteral -> {
                                        Standard(CodeGenUtils.numAsBytesKConst(fromCastType.n, toCastType.bitWidth).asSym()).lift()
                                    }
                                    else -> error("Bad cast type $fromCastType")
                                }
                            }
                    CVLType.PureCVLType.Primitive.Mathint -> compileToTacExpr(e.arg, allocatedTACSymbols, env)
                    else -> throw CertoraInternalException(CertoraInternalErrorType.CVL_COMPILER, "Bad cast type ${e.castType}")
                }
                CastType.CONVERT -> if((e.tag.annotation as BoxingType).isIdentity) {
                    compileToTacExpr(e.arg, allocatedTACSymbols, env)
                } else {
                    this.compileToTacExpr(e.arg, allocatedTACSymbols, env).transformCode { arg ->
                        val sym = arg.exp as? TACExpr.Sym.Var
                            ?: throw java.lang.UnsupportedOperationException("Cannot use value ${e.arg} as a bytes key within a quantifier." +
                                " Try hoisting it to a variable declared outside of the quantiifer")
                        TACExpr.SimpleHash.fromByteMapRange(
                            hashFamily = HashFamily.Keccack,
                            map = sym.s,
                        ).let {
                            Standard(
                                exp = it.exp,
                                newCmds = arg.newCmds + it.cmdsToAdd,
                                newDecls = arg.newDecls + it.declsToAdd
                            )
                        }
                    }
                }

            }
            is CVLExp.ApplyExp.CVLFunction -> throw CertoraInternalException(CertoraInternalErrorType.CVL_COMPILER, "Can't compile $e to TAC Expression")
            is CVLExp.Constant.StructLit -> {
                val newDecls = mutableSetOf<TACSymbol.Var>()
                 val newCmds = mutableListOf<TACCmd.Spec>()
                e.fieldNameToEntry.entries.map { (fieldName, fieldValue) ->
                    compileToTacExpr(fieldValue, allocatedTACSymbols, env).transformCode {
                        newDecls.addAll(it.newDecls)
                        newCmds.addAll(it.newCmds)
                        fieldName to (it as Standard).exp

                    }
                }.flatten().transformCode {
                    Standard(
                        TACExpr.StructConstant(
                            it.toMap(),
                            (e.tag.type as CVLType.PureCVLType.Struct).toTag()
                        ),
                        newDecls,
                        newCmds
                    )
                }
            }
            is CVLExp.Constant.StringLit -> Standard(generateStringExpression(e.s), setOf(), listOf()).lift()
            is CVLExp.UnaryExp.UnaryMinusExp -> compileToTacExpr(
                CVLExp.BinaryExp.SubExp(
                    CVLExp.Constant.NumberLit(
                        BigInteger.ZERO,
                        CVLExpTag.transient(CVLType.PureCVLType.Primitive.NumberLiteral(BigInteger.ZERO))
                    ),
                    e.e,
                    CVLExpTag.transient(CVLType.PureCVLType.Primitive.Mathint)
                ),
                allocatedTACSymbols, env
            )
            is CVLExp.BinaryExp.BwOrExp -> combineSummariesNonBooleanExp(
                compileToTacExpr(e.l, allocatedTACSymbols, env),
                compileToTacExpr(e.r, allocatedTACSymbols, env),
                cvlCompiler.exprFact::BWOr
            )

            is CVLExp.BinaryExp.BwXOrExp -> combineSummariesNonBooleanExp(
                compileToTacExpr(e.l, allocatedTACSymbols, env),
                compileToTacExpr(e.r, allocatedTACSymbols, env),
                cvlCompiler.exprFact::BWXOr
            )

            is CVLExp.BinaryExp.BwAndExp -> combineSummariesNonBooleanExp(
                compileToTacExpr(e.l, allocatedTACSymbols, env),
                compileToTacExpr(e.r, allocatedTACSymbols, env),
                cvlCompiler.exprFact::BWAnd
            )

            is CVLExp.BinaryExp.BwLeftShiftExp -> {

                combineSummariesNonBooleanExp(
                    compileToTacExpr(e.l, allocatedTACSymbols, env).transformCode(::wrapExpr),
                    compileToTacExpr(e.r, allocatedTACSymbols, env),
                    cvlCompiler.exprFact::ShiftLeft
                )
            }

            is CVLExp.BinaryExp.BwRightShiftExp -> combineSummariesNonBooleanExp(
                compileToTacExpr(e.l, allocatedTACSymbols, env).transformCode(::wrapExpr),
                compileToTacExpr(e.r, allocatedTACSymbols, env),
                cvlCompiler.exprFact::ShiftRightArithmetical
            )

            is CVLExp.BinaryExp.BwRightShiftWithZerosExp -> combineSummariesNonBooleanExp(
                compileToTacExpr(e.l, allocatedTACSymbols, env).transformCode(::wrapExpr),
                compileToTacExpr(e.r, allocatedTACSymbols, env),
                cvlCompiler.exprFact::ShiftRightLogical
            )

            is CVLExp.UnaryExp.BwNotExp -> {
                throw UnsupportedOperationException("Can't compile ${e} to TAC")
            }

            is CVLExp.CondExp -> {
                val c_ = compileToTacExpr(e.c, allocatedTACSymbols, env)
                val e1_ = compileToTacExpr(e.e1, allocatedTACSymbols, env)
                val e2_ = compileToTacExpr(e.e2, allocatedTACSymbols, env)
                val newDecls = mutableSetOf<TACSymbol.Var>().also { s ->
                    c_.transformCode { s.addAll(it.newDecls) }
                    e1_.transformCode { s.addAll(it.newDecls) }
                    e2_.transformCode { s.addAll(it.newDecls) }
                }
                val newCmds = mutableListOf<TACCmd.Spec>().also { cmds ->
                    c_.transformCode { cmds.addAll(it.newCmds) }
                    e1_.transformCode { cmds.addAll(it.newCmds) }
                    e2_.transformCode { cmds.addAll(it.newCmds) }
                }

                c_.merge(e1_, e2_) { c, e1, e2 ->
                    val exp = cvlCompiler.exprFact.Ite(c.exp, e1.exp, e2.exp)
                    if (c is WithSummary || e1 is WithSummary || e2 is WithSummary) {
                        val varsToQuantify = c.getVarsToQuantify() + e1.getVarsToQuantify() + e2.getVarsToQuantify()
                        val constraintFormula = tacExprFactory.LAnd(
                            listOf(
                                c.getConstraintFormula(),
                                e1.getConstraintFormula(),
                                e2.getConstraintFormula()
                            ),
                            null
                        )

                    WithSummary(exp, newDecls, newCmds, constraintFormula, varsToQuantify)
                    } else {
                    Standard(exp, newDecls, newCmds)
                    }
                }
            }
            is CVLExp.UnresolvedApplyExp -> throw IllegalStateException("All apply expressions must be fully resolved")
            is CVLExp.AddressFunctionCallExp -> throw UnsupportedOperationException("Cannot compile address function calls to expression form (yet)")
            is CVLExp.Constant.EnumConstant -> {
                val en = e.getCVLType() as CVLType.PureCVLType.Enum

                val i = en.elements.indexOf(e.memberName)
                check(i != -1) {
                    "Type checker should have caught this"
                }
                Standard(
                    i.asTACExpr, setOf(), listOf()
                ).lift()
            }

            is CVLExp.ApplyExp.CVLBuiltIn -> throw UnsupportedOperationException("Cannot compile $e to expression form, type checker should have caught this")
        }
    }

    private fun compileGhostParameter(
        it: CVLExp,
        ind: Int,
        allocatedTACSymbols: TACSymbolAllocation,
        ty: CVLType.PureCVLType,
        env: CVLCompiler.CompilationEnvironment
    ): ParametricInstantiation<InternalResult> {
        return if (it.tag.type?.let(GhostTypeRewriter::isByteBlobCandidate) == true) {
            check(ty == CVLType.PureCVLType.Primitive.HashBlob) {
                "Type mismatch: have a bytes key being passed to non-bytesblob position $it $ind"
            }
            val compiled = compileToTacExpr(it, allocatedTACSymbols, env).mapNotNull {
                it as? Standard
            }
            compiled.bind { compiledInternal ->
                val compiledVar = compiledInternal.exp as? TACExpr.Sym.Var
                if (compiledVar != null) {
                val (hashExp, newDecls, newCmds) =
                    TACExpr.SimpleHash.fromByteMapRange(
                        hashFamily = HashFamily.Keccack,
                        compiledVar.s,
                    )
                Standard(hashExp, compiledInternal.newDecls + newDecls, newCmds).lift()
                } else {
                    error("Could not compile bytes key to ghost argument")
                }
            }
        } else {
            compileToTacExpr(it, allocatedTACSymbols, env)
        }
    }


    private fun getTACCodeForFunction(
        e: CVLExp.ApplyExp.ContractFunction,
        resultVar: CVLParam,
        allocatedTACSymbols: TACSymbolAllocation,
        env: CVLCompiler.CompilationEnvironment
    ): ParametricInstantiation<CVLTACProgram> {
        return cvlCompiler.compileContractFunctionInvocation(
            e,
            listOf(resultVar),
            allocatedTACSymbols,
            env = env
        )
    }

    private fun combineSummariesNonBooleanExp(
        l_: ParametricInstantiation<InternalResult>,
        r_: ParametricInstantiation<InternalResult>,
        exprFac: (TACExpr, TACExpr) -> TACExpr
    ): ParametricInstantiation<InternalResult> {
        return l_.bind { l ->
            r_.transformCode { r ->
                val constraintFormula =
                    tacExprFactory.LAnd(l.getConstraintFormula(), r.getConstraintFormula(), null)
                val varsToQuantify = l.getVarsToQuantify() + r.getVarsToQuantify()

                val exp = exprFac(l.exp, r.exp)

                if (l is WithSummary || r is WithSummary) {
                    WithSummary(
                        exp = exp,
                        newDecls = l.newDecls + r.newDecls,
                        newCmds = l.newCmds + r.newCmds,
                        formula = constraintFormula,
                        connectingAuxVars = varsToQuantify
                    )
                } else {
                    check(varsToQuantify.isEmpty() && constraintFormula == TACSymbol.True.asSym())
                    { "Got vars to quantify $varsToQuantify and constraint formula $constraintFormula" }
                  Standard(exp = exp, newDecls = l.newDecls + r.newDecls, newCmds = l.newCmds + r.newCmds)
                }
            }
        }
    }

    /**
     * Generate a [TACExpr] of type [Tag.ByteMap] that represents the string.
     * Build it according to our conventions for string literals, i.e., with a certain prefix.
     * This method is analogous to [generateStringAssignment]. The only difference being that this method returns an
     * expression (essentially a store chain over the empty map) whereas [generateStringAssignment] generates a list of
     * assignments.
     */
    internal fun generateStringExpression(stringConst: String)
        : TACExpr {
        // start with the empty map
        var resultMap: TACExpr = TACExprFactTypeCheckedOnlyPrimitives.resetStore(Tag.ByteMap)

        /* Store the constant prefix, i.e.,
            0=0x0: 0x8c379a000000000000000000000000000000000000000000000000000000000
            4=0x4: 0x20
            36=0x24: len
            68=0x44: string
         */
        resultMap = TACExpr.Store(
            resultMap,
            TACSymbol.Const(BigInteger.ZERO).asSym(),
            TACSymbol.Const(
                "8c379a000000000000000000000000000000000000000000000000000000000".toBigInteger(16)
            ).asSym()
        )
        resultMap = TACExpr.Store(
            resultMap,
            TACSymbol.Const(DEFAULT_SIGHASH_SIZE).asSym(),
            TACSymbol.Const("20".toBigInteger(16)).asSym()
        )
        resultMap = TACExpr.Store(
            resultMap,
            TACSymbol.Const(BigInteger.valueOf(36)).asSym(),
            TACSymbol.Const(stringConst.length.toBigInteger()).asSym()
        )

        // divide string const to portions of 32 bytes each
        for (portionIdx in (0..(stringConst.length / 32))) {
            val locWithPrefixOffset = BigInteger.valueOf(68).plus((portionIdx * 32).toBigInteger())

            val stringPortion = stringConst.substring(
                portionIdx * 32,
                Math.min(stringConst.length, portionIdx * 32 + 32)
            )
            @Suppress("DEPRECATION") val stringChars = stringPortion.map { c -> c.toByte() }
            val padding =
                (0..((32 - stringPortion.length) - 1)).map { 0.toByte() } // take the remaining N, add 2*N zeroes
            val whole = stringChars + padding
            check(whole.size == 32) { "Chunk size should be 32 but got whole $whole (${whole.size}) consisting of chunk $stringChars (${stringChars.size}) + padding $padding (${padding.size})" }
            resultMap = TACExpr.Store(
                resultMap,
                TACSymbol.Const(locWithPrefixOffset).asSym(),
                TACSymbol.Const(
                    whole.map { b -> b.toHexString() }.joinToString("").toBigInteger(
                        16
                    )
                ).asSym()
            )
        }

        return resultMap
    }
}
