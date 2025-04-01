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

package report
import allocator.Allocator
import bridge.Method
import config.Config
import datastructures.mutableMultiMapOf
import datastructures.stdcollections.*
import kotlinx.serialization.json.*
import log.*
import org.jetbrains.annotations.TestOnly
import report.SolverResultStatusToTreeViewStatusMapper.computeFinalStatus
import report.callresolution.GlobalCallResolutionReportView
import rules.RuleCheckResult
import rules.VerifyTime
import scene.IContractWithSource
import scene.IScene
import solver.SolverResult
import spec.cvlast.*
import spec.rules.*
import statistics.data.LiveStatsProgressInfo
import tac.TACStorageLayout
import tac.TACStorageType
import utils.*
import verifier.RuleAndSplitIdentifier
import java.io.IOException
import java.math.BigInteger

private val logger = Logger(LoggerTypes.TREEVIEW_REPORTER)


object SolverResultStatusToTreeViewStatusMapper{
    private fun getStatusForSatisfyRule(result: SolverResult): TreeViewReporter.TreeViewStatusEnum {
        return when (result) {
            SolverResult.SAT -> TreeViewReporter.TreeViewStatusEnum.VERIFIED
            SolverResult.UNSAT -> TreeViewReporter.TreeViewStatusEnum.VIOLATED
            SolverResult.UNKNOWN -> TreeViewReporter.TreeViewStatusEnum.UNKNOWN
            SolverResult.TIMEOUT -> TreeViewReporter.TreeViewStatusEnum.TIMEOUT
            SolverResult.SANITY_FAIL -> error("Unexpected Behaviour: There was a satisfy rule that has a solver result ${SolverResult.SANITY_FAIL}")
        }
    }

    private fun getStatusForSanityRule(result: SolverResult): TreeViewReporter.TreeViewStatusEnum {
        return when (result) {
            SolverResult.SAT -> TreeViewReporter.TreeViewStatusEnum.VERIFIED
            SolverResult.UNSAT -> TreeViewReporter.TreeViewStatusEnum.SANITY_FAILED
            SolverResult.UNKNOWN -> TreeViewReporter.TreeViewStatusEnum.UNKNOWN
            SolverResult.TIMEOUT -> TreeViewReporter.TreeViewStatusEnum.TIMEOUT
            SolverResult.SANITY_FAIL -> error("Unexpected Behaviour: There was a sanity rule with base solver result ${SolverResult.SANITY_FAIL}")
        }
    }

    private fun getStatusForRegularRule(result: SolverResult): TreeViewReporter.TreeViewStatusEnum {
        return when (result) {
            SolverResult.SAT -> TreeViewReporter.TreeViewStatusEnum.VIOLATED
            SolverResult.UNSAT -> TreeViewReporter.TreeViewStatusEnum.VERIFIED
            SolverResult.UNKNOWN -> TreeViewReporter.TreeViewStatusEnum.UNKNOWN
            SolverResult.TIMEOUT -> TreeViewReporter.TreeViewStatusEnum.TIMEOUT
            SolverResult.SANITY_FAIL -> error("Unexpected Behaviour: There was a regular rule with base solver result ${SolverResult.SANITY_FAIL}")
        }
    }

    fun computeFinalStatus(solverResult: SolverResult, rule: IRule): TreeViewReporter.TreeViewStatusEnum {
        return when (rule) {
            is CVLSingleRule ->
                if (rule.isSanityCheck()) { //What if we have a sanity check and a satisfy rule?
                    getStatusForSanityRule(solverResult)
                } else if (rule.isSatisfyRule) {
                    getStatusForSatisfyRule(solverResult)
                } else {
                    getStatusForRegularRule(solverResult)
                }
            is StaticRule,
            is AssertRule -> if (rule.isSatisfyRule) {
                getStatusForSatisfyRule(solverResult)
            } else {
                getStatusForRegularRule(solverResult)
            }
            is GroupRule -> error("Unexpected Behaviour: Tried to map the status for the rule ${rule}")
            is EcosystemAgnosticRule ->
                if (rule.ruleType is SpecType.Single.GeneratedFromBasicRule.SanityRule.VacuityCheck) {
                    getStatusForSanityRule(solverResult)
                } else if (rule.isSatisfyRule) {
                    getStatusForSatisfyRule(solverResult)
                } else {
                    getStatusForRegularRule(solverResult)
                }
        }
    }
}

/**
 * The NodeType is used for the Rules pages and the Re-Run feature.
 */
enum class NodeType {
    ROOT,
    METHOD_INSTANTIATION,
    CONTRACT,
    INVARIANT_SUBCHECK,
    INDUCTION_STEPS,
    CUSTOM_INDUCTION_STEP,
    ASSERT_SUBRULE_AUTO_GEN,
    VIOLATED_ASSERT,
    SANITY
}


/**
 * A reporter that generates an up-to-date "Tree-View" of the progress of the tool.
 * The Tree-View report shows the checking status of every rule.
 *
 * Each rule should be registered when its check begins via [TreeViewReporter.signalStart], and once its results ([RuleCheckResult])
 * are available they should be added using [TreeViewReporter.addResults].
 *
 * The reporter is linked to a single verification query of ([contract], [specFile]).
 *
 */
