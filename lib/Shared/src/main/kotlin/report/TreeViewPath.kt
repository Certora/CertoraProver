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

import com.certora.collect.*
import datastructures.stdcollections.*
import event.EventAttributeWithStringRepr
import event.ReportableAsEventAttribute
import kotlinx.serialization.json.*
import spec.rules.IRule
import utils.hashObject
import utils.toHexString
import java.security.MessageDigest


/**
 * Uniquely characterizes an element in a treeView report through its path in the tree.
 * A [TreeViewPath] can be either [TreeViewPath.NonEmpty] or [TreeViewPath.Empty]
 *
 */
@Treapable
sealed class TreeViewPath: TreeViewReportable {

    /**
     * The [TreeViewPathNode]s of this [TreeViewPath]
     */
    abstract val nodes: List<TreeViewPathNode>

    /**
     * String representation of the hashDigest of the list of [TreeViewPathNode]s of this [TreeViewPath].
     * The length of the digest is 256 bits, or 64 hexadecimal digits.
     */
    val pathHash: String
        get() = MessageDigest.getInstance("SHA-256").digest(
            hashDigestInput.toByteArray()
        ).toHexString()


    /**
     * The string that should be used to compute the [pathHash] digest.
     */
    protected abstract val hashDigestInput: String

    /**
     * A [TreeViewPath] that may be extended with one or more [TreeViewPathNode]s via concatenation.
     * Only paths that end with nodes that can have children may be [Extendable]. Effectively,
     * it applies to paths that end with a [TreeViewPathNode.Rule] node (i.e., [NonEmpty.LastRuleNode]) or the [Empty] path.
     */
    interface Extendable  {
        operator fun plus(other: TreeViewPathNode.Rule): NonEmpty.LastRuleNode
        operator fun plus(other: TreeViewPathNode.Assert): NonEmpty.LastAssertNode

        operator fun plus(other: NonEmpty.LastRuleNode): NonEmpty.LastRuleNode
        operator fun plus(other: NonEmpty.LastAssertNode): NonEmpty.LastAssertNode
    }

    /**
     * Uniquely characterizes an element in a treeView report through its path in the tree.
     * @property prefixNodes the prefix nodes ([TreeViewPathNode.Rule]) of the path,
     * ordered from the first to the one before last node.
     * @property lastNode the last node ([TreeViewPathNode.Rule] or [TreeViewPathNode.Assert]) of the path.
     *
     */
    sealed class NonEmpty : TreeViewPath() {

        abstract val prefixNodes: List<TreeViewPathNode.Rule>
        abstract val lastNode: TreeViewPathNode

        /**
         * Returns a new [TreeViewPath] that only consists of the prefix of this path ([prefixNodes]), which
         * may be [Empty].
         */
        fun dropLast(): TreeViewPath =
            when (prefixNodes.size) {
                0 -> {
                    Empty
                }

                1 -> {
                    LastRuleNode(emptyList(), prefixNodes.last())
                }

                else -> {
                    LastRuleNode(prefixNodes.subList(0, prefixNodes.size - 1), prefixNodes.last())
                }
            }

        /**
         * A non-empty path whose last node is [TreeViewPathNode.Rule].
         */
        data class LastRuleNode(
            override val prefixNodes: List<TreeViewPathNode.Rule>,
            override val lastNode: TreeViewPathNode.Rule
        ) : NonEmpty(), Extendable, ReportableAsEventAttribute<TreeViewPathEventAttribute> {

            override val hashDigestInput: String
                get() = nodes.joinToString(separator = "->") { it.rule.declarationId }

            override val treeViewRepBuilder = TreeViewRepJsonObjectBuilder(
                objectBuilderAction = treeViewRepBuilderAction
            )

            override fun toString(): String = super.toString()

            override val asEventAttribute: TreeViewPathEventAttribute
                get() = TreeViewPathEventAttribute(
                    pathHash,
                    lastNode.asEventAttribute.ruleId,
                    prefixNodes.lastOrNull()?.asEventAttribute?.ruleId,
                    dropLast().pathHash,
                    nodes.map { it.asEventAttribute },
                    prefixNodes.size
                )

            override val nodes: List<TreeViewPathNode.Rule> = prefixNodes + lastNode

            override fun plus(other: TreeViewPathNode.Rule): LastRuleNode =
                LastRuleNode(prefixNodes = this.prefixNodes + this.lastNode, lastNode = other)

            override fun plus(other: TreeViewPathNode.Assert): LastAssertNode =
                LastAssertNode(prefixNodes = this.prefixNodes + this.lastNode, lastNode = other)

            override fun plus(other: LastRuleNode): LastRuleNode =
                LastRuleNode(
                    prefixNodes = this.prefixNodes + this.lastNode + other.prefixNodes,
                    lastNode = other.lastNode
                )

            override fun plus(other: LastAssertNode): LastAssertNode =
                LastAssertNode(
                    prefixNodes = this.prefixNodes + this.lastNode + other.prefixNodes,
                    lastNode = other.lastNode
                )

        }

