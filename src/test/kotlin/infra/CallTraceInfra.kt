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

package infra

import analysis.LTACCmd
import bridge.NamedContractIdentifier
import datastructures.NonEmptyList
import instrumentation.transformers.InitialCodeInstrumentation
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.*
import log.RuleTestArtifactKey.TestArtifactKind
import report.DummyLiveStatsReporter
import report.callresolution.CallResolutionTable
import report.calltrace.CallInstance
import report.calltrace.CallTrace
import rules.CompiledRule
import rules.IsFromCache
import rules.RuleCheckResult.Single.RuleCheckInfo
import scene.*
import spec.rules.CVLSingleRule
import utils.*
import utils.CollectingResult.Companion.map
import vc.data.CoreTACProgram
import verifier.AbstractTACChecker
import verifier.TACVerifier
import verifier.Verifier
import java.nio.file.Path
import java.util.*
import java.util.function.Predicate
import kotlin.io.path.*


/**
 * This object provides functions to analyze callInstance hierarchies, apply attributes, and find paths.
 */
object CallTraceInfra {
    private val json =
        Json {
            allowStructuredMapKeys = true; prettyPrint = true; serializersModule =
            CVLSerializerModules.modules; ignoreUnknownKeys = true
        }

    /**
     * Data class representing the loaded artifacts for a [CallTrace] test.
     *
     * @property verifierResult The [Verifier.JoinedResult.Failure] containing [CoreTACProgram] and [AbstractTACChecker.ExampleInfo]s.
     * @property rule The [CVLSingleRule] representing the rule to test.
     * @property expectedViolateAssert The expected violated assertion.
     * @property expectedJson The expected JSON object of the generated [CallTrace].
     */
    data class LoadedCallTraceArtifacts(
        val verifierResult: Verifier.JoinedResult.Failure,
        val rule: CVLSingleRule,
        val expectedViolateAssert: LTACCmd,
        val expectedJson: JsonObject
    ) {
        fun toExamplesData(scene: IScene): RuleCheckInfo.WithExamplesData {
            // Process and prepare the necessary data for the CallTrace
            val origProWithAssertIdMeta =
                CompiledRule.addAssertIDMetaToAsserts(
                    this.verifierResult.simpleSimpleSSATAC,
                    this.rule
                )

            val callResolutionTableFactory = CallResolutionTable.Factory(origProWithAssertIdMeta, scene, this.rule)

            return RuleCheckInfo.WithExamplesData(
                scene,
                this.rule,
                this.verifierResult,
                origProWithAssertIdMeta,
                callResolutionTableFactory,
                isOptimizedRuleFromCache = IsFromCache.DISABLED,
                isSolverResultFromCache = IsFromCache.DISABLED
            )
        }
    }

    /**
     * Retrieves the [RuleCheckInfo.WithExamplesData.CounterExample] instance from the files in [verifierResultPath].
     *
     * For that, the [Scene] is reconstructed from a `.conf` file (and the `.sol` and `.spec` files it refers to), and
     * certain previously serialized artifacts (including a tac program, see [LoadedCallTraceArtifacts], for details)
     * are deserialized from the given directory.
     *
     * @param confPath The path to the configuration.
     * @param verifierResultPath The path to the verifier result.
     * @return A CounterExample.
     */
    fun getCounterExampleFromSerialized(
        confPath: Path,
        verifierResultPath: Path
    ): RuleCheckInfo.WithExamplesData.CounterExample {
        val examplesData = CertoraBuild.inTempDir(CertoraBuildKind.EVMBuild(), confPath).useWithBuildDir { build ->
            val scene = build.getScene(query = null)
            loadVerifierResult(verifierResultPath).toExamplesData(scene)
        }

        val singleCounterExample = examplesData.examples.singleOrNull()

        return singleCounterExample
            ?: error("Expected to have a single example but got ${examplesData.examples.size}")
    }

