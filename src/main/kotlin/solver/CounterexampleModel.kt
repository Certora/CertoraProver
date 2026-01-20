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

package solver

import datastructures.stdcollections.*
import log.*
import report.dumps.extendModel
import rules.RuleCheckResult
import smt.TacExpTermsOfInterest
import smtlibutils.cmdprocessor.SmtFormulaCheckerResult
import smtlibutils.data.SatResult
import smtlibutils.data.SmtExp
import smtlibutils.data.SmtSymbolTable
import smtlibutils.data.Sort
import solver.CounterexampleModel.ResolvingFailure.UnexpectedResultValue.Expected
import utils.Range
import spec.cvlast.CVLType
import spec.rules.IRule
import spec.cvlast.SpecType
import tac.MetaKey
import tac.NBId
import tac.Tag
import utils.*
import vc.data.*
import vc.data.TACBuiltInFunction.*
import vc.data.TACMeta.CVL_TYPE
import vc.data.state.ConcreteTacValue
import vc.data.state.TACValue
import vc.data.tacexprutil.evaluators.TACExprInterpreter
import verifier.AbstractTACChecker
import java.math.BigInteger

private val logger = Logger(LoggerTypes.COUNTEREXAMPLEMODEL)

@KSerializable
sealed class CounterexampleModel: AmbiSerializable {

    /**
     * Possible failures while trying to resolve [SmtExp]s.
     */
    sealed class ResolvingFailure : RuntimeException() {
        abstract override val message: String

        operator fun invoke(v: TACSymbol): String = "$v $message" // seems unneeded?

        class NotFound(private val v: TACSymbol) : ResolvingFailure() {
            override val message: String
                get() = "$v could not be found in the counter example model"
        }

        data class UnexpectedResultValue(val tacValue: TACValue, val expected: Expected) : ResolvingFailure() {
            enum class Expected(val msg: String) {
                INT_BV("Int or BitVector"),
                BOOLEAN("Boolean"),
            }

            override val message: String
                get() = "expected a ${expected.msg} (got \"$tacValue\")"
        }

        data class UnsupportedOperation(val e: UnsupportedOperationException) : ResolvingFailure() {
            override val message get() = "operation unsupported, or not yet implemented"
        }

        data class UnhashableSKey(val skey: ConcreteTacValue, val e: TACExprInterpreter.HashingException) : ResolvingFailure() {
            override val message get() = "SKey $skey could not be hashed"
        }

        data class MissingMetaKey(val meta: MetaKey<*>) : ResolvingFailure() {
            override val message get() = "meta $meta not found in symbol"
        }
    }

    // model accessed by TAC Variable Names
    abstract val tacAssignments: Map<TACSymbol.Var, TACValue>
    // model accessed by CVL Variable Names
    /** Set of blocks passed by the counter example */
    abstract val reachableNBIds: Set<NBId>
    /** Set of blocks not passed by the counter example */
    abstract val unreachableNBIds: Set<NBId>
    abstract val havocedVariables: Set<TACSymbol.Var>
    // returns an empty CounterexampleModel
    @KSerializable
    object Empty: CounterexampleModel(), AmbiSerializable {
        override val tacAssignments: Map<TACSymbol.Var, TACValue> = emptyMap()
        override val reachableNBIds: Set<NBId> = emptySet()
        override val unreachableNBIds: Set<NBId> = emptySet()
        override val havocedVariables: Set<TACSymbol.Var> = emptySet()
        override fun cvlAssignments(fullyProcessedTAC: CoreTACProgram): Map<String, TACValue> = emptyMap()

        private fun readResolve(): Any = Empty
    }

    protected open fun getAssignmentOf(v: TACSymbol.Var): TACValue? = this.tacAssignments[v]

    override fun toString(): String = tacAssignments.map { "${it.key}=${it.value}" }.joinToString("\n")

