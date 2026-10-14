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

package analysis.dataflow

import analysis.CmdPointer
import analysis.opt.ConstantPropagatorAndSimplifier.Companion.simplifyTop
import com.certora.collect.*
import datastructures.MutableReversibleMap
import datastructures.add
import datastructures.mutableMultiMapOf
import datastructures.memoized
import datastructures.stdcollections.*
import instrumentation.transformers.DSAChecker
import java.math.BigInteger
import tac.NBId
import utils.*
import vc.data.*
import vc.data.tacexprutil.TACExprUtils.contains
import vc.data.tacexprutil.asConstOrNull
import vc.data.tacexprutil.rebuild

/**
 * A canonical [TACExpr] standing for a value, as [BranchAwareGlobalValueNumbering] computes them. Not an expression
 * of the program being numbered: its leaves are representatives, which are symbols of this pass's own making, so a
 * value number must never be written back into the program. A constant is the exception, being both.
 */
typealias ValueNumber = TACExpr

/**
 * Value numbering for a program in diamond-DSA form. Two definitions get the same number exactly when this pass can
 * show they compute the same value. What makes it worth having is that this covers definitions in matching arms of
 * two *different* diamonds over the same condition, and those diamonds' joins.
 *
 * ## How a number is built
 *
 * A [ValueNumber] is built bottom-up: a definition's right-hand side, with every operand replaced by a
 * *representative* of that operand's number. A representative is a fresh symbol, minted the first time a number is
 * seen; constants and havoc'd variables represent themselves. So `c := a + b` and `d := a + b` both number as
 * `Add(rep_a, rep_b)`, and comparing numbers is just comparing [TACExpr]s. Numbers mean something only within one
 * run of the pass, since each run mints its own representatives.
 *
 * Why a representative is never a program variable: one program variable can carry two numbers - its arm
 * definition's inside the arm, its join's below the join - and a canonical form must not confuse the two.
 *
 * ## What makes it branch-aware
 *
 *  - Every `Jumpi` condition is numbered like any other read, and the [Literal] an edge establishes is keyed by
 *    that *number* rather than by the variable's name: the condition holds on the `dst` edge and fails on the
 *    `elseDst` one. Two branches on identical but differently named conditions establish the same literal.
 *
 *  - A variable defined once per arm is numbered, below its join, as a decision tree `Ite(k, then, else)`: `k` is
 *    the condition's number, `then` and `else` the arms' numbers. Which arm is which comes from the facts -
 *    `facts(arm) - facts(join)` are the literals that single that arm out. A branch nested inside an arm gives a
 *    nested `Ite`.
 *
 * Together those are what makes twin diamonds match: the same condition number over the same arm numbers builds the
 * same tree, with no rewriting and no dominance query. Read *inside* one of its own arms, the variable is numbered
 * by that arm's definition instead, since that is what the read there actually sees.
 *
 * ## Facts
 *
 * [facts] is a must-analysis along the topological order: `facts(b)` is the intersection, over predecessors `p`, of
 * `facts(p)` plus the literal of the edge `p -> b`. Roots start with nothing. Carrying a literal forward is sound
 * because of DSA: a condition variable is never reassigned after its branch, so what the branch tested still holds
 * wherever the literal is read.
 *
 * ## Preconditions, checked rather than assumed
 *
 *  - The program is in DSA, which also means every variable read has a definition somewhere. [DSAChecker.checkDSA]
 *    runs first and throws otherwise. The pass runs just after [instrumentation.transformers.TACDSA], so this is
 *    expected to hold; the check is how we find out when it does not.
 *  - The block graph is acyclic. [vc.data.TACProgram.topoSortFw], which the walk follows, throws on a cycle.
 *  - Bound variables are unique across the program, which [instrumentation.transformers.UniqueifyBoundVars] has
 *    just seen to. A bound variable has no definition, so it is numbered as itself; were two binders to share a
 *    name, two unrelated formulas would number alike. This one is relied on rather than checked.
 *
 * ## What comes out
 *
 * Nothing is rewritten; this is an analysis. [valueNumberOf] answers for a variable or an expression read at a
 * given block. [vnOfDef] is what each definition assigns, and read backwards it gives the definitions sharing a
 * number. [facts] is the literals each block is reached under. A number that comes out a constant is a constant
 * this pass has proved.
 */