class TreeViewReporter(
    contract: SolidityContract?,
    specFile: String,
    scene: IScene,
) {
    // XXX: this path used to configurable, but I believe it's now hardcoded in some of our infrastructure.
    private val versionedFIle get() = VersionedFile("treeViewStatus.json")

    init {
        instance = this
    }

    /** [LiveStatsReporter] is a specialized part of [TreeViewReporter] that live stats are reported to and that
     * enters them into the treeViewReports during [hotUpdate]. */
    val liveStatsReporter =
        if (Config.TreeViewLiveStats.get()) {
            ConcreteLiveStatsReporter()
        } else {
            DummyLiveStatsReporter
        }

    /**
     * The version of the treeView report JSON file.
     * Each invocation of [writeToFile] increments this version by one.
     */
    private var fileVersion: Int = 0

    private val perAssertReporter = PerAssertReporter()

    init {
        // set up the files we'll dump
        ArtifactManagerFactory().registerArtifact(versionedFIle, StaticArtifactLocation.TreeViewReports)
    }
    companion object {
        val ROOT_NODE_IDENTIFIER: RuleIdentifier = RuleIdentifier.freshIdentifier("TREE_VIEW_ROOT_NODE")

        /** TreeView reporter is always (so far) a singleton (or null); so this is a pointer to the instance, if
         * existent. Only used for checking purposes; and probably that should not change -- i.e. don't use this,
         * unless it's been thought about well ...*/
        var instance: TreeViewReporter? = null
    }
    /**
     * An enum describing the status or the final result of a check of an [IRule].
     *
     *
     * The order of the enum entries is important: later is "worse" (i.e. has a higher displaying priority) than earlier.
     * This is relevant for computing the color of the labels of nodes in the rules display. The fields in this enum
     * have colors (and/or other highlights) associated. A parent's color is the color among its children's colors that
     * has the highest priority.
     * (practically: when some child is violated, which shows as red, then we assume the user will want to unfold
     *           this parent, so we color the parent red (whereas "green"/not violated has a lower priority, because
     *           that's usually the state that people are fine with)
     */
    enum class TreeViewStatusEnum {

        /**
         *  This status is the initial status a node will be created in upon registering the node in the tree with [addChildNode].
         *
         *  The node's is present in the tree, but the actual solving process associated to it has not been started yet.
         *  This will always be the status of any [GroupRule], but also for nodes grouping parametric contracts (see [spec.cvlast.SpecType.Group.ContractRuleType]).
         *
         *  We'll transmit it as "RUNNING" in the rule report as the rule report doesn't distinguish between [REGISTERED] and [SOLVING].
         *  We distinguish between those two states in CertoraProver to be able to correctly set the [IS_RUNNING]
         *  flag (which is only derived from [SOLVING]), but _not_ from [REGISTERED]. For details on the  [IS_RUNNING] flag,
         *  see also the comment in [JSONSerializableTreeNode].
         */
        REGISTERED {
            override val reportName: String
                get() = "RUNNING"
        },
        SOLVING {
            override val reportName: String
                get() = "RUNNING"
        },
        BENIGN_SKIPPED {
            override val reportName: String
                get() = SKIPPED.name
        },
        VERIFIED,
        SKIPPED,
        TIMEOUT,
        UNKNOWN,
        SANITY_FAILED,
        VIOLATED,
        ERROR
        ;

        open val reportName: String
            get() = name

        fun isRunning() = this == REGISTERED || this == SOLVING
    }


    /**
     * Available contracts table. This table lists all the contract available in [scene].
     */
    class ContractsTable(val scene: IScene) : TreeViewReportable {
        /**
         * Represents a row (or an entry) in the Available Contracts table.
         */
        inner class ContractEntry(
            val name: String,
            val storageLinks: Map<BigInteger, BigInteger>,
            val methods: List<Method>,
            val storageLayout: TACStorageLayout?
        ) : TreeViewReportable {

            override val treeViewRepBuilder = TreeViewRepJsonObjectBuilder {
                put(key = "name", name)
                put(key = "address", value = "See in the \"Variables\" tab for a violated rule")
                /**
                storageLinks : [
                { slot: slotLabel, linkedAddress: addressLabel }, ...
                ]
                 */
                putJsonArray(key = "storageLinks") {
                    storageLinks.forEach { (slot, linkedAddress) ->
                        val slotLabel: String =
                            storageLayout?.values?.singleOrNull { storageSlotEntry ->
                                storageSlotEntry.slot == slot &&
                                    (storageSlotEntry.storageType as?
                                        TACStorageType.IntegralType)?.typeLabel == "address"
                            }?.label ?: "slot $slot"
                        val addressLabel: String = scene.getContractOrNull(linkedAddress)?.name ?: "0x${
                            linkedAddress.toString(16)
                        }"
                        addJsonObject {
                            put(key = "slot", value = slotLabel)
                            put(key = "linkedAddress", value = addressLabel)
                        }
                    }
                }
                putJsonArray(key = "methods") {
                    methods.forEach { method ->
                        add(method.getPrettyName())
                    }
                }
            }
        }

        override val treeViewRepBuilder = TreeViewRepJsonArrayBuilder(caching = true) {
            scene.getContracts().forEach { contract ->
                (contract as? IContractWithSource)?.src?.let { contractSrc ->
                    add(
                        ContractEntry(
                            name = contractSrc.name,
                            storageLinks = contractSrc.state,
                            methods = contractSrc.methods,
                            storageLayout = contract.getStorageLayout()
                        )
                    )
                }
            }
        }
    }


    data class TreeViewNodeResult(
        val nodeType: NodeType,
        val rule: IRule?,
        val status: TreeViewStatusEnum,
        val isRunning: Boolean,
        val verifyTime: VerifyTime = VerifyTime.None,
        val liveCheckFileName: String? = null,
        val splitProgress: Int? = null,
        val outputFiles: List<String> = listOf(),
        val ruleAlerts: List<RuleAlertReport.Single<*>> = listOf(),
        val location: TreeViewLocation? = null,
        /**
         *  The uuid is a unique Id for the node as Integer type. It's used in the rule report for tracking points
         *  and expanding the tree when switching between tabs or during polling.
         */
        val uuid: Int) {
        fun printForErrorLog(): String = listOf(
            "nodeType" to nodeType.toString(),
            "status" to status.toString(),
            "isRunning" to isRunning.toString(),
        ).joinToString(separator = "\n") { (label, contents) -> "   $label: $contents" }
    }

    /**
     * This class will be created just for serialization purposes, when the actual node will be written to
     * file. In comparison to [TreeViewNodeResult] this class knows the [DisplayableIdentifier] and it's children.
     */
    data class JSONSerializableTreeNode(
        val name: String,
        val children: List<JSONSerializableTreeNode>,
        val output: JsonArrayBuilder.() -> Unit,
        val uiId: Int,
        val liveCheckFileName: String?,
        val errors: List<RuleAlertReport.Single<*>>,
        val jumpToDefinition: TreeViewLocation?,
        val nodeType: String,
        val splitProgress: Int?,
        val status: String,
        val duration: Long,
        val isRunning: Boolean,
    ) : TreeViewReportable {

        override val treeViewRepBuilder = TreeViewRepJsonObjectBuilder {
            put(TreeViewReportAttribute.NAME(), name)

            putJsonArray(TreeViewReportAttribute.CHILDREN(), children)
            putJsonArray(TreeViewReportAttribute.OUTPUT(), output)

            //Properties taken from [TreeViewNodeResult]
            put(TreeViewReportAttribute.UI_ID(), uiId)
            put(TreeViewReportAttribute.LIVE_CHECK_INFO(), liveCheckFileName)
            putJsonArray(TreeViewReportAttribute.ERRORS(), errors)

            //If location has not been explicitly set (in the case we have a counter example for an assert) - use the rule's location if available.
            put(TreeViewReportAttribute.JUMP_TO_DEFINITION(), jumpToDefinition)
            put(TreeViewReportAttribute.NODE_TYPE(), nodeType)
            put(TreeViewReportAttribute.SPLIT_PROGRESS(), splitProgress)

            put(TreeViewReportAttribute.STATUS(), status)
            put(TreeViewReportAttribute.DURATION(), duration)

            put(TreeViewReportAttribute.IS_RUNNING(), isRunning)
        }
    }

    /**
     * The underlying forest of trees that is built and maintained by this [TreeViewReporter].
     * In this forest, each tree consists of [TreeViewNode]s. The root of each tree is a [TreeViewNode.Rule], whereas
     * the leaves may be either [TreeViewNode.Rule] or [TreeViewNode.Assert].
     */
    class TreeViewTree(
        val contract: SolidityContract?,
        val specFile: String,
        val contractsTable: ContractsTable,
        val globalCallResolutionBuilder: GlobalCallResolutionReportView.Builder,
    ) : TreeViewReportable {

        private val identifierToNode: MutableMap<DisplayableIdentifier, TreeViewNodeResult> = mutableMapOf()

        private val parentToChild = mutableMultiMapOf<DisplayableIdentifier, DisplayableIdentifier>()

        private fun getTopLevelNodes(): List<DisplayableIdentifier> =
            parentToChild[ROOT_NODE_IDENTIFIER]?.toList() ?: listOf()

        @TestOnly
        fun treeViewNodeResults(): Collection<TreeViewNodeResult> {
            return identifierToNode.values
        }

        fun addChildNode(child: DisplayableIdentifier, parent: DisplayableIdentifier, nodeType: NodeType, rule: IRule?) {
            synchronized(this) {
                identifierToNode[child] = TreeViewNodeResult(
                    nodeType = nodeType,
                    rule = rule,
                    status = TreeViewStatusEnum.REGISTERED,
                    isRunning = false,
                    uuid = Allocator.getFreshId(Allocator.Id.TREE_VIEW_NODE_ID)
                )
                val children = parentToChild[parent] ?: mutableSetOf()
                children.add(child)
                parentToChild[parent] = children
                sanityCheck()
            }
        }

        fun getResultForNode(identifier: DisplayableIdentifier): TreeViewNodeResult {
            return synchronized(this) {
                identifierToNode.getOrPut(identifier) {
                    TreeViewNodeResult(
                        nodeType = NodeType.ROOT,
                        rule = null,
                        status = TreeViewStatusEnum.REGISTERED,
                        isRunning = false,
                        uuid = Allocator.getFreshId(Allocator.Id.TREE_VIEW_NODE_ID)
                    ).also {
                        logger.warn { "Could not find a tree view node for $identifier - there is a call to addChildNode missing." }
                    }
                }
            }
        }

        fun getChildren(node: DisplayableIdentifier): Sequence<DisplayableIdentifier> {
            return parentToChild[node]?.asSequence().orEmpty()
        }

        fun signalStart(ruleId: RuleIdentifier) {
            updateStatus(ruleId){ it.copy(status = TreeViewStatusEnum.SOLVING)}
        }

        fun signalSkip(ruleId: RuleIdentifier) {
            Logger.always("received `signalSkip` for rule $ruleId", respectQuiet = false)
            updateStatus(ruleId){ it.copy(status = TreeViewStatusEnum.BENIGN_SKIPPED)}
        }

        fun signalEnd(ruleId: RuleIdentifier, solverResult: RuleCheckResult.Leaf, ruleOutput: List<String>) {
            updateStatus(ruleId){ treeViewNodeResult ->
                when(solverResult){
                    is RuleCheckResult.Single ->
                        treeViewNodeResult.copy(
                            status = computeFinalStatus(solverResult.result, solverResult.rule),
                            verifyTime = solverResult.verifyTime,
                            ruleAlerts = solverResult.ruleAlerts?.asList ?: listOf(),
                            outputFiles = ruleOutput
                        ).letIf(solverResult.result != SolverResult.TIMEOUT) {
                            it.copy(splitProgress = null) // should have been propagated at this point, but making sure
                        }
                    is RuleCheckResult.Error ->
                        treeViewNodeResult.copy(
                            status = TreeViewStatusEnum.ERROR,
                            ruleAlerts = solverResult.ruleAlerts.asList
                        )
                    is RuleCheckResult.Skipped ->
                        treeViewNodeResult.copy(
                            status = TreeViewStatusEnum.SKIPPED,
                            ruleAlerts = solverResult.ruleAlerts.asList
                        )
                }
            }
        }

        /**
         * For the given [id] Identifier gets the current result (which must exist), calls the [updateFunction]
         * and persists the current result.
         */
        fun updateStatus(id: DisplayableIdentifier, updateFunction: (TreeViewNodeResult) -> TreeViewNodeResult) {
            synchronized(this) {
                val existingResult = identifierToNode[id]
                if (existingResult != null) {
                    identifierToNode[id] = updateFunction(existingResult)
                } else {
                    logger.error { "The identifier ${id} was not registered in TreeView. This means there is a call to `addChildNode` missing on the tree." }
                }
            }
        }

        fun updateLiveCheckInfo(rule: RuleIdentifier, liveCheckFileName: String, splitProgressPercentage: Int?) {
            updateStatus(rule) {
                 it.copy(
                     liveCheckFileName = liveCheckFileName,
                     splitProgress = splitProgressPercentage,
                 )
            }
        }

        fun sanityCheck() {
            val traversed = mutableSetOf<DisplayableIdentifier>()
            val stack = listOf<DisplayableIdentifier>()
            sanityTraverse(ROOT_NODE_IDENTIFIER, traversed, stack)

            //Ensure all nodes are connected
            if(!getAllNodes().containsAll(traversed.toSet())) {
                logger.warn { "Found dangling tree nodes: Found more nodes while traversing tree, ${traversed.toSet().minus(getAllNodes())}" }
            }
            if(!traversed.toSet().containsAll(getAllNodes())){
                logger.warn { "Found dangling tree nodes: Not all nodes where traversed, ${getAllNodes().minus(traversed.toSet())}" }
            }
        }

        fun getAllNodes(): Set<DisplayableIdentifier> {
            synchronized(this) {
                return identifierToNode.keys.toSet() + ROOT_NODE_IDENTIFIER
            }
        }

        private fun sanityTraverse(curr: DisplayableIdentifier, visited: MutableSet<DisplayableIdentifier>, stack: List<DisplayableIdentifier>) {
            visited.add(curr)
            if (stack.contains(curr)) {
                logger.error("Tree contains a cycle, ${stack} - re-occurring node ${curr}", )
            } else {
                parentToChild[curr]?.forEach {
                    sanityTraverse(it, visited, stack + curr)
                }
            }
        }

        private fun timestamp() = System.currentTimeMillis()

        /**
         * This function (recursively) calls itself and first computes the final result for all children of [curr]
         * and then merges the results with the result at the [curr] node.
         */
        private fun toJson(curr: DisplayableIdentifier): JSONSerializableTreeNode {
            val childJsonResults =
                getChildren(curr)
                    // we need the TreeViewNodeResults here only so we can filter out the BENIGN_SKIPPED ones
                    .map { childDI -> childDI to getResultForNode(childDI) }
                    .filter { (_, treeViewResult) -> treeViewResult.status != TreeViewStatusEnum.BENIGN_SKIPPED }
                    .map { (childDI, _) -> toJson(childDI) }

            val currTreeViewResult = getResultForNode(curr)
            val jumpToDefinition = currTreeViewResult.location ?: currTreeViewResult.rule?.treeViewLocation()
            val statusString = currTreeViewResult.status.reportName
            val duration = currTreeViewResult.verifyTime.timeSeconds
            val isRunning = currTreeViewResult.isRunning

            return JSONSerializableTreeNode(
                name = curr.displayName,
                children = childJsonResults.toList(),
                output = { currTreeViewResult.outputFiles.forEach { add(it) } },
                uiId = currTreeViewResult.uuid,
                liveCheckFileName = currTreeViewResult.liveCheckFileName,
                errors = currTreeViewResult.ruleAlerts,
                jumpToDefinition = jumpToDefinition,
                nodeType = currTreeViewResult.nodeType.name,
                splitProgress = currTreeViewResult.splitProgress,
                status = statusString,
                duration = duration,
                isRunning = isRunning,
            )
        }

        override val treeViewRepBuilder = TreeViewRepJsonObjectBuilder {
            putJsonArray(
                key = TreeViewReportAttribute.RULES(),
                getTopLevelNodes().sortedBy { it.displayName }.map { toJson(it) }
            )
            put(key = TreeViewReportAttribute.TIMESTAMP(), value = timestamp())
            put(key = TreeViewReportAttribute.CONTRACT(), value = contract?.name)
            put(key = TreeViewReportAttribute.SPEC(), value = specFile)
            put(key = TreeViewReportAttribute.AVAILABLE_CONTRACTS(), value = contractsTable)
            put(
                key = TreeViewReportAttribute.GLOBAL_CALL_RESOLUTION(),
                value = globalCallResolutionBuilder.toGlobalReportView()
            )
        }

        /** For debug purpose only
         */
        override fun toString(): String {
            return """TreeViewReporter:
${getTopLevelNodes().joinToString("\n") { nodeToString(it, 0) }}
                """
        }

        /** For debug purpose only
         */
        private fun nodeToString(node: DisplayableIdentifier, indentation: Int): String {
            return ("\t".repeat(indentation) + node.displayName + " -> Current Node Status: " + identifierToNode[node]?.status + "\n" +
                (parentToChild[node] ?: listOf()).joinToString("\n") { nodeToString(it, indentation + 1) })
        }

        fun checkAllLeavesAreTerminated() {
            val runningNodes = identifierToNode.filter{
                getChildren(it.key).isEmpty() && it.value.status == TreeViewStatusEnum.SOLVING
            }
            if(runningNodes.isNotEmpty()) {
                logger.error("There are still running nodes in the tree: ${runningNodes.keys }}}")
            }
        }

        /**
         * Some state is propagated from the leafs upwards in the tree, including the [TreeViewStatusEnum], the
         * `isRunningField`, and the consumed time.
         * This propagation is done on every [hotUpdate] by triggering this method.
         *
         * Background on what the UI does with this information:
         *
         * The [IS_RUNNING()] property is used in the rule report to render three dots for a node in the tree and indicates
         * that some rule below the current node is still in the solving process. Be aware that the actual color of the node in the rule
         * report depends on the [STATUS()] property above.
         * Examples:
         *    - A node X with two children with one of them is still being processed while the other has been verified
         *        -> X is rendered as Green with three dots
         *    - A node X with two children with one of them is still being processed while the other has been violated
         *        -> X is rendered as Red with three dots
         */
        fun propagateState() {
            fun rec(di: DisplayableIdentifier) {
                val children = getChildren(di)

                children.forEach { rec(it) } // update state recursively, starting from the leafs

                val childrenTreeViewResults = children.map { getResultForNode(it) }.toList()

                if (children.isEmpty()) {
                    updateStatus(di) { res -> res.copy(isRunning = res.status.isRunning()) }
                } else if (childrenTreeViewResults.any { it.nodeType == NodeType.SANITY }) {
                    checkWarn(childrenTreeViewResults
                        // we might have a violated_assert child (the one that's added after the fact, on signalEnd)
                        // -- in this case, the sanity children are still there, but are ignored (I assume..), so this
                        //  filter avoids a false positive for this check in that case
                        .filter { it.status != TreeViewStatusEnum.BENIGN_SKIPPED }
                        .all { it.nodeType == NodeType.SANITY }
                    ) {
                        "if any child is a rule-sanity child, then all (non-skipped) children must be rule sanity " +
                            "children; instead got these children: ${childrenTreeViewResults.map { it.rule?.ruleIdentifier to it.nodeType }}"
                    }
                    val currTreeViewResult = getResultForNode(di)
                    val newStatus = (childrenTreeViewResults + currTreeViewResult).maxOf { it.status }
                    val newIsRunning = currTreeViewResult.status.isRunning() ||
                        childrenTreeViewResults.any { it.isRunning }
                    val newVerifyTime =
                        (childrenTreeViewResults + currTreeViewResult)
                            .map { it.verifyTime }
                            .reduce { acc, y -> acc.join(y) }
                    updateStatus(di) { res -> res.copy(status = newStatus, isRunning = newIsRunning, verifyTime = newVerifyTime) }
                } else {
                    val newStatus = childrenTreeViewResults.maxBy { it.status }.status
                    val newIsRunning = childrenTreeViewResults.any { it.isRunning }
                    val newVerifyTime =
                            childrenTreeViewResults.map { it.verifyTime }.reduce { acc, y -> acc.join(y) }

                    updateStatus(di) { res -> res.copy(status = newStatus, isRunning = newIsRunning, verifyTime = newVerifyTime) }
                }
            }
            getChildren(ROOT_NODE_IDENTIFIER).forEach { rec(it) }
        }
    }

    private val tree: TreeViewTree =
        TreeViewTree(contract, specFile, ContractsTable(scene), GlobalCallResolutionReportView.Builder())


    private fun mapRuleToNodeType(child: IRule): NodeType {
        /**
         * This check handles parametric methods. These will have additional rule generation meta.
         * This can be the case for [SpecType.Single.BuiltIn] sanity rules, parametric rules and invariants.
         */
        if(child is CVLSingleRule && child.ruleGenerationMeta is SingleRuleGenerationMeta.WithMethodInstantiations){
            /**
             *  Method instantiation rules further explode into sanity rules. So in case the rule is a sanity check,
             *  the node's type should be [NodeType.SANITY]
             */
            return if(child.isSanityCheck()){
                NodeType.SANITY
            } else{
                NodeType.METHOD_INSTANTIATION
            }
        }
        return when(child.ruleType){
            //All these rules are top-level elements in the tree.
            SpecType.Group.StaticEnvFree,
            SpecType.Single.BMC,
            is SpecType.Group.InvariantCheck.Root,
            is SpecType.Single.BuiltIn,
            is SpecType.Single.FromUser,
            is SpecType.Single.EnvFree.Static,
            is SpecType.Single.SkippedMissingOptionalMethod -> NodeType.ROOT


            //Logic related to Invariants
            is SpecType.Single.InvariantCheck.TransientStorageStep,
            is SpecType.Single.InvariantCheck.InductionBase,
            is SpecType.Group.InvariantCheck.InductionSteps -> NodeType.INVARIANT_SUBCHECK

            is SpecType.Single.InvariantCheck.ExplicitPreservedInductionStep,
            is SpecType.Single.InvariantCheck.GenericPreservedInductionStep -> NodeType.INDUCTION_STEPS
            is SpecType.Group.InvariantCheck.CustomInductionSteps -> NodeType.CUSTOM_INDUCTION_STEP


            //Logic related to Parametric Contracts
            is SpecType.Group.ContractRuleType -> NodeType.CONTRACT

            //Logic related to Asserts
            SpecType.Single.InCodeAssertions,
            is SpecType.Single.GeneratedFromBasicRule.MultiAssertSubRule -> NodeType.ASSERT_SUBRULE_AUTO_GEN
            is SpecType.Single.GeneratedFromBasicRule.SanityRule -> NodeType.SANITY
        }
    }

    fun registerSubruleOf(child: IRule, parent: IRule) {
        synchronized(this) {
            tree.addChildNode(child.ruleIdentifier, parent.ruleIdentifier, mapRuleToNodeType(child), child)
        }
    }

    fun addChildNode(childRuleIdentifier: DisplayableIdentifier, parentRuleIdentifier: DisplayableIdentifier, nodeType: NodeType) {
        synchronized(this) {
            tree.addChildNode(childRuleIdentifier, parentRuleIdentifier, nodeType, null)
        }
    }
    fun signalStart(rule: IRule) {
        synchronized(this) {
            logger.info { "Signaled start of rule ${rule.ruleIdentifier.displayName}" }
            // we many times we fail in the above check. sometimes it's helpful to see where the original
            // signal start came from. if we enable the treeview's trace logger, we'll get
            // a debug message with the trace leading us to a successful signalStart.
            logger.reportOnEventInCode("signal start")

            liveStatsReporter.reportProgress(
                rule.ruleIdentifier,
                LiveStatsProgressInfo.StaticAnalysis(RuleAndSplitIdentifier.root(rule.ruleIdentifier))
            )
            tree.signalStart(rule.ruleIdentifier)
        }
    }
    private fun writeToFile(jsond: String) {
        logger.info { "Writing version $fileVersion of treeView json" }
        ArtifactManagerFactory().useArtifact(versionedFIle, fileVersion) { handle ->
            ArtifactFileUtils.getWriterForFile(handle, overwrite = true).use { i ->
                i.append(jsond)
            }
        }
        // Increment version of output file
        fileVersion++
    }

    /**
     * Signals the termination of the [IRule] with provided [results] in the TreeView report.
     * This function can only be called with a [RuleCheckResult.Leaf].
     *
     * The [TreeViewReporter] takes care of then propagating up the final status to the node's parents, this is
     * implemented in [toJson].
     */
    fun signalEnd(rule: IRule, result: RuleCheckResult.Leaf) {
        val ruleOutput = perAssertReporter.addResults(rule.ruleIdentifier, result)
        tree.signalEnd(rule.ruleIdentifier, result, ruleOutput)

        // update the global CallResolution table
        if (result is RuleCheckResult.Single) {
            tree.globalCallResolutionBuilder.joinWith(result.callResolutionTable)
        }
    }


    /**
     * Signals skipping of
     * This function can only be called with a [RuleCheckResult.Leaf].
     *
     * The [TreeViewReporter] takes care of then propagating up the final status to the node's parents.
     */
    fun signalSkip(rule: IRule) {
        tree.signalSkip(rule.ruleIdentifier)
    }
    /**
     * Hot-updates [perAssertReporter]. Then hot-updates this [TreeViewReporter], which leads to a creation
     * of treeViewStatus.json files, based on [tree] (which is constructed itself each time [addResults]
     * is invoked).
     * It is important to first hot-update [perAssertReporter] and only then this [TreeViewReporter],
     * because otherwise, the generated treeViewStatus.json files (generated by this reporter) might
     * point to rule_output.json files (generated by [perAssertReporter]) which are not exist yet.
     * This might cause a problem in the frontend.
     */
    fun hotUpdate() {
        if (!Config.AvoidAnyOutput.get()) {
            synchronized(this) {
                hotUpdateLiveCheckInfo()
                if(logger.isDebugEnabled) {
                    logger.info { "Hot updating tree version ${fileVersion}: ${tree}" }
                }

                tree.propagateState()

                tree.sanityCheck()
                writeToFile(tree.toJsonString())
            }
        }
    }

    /**
     * This manipulates [tree], among other things, so needs to be in a `synchronized(this)` lock. Currently it's
     * only called from [hotUpdate], which has that lock.
     */
    private fun hotUpdateLiveCheckInfo() {
        val manager = ArtifactManagerFactory
            .enabledOrNull()
            ?: run {
                // if the artifact manager is disabled, we can't do anything here.
                // we also don't expect it to become enabled throughout the duration
                // of this function, so let's just bail
                return
            }

        liveStatsReporter.ruleToLiveCheckData.forEachEntry { (rule, data) ->
            val key = RuleOutputArtifactsKey(rule)
            val artifacts = manager
                .getOrRegisterRuleOutputArtifacts(key)
                ?: error("broken invariant: can't fail for enabled artifact manager")

            val fileName = artifacts.liveStatisticsFileName

            tree.updateLiveCheckInfo(
                rule,
                fileName,
                data.progress
            )

            val path = manager
                .getRegisteredArtifactPathOrNull(fileName)
                ?: error("broken invariant: we made sure that the artifact has been registered")

            try {
                val json = data.toJSON()
                ArtifactFileUtils
                    .getWriterForFile(path, overwrite = true)
                    .use { it.write(json) }
            } catch (e: IOException) {
                logger.error("Write of live statistics for ${key.ruleIdentifier} failed: $e")
            }
        }
    }


    /**
     * This class generates rule_output.json files for results of asserts in rules. The call trace in the rule report uses these files.
     * We create a report per [RuleCheckResult] in [addResults] as follows:
     * For a [RuleCheckResult.Single], if it was registered in [ArtifactManager.getRegisteredArtifactPathOrNull].
     *
     * Dependencies:
     * [rules.SpecChecker] is responsible for determining which reports should be written and for registering a file path
     * per rule in [ArtifactManagerFactory]. We do not generate a report for every assert - if the rule is verified and
     * contains no call resolution table, variables, or a call trace, we skip it.
     *
     * We do not expect to get the same [RuleCheckResult.Single] in [addResults] more than once. If we do, the latest results will
     * overwrite the previous one.
     */

    private inner class PerAssertReporter {
        fun addResults(node: RuleIdentifier, results: RuleCheckResult.Leaf): List<String> {
            val rule = results.rule
            return when (results) {
                is RuleCheckResult.Single -> {
                    val location = rule.treeViewLocation()

                    when (results) {
                        is RuleCheckResult.Single.Basic -> {
                            val ruleOutputReportView = results.toOutputReportView(
                                location,
                                node,
                            )
                            val outputFileName = ruleOutputReportView.writeToFile()
                            listOfNotNull(outputFileName)
                        }

                        is RuleCheckResult.Single.WithCounterExamples -> {
                            return results.ruleCheckInfo.examples.mapNotNull { example ->
                                synchronized(this) {
                                    val assertMeta = example.toViolatedAssertFailureMeta()
                                        ?: error("Could not find meta for node")

                                    val ruleOutputReportView = results.toOutputReportView(
                                        location,
                                        assertMeta.identifier,
                                        example
                                    )
                                    val outputFileName = ruleOutputReportView
                                        .writeToFile()
                                        ?: return@mapNotNull null

                                    tree.addChildNode(assertMeta.identifier, node, nodeType = NodeType.VIOLATED_ASSERT, rule = null)
                                    tree.updateStatus(assertMeta.identifier) {
                                        it.copy(
                                            nodeType = NodeType.VIOLATED_ASSERT,
                                            rule = null,
                                            status = computeFinalStatus(results.result, results.rule),
                                            outputFiles = listOf(outputFileName),
                                            location = assertMeta.range as? TreeViewLocation,
                                            verifyTime = results.verifyTime
                                        )
                                    }

                                    outputFileName
                                }
                            }
                        }
                    }
                }

                is RuleCheckResult.Skipped, is RuleCheckResult.Error -> {
                    // Skipped or Error - ignore no output
                    listOf()
                }
            }
        }
    }

    /**
     * Performs a basic check that the tree doesn't contain any running nodes, if so it will
     * log an error.
     */
    fun checkTermination() {
        tree.checkAllLeavesAreTerminated()
    }

    /**
     * This method adds a [IRule] to the top level of the tree.
     */
    fun addTopLevelRule(rule: IRule) {
        synchronized(this) {
            tree.addChildNode(rule.ruleIdentifier, ROOT_NODE_IDENTIFIER, NodeType.ROOT, rule)
        }
    }


    /**
     * IMPORTANT: Please be aware that this is only used in the Solana / Soroban / TAC flow.
     * The CVL/Solidity flow builds the rule tree manually by calling [registerSubruleOf] and [addTopLevelRule] explicitly
     * (see [RuleChecker]).
     *
     * This function takes as input all [rules] that are to be processed and builds the rule
     * tree based on the ruleType ([SpecType]).
     *
     * If the ruleType of a rule someRule is a [GeneratedFromBasicRule], the rule tree will be
     *  parentRule
     *  -> someRule
     * where parentRule is the original rule that someRule was derived from.
     *
     * Examples for the Solana Flow: The list [rules] contains all split rules when running in mode [Config.MultiAssertCheck],
     * alternatively, the list contains vacuity checks when [Config.DoSanityChecksForRules] is set to [SanityValues.BASIC].
     * All these rules are derived from the original rule.
     *
     * Note: This function currently only supports one level of [GeneratedFromBasicRule] and doesn't build the tree recursively,
     * in the case the parentRule is of ruleType [GeneratedFromBasicRule] again.
     */
    fun buildRuleTree(rules: Iterable<IRule>) {
        rules.forEach { rule ->
            when (rule.ruleType) {
                is SpecType.Single.GeneratedFromBasicRule -> {
                    val parentRule = rule.ruleType.getOriginatingRule()!!
                    addTopLevelRule(parentRule)
                    registerSubruleOf(rule, parentRule)
                }
                else -> addTopLevelRule(rule)
            }
        }
    }
    /**
     * Returns a list of pairs consisting of the rule identifier, and a string (multiline) for detailed error-printing.
     */
    fun topLevelRulesStillRunning() =
        tree.getChildren(ROOT_NODE_IDENTIFIER)
            .filter { topLevelRule -> tree.getResultForNode(topLevelRule).isRunning }
            .map { rule -> rule to "${rule.displayName}:\n" + tree.getResultForNode(rule).printForErrorLog() + "\n" }
    /**
     * Returns a list of pairs consisting of the rule identifier, and a string (multiline) for detailed error-printing.
     */
    fun topLevelRulesWithViolationOrErrorOrRunning() =
        tree.getChildren(ROOT_NODE_IDENTIFIER).filter { topLevelRule ->
            val res = tree.getResultForNode(topLevelRule)
            (res.status != TreeViewStatusEnum.VERIFIED &&
                res.status != TreeViewStatusEnum.SKIPPED &&
                    res.status != TreeViewStatusEnum.BENIGN_SKIPPED)
        }.map { rule ->
            rule to "${rule.displayName}:\n" + tree.getResultForNode(rule).printForErrorLog() + "\n"
        }

    @TestOnly
    fun treePublic() = tree

    /** Collect all paths in the given tree. Each path goes from root to leaf. */
    @TestOnly
    fun pathsToLeaves(): Set<List<DisplayableIdentifier>> {
        val paths = mutableSetOf<List<DisplayableIdentifier>>()
        fun rec(node: DisplayableIdentifier, prefix: List<DisplayableIdentifier>) {
            when (val children = tree.getChildren(node)) {
                emptySequence<DisplayableIdentifier>() -> paths.add(prefix + node)
                else -> children.forEach { child ->
                    rec(child, prefix + node)
                }
            }
        }
        rec(ROOT_NODE_IDENTIFIER, emptyList())
        return paths
    }
    @TestOnly
    fun nodes(): Set<DisplayableIdentifier> {
        return tree.getAllNodes().filterToSet { it != ROOT_NODE_IDENTIFIER }
    }
    /**
     * This invariant is violated if there is any adjacent pair `(parent, child)` on any path in the tree, going from
     * root to leaf, such that `parent.status < child.status` or `!parent.isRunning && child.isRunning`.
     */
    @TestOnly
    fun findNotMonotonicallyDescendingPath(): List<DisplayableIdentifier>? {
        fun <T : Comparable<T>> Iterable<T>.isNotDescending(lt: (T, T) -> Boolean) =
            this.zipWithNext().any { (l, r) -> lt(l, r) }
        return pathsToLeaves().find { path ->
            val suffix = path.drop(1) // can't get result node for the root
            val statuses = suffix.map { di -> tree.getResultForNode(di).status }
            val isRunnings = suffix.map { di -> tree.getResultForNode(di).isRunning }
            statuses.isNotDescending { curr, next -> curr < next } ||
                isRunnings.isNotDescending { curr, next -> !curr && next } }
    }
}

