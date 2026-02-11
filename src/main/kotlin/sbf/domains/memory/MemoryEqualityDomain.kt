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

package sbf.domains

import algorithms.UnionFind
import com.certora.collect.*
import sbf.disassembler.*
import sbf.cfg.*
import log.*
import datastructures.stdcollections.*
import sbf.callgraph.CVTCore
import sbf.callgraph.CVTFunction
import sbf.callgraph.SolanaFunction

/**
 * MemEqualityPredicateDomain: abstract domain that discovers equalities between sequences of bytes.
 *
 * The domain keeps track of two kind of predicates `ConstantMemEqualityPredicate` and `SymbolicMemEqualityPredicate`.
 * The former represents that a byte sequence is equal to some constant value while the latter represents equalities
 * between two byte sequences. Note that `MemEqualityPredicateDomain` is a classical example where a Galois connection
 * does not exist. Thus, two different predicates can refer to the same byte sequence. This is okay for soundness, but it
 * can affect precision.
 *
 * The following instructions create one of those two predicates:
 *
 * - `load` followed by `assume`
 * - `memcmp` followed by `assume`
 * - `store` and `memset` instructions
 *
 * It is our choice that `memcpy` __does not create new predicates__. The reason is to keep
 * the number of predicates low since some operations such as join is quadratic on the number of predicates.
 **/

/**
 * Represent a stack or heap/external location.
 *
 * In principle, we could have used directly a [PTACell] instead of [MemLocation].
 * However, the [PTANode] that represents the stack changes from basic block to basic block.
 * The use of [MemLocation.Stack] avoids extra reasoning to conclude that two different [PTANode] actually correspond
 * to the stack but at different basic blocks.
 */
sealed class MemLocation {

    data class Stack(private val o: Long): MemLocation() {
        override fun getOffset() = o
        override fun isLexOrdered(other: MemLocation) =
            when (other){
                is NonStack<*> -> true // assume stack < non_stack
                is Stack -> getOffset() < other.getOffset()
            }

        override fun<Flags : IPTANodeFlags<Flags>> sameMemRegion(node: PTANode<Flags>) = node.flags.isMayStack
    }

    /**
     * [NonStack] is not a data class because [c] can be aliased (i.e., forwarded) to another cell.
     * Because of that, we must ensure that all operations, included `equals` take into account aliasing.
     * All calls to `c.getNode()` and `c.getOffset()` will resolve aliasing before accessing to data.
     **/
    class NonStack<Flags: IPTANodeFlags<Flags>> (private val c: PTACell<Flags>): MemLocation() {
        fun getNode() = c.getNode()
        override fun getOffset() = c.getOffset().v
        override fun isLexOrdered(other: MemLocation) =
            when (other){
                is Stack -> false // assume stack < non_stack
                is NonStack<*> -> {
                    when (c.getNode().id) {
                        other.c.getNode().id -> getOffset() < other.getOffset()
                        else -> c.getNode().id < other.c.getNode().id
                    }

                }
            }

        override fun<Flags : IPTANodeFlags<Flags>> sameMemRegion(node: PTANode<Flags>) = c.getNode() == node

        override fun equals(other: Any?): Boolean {
            if (this === other) {
                return true
            }
            if (other !is NonStack<*>) {
                return false
            }
            return getNode() == other.getNode() && getOffset() == other.getOffset()
        }

        override fun hashCode(): Int {
            error("MemLocation.NonStack does not and will not implement hashCode")
        }

        override fun toString(): String {
            // resolve forwarding cell
            c.getNode()
            return "NonStack($c)"
        }
    }

    companion object {
        fun <Flags : IPTANodeFlags<Flags>> from(c: PTACell<Flags>): MemLocation {
            val node = c.getNode()
            return if (node.flags.isMayStack) {
                // we know that the PTA node that models the stack cannot be unified with anything else.
                // Thus, checking whether this flag is enabled suffices to tell whether `node` represents the program stack
                Stack(c.getOffset().v)
            } else {
                NonStack(c)
            }
        }
    }

    abstract fun getOffset(): Long
    abstract fun isLexOrdered(other: MemLocation): Boolean
    abstract fun<Flags : IPTANodeFlags<Flags>> sameMemRegion(node: PTANode<Flags>): Boolean
}

sealed class MemEqualityPredicate<Flags: IPTANodeFlags<Flags>> {
    /**
     * Merge two predicates.
     *
     * If we have __two__ predicates that represent that two byte sequences are equal, and they are consecutive,
     * then we can infer __one__ new predicate representing that the two sequences are equal.
     */
    abstract fun merge(other: MemEqualityPredicate<Flags>): MemEqualityPredicate<Flags>?
    /**
     * Return true if any byte sequence represented by the predicate might overlap with
     * the sequence that starts at [c] and length [size].
     **/
    abstract fun overlap(c: PTACell<Flags>, size: Long): Boolean
}

data class WordValue(val value:Long, val isEqual: Boolean)

/**
 *  let `[o1 -> (value1, isEqual1)), o2 -> (value2, isEqual2) ... ]` be [values]
 *
 *  let `(n, o)` be [base] where `n` is either a [PTANode] or stack
 *
 *  Then,
 *
 *  ```
 *  n[o+o1] == value1 if isEqual1 == true
 *  n[o+o2] == value2 if isEqual2 == true
 *  ...
 *  ```
 *  where the number of bytes accessed by every `n[o+oi]` is [wordSize].
 */