class BranchAwareGlobalValueNumbering(code: CoreTACProgram) {

    /** That [key] holds, or that it does not. A block carries a literal when it is true on every path reaching it. */
    data class Literal(val key: ValueNumber, val holds: Boolean)

    private val g = code.analysisCache.graph

    init {
        DSAChecker.checkDSA(code)
    }

    /**
     * The number each definition assigns. Read backwards, with [MutableReversibleMap.keysOf], it gives all the
     * definitions carrying a given number - an equivalence class, in the order the walk met them.
     */
    val vnOfDef = MutableReversibleMap<CmdPointer, ValueNumber>()

    /**
     * The literals that hold on every path reaching a block. Treap sets, so a block shares its predecessor's
     * literals rather than copying them.
     */
    val facts = mutableMapOf<NBId, TreapSet<Literal>>()

    /**
     * Every definition of a variable, as the walk meets them - so only live ones, a dead block never being walked.
     * Complete before the variable can be read: in any topological order, DSA puts every definition of a variable
     * before every legal read of it.
     */
    private val defsOf = mutableMultiMapOf<TACSymbol.Var, CmdPointer>()

    private val vnOfJoin = mutableMapOf<TACSymbol.Var, ValueNumber>()

    /**
     * The representative minted for each compound number, and the number each representative stands for. Kept both
     * ways because [canonical] has to undo the substitution: a simplification that collapses to a representative
     * has really produced the number that representative stands for.
     */
    private val repOfNumber = mutableMapOf<ValueNumber, TACExpr>()
    private val numberOfRep = mutableMapOf<TACExpr, ValueNumber>()

    /** What stands for [vn] inside a larger number: [vn] itself if it is a symbol or constant, else its representative. */
    private fun repOf(vn: ValueNumber): TACExpr =
        vn as? TACExpr.Sym ?: repOfNumber.getOrPut(vn) {
            TACSymbol.Var("vn!", vn.tagAssumeChecked).toUnique().asSym()
                .also { numberOfRep[it] = vn }
        }

    /**
     * [e] simplified and put back in canonical form - an operator over representatives and constants, one level
     * deep, and never a bare representative. [simplifyTop] alone can leave either of those, and both would cost
     * the numbering pairings it ought to see:
     *  - It can collapse to one of its operands, which is a representative: `Ite(c, true, false)` becomes `c`, and
     *    `Add(x, 0)` becomes `x`. The number that representative stands for is the real answer, and the one that
     *    every other definition of the same value carries.
     *  - It can build a deeper operand: `Ite(c, t, true)` becomes `LOr(LNot(c), t)`. A compound operand is a number
     *    in its own right, so it goes in as its representative, like any other.
     */
    private fun canonical(e: TACExpr): ValueNumber {
        val simplified = simplifyTop(e)
        numberOfRep[simplified]?.let { return it }
        return if (simplified is TACExpr.Sym) {
            simplified
        } else {
            simplified.rebuild { op -> op as? TACExpr.Sym ?: repOf(canonical(op)) }
        }
    }