    fun runConfAndGetCounterExample(
        confPath: Path,
        specFilename: Path,
        ruleName: String,
        primaryContract: String,
        parametricMethodNames: List<String> = emptyList(),
        buildOptions: List<String> = emptyList()
    ): RuleCheckInfo.WithExamplesData.CounterExample {
        val cvlText = specFilename.readText()

        val flow = CVLFlow()

        val specSource = CVLSpecTextSource(
            cvlText,
            NamedContractIdentifier(
                primaryContract
            )
        )
        return CertoraBuild.inTempDir(CertoraBuildKind.EVMBuild(buildOptions), confPath).useWithBuildDir { build ->

            val certoraBuilderContractSource = build.contractSource

            val pqAndS = specSource.getQuery(
                certoraBuilderContractSource.instances(),
                SceneFactory.getCVLScene(certoraBuilderContractSource)
            ).map { query ->
                build.getScene(query) to query
            }
            val (scene, iCheckableTACS) = flow.transformResultsToTACs(pqAndS)

            val relevantTACs = iCheckableTACS.filter { it.subRule.ruleIdentifier.root().displayName == ruleName }
            check(!iCheckableTACS.isEmpty()) { "rule `$ruleName` not found in resulting TACs, does this rule exist?" }

            val checkableTAC = if (parametricMethodNames.isEmpty()) {
                relevantTACs
                    .singleOrNull()
                    ?: error("got unexpected number of TACs (${relevantTACs.size}) for rule: $ruleName. " +
                            "maybe you need to specify method instantiation names?")
            } else {
                relevantTACs
                    .filter { it.methodParameterInstantiation.size == parametricMethodNames.size }
                    .find {
                        /**
                         * XXX: this relies on
                         * 1. the method parameters being inserted to the
                         *    underlying map in declaration order
                         * 2. the underlying map being ordered
                         *
                         * while ordered maps are the default with kotlin maps,
                         * neither of these are enforced, and so this may break.
                         * instead, we should change [vc.data.MethodParameterInstantiation]
                         * to use lists of pairs instead of sets, since instantiations are ordered.
                         */
                        val instMethods = it.methodParameterInstantiation.values

                        instMethods.zip(parametricMethodNames).all { (method, expectedName) -> method.toString() == expectedName }
                    }
                    ?: error(
                        "no exact match for parametric method instantiations. " +
                                "double-check how many methods are expected here, and that the names are exactly right"
                    )
            }

            val rule = checkableTAC.subRule

            val withAppliedSummaries = InitialCodeInstrumentation.applySummariesAndGhostHooksAndAxiomsTransformations(checkableTAC.tac, scene, (pqAndS.force().second as ProverQuery.CVLQuery.Single).cvl, rule, null)

            val verifierResult = runBlocking {
                Verifier.JoinedResult(TACVerifier.verify(scene.toIdentifiers(), withAppliedSummaries, DummyLiveStatsReporter, rule))
            } as? Verifier.JoinedResult.Failure
                ?: error("expecting a JoinedResult.Failure, since we're testing call traces")

            val origProWithAssertIdMeta =
                CompiledRule.addAssertIDMetaToAsserts(
                    verifierResult.simpleSimpleSSATAC,
                    rule,
                )

            val callResolutionTableFactory = CallResolutionTable.Factory(origProWithAssertIdMeta, scene, rule)

            val wed = RuleCheckInfo.WithExamplesData(
                scene,
                rule,
                verifierResult,
                origProWithAssertIdMeta,
                callResolutionTableFactory,
                isOptimizedRuleFromCache = IsFromCache.DISABLED,
                isSolverResultFromCache = IsFromCache.DISABLED
            )

            wed.examples.head
        }
    }

    fun runConfAndGetCallTrace(
        confPath: Path,
        specFilename: Path,
        ruleName: String,
        primaryContract: String,
        parametricMethodNames: List<String> = emptyList(),
        buildOptions: List<String> = emptyList()
    ): CallTrace {
        val counterExample = runConfAndGetCounterExample(confPath, specFilename, ruleName, primaryContract, parametricMethodNames, buildOptions)
        return counterExample.callTrace ?: error("failed to get call trace, got: $counterExample")
    }

