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

package analysis

import algorithms.SimpleDominanceAnalysis
import algorithms.findRoots
import annotations.PerformanceCriticalUsesOnly
import com.certora.collect.*
import datastructures.LinkedArrayHashMap
import datastructures.stdcollections.*
import datastructures.stdcollections.toMap
import log.*
import scene.ITACMethod
import tac.MetaKey
import tac.MetaMap
import tac.NBId
import tac.Tag
import utils.*
import vc.data.*
import java.io.Serializable
import java.math.BigInteger
import kotlin.collections.last

/*
    A "pointer" to a unique command within a TAC program. Effectively
    a tuple of block id and index into the block.
 */
@KSerializable
@Treapable
data class CmdPointer(val block: NBId, val pos: Int) : AmbiSerializable, Comparable<CmdPointer> {
    companion object {
        val comparator = Comparator.comparing<CmdPointer, NBId> { it.block }.thenComparing { it: CmdPointer -> it.pos }
    }

    override fun compareTo(other: CmdPointer): Int {
        return comparator.compare(this, other)
    }

    operator fun plus(by: Int) = CmdPointer(block, pos + by)
    operator fun minus(by: Int) = CmdPointer(block, pos - by)
}

/**
 * [path] describes the path from the root of the tree (the command) to the pointed-to expression.
 * */
@Treapable
data class ExpPointer(val cmdPointer: CmdPointer, val path: Path) : Serializable {
    constructor(cmdPointer: CmdPointer, vararg path: Int) : this(cmdPointer, Path(path.toList()))
    constructor(cmdPointer: CmdPointer, path: List<Int>) : this(cmdPointer, Path(path))
    init {
        check(path.isNotEmpty())
        { "An ExpPointer with an empty path makes no sense (does not point to an expression). " +
                "(cmdPointer: $cmdPointer)" }
    }

    @Treapable
    data class Path(val list: List<Int>) : List<Int> by list {
        constructor(vararg i: Int) : this(i.toList())
        fun dropFirst(n: Int = 1) = Path(list = list.drop(n)) // NB sublist is shallow ... make a deep copy?
        fun dropLast() = Path(list = list.dropLast(1)) // NB sublist is shallow ... make a deep copy?
        fun extend(pos: Int) = Path(list = list + listOf(pos))
        fun replaceLast(pos: Int) = Path(list = list.dropLast(1) + listOf(pos))

        override fun toString(): String = list.toString()
    }

    val depth get() = path.size
    fun extend(pos: Int) = this.copy(path = path.extend(pos))
    fun dropLast() = this.copy(path = path.dropLast()) // NB sublist is shallow ... make a deep copy?
    @Suppress("unused")
    fun replaceLast(pos: Int) = this.copy(path = path.replaceLast(pos))

    fun replacePrefix(prefix: ExpPointer, substitute: ExpPointer): ExpPointer {
        require(prefix isPrefixOf this)
        { "first argument bust be a prefix of this ExpPointer ($this), but is \"$prefix\"" }
        require(substitute.cmdPointer == this.cmdPointer)
        { "expecting substitute ($substitute) to have the same cmdPointer as this ($this) " }
        return this.copy(path = Path(substitute.path + path.dropFirst(prefix.path.size)))
    }

    infix fun isPrefixOf(other: ExpPointer): Boolean =
        this.cmdPointer == other.cmdPointer && this.path isPrefixOf other.path

    private infix fun <T> List<T>.isPrefixOf(other: List<T>): Boolean {
        if (this.size > other.size) return false
        this.forEachIndexed { index, item ->
            if (other[index] != item) return false
        }
        return true
    }

    override fun toString(): String {
        return "expPtr(cmd: $cmdPointer, path: $path)"
    }

    companion object {
        /** A pointer that points to the first argument of the command (e.g. if [cmdPointer] points to the command
         * `x := f(z)` then the resulting ExpPointer will point to `x`) */
        fun lhsOfAssign(cmdPointer: CmdPointer) = ExpPointer(cmdPointer, Path(listOf(0)))
        /** A pointer that points to the second argument of the command (e.g. if [cmdPointer] points to the command
         * `x := f(z)` then the resulting ExpPointer will point to `f(z)`) */
        fun rhsOfAssign(cmdPointer: CmdPointer) = ExpPointer(cmdPointer, Path(listOf(1)))
    }
}


/* No relation to Coq. A Located TACCmd, or "LTACCmd".
   Effectively a tuple of a TACCmd and its location within a graph.
   It's an invariant that the command pointed to by ptr is equivalent to cmd (although
   they may not be referentially equal)
 */
@KSerializable
data class LTACCmd(override val ptr: CmdPointer, override val cmd: TACCmd.Simple): LTACCmdGen<TACCmd.Simple> // TODO Merge: Should also have : HasTACSimpleCmd

@Treapable
data class LTACSymbol(val ptr: CmdPointer, val symbol: TACSymbol) : Serializable