    /**
     * The number [v] denotes when read in [block]. DSA allows only two kinds of read, giving three answers:
     *  - [v] has a single definition: that definition's number.
     *  - [v] is defined once per arm and [block] is one of those arms: that arm's number, which is what a read
     *    there sees.
     *  - [v] is defined once per arm and [block] is below the join: the [decisionTree] over the arms, told apart by
     *    [guardOf]. When no key tells them apart - an irregular join, which no tree describes - [v] stands for
     *    itself.
     *
     * The tree is built on the first read below the join, by when the walk has passed every arm, and cached: it is
     * the same at every later read.
     */
    fun valueNumberOf(v: TACSymbol.Var, block: NBId): ValueNumber {
        val defs = defsOf[v].orEmpty()
        defs.singleOrNull()?.let {
            return vnOfDef[it]!!
        }
        defs.find { it.block == block }?.let {
            return vnOfDef[it]!!
        }
        return vnOfJoin.getOrPut(v) {
            val guarded = defs.map { guardOf(it.block) to vnOfDef[it]!! }
            // arms that all agree leave nothing to decide, and their number is the answer. A regular join would
            // reach the same place through [decisionTree], whose `Ite(k, x, x)` simplifies back to `x`; this is
            // for the irregular one, where there is no key to build a tree from and the answer would be lost.
            guarded.mapToSet { it.second }.singleOrNull()
                ?: decisionTree(guarded)
                ?: v.asSym()
        }
    }

    /**
     * The literals an arm carries beyond its join, which is what tells it from its siblings.
     *
     * Subtracting the join's facts is enough because of the shape: a clean join's predecessors are exactly the
     * arms, and each arm has only the join as successor, so no arm adds a literal on the way out and the join's
     * facts are precisely what the arms share. Keyed by the arm alone, so the arms of every variable joining there
     * are computed once.
     */
    private val guardOf = memoized { armBlock: NBId ->
        facts[armBlock]!! - facts[g.succ(armBlock).single()]!!
    }

    /**
     * The number of expression [e] read in [block]: each variable stands for its own number, and the rest is
     * simplified from the leaves up. If the answer is a constant, that is a constant this pass has proved [e] to
     * be, whatever the expression looks like.
     */
    fun valueNumberOf(e: TACExpr, block: NBId): ValueNumber =
        when (e) {
            is TACExpr.Sym.Var -> repOf(valueNumberOf(e.s, block))
            is TACExpr.Sym.Const -> e
            else -> canonical(e.rebuild { valueNumberOf(it, block) })
        }

    /**
     * The number of a definition `lhs := rhs`. An annotation carries no value of its own, so a definition wrapped
     * in one is numbered by what it wraps - there is at most one annotation, at the top.
     */
    private fun valueNumberOfRhs(rhs: TACExpr, lhs: TACSymbol.Var, block: NBId): ValueNumber {
        val core = (rhs as? TACExpr.AnnotationExp<*>)?.o ?: rhs
        return when {
            // A copy takes the number of what it copies. Note this asks the variable, not the expression: the
            // expression form would wrap the answer in [repOf], which is what an operand needs and a definition
            // does not. Then `d := c` would be numbered by the representative standing for `c`'s number rather
            // than by that number, and [vnOfDef] read backwards would no longer put the two definitions together.
            core is TACExpr.Sym.Var -> valueNumberOf(core.s, block)
            // two unconstrained terms that look alike are still different values, so this definition is a leaf
            core.contains { it is TACExpr.Unconstrained } -> lhs.asSym()
            else -> valueNumberOf(core, block)
        }
    }

    /** The branch [block] ends with, if it ends with one; its two targets are distinct. */
    private fun branchOf(block: NBId) =
        g.elab(block).commands.lastOrNull()?.cmd as? TACCmd.Simple.JumpiCmd

    /**
     * The number of the condition [branch] tests. Only ever asked about a block the walk has already passed, so
     * the condition's definitions are recorded by then.
     */
    private fun condVnOf(branch: TACCmd.Simple.JumpiCmd, block: NBId): ValueNumber =
        when (val cond = branch.cond) {
            is TACSymbol.Var -> valueNumberOf(cond, block)
            is TACSymbol.Const -> cond.asSym()
        }

    /** The literal that the edge [from] -> [to] establishes, if [from] branches. */
    private fun literalOf(from: NBId, to: NBId): Literal? =
        branchOf(from)?.let { Literal(condVnOf(it, from), to == it.dst) }