    /**
     * This just thinly wraps [getCounterExampleFromSerialized] look there for details. (tldr: reads a bunch of files on
     * disk to retrieve a [CounterExample] from a previous run.
     *
     * This reads one part of the serialized files we committed to `Test/CallTrace/...`, the other is
     * read by [getExpectedAssertAndJson].
     * Note that both use [loadVerifierResult], they just take different things out of the result ..
     *
     * @param confPath The path to the configuration.
     * @param verifierResultPath The path to the verifier result.
     * @return The [CallTrace]
     */
    fun getCallTraceFromSerialized(confPath: Path, verifierResultPath: Path): CallTrace {
        val counterExample = getCounterExampleFromSerialized(confPath, verifierResultPath)
        return counterExample.callTrace
            ?: error("Expected to have a single example (rule ${counterExample.rule.declarationId})")
    }

    /**
     * (seems like) This class loads the test expectations from disk from a given directory belonging to some test.
     * These expectations consist of a specific `assert` command, and the "...expected....json" file. The latter being
     * a serialization of the call trace itself (a nested sequence of [CallInstance]s, basically). (I'm not sure yet
     * about the exact serialization format.) (Note that his "expected json" is not the same as the
     * `expected<name>.json`s that we use for our tests in `Test`.)
     *
     * This reads one part of the serialized files we committed to `Test/CallTrace/...`, the other is
     * read by [getCallTraceFromSerialized].
     * Note that both use [loadVerifierResult], they just take different things out of the result ..
     *
     *
     * @param verifierResultPath The path to the verifier result.
     * @return The expectedViolatedAssert [LTACCmd], and expectedJson [JsonObject].
     */
    fun getExpectedAssertAndJson(verifierResultPath: Path): Pair<LTACCmd, JsonObject> {
        val callTraceArtifacts = loadVerifierResult(verifierResultPath)
        return Pair(
            callTraceArtifacts.expectedViolateAssert,
            callTraceArtifacts.expectedJson,
        )
    }

    /**
     * Finds a file with the specified suffix in the given directory and its subdirectories.
     *
     * @param directory The path to the directory to search.
     * @param suffix The suffix of the file to find.
     * @return The single matching file.
     * @throws NoSuchElementException If no file with the specified suffix is found.
     * @throws IllegalStateException If multiple files with the specified suffix are found.
     */
    @OptIn(ExperimentalPathApi::class)
    private fun findFileWithSuffix(directory: Path, suffix: String): Path {
        // List of files with the specified suffix in the given directory and its subdirectories
        val matchingFiles = directory.walk()
            .filter { it.isRegularFile() && it.name.endsWith(suffix, ignoreCase = true) }
            .toList()

        // Handle cases based on the number of matching files
        when {
            matchingFiles.size == 1 -> return matchingFiles[0]
            matchingFiles.isEmpty() -> throw NoSuchElementException("No file with suffix $suffix found in $directory")
            else -> throw IllegalStateException("Multiple files with suffix $suffix found in $directory: $matchingFiles")
        }
    }

    /**
     * Loads the verifier result from the given [verifierResultPath].
     *
     * @param verifierResultPath The verifier result path.
     * @return [LoadedCallTraceArtifacts], a struct containing the loaded artifacts.
     */
    @OptIn(ExperimentalSerializationApi::class)
    private fun loadVerifierResult(verifierResultPath: Path): LoadedCallTraceArtifacts {
        // Create full file paths for the required artifacts
        val resultDirPath = workingDirectory().resolve(verifierResultPath)

        val coreTACProgramFile =
            findFileWithSuffix(resultDirPath, TestArtifactKind.CORE_TAC_PROGRAM.suffixFileName)
        val examplesInfoFile = findFileWithSuffix(resultDirPath, TestArtifactKind.EXAMPLES_INFO.suffixFileName)
        val singleRuleFile = findFileWithSuffix(resultDirPath, TestArtifactKind.SINGLE_RULE.suffixFileName)
        val expectedViolatedAssertFile =
            findFileWithSuffix(resultDirPath, TestArtifactKind.VIOLATED_ASSERT.suffixFileName)
        val expectedJsonFile = findFileWithSuffix(resultDirPath, TestArtifactKind.EXPECTED.suffixFileName)

        // Load data from various files using kotlinx.serialization
        val examplesInfo = json.decodeFromStream<NonEmptyList<AbstractTACChecker.ExampleInfo>>(
            examplesInfoFile.inputStream()
        )
        val coreTACProgram =
            CoreTACProgram.fromStream(coreTACProgramFile.inputStream(), coreTACProgramFile.name)
        val singleRule = json.decodeFromStream<CVLSingleRule>(singleRuleFile.inputStream())
        val expectedViolatedAssert = json.decodeFromStream<LTACCmd>(
            expectedViolatedAssertFile.inputStream()
        )
        val expectedJson = json.decodeFromStream<JsonObject>(expectedJsonFile.inputStream())

        // Create and return a LoadedCallTraceArtifacts object
        return LoadedCallTraceArtifacts(
            Verifier.JoinedResult.Failure(coreTACProgram, null, examplesInfo),
            singleRule,
            expectedViolatedAssert,
            expectedJson
        )
    }