class ConstantMemEqualityPredicate<Flags: IPTANodeFlags<Flags>> private constructor(
    val base: MemLocation,
    val values: TreapMap<Long, WordValue>,
    // For simplicity, all values must have the same word size
    val wordSize: Short
    ): MemEqualityPredicate<Flags>() {

    init {
        check(values.isNotEmpty())
    }

    fun withBase(newBase: MemLocation) = ConstantMemEqualityPredicate<Flags>(newBase, values, wordSize)

    companion object {
        operator fun<Flags: IPTANodeFlags<Flags>> invoke(
            c: PTACell<Flags>,
            offset: Long,
            width:Short,
            value: Long,
            isEqual: Boolean
        ): ConstantMemEqualityPredicate<Flags> {
            return ConstantMemEqualityPredicate(
                MemLocation.from(c),
                // Should we normalize offsets?
                treapMapOf<Long, WordValue>().put(offset, WordValue(value, isEqual)),
                width
            )
        }

        fun<Flags: IPTANodeFlags<Flags>> memsetZero(
            c: PTACell<Flags>,
            len: Long,
            wordSize: Short
        ): ConstantMemEqualityPredicate<Flags> {
            check(len % wordSize == 0L)

            var values = treapMapOf<Long, WordValue>()
            for (i in 0 until len / wordSize) {
                values = values.put(i*8, WordValue(0, true))
            }

            return ConstantMemEqualityPredicate(
                MemLocation.from(c),
                values,
                wordSize
            )
        }
    }

    override fun overlap(c: PTACell<Flags>, size: Long): Boolean {
        val otherLoc = MemLocation.from(c)
        return when {
            (base is MemLocation.Stack) && (otherLoc is MemLocation.Stack) -> {
                checkOverlapValues(base.getOffset(), otherLoc.getOffset(), size, wordSize, values)
            }
            (base is MemLocation.NonStack<*>) && (otherLoc is MemLocation.NonStack<*>) -> {
                (base.getNode() == c.getNode()) && checkOverlapValues(base.getOffset(), otherLoc.getOffset(), size, wordSize, values)
            }
            else -> false // different regions cannot overlap
        }
    }

    private fun checkOverlapValues(
        baseOffset: Long,
        otherOffset: Long,
        size: Long,
        stride: Short,
        values: Map<Long, *>
    ): Boolean {
        val otherSlice = FiniteInterval.mkInterval(otherOffset, size)
        val strideL = stride.toLong()
        val offsets = values.keys
        return offsets.any { offset ->
            val slice = FiniteInterval.mkInterval(baseOffset + offset, strideL)
            slice.overlap(otherSlice)
        }
    }

    override fun merge(other: MemEqualityPredicate<Flags>): MemEqualityPredicate<Flags>? {
        if (other !is ConstantMemEqualityPredicate<*>) {
            return null
        }

        if (base != other.base || wordSize != other.wordSize) {
            return null
        }

        val newValues = values.merge(other.values) { _, t1, t2 ->
            when {
                t1 != null && t2 != null -> {
                    val (v1, isEqual1) = t1
                    val (v2, isEqual2) = t2
                    WordValue(v1, isEqual1 && isEqual2).takeIf { v1 == v2 }
                }
                t1 != null -> t1
                t2 != null -> t2
                else -> null
            }
        }
        return if (newValues.isEmpty()) {
            null
        } else {
            ConstantMemEqualityPredicate(base = base, values = newValues, wordSize = wordSize)
        }
    }

    /**
     * If the predicate represents a sequence of consecutive bytes without holes then return the byte sequence
     * as an interval.
     **/
    fun toFiniteInterval(): FiniteInterval? {
        val start = base.getOffset()
        val stride = wordSize.toLong()
        val offsets = values.keys.toList()
        check(offsets.isNotEmpty())

        val initial = FiniteInterval.mkInterval(start + offsets.first(), stride)
        return offsets.drop(1).fold(initial) { acc, o ->
            acc.union(FiniteInterval.mkInterval(start + o, stride)) ?: return null
        }
    }

    fun getMinOffset(): Long {
        val offset = values.firstKey()
        check(offset != null) {"values cannot be empty"}
        return base.getOffset() + offset
    }

    fun getMaxOffset(): Long {
        val offset = values.lastKey()
        check(offset != null) {"values cannot be empty"}
        return base.getOffset() + offset
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other !is ConstantMemEqualityPredicate<*>) {
            return false
        }
        return base == other.base &&
               values == other.values &&
               wordSize == other.wordSize
    }

    override fun hashCode(): Int {
        error("ConstantMemEqualityPredicate does not and will not implement hashCode")
    }

    override fun toString(): String {
        return "CstMemEqPred($base,$values,$wordSize)"
    }

}

/**
 *  let `(n1, o1)` and `(n2, o2)` be [base1] and [base2], respectively.
 *
 *  If `allEqual=true` then every byte of `n1[o1+range1.l ... o1+range1.u]` is equal
 *  to every byte of `n2[o2+range2.l ... o2+range2.u]`.
 *
 *  - Note #1:  if `allEqual=false` then nothing can be said about whether the byte sequences are equal or not.
 *    However, there is still something useful about the predicate
 *    because it can be used to promote `memcmp`, because we know that some byte sequences have been compared even if
 *    we don't know whether they are equal or not.
 *  - Note #2: An alternative representation of `(base1: MemLocation, base2: MemLocation, numOfBytes: Long, ...)` is not
 *    expressive enough to represent that `*(u64 *)sp(4040+24) == *(u64 *)sp(8080+24)`
 *
**/
class SymbolicMemEqualityPredicate<Flags: IPTANodeFlags<Flags>> private constructor(
    val base1: MemLocation,
    val range1: FiniteInterval,
    val base2: MemLocation,
    val range2: FiniteInterval,
    val allEqual: Boolean): MemEqualityPredicate<Flags>()  {
    init {
        check(range1.size() == range2.size())
        check(base1 == base2 || base1.isLexOrdered(base2)) {
            "base1 and base2 must be given to the constructor in lexicographical order: $base1 and $base2"
        }
    }

    companion object {
        /**
         * This is a constructor that normalizes operands before creating the object
         **/
        operator fun<Flags: IPTANodeFlags<Flags>> invoke(
            base1: PTACell<Flags>,
            range1: FiniteInterval,
            base2: PTACell<Flags>,
            range2: FiniteInterval,
            allEqual: Boolean
        ): SymbolicMemEqualityPredicate<Flags> {
            val loc1 = MemLocation.from(base1)
            val loc2 = MemLocation.from(base2)
            return if (loc1.isLexOrdered(loc2)) {
                SymbolicMemEqualityPredicate(loc1, range1, loc2, range2, allEqual)
            } else {
                SymbolicMemEqualityPredicate(loc2, range2, loc1, range1, allEqual)
            }
        }
    }

    fun withBases(newBase1: MemLocation, newBase2: MemLocation): SymbolicMemEqualityPredicate<Flags> {
        return if (newBase1.isLexOrdered(newBase2)) {
            SymbolicMemEqualityPredicate(newBase1, range1, newBase2, range2, allEqual)
        } else {
            SymbolicMemEqualityPredicate(newBase2, range2, newBase1, range1, allEqual)
        }
    }

    /**
     *  Return one predicate that represents the union of the two byte segments represented by
     *  `this` and [other] if the two byte segments are consecutive. Otherwise, it returns `null`.
     */
    override fun merge(other: MemEqualityPredicate<Flags>): MemEqualityPredicate<Flags>? {
        if (other !is SymbolicMemEqualityPredicate<*>) {
            return null
        }
        if (base1 == other.base1 && base2 == other.base2) {
            val newRange1 = range1.union(other.range1)
            val newRange2 = range2.union(other.range2)
            if (newRange1 != null && newRange2 != null && range1 != newRange1 && range2 != newRange2) {
                return SymbolicMemEqualityPredicate(base1, newRange1, base2, newRange2, allEqual = allEqual && other.allEqual )
            }
        }
        return null
    }


    override fun overlap(c: PTACell<Flags>, size: Long) =
        overlap(base1, range1, c, size) || overlap(base2, range2, c, size)

    private fun overlap(base: MemLocation, slice: FiniteInterval, c: PTACell<Flags>, size: Long): Boolean {
        val otherLoc = MemLocation.from(c)
        return when {
            (base is MemLocation.Stack) && (otherLoc is MemLocation.Stack) -> {
                val otherSlice = FiniteInterval.mkInterval(otherLoc.getOffset(), size)
                slice.overlap(otherSlice)
            }
            (base is MemLocation.NonStack<*>) && (otherLoc is MemLocation.NonStack<*>) -> {
                if (base.getNode() != otherLoc.getNode()) {
                    false
                } else {
                    val otherSlice = FiniteInterval.mkInterval(otherLoc.getOffset(), size)
                    slice.overlap(otherSlice)
                }
            }
            else ->  false // different regions cannot overlap
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other !is SymbolicMemEqualityPredicate<*>) {
            return false
        }
        return base1 == other.base1 &&
               range1 == other.range1 &&
               base2 == other.base2 &&
               range2 == other.range2 &&
               allEqual == other.allEqual
    }

    override fun hashCode(): Int {
        error("SymbolicMemEqualityPredicate does not and will not implement hashCode")
    }

    override fun toString(): String {
        return "SymMemEqPred($base1,$range1,$base2,$range2,equal=$allEqual)"
    }
}

