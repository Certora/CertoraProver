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

package smtlibutils.cmdprocessor

import algorithms.EquivalenceRelation
import log.*
import smtlibutils.data.*
import smtlibutils.data.FactorySmtScript.bvadd256
import smtlibutils.data.FactorySmtScript.bvult256
import smtlibutils.data.FactorySmtScript.lt
import utils.*
import java.math.BigInteger
import kotlin.time.Duration

/**
 * Contains model transformations that we may want to apply before reporting a SAT result (but after the solving
 * process).
 */
object PostProcessModel {

    /** Might extend the [stats]-String to something more detailed in the future */
    data class PostProcessModelResult(val newModel: Map<SmtExp, SmtExp>, val stats: String) :
        java.io.Serializable

    /**
     * The barriers we try to get the model values under as good as we can -- given as powers of 16 or 2.
     * Must be in ascending order
     */
    private val largeBarrierConstants = listOf(1, 2, 4, 6, 16, 32).map { 16.toBigInteger().pow(it) }
    private val smallBarrierConstants = listOf(8, 9, 10, 12, 14, 16, 20).map { 2.toBigInteger().pow(it) }

    fun barrierConstants(useSmallBarriers: Boolean) = if (useSmallBarriers) {
        smallBarrierConstants
    } else {
        largeBarrierConstants
    }

    val lowerBoundForAddresses = BigInteger("400", 16)

    /**
     * This implementation supposes we do not mix integers (or reals) with bitvectors.
     */
    fun isBvMode(terms: Collection<SmtExp>): Boolean {
        return terms.any { it.sort?.isBitVecSort() ?: false}
    }

    /**
     * Attempt to make the model easier to read by choosing lower values for a given list [termsToPrettify] of variables.
     *
     * We iteratively try several barriers, starting with lower and going to higher, and checking whether there exist
     * a model (SAT assignment) such that the sum of the values of [termsToPrettify] fits under the barrier. In particular,
     * assuming [termsToPrettify] = x1, x2, ..., xn and a barrier k, we use two strategies. First, we check whether
     * there exist a model such that x1 + x2 + ... xn < k. If yes, we return such model. Otherwise, we check whether
     * there at least exists a subset of n-1 variables of [termsToPrettify] such that the sum of the values of these variables
     * can be lower than k (i.e., we allow one outlier in the model). If the second strategy also does not work, then
     * we try to increase the barrier.
     *
     *
     * We do not focus on non-aliasing values here.
     *
     * @return the refined model, possibly identical to the original model. Moreover, if we actually found a model
     * that satisfies a barrier, we keep in the queryProcessor the corresponding barrier constraints (which are then
     * exploited within [prettify]).
     */
    suspend fun prettifyJointly(
        symbolTable: SmtSymbolTable,
        queryProcessor: SmtQueryProcessor,
        queryProcessorContext: SmtQueryProcessorContext,
        allTerms: List<SmtExp>,
        termsToPrettify: List<SmtExp>,
        timeout: Duration,
        useSmallBarriers: Boolean
    ): ModelWithPartition {
        val startTime = getCurrentTime()

        logger.info { "starting to prettify model, the joint part" }
        val currentModel = ModelWithPartition.fromModel(queryProcessor.getValue(allTerms, symbolTable))

        //we do not support mixed sorts; either all Ints or all BV should fit below a barrier
        if(termsToPrettify.isEmpty() || termsToPrettify.mapToSet { it.sort }.size > 1){
            return currentModel
        }

        val bvMode = isBvMode(termsToPrettify)

        val script = queryProcessor.script

        fun getSimpleJointBarrierConstraint(barrier: SmtExp, terms: List<SmtExp>): SmtExp{
            val sum = if(terms.size == 1){
                terms[0]
            } else {
                if (bvMode) {
                    bvadd256(terms)
                } else {
                    script.apply(SmtIntpFunctionSymbol.IRA.Add(), terms)
                }
            }
            return if(bvMode){
                sum bvult256 barrier
            } else{
                sum lt barrier
            }
        }

        fun getJointBarrierConstraintWithOutlier(barrier: SmtExp, terms: List<SmtExp>): SmtExp{
            val sums = List(termsToPrettify.size) { index ->
                getSimpleJointBarrierConstraint(barrier, terms.subList(0, index) + terms.subList(index + 1, terms.size))
            }
            return script.or(sums)
        }

        /**
         * Checks whether there is a CEX (model) that satisfies the given assertion (barrier). In case of SAT result,
         * the function keeps the assertion in the queryProcessor and pops it otherwise. We use this function to
         * check whether a given barrier can be satisfied.
         */
        suspend fun checkAssert(assertion: Cmd.Assert): SatResult?{
            try {
                queryProcessorContext.push()
                queryProcessor.assert(assertion)
                val result = queryProcessor.checkSat("iterative check in prettifyJointly")
                if (result !is SatResult.SAT) {
                    queryProcessorContext.pop()
                }
                return result
            } catch (e: InteractingCmdProcessor.SMTSolverException) {
                logger.warn(e) { "Got an error from solver while post processing model" }
                queryProcessorContext.pop()
            }
            return null
        }

        val barriers = barrierConstants(useSmallBarriers).map { bc ->
            if (bvMode) script.bitvector(bc, 256) else script.number(bc)
        }

        try {
            for (barrier in barriers) {
                if (startTime.elapsedNow() > timeout) {
                    logger.warn { "model prettification exceeded timeout (${timeout.inWholeSeconds}s), aborting." }
                    return currentModel
                }
                val barrierAssert = script.assertCmd(getSimpleJointBarrierConstraint(barrier, termsToPrettify))
                val result = checkAssert(barrierAssert)
                when (result) {
                    is SatResult.SAT ->
                        //this is the tightest barrier we can satisfy
                        return ModelWithPartition.fromModel(queryProcessor.getValue(allTerms, symbolTable))
                    is SatResult.UNSAT -> {
                        //we try to at least satisfy the barrier while allowing one outlier
                        //the corersponding encoding is quadratic w.r.t. [termsToPrettify.size] and hence we apply it
                        //only if there are up to 15 terms to prettify
                        //only if small barriers are not requested
                        if(termsToPrettify.size <= 15 && !useSmallBarriers) {
                            val barrierAssertWithOutlier = script.assertCmd(getJointBarrierConstraintWithOutlier(
                                barrier, termsToPrettify))
                            if (checkAssert(barrierAssertWithOutlier) is SatResult.SAT) {
                                return ModelWithPartition.fromModel(queryProcessor.getValue(allTerms, symbolTable))
                            }
                        }
                    }
                    is SatResult.UNKNOWN -> continue
                    null -> continue
                }
            }
            return currentModel
        } catch (e: InteractingCmdProcessor.SMTSolverException) {
            logger.warn(e) {
                "Got an error from solver while post processing model; returning the model in the current " +
                    "state of post processing."
            }
            return currentModel
        }
    }