    /**
     * Compares two JSON elements and generates a list of differences.
     *
     * @param element1 The first JSON element to compare.
     * @param element2 The second JSON element to compare.
     * @return A list of strings describing the differences between the two JSON elements.
     *
     *
     * Note (alex): I'm sceptical of this approach -- if we serialized some object, wouldn't it be better to restore that
     *    object from the json and then have according comparison logic?
     *   Update: I'm in the process of softening the above position ... checking at the json level is most probably good
     *     in some cases; json is what the front end will see after all, and it does give us tests at the "whole output"
     *     granularity. Looking at the differences one by one when having made an update does seem useful ..
     *     Maybe we can make it more comfortable though.
     */
    fun compareJson(element1: JsonElement, element2: JsonElement): List<String> {
        val diff = mutableListOf<String>()

        when {
            element1 is JsonObject && element2 is JsonObject -> {
                // Compare keys and values in the first JSON object with the second JSON object
                for ((key, value1) in element1.entries) {
                    if (!element2.contains(key)) {
                        diff.add("$key, Key missing in json2")
                    } else {
                        val value2 = element2[key]!!
                        if (value1 != value2) {
                            diff.addAll(compareJson(value1, value2))
                        }
                    }
                }

                // Check for keys in the second JSON object that are missing in the first JSON object
                for (key in element2.keys) {
                    if (!element1.contains(key)) {
                        diff.add("$key Key missing in json1")
                    }
                }
            }

            element1 is JsonArray && element2 is JsonArray -> {
                if (element1.size != element2.size) {
                    diff.add("Array size mismatch: ${element1.size} vs ${element2.size}")
                } else {
                    element1.zip(element2).forEach { (e1, e2) ->
                        val itemDiff = compareJson(e1, e2)
                        diff.addAll(itemDiff)
                    }
                }
            }

            else -> {
                if (element1 != element2) {
                    diff.add("Value mismatch: $element1 vs $element2")
                }
            }
        }

        return diff
    }


    /**
     * Check whether there exists Node in the CallHierarchy that.
     *
     *  @param callInstance The root node of the call instance hierarchy to be checked.
     *  @param attribute the attribute the [CallInstance] node must hold.
     *  @return 'true' if there is a Node that hold for the attribute.
     */
    fun nodeExists(callInstance: CallInstance, attribute: Predicate<CallInstance>): Boolean {

        if (attribute.test(callInstance)) {
            return true
        }
        callInstance.children.forEach {
            if (nodeExists(it, attribute)) {
                return true
            }
        }
        return false
    }


    /**
     * Checks that no node is its own descendant/ancestor via the [CallInstance.children] hierarchy.
     *
     * (alex n:) This invariant is something we definely want, but I have a hard time seeing us ever actually violate
     *   it .. -- so not totally clear if we want to keep this (but it doesn't hurt).
     *
     * Details / older description:
     * Checks whether there exists a single path per node in the call instance hierarchy.
     *
     * This function performs a depth-first search (DFS) traversal of the call instance hierarchy
     * and checks if there is a single unique path from the root node to each leaf node, ensuring
     * that no two different paths contain the same node.
     *
     * @param callInstance The root node of the call instance hierarchy to be checked.
     * @return `true` if there is a single path per node, `false` otherwise.
     */
    fun checkNoCycles(callInstance: CallInstance): Boolean {
        // Create a memory to keep track of visited nodes
        val memory = mutableListOf<CallInstance>()

        /**
         * Internal helper function to perform DFS traversal and check for unique paths.
         *
         * @param node The current node being visited during DFS.
         * @return `true` if the traversal from this node follows a unique path, `false` otherwise.
         */
        fun dfs(node: CallInstance?): Boolean {
            if (node == null) return true

            // If the node is already visited and present in memory, it's not a unique path
            if (node in memory) {
                return false
            }
            memory.add(node) // Mark the node as visited


            // Recursively traverse all child nodes and check for unique paths
            node.children.forEach { child ->
                val isSinglePathPerNode = dfs(child)
                if (!isSinglePathPerNode) {
                    return false
                }
            }
            return true
        }

        // Start DFS traversal from the root node
        return dfs(callInstance)
    }