@Treapable
data class LTACVar(val ptr: CmdPointer, val v: TACSymbol.Var) : Serializable {
    constructor(pair: Pair<CmdPointer, TACSymbol.Var>) : this(pair.first, pair.second)
}

data class LTACAnnotation<T: Serializable>(
    val annotation: T,
    val cmd: LTACCmdView<TACCmd.Simple.AnnotationCmd>
) {
    val wrapped: LTACCmd get() = cmd.wrapped
    val ptr: CmdPointer get() = cmd.ptr
}

data class LTACExp(val ptr: ExpPointer, val exp: TACExpr) : Serializable

inline fun <reified T : U, U: TACCmd> LTACCmdGen<U>.narrow(): GenericLTACCmdView<T> {
    check(this.cmd is T) { "cmd ${this.cmd} is not ${T::class.java}" }
    return GenericLTACCmdView(this.uncheckedAs())
}

inline fun <reified T : TACCmd> LTACCmd.narrow(): LTACCmdView<T> {
    check(this.cmd is T) { "cmd ${this.cmd} is not ${T::class.java}" }
    return LTACCmdView(this)
}

inline fun <reified T : U, U : TACCmd> LTACCmdGen<U>.maybeNarrow(): GenericLTACCmdView<T>? {
   return if (cmd !is T) {
       null
   } else {
       this.narrow()
   }
}

inline fun <reified T : TACCmd.Simple> LTACCmd.maybeNarrow(): LTACCmdView<T>? {
    return if (this.cmd !is T) {
        null
    } else {
        this.narrow()
    }
}

inline fun <reified T : TACExpr> LTACExp.narrow(): LTACExpView<T> {
    check(this.exp is T) { "exp ${this.exp} is not ${T::class.java}" }
    return LTACExpView(this)
}

inline fun <reified T : TACExpr> LTACExp.maybeNarrow(): LTACExpView<T>? =
    if (this.exp !is T) {
        null
    } else {
        this.narrow()
    }

inline fun <reified E: TACExpr> LTACCmd.maybeExpr(): ExprView<E>? {
    return if(this.cmd is TACCmd.Simple.AssigningCmd.AssignExpCmd && this.cmd.rhs is E) {
        this.enarrow()
    } else {
        null
    }
}

inline fun <reified T : TACExpr> LTACCmd.enarrow(): ExprView<T> {
    check(this.cmd is TACCmd.Simple.AssigningCmd.AssignExpCmd)
    check(this.cmd.rhs is T)
    return ExprView(this)
}

inline fun <reified T : TACSummary> LTACCmd.snarrowOrNull(): T? =
    if (this.cmd is TACCmd.Simple.SummaryCmd && this.cmd.summ is T) {
        this.cmd.summ
    } else {
        null
    }

fun <T : Serializable> LTACCmd.annotation(k: MetaKey<T>) : T? = this.narrow<TACCmd.Simple.AnnotationCmd>().cmd.maybeAnnotation(k)

fun <T : Serializable> LTACCmd.maybeAnnotation(k: MetaKey<T>) : T? = this.maybeNarrow<TACCmd.Simple.AnnotationCmd>()?.cmd?.maybeAnnotation(k)
fun LTACCmd.maybeAnnotation(k: MetaKey<Nothing>) : Boolean = this.maybeNarrow<TACCmd.Simple.AnnotationCmd>()?.cmd?.maybeAnnotation(k) == true

fun <T: Serializable> LTACCmd.annotationView(k: MetaKey<T>) = this.maybeNarrow<TACCmd.Simple.AnnotationCmd>()?.annotationView(k)

fun <T: Serializable> LTACCmdView<TACCmd.Simple.AnnotationCmd>.annotationView(k: MetaKey<T>) = this.cmd.maybeAnnotation(k)?.let { payload ->
    LTACAnnotation(payload, this)
}

fun LTACCmd.asSnippetCmd(): SnippetCmd? = maybeAnnotation(TACMeta.SNIPPET)

fun <T : Serializable> GenericLTACCmd<*>.maybeAnnotation(k: MetaKey<T>) : T? = this.toLTACCmd()?.maybeAnnotation(k)
fun GenericLTACCmd<*>.maybeAnnotation(k: MetaKey<Nothing>) : Boolean = this.toLTACCmd()?.maybeAnnotation(k) == true

inline fun <reified T : TACSymbol> LTACSymbol.narrow(): TACSymbolView<T> {
    check(this.symbol is T)
    return TACSymbolView(this)
}

@JvmInline
value class TACSymbolView<out T : TACSymbol>(val wrapped: LTACSymbol) {
    val ptr: CmdPointer get() = wrapped.ptr

    val cmd: T
        get() = wrapped.symbol.uncheckedAs<T>()
}