    /**
     * Attempt to make the model easier to read by choosing lower, and non-aliasing values if possible.
     *
     * We refine the model one expression (roughly: variable) at a time, we never revisit an expression.
     *  -- we're not going for optimality here; variable order can play a role for the outcome.
     * When refining for a given variable, we start optimistically with "is lower than 10 and does not alias anything",
     * if that doesn't work, we work our way up through the barriers, and if none of the barriers work, we try just
     * non-aliasing.
     *
     * @return the refined model, possibly identical to the original model.
     */
    suspend fun prettify(
        queryProcessor: SmtQueryProcessor,
        queryProcessorContext: SmtQueryProcessorContext,
        allTerms: List<SmtExp>,
        addressesToPrettify: List<SmtExp>,
        otherTermsToPrettify: List<SmtExp>,
        prioritisedTermsToPrettify: List<SmtExp>,
        symbolTable: SmtSymbolTable,
        timeout: Duration,
        jointPrettification: Boolean,
        useSmallBarriers: Boolean = false
    ): PostProcessModelResult {
        logger.info { "starting to prettify model" }

        var currentModel = ModelWithPartition.fromModel(queryProcessor.getValue(allTerms, symbolTable))

        if (!queryProcessor.options.incremental) {
            logger.warn { "SmtQueryProcessor ${queryProcessor.name}#${queryProcessor.serial} is not in incremental mode -- cannot prettify model" }
            return PostProcessModelResult(currentModel.model, "solver not in incremental mode -- did nothing")
        }

        /**
         * Optionally, we first try to find a suitable barrier (upper bound) such that the values of all the prioritised
         * terms (variables) fit below the barrier (or at least with one outlier). [prettifyJointly] leaves the
         * corresponding barrier constraints in [queryProcessor] if it finds a suitable barrier.
         */
        if(jointPrettification) {
            try {
                currentModel = prettifyJointly(
                    symbolTable,
                    queryProcessor,
                    queryProcessorContext,
                    allTerms,
                    prioritisedTermsToPrettify.filter {
                        it is SmtExp.QualIdentifier &&
                            (it.sort?.isBitVecSort() ?: false) || (it.sort?.isIntSort() ?: false) //just Int/BV vars
                    },
                    timeout,
                    useSmallBarriers
                )
            } catch (e: Exception) {
                when (e) {
                    is InteractingCmdProcessor.SMTSolverException ->
                        logger.warn {
                            "Got an error from solver while post processing model in the prettifyJointly phase; continuing" +
                                "with the sequential model postprocessing phase"
                        }
                    else ->
                        logger.warn {
                            "Got an unexpected error while post processing model in the prettifyJointly phase; continuing" +
                                "with the sequential model postprocessing phase"
                        }
                }
            }
        }

        val startTime = getCurrentTime()
        val script = queryProcessor.script

        val bvMode = isBvMode(addressesToPrettify + otherTermsToPrettify)

        fun bvOrIntLit(bi: BigInteger): SmtExp =
            if (bvMode) script.bitvector(bi, 256) else script.number(bi)

        val ltFunctionSymbol: SmtIntpFunctionSymbol =
            if (bvMode)
                SmtIntpFunctionSymbol.BV.Rel.BvULt(256)
            else
                SmtIntpFunctionSymbol.IRA.Lt()


        val barriers = barrierConstants(useSmallBarriers).map { bvOrIntLit(it) }
        val barrierAboveLowerBound = barrierConstants(useSmallBarriers)
            .filter { it > lowerBoundForAddresses }.map { bvOrIntLit(it) }


        var statsNumberOfChangedValues = 0

        /**
         * The prioritised terms go first, then all addresses, and then the remaining terms/variables.
         */
        val termsToPrettify = (addressesToPrettify + otherTermsToPrettify).sortedBy { term ->
            term !in prioritisedTermsToPrettify }

        try {
            /* Iterate over model codomain. For each expression try to improve the model as much as possible, then fix the
             * expressions current value and go on to the next expression.  */
            termsToPrettify.forEachIndexed { index, exp ->

                val lowerBound = if (exp in addressesToPrettify) bvOrIntLit(lowerBoundForAddresses) else null
                val actualBarriers = if (lowerBound == null) barriers else barrierAboveLowerBound

                var current =
                    refineModelValue(script, ltFunctionSymbol, actualBarriers, lowerBound, exp, currentModel, setOf())
                        ?: run {
                            // couldn't find a refinement constraint -- fix the current model value
                            queryProcessor.assert(script.assertCmd(script.eq(exp, currentModel.getValue(exp))))
                            return@forEachIndexed
                        }

                /*
                 * iterate refinement constraints, start with the strongest go on with the next-weakest
                 * - if one succeeds (formula remains sat) keep the new value
                 * - otherwise try the next until there are no candidate constraints anymore
                 */
                while (true) {
                    if (startTime.elapsedNow() > timeout) {
                        logger.warn { "model prettification exceeded timeout (${timeout.inWholeSeconds}s), aborting." }
                        return@prettify PostProcessModelResult(
                            currentModel.model,
                            "hit timeout (${timeout.inWholeSeconds}s), " +
                                    statsMessage(statsNumberOfChangedValues, termsToPrettify)
                        )
                    }

                    val currentValue = currentModel.getValue(current.exp)
                    logger.trace { " picked to-be-refined value:\n$current, current value $currentValue" }
                    queryProcessorContext.push()
                    current.currentConstraints.forEach {
                        queryProcessor.assert(script.assertCmd(it))
                    }
                    when (queryProcessor.checkSat("iterative check in prettify")) {
                        is SatResult.SAT -> {
                            currentModel = try {
                                ModelWithPartition.fromModel(queryProcessor.getValue(allTerms, symbolTable))
                            } catch (e: InteractingCmdProcessor.SMTSolverException) {
                                logger.warn {
                                    "Failed to retrieve model values, falling back to previous model. \n" +
                                        " Error message from solver:\n ${e.msg}"
                                }
                                return@prettify PostProcessModelResult(
                                    currentModel.model,
                                    "reached bad solver state, aborted; " +
                                        statsMessage(statsNumberOfChangedValues, termsToPrettify)
                                )
                            }
                            val newValue = currentModel.getValue(current.exp)
                            queryProcessorContext.pop()
                            queryProcessor.assert(script.assertCmd(script.eq(current.exp, newValue)))

                            logger.debug {
                                "success! fixed expression #$index/${termsToPrettify.size}, " +
                                    "${current.exp}, to value $newValue; prev value was: $currentValue; " +
                                    "qp: ${queryProcessor.name}#${queryProcessor.serial} "
                            }
                            logger.debug { "   successful constraints were: ${current.currentConstraints}" }

                            statsNumberOfChangedValues++
                            return@forEachIndexed
                        }
                        SatResult.UNSAT -> {
                            queryProcessorContext.pop()
                            // current constraint made formula unsat --> try the next (weaker) refinement (if one exists)
                            current =
                                refineModelValue(
                                    script,
                                    ltFunctionSymbol,
                                    actualBarriers,
                                    lowerBound,
                                    current.exp,
                                    currentModel,
                                    current.failedConstraints + setOf(current)
                                ) ?: run {
                                    // couldn't find a refinement constraint -- fix the current model value
                                    queryProcessor.assert(script.assertCmd(script.eq(exp, currentModel.getValue(exp))))
                                    return@forEachIndexed // if there is no refinement, go on
                                }
                        }
                        is SatResult.UNKNOWN -> {
                            queryProcessorContext.pop()
                            logger.warn { "got unknown result from solver for $exp, continuing with the next term" }
                            return@forEachIndexed
                        }
                    }
                }
            }
            return PostProcessModelResult(
                currentModel.model, "finished normally; " +
                    statsMessage(statsNumberOfChangedValues, termsToPrettify)
            )
        } catch (e: InteractingCmdProcessor.SMTSolverException) {
            logger.warn {
                "Got an error from solver while post processing model; returning the model in the current " +
                    "state of post processing.\n" +
                    "The solver response was: ${e.msg}"
            }
            return PostProcessModelResult(
                currentModel.model, "got an exception from solver, returning intermediate " +
                    "result; solver output: ${e.msg}; " + statsMessage(
                    statsNumberOfChangedValues,
                    termsToPrettify
                )
            )
        }
    }