    /**
     * Tests the call hierarchy with specified attributes and returns a set of result nodes marking
     * the ends of paths satisfying all predicates.
     * For example:
     * given list of nodes from the following tree:    A
     *                                               /  \
     *                                              B    C
     *                                            /  \
     *                                           D    E
     *  and attributes L1,L2
     *  if for example we give {A} and A and B holds for L1 and D holds for L2  the function will return node D as there is a path from A to D that
     *  Holds (But not overlap) L1 and L2.
     *
     * @param callInstances The list of CallInstance nodes to test.
     * @param attributes The queue of attribute predicates to apply.
     * @return A set of CallInstance nodes that satisfy the conditions.
     */
    fun pathEndingsWithPredicates(
        callInstances: Set<CallInstance>,
        attributes: Queue<Predicate<CallInstance>>
    ): Set<CallInstance> {

        // Initialize a mutable list to store the result nodes
        val resultNodes = HashSet<CallInstance>()

        // Start DFS traversal from the root call instances with the attributes
        /**
         * A helper function for depth-first search traversal to test call hierarchy with attributes.
         *
         * @param callInstances The list of CallInstance nodes to test.
         * @param attributes The queue of attribute predicates to apply.
         */
        fun chainedPathDFS(
            callInstances: Set<CallInstance>,
            attributes: Queue<Predicate<CallInstance>>,
            resultNodes: HashSet<CallInstance>
        ) {
            // Base case: No call instances or no attributes left in the queue
            if (callInstances.isEmpty() || attributes.isEmpty()) {
                return
            }

            // Apply the current attribute from the queue
            val currAttribute = attributes.poll()
            // Clone the attributes to prevent undesired behaviour due to shared reference
            val nextAttributes = attributes.clone()

            // Process each call instance with the current attribute
            callInstances.forEach { callInstance ->
                // Test the current call instance with the current attribute
                val testedInstances = pathEndingsWithPredicates(callInstance, currAttribute)

                // If it's the last remaining attribute, keep the instances which apply this attribute
                if (nextAttributes.isEmpty()) {
                    resultNodes.addAll(testedInstances)
                }

                // Recursively test the remaining attributes on the children of the last passed attribute
                chainedPathDFS(testedInstances.map { it.children }.flatten().toSet(), nextAttributes, resultNodes)
            }
        }

        chainedPathDFS(callInstances, attributes, resultNodes)

        // Return the list of result nodes
        return resultNodes
    }

    /**
     * Finds a set of CallInstances that satisfy the given attribute along paths from the input node.
     * For example:
     * given node from the following tree:    A
     *                                      /  \
     *                                     B    C
     *                                   /  \
     *                                  D    E
     * and attributes L1
     * if for example node A was given and nodes A,B,E holds for L1 then the algo will return Node E
     * @param node The starting node to begin the search.
     * @param attribute The attribute that must hold for nodes along the path.
     * @return A set of CallInstance nodes that satisfy the conditions.
     */
    fun pathEndingsWithPredicates(node: CallInstance, attribute: Predicate<CallInstance>): Set<CallInstance> {

        val resultNodes = HashSet<CallInstance>()

        // Start DFS from the input node
        /**
         * A helper function for depth-first search traversal to find the paths from the given node that holds the attribute.
         *
         * @param node The current node being visited.
         * @param attribute The attribute that must hold for nodes along the path.
         * @return `true` if the path from this node to a leaf satisfies the attribute, otherwise `false`.
         */
        fun singlePathDFS(
            node: CallInstance,
            attribute: Predicate<CallInstance>,
            resultNodes: HashSet<CallInstance>
        ): Boolean {

            // Check if this node satisfies the attribute and
            val nodeSatisfiesAttribute = attribute.test(node)

            // if the node don׳t satisfies the attribute return false
            if (!nodeSatisfiesAttribute) {
                return false
            }

            // Flag to track whether any child's path satisfies the attribute
            var anyChildPathSatisfiesAttribute = false

            // Explore children
            node.children.forEach { child ->
                if (singlePathDFS(child, attribute, resultNodes)) {
                    anyChildPathSatisfiesAttribute = true
                }
            }

            // If this node satisfies the attribute and none of its children's paths do keep it
            if (!anyChildPathSatisfiesAttribute) {
                resultNodes.add(node)
            }

            // Return whether this node is part of the path that satisfies the attribute
            return true
        }
        singlePathDFS(node, attribute, resultNodes)

        return resultNodes
    }