@JvmInline
value class GenericLTACCmdView<out T : TACCmd>(val wrapped: LTACCmdGen<T>) {
    val ptr: CmdPointer get() = wrapped.ptr

    val cmd: T
        get() = wrapped.cmd.uncheckedAs<T>()

    operator fun component1() = ptr
    operator fun component2() = cmd
}

@JvmInline
value class LTACCmdView<out T : TACCmd>(val wrapped: LTACCmd) {
    val ptr: CmdPointer get() = wrapped.ptr

    val cmd: T
        get() = wrapped.cmd.uncheckedAs<T>()

    operator fun component1() = ptr
    operator fun component2() = cmd
}

@JvmInline
value class LTACExpView<out T : TACExpr>(val wrapped: LTACExp) {
    val ptr: ExpPointer get() = wrapped.ptr

    val exp: T
        get() = wrapped.exp.uncheckedAs<T>()

    operator fun component1() = ptr
    operator fun component2() = exp
}

@JvmInline
value class ExprView<out T>(val wrapped: LTACCmd) {
    val ptr: CmdPointer get() = wrapped.ptr
    val cmd: TACCmd.Simple.AssigningCmd.AssignExpCmd get() = wrapped.cmd as TACCmd.Simple.AssigningCmd.AssignExpCmd

    val exp: T
        get() = (wrapped.cmd as TACCmd.Simple.AssigningCmd.AssignExpCmd).rhs.uncheckedAs<T>()
    val lhs: TACSymbol.Var get() = (wrapped.cmd as TACCmd.Simple.AssigningCmd).lhs

    val toCmdView: LTACCmdView<TACCmd.Simple.AssigningCmd.AssignExpCmd> get() = LTACCmdView(wrapped)
}

inline fun <U, reified T : U> ExprView<U>.narrow(): ExprView<T> {
    check(this.exp is T)
    return this.uncheckedAs<ExprView<T>>()
}


inline fun <reified T : TACSummary> TACCmd.Simple.snarrowOrNull(): T? {
    return if (this is TACCmd.Simple.SummaryCmd && this.summ is T) {
        this.summ
    } else {
        null
    }
}

interface LTACCmdGen<out T: TACCmd> {
    val ptr: CmdPointer
    val cmd: T
}

operator fun <T : TACCmd> LTACCmdGen<T>.component1() = ptr
operator fun <T : TACCmd> LTACCmdGen<T>.component2() = cmd

interface TACBlockGen<out T: TACCmd, out U: LTACCmdGen<T>> {
    val id: NBId
    val commands: List<U>
}

data class GenericLTACCmd<out T: TACCmd>(override val ptr: CmdPointer, override val cmd: T) : LTACCmdGen<T>, Serializable {
    fun toLTACCmd() =
        if(this.cmd is TACCmd.Simple) {
            LTACCmd(ptr, cmd)
        } else {
            null
        }

}

open class GenericTACBlock<T: TACCmd, U: LTACCmdGen<T>>(override val id: NBId, override val commands: List<U>) : TACBlockGen<T, U>, Serializable

abstract class GenericTACCommandGraph<T: TACCmd, U: LTACCmdGen<T>, V: TACBlockGen<T, U>> {
    abstract val blockGraph: BlockGraph
    abstract val code: BlockNodes<T>
    abstract val symbolTable: TACSymbolTable
    open val name: String? get() = this.toString()

    fun succCommand(node: CmdPointer) = succ(node).map(::elab)

    fun predCommand(node: CmdPointer) = pred(node).map(::elab)

    fun succ(l: U) = succCommand(l.ptr)

    fun pred(l: U) = predCommand(l.ptr)

    fun succPointer(l: U) = succ(l.ptr)
    @Suppress("unused")


    fun predPointer(l: U) = pred(l.ptr)

    fun toCommand(p: CmdPointer) = toCommand(p.block, p.pos)
    fun toCommand(block: NBId, pos: Int) = code[block]!![pos]

    fun getLhs(p: CmdPointer) = toCommand(p).getLhs()

    abstract fun elab(p: CmdPointer): U

    fun elab(b: NBId) = _nodeIdToBlock[b] ?: error("Block id $b is not in the graph")

    fun pred(b: NBId) = blockPred[b] ?: error("Block id $b is not in the graph")

    @Suppress("USELESS_CAST")
    @PerformanceCriticalUsesOnly
    fun predUntyped(b: Any?): Any? = (blockPred as Map<*, *>)[b]

    fun succ(b: NBId) = blockSucc[b] ?: error("Block id $b is not in the graph")

    fun dump() {
        code.entries.forEach { (bid, blocks) ->
            println("Block ${bid}")
            blocks.forEachIndexed { i, b ->
                println("(${bid}, ${i}): ${b} (pred: ${pred(CmdPointer(bid, i))})")
            }
            println("<<")
        }
    }

    fun dump(nbId: NBId, l: Logger, log: Logger.(() -> String) -> Unit = Logger::debug) {
        l.log {
            "Context: Contents of $nbId"
        }
        if (l.isDebugEnabled) {
            elab(nbId).commands.forEach {
                l.log { it.toString() }
            }
        }
    }