    fun valueAsTACValue(v: TACSymbol): TACValue? = when (v) {
        is TACSymbol.Const ->
            // this Const case may look a bit useless, but it is convenient sometimes to be able to call this method on
            // all types of TACSymbols
            when (v.tag) {
                is Tag.Bits,
                Tag.Int -> TACValue.PrimitiveValue.Integer(v.value)
                Tag.Bool -> TACValue.PrimitiveValue.Bool(v.value)
                Tag.ByteMap,
                is Tag.GhostMap,
                Tag.WordMap,
                is Tag.UserDefined.Struct,
                is Tag.UserDefined.UninterpretedSort,
                is Tag.Move ->
                    throw UnsupportedOperationException("Unexpected case: We don't have constant TACSymbols for " +
                        "expressions of type \"${v.tag}\".")

                Tag.BlockchainState,
                Tag.CVLArray.RawArray,
                is Tag.CVLArray.UserArray -> {
                    logger.warn { "cannot convert the TACSymbol.Const \"$v\" to a TACValue (it is unexpected to have a " +
                        "TACSymbol.Const of type \"${v.tag}\")." }
                    null
                }
            }

        is TACSymbol.Var -> {
            when (val value = getAssignmentOf(v)) {
                is ConcreteTacValue -> value
                else -> null
            }
        }
    }

    /**
     * Given a [ConcreteTACValue] that belongs to a TAC expression of skey type, this attempts to convert the value
     * to a [TACValue.PrimitiveValue.Integer] inside the Bit256 space using the model of the `from_skey` builtin
     * function.
     * This is done by constructing the [TACExpr] `from_skey(skey)`, and using [TACExprInterpreter] to evaluate it
     * under [tacAssignments].
     *
     * (background: Under the Datatypes hashing scheme, this will convert from the skey datatype to bit256; under the
     *  Legacy hashing scheme, this will convert a very large integer (> maxBv256) to an integer in the bit256 range);
     *  under the PlainInjectivity hashing scheme, this will amount to applying the identity function, the input
     *  is already in the bit256 space then.)
     */
    fun storageKeyToInteger(skey: ConcreteTacValue): Either<TACValue.PrimitiveValue.Integer, ResolvingFailure> {
        val toInteger = TACExpr.Apply.Unary(
            f = TACBuiltInFunction.Hash.FromSkey.toTACFunctionSym(),
            op = skey.asConstantTacExpr(),
            tag = Tag.Bit256
        )

        return try {
            val tv = TACExprInterpreter.eval(tacAssignments, toInteger)
            if (tv is TACValue.PrimitiveValue.Integer) {
                tv.toLeft()
            } else {
                ResolvingFailure.UnexpectedResultValue(tv, Expected.INT_BV).toRight()
            }
        } catch (e: UnsupportedOperationException) {
            ResolvingFailure.UnsupportedOperation(e).toRight()
        } catch (e: TACExprInterpreter.HashingException) {
            ResolvingFailure.UnhashableSKey(skey, e).toRight()
        }
    }

    /**
     * Trying to resolve a BigInteger value for [v] from this model.
     * Based on the success/failure of the resolving, either a [BigInteger]
     * or an appropriate [ResolvingFailure] is returned.
     */
    fun valueAsBigInteger(v: TACSymbol): Either<BigInteger, ResolvingFailure> {
        val d = valueAsTACValue(v) ?: return ResolvingFailure.NotFound(v).toRight()
        return when (d) {
            is TACValue.PrimitiveValue -> d.asBigInt.toLeft()
            else -> ResolvingFailure.UnexpectedResultValue(d, Expected.INT_BV).toRight()
        }
    }