    private fun statsMessage(
        statsNumberOfChangedValues: Int,
        termsToPrettify: List<SmtExp>
    ) = "adjusted $statsNumberOfChangedValues out of ${termsToPrettify.size} candidate values"

    /**
     *
     * Note: this [RefinementCreator] vs [Refinement] setup is a bit overblown for the current use -- leaving it in case
     *  it may become useful -- if it doesn't, should simplify
     */
    private val refinementCreators: List<RefinementCreator> = listOf(MakeSmallerWithoutAliasing /*, AvoidAlias */)

    private fun refineModelValue(
        script: ISmtScript,
        ltFunctionSymbol: SmtIntpFunctionSymbol,
        barriers: List<SmtExp>,
        lowerBound: SmtExp?,
        exp: SmtExp,
        currentModel: ModelWithPartition,
        failedConstraints: Set<Refinement>
    ): Refinement? {
        refinementCreators.forEach {
            it.create(script, ltFunctionSymbol, barriers, lowerBound, exp, currentModel, failedConstraints)
                ?.let { refinement -> return refinement }
        }
        return null
    }

    /**
     * at most one [Refinement] should ever exit, per [exp]
     *
     * @param isFinal marks a [Refinement] as being the last one we try for a given model expression -- after that,
     *  we don't attempt to create a weaker version.
     */
    data class Refinement(
        val exp: SmtExp,
        val currentConstraints: List<SmtExp>,
        val currentBarrierIndex: Int,
        val isFinal: Boolean,
        val failedConstraints: Set<Refinement>
    ) {
        override fun toString(): String {
            return exp.toString()
        }
    }