    /**
     * From [low] to [high], or if [reversed] then from [high] to [low]. inclusive on both ends.
     */
    fun lcmdSequence(
        block: NBId,
        low: Int = 0,
        high: Int = code[block]!!.size - 1,
        reverse: Boolean = false,
    ): Sequence<U> {
        val cmds = elab(block).commands
        if (low > high) {
            return emptySequence()
        }
        require(high < code[block]!!.size) {
            "$high exceeds maxmimal allowed index in $block : (${cmds.size - 1})"
        }
        require(low >= 0) {
            "$low should be at least 0"
        }
        return sequence {
            val range = if (reverse) {
                high downTo low
            } else {
                low..high
            }
            for (i in range) {
                yield(cmds[i])
            }
        }
    }

    fun blockCmdsBackwardFrom(p : CmdPointer) =
        lcmdSequence(p.block, high = p.pos, reverse = true)

    fun blockCmdsBackwardSeq(b : NBId) =
        lcmdSequence(b, reverse = true)

    fun blockCmdsForwardFrom(p : CmdPointer) =
        lcmdSequence(p.block, low = p.pos)

    fun iterateUntil(end: CmdPointer) =
        lcmdSequence(end.block, high = end.pos - 1).asIterable()

    /**
     * Going backwards from [end] - 1, returns the element satisfying [pred] within the block,
     * or null if none is found.
     */
    inline fun findLastUntil(end: CmdPointer, pred: (it: T) -> Boolean) =
        lcmdSequence(end.block, high = end.pos - 1, reverse = true)
            .firstOrNull { u -> pred(u.cmd) }?.ptr


    /**
     * Sequence of commands in [blocks] according to their order. The commands within each block
     * are reversed if [reverse] is true.
     */
    fun lcmdSequence(blocks : Iterable<NBId>, reverse: Boolean = false): Sequence<U> =
        blocks.asSequence().flatMap {
            lcmdSequence(it, reverse = reverse)
        }


    /**
     * Returns an [Iterable] for the commands within a block from [start] until [end] (excluding it).
     * [excludeStart] determines whether the given [start] command is included in the iteration.
     */
    fun iterateBlock(start: CmdPointer, end: Int = code[start.block]!!.size, excludeStart: Boolean = true) =
        lcmdSequence(
            start.block,
            low = start.pos.letIf(excludeStart) { it + 1 },
            high = end - 1
        ).asIterable()

    /** command sequence for blocks in [blocks] from [start] (inclusive) until the end. */
    fun iterateFrom(start: CmdPointer, blocks: List<NBId>): Sequence<U> {
        require(start.block in blocks) { "block ${start.block} not in block list." }
        val blockIndex = blocks.indexOf(start.block)
        return lcmdSequence(start.block, low = start.pos) +
            (blockIndex + 1 until blocks.size).asSequence().flatMap {
                lcmdSequence(blocks[it])
            }
    }

    /** the reverse sequence of commands starting at [end] (excluding) within its block */
    fun iterateRevBlock(end: CmdPointer) =
        lcmdSequence(end.block, high = end.pos - 1, reverse = true)

    val pointers: Sequence<CmdPointer>
        get() = sequence {
            code.forEachEntryInline { (block, cmds) ->
                for (pos in cmds.indices) {
                    yield(CmdPointer(block, pos))
                }
            }
        }

    val commands: Sequence<U> = pointers.map { elab(it) }

    abstract val blocks: List<V>

    val blockIds by lazy { blocks.mapToSet { it.id } }

    private val _nodeIdToBlock by lazy { blocks.map { it.id to it }.toMap() }

    val blockSucc: BlockGraph by lazy {
        LinkedArrayHashMap<NBId, TreapSet<NBId>>().apply {
            code.forEachEntry { (key, _) ->
                this[key] = treapSetOf()
            }
            blockGraph.forEachEntry { (src, dsts) ->
                this[src] = this[src].orEmpty() + dsts
            }
        }
    }

    val blockPred: BlockGraph by lazy {
        LinkedArrayHashMap<NBId, TreapSet<NBId>>().apply {
            blockSucc.forEachEntry { (key, _) ->
                put(key, treapSetOf())
            }
            blockSucc.forEachEntry { (src, dsts) ->
                dsts.forEach { dst ->
                    this[dst] = this[dst].orEmpty() + src
                }
            }
        }
    }

    val rootBlockIds by lazy { blockPred.filterValues { it.isEmpty() }.keys }
    val rootBlocks by lazy { rootBlockIds.map(::elab) }

    val sinkBlockIds by lazy { blockSucc.filterValues { it.isEmpty() }.keys }
    val sinkBlocks by lazy { sinkBlockIds.map(::elab) }

