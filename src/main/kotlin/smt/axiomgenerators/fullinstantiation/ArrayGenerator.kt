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

package smt.axiomgenerators.fullinstantiation

import algorithms.*
import analysis.storage.StorageAnalysisResult.NonIndexedPath
import config.Config
import datastructures.*
import datastructures.stdcollections.*
import log.*
import smt.GenerateEnv
import smt.QuantifierRewriter.Companion.LEXPRESSION_STORAGE_ACCESSES
import smt.axiomgenerators.*
import smt.axiomgenerators.fullinstantiation.ArrayGenerator.QAxiom.SubQAxiom
import smt.newufliatranslator.AxiomGenerator
import smt.newufliatranslator.AxiomGeneratorContainer
import smt.solverscript.LExpressionFactory
import smt.solverscript.SmtTheory
import smt.solverscript.functionsymbols.*
import smt.solverscript.functionsymbols.IFixedFunctionSignatures.FixedFunctionSignatures
import statistics.data.ArrayGeneratorStats
import tac.Tag
import tac.isMapType
import utils.*
import utils.allValues
import vc.data.LExpression
import vc.data.Quantifier
import vc.data.lexpression.PlainLExpressionTransformer
import vc.gen.LExpVCMetaData
import java.util.concurrent.atomic.AtomicInteger
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract


private val logger = Logger(LoggerTypes.SMT_ARRAY_GENERATOR)

/**
 * A little misleading. To be a true Array id, it should be of maptype. See [ArrayGenerator.isArray].
 */
private typealias ArrayId = LExpression.Identifier

/**
 * Models arrays (which stand for mappings/arrays/ghost-functions) as a define-fun of the arrays they are created from
 * (via stores, etc). Source arrays, i.e., those that are not created from any other array, are modeled as an
 * uninterpreted function.
 *
 * For example, if:
 *    `B = store(A, index, value)`
 * we'd have a `define-fun`:
 *     `A(x) := index == x ? value : B(x)
 *
 * [visit] creates an assignment DAG for arrays. Each one should have exactly one definition point because of SSA.
 * There are 5 kinds of definitions: Simple Assignment, MapDefinition, Ite, Store, and LongStore. The parents of an
 * array are the arrays that it is created from (one array in the case of simple assignment and Store, two in LongStore,
 * possibly many in Ite, and 0 in MapDefinition). For each array we also collect all its accesses (termed "selects"
 * here).
 *
 * Grounding of quantifiers is also done here. See the specific comments in the code. The high level design document is:
 * `https://www.notion.so/certora/Optimistic-Grounding-7eb6d08382b640f8a9681131ac1dacb4`.
 *
 * Currently: arrayTheoryMode is not supported. The main reason is MapDefinition which needs special support. Also, when
 * we last checked, it seemed to perform worse than our own implementation. Now with the addition of grounding, it's not
 * sure what are the challenges in supporting it.
 */