    interface RefinementCreator {
        fun create(
            script: ISmtScript,
            ltFunctionSymbol: SmtIntpFunctionSymbol,
            barriers: List<SmtExp>,
            lowerBound: SmtExp?,
            exp: SmtExp,
            model: ModelWithPartition,
            failedConstraints: Set<Refinement>
        ): Refinement?
    }

    object MakeSmallerWithoutAliasing : RefinementCreator {

        /**
         * Strategy: try to move the model to a value below the given barrier while not aliasing with anything else
         * below that barrier
         *
         * If [lowerBound] is non-null, all barriers are guaranteed to be larger
         */
        override fun create(
            script: ISmtScript,
            ltFunctionSymbol: SmtIntpFunctionSymbol,
            barriers: List<SmtExp>,
            lowerBound: SmtExp?,
            exp: SmtExp,
            model: ModelWithPartition,
            failedConstraints: Set<Refinement>
        ): Refinement? {
            if (failedConstraints.any { it.isFinal }) return null

            val value = model.getValue(exp)
            val valueInt = when {
                value.isIntLiteral() || value.isBitVectorLiteral() -> value.asBigInt()
                value is SmtExp.BoolLiteral -> return null // this refinement creation is not applicable for Booleans
                else -> {
                    logger.warn {
                        "$value (coming from a model) is not a literal; should not happpen; not creating " +
                            "refinement constraints"
                    }
                    return null
                }
            }


            /** the largest value below which we tried to constrain exp, but couldn't (given as index in [barriers]) */
            val largestFailedBarrier: Refinement? = failedConstraints.maxByOrNull { it.currentBarrierIndex }
            check((largestFailedBarrier == null) == failedConstraints.isEmpty()) { "..right?.." }
            val largestFailedBarrierIndex = largestFailedBarrier?.currentBarrierIndex

            val nextAttemptedBarrierIndex: Int =
                when {
                    largestFailedBarrierIndex == null -> {
                        // start with the smallest
                        0
                    }
                    largestFailedBarrierIndex < barriers.size - 1 -> {
                        largestFailedBarrierIndex + 1
                    }
                    else -> {
                        // failed on all barriers
                        // one last try, just for antialiasing
                        return if (model.partition.getEquivalenceClass(exp).size == 1 &&
                            (lowerBound == null || valueInt > lowerBoundForAddresses)
                        ) {
                            // already non-aliasing (and large enough), nothing to do
                            null
                        } else {
                            val allValues = model.partition.getAllRepresentatives()
                                .map { model.getValue(it) }
                                .filter { it.isIntLiteral() || it.isBitVectorLiteral() }
                            val distinct = script.distinct(listOf(exp) + allValues)
                            Refinement(
                                exp,
                                listOfNotNull(
                                    lowerBound?.let { lb -> script.apply(ltFunctionSymbol, lb, exp) },
                                    distinct
                                ),
                                -1, // should never be used
                                true,
                                failedConstraints
                            )
                        }
                    }
                }
            val nextAttemptedBarrier: SmtExp = barriers[nextAttemptedBarrierIndex]

            if (valueInt < nextAttemptedBarrier.asBigInt()) {
                /* already lower than next barrier --> stop refining */
                return if (model.partition.getEquivalenceClass(exp).size == 1 &&
                    (lowerBound == null || valueInt > lowerBoundForAddresses)) {
                    // already non-aliasing and bigger than lower bound; nothing to do
                    null
                } else {
                    val allValuesBelowBarrier = model.partition.getAllRepresentatives()
                        .map { model.getValue(it) }
                        .filter {
                            (it.isIntLiteral() || it.isBitVectorLiteral())
                                && it.asBigInt() < nextAttemptedBarrier.asBigInt()
                        }
                    val distinct = script.distinct(listOf(exp) + allValuesBelowBarrier)
                    Refinement(
                        exp,
                        listOfNotNull(
                            lowerBound?.let { lb -> script.apply(ltFunctionSymbol, lb, exp) },
                            script.apply(ltFunctionSymbol, exp, nextAttemptedBarrier),
                            distinct
                        ),
                        -1, // should never be used
                        true,
                        failedConstraints
                    )
                }
            }


            /* case: value is bigger than barrier --> try to push below barrier without introducing aliasing */

            val modelValuesInsideNextBarrier = model.partition.getAllRepresentatives().map {
                model.getValue(it)
            }.filter { modelValue ->
                (modelValue.isIntLiteral() || modelValue.isBitVectorLiteral()) && // don't consider Booleans, fractions, arrays
                    modelValue.asBigInt() <= nextAttemptedBarrier.asBigInt()
            }
            val distinct = script.distinct(listOf(exp) + modelValuesInsideNextBarrier)

            return Refinement(
                exp,
                listOfNotNull(
                    lowerBound?.let { lb -> script.apply(ltFunctionSymbol, lb, exp) },
                    script.apply(ltFunctionSymbol, exp, nextAttemptedBarrier),
                    distinct
                ),
                nextAttemptedBarrierIndex,
                false,
                failedConstraints
            )
        }
    }

    class ModelWithPartition private constructor(val model: Map<SmtExp, SmtExp>, val partition: EquivalenceRelation<SmtExp>) {
        fun getValue(exp: SmtExp): SmtExp =
            model[exp] ?: error("failed to find $exp in model, should not happen")

        companion object {
            fun fromModel(model: Map<SmtExp, SmtExp>): ModelWithPartition {
                return ModelWithPartition(model, EquivalenceRelation.fromMap(model))
            }
        }
    }


    private val logger = Logger(SMTLIBUtilsLoggerTypes.SMTLIBUTILS)
}