    val roots by lazy { findRoots(blockGraph).map { b -> CmdPointer(b, 0) }.map(::elab) }
    val sinks by lazy { blockSucc.filterValues { it.isEmpty() }.keys.map { elab(CmdPointer(it, code[it]!!.size - 1)) } }


    fun pred(b: V): List<V> = predBlock(b.id)


    @Deprecated("Use consistent pred instead", replaceWith = ReplaceWith("pred"))
    fun predBlock(b: V): List<V> = predBlock(b.id)
    fun predBlock(b: NBId): List<V> =
            pred(b).map(this::elab)

    fun succ(b: V): List<V> = succBlock(b.id)

    @Deprecated("Use consistent succ instead", replaceWith = ReplaceWith("succ"))
    fun succBlock(b: V): List<V> = succ(b.id).map(this::elab)
    fun succBlock(b: NBId): List<V> = succ(b).map(this::elab)

    private val succRelation: Map<NBId, Set<CmdPointer>> by lazy {
        mutableMapOf<NBId, Set<CmdPointer>>().apply {

            code.forEach { id, _ /* cmds */ ->
                put(id, succ(id).map { CmdPointer(it, 0) }.toTreapSet().orEmpty())
            }
        }
    }

    private val predRelation: Map<NBId, Set<CmdPointer>> by lazy {
        mutableMapOf<NBId, Set<CmdPointer>>().apply {
            code.forEach { id, _ /* cmds */ ->
                put(id, pred(id).map { CmdPointer(it, code[it]!!.size - 1) }.toTreapSet().orEmpty())
            }
        }
    }

    fun succ(node: CmdPointer): Set<CmdPointer> {
        val block = code[node.block] ?: error("Node pointer $node is not in the graph")
        check (node.pos < block.size) { "Node pointer $node out of range for block" }
        if (node.pos + 1 == block.size) {
            return succRelation[node.block]!!
        } else {
            return treapSetOf(CmdPointer(node.block, node.pos + 1))
        }
    }

    fun pred(node: CmdPointer): Set<CmdPointer> {
        check (node.block in code) { "Node pointer $node is not in the graph" } // do we really need this?
        if (node.pos == 0) {
            return predRelation[node.block]!!
        } else {
            return treapSetOf(CmdPointer(node.block, node.pos - 1))
        }
    }

    /**
     * Returns the sibling of [b] for its parent [p]
     */
    fun siblingOf(b: NBId, p: NBId): NBId? {
        return succ(p).singleOrNull { it != b }
    }

    fun toBlockGraph(): BlockGraph = blockGraph
    fun toRevBlockGraph(): BlockGraph = blockPred
}

sealed class PathCondition : ToLExpression {

    /**
     * A path condition that relies on the value of [v]
     */
    sealed interface ConditionalOn {
        val v: TACSymbol.Var
    }

    object TRUE : PathCondition() {
        override fun toString(): String = "TRUE"

        override fun toLExpression(
            conv: ToLExpression.Conv,
            meta: MetaMap?,
        ): LExpression =
            conv.lxf.litBool(true)
    }

    data class EqZero(override val v: TACSymbol.Var) : PathCondition(), ConditionalOn {

        override fun toLExpression(
            conv: ToLExpression.Conv,
            meta: MetaMap?,
        ): LExpression = conv.lxf {
            if (v.tag == Tag.Bool) {
                not(const(v.toSMTRep(), Tag.Bool, meta))
            } else {
                ZERO eq const(v.toSMTRep(), v.tag, meta)
            }
        }
    }

    data class NonZero(override val v: TACSymbol.Var) : PathCondition(), ConditionalOn {

        override fun toLExpression(
            conv: ToLExpression.Conv,
            meta: MetaMap?,
        ): LExpression =
            if (v.tag == Tag.Bool) {
                conv.lxf.const(v.toSMTRep(), Tag.Bool, meta)
            } else {
                conv.lxf.not(
                    conv.lxf.eq(
                        conv.lxf.litInt(0),
                        conv.lxf.const(v.toSMTRep(), v.tag, meta)
                    )
                )
            }
    }

    data class Summary(val s: ConditionalBlockSummary) : PathCondition() {
        override fun toLExpression(
            conv: ToLExpression.Conv,
            meta: MetaMap?,
        ): LExpression =
            throw java.lang.UnsupportedOperationException("Cannot support summary condition $this in vc gen")
    }

    fun getAsAssumeCmd() : TACCmd.Simple = when (this) {
        is Summary, TRUE -> TACCmd.Simple.AssumeCmd(TACSymbol.True, "path condition assume")
        is EqZero -> TACCmd.Simple.AssumeNotCmd(this.v)
        is NonZero -> TACCmd.Simple.AssumeCmd(this.v, "path condition assume")
    }