    /**
     * Trying to resolve a boolean value for [v] from this model.
     * If its underlying [TACValue] is not a boolean,
     * returns a [ResolvingFailure.UnexpectedResultValue], otherwise, returns
     * the boolean value itself.
     */
    fun valueAsBoolean(v: TACSymbol): Either<Boolean, ResolvingFailure> {
        val d = valueAsTACValue(v) ?: return ResolvingFailure.NotFound(v).toRight()
        return if (d is TACValue.PrimitiveValue.Bool) {
            d.value.toLeft()
        } else {
            ResolvingFailure.UnexpectedResultValue(d, Expected.BOOLEAN).toRight()
        }
    }

    /**
     * if [v] has [CVL_TYPE] meta and [v] has a value in this model,
     * returns the corresponding [TACValue] and the [CVLType.PureCVLType] extracted from the [MetaKey].
     * otherwise, returns an appropriate error.
     */
    fun valueAndPureCVLType(v: TACSymbol.Var): Either<Pair<TACValue, CVLType.PureCVLType>, ResolvingFailure> {
        val tv = valueAsTACValue(v)
            ?: return ResolvingFailure.NotFound(v).toRight()
        val cvlType = v.meta[CVL_TYPE]
            ?: return ResolvingFailure.MissingMetaKey(CVL_TYPE).toRight()

        return Pair(tv, cvlType).toLeft()
    }

    /**
     * Finds the first assert statement that this model violates in [coreProgram], which corresponds to the compiled rule of [rule].
     * Returns null if no such statement has been found.
     * If the message of the assert statement found is empty and [defaultAssertMessage] is not null,
     * [RuleCheckResult.RuleFailureMeta.ViolatedAssert.message] would be set to [defaultAssertMessage].
     */
    fun findMetaOfFirstViolatedAssert(
        coreProgram: CoreTACProgram,
        rule: IRule,
        defaultAssertMessage: String? = null
    ): RuleCheckResult.RuleFailureMeta.ViolatedAssert? {
        val g = coreProgram.analysisCache.graph
        val reachBlocksInTopoOrder: List<NBId> = coreProgram.topoSortFw.filter { e -> e in reachableNBIds }
        reachBlocksInTopoOrder.forEach { block ->
            g.elab(block).commands
                .filter { it.cmd is TACCmd.Simple.AssertCmd }
                .forEach innerForEach@ { ltac ->
                    val assertMsg: String =
                        if (ltac.cmd is TACCmd.Simple.AssertCmd &&
                            valueAsTACValue(ltac.cmd.o) == TACValue.False
                        ) {
                            ltac.cmd.msg
                        } else {
                            return@innerForEach
                        }
                    val assertId: Int = ltac.cmd.meta.find(TACMeta.ASSERT_ID) ?: throw IllegalStateException(
                        "Expected ${ltac.cmd}@${ltac.ptr} to have an ASSERT_ID meta key, but got ${ltac.cmd.meta}"
                    )
                    if (rule.ruleType is SpecType.Single.GeneratedFromBasicRule.MultiAssertSubRule.SpecFile) {
                        val multiAssertRuleType = rule.ruleType as SpecType.Single.GeneratedFromBasicRule.MultiAssertSubRule.SpecFile
                        if (multiAssertRuleType.assertId != assertId) {
                            throw IllegalStateException(
                                "Expected the violated assert Id to be ${
                                    multiAssertRuleType.assertId
                                }, but got $assertId"
                            )
                        }
                    }
                    val range: Range = ltac.cmd.meta.find(TACMeta.CVL_RANGE)?: Range.Empty()
                    val msg = if (assertMsg == "" && defaultAssertMessage != null) {
                        defaultAssertMessage
                    } else {
                        assertMsg
                    }
                    return@findMetaOfFirstViolatedAssert RuleCheckResult.RuleFailureMeta.ViolatedAssert(
                        rule.ruleIdentifier.derivedAssertIdentifier(msg, assertId),
                        msg,
                        range,
                    )
                }
        }
        return null
    }

    /**
     * @return (<success>, <evaluated value>)
     * */
    fun evalExprByRhs(e: TACExpr): Pair<Boolean, BigInteger?> {
        when (e) {
            is TACExpr.QuantifiedFormula ->
                return true to null

            is TACExpr.Sym.Var ->
                return when (val tacValue = this.valueAsTACValue(e.s)) {
                    is TACValue.PrimitiveValue -> true to tacValue.asBigInt
                    else -> false to null
                }

            is TACExpr.AnnotationExp<*> ->
                return evalExprByRhs(e.o)

            is TACExpr.Sym.Const ->
                return true to e.s.value

            else -> Unit
        }

        val (successes, nullableArgs) = e.getOperands().map { evalExprByRhs(it) }.unzip()
        val success = successes.all { it }

        val args = nullableArgs.map {
            it ?: return success to null
        }

        try {
            return when (e) {
                is TACExpr.Apply ->
                    when ((e.f as? TACExpr.TACFunctionSym.BuiltIn)?.bif) {
                        is NoSMulOverAndUnderflowCheck,
                        is NoSAddOverAndUnderflowCheck,
                        is NoSSubOverAndUnderflowCheck,
                        is SafeMathNarrow,
                        is TwosComplement.Unwrap,
                        is TwosComplement.Wrap,
                        is NoAddOverflowCheck,
                        is NoMulOverflowCheck ->
                            e.eval(args)
                                ?.let { success to it }
                                ?: (false to null)

                        else -> success to  e.eval(args)
                    }

                else -> success to e.eval(args)
            }
        } catch (_ : IllegalStateException) {
            // the exception is swallowed because we may be evaluating expressions that are after the failed
            // assert. That means that values there are unrestricted, which crashes internal `eval` checks,
            // such as the one in the eval of sign-extend.
            return false to null
        }
    }

    fun instantiate(hookValue: HookValue): TACValue.PrimitiveValue.Integer? {
        return when (hookValue) {
            is HookValue.Direct -> this
                .evalExprByRhs(hookValue.expr)
                .filterSuccess()
                ?.let(TACValue.PrimitiveValue::Integer)
        }
    }

    abstract fun cvlAssignments(fullyProcessedTAC: CoreTACProgram): Map<String, TACValue>

    companion object {

        /**
         * Generates CounterExampleModel from SMT model.
         */
        fun fromSMTResult(
            exampleInfo: AbstractTACChecker.ExampleInfo,
            fullyProcessedTAC: CoreTACProgram
        ): CounterexampleModel =
            if (exampleInfo.solverResult == SolverResult.SAT && exampleInfo.model is SMTCounterexampleModel) {
                extendModel(
                    exampleInfo.model,
                    fullyProcessedTAC,
                    exampleInfo.model.getReachableBadBlocks()
                )
            } else {
                Empty
            }
    }
}