    /**
     * Retrieves the closest ancestor that holds for the given attribute.
     *
     * @param node some node of the [CallInstance] tree.
     * @return A node from the [CallInstance] tree.
     */
    fun ancestorExists(node: CallInstance?, attribute: Predicate<CallInstance>): CallInstance? {
        if (node == null) return null
        if (attribute.test(node)) {
            return node
        }
        return ancestorExists(node.parent, attribute)
    }

    /**
     * Retrieves the leaf nodes of a CallInstance tree.
     *
     * @param node The root node of the CallInstance tree.
     * @return A set of leaf nodes in the CallInstance tree.
     */
    fun getTreeLeafs(node: CallInstance): Set<CallInstance> = findNodes(node) { it.children.isEmpty() }

    /**
     * Filters the call instance tree to find nodes that satisfy the given attribute.
     *
     * @param node Node of the call instance tree.
     * @param attribute The attribute predicate that must hold for nodes to be included.
     * @return A set of CallInstance nodes that satisfy the attribute.
     */
    fun findNodes(node: CallInstance, attribute: Predicate<CallInstance>): Set<CallInstance> {
        // Initialize a set to store the filtered nodes
        val nodes = HashSet<CallInstance>()

        // Start DFS traversal from the root node to find the filtered nodes
        /**
         * A helper function for depth-first search traversal to find nodes that satisfy the attribute.
         *
         * @param node The current node being visited.
         * @param attribute The attribute predicate that must hold for nodes to be included.
         * @param nodes The set to store the filtered nodes.
         */
        fun findNodesRec(node: CallInstance, attribute: Predicate<CallInstance>, nodes: HashSet<CallInstance>) {

            // Check if the current node satisfies the attribute
            if (attribute.test(node)) {
                nodes.add(node)
                return
            }

            // Recursively explore children
            node.children.forEach { childInstance ->
                findNodesRec(childInstance, attribute, nodes)
            }
        }

        findNodesRec(node, attribute, nodes)

        // Return the set of filtered nodes
        return nodes
    }

    /**
     * Represents the relationship between two [CallInstance].
     */
    enum class Relation {
        /** Indicates that node1 is a child of node2. */
        CHILD_OF,

        /** Indicates that node1 is a parent of node2. */
        PARENT_OF,

        /** Indicates that there is no parent-child relationship between the nodes. */
        NEITHER,
    }

    /**
     * Determines the relationship between two CallInstance nodes.
     *
     * @param node1 The first CallInstance node.
     * @param node2 The second CallInstance node.
     * @return The Relation enum value indicating the relationship between the nodes.
     */
    fun nodeRelations(node1: CallInstance, node2: CallInstance): Relation {
        // Ensure that node1 and node2 are distinct nodes
        assert(node1 != node2) { "node1 and node2 must be distinct nodes" }

        // Helper function to generate the sequence of parent nodes for a given instance
        fun parents(instance: CallInstance) = generateSequence(instance) { it.parent }.drop(1)

        // Check if node2 is a parent of node1
        if (parents(node1).contains(node2)) {
            return Relation.CHILD_OF
        }

        // Check if node1 is a parent of node2
        if (parents(node2).contains(node1)) {
            return Relation.PARENT_OF
        }

        // No parent-child relationship found
        return Relation.NEITHER
    }

}