    fun getAsExpression() = when (this) {
        TRUE -> TACSymbol.True.asSym()
        is EqZero -> {
            if (this.v.tag == Tag.Bool) {
                TACExpr.UnaryExp.LNot(this.v.asSym())
            } else {
                TACExpr.BinRel.Eq(this.v.asSym(), TACSymbol.lift(0).asSym())
            }
        }
        is NonZero -> {
            if (this.v.tag == Tag.Bool) {
                v.asSym()
            } else {
                TACExpr.UnaryExp.LNot(TACExpr.BinRel.Eq(this.v.asSym(), TACSymbol.lift(0).asSym()))
            }
        }
        else -> throw UnsupportedOperationException("Cannot get path condition $this as an expression")
    }

}


class CVLTACCommandGraph(
        override val blockGraph: BlockGraph,
        override val code: BlockNodes<TACCmd.Spec>,
        override val symbolTable: TACSymbolTable
): GenericTACCommandGraph<TACCmd.Spec, GenericLTACCmd<TACCmd.Spec>, GenericTACBlock<TACCmd.Spec, GenericLTACCmd<TACCmd.Spec>>>(), Serializable {
    override val blocks = code.keys.map {
        GenericTACBlock(it, commands = code[it]!!.mapIndexed { idx, simple ->
            GenericLTACCmd(CmdPointer(it, idx), simple)
        })
    }

    override fun elab(p: CmdPointer): GenericLTACCmd<TACCmd.Spec> = GenericLTACCmd(p, toCommand(p))
}

class EVMTACCommandGraph(
        override val blockGraph: BlockGraph,
        override val code: BlockNodes<TACCmd>,
        override val symbolTable: TACSymbolTable
): GenericTACCommandGraph<TACCmd, GenericLTACCmd<TACCmd>, GenericTACBlock<TACCmd, GenericLTACCmd<TACCmd>>>() {
    override val blocks = code.keys.map {
        GenericTACBlock(it, commands = code[it]!!.mapIndexed { idx, simple ->
            GenericLTACCmd(CmdPointer(it, idx), simple)
        })
    }

    override fun elab(p: CmdPointer): GenericLTACCmd<TACCmd> = GenericLTACCmd(p, toCommand(p))
}
/*
  A TACBlock, identified by its id and elaborated with the list of commands within
  the block. It is an invariant the list of commands are equivalent to those
  identified by the block id.
 */
data class TACBlock(override val id: NBId, override val commands: List<LTACCmd>): TACBlockGen<TACCmd.Simple, LTACCmd>


interface GraphPathConditions {
    fun pathConditionsOf(blockId: NBId): Map<NBId, PathCondition>
}

object SimplePathConditionReasoning {
    fun pathConditions(last: LTACCmdGen<TACCmd.Simple>, s: Set<NBId>): Map<NBId, PathCondition> {
        val cmd = last.cmd
        return if (s.isEmpty()) {
            mapOf()
        } else if (s.size == 1) {
            mapOf(s.first() to PathCondition.TRUE)
        } else if (cmd is TACCmd.Simple.SummaryCmd && cmd.summ is ConditionalBlockSummary) {
            cmd.summ.successors.map {
                it to PathCondition.Summary(cmd.summ)
            }.toMap()
        } else if (cmd is TACCmd.Simple.JumpiCmd) {
            check(s.size == 2)
            if (cmd.cond !is TACSymbol.Var) {
                check(cmd.cond is TACSymbol.Const)
                if (cmd.cond.value != BigInteger.ZERO) {
                    mapOf(cmd.dst to PathCondition.TRUE)
                } else {
                    check(cmd.elseDst in s)
                    mapOf(cmd.elseDst to PathCondition.TRUE)
                }
            } else {
                check(cmd.dst in s) { "Expected to have ${cmd.dst} in $s" }
                check(cmd.elseDst in s)
                mapOf(
                    cmd.dst to PathCondition.NonZero(cmd.cond),
                    cmd.elseDst to PathCondition.EqZero(cmd.cond)
                )
            }
        } else {
            s.map { it to PathCondition.TRUE }.toMap()
        }
    }
}

interface HasDominanceAnalysis {
    val domination : SimpleDominanceAnalysis<NBId>
}