class ArrayGenerator(
    private val lxf: LExpressionFactory,
    private val targetTheory: SmtTheory,
) : AxiomGenerator() {

    private val statsBuilder = ArrayGeneratorStats.Builder()
    override val stats
        get() = statsBuilder.build()

    private lateinit var ruleName: String

    override fun visitVCMetaData(lExpVc: LExpVCMetaData) {
        super.visitVCMetaData(lExpVc)
        ruleName = lExpVc.tacProgramMetadata.ruleAndSplitId.toString()
    }

    private var groundQuantifiers = Config.GroundQuantifiers.get()
    fun cantGround() {
        groundQuantifiers = false
    }

    private val LExpression.paths get() = meta[LEXPRESSION_STORAGE_ACCESSES]?.map.orEmpty()


    companion object {

        private val warnAtNumSelects = AtomicInteger(100000)

        val AxiomGeneratorContainer.arrayGenerator
            get() =
                this.allGenerators
                    .filterIsInstance<ArrayGenerator>()
                    .also { check(it.size < 2) { "Can't have two ArrayGenerators!" } }
                    .singleOrNull()

        @OptIn(ExperimentalContracts::class)
        fun isArray(e: LExpression): Boolean {
            contract {
                returns(true) implies (e is ArrayId)
            }
            return e is ArrayId && e.tag.isMapType()
        }

        /**
         * Extracts the base arrays from a possibly complex expression (allowed only to contain ites).
         */
        fun arraysFromExpr(arrayExpr: LExpression): Set<ArrayId> {
            val res = mutableSetOf<ArrayId>()
            fun recurse(e: LExpression) {
                when {
                    isArray(e) -> res.add(e)
                    e.isApplyOf<TheoryFunctionSymbol.Ternary.Ite>() -> {
                        recurse(e.thenExp)
                        recurse(e.elseExp)
                    }

                    else -> error("Array Expression $arrayExpr contains non-ite sub-expressions")
                }
            }
            recurse(arrayExpr)
            return res
        }

        /**
         * A map definition that uses * in its expressions has a backing uninitialized array that holds all these values.
         */
        private fun backing(lxf: LExpressionFactory, a: ArrayId) =
            lxf.const("backing_${a.id}", a.tag, a.meta)

        private fun containsStar(e: LExpression) =
            e.contains { sub: LExpression ->
                sub.isApplyOf<NonSMTInterpretedFunctionSymbol.Nullary.Nondet>()
            }

        fun defErr(rhs: LExpression?): Nothing {
            error("Array Definition graph should not contain $rhs")
        }

        /**
         * For Each [ArrayId] this keeps the rhs of its assign expression, and so it's "parent" arrays, the
         * arrays it is created from.
         */
        class ArrayDefGraph(val lxf: LExpressionFactory) {
            constructor(lxf: LExpressionFactory, expressions: Sequence<LExpression>) : this(lxf) {
                for (e in expressions) {
                    addFromExpr(e)
                }
            }

            private val map = mutableMapOf<ArrayId, LExpression>()
            val allArrays = mutableSetOf<ArrayId>()

            operator fun get(a: ArrayId) = map[a]

            /**
             * Adds an array to the graph.
             * Note that some arrays don't appear directly in the graph because they don't have a definition.
             */
            fun add(a: ArrayId) {
                check(isArray(a))
                allArrays.add(a)
            }

            private fun put(key: ArrayId, value: LExpression) {
                // SSA is required for arrays. Without it we are lost unless we consult the block-graph.
                // Otherwise, what would be `A[x]` if `A` has two definitions?
                map.put(key, value)?.let {
                    error("Multiple assignments for array $key := $it, $value")
                }
                add(key)
                logger.trace { "$key := $value" }
                getParents(key).forEach(::add)
            }

            fun addFromExpr(e: LExpression) {
                if (e !is LExpression.ApplyExpr || e.f !is NonSMTInterpretedFunctionSymbol.Binary.AssignEq) {
                    return
                }
                val (lhs, rhs) = e.args
                if (isArray(lhs)) {
                    when (rhs) {
                        is ArrayId -> {
                            check(isArray(rhs))
                            put(lhs, rhs)
                        }

                        is LExpression.ApplyExpr -> when (rhs.f) {
                            is TheoryFunctionSymbol.Array.Store,
                            is NonSMTInterpretedFunctionSymbol.MultiDimArray.MultiStore,
                            is AxiomatizedFunctionSymbol.LongStore,
                            is AxiomatizedFunctionSymbol.MapDefinition,
                            is TheoryFunctionSymbol.Ternary.Ite ->
                                put(lhs, rhs)

                            else -> defErr(rhs)
                        }

                        else -> defErr(rhs)
                    }
                }
            }


            private val getParentsCache = mutableMapOf<ArrayId, Set<ArrayId>>()
            fun getParents(a0: ArrayId): Set<ArrayId> = getParentsCache.computeIfAbsent(a0) { a ->
                when (val rhs = map[a]) {
                    null ->
                        emptySet()

                    is ArrayId ->
                        setOf(rhs)

                    is LExpression.ApplyExpr -> when (rhs.f) {
                        is TheoryFunctionSymbol.Array.Store,
                        is NonSMTInterpretedFunctionSymbol.MultiDimArray.MultiStore ->
                            arraysFromExpr(rhs.map)

                        is AxiomatizedFunctionSymbol.LongStore ->
                            arraysFromExpr(rhs.srcMap) + arraysFromExpr(rhs.dstMap)

                        is AxiomatizedFunctionSymbol.MapDefinition ->
                            if (containsStar(rhs.mapDef)) {
                                setOf(backing(lxf, a))
                            } else {
                                setOf()
                            }

                        is TheoryFunctionSymbol.Ternary.Ite ->
                            arraysFromExpr(rhs)

                        else -> defErr(rhs)
                    }

                    else -> defErr(rhs)
                }
            }

            fun isSource(a: ArrayId) = getParents(a).isEmpty() &&
                map[a]?.isApplyOf<AxiomatizedFunctionSymbol.MapDefinition>() != true

            /**
             * Returns arrays in topological order with sources first.
             * We take into account both the data flow induced by assignments (via [getParents]), as
             * well as usages of arrays within those assigned expressions (think a `store` containing
             * another array in the stored value, this is looked up in [map]).
             */
            val topOrder by lazy {
                topologicalOrder(allArrays, { a ->
                    getParents(a) +
                        map[a]
                            ?.subs
                            ?.filterIsInstance<ArrayId>()
                            ?.filter { it in allArrays }
                            .orEmpty()
                })
            }

            val longestPathLength by lazy {
                longestPathLength(topOrder, getParentsCache)
            }

            val numberOfOps: Map<String, Int>
                get() =
                    map.values.filterIsInstance<LExpression.ApplyExpr>()
                        .groupBy { it.f.javaClass.simpleName }.mapValues { (_, v) -> v.size }
        }
    }

    private val graph = ArrayDefGraph(lxf)

    private val constantComputer = ConstantComputer(lxf, targetTheory)

    private fun lxfSimp(e: LExpressionFactory.() -> LExpression) =
        constantComputer.evalRec(lxf.e())

    private val sortOfStorageKeys get() = Tag.Int

    /** Computes the signature of the array [array] in the final SMT file.
     * In effect that signature is the signature from the [Tag], except that in the word map case, the index type
     * is replaced with [sortOfStorageKeys].
     * The Signature is given as a list, the last entry is the result sort. */
    private fun computeFinalArraySignature(array: ArrayId): FixedFunctionSignatures {
        return when (val t =
            array.tag as? Tag.Map ?: error("array (\"$array\") must have a map tag, got \"${array.tag}\"")) {
            Tag.WordMap -> IFixedFunctionSignatures.wordMapSignature(sortOfStorageKeys)
            Tag.ByteMap -> FixedFunctionSignatures(listOf(Tag.Bit256), Tag.Bit256)
            is Tag.GhostMap -> FixedFunctionSignatures(t.paramSorts, t.resultSort)
        }
    }

    private fun fsOfArray(array: ArrayId) =
        ArraySelectFunctionSymbol(array, computeFinalArraySignature(array))

    private fun registerArrayAsDef(
        array: ArrayId,
        @Suppress("SameParameterValue") description: String,
        exp: LExpressionFactory.(List<LExpression>) -> LExpression
    ): LExpDefineFun {
        val fs = fsOfArray(array)
        val signature = computeFinalArraySignature(array)
        return LExpDefineFun(
            lxf,
            fs.name,
            description,
            signature.paramSorts,
            signature.resultSort,
            exp
        )
    }

    /** Leaves the star in */
    private fun applyMapDefinition(rhs: LExpression.ApplyExpr, locs: List<LExpression>) =
        rhs.mapDef.substitute(
            lxf,
            rhs.indVars.zip(locs).toMap()
        )

    private fun replaceStar(original: ArrayId, mapExpr: LExpression, locs: List<LExpression>) =
        mapExpr.letIf(containsStar(mapExpr)) {
            it.transformPost(lxf) { e ->
                e.letIf(e.isApplyOf<NonSMTInterpretedFunctionSymbol.Nullary.Nondet>()) {
                    applyArray(backing(lxf, original), locs)
                }
            }
        }

    private val idToDef = mutableMapOf<ArrayId, LExpDefineFun>()

    /**
     * Constructs the rhs of the array definition.
     */
    private fun arrayDefRhs(a: ArrayId): LExpressionFactory.(List<LExpression>) -> LExpression =
        when (val rhs = graph[a] ?: error("ArrayDef $a should have parent arrays")) {
            is ArrayId -> { locs ->
                applyArray(rhs, locs)
            }

            is LExpression.ApplyExpr -> when (rhs.f) {
                is TheoryFunctionSymbol.Array.Store,
                is NonSMTInterpretedFunctionSymbol.MultiDimArray.MultiStore -> { locs ->
                    if (a.tag is Tag.ByteMap && Config.PreciseByteMaps.get()) {
                        // naming convention: (select (mstore a i v) j)
                        val _a = rhs.map // written as `a` in the comments in this block
                        val i = rhs.loc // store location
                        val j = locs.singleOrNull() ?:  // select location
                        throw UnsupportedOperationException("multi dim store for bytemaps not supported")
                        val v = rhs.value

                        val selectAJ = applyArray(_a, locs)

                        /*
                           diff  := abs(i - j)
                           diff' := 32 - diff

                           case "r" -- "reading closely right of write"
                                   i    j
                           a:           123456789
                           v:      abcdefghij
                           res:         fghij6789

                           (v << diff) + (old << diff' >> diff')

                           case "l" -- "reading closely left of write"
                                   j   i
                           a:      123456789
                           v:          abcdefghij
                           res:    1234abcde

                           (old >> diff' << diff') + (v >> diff)
                         */
                        val diffR = j - i
                        val diffRcomp = 32 - diffR
                        val diffL = i - j
                        val diffLcomp = 32 - diffL
                        switch(
                            (i eq j) to v,
                            // case "r"
                            j.within(i + 1, i + 32) to
                                ((v shlBytes diffR) +
                                    ((selectAJ shlBytes diffRcomp) shrBytes diffRcomp)
                                    ),
                            // case "l"
                            j.within(i - 31, i) to
                                (((selectAJ shrBytes diffLcomp) shlBytes diffLcomp) +
                                    (v shrBytes diffL)),
                            elseExpr = selectAJ
                        )
                    } else {
                        val eq = and(locs.zip(rhs.locs).map {
                            it.first eq it.second
                        })
                        lxfSimp {  // will remove the `and` if it is just one/zero arguments.
                            ite(eq, rhs.value, applyArray(rhs.map, locs))
                        }
                    }
                }

                is AxiomatizedFunctionSymbol.LongStore -> { locs ->
                    val loc = locs.first()
                    val srcLoc = listOf((loc - rhs.dstOffset + rhs.srcOffset) safeMathNarrow Tag.Bit256)
                    val cond = loc.within(rhs.dstOffset, (rhs.dstOffset + rhs.length) safeMathNarrow Tag.Bit256)
                    lxfSimp {
                        ite(
                            cond,
                            applyArray(rhs.srcMap, srcLoc),
                            applyArray(rhs.dstMap, locs)
                        )
                    }
                }

                is TheoryFunctionSymbol.Ternary.Ite -> { locs ->
                    applyArray(rhs, locs)
                }

                is AxiomatizedFunctionSymbol.MapDefinition -> { locs ->
                    replaceStar(a, applyMapDefinition(rhs, locs), locs)
                }

                else -> defErr(rhs)
            }

            else -> defErr(rhs)
        }


    private fun register(a: ArrayId) {
        if (graph.isSource(a)) {
            lxf.registerFunctionSymbol(fsOfArray(a))
        } else {
            idToDef.getOrPut(a) {
                registerArrayAsDef(a, "Model array as definition", arrayDefRhs(a))
            }
        }
    }

    private val usedInQuantifiedExprs = mutableSetOf<ArrayId>()

    /** the reversed array creation graph. Each array points to the arrays created from it */
    private val childArrays by lazy {
        reverse(graph.allArrays.associateWith(graph::getParents))
    }


    /**
     * Registers function symbols and define-funs for arrays.
     * Also calculates and grounds quantified formulas.
     */
    override fun beforeScriptFreeze() {
        // traverse in topological order because of definitions usage order (sources first)
        graph.topOrder.forEach(::register)

        // register stores as selects. This is necessary for some grounding cases.
        graph.allArrays
            .forEach { a ->
                val rhs = graph[a] ?: return@forEach
                if (rhs.isStore) {
                    arrayToSelects.addAll(a, selectInfo(a, rhs.locs, rhs.paths))
                }
            }

        // these are the only arrays we actually need to propagate selects through.
        val arraysRelevantToQuantification  =
            getReachable(usedInQuantifiedExprs) { a ->
                childArrays[a].orEmpty() + graph.getParents(a)
            }.also {
                logger.debug { "Arrays relevant to quantification = $it"}
            }

        val originalSelects = arrayToSelects.allValues.filter {
            it.a in arraysRelevantToQuantification
        }
        arrayToSelects.clear()
        logger.debug {
            originalSelects.groupBy { it.a }.entries.joinToString("\n") { (a, selects) ->
                "Original Selects : $a ---> ${selects.size}"
            }
        }
        originalSelects.forEach(::propagateSelectRec)
        registerQAxioms()
        super.beforeScriptFreeze()
    }

    private fun applyArray(a: ArrayId, locs: List<LExpression>) =
        idToDef[a]
            ?.applyDef(locs)
            ?: lxf.applyExp(ArraySelectFunctionSymbol(a, computeFinalArraySignature(a)), locs)

    /**
     * replaces the array leaves of an ite with their apply on a specific location.
     */
    private fun applyArray(e: LExpression, locs: List<LExpression>): LExpression =
        when {
            isArray(e) ->
                applyArray(e, locs)

            e.isApplyOf<TheoryFunctionSymbol.Ternary.Ite>() ->
                lxf.ite(
                    e.cond,
                    applyArray(e.thenExp, locs),
                    applyArray(e.elseExp, locs),
                    e.meta
                )

            else -> error("Array Expression $e contains non-ite sub-expressions")
        }


    /**
     * Extracts the base arrays from a possibly complex expression (allowed only to contain ites).
     * NB: ignores the condition when looking for arrays, only looks in then/else cases
     */
    private fun arraysFromExpr(arrayExpr: LExpression): Set<ArrayId> {
        val res = mutableSetOf<ArrayId>()
        fun recurse(e: LExpression) {
            when {
                isArray(e) -> res.add(e)
                e.isApplyOf<TheoryFunctionSymbol.Ternary.Ite>() -> {
                    recurse(e.thenExp)
                    recurse(e.elseExp)
                }

                else -> error("Array Expression $arrayExpr contains non-ite sub-expressions")
            }
        }
        recurse(arrayExpr)
        return res
    }


    /**
     * We expect that [yield] is only called once on this [AxiomGenerator]
     */
    private var yieldHasBeenCalled = false

    private sealed interface SelectInfo {
        val a: ArrayId
        val locs: List<LExpression>
        val env: GenerateEnv

        operator fun component1() = a
        operator fun component2() = locs
        operator fun component3() = env

        data class Simple(
            override val a: ArrayId,
            override val locs: List<LExpression>,
            override val env: GenerateEnv = GenerateEnv()
        ) : SelectInfo

        data class StorageAccess(
            override val a: ArrayId,
            override val locs: List<LExpression>,
            override val env: GenerateEnv = GenerateEnv(),
            val nonIndexedPath: NonIndexedPath,
            val indices: List<LExpression>
        ) : SelectInfo

        fun copy(newA: ArrayId = a, newLocs: List<LExpression> = locs): SelectInfo = when (this) {
            is Simple -> copy(a = newA, locs = newLocs)
            is StorageAccess -> {
                // storage access locations are unchanged when propagating, because it's always simple stores or
                // assignments. longstore is the only thing that changes the actual access location relative to the
                // base array.
                // The reason this check is important, is that otherwise we'd have to also change the access-path meta
                // thing, we can't just change loc itself without changing that accordingly.
                check(newLocs == locs) {
                    "propagation of storage access changed the locatino from $locs to $newLocs"
                }
                copy(a = newA)
            }
        }
    }


    private fun selectInfo(
        a: ArrayId,
        locs: List<LExpression>,
        paths: MultiMap<NonIndexedPath, List<LExpression>>,
        env: GenerateEnv = GenerateEnv()
    ) =
        if (paths.isEmpty() || !groundQuantifiers) {
            listOf(SelectInfo.Simple(a, locs, env))
        } else {
            paths.pairs.map { (nonIndexedPath, indices) ->
                SelectInfo.StorageAccess(a, locs, env, nonIndexedPath, indices)
            }
        }


    /**
     * Saves for each [ArrayId] all of its selects.
     */
    private val arrayToSelects = mutableMultiMapOf<ArrayId, SelectInfo>()


    /**
     * Builds the assignment graph and records all selects
     */
    override fun visit(e: LExpression, env: GenerateEnv) {

        if (e is LExpression.QuantifiedExpr) {
            if (groundQuantifiers) {
                check(e.quantifier == Quantifier.FORALL) {
                    "Exists quantifiers should have been removed."
                }
                addQAxiom(e)
            }
            return
        }

        if (e !is LExpression.ApplyExpr) {
            return
        }

        when {
            e.f is TheoryFunctionSymbol.Binary.Eq && isArray(e.lhs) && isArray(e.rhs) ->
                error("Well, appears like we should handle array comparisons: $e")

            e.f is NonSMTInterpretedFunctionSymbol.Binary.AssignEq ->
                graph.addFromExpr(e)

            e.isSelect -> {
                val arrays = arraysFromExpr(e.map)
                arrays.forEach(graph::add)
                if (groundQuantifiers &&
                    !env.isEmpty() &&
                    e.locs.any { loc -> loc.contains { it in env.quantifiedVariables } }
                ) {
                    usedInQuantifiedExprs += arrays
                } else {
                    arrays.forEach { a ->
                        arrayToSelects.addAll(a, selectInfo(a, e.locs, e.paths, env))
                    }
                }
            }

            e.f is ArraySelectFunctionSymbol ->
                error(
                    "we don't do this replacement until GeneratorContainer.postprocess " +
                        "-- thus shouldn't reach this point "
                )
        }
    }


    private var numPropogatedSelects = 0

    private fun countSelects(num: Int) {
        numPropogatedSelects += num
        val currentThreshold = warnAtNumSelects.get()
        if (numPropogatedSelects >= currentThreshold) {
            Logger.alwaysWarn(
                "Number of propagated selects is $numPropogatedSelects (due to grounding?) for $ruleName," +
                    " and maybe for more rules."
            )
            warnAtNumSelects.compareAndExchange(currentThreshold, currentThreshold * 2)
        }
    }

    /**
     * Propagates [selectInfo], stopping when reaching the same array in a loop (as opposed to reaching it via
     * different paths).
     *
     * Main effect: updates [arrayToSelects].
     */
    private fun propagateSelectRec(selectInfo: SelectInfo) {
        var count = 0
        treeLikeDFS(
            start = selectInfo,
            id = { it.a },
            nexts = { sInfo ->
                propagateSelectFlat(sInfo).also {
                    count += it.size
                    countSelects(it.size)
                }
            }
        )
        logger.trace {
            "Propagating $selectInfo resulted in $count instances (total = $numPropogatedSelects)"
        }
    }


    /**
     * Returns which selects to propagate next.
     */
    private fun propagateSelectFlat(selectInfo: SelectInfo): Set<SelectInfo> {
        /** adds the [selectInfo] to our main datastructure */
        if (!arrayToSelects.add(selectInfo.a, selectInfo)) {
            return emptySet()
        }
        val result = mutableSetOf<SelectInfo>()
        logger.trace { "propagating $selectInfo" }

        fun <T> Set<T>.alsoLogIfNotEmpty(lazyMsg : (Set<T>) -> String) =
            also {
                if (it.isNotEmpty()) {
                    logger.trace { lazyMsg(it) }
                }
            }

        result += propagateToParents(selectInfo)
            .alsoLogIfNotEmpty { "    to parents : $it" }

        qAxioms.flatMapTo(result) { (_, qAxiom) ->
            qAxiom.propagate(selectInfo)
                .alsoLogIfNotEmpty { "    from axioms : $it" }
        }

        result += propagateToChildren(selectInfo)
            .alsoLogIfNotEmpty { "    to children : $it" }

        return result
    }


    private fun propagateToParents(selectInfo: SelectInfo): Set<SelectInfo> {
        val (a, locs, _) = selectInfo

        fun newSelects(newArrays: LExpression, newLocs: List<LExpression> = locs) =
            arraysFromExpr(newArrays).mapToSet { selectInfo.copy(it, newLocs) }

        return when (val rhs = graph[a]) {
            null -> emptySet() // this is a root array.

            is ArrayId -> newSelects(rhs)

            is LExpression.ApplyExpr -> when {
                rhs.isStore -> {
                    val cond = lxfSimp {
                        and(locs.zip(rhs.locs).map { it.first eq it.second })
                    }
                    runIf(cond != lxf.TRUE) {
                        newSelects(rhs.map)
                    }.orEmpty()
                }

                rhs.f is AxiomatizedFunctionSymbol.LongStore -> {
                    check(selectInfo is SelectInfo.Simple)
                    val loc = locs.single()
                    with(rhs) {
                        val cond = lxfSimp {
                            loc.within(dstOffset, dstOffset + length)
                        }
                        runIf(cond != lxf.TRUE) {
                            newSelects(dstMap)
                        }.orEmpty() + runIf(cond != lxf.FALSE) {
                            val srcLoc = lxfSimp { loc - dstOffset + srcOffset }
                            newSelects(srcMap, listOf(srcLoc))
                        }.orEmpty()
                    }
                }

                rhs.f is AxiomatizedFunctionSymbol.MapDefinition ->
                    emptySet() // `backing` will never be used directly, so no need to propagate.

                rhs.f is TheoryFunctionSymbol.Ternary.Ite ->
                    newSelects(rhs)

                else -> defErr(rhs)
            }

            else -> defErr(rhs)
        }
    }

    private fun propagateToChildren(selectInfo: SelectInfo): Set<SelectInfo> {
        val (a, locs, _) = selectInfo

        return childArrays[a]?.flatMapToSet { child ->
            fun newSelects(newLocs: List<LExpression> = locs) =
                setOf(selectInfo.copy(child, newLocs))

            when (val rhs = graph[child]) {
                null -> `impossible!`

                is ArrayId -> newSelects()

                is LExpression.ApplyExpr -> when {
                    rhs.isStore -> {
                        val cond = lxfSimp {
                            and(locs.zip(rhs.locs).map { it.first eq it.second })
                        }
                        runIf(cond != lxf.TRUE) {
                            newSelects()
                        }.orEmpty()
                    }

                    // a := dstMap[dstOffset:dstOffset+length) <== srcMap[srcOffset:srcOffset+length)
                    // and `loc` is an access to `dstMap` and/or `srcMap`.
                    rhs.f is AxiomatizedFunctionSymbol.LongStore -> {
                        check(selectInfo is SelectInfo.Simple)
                        val loc = locs.single()
                        with(rhs) {
                            runIf(a in arraysFromExpr(rhs.srcMap)) {
                                // if there is a chance that `loc` is in `[srcOffset:srcOffSet+length)`
                                val cond = lxfSimp {
                                    loc.within(srcOffset, srcOffset + length)
                                }
                                runIf(cond != lxf.FALSE) {
                                    val newLoc = lxfSimp { loc - srcOffset + dstOffset }
                                    newSelects(listOf(newLoc))
                                }
                            }.orEmpty() + runIf(a in arraysFromExpr(rhs.dstMap)) {
                                // if there is a chance that `loc` is not in `[dstOffset:dstOffSet+length)`
                                val cond = lxfSimp {
                                    loc.within(dstOffset, dstOffset + length)
                                }
                                runIf(cond != lxf.TRUE) {
                                    newSelects()
                                }
                            }.orEmpty()
                        }
                    }

                    rhs.f is AxiomatizedFunctionSymbol.MapDefinition -> `impossible!`

                    rhs.f is TheoryFunctionSymbol.Ternary.Ite -> newSelects()

                    else -> defErr(rhs)
                }

                else -> defErr(rhs)
            }
        }.orEmpty()
    }


    override fun yield(axiomContainer: AxiomContainer) = timeIt("Yield") {
        check(!yieldHasBeenCalled)
        yieldHasBeenCalled = true
        statsBuilder.graphSize = graph.topOrder.size
        statsBuilder.longestStoreChainLength = graph.longestPathLength
        statsBuilder.opCount = graph.numberOfOps
    }


    val postProcessArrayTransformer: PlainLExpressionTransformer = object : PlainLExpressionTransformer("ArrayGenerator.postProcessArray") {
        override fun quantifiedExprPost(exp: QuantifierExpr): IntermediateLExp? =
            runIf(groundQuantifiers) {
                qAxiomsPostProcessed[exp.quantifiedVar to exp.body]?.groundedAxiom?.applyDef(emptyList())?.lift()
                    ?: error("$exp wasn't matched.")
            }

        // This has to be done after all vc formulas have been processed, and yield has been called on the Generators.
        override fun applyExprPost(exp: ApplyExpr): IntermediateLExp? =
            when {
                exp.isSelect -> applyArray(exp.map, exp.locs).lift()
                else -> when (exp.f) {
                    is TheoryFunctionSymbol.Binary.Eq -> {
                        check(!isArray(exp.args[0])) { "Array comparisons are currently not supported: ${exp.args}" }
                        null
                    }
                    is NonSMTInterpretedFunctionSymbol.Binary.AssignEq ->
                        // erase all array assignments - they are now encoded in the array define-funs
                        runIf(isArray(exp.args[0])) { lxf.TRUE.lift() }
                    else -> null
                }
            }
    }


    //------------------------------------------------------------------------------------------------------------------
    // From here on is its all about grounding forall quantifiers (exists should have been gone by now).

    /** holds the [QAxiom] of every quantified expression in the vc */
    private val qAxioms = mutableMapOf<LExpression.QuantifiedExpr, QAxiom>()

    private fun addQAxiom(e: LExpression.QuantifiedExpr) {
        qAxioms[e] = QAxiom(e)
    }

    private val qAxiomsPostProcessed by lazy {
        qAxioms.mapKeys { (e, _) ->
            Pair(
                e.quantifiedVar.map(axiomGeneratorContainer::postProcessTransform),
                axiomGeneratorContainer.postProcessTransform(e.body)
            )
        }
    }

    /**
     * As the axioms are lazy, this instantiates and registers them. We can't instantiate them at the time they are
     * created, because the ufs they use must be registered first.
     */
    private fun registerQAxioms() {
        for (g in qAxioms.values) {
            g.groundedAxiom
        }
    }


    fun constMulOf(exp: LExpression) =
        // note: memory accesses that this is designed for use Mul (so we're not matching IntMul for now)
        if (exp.isApplyOf<NonSMTInterpretedFunctionSymbol.Vec.Mul>() &&
            exp.lhs.isConst &&
            exp.rhs is LExpression.Identifier
        ) {
            exp.rhs to exp.lhs.asConst
        } else {
            null
        }

    /**
     * A select expression appearing within a quantified formula.
     */
    private inner class Occurrence(val selectExp: LExpression, allQVars: Set<LExpression.Identifier>) {
        private val path = selectExp.meta[LEXPRESSION_STORAGE_ACCESSES]?.map?.let {
            check(selectExp.locs.size == 1)
            it.pairs.singleOrNull() ?: error("Direct storage access shouldn't have multiple access-paths")
        }

        val nonIndexedPath = path?.first

        val args = path?.second ?: selectExp.locs

        /** The set of quantified variables appearing in parameters of the expression. */
        val usedQVars = args.subs
            .filterIsInstance<LExpression.Identifier>()
            .filterTo(mutableSetOf()) { it in allQVars }

        /** The set of quantified variables appearing as one of the [locs]. */
        val simpleUsedQVars = (
            args.filterIsInstance<LExpression.Identifier>() +
                args.mapNotNull(::constMulOf).map { it.first }
            ).filterToSet { it in allQVars }


        /** the array accessed in the [selectExp] may actually be an ite of arrays */
        val arrayIds get() = arraysFromExpr(selectExp.map)

        /**
         * Does this [info] match this [Occurrence].
         * Normally this means that either the [info] is [SelectInfo.Simple], and the occurrence has no [nonIndexedPath],
         * or the other way around.
         * However, there are cases (currently only generated by [TypeBoundsGenerator]) where the quantified expression
         * is over storage accesses, yet does not access it using a storage-key expression.
         * In such a case, the [Occurrence] does not have a [nonIndexedPath], yet the [info] is a storage access, and
         * they do match.
         */
        fun matches(info: SelectInfo) =
            info.a in arrayIds &&
                (nonIndexedPath == null ||
                    info is SelectInfo.StorageAccess && info.nonIndexedPath == nonIndexedPath)

        /**
         * In the case described in the kdoc of [matches], this turns the [SelectInfo.StorageAccess] to a
         * [SelectInfo.Simple]
         */
        fun makeSimpleIfNoPath(info: SelectInfo): SelectInfo {
            require(matches(info))
            return if (info is SelectInfo.StorageAccess && nonIndexedPath == null) {
                SelectInfo.Simple(info.a, info.locs, info.env)
            } else {
                info
            }
        }

        /** Returns the new select terms that result from grounding the occurrence with replacement [sub]. */
        fun groundWith(sub: Map<LExpression.Identifier, LExpression>): List<SelectInfo> {
            val newLocs = selectExp.locs.map { it.substitute(lxf, sub) }
            return arrayIds.map { a ->
                if (nonIndexedPath == null) {
                    selectInfo(a, newLocs, paths = emptyMap()).single()
                } else {
                    SelectInfo.StorageAccess(
                        a,
                        newLocs,
                        nonIndexedPath = nonIndexedPath,
                        indices = args.map { it.substitute(lxf, sub) }
                    )
                }
            }
        }


        override fun toString() = "<$simpleUsedQVars: $selectExp>"
    }

    /**
     * A pointer to the [locIndex] argument of the [occIndex] occurrence in a list of [Occurrence]s
     */
    private data class Position(val occIndex: Int, val locIndex: Int) {
        override fun toString() = "${occIndex}!$locIndex"
    }


    private var qAxiomCounter = 1


    /**
     * One of these is defined for every quantified expression. It holds a list of axioms, each based on a different
     * way to ground the axiom - see [SubQAxiom].
     */
    private inner class QAxiom(val e: LExpression.QuantifiedExpr) {
        private val qAxiomId = qAxiomCounter++

        /** All occurrences in [e] that reference at least one quantified variable. */
        val occurrences: List<Occurrence> = run {
            val qVarSet = e.quantifiedVar.toSet()
            e.subs
                // a set so that `forall x. f(x) > 0 && f(x) < 100` will consider `f(x)` only once.
                .filterTo(mutableSetOf()) { it.isSelect }
                // only occurrences that use a quantified variable.
                .filter { exp -> exp.contains { it in qVarSet } }
                .map { Occurrence(it, qVarSet) }
        }

        /** Contains only used vars, so `forall x,y. f(x) < 100` will drop the `y`.*/
        val qVars = occurrences.flatMapToSet { it.usedQVars }.let { usedQVars ->
            // we keep the original order of quantified vars for clarity
            e.quantifiedVar.filter { it in usedQVars }
        }

        init {
            // Crash grounding on `forall x,y. f(x) != y^2` because it just can't work.
            (e.quantifiedVar.toSet() - qVars.toSet()).forEach { v ->
                if (e.body.contains { it == v }) {
                    throw CertoraException(
                        CertoraErrorType.GROUNDING_FAIL,
                        "Can't ground quantifiers, a variable is quantified yet does not appear as an argument" +
                            " of any function. Either rewrite or turn grounding off. Expression is $e"
                    )
                }
            }
        }

        val body by lazy {
            // we use a little trick here (also in other define-funs): the arguments of the define-fun have the
            // exact same name and tag as the original quantified variables. This saves us some substitutions because
            // `e.body` is already in terms of the arguments of the define-fun.
            LExpDefineFun(
                lxf = lxf,
                name = "body${qAxiomId}",
                description = "body of quantified expression",
                tags = qVars.map { it.tag },
                argNames = qVars.map { it.id },
                outTag = Tag.Bool,
                exp = { lxfSimp { e.body } }
            )
        }


        /**
         * A sub-axiom for each way to define all the [qVars]. See [SubQAxiom].
         */
        val subAxioms by lazy {
            body // register before using

            // All minimal subsets of occurrences which together use all variables directly (i.e., `f(x)`, not `f(x+1)`)
            val coveringSets =
                minimalCoveringSets(qVars.toSet(), occurrences.map { it.simpleUsedQVars })

            coveringSets.flatMap { subset ->
                // for each qVar, the set of positions it appears in (directly).
                val appearsIn = qVars.map { v ->
                    subset.flatMapToSet { occIndex ->
                        occurrences[occIndex].args.withIndex()
                            .filter { (_, arg) ->
                                v == arg || v == constMulOf(arg)?.first
                            }
                            .map { (argIndex, _) -> Position(occIndex, argIndex) }
                    }
                }

                // go over all combinations of choices of defining positions for each quantified variable.
                cartesianProduct(appearsIn).map { positions ->
                    SubQAxiom(qVars.zip(positions).toMap())
                }
            }
        }


        /** A nullary axiom which is a conjunction of all ground instances of this quantified expression */
        val groundedAxiom by lazy {
            subAxioms.forEach { it.groundedAxiom } // register them before using them.
            NullaryAxiomDef(
                lxf = lxf,
                name = "ground_instances$qAxiomId",
                description = "A conjunction of all grounded instances over all occurrence sets of forall $qAxiomId",
            ) {
                lxfSimp {
                    and(subAxioms.map { it.groundedAxiom.applyDef() })
                }
            }
        }

        fun propagate(selectInfo: SelectInfo) =
            subAxioms.flatMapToSet {
                it.propagate(selectInfo)
            }

        override fun toString() =
            "$qAxiomId, ${subAxioms.joinToString { it.defPositions.toString() }}, $qVars\n   ${e.body}"


        /**
         * This is one version of the axiom, based on the given [defPositions]. For example:
         *    forall x,y,z. f(x, y, z) = g(z, y+1) + h(y, x, c)
         * It has 3 occurrences `f(x, z, y)`, `g(z, y+1)`, and `h(y, x, c)`.
         *
         * First, the `y+1` is non-simple, and so is not considered for what follows. `c` is also not used because it
         * is not a quantified formula.
         *
         * [defPositions] should give each quantified variable one [Position] in this list of occurrences that defines it.
         * So it could be here:
         *     x -> (0,0), y -> (0,1), z -> (0,2)
         * That is, we gather the values for the variables from the `f` occurrence. Or it could be:
         *     x -> (2, 1), y -> (2, 0), z -> (1, 0)
         * which means we need two occurrences, one of `g` and one of `h` to ground this axiom.
         *
         * Let's say it's the second case. So for two instances `g(t1, t2)` and `h(t4, t4, t5)`, the axiom is grounded
         * by asserting:
         *    body(t4, t3, t1)
         *
         * This grounding will create a new instance of `f` : `f(t4, t1, t3)`. Note that even if `f` had non-simple
         * argument, a new instance of it would still be created and propagated.
         */
        private inner class SubQAxiom(val defPositions: Map<LExpression.Identifier, Position>) {

            private val name = "${qAxiomId}_${qVars.joinToString("_") { defPositions[it].toString() }}"

            private val instancesAxioms = mutableSetOf<LExpression>()

            /** The indices of occurrences we need in order to ground this axiom */
            private val definingOccIndices = defPositions.values.mapToSet { it.occIndex }

            /**
             * Given a not yet seen instance [selectInfo], creates all the new grounded of [QAxiom] above, by considering
             * all possible combinations of this new instance with the already known ones (possibly also with itself).
             * Returns all new instances generated by these new groundings.
             */
            fun propagate(selectInfo: SelectInfo): Set<SelectInfo> =
                definingOccIndices
                    .filter { occurrences[it].matches(selectInfo) }
                    .flatMapToSet { index ->
                        /**
                         *  Combinations of [selectInfo] acting as occurrence [index], with all possible instances
                         *  acting in the place of other occurrences. For example:
                         *     forall x,y. f(x) > g(y)
                         *  and we got a new instance of `f`, then we look at the combination of this `f` and all already
                         *  known instances of `g`.
                         */
                        val newInstanceCombinations: List<List<SelectInfo?>> = cartesianProduct(
                            occurrences.indices.map { i ->
                                when (i) {
                                    index -> listOf(occurrences[i].makeSimpleIfNoPath(selectInfo))

                                    in definingOccIndices ->
                                        occurrences[i].arrayIds.flatMapToSet {
                                            arrayToSelects[it]
                                                ?.filter(occurrences[i]::matches)
                                                ?.map(occurrences[i]::makeSimpleIfNoPath)
                                                .orEmpty()
                                        }

                                    else -> listOf(null)
                                }
                            }
                        )

                        newInstanceCombinations.flatMap { combination: List<SelectInfo?> ->
                            val sub = qVars.associateWith { v ->
                                val p = defPositions[v]!!
                                val occ = occurrences[p.occIndex]

                                // the expression at position `p` for this `combination` of instances
                                when (val info = combination[p.occIndex]!!) {
                                    is SelectInfo.StorageAccess ->
                                        info.indices[p.locIndex]

                                    is SelectInfo.Simple -> {
                                        val orig = info.locs[p.locIndex]
                                        constMulOf(occ.selectExp.locs[p.locIndex])
                                            ?.let { (u, coef) ->
                                                check(u == v)
                                                lxf { orig / litInt(coef) }
                                            }
                                            ?: orig
                                    }
                                }
                            }

                            // Corresponds to `body(t4, t3, t1)` in the kdoc above
                            instancesAxioms += body.applyDef(
                                qVars.map { sub[it]!! }
                            )

                            // The other occurrences are the ones we have probably just created new instances of
                            occurrences.indices
                                .filterNot { it in definingOccIndices }
                                .map { occurrences[it] }
                                .flatMapToSet { it.groundWith(sub) }
                        }
                    }

            val groundedAxiom by lazy {
                NullaryAxiomDef(
                    lxf = lxf,
                    name = "ground_instances$name",
                    description = "A conjunction of all grounded instances of sub-axiom $name",
                ) {
                    lxfSimp {
                        and(instancesAxioms.toList())
                    }
                }
            }
        }

    }

    override fun transformTermsOfInterest(lExp: LExpression): LExpression {
        return if (lExp in graph.allArrays) {
            // the type of this is a bit problematic, as it's not actually an expression, but just a function symbol ..
            //  - cvc5 solves it by interpreting the symbol as a higher-order "constant"
            //  - z3 solves it by interpreting the symbol as an array
            LExpression.Identifier(fsOfArray(lExp as ArrayId).toString(), tag = lExp.tag, meta = lExp.meta)
        } else {
            super.transformTermsOfInterest(lExp)
        }
    }
}