/**
 * Implement a set with __intersection__ semantics of [MemEqualityPredicate] using an unordered list without duplicates.
 *
 * This is much less efficient than using [TreapMap], but it doesn't require that [MemEqualityPredicate] implements `hashCode`
 * which is something we want to avoid because [PTACell] is not hashable. In fact, a better data-structure would be
 * union-find so that all predicates to be known equal are in the same equivalence class.
 **/
data class SetOfMemEqualityPredicate<Flags: IPTANodeFlags<Flags>>(
    /** invariant: `isTop || not_duplicates(predicates)` **/
    private val predicates: List<MemEqualityPredicate<Flags>> = listOf(),
    private val isTop: Boolean = false
) : StackEnvironmentValue<SetOfMemEqualityPredicate<Flags>>, Iterable<MemEqualityPredicate<Flags>> {

    override fun isBottom() = false

    override fun isTop() = isTop

    override fun mkTop() = SetOfMemEqualityPredicate<Flags>(listOf(), isTop = true)

    fun size(): Int {
        check(!isTop())
        return predicates.size
    }

    fun isEmpty(): Boolean {
        check(!isTop())
        return predicates.isEmpty()
    }

    override fun join(other: SetOfMemEqualityPredicate<Flags>): SetOfMemEqualityPredicate<Flags> {
        return if (isTop() || other.isTop()) {
            mkTop()
        } else {
            // intersection
            val outL = this.toMutableList()
            outL.retainAll(other.predicates)
            SetOfMemEqualityPredicate(outL, isTop = false)
        }
    }

    override fun widen(other: SetOfMemEqualityPredicate<Flags>) = this.join(other)

    override fun lessOrEqual(other: SetOfMemEqualityPredicate<Flags>): Boolean {
        return if (other.isTop()) {
           true
        } else if (isTop()) {
            false
        } else {
            // if `this` has more predicates than `other` then `this` is more precise
            predicates.containsAll(other.predicates)
        }
    }

    override fun iterator(): Iterator<MemEqualityPredicate<Flags>> = predicates.iterator()

    fun add(predicate: MemEqualityPredicate<Flags>): SetOfMemEqualityPredicate<Flags> {
        return if (isTop()) {
            this
        } else {
            if (predicates.contains(predicate)) {
                this
            } else {
                SetOfMemEqualityPredicate(predicates + predicate, isTop = false)
            }
        }
    }

    fun remove(predicate: MemEqualityPredicate<Flags>): SetOfMemEqualityPredicate<Flags> {
        return if (isTop()) {
            this
        } else {
            SetOfMemEqualityPredicate(predicates - predicate, isTop = false)
        }
    }

    fun removeIf(pred: (MemEqualityPredicate<Flags>) -> Boolean): SetOfMemEqualityPredicate<Flags>{
        return if (isTop()) {
            this
        } else {
            SetOfMemEqualityPredicate(predicates.filter { !pred(it) }, isTop = false)
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other !is SetOfMemEqualityPredicate<*>) {
            return false
        }

        if (this.isTop != other.isTop) {
            return false
        }

        if (this.isTop && other.isTop) {
            return true
        }

        // shortcut to avoid expensive check below
        if (predicates.size != other.predicates.size) {
            return false
        }

        val o = other as SetOfMemEqualityPredicate<Flags>
        return this.lessOrEqual(o) && o.lessOrEqual(this)
    }

    override fun hashCode(): Int {
        error("SetOfMemEqualityPredicate does not and will not implement hashCode")
    }

    override fun toString(): String {
        return if (isTop()) {
            "top"
        } else {
            predicates.toString()
        }
    }
}

sealed class LiveInstructionLHS<Flags: IPTANodeFlags<Flags>>
/**
 * Represent that `*([base]+[offset])` has been loaded
 */
data class Load<Flags: IPTANodeFlags<Flags>>(val base: PTACell<Flags>, val offset: Long, val width: Short): LiveInstructionLHS<Flags>()
/**
 * Represent `memcmp` instruction
 */
data class Memcmp<Flags: IPTANodeFlags<Flags>>(val op1: PTACell<Flags>, val op2: PTACell<Flags>, val len: Long): LiveInstructionLHS<Flags>()

/**
 * Abstract domain to keep track of [MemEqualityPredicate] predicates.
 *
 * This domain cannot represent error/failure (i.e., bottom). This means that this domain cannot be used to prune
 * infeasible paths or unreachable blocks.
 *
 * @param [instMap]. Keep track of executed instructions whose left-hand side has not been overwritten yet.
 * @param [memcmpSet]. Set of [MemEqualityPredicate] predicates
 **/