    /**
     * A tree deciding between [arms], each given as the literals that single it out and its value number. Null when
     * no one key splits the arms into two sides - an irregular join, which no tree describes.
     *
     * Each level picks a key that every remaining arm has an opinion on and where both opinions occur, then
     * recurses on the two sides. One remaining arm is a leaf, whatever literals it still carries: those are implied
     * on any path that reaches it. Operands are representatives, inner decisions included, and [canonical] keeps
     * them that way, so a tree is one level deep like every other number. That is also where an `Ite` on its own
     * condition, or one whose arms agree, disappears.
     */
    private fun decisionTree(arms: List<Pair<TreapSet<Literal>, ValueNumber>>): ValueNumber? {
        arms.singleOrNull()?.let { return repOf(it.second) }
        val keys = arms
            .map { (guard, _) -> guard.mapToTreapSet { it.key } }
            .intersectAll()
        val key = keys
            .filter { k ->
                arms.any { Literal(k, true) in it.first } &&
                    arms.any { Literal(k, false) in it.first }
            }
            .minByOrNull { it.toString() } ?: return null

        fun side(holds: Boolean) = arms
            .filter { Literal(key, holds) in it.first }
            .map { (guard, vn) -> (guard - Literal(key, holds)) to vn }

        val then = decisionTree(side(true))?.let(::repOf) ?: return null
        val otherwise = decisionTree(side(false))?.let(::repOf) ?: return null
        // tagged from an arm, so that a tree is as type-checked as the numbers it selects between
        return canonical(TACExpr.TernaryExp.Ite(repOf(key), then, otherwise, then.tagAssumeChecked))
    }

    /**
     * Where control can still go from [block]: both successors when the branch condition is unknown, and only the
     * selected one when it is a constant. During the walk this is only asked about blocks already walked, so the
     * condition is numbered by then. Afterwards it answers for any block the walk reached, which is how a consumer
     * learns which edges are worth keeping.
     */
    fun liveSuccs(block: NBId): Set<NBId> {
        val branch = branchOf(block)
            ?: return g.succ(block)
        return when (condVnOf(branch, block).asConstOrNull) {
            null -> g.succ(block)
            BigInteger.ZERO -> setOf(branch.elseDst)
            else -> setOf(branch.dst)
        }
    }

    private fun record(ptr: CmdPointer, lhs: TACSymbol.Var, vn: ValueNumber) {
        vnOfDef[ptr] = vn
        defsOf.add(lhs, ptr)
    }

    init {
        for (block in code.topoSortFw) {
            // only the predecessors control can actually arrive from: one the walk never reached is unreachable,
            // and one whose edge to here was cut would contribute a literal for a path that cannot be taken. A
            // block other than the root with no such predecessor is dead, and is not walked.
            val incoming = g.pred(block)
                .mapNotNull { p ->
                    facts[p]
                        ?.takeIf { block in liveSuccs(p) }
                        ?.let { f -> literalOf(p, block)?.let { f + it } ?: f }
                }
            if (incoming.isEmpty() && block !in g.rootBlockIds) {
                continue
            }
            facts[block] = incoming.intersectAll()
            for ((ptr, cmd) in g.lcmdSequence(block)) {
                when (cmd) {
                    is TACCmd.Simple.AssigningCmd.AssignExpCmd ->
                        record(ptr, cmd.lhs, valueNumberOfRhs(cmd.rhs, cmd.lhs, block))

                    // a leaf, sound for any assigning command: it claims only that the value equals itself, and
                    // under DSA a variable has one value on any path. The commands it would be wrong for update
                    // their target in place (`ByteStore`, and the non-assigning `WordStore` and `ByteLongCopy`),
                    // and those all read what they write, which [DSAChecker.checkDSA] has already rejected.
                    // Anything else only costs precision: two equal `ByteLoad`s, say, are not paired.
                    is TACCmd.Simple.AssigningCmd ->
                        record(ptr, cmd.lhs, cmd.lhs.asSym())

                    else -> {}
                }
            }
        }
    }
}