/**
 * Converts [SmtFormulaCheckerResult] to an assignment of TAC program variables to values.
 *
 * This relies on a backtranslation being present in the result via a [TacExpTermsOfInterest] object. Will just return
 * [partialAssignment] otherwise.
 *
 * Proceeds in these steps:
 *  - backtranslate  [SmtExp] model keys to [TACExpr]s (those will be either [TACSymbol.Var]s or
 *    [TACExpr.Select]/[TACExpr.Apply], so far at least
 *  - convert the model values (e.g. SMT literals in case of scalars) to [TACValue]s (using the [smtExpToTacValue] method)
 *  - separate the backtranslated keys into scalars and maps
 *    - for scalars the [TACExpr] is a [TACExpr.Sym.Var], which we convert to a [TACSymbol.Var] and use for the result map
 *    - for maps, the [TACExpr] will be a select or apply -- these cases we process separately in order to set up a
 *      key value mapping for each map or builtInFunction, which we convert to an appropriate [TACValue.MappingValue]
 *
 * @param partialAssignment this parameter is fuzzer-specific; it is used to preserve state that is in [TACValue] that
 *  is not contained in the model (or which didn't get backtranslated for some reason). In non-fuzzer use cases, this
 *  can be left at the (empty) default.
 */
fun SmtFormulaCheckerResult.toTacAssignment(partialAssignment: Map<TACSymbol.Var, TACValue> = emptyMap()): Map<TACSymbol.Var, TACValue> {
    require(this.satResult == SatResult.SAT) { "model only available when result is SAT (got: $this)" }
    val values = this.getValuesResult ?: return emptyMap()

    val tacExprsToValues = mutableMapOf<TACExpr, TACValue>().also { map ->
        val query = when (this) {
            is SmtFormulaCheckerResult.Single -> this.query
            is SmtFormulaCheckerResult.Agg -> this.representativeResult.query
        }
        val tacExpTermsOfInterest = query?.termsOfInterest as? TacExpTermsOfInterest
        if (tacExpTermsOfInterest != null) {
            // the backtranslation might have values for things that we have optimized out but are needed for
            // call trace construction (example: to_skey / from_skey in plainInjectivity case -- they're the
            // identity, but are just optimized out in the formula; in order to not have to make an exception in
            // calltrace-land, we add that information here)
            map.putAll(tacExpTermsOfInterest.optimizedOutModelValues)
        }
        val backTranslation = tacExpTermsOfInterest?.backTranslation
        if (backTranslation != null) {
            values.forEachEntry { (k, v) ->
                // combine [backtranslation] and the smt model ([smtExpToTacValue]) to construct the tac assignment
                backTranslation(k)?.let {
                    if (it in map) {
                        logger.warn { "about to overwrite an assignment for $it." }
                    }

                    try {
                        map[it] = smtExpToTacValue(v, query.symbolTable)
                    } catch (@Suppress("SwallowedException") e: UnsupportedOperationException) {
                        logger.warn { "failed to translate smt exp to tac value, leaving it out of the tac-level " +
                            "model; tac symbol: $it, smt symbol: $k, model value: $v" }
                        null
                    }
                } ?: run {
                    // the purpose of this block is to get debug feedback when a map variable gets lost in
                    // backtranslation (the different checks in the disjunctions are basically heuristics for
                    // identifying a model value of map type) -- will/should be updated when DumpMapDefinitions mode is
                    // developed further.
                    fun isMapVar(variable: SmtExp, value: SmtExp) =
                        (variable.sort?.isArraySort() == true ||
                            (variable.sort != Sort.BV256Sort &&
                                variable.sort != Sort.IntSort &&
                                variable.sort != Sort.BoolSort) ||
                            value.isMapDefinitionLiteral(query.symbolTable))
                }
            }

        } else {
            logger.error { "got a TermsOfInterest object that has no backtranslation attached, returning the input " +
                "partial assignment unchanged (an empty map)." }
            return partialAssignment
        }
    }

    val (vars, nonVars) = tacExprsToValues.entries.partition { (k, _) -> k is TACExpr.Sym.Var }

    /** Convert valuations of a set of select-terms into valuations of a set of map variables.  */
    fun processMapEntries(mapPoints: List<Map.Entry<TACExpr, TACValue>>): Map<TACSymbol.Var, TACValue> {
        fun getModelVal(loc: TACExpr): TACValue? =
            if (loc is TACExpr.Sym.Const) {
                TACValue.valueOf(loc.s.value)
            } else {
                tacExprsToValues[loc]
            }

        val res = mutableMapOf<TACSymbol.Var, TACValue.MappingValue>()
        mapPoints.forEach { (exp, value) ->
            if (exp is TACExpr.Select && exp.base is TACExpr.Sym.Var) {
                val modelAtLoc = getModelVal(exp.loc)
                if (modelAtLoc == null) {
                    logger.warn {
                        "failed to process model of map \"$exp\" at location \"${exp.loc}\", " +
                            "could not retrieve operand value from model"
                    }
                    return@forEach
                }

                res.compute(exp.base.s) { baseSym, oldVal ->
                    /* nb, it's important to take this from the `partialAssignment` if present due to state the ByteMap
                     * might, have, like initialValue, accessedIndices and such */
                    val mapVal = oldVal
                        ?: partialAssignment[baseSym].takeIf { it != TACValue.Uninitialized }
                        ?: when (baseSym.tag) {
                            is Tag.WordMap -> TACValue.MappingValue.KVMapping.WordMap()
                            is Tag.ByteMap -> TACValue.MappingValue.KVMapping.ByteMap()
                            else -> `impossible!` // right?
                        }
                    check(mapVal is TACValue.MappingValue.KVMapping) {
                        "something's wrong, value for program variable \"$baseSym\" is not a KVMapping"
                    }
                    mapVal.store(
                        modelAtLoc,
                        value as? TACValue.PrimitiveValue.Integer
                            ?: error("got a non-primitive model value for a map entry ($value); unexpected..")
                    )
                }
            } else if (exp is TACExpr.Apply && exp.f is TACExpr.TACFunctionSym.BuiltIn ) {
                val mapVar = when ((exp.f as TACExpr.TACFunctionSym.BuiltIn).bif) {
                    TACBuiltInFunction.Hash.ToSkey -> TACBuiltInFunction.Hash.ToSkey.asMapVar.s
                    TACBuiltInFunction.Hash.FromSkey -> TACBuiltInFunction.Hash.FromSkey.asMapVar.s
                    else -> null
                }
                check(exp.ops.size == 1) {
                    "only handling 1-argument uninterpreted functions for now, got expression \"$exp\""
                }
                val modelOfMapArg = getModelVal(exp.ops.first())

                if (mapVar == null) {
                    logger.warn { "don't know how to process model entry \"$exp\" -> \"$value\" (but might be fine)" }
                    return@forEach
                }
                if (modelOfMapArg == null) {
                    logger.warn {
                        "failed to process model of map \"$exp\" at location \"${exp.ops.first()}\", " +
                            "could not retrieve operand value from model"
                    }
                    return@forEach
                }

                res.compute(mapVar) { _, oldVal ->
                    val mapVal = oldVal ?: TACValue.MappingValue.UninterpretedFunction(mapOf())
                    check(mapVal is TACValue.MappingValue.UninterpretedFunction) {
                        "something's wrong, value for program variable " +
                            "\"${(exp.f as TACExpr.TACFunctionSym.BuiltIn).bif}\" is not an UninterpretedFunction"
                    }
                    mapVal.store(listOf(modelOfMapArg), value)
                }
            } else {
                logger.warn { "don't know how to process model entry \"$exp\" -> \"$value\" (but might be fine)" }
            }
        }
        return res
    }

    val primitiveAssignemts = vars.associate { (k, v) -> (k as TACExpr.Sym.Var).s to v }
    val mapAssignments = processMapEntries(nonVars)
    return partialAssignment + primitiveAssignemts + mapAssignments
}


private fun smtExpToTacValue(
    value: SmtExp,
    symbolTable: SmtSymbolTable?,
): TACValue = when {
    value.isIntLiteral() -> {
        TACValue.PrimitiveValue.Integer(value.asBigInt())
    }
    value.isBitVectorLiteral() -> {
        TACValue.PrimitiveValue.Integer(value.asBigInt())
    }
    value.isBooleanValueLit() -> {
        TACValue.PrimitiveValue.Bool(value.asBoolean())
    }
    value.isDatatypeLiteral(symbolTable) -> {
        TACValue.SKey.fromSmtExp(value)
    }
    value.isMapDefinitionLiteral(symbolTable) -> {
        TACValue.MappingValue.MapDefinition.fromSmtExp(value, symbolTable)
    }
    else -> {
        error("not a literal? $value")
    }
}

/** adapter for legacy API */
private fun <T> Pair<Boolean, T>.filterSuccess() : T? {
    val (success, value) = this
    return value.takeIf { success }
}