class MemEqualityPredicateDomain<Flags: IPTANodeFlags<Flags>>(
    private var instMap: TreapMap<Value.Reg, LiveInstructionLHS<Flags>> = treapMapOf(),
    private var memcmpSet: SetOfMemEqualityPredicate<Flags> = SetOfMemEqualityPredicate(),
    private val globalState: GlobalState
) : MutableAbstractDomain<MemEqualityPredicateDomain<Flags>> {

    override fun isBottom() = false
    override fun isTop() = instMap.isEmpty() && memcmpSet.isTop()

    override fun setToBottom() {
        instMap = treapMapOf()
        memcmpSet = SetOfMemEqualityPredicate()
    }

    override fun deepCopy() = MemEqualityPredicateDomain(instMap, memcmpSet, globalState)

    private fun merge(
        memcmpSet: SetOfMemEqualityPredicate<Flags>,
        newPred: MemEqualityPredicate<Flags>
    ): Pair<SetOfMemEqualityPredicate<Flags>, Boolean> {
        check(!memcmpSet.isBottom())
        check(!memcmpSet.isTop())
        check(!memcmpSet.isEmpty())

        var outSet = memcmpSet
        var mergedPredicate = newPred
        var changed: Boolean
        do {
            val curSet = outSet
            for (pred in curSet) {
                pred.merge(mergedPredicate)?.let {
                    outSet = outSet.remove(pred).add(it)
                    mergedPredicate = it
                }
            }
            changed = outSet != curSet // it will call equals from MemEqualityPredicate
        } while (changed)
        return outSet to (newPred != mergedPredicate)
    }

    /**
     * Add `newPred` in `memcmpSet`.
     * If there is already a predicate in `memcmpSet` that can be merged with `newPred` then they are merged together
     **/
    private fun addAndMerge(
        memcmpSet: SetOfMemEqualityPredicate<Flags>,
        newPred: MemEqualityPredicate<Flags>
    ): SetOfMemEqualityPredicate<Flags> {
        return if (memcmpSet.isBottom() || memcmpSet.isTop()) {
            memcmpSet
        } else {
            if (memcmpSet.isEmpty()) {
                memcmpSet.add(newPred)
            } else {
                merge(memcmpSet, newPred).let { (outSet, merged) ->
                    if (!merged) {
                        outSet.add(newPred)
                    } else {
                        outSet
                    }
                }
            }
        }
    }

    /**
     * It does not perform any kind of transitive closure. Instead, it only merges predicates.
     **/
    private fun normalize(memcmpSet: SetOfMemEqualityPredicate<Flags>): SetOfMemEqualityPredicate<Flags> {
        if (memcmpSet.isTop() || memcmpSet.isBottom()) {
            return memcmpSet
        }

        var outSet = memcmpSet
        var changed: Boolean
        do {
            val curSet = outSet
            for (pred in curSet) {
                outSet = addAndMerge(outSet.remove(pred), pred)
            }
            changed = outSet != curSet
        } while (changed)

        return outSet
    }

    override fun pseudoCanonicalize(
        other: MemEqualityPredicateDomain<Flags>
    ): MemEqualityPredicateDomain<Flags> {

        val out = this.deepCopy()
        val leftPreds = memcmpSet.filterIsInstance<SymbolicMemEqualityPredicate<Flags>>()
        val rightPreds  = other.memcmpSet.filterIsInstance<SymbolicMemEqualityPredicate<Flags>>()

        for (leftPred in leftPreds) {
            for (rightPred in rightPreds) {
                if (rightPred.base1 == leftPred.base1 && rightPred.base2 == leftPred.base2 && rightPred.allEqual == leftPred.allEqual) {
                    if (rightPred.range1 != leftPred.range1 && rightPred.range2 != leftPred.range2) {
                        when (rightPred.allEqual) {
                            false -> {
                                /**
                                 *  If `this` contains `memcmp(b1,b2,[0,7], notEqual)` and `other` contains `memcmp(b1,b2,[0,16], notEqual)` then
                                 *  it is sound to add `memcmp(b1,b2,[0,16], notEqual)` in `this`.
                                 **/
                                if (rightPred.range1.includes(leftPred.range1) && rightPred.range2.includes(leftPred.range2)) {
                                    out.memcmpSet = out.memcmpSet.add(rightPred)
                                }
                            }
                            true -> {
                                /**
                                 *  If `this` contains `memcmp(b1,b2,[0,16], equal)` and `other` contains `memcmp(b1,b2,[0,7], equal)` then
                                 *  it is sound to add `memcmp(b1,b2,[0,7], equal)` in `this`.
                                 **/
                                if (leftPred.range1.includes(rightPred.range1) && leftPred.range2.includes(rightPred.range2)) {
                                    out.memcmpSet = out.memcmpSet.add(rightPred)
                                }
                            }
                        }
                    }
                }
            }
        }
        return out
    }

    override fun join(other: MemEqualityPredicateDomain<Flags>, left: Label?, right: Label?): MemEqualityPredicateDomain<Flags> {
        fun join(left: TreapMap<Value.Reg, LiveInstructionLHS<Flags>>,
                 right: TreapMap<Value.Reg, LiveInstructionLHS<Flags>>): TreapMap<Value.Reg, LiveInstructionLHS<Flags>> {
            return left.merge(right) { _, leftP, rightP ->
                if (leftP == rightP) {
                    leftP
                } else {
                    null
                }
            }
        }

        return if (isTop() || other.isBottom()) {
            this
        } else if (other.isTop() || isBottom()) {
            other
        }  else {
            MemEqualityPredicateDomain(
                join(instMap, other.instMap),
                normalize(memcmpSet.join(other.memcmpSet)),
                globalState
            )
        }
    }

    override fun widen(other: MemEqualityPredicateDomain<Flags>, b: Label?) = join(other)

    override fun lessOrEqual(other: MemEqualityPredicateDomain<Flags>, left: Label?, right: Label?): Boolean {
        fun lessOrEqual(left: TreapMap<Value.Reg, LiveInstructionLHS<Flags>>,
                        right: TreapMap<Value.Reg, LiveInstructionLHS<Flags>>): Boolean {
            return left.zip(right).all { (_, pair) ->
                val (leftVal, rightVal) = pair
                check(!(leftVal == null && rightVal == null)) { "cannot compare two null values" }
                when {
                    leftVal == null -> false
                    rightVal == null -> true
                    else -> leftVal == rightVal
                }
            }
        }

        return if (other.isTop() || isBottom()) {
            true
        } else if (other.isBottom() || isTop()) {
            false
        } else {
            lessOrEqual(instMap, other.instMap) && memcmpSet.lessOrEqual(other.memcmpSet)
        }
    }

    /// -- Transfer functions

    override fun forget(reg: Value.Reg) {
        instMap = instMap.remove(reg)
    }

    override fun forget(regs: Iterable<Value.Reg>): MemEqualityPredicateDomain<Flags> {
        return MemEqualityPredicateDomain(
            instMap.removeAll(regs),
            memcmpSet,
            globalState
        )
    }

    /** Forget any predicate that refers to memory location `*(reg+offset)` **/
    private fun<TNum: INumValue<TNum>, TOffset: IOffset<TOffset>> forgetPredicates(
        reg: Value.Reg,
        offset: Long,
        width: Long,
        memoryAbsVal: MemoryDomain<TNum, TOffset, Flags>
    ) {
        val baseSymC = memoryAbsVal.getPTAGraph().getRegCell(reg)
        if (baseSymC != null) {
            val baseN = baseSymC.getNode()
            val baseOffsets = baseSymC.getOffset().toLongList()
            baseOffsets.forEach {  baseO ->
                memcmpSet = memcmpSet.removeIf { pred: MemEqualityPredicate<Flags> ->
                    pred.overlap(baseN.createCell(baseO + offset), width)
                }
            }
        }
    }

    private fun<TNum: INumValue<TNum>, TOffset: IOffset<TOffset>> analyzeLoad(
        inst: SbfInstruction.Mem,
        memoryAbsVal: MemoryDomain<TNum, TOffset, Flags>
    ) {
        check(inst.isLoad)

        val baseReg = inst.access.base
        val offset = inst.access.offset.toLong()
        val width = inst.access.width

        if (width.toInt() != 8) {
            return
        }

        // update `loadedMap` to remember the loaded value
        val baseSymC = memoryAbsVal.getPTAGraph().getRegCell(baseReg)
        if (baseSymC != null && baseSymC.isConcrete()) {
            val baseC = baseSymC.concretize()
            val lhs = inst.value  as Value.Reg
            val loadedTerm = Load(baseC, offset, width)
            instMap = instMap.put(lhs, loadedTerm)
        }
    }

    private fun<TNum: INumValue<TNum>, TOffset: IOffset<TOffset>> analyzeStore(
        inst: SbfInstruction.Mem,
        memoryAbsVal: MemoryDomain<TNum, TOffset, Flags>
    ) {
        check(!inst.isLoad)

        val baseReg = inst.access.base
        val offset = inst.access.offset.toLong()
        val width = inst.access.width
        val storedVal = inst.value

        // 1. Remove all predicates that might be affected by the store
        forgetPredicates(baseReg, offset, width.toLong(), memoryAbsVal)

        // 2. Create a new predicate if the store instruction writes a number
        (memoryAbsVal.getScalars().getAsScalarValue(storedVal).type() as? SbfType.NumType)?.value?.toLongOrNull()?.let {
            val baseSymC = memoryAbsVal.getPTAGraph().getRegCell(baseReg)
            if (baseSymC != null && baseSymC.isConcrete()) {
                val baseC = baseSymC.concretize()
                memcmpSet = addAndMerge(memcmpSet, ConstantMemEqualityPredicate(baseC, offset, width, it, isEqual = true))
            }
        }
    }


    private fun<TNum: INumValue<TNum>, TOffset: IOffset<TOffset>> analyzeMem(
        locInst: LocatedSbfInstruction,
        memoryAbsVal: MemoryDomain<TNum, TOffset, Flags>
    ) {
        val inst = locInst.inst
        check(inst is SbfInstruction.Mem)
        when (inst.isLoad) {
            true -> analyzeLoad(inst, memoryAbsVal)
            false -> analyzeStore(inst, memoryAbsVal)
        }
    }

    private fun<TNum: INumValue<TNum>, TOffset: IOffset<TOffset>> getScalarAsLong(
        v: Value,
        memAbsVal: MemoryDomain<TNum, TOffset, Flags>
    ): Long? {
        return when (v) {
            is Value.Imm -> v.v.toLong()
            is Value.Reg -> (memAbsVal.getScalars().getAsScalarValue(v).type() as? SbfType.NumType)?.value?.toLongOrNull()
        }
    }

    private fun<TNum: INumValue<TNum>, TOffset: IOffset<TOffset>> analyzeAssumeOfMemcmp(
        locInst: LocatedSbfInstruction,
        memAbsVal: MemoryDomain<TNum, TOffset, Flags>
    ) {

        fun addIfMemcmpComparedWithZero(memcmp: Memcmp<Flags>, op: CondOp, n: Long) {
            if (n == 0L) {
                // We could call `addAndMerge`, but we don't expect to merge this predicate with another one
                memcmpSet = memcmpSet.add(
                    SymbolicMemEqualityPredicate(
                        memcmp.op1,
                        FiniteInterval(0, memcmp.len-1),
                        memcmp.op2,
                        FiniteInterval(0, memcmp.len-1),
                        op == CondOp.EQ
                    )
                )
            }
        }

        val inst = locInst.inst
        check(inst is SbfInstruction.Assume)

        val op = inst.cond.op
        if (op != CondOp.EQ && op != CondOp.NE) {
            return
        }

        val x = inst.cond.left
        val y = inst.cond.right

        (instMap[x] as? Memcmp<Flags>)?.let { t ->
            getScalarAsLong(y, memAbsVal)?.let { n ->
                addIfMemcmpComparedWithZero(t, op, n)
            }
        }

        (instMap[y] as? Memcmp<Flags>)?.let { t ->
            getScalarAsLong(x, memAbsVal)?.let { n ->
                addIfMemcmpComparedWithZero(t, op, n)
            }
        }
    }

    private fun<TNum: INumValue<TNum>, TOffset: IOffset<TOffset>> analyzeAssumeOfLoad(
        locInst: LocatedSbfInstruction,
        memAbsVal: MemoryDomain<TNum, TOffset, Flags>
    ) {

        val inst = locInst.inst
        check(inst is SbfInstruction.Assume)

        val op = inst.cond.op
        if (op != CondOp.EQ && op != CondOp.NE) {
            return
        }

        val x = inst.cond.left
        val y = inst.cond.right
        val t1 = instMap[x] as? Load<Flags>
        val t2 = (y as? Value.Reg)?.let { instMap[y] as? Load<Flags> }
        when {
            t1 != null && t2 != null -> {
                if (t1.width != t2.width) {
                    return
                }
                val range1 = FiniteInterval.mkInterval(t1.offset, t1.width.toLong())
                val range2 = FiniteInterval.mkInterval(t2.offset, t2.width.toLong())
                val pred = SymbolicMemEqualityPredicate(t1.base, range1, t2.base, range2, allEqual = op == CondOp.EQ)
                memcmpSet = addAndMerge(memcmpSet, pred)
            }
            t1 != null -> {
                getScalarAsLong(y, memAbsVal)?.let {
                    val pred = ConstantMemEqualityPredicate(t1.base, t1.offset, t1.width, it,op == CondOp.EQ)
                    memcmpSet = addAndMerge(memcmpSet, pred)
                }
            }
            t2 != null -> {
                getScalarAsLong(x, memAbsVal)?.let {
                    val pred = ConstantMemEqualityPredicate(t2.base, t2.offset, t2.width, it, op == CondOp.EQ)
                    memcmpSet = addAndMerge(memcmpSet, pred)
                }
            }
        }
    }

    private fun<TNum: INumValue<TNum>, TOffset: IOffset<TOffset>> analyzeAssume(
        locInst: LocatedSbfInstruction,
        memAbsVal: MemoryDomain<TNum, TOffset, Flags>
    ) {
        // Only one at most can add a predicate because the registers involved in the condition cannot be
        // mapped (using `instMap`) both to Load and Memcmp
        analyzeAssumeOfLoad(locInst, memAbsVal)
        analyzeAssumeOfMemcmp(locInst, memAbsVal)
    }

    private fun analyzeBin(locInst: LocatedSbfInstruction) {
        val inst = locInst.inst
        check(inst is SbfInstruction.Bin)

        when (inst.op) {
            BinOp.MOV -> {
                val lhs = inst.dst
                when (val rhs = inst.v) {
                    is Value.Imm -> forget(lhs)
                    is Value.Reg -> {
                        instMap[rhs]?.let { x ->
                            instMap = instMap.put(lhs, x)
                        } ?: forget(lhs)
                    }
                }
            }
            else -> {
                forget(inst.dst)
            }
        }
    }


    /** Class that contains helpers for the abstract transformer of memory intrinsics: `memcpy`/`memmove`/`memset` **/
    private sealed class MemIntrinsicsTransformer<P: MemEqualityPredicate<Flags>, Flags : IPTANodeFlags<Flags>>(
        val pred: P
    ) {
        /** Returns true if `pred` may overlap with [node] and [range]. **/
        abstract fun mayOverlap(node: PTANode<Flags>, range: FiniteInterval?): Boolean
        /** Returns a new propagated predicate from src to dst if needed, or null otherwise. **/
        abstract fun propagate(srcC: PTACell<Flags>, dstC: PTACell<Flags>, len: Long): P?

        companion object {
            /**
             * We explain what propagate does by an example.
             *
             * If [predLoc] = `(Stack, 4064)`, [predSlice] = `[4064, 4095]`, [srcC] = `(Stack, 4016)`, [dstC] = `(Stack, 3916)`, and [len] = `80` then
             * this function returns `(Stack, 3964)`
             */
            @JvmStatic
            protected fun<Flags : IPTANodeFlags<Flags>> propagate(
                predLoc: MemLocation,
                predSlice: FiniteInterval,
                srcC: PTACell<Flags>,
                dstC: PTACell<Flags>, len: Long
            ): MemLocation? {
                val srcRange  = FiniteInterval.mkInterval(srcC.getOffset().v, len)
                return if (predLoc.sameMemRegion(srcC.getNode()) && srcRange.includes(predSlice))  {
                    val adjustedOffset = predLoc.getOffset() - srcC.getOffset().v
                    val newOffset = dstC.getOffset().v + adjustedOffset
                    MemLocation.from(dstC.getNode().createCell(newOffset))
                } else {
                    null
                }
            }

        }
    }


    private inner class MemIntrinsicsTransformerForCstPred(pred: ConstantMemEqualityPredicate<Flags>)
        : MemIntrinsicsTransformer<ConstantMemEqualityPredicate<Flags>, Flags>(pred) {
       override fun mayOverlap(
           node: PTANode<Flags>,
           range: FiniteInterval?
       ): Boolean {
           return pred.base.sameMemRegion(node) &&
               (range?.let { FiniteInterval(pred.getMinOffset(), pred.getMaxOffset()).overlap(it) } ?: true)
       }

       override fun propagate(srcC: PTACell<Flags>, dstC: PTACell<Flags>, len: Long
       ): ConstantMemEqualityPredicate<Flags>? {
           propagate(pred.base, FiniteInterval(pred.getMinOffset(), pred.getMaxOffset()), srcC, dstC, len)?.let { newBase ->
               return pred.withBase(newBase)
           }
           return null
       }
    }

    private inner class MemIntrinsicsTransformerForSymPred(pred: SymbolicMemEqualityPredicate<Flags>):
        MemIntrinsicsTransformer<SymbolicMemEqualityPredicate<Flags>, Flags>(pred) {
        override fun mayOverlap(node: PTANode<Flags>, range: FiniteInterval?): Boolean {
            return (pred.base1.sameMemRegion(node) || pred.base2.sameMemRegion(node)) &&
                (range?.let { pred.range1.overlap(it) || pred.range2.overlap(it) } ?: true)
        }

        override fun propagate(srcC: PTACell<Flags>, dstC: PTACell<Flags>, len: Long
        ): SymbolicMemEqualityPredicate<Flags>? {
            val o1 = pred.base1.getOffset()
            val o2 = pred.base2.getOffset()
            val newBase1 = propagate(pred.base1, pred.range1.add(o1), srcC, dstC, len)
            val newBase2 = propagate(pred.base2, pred.range2.add(o2), srcC, dstC, len)
            return when {
                newBase1 != null && newBase2 == null -> pred.withBases(newBase1, pred.base2)
                newBase1 == null && newBase2 != null -> pred.withBases(pred.base1, newBase2)
                newBase1 != null && newBase2 != null -> pred.withBases(newBase1, newBase2)
                else -> null
            }
        }
    }

    private data class MemIntrinsicsArgs<Flags: IPTANodeFlags<Flags>>(val r1: PTASymCell<Flags>, val r2: PTASymCell<Flags>, val r3: Long?)

    private fun <TNum: INumValue<TNum>, TOffset: IOffset<TOffset>> getArgsForMemIntrinsics(
        memoryAbsVal: MemoryDomain<TNum, TOffset, Flags>
    ): MemIntrinsicsArgs<Flags>? {
        val (r1, r2, r3) = listOf(Value.Reg(SbfRegister.R1_ARG), Value.Reg(SbfRegister.R2_ARG), Value.Reg(SbfRegister.R3_ARG))
        // len can be null
        val len = (memoryAbsVal.getScalars().getAsScalarValue(r3).type() as? SbfType.NumType)?.value?.toLongOrNull()
        // Since this domain is part of the [MemoryDomain], if `r1` does not point to any [PTACell] then [MemoryDomain] will complain
        val dstSc = memoryAbsVal.getRegCell(r1) ?: return null
        // Since this domain is part of the [MemoryDomain], if `r2` does not point to any [PTACell] then [MemoryDomain] will complain
        val srcSc = memoryAbsVal.getRegCell(r2) ?: return null
        return MemIntrinsicsArgs(dstSc, srcSc, len)
    }

    private fun<P: MemEqualityPredicate<Flags>> havocPredicate(
        predTr: MemIntrinsicsTransformer<P, Flags>,
        sc: PTASymCell<Flags>,
        len: Long?
    ) {
        val node = sc.getNode()
        if (!sc.isConcrete() || len == null) {
            if (predTr.mayOverlap(node, null)) {
                memcmpSet = memcmpSet.remove(predTr.pred)
            }
            return
        }

        val dstC = sc.concretize()
        val dstRange = FiniteInterval.mkInterval(dstC.getOffset().v, len)
        if (predTr.mayOverlap(node, dstRange)) {
            memcmpSet = memcmpSet.remove(predTr.pred)
        }

    }

    private fun<P: MemEqualityPredicate<Flags>> memTransferFn(
        predTr: MemIntrinsicsTransformer<P, Flags>,
        srcSc: PTASymCell<Flags>,
        dstSc: PTASymCell<Flags>,
        len: Long?
    ) {
        havocPredicate(predTr, dstSc, len)

        // not adding a predicate is always sound
        if (srcSc.isConcrete() && dstSc.isConcrete() && len != null) {
            val srcC = srcSc.concretize()
            val dstC = dstSc.concretize()
            predTr.propagate(srcC, dstC, len)?.let {
                memcmpSet = memcmpSet.add(it)
            }
        }
    }

    private fun memHavoc(ptr: PTASymCell<Flags>, len: Long?) {
        val predicates = memcmpSet
        for (pred in predicates) {
            val predTr = when(pred) {
                is ConstantMemEqualityPredicate -> MemIntrinsicsTransformerForCstPred(pred)
                is SymbolicMemEqualityPredicate -> MemIntrinsicsTransformerForSymPred(pred)
            }
            havocPredicate(predTr, ptr, len)
        }
    }

    private fun<TNum: INumValue<TNum>, TOffset: IOffset<TOffset>> analyzeMemTransfer(
        locInst: LocatedSbfInstruction,
        memoryAbsVal: MemoryDomain<TNum, TOffset, Flags>
    ) {

        val inst = locInst.inst
        check(inst is SbfInstruction.Call)

        val r0 = Value.Reg(SbfRegister.R0_RETURN_VALUE)
        if (inst.writeRegister.contains(r0)) {
            forget(r0)
        }

        val (dstSc, srcSc, len) = getArgsForMemIntrinsics(memoryAbsVal) ?: return
        val predicates = memcmpSet
        for (pred in predicates) {
            val predTr = when(pred) {
                is ConstantMemEqualityPredicate -> MemIntrinsicsTransformerForCstPred(pred)
                is SymbolicMemEqualityPredicate -> MemIntrinsicsTransformerForSymPred(pred)
            }
            memTransferFn(predTr, srcSc, dstSc, len)
        }

    }

    private fun<TNum: INumValue<TNum>, TOffset: IOffset<TOffset>> analyzeMemcmp(
        @Suppress("UNUSED_PARAMETER")
        locInst: LocatedSbfInstruction,
        memoryAbsVal: MemoryDomain<TNum, TOffset, Flags>
    ) {
        // Note that non-op is always sound.
        val (op1, op2, len) = getArgsForMemIntrinsics(memoryAbsVal) ?: return
        if (!op1.isConcrete() || !op2.isConcrete()) {
            return
        }
        len?.let {
            val memcmpTerm = Memcmp(op1.concretize(), op2.concretize(), it)
            instMap = instMap.put(Value.Reg(SbfRegister.R0_RETURN_VALUE), memcmpTerm)
        }
    }

    private fun<TNum: INumValue<TNum>, TOffset: IOffset<TOffset>> analyzeMemset(
        locInst: LocatedSbfInstruction,
        memoryAbsVal: MemoryDomain<TNum, TOffset, Flags>
    ) {

        val inst = locInst.inst
        check(inst is SbfInstruction.Call)

        val r0 = Value.Reg(SbfRegister.R0_RETURN_VALUE)
        val r1 = Value.Reg(SbfRegister.R1_ARG)
        val r2 = Value.Reg(SbfRegister.R2_ARG)
        val r3 = Value.Reg(SbfRegister.R3_ARG)

        if (inst.writeRegister.contains(r0)) {
            forget(r0)
        }

        // The [MemoryDomain] will complain if dstSc is null
        val dstSc = memoryAbsVal.getRegCell(r1) ?: return
        val len = (memoryAbsVal.getScalars().getAsScalarValue(r3).type() as? SbfType.NumType)?.value?.toLongOrNull()

        memHavoc(dstSc, len)

        // Not adding a predicate is always sound
        if (dstSc.isConcrete() && len != null) {
            (memoryAbsVal.getScalars().getAsScalarValue(r2).type() as? SbfType.NumType)?.value?.toLongOrNull()?.let {
                if (len == 32L && it == 0L) {
                    // Special case for pubkey::default()
                    // We could call `addAndMerge`, but we don't expect to merge this predicate with another one
                    memcmpSet = memcmpSet.add(ConstantMemEqualityPredicate.memsetZero(dstSc.concretize(), len, 8))
                }
            }
        }
    }

    private fun<TNum: INumValue<TNum>, TOffset: IOffset<TOffset>> analyzeMemcpyZExtOrTrunc(
        locInst: LocatedSbfInstruction,
        memoryAbsVal: MemoryDomain<TNum, TOffset, Flags>
    ) {
        val inst = locInst.inst
        check(inst is SbfInstruction.Call)

        val r0 = Value.Reg(SbfRegister.R0_RETURN_VALUE)
        val r1 = Value.Reg(SbfRegister.R1_ARG)


        if (inst.writeRegister.contains(r0)) {
            forget(r0)
        }

        // The [MemoryDomain] will complain if dstSc is null
        val dstSc = memoryAbsVal.getRegCell(r1) ?: return
        // Note: for memcpy_trunc we should havoc only up to i (r3) bytes
        memHavoc(dstSc, len = 8)
    }


    /**
     *  Invariant ensured by CFG construction: r10 has been decremented already
     **/
    private fun<TNum: INumValue<TNum>, TOffset: IOffset<TOffset>> restoreScratchRegisters(
        memoryAbsVal: MemoryDomain<TNum, TOffset, Flags>
    ) {
        fun isDead(loc: MemLocation, topStack: Long) = when (loc) {
                is MemLocation.Stack ->  loc.getOffset() > topStack
                else -> false
            }

        val stackPtr = Value.Reg(SbfRegister.R10_STACK_POINTER)
        val topStack = (memoryAbsVal.getScalars().getAsScalarValue(stackPtr).type() as? SbfType.PointerType.Stack)?.offset?.toLongOrNull()
        check(topStack != null){ "r10 should point to a statically known stack offset"}

        memcmpSet = memcmpSet.removeIf { pred: MemEqualityPredicate<Flags> ->
           when(pred) {
               is ConstantMemEqualityPredicate -> isDead(pred.base, topStack)
               is SymbolicMemEqualityPredicate -> isDead(pred.base1, topStack) || isDead(pred.base2, topStack)
           }
        }
    }

    private fun<TNum: INumValue<TNum>, TOffset: IOffset<TOffset>>  summarizeExternalCall(
        locInst: LocatedSbfInstruction,
        memoryAbsVal: MemoryDomain<TNum, TOffset, Flags>
    ) {
        class MemEqualityPredicateSummaryVisitor: SummaryVisitor {
            override fun noSummaryFound(locInst: LocatedSbfInstruction) {
                forget(Value.Reg(SbfRegister.R0_RETURN_VALUE))
            }
            override fun processReturnArgument(locInst: LocatedSbfInstruction, type: MemSummaryArgumentType) {
                forget(Value.Reg(SbfRegister.R0_RETURN_VALUE))
            }
            override fun processArgument(
                @Suppress("UNUSED_PARAMETER") locInst: LocatedSbfInstruction,
                reg: SbfRegister,
                offset: Long,
                width: Byte,
                @Suppress("UNUSED_PARAMETER") allocatedSpace: ULong,
                @Suppress("UNUSED_PARAMETER") type: MemSummaryArgumentType
            ) {
                forgetPredicates(Value.Reg(reg), offset, width.toLong(), memoryAbsVal)
            }
        }
        val vis = MemEqualityPredicateSummaryVisitor()
        globalState.memSummaries.visitSummary(locInst, vis)
    }


    private fun<TNum: INumValue<TNum>, TOffset: IOffset<TOffset>> analyzeCall(
        locInst: LocatedSbfInstruction,
        memoryAbsVal: MemoryDomain<TNum, TOffset, Flags>
    ) {
        val inst = locInst.inst
        check(inst is SbfInstruction.Call)

        val cvtFunction = CVTFunction.from(inst.name)
        if (cvtFunction != null) {
            when (cvtFunction) {
                is CVTFunction.Core -> {
                    when (cvtFunction.value) {
                        CVTCore.RESTORE_SCRATCH_REGISTERS -> {
                            restoreScratchRegisters(memoryAbsVal)
                            return
                        }
                        else -> {}
                    }
                }
                else -> {}
            }
        }

        val solFunction = SolanaFunction.from(inst.name)
        if (solFunction != null) {
            when (solFunction) {
                SolanaFunction.SOL_MEMCMP -> {
                    analyzeMemcmp(locInst, memoryAbsVal)
                    return
                }
                SolanaFunction.SOL_MEMCPY,
                SolanaFunction.SOL_MEMMOVE -> {
                    analyzeMemTransfer(locInst, memoryAbsVal)
                    return
                }
                SolanaFunction.SOL_MEMCPY_ZEXT,
                SolanaFunction.SOL_MEMCPY_TRUNC -> {
                    analyzeMemcpyZExtOrTrunc(locInst, memoryAbsVal)
                    return
                }
                SolanaFunction.SOL_MEMSET -> {
                    analyzeMemset(locInst, memoryAbsVal)
                    return
                }
                else -> {}
            }
        }

        summarizeExternalCall(locInst, memoryAbsVal)
    }

    /** To be called from  [MemoryDomain] **/
    fun <TNum: INumValue<TNum>, TOffset: IOffset<TOffset>>analyze(
        locInst: LocatedSbfInstruction,
        memoryAbsVal: MemoryDomain<TNum, TOffset, Flags>
    ) {
        val s = locInst.inst
        if (!isBottom()) {
            when (s) {
                is SbfInstruction.Un -> forget(s.dst)
                is SbfInstruction.Bin -> analyzeBin(locInst)
                is SbfInstruction.Call -> analyzeCall(locInst, memoryAbsVal)
                is SbfInstruction.CallReg -> {}
                is SbfInstruction.Select -> forget(s.dst)
                is SbfInstruction.Havoc -> forget(s.dst)
                is SbfInstruction.Jump.ConditionalJump -> {}
                is SbfInstruction.Assume -> analyzeAssume(locInst, memoryAbsVal)
                is SbfInstruction.Assert -> {}
                is SbfInstruction.Mem -> analyzeMem(locInst, memoryAbsVal)
                is SbfInstruction.Jump.UnconditionalJump -> {}
                is SbfInstruction.Exit -> {}
                is SbfInstruction.Debug -> {}
            }
        }
    }

    override fun analyze(
        b: SbfBasicBlock,
        listener: InstructionListener<MemEqualityPredicateDomain<Flags>>
    ): MemEqualityPredicateDomain<Flags> {
        error(
            "MemEqualityPredicateDomain requires info from MemoryDomain. " +
            "Thus, MemoryDomain should be the only one calling it."
        )
    }


    /**
     * Predicates are not maintained in closed form (see [normalize] for details).
     * In particular, we do not apply transitive closure eagerly to infer all possible equalities.
     * This approach is sound but may be imprecise. The full transitive closure is computed only
     * on demand, when the client calls [get].
     *
     * [EqualitySolver] uses a classical union-find data structure to infer all equalities
     * from a given set of predicates. Note that [query] is currently neither incremental
     * nor cached.
     */
    private class EqualitySolver<Flags : IPTANodeFlags<Flags>> {
        /**
         * Internal class for the union-find element.
         *
         * [ByteSequence] restricts how two byte sequences are considered equal or not. Note that we can have two different
         * [ByteSequence] instances that refer exactly to the same byte sequence. It doesn't affect soundness, only precision.
         *
         * @param [nodeId] If it is null then it refers to the stack, otherwise it is the id of some [PTANode]
         * @param [offset] The actual byte sequence is `[offset+range.l, ..., offset+range.u]`
        **/
        private data class ByteSequence(private val nodeId:ULong?, private val offset:Long, private val range: FiniteInterval) {
            companion object {
                /** Cannot use [MemLocation] directly because it cannot be hashed **/
                fun from(loc: MemLocation, slice: FiniteInterval): ByteSequence {
                    val offset = loc.getOffset()
                    return when (loc) {
                        is MemLocation.Stack -> ByteSequence(null, offset, slice)
                        is MemLocation.NonStack<*> -> ByteSequence(loc.getNode().id, offset, slice)
                    }
                }
            }
        }
        private val solver = UnionFind<ByteSequence>()
        private val sliceToPred = mutableMapOf<ByteSequence, ConstantMemEqualityPredicate<Flags>>()

        fun init(
            predicates: SetOfMemEqualityPredicate<Flags>,
            filter: (ConstantMemEqualityPredicate<Flags>) -> Boolean
        ) {
            predicates.forEach { pred ->
                when (pred) {
                    is ConstantMemEqualityPredicate -> {
                        if (filter(pred)) {
                            pred.toFiniteInterval()?.let { slice ->
                                val range = slice.add(-pred.base.getOffset())
                                val e = ByteSequence.from(pred.base, range)
                                solver.register(e)
                                sliceToPred[e] = pred
                            }
                        }
                    }
                    is SymbolicMemEqualityPredicate -> {
                        val e1 = ByteSequence.from(pred.base1, pred.range1)
                        val e2 = ByteSequence.from(pred.base2, pred.range2)
                        solver.register(e1)
                        solver.register(e2)
                        solver.union(e1, e2)
                    }
                }
            }
        }

        fun query(c: PTACell<Flags>, slice: FiniteInterval): ConstantMemEqualityPredicate<Flags>? {
            val e = ByteSequence.from(MemLocation.from(c), slice)
            val candidates = solver.getEquivalenceClass(e).filter { sliceToPred.contains(it) }

            // The number of candidates can be different from one. Either:
            // (1) no [ConstantMemEqualityPredicate] exists in the equivalence class, or
            // (2) multiple predicates exist. In case (2), they may be equivalent but syntactically different, or
            // truly inconsistent.

            return candidates.singleOrNull()?.let {
                checkNotNull(sliceToPred[it])
            }
        }
    }

    /**
     * Be aware that this function populates a union-find from `memcmpSet` each time is called.
     */
    fun get(n: PTANode<Flags>, start: Long, stride: Short, size:Long): ConstantMemEqualityPredicate<Flags>? {
        val solver = EqualitySolver<Flags>()
        solver.init(memcmpSet) { p -> p.wordSize == stride && p.values.all{ it.value.isEqual } }
        return solver.query(n.createCell(start), FiniteInterval(0, size -1))
    }

    override fun toString(): String {
        fun toString(m: TreapMap<Value.Reg, LiveInstructionLHS<Flags>>): String {
            val sb = StringBuffer()
            sb.append("{")
            for ((k, v) in m) {
                sb.append("$k-->$v,")
            }
            sb.append("}")
            return sb.toString()
        }
        return "(${toString(instMap)}, $memcmpSet)"
    }

}