/**
 * A view of this result, which is tree-view reportable and should be used to produce
 * the corresponding Json element of this result.
 */
class OutputReportView(
    val ruleIdentifier: RuleIdentifier,
    override val treeViewRepBuilder: TreeViewRepBuilder<JsonObjectBuilder>,
) : TreeViewReportable {
    /** dump rule output to file. returns the written file name on success */
    fun writeToFile(): String? {
        val manager = ArtifactManagerFactory.enabledOrNull() ?: return null
        val key = RuleOutputArtifactsKey(this.ruleIdentifier)
        val artifacts = manager
            .getOrRegisterRuleOutputArtifacts(key)
            ?: error("broken invariant: can't fail for enabled artifact manager")

        val fileName = artifacts.ruleOutputFileName

        val path = manager
            .getRegisteredArtifactPathOrNull(fileName)
            ?: error("broken invariant: we made sure that the artifact has been registered")

        return try {
            val json = this.toJsonString()
            ArtifactFileUtils.getWriterForFile(path, overwrite = true).use { it.write(json) }
            fileName
        } catch (e: IOException) {
            logger.error("Write of rule ${key.ruleIdentifier} failed: $e")
            null
        }
    }
}

private fun IRule.treeViewLocation(): TreeViewLocation? {
    /** for parametric rules, we instead use the range of the instantiated method(s) - even if it's empty */
    val range = (this as? CVLSingleRule)?.methodInstantiationRange() ?: this.range

    return range as? TreeViewLocation
}
