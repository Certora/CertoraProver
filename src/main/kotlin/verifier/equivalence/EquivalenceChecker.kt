/*
 *     The Certora Prover
 *     Copyright (C) 2025  Certora Ltd.
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, version 3 of the License.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY, without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR a PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package verifier.equivalence

import analysis.CmdPointer
import com.certora.collect.*
import config.Config
import datastructures.stdcollections.*
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.*
import log.*
import report.OutputReporter
import report.StatusReporter
import report.TreeViewReporter
import rules.RuleCheckResult
import scene.IScene
import scene.ProverQuery
import scene.TACMethod
import spec.rules.EquivalenceRule
import tac.MetaKey
import tac.MetaMap
import tac.NBId
import tac.Tag
import utils.*
import vc.data.*
import verifier.equivalence.cex.*
import verifier.equivalence.data.*
import verifier.equivalence.tracing.BufferTraceInstrumentation
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

private val logger = Logger(LoggerTypes.EQUIVALENCE)

internal typealias TaggedMap<@Suppress("UNUSED_TYPEALIAS_PARAMETER", "unused") T, K, V> = Map<K, V>


/**
 * The actual object that does equivalence checking.
 */
class EquivalenceChecker private constructor(
    override val queryContext: EquivalenceQueryContext,
    val methodA: TACMethod,
    val methodB: TACMethod,
    /**
     * The overall rule for this equivalence check
     */
    val equivalenceRule: EquivalenceRule
) : IWithQueryContext {

    @KSerializable
    data class StorageComparison(
        val contractAValue: TACSymbol.Var,
        val contractBValue: TACSymbol.Var,
        val skolemIndex: TACSymbol.Var
    ) : AmbiSerializable, TransformableVarEntityWithSupport<StorageComparison> {
        override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var): StorageComparison {
            return StorageComparison(
                contractAValue = f(contractAValue),
                contractBValue = f(contractBValue),
                skolemIndex = f(skolemIndex)
            )
        }

        override val support: Set<TACSymbol.Var>
            get() = setOf(contractAValue, contractBValue, skolemIndex)
    }

    companion object {

        fun <T: MethodMarker> TACMethod.toContext() = ProgramContext.of<T>(this)


        fun List<UByte>.asBufferRepr(emptyRepr: String): String {
            if(isEmpty()) {
                return emptyRepr
            }
            return joinToString("") {
                it.toString(16)
            }
        }

        val TRACE_EQUIVALENCE_ASSERTION = MetaKey<Int>("equivalence.trace.assertion")
        val STORAGE_EQUIVALENCE_ASSERTION = MetaKey<StorageComparison>("equivalence.storage.assertion")

        /**
         * Finds the method in the two contracts of [query] that matches the provided [Config.MethodChoices] arg.
         * Throws if these functions can't be found (unrecoverable error)
         */
        fun IScene.resolve(query: ProverQuery.EquivalenceQuery) : Pair<TACMethod, TACMethod> {
            val methodChoice = Config.MethodChoices.orEmpty().singleOrNull() ?: throw CertoraException(
                CertoraErrorType.BAD_CONFIG,
                "Missing single method choice"
            )
            val contractA = this.getContract(query.contractA)
            val contractB = this.getContract(query.contractB)


            val methodA = contractA.getMethods().find {
                val m = it as TACMethod
                m.evmExternalMethodInfo?.toExternalABIName() == methodChoice
            } ?: throw CertoraException(
                CertoraErrorType.NO_MATCHING_METHOD,
                "No method $methodChoice found in equivalence contract ${contractA.name}"
            )

            val methodSighash = methodA.sigHash!!

            val methodB = contractB.getMethodBySigHash(methodSighash.n)!!
            return (methodA as TACMethod) to (methodB as TACMethod)
        }

        suspend fun handleEquivalence(
            query: ProverQuery.EquivalenceQuery,
            scene: IScene,
            outputReporter: OutputReporter,
            treeViewReporter: TreeViewReporter
        ) : List<RuleCheckResult> {
            if(!Config.EquivalenceCheck.get()) {
                throw CertoraException(
                    CertoraErrorType.BAD_CONFIG,
                    "Need to set ${Config.EquivalenceCheck.option.opt} in equivalence checker mode"
                )
            }

            Logger.always("WARNING: Concord is still extremely experimental, and should not be used in production contexts", respectQuiet = false)

            val methodChoice = Config.MethodChoices.orEmpty().singleOrNull() ?: throw CertoraException(
                CertoraErrorType.BAD_CONFIG,
                "Missing single method choice"
            )

            EquivalencePreprocessor.preprocess(
                scene, query
            )
            val equivalenceRule = EquivalenceRule.freshRule("Equivalence of ${query.contractA.name} and ${query.contractB.name} on $methodChoice")

            StatusReporter.registerSubrule(equivalenceRule)

            val (methodA, methodB) = scene.resolve(query)

            treeViewReporter.addTopLevelRule(equivalenceRule)
            treeViewReporter.signalStart(equivalenceRule)

            val r = EquivalenceChecker(
                object : EquivalenceQueryContext {
                    override val contextA: ProgramContext<MethodMarker.METHODA> = ProgramContext.of(methodA)
                    override val contextB: ProgramContext<MethodMarker.METHODB> = ProgramContext.of(methodB)
                    override val scene: IScene
                        get() = scene
                },
                methodA,
                methodB,
                equivalenceRule = equivalenceRule,
            ).handleEquivalence()
            r.forEach {
                if(it is RuleCheckResult.Leaf) {
                    treeViewReporter.signalEnd(it.rule, it)
                }
            }
            outputReporter.feedReporter(r, scene)
            return r
        }


        fun TACMethod.pp() = "${this.getContainingContract().name}.${this.soliditySignature ?: this.name}"

        fun mergeCodes(first: CoreTACProgram, second: CoreTACProgram) = TACProgramCombiners.mergeCodes(first, second)
    }

    /**
     * Records the variable used in skolemization for the query.
     */
    data class IndexHolder(val indexSym: TACSymbol) : TransformableSymEntity<IndexHolder> {
        override fun transformSymbols(f: (TACSymbol) -> TACSymbol): IndexHolder {
            return IndexHolder(f(indexSym))
        }
        companion object {
            val META_KEY = MetaKey<IndexHolder>("equiv.trace.read.marker")

        }
    }

    internal sealed interface SatInterpretation {
        /**
         * Conservatively take the failure result in [overallResult]. Used when CEX refinement fails, AKA
         * we "gave up" trying to get a more precise answer.
         */
        data class GaveUp(val reason: String, val overallResult: RuleCheckResult) : SatInterpretation

        /**
         * Take the SAT result from the solver indicates a real issue, as described by the fields
         * of this class.
         */
        data class RealCounterExample(
            /**
             * The overall check result to be used for the query
             */
            val ruleCheckResult: RuleCheckResult,
            /**
             * Explanation of the concrete inputs used in the trace
             */
            val inputExplanation: InputExplanation,
            /**
             * Any events (logs/external calls) that occurred before the divergence
             */
            val priorEventsA: List<IEvent>?,
            val priorEventsB: List<IEvent>?,
            /**
             * The divergence itself
             */
            val diffExplanation: MismatchExplanation
        ) : SatInterpretation
    }

    private interface OutputF : AutoCloseable {
        fun print(s: String)

        operator fun invoke(s: String) = print(s)
    }

    private suspend fun checkEquivalence() : RuleCheckResult {
        val programA = object : CallableProgram<MethodMarker.METHODA, TACMethod> {
            override val program: CoreTACProgram
                get() = methodA.code as CoreTACProgram
            override val callingInfo: TACMethod
                get() = methodA

            override fun pp(): String {
                return methodA.pp()
            }
        }

        val programB = object : CallableProgram<MethodMarker.METHODB, TACMethod> {
            override val program: CoreTACProgram
                get() = methodB.code as CoreTACProgram
            override val callingInfo: TACMethod
                get() = methodB

            override fun pp(): String {
                return methodB.pp()
            }
        }

        val equivalence = object : FullEquivalence<TACMethod, SatInterpretation>(
            programB = programB,
            programA = programA,
            rule = equivalenceRule,
            queryContext = queryContext,
            ruleGenerator = CalldataRuleGenerator(
                queryContext = queryContext
            )
        ) {
            override fun explainTraceCEX(
                targets: BufferTraceInstrumentation.TraceTargets,
                a: TraceEvent<MethodMarker.METHODA>?,
                b: TraceEvent<MethodMarker.METHODB>?,
                res: TraceEquivalenceChecker.CheckResult<TACMethod>
            ): SatInterpretation {
                return CounterExampleExplainer(
                    queryContext = queryContext,
                    instLevels = targets,
                    checkResult = res
                ).explainCounterExample(a, b)
            }

            override fun explainStorageCEX(res: TraceEquivalenceChecker.CheckResult<TACMethod>, f: StorageStateDiff<TACMethod>): SatInterpretation {
                return CounterExampleExplainer(
                    queryContext = queryContext,
                    instLevels = BufferTraceInstrumentation.TraceTargets.Results,
                    checkResult = res
                ).explainStorageCEX(f.failingAssert)
            }

            suspend fun check() = equivalenceLoop(StandardExplorationStrategy.entry())
        }.check()

        return when(equivalence) {
            is FullEquivalence.EquivalenceResult.ExplainedCounterExample -> {
                handleSatInterpretation(equivalence.ex)
            }
            is FullEquivalence.EquivalenceResult.GaveUp -> {
                logger.info {
                    "Giving up, taking the sat result: ${equivalence.message}"
                }
                equivalence.check.ruleResult
            }
            is FullEquivalence.EquivalenceResult.Verified -> {
                equivalence.check.ruleResult
            }
        }
    }

    /**
     * Handle the sat interpretation.
     */
    @Suppress("ForbiddenMethodCall") // allow println for now
    private fun handleSatInterpretation(
        interp: SatInterpretation
    ) : RuleCheckResult {
        return when(interp) {
            is SatInterpretation.GaveUp -> {
                logger.info {
                    "Giving up, taking the sat result ${interp.reason}"
                }
                interp.overallResult
            }
            is SatInterpretation.RealCounterExample -> {
                /**
                 * "Pretty" print the report to the console, and generate our temporary report
                 */
                val reportContent = this.javaClass.classLoader.getResourceAsStream("EquivalenceReport.html")?.bufferedReader()?.readText()
                check(reportContent == null || reportContent.indexOf("ADD_PARAMS_HERE") == reportContent.lastIndexOf("ADD_PARAMS_HERE")) {
                    "Malformed report content"
                }
                fun contextObj(label: String, tooltip: String?, value: String?) = buildJsonObject {
                    put("key", label)
                    put("tooltip", tooltip)
                    put("value", value)
                }

                fun IEvent.toJson() = buildJsonObject {
                    when(this@toJson) {
                        is IncompleteEvent -> {
                            put("sort", "Error in trace extraction")
                            put("warning", msg)
                        }
                        is EventWithData -> {
                            put("sort", when(sort) {
                                BufferTraceInstrumentation.TraceEventSort.REVERT -> "Function Revert"
                                BufferTraceInstrumentation.TraceEventSort.RETURN -> "Function Return"
                                BufferTraceInstrumentation.TraceEventSort.LOG -> "Log Emit"
                                BufferTraceInstrumentation.TraceEventSort.EXTERNAL_CALL -> "External Call"
                                BufferTraceInstrumentation.TraceEventSort.INTERNAL_SUMMARY_CALL -> "Internal Call"
                                BufferTraceInstrumentation.TraceEventSort.RESULT -> "Result"
                            })
                            if(sort.showBuffer) {
                                val r = bufferRepr?.asBufferRepr("")
                                put("rawBuffer", r)
                            }
                            put("context", buildJsonArray {
                                for(c in params) {
                                    val o = contextObj(c.label.displayLabel, c.label.description, c.value)
                                    add(o)
                                }
                            })
                        }

                        is Elaboration -> {
                            put("detail", what)
                        }
                        SyncEvent -> {
                            put("sync", true)
                        }
                    }
                }

                fun InputExplanation.toJson() = buildJsonObject {
                    put("caller", sender?.let {
                        "0x${it.toString(16)}"
                    })
                    put("value", messageValue?.toString())
                    val argList = buildJsonArray {
                        for(a in argumentFormatting) {
                            val obj = buildJsonObject {
                                put("name", a.name)
                                put("altName", a.altName)
                                put("value", a.value)
                            }
                            add(obj)
                        }
                    }
                    put("arguments", argList)
                }

                fun IEvent.toJsonString() = Json.encodeToString(JsonObject.serializer(), this.toJson())
                if(reportContent != null) {
                    val prefixStrA = interp.priorEventsA.orEmpty().let { prior ->
                        buildJsonArray {
                            for(p in prior) {
                                add(p.toJson())
                            }
                        }
                    }.let {
                        Json.encodeToString(JsonArray.serializer(), it)
                    }

                    val prefixStrB = interp.priorEventsB.orEmpty().let { priorB ->
                        buildJsonArray {
                            for(p in priorB) {
                                add(p.toJson())
                            }
                        }
                    }

                    val contractAStr = Json.encodeToString(String.serializer(), methodA.getContainingContract().name)
                    val contractBStr = Json.encodeToString(String.serializer(), methodB.getContainingContract().name)

                    val (eventAStr, eventBStr) = when(interp.diffExplanation) {
                        is MismatchExplanation.DifferentEvents -> {
                            interp.diffExplanation.eventInA.toJsonString() to interp.diffExplanation.eventInB.toJsonString()
                        }
                        is MismatchExplanation.MissingInA -> {
                            null to interp.diffExplanation.eventInB.toJsonString()
                        }
                        is MismatchExplanation.MissingInB -> {
                            interp.diffExplanation.eventInA.toJsonString() to null
                        }
                        is MismatchExplanation.StorageExplanation -> {
                            val methodADiff = interp.diffExplanation.contractAValue
                            val methodBDiff = interp.diffExplanation.contractBValue

                            fun String.toJsonString() = buildJsonObject {
                                put("sort", "Storage Mismatch")
                                put("context", buildJsonArray {
                                    add(contextObj(
                                        "Storage slot", "Internal prover representation of the storage slot; may or may not match an actual storage slot", interp.diffExplanation.slot
                                    ))
                                    add(contextObj(
                                        "Value after execution", "Value in the slot after execution", this@toJsonString
                                    ))
                                })
                            }.let {
                                Json.encodeToString(JsonObject.serializer(), it)
                            }

                            val storage1 = methodADiff.toJsonString()
                            val storage2 = methodBDiff.toJsonString()

                            storage1 to storage2
                        }
                    }
                    val inputsStr = Json.encodeToString(JsonObject.serializer(), interp.inputExplanation.toJson())

                    val initString = """
                        window.contractA = $contractAStr;
                        window.contractB = $contractBStr;

                        window.input = $inputsStr;
                        window.prefixA = $prefixStrA;
                        window.prefixB = $prefixStrB
                        window.eventA = $eventAStr;
                        window.eventB = $eventBStr;
                    """.trimIndent()
                    ArtifactManagerFactory().mainReportsDir.let {
                        File(it, "EquivalenceReport.html")
                    }.outputStream().bufferedWriter().use { out ->
                        out.write(reportContent.replace("ADD_PARAMS_HERE", initString))
                    }
                }

                val outputFunction = Config.EquivalenceTraceFile.get().takeIf { f -> f.isNotBlank() }?.let { f ->
                    val o = BufferedOutputStream(FileOutputStream(File(f))).bufferedWriter()
                    object : OutputF {
                        override fun close() {
                            o.close()
                        }

                        override fun print(s: String) {
                            o.write(s)
                        }
                    };
                } ?: object : OutputF {
                    override fun print(s: String) {
                        println(s)
                    }

                    override fun close() {
                    }
                }
                if(interp.priorEventsA != null) {
                    outputFunction("There were ${interp.priorEventsA.size} call(s) prior to this event:")
                    interp.priorEventsA.forEach {
                        outputFunction(it.prettyPrint())
                    }
                } else {
                    outputFunction("Couldn't extract prior trace information")
                }

                val msg = when(interp.diffExplanation) {
                    is MismatchExplanation.DifferentEvents -> {
                        "The methods performed different actions.\n" +
                            "In ${methodA.pp()}:\n${interp.diffExplanation.eventInA.prettyPrint()}\n" +
                            "In ${methodB.pp()}:\n${interp.diffExplanation.eventInB.prettyPrint()}"

                    }
                    is MismatchExplanation.MissingInA -> {
                        "${methodB.pp()} included an event *missing* in ${methodA.pp()}:\n${interp.diffExplanation.eventInB.prettyPrint()}"
                    }
                    is MismatchExplanation.MissingInB -> {
                        "${methodA.pp()} included an event *missing* in ${methodB.pp()}:\n${interp.diffExplanation.eventInA.prettyPrint()}"
                    }
                    is MismatchExplanation.StorageExplanation -> {
                        "The executions left storage in conflicting states:\n" +
                            "The witness storage slot was ${interp.diffExplanation.slot}:" +
                            "\t! ${methodA.getContainingContract().name}: ${interp.diffExplanation.contractAValue}\n" +
                            "\t! ${methodB.getContainingContract().name}: ${interp.diffExplanation.contractBValue}"
                    }
                }
                outputFunction(msg)
                outputFunction.close()
                interp.ruleCheckResult
            }
        }
    }

    /**
     * Class meant to encapsulate/coordinate the fine-tuning of instrumentation w.r.t. precision. Records the bounded
     * precision windows to be used for each method, and the pairwise agreements found via pairwise buffer equivalence.
     *
     * Considered preferable to passing around the component maps and hoping everyone uses them correctly.
     */
    class PairwiseProofManager {
        /**
         * Used to record the (reach-var, value) pairs used in the overriding of sites in [methodB]
         */
        @KSerializable
        @Treapable
        data class PairwiseAgreement(
            val flagA: TACSymbol.Var,
            val agreedValue: Int
        ) : AmbiSerializable

        companion object {
            private fun PairwiseAgreement.toGate() =
                flagA.asSym() to this.agreedValue.asTACExpr

            private fun List<PairwiseAgreement>.toGates() = this.map {
                it.toGate()
            }
        }

        /**
         * Records the overriding value [manual] for a site in [methodA] and the variable [reachedVar] which
         * is used to record when that site is reached.
         */
        @KSerializable
        data class SiteAOverride(
            val manual: Int,
            val reachedVar: TACSymbol.Var
        ) : AmbiSerializable {
            fun toSiteOverride() = BufferTraceInstrumentation.TraceOverrideSpec.CompleteOverride(manual.asTACExpr)
        }

        /**
         * Memento for serializing the current state. For debugging/dev sanity only
         */
        @KSerializable
        data class Memento(
            val aSiteOverride: Map<CmdPointer, SiteAOverride>,
            val bSitePartners: Map<CmdPointer, List<PairwiseAgreement>>,
            val nextSite: Int,

            val bOverrides: Map<CmdPointer, Int>,
            val aOverrides: Map<CmdPointer, Int>,

            val methodAReached: Map<CmdPointer, TACSymbol.Var>
        )

        private val aSiteOverrides: MutableMap<CmdPointer, SiteAOverride> = mutableMapOf()

        private val bSitePartners : MutableMap<CmdPointer, MutableList<PairwiseAgreement>> = mutableMapOf()

        private val aMloadOverrides = mutableMapOf<CmdPointer, Int>()

        private val bMLoadOverrides = mutableMapOf<CmdPointer, Int>()

        /**
         * Monotonically increasing number used to invent fresh numbers to use
         * for the override of the buffer identity at sites in A
         */
        private val siteIdentifier = AtomicInteger(0)

        fun isSummarized(aSite: CmdPointer) = aSite in aSiteOverrides

        /**
         * For [aSite] to be summarized, making buffer computation easier.
         */
        fun forceSummarized(aSite: CmdPointer) {
            aSiteOverrides.getOrPut(aSite) {
                val reached = getMethodAReach(aSite)
                SiteAOverride(siteIdentifier.getAndIncrement(), reachedVar = reached)
            }
        }

        // none of these are proper merge functions, but who cares
        fun loadMemento(m: Memento) {
            while(true) {
                val curr = siteIdentifier.get()
                val nextSite = max(m.nextSite, curr)
                if(siteIdentifier.compareAndSet(curr, nextSite)) {
                    break
                }
            }
            aSiteOverrides.putAll(m.aSiteOverride)
            for((k, v) in m.bSitePartners) {
                bSitePartners.getOrPut(k) { mutableListOf() }.addAll(v)
            }
            aMloadOverrides.putAll(m.aOverrides)
            bMLoadOverrides.putAll(m.bOverrides)
            methodAReached.putAll(m.methodAReached)
        }

        private val methodAReached = mutableMapOf<CmdPointer, TACSymbol.Var>()

        private fun getMethodReachVar(m: MutableMap<CmdPointer, TACSymbol.Var>, where: CmdPointer) : TACSymbol.Var = m.getOrPut(where) {
            TACSymbol.Var("siteReachedMarker!${siteIdentifier.getAndIncrement()}", Tag.Bool, NBId.ROOT_CALL_ID, MetaMap(TACMeta.NO_CALLINDEX))
        }

        private fun getMethodAReach(v: CmdPointer) = getMethodReachVar(methodAReached, v)

        fun save(): Memento {
            return Memento(aSiteOverrides, bSitePartners, siteIdentifier.get(), aMloadOverrides, bMLoadOverrides, methodAReached)
        }

        /**
         * Record that the buffers used at long reads [aSite] in [methodA] and [bSite] in [methodB]
         * must be the same, and update the buffer identity overrides accordingly
         */
        fun registerPairwiseProof(aSite: CmdPointer, bSite: CmdPointer) {

            val reach = aSiteOverrides.getOrPut(aSite) {
                val aReachedVar = getMethodAReach(aSite)
                val agreedConstant = siteIdentifier.getAndIncrement()
                SiteAOverride(manual = agreedConstant, reachedVar = aReachedVar)
            }
            bSitePartners.getOrPut(bSite, ::mutableListOf).add(PairwiseAgreement(
                agreedValue = reach.manual,
                flagA = reach.reachedVar,
            ))
        }

        /**
         * Get the buffer identity overrides for [methodA]
         */
        fun getAOverrides() : TaggedMap<MethodMarker.METHODA, CmdPointer, BufferTraceInstrumentation.TraceOverrideSpec> = aSiteOverrides.mapValues {
            it.value.toSiteOverride()
        }

        /**
         * Get the buffer identity overrides for [methodB]
         */
        fun getBOverrides() : TaggedMap<MethodMarker.METHODB, CmdPointer, BufferTraceInstrumentation.TraceOverrideSpec> = bSitePartners.mapValues { (_, partners) ->
            partners.toGates().let(BufferTraceInstrumentation.TraceOverrideSpec::ConditionalOverrides)
        }

        fun getAUseSiteControl(): TaggedMap<MethodMarker.METHODA, CmdPointer, BufferTraceInstrumentation.UseSiteControl> = methodAReached.mapValues {
            BufferTraceInstrumentation.UseSiteControl(
                trackBufferContents = false,
                traceReached = it.value
            )
        }

        fun getBUseSiteControl(): TaggedMap<MethodMarker.METHODB, CmdPointer, BufferTraceInstrumentation.UseSiteControl> = mapOf()

        /**
         * Get the bounded precision windows (expressed as a map from mload locations to window size) for [methodA]
         */
        fun getAMloadOverrides() : TaggedMap<MethodMarker.METHODA, CmdPointer, Int> = aMloadOverrides

        /**
         * Get the bounded precision windows (expressed as a map from mload locations to window size) for [methodB]
         */
        fun getBMloadOverrides() : TaggedMap<MethodMarker.METHODB, CmdPointer, Int> = bMLoadOverrides

        /**
         * Register that the mload at [where] in [methodB] should use a bounded precision window
         * of size [depth]
         */
        fun registerBLoadOverride(where: CmdPointer, depth: Int) : Boolean {
            return registerLoadOverride(bMLoadOverrides, where, depth)
        }

        /**
         * Register that the mload at [where] in [methodA] should use a bounded precision window
         * of size [depth]
         */
        fun registerALoadOverride(where: CmdPointer, depth: Int) : Boolean {
            return registerLoadOverride(aMloadOverrides, where, depth)
        }

        private fun registerLoadOverride(
            overrideMap: MutableMap<CmdPointer, Int>,
            where: CmdPointer,
            depth: Int
        ): Boolean {
            if (where !in overrideMap || overrideMap[where]!! < depth) {
                overrideMap[where] = depth
                return true
            }
            return false
        }
    }


    suspend fun handleEquivalence(): List<RuleCheckResult> {
        return listOf(checkEquivalence())
    }

}