class TACCommandGraph(
    override val blockGraph: BlockGraph,
    override val code: BlockNodes<TACCmd.Simple>,
    override val symbolTable: TACSymbolTable,
    cache: TACCommandGraphAnalysisCache? = null,
    override val name: String
): GenericTACCommandGraph<TACCmd.Simple, LTACCmd, TACBlock>(), GraphPathConditions, HasDominanceAnalysis {

    companion object {
        // If building a TACCommandGraph from a CoreTACProgram, just use the pre-cached object.
        operator fun invoke(program: CoreTACProgram) = program.analysisCache.graph
    }

    override val domination get() = cache.domination

    override val blocks: List<TACBlock> = code.keys.map {
        TACBlock(it, commands = code[it]!!.mapIndexed { idx, simple ->
            LTACCmd(CmdPointer(it, idx), simple)
        })
    }

    override fun elab(p: CmdPointer): LTACCmd = LTACCmd(p, toCommand(p))

    fun elab(ep: ExpPointer) = LTACExp(ep, toExp(ep))

    fun elab(nbid: NBId, pos: Int) = elab(CmdPointer(nbid, pos))

    /**
     * [start] is the starting CmdPtr to iterate over.
     * Returns either an iterable of [LTACCmd]s preceding the first command satisfying [predicate],
     * or an error message if no such command was found.
     */
    fun iterateUntil(
        start: CmdPointer,
        predicate: (it: TACCmd.Simple) -> Boolean
    ): Either<Iterable<LTACCmd>, String> {

        var predicateHolds = false
        val cmds = mutableListOf<LTACCmd>().also {
            for (i in start.pos + 1 until code[start.block]!!.size) {
                val cmd = code.getValue(start.block)[i]
                if (predicate(cmd)) {
                    predicateHolds = true
                    break
                }
                it.add(LTACCmd(CmdPointer(start.block, i), cmd))
            }
        }.asIterable()
        return if (predicateHolds) {
            cmds.asIterable().toLeft()
        } else {
            "no command satisfied [predicate] while iterating the TACCommandGraph from the starting CmdPtr $start".toRight()
        }
    }

    /**
     * Starting from [start], iterate forward through the graph following single successors
     * of basic blocks. In other words, iterate from [start] until the end of the block.
     * Then, from [start]'s block, follow single successors through the graph, iterating through each
     * such block's commands. The sequence terminates if there are no single successors to follow.
     *
     * [start] is included in the sequence depending on [excludeStart]; if true, then the sequence starts
     * from the single, unique command immediately following [start].
     */
    fun interBlockForwardsFrom(start: CmdPointer, excludeStart: Boolean) : Sequence<LTACCmd> {
        val blockGenerator = sequence<NBId> {
            var currBlock: NBId? = start.block
            while(currBlock != null) {
                yield(currBlock)
                currBlock = succ(currBlock).singleOrNull()
            }
        }.asIterable()
        return sequence {
            var started = false
            for(lc in lcmdSequence(blockGenerator, reverse = false)) {
                if(started || (!excludeStart && lc.ptr == start)) {
                    yield(lc)
                }
                started = started || lc.ptr == start
            }
        }
    }

    /**
     * the same as [interBlockForwardsFrom], but in reverse: starting from [start] we go back through the block.
     * Afterwards, we follow single predecessor blocks, iterating through those blocks in reverse order.
     *
     * [start] is included in the sequence depending on [excludeStart]; if true, then the sequence starts
     * from the unique predecessor command of [start].
     */
    fun interBlockBackwardsFrom(start: CmdPointer, excludeStart: Boolean = true) : Sequence<LTACCmd> {
        val blockGenerator = sequence<NBId> {
            var currBlock : NBId? = start.block
            while(currBlock != null) {
                yield(currBlock)
                currBlock = pred(currBlock).singleOrNull()
            }
        }.asIterable()
        return sequence {
            var started = false
            for(lc in lcmdSequence(blockGenerator, reverse = true)) {
                if(started || (!excludeStart && lc.ptr == start)) {
                    yield(lc)
                }
                started = started || lc.ptr == start
            }
        }
    }


    private fun toExp(ep: ExpPointer): TACExpr {
        fun findInSubExp(path: ExpPointer.Path, subExp: TACExpr): TACExpr? {
            var curSubExp: TACExpr = subExp
            path.forEach { pathItem ->
                curSubExp = curSubExp.getOperands().getOrNull(pathItem) ?: return null
            }
            return curSubExp
        }

        fun findInSubExps(ptr: ExpPointer, subExps: List<TACExpr>): TACExpr? {
            val subExp = subExps.getOrNull(ptr.path.first()) ?: return null
            val path = ptr.path.dropFirst()
            if (path.isEmpty()) { return subExp }
            return findInSubExp(path, subExp)
        }

        val ltacCmd = elab(ep.cmdPointer)
        return findInSubExps(ep, ltacCmd.cmd.exprs())
            ?: error("ExpPointer ($ep) points nowhere: Could not find expression inside command ${ltacCmd.cmd} for " +
                    "path ${ep.path}")
    }

    val cache: TACCommandGraphAnalysisCache = cache ?: TACCommandGraphAnalysisCache(lazyOf(this))

    fun pathConditionsOf(x: CmdPointer): Map<CmdPointer, PathCondition> {
        return elab(x.block).let {
            if (it.commands.lastIndex == x.pos) {
                pathConditionsOf(x.block).map {
                    elab(it.key).commands.first().ptr to it.value
                }.toMap()
            } else {
                val succ = this.succ(x)
                assert(succ.size == 1)
                mapOf(succ.first() to PathCondition.TRUE)
            }
        }
    }

    override fun pathConditionsOf(blockId: NBId): Map<NBId, PathCondition> {
        return elab(blockId).let {
            SimplePathConditionReasoning.pathConditions(it.commands.last(), succ(blockId))
        }
    }


    /**
     * Maps pairs of a block, and its successor to the path condition between them.
     * ([NBId] B) -> ([NBId] -> [PathCondition])
     * (Just a convenience field on top of [pathConditionsOf])
     */
    val pathConditions: Map<NBId, Map<NBId, PathCondition>>  by lazy {
        mutableMapOf<NBId, Map<NBId, PathCondition>>().let { pathConditions ->
            this.blocks.map { it.id }.associateWithTo(pathConditions) { this.pathConditionsOf(it) }
        }
    }

    /**
     * Represents a graph scope. Code run within this scope allows the resolution of successor/predecessor relations
     * on blocks, command pointers, etc. w.r.t some underlying graph, i.e., within the scope of a graph.
     */
    interface Scope {
        fun CmdPointer.succ(): Set<CmdPointer>
        fun LTACCmd.succ(): List<LTACCmd>

        fun LTACCmd.pred(): List<LTACCmd>
        fun CmdPointer.pred(): Set<CmdPointer>

        fun NBId.succ(): Set<NBId>
        fun TACBlock.succ(): List<TACBlock>

        fun NBId.pred(): Set<NBId>
        fun TACBlock.pred(): List<TACBlock>

        /**
         * Elaborate a command pointer into the richer [LTACCmd]
         */
        fun CmdPointer.elab(): LTACCmd

        /**
         * Elabroate a block id into the richer [TACBlock]
         */
        fun NBId.elab(): TACBlock
    }

    /**
     * Runs some computation within a graph scope. Code in the passed lambda is run within the scope of a
     * [Scope] object which extends [CmdPointer], [LTACCmd], [NBId], and [TACBlock] with pred and succ functions
     */
    inline fun <R> scope(f: Scope.() -> R): R =
        (object : Scope {
            override fun CmdPointer.succ(): Set<CmdPointer> = succ(this)

            override fun LTACCmd.succ(): List<LTACCmd> = succCommand(this.ptr)

            override fun NBId.succ(): Set<NBId> = succ(this)

            override fun TACBlock.succ(): List<TACBlock> = succ(this)

            override fun LTACCmd.pred(): List<LTACCmd> = predCommand(this.ptr)

            override fun CmdPointer.pred(): Set<CmdPointer> = pred(this)

            override fun NBId.pred(): Set<NBId> = pred(this)

            override fun TACBlock.pred(): List<TACBlock> = pred(this)

            override fun CmdPointer.elab(): LTACCmd = elab(this)

            override fun NBId.elab(): TACBlock = elab(this)
        }).f()

    fun toSubGraph(
        toIsolate: Set<NBId>,
        prefixFilter: (LTACCmd) -> Boolean,
        sinkModel: (TACCmd.Simple) -> TACCmd.Simple = { it }
    ): TACCommandGraph {
        val blockGraph = this.blockGraph.filterKeys {
            it in toIsolate
        }.mapValuesTo(MutableBlockGraph()) {
            it.value.retainAll {
                it in toIsolate
            }
        }
        val root = blockGraph.entries.filter { (k, _) ->
            blockGraph.none {
                k in it.value
            }
        }
        if (root.size != 1) {
            throw IllegalArgumentException("Nodes $toIsolate do not denote a valid subgraph: need one entrance")
        }
        val sinks = blockGraph.entries.filter {
            it.value.isEmpty()
        }.map { it.key }.toSet()
        val code = code.filterKeys {
            it in blockGraph
        }.mapValues {
            if (it.key == root.single().key) {
                it.value.filterIndexed { ind, c ->
                    prefixFilter(
                        LTACCmd(
                            cmd = c,
                            ptr = CmdPointer(
                                pos = ind,
                                block = it.key
                            )
                        )
                    )
                }.takeIf { it.isNotEmpty() } ?: listOf(TACCmd.Simple.NopCmd)
            } else if (it.key !in sinks) {
                it.value
            } else {
                it.value.dropLast(1) + sinkModel(it.value.last())
            }
        }
        return TACCommandGraph(
            blockGraph = blockGraph,
            code = code,
            symbolTable = symbolTable,
            cache = null,
            name = this.name
        )
    }

    // block to vars
    val blockToVars: Map<NBId, Set<TACSymbol.Var>>
        get() = run {
            val m = mutableMapOf<NBId, Set<TACSymbol.Var>>()
            blocks.forEach {
                m[it.id] = it.commands.flatMapTo(mutableSetOf()) { c -> c.cmd.getFreeVarsOfRhs() }
            }
            return m
        }

}


val ITACMethod.commands get() =
    (this.code as CoreTACProgram).analysisCache.graph.commands

fun ITACMethod.elab(p : CmdPointer) =
    (this.code as CoreTACProgram).analysisCache.graph.elab(p)