        /**
         * A non-empty path whose last node is [TreeViewPathNode.Assert].
         */
        data class LastAssertNode(
            override val prefixNodes: List<TreeViewPathNode.Rule>,
            override val lastNode: TreeViewPathNode.Assert
        ) : NonEmpty() {

            override val hashDigestInput: String
                get() = when {
                    prefixNodes.isEmpty() -> lastNode.assertId.toString()
                    else -> prefixNodes.joinToString(separator = "->") { it.rule.declarationId } + "->" + lastNode.assertId
                }

            override val nodes: List<TreeViewPathNode>
                get() = prefixNodes + lastNode

            override val treeViewRepBuilder = TreeViewRepJsonObjectBuilder(
                objectBuilderAction = treeViewRepBuilderAction
            )

        }

        override fun toString(): String = nodes.joinToString(separator = "->")

        protected val treeViewRepBuilderAction: JsonObjectBuilder.() -> Unit
            get() = prefixNodes.foldRight(
                initial = {
                    lastNode.treeViewRepBuilder coalesceInto this
                    put(key = "next", null)
                }
            )
            { head: TreeViewPathNode, tail: JsonObjectBuilder.() -> Unit ->
                {
                    head.treeViewRepBuilder coalesceInto this
                    putJsonObject(key = "next", tail)
                }
            }
    }

    /**
     * The empty [TreeViewPath]
     */
    object Empty : TreeViewPath(), Extendable {

        override val hashDigestInput: String
            get() = ""

        override val nodes: List<TreeViewPathNode>
            get() = emptyList()

        override fun plus(other: TreeViewPathNode.Rule): NonEmpty.LastRuleNode =
            NonEmpty.LastRuleNode(emptyList(), other)

        override fun plus(other: TreeViewPathNode.Assert): NonEmpty.LastAssertNode =
            NonEmpty.LastAssertNode(emptyList(), other)

        override fun plus(other: NonEmpty.LastRuleNode): NonEmpty.LastRuleNode = other

        override fun plus(other: NonEmpty.LastAssertNode): NonEmpty.LastAssertNode = other

        override fun toString(): String = "Empty"

        override fun hashCode(): Int = hashObject(this)

        override val treeViewRepBuilder = TreeViewRepJsonObjectBuilder { }
    }

}

    /**
 * A node of a [TreeViewPath].
 */
@Treapable
sealed class TreeViewPathNode : TreeViewReportable {

    abstract override val treeViewRepBuilder: TreeViewRepBuilder<JsonObjectBuilder>

    /**
     * A node that corresponds to the status of [rule] in a treeView report.
     */
    data class Rule(val rule: IRule) : TreeViewPathNode(),
        ReportableAsEventAttribute<TreeViewPathRuleNodeEventAttribute> {

        override val asEventAttribute: TreeViewPathRuleNodeEventAttribute
            get() = TreeViewPathRuleNodeEventAttribute(rule.declarationId)

        override fun toString(): String = "Rule(${rule.declarationId})"


        override fun hashCode(): Int = rule.declarationId.hashCode()
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as Rule

            return rule.declarationId == other.rule.declarationId
        }

        override val treeViewRepBuilder = TreeViewRepJsonObjectBuilder {
            put(key = "ruleName", value = rule.declarationId)
            put(key = "assertId", value = null)
            put(key = "assertMessage", value = null)
        }
    }

    /**
     * A node that corresponds to the status of a multi-assert sub-rule in a treeView report.
     * A multi-assert sub-rule is auto-generated for each (user-defined)
     * assert statement that occurs in a compiled rule.
     */
    data class Assert(val assertId: Int, val assertMsg: String) : TreeViewPathNode() {
        override fun toString(): String = "Assert(${assertId})"

        override val treeViewRepBuilder = TreeViewRepJsonObjectBuilder {
            put(key = "ruleName", value = null)
            put(key = "assertId", value = assertId)
            put(key = "assertMessage", value = assertMsg.takeUnless { it.isEmpty() })
        }
    }
}

/**
 * Casts this [TreeViewPath] to [TreeViewPath.Extendable].
 * Throws an [IllegalArgumentException] if the casting fails.
 */
fun TreeViewPath.asExtendable(): TreeViewPath.Extendable {
    require(this is TreeViewPath.Extendable) {
        "Expected $this to be an Extendable TreeViewPath"
    }
    return this
}

/**
 * @param uniqueId A unique identifier for a [TreeViewPath]
 * @param ruleId The rule id of an [IRule]
 * @param parentRuleId The rule Id of the parent of the [IRule]
 * @param parentUniqueId A unique identifier for a parent of a rule in a [TreeViewPath]
 * @param nodesEventAttributes a collection of [IRule.declarationId]s of [TreeViewPathNode.Rule.rule]
 * @param levelOfLastNode the level of the [TreeViewPathNode]
 */
data class TreeViewPathEventAttribute(
    val uniqueId: String,
    val ruleId: String,
    val parentRuleId: String?,
    val parentUniqueId: String,
    val nodesEventAttributes: List<TreeViewPathRuleNodeEventAttribute>,
    val levelOfLastNode: Int
): EventAttributeWithStringRepr {

    override val stringRepr: String
        get() = nodesEventAttributes.joinToString(separator = "->") { it.stringRepr }

}

@JvmInline
value class TreeViewPathRuleNodeEventAttribute(
    val ruleId: String
): EventAttributeWithStringRepr {

    override val stringRepr: String
        get() = ruleId
}
