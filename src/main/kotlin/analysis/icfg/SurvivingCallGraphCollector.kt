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

package analysis.icfg

import analysis.ip.INTERNAL_FUNC_START
import analysis.maybeAnnotation
import config.OUTPUT_NAME_DELIMITER
import datastructures.stdcollections.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray
import log.ArtifactManagerFactory
import log.Logger
import log.LoggerTypes
import log.StaticArtifactLocation
import log.writeArtifact
import report.dumps.getCallIdToCaller
import utils.ArtifactFileUtils
import utils.Range
import utils.mapToSet
import vc.data.CoreTACProgram

private val logger = Logger(LoggerTypes.OPTIMIZE)

/**
 * A procedure surviving the pipeline. [procId] is `ProcedureId.toString()` = "Contract.function", [callId]
 * the procedure's call id, [range] its source range (if known).
 */
@Serializable
private data class SurvivingProc(val callId: Int, val procId: String, val range: Range.Range? = null)

/** An edge of the surviving call graph, as a pair of `callId`s. */
@Serializable
private data class CallIdEdge(val caller: Int, val callee: Int)

/**
 * An INTERNAL (library/private/free) function whose body survives in the TAC. Internal functions are
 * inlined into their caller and get no [SurvivingProc] of their own, so they are collected separately from
 * the `INTERNAL_FUNC_START` annotations. [name] is fully qualified ("Contract.function"); [summarizable]
 * is the prover's own check (all args scalar) — a hint for whether internal summarization applies.
 */
@Serializable
private data class InternalFn(val name: String, val summarizable: Boolean)

/**
 * Per-rule dump of the functions/procedures that SURVIVE a rule's TAC pipeline, plus the call graph among
 * them.
 *
 * `tac.procedures` is NOT pruned by the optimizer (it is fixed at inlining), so "surviving" = a `ProcedureId`
 * whose `callId` still owns at least one block in the (pruned) program; [procedures] holds those.
 *
 * [callGraph] edges are `callId` pairs (`getCallIdToCaller` over the pruned blockgraph, so already restricted
 * to survivors).
 */
@Serializable
private data class SurvivingCallGraph(
    val rule: String,
    val phase: String,
    val procedures: List<SurvivingProc>,
    val internalFunctions: List<InternalFn>,
    val callGraph: List<CallIdEdge>,
)

object SurvivingCallGraphCollector {
    private val json = Json { prettyPrint = true }

    /**
     * Manifest `ruleIdentifier (== tac.name) -> [artifact file names]`, written progressively as
     * `survivingCallGraph_map.json` (same shape as `unsat_core_map.json`; consumers read the map then fetch
     * each named file). [emit] runs per-rule CONCURRENTLY, so the map update + its write are serialized —
     * NOT with a new lock, but on the EXISTING `ArtifactManagerFactory` monitor that the surrounding TAC
     * dumps (`dumpPostOptimized`) already use, so no new locking mechanism / no new deadlock surface.
     */
    private val manifest = linkedMapOf<String, MutableList<String>>()

    private fun recordInManifest(ruleId: String, fileName: String) {
        synchronized(ArtifactManagerFactory) {
            manifest.getOrPut(ruleId) { mutableListOf() }.add(fileName)
            val mapJson = buildJsonObject {
                manifest.forEachEntry { (rid, files) -> putJsonArray(rid) { files.forEach { add(it) } } }
            }.toString()
            ArtifactManagerFactory().writeArtifact(
                name = "survivingCallGraph_map.json",
                location = StaticArtifactLocation.Reports,
                overwrite = true,
            ) { mapJson }
        }
    }

    /**
     * Emit `survivingCallGraph-<tac.name>-<phase>.json` for [tac]. The key is the TAC program's `name`
     * (the same identity the pre/post-optimize `.tac` dumps use). Callers gate this on
     * [config.Config.DumpSurvivingCallGraph]. Failures are logged and swallowed — diagnostic artifact, never
     * part of verification.
     */
    fun emit(tac: CoreTACProgram, phase: String) {
        try {
            val rule = tac.name
            val liveCallIds = tac.code.keys.mapToSet { it.calleeIdx }
            val procedures = tac.procedures
                .filter { it.callId in liveCallIds }
                .map { SurvivingProc(it.callId, it.procedureId.toString(), it.procedureId.range) }
                .sortedWith(compareBy({ it.procId }, { it.callId }))
            val callGraph = getCallIdToCaller(tac.blockgraph)
                .filter { (callee, caller) -> callee in liveCallIds && caller in liveCallIds }
                .map { (callee, caller) -> CallIdEdge(caller = caller, callee = callee) }
                .sortedWith(compareBy({ it.caller }, { it.callee }))
            // Internal (library/private/free) functions are inlined and get no SurvivingProc — collect the
            // ones whose body survives from their INTERNAL_FUNC_START annotations.
            val internalFunctions = tac.analysisCache.graph.commands
                .mapNotNull { it.maybeAnnotation(INTERNAL_FUNC_START) }
                .map { InternalFn(it.methodSignature.prettyPrintFullyQualifiedName(), it.isSummarizable()) }
                .distinct()
                .sortedBy { it.name }
                .toList()
            logger.info {
                "Emitting surviving call graph ($phase) for rule $rule: ${procedures.size} procs, " +
                    "${internalFunctions.size} internal fns"
            }
            // Name mirrors the per-rule dumps (e.g. UnsatCoreAnalysis): <Prefix><DELIM><tac.name><DELIM>
            // <phase>. `tac.name` (= ruleIdentifier.toString()) is UNIQUE PER PARAMETRIC METHOD, so method
            // instances don't collide; writeArtifact sanitizes the name for the filesystem.
            val artifactName = "SurvivingCallGraph$OUTPUT_NAME_DELIMITER$rule$OUTPUT_NAME_DELIMITER$phase.json"
            ArtifactManagerFactory().writeArtifact(
                name = artifactName,
                location = StaticArtifactLocation.Reports,
                overwrite = true,
            ) {
                json.encodeToString(SurvivingCallGraph(rule, phase, procedures, internalFunctions, callGraph))
            }
            // Record the ACTUAL (sanitized) file name a consumer will fetch, like unsat_core_map.json.
            recordInManifest(rule, ArtifactFileUtils.sanitizePath(artifactName))
        } catch (t: Throwable) {
            logger.warn(t) { "Failed to emit surviving call graph for rule ${tac.name} ($phase) — continuing" }
        }
    }
}
