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
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package wasm.analysis.intervals

import analysis.PathCondition
import analysis.TACCommandGraph
import analysis.numeric.*
import analysis.numeric.simplequalifiedint.SimpleQualifiedInt
import analysis.numeric.simplequalifiedint.SimpleQualifiedIntSMTSemantics
import analysis.numeric.simplequalifiedint.SimpleQualifiedIntState
import analysis.smtblaster.*
import annotations.TestTags
import com.certora.collect.*
import net.jqwik.api.*
import net.jqwik.kotlin.api.any
import net.jqwik.kotlin.api.combine
import net.jqwik.kotlin.api.component1
import net.jqwik.kotlin.api.component2
import org.junit.jupiter.api.Assertions
import smtlibutils.data.SmtExp
import tac.NBId
import tac.Tag
import testing.ttl.TACMockLanguage
import utils.*
import vc.data.*
import java.math.BigInteger

@net.jqwik.api.Tag(TestTags.EXPENSIVE)
class IntervalAnalysisPropertyTest {

    fun condition(): Arbitrary<ConditionQualifier.Condition> = Arbitraries.of(
        ConditionQualifier.Condition.EQ,
        ConditionQualifier.Condition.NEQ,
        ConditionQualifier.Condition.LT,
        ConditionQualifier.Condition.SLT,
        ConditionQualifier.Condition.LE,
        ConditionQualifier.Condition.SLE,
    )

    private fun <T> List<T>.addIf(b: Boolean, f: () -> T): List<T> {
        return if (b) {
            this.plus(f())
        } else {
            this
        }
    }

    private fun connective(): Arbitrary<LogicalConnectiveQualifier.LBinOp> = Arbitraries.of(
        LogicalConnectiveQualifier.LBinOp.AND,
        LogicalConnectiveQualifier.LBinOp.OR
    )

    private fun qual(x: TACSymbol.Var, iv: IntValue, s: SimpleQualifiedIntState): Arbitrary<SimpleIntQualifier>? {
        val ints = s.s.keys.filter { it.tag == Tag.Bit256 }
        val bools = s.s.keys.filter { it.tag == Tag.Bool }
        val canBeEqual = s.s.entries
            .filter { (_, av) -> av.x.mayIntersect(iv) }
            .map { it.key }
        val multiples = (BigInteger.ONE.max(iv.lb).safeAsInt()..iv.ub.safeAsInt())
        val canBeGreater = s.s.entries.filter { (_, av) -> iv.lb < av.x.ub }.map { it.key }

        val qualGen = listOf<Arbitrary<SimpleIntQualifier>>()
            // Add MustEqual(other) for all possibile equalities
            .addIf(x.tag == Tag.Bit256 && canBeEqual.isNotEmpty()) {
                Arbitraries.of(canBeEqual).map {
                    SimpleIntQualifier.MustEqual(it)
                }
            }
            // Add Cond(a, b, c) for (c \in <, <=, ==, etc), with at least one of a, b TACSymbol.Vars in scope
            .addIf(x.tag == Tag.Bool && ints.isNotEmpty()) {
                val varThenSym = combine(
                    Arbitraries.of(ints),
                    Arbitraries.oneOf(
                        Int.any(0..10).map { it.asTACSymbol() },
                        Arbitraries.of(ints)
                    )
                ) { a, b -> a to b }
                combine(varThenSym, Boolean.any(), condition()) { (a, b), flip, c ->
                    if (flip) {
                        SimpleIntQualifier.Condition(b, a, c)
                    } else {
                        SimpleIntQualifier.Condition(a, b, c)
                    }
                }
            }
            // Boolean combinations of in-scope bools
            .addIf(x.tag == Tag.Bool && bools.isNotEmpty()) {
                combine(Arbitraries.of(bools).tuple2(), connective(), Boolean.any()) { (a, b), c, negate ->
                    SimpleIntQualifier.LogicalConnective(a, b, c, negate)
                }
            }
            // MultipleOf(k) if k could possibly divide this
            .addIf(x.tag == Tag.Bit256 && !multiples.isEmpty()) {
                Int.any(multiples).map { SimpleIntQualifier.MultipleOf(it.toBigInteger()) }
            }
            // ModularUpperBound(y, factor, strong) when factor possibly divides (y - x)
            .addIf(x.tag == Tag.Bit256 && canBeGreater.isNotEmpty()) {
                Arbitraries.of(canBeGreater).flatMap { y ->
                    Boolean.any().flatMap { strong ->
                        val yv = s.s[y]!!
                        val maxDiff = yv.x.ub - iv.x.lb
                        val minDiff = yv.x.lb - iv.x.ub
                        val mults = BigInteger.ONE.max(minDiff).safeAsInt()..maxDiff.safeAsInt()
                        Int.any(mults).map { modulus ->
                            SimpleIntQualifier.ModularUpperBound(
                                y,
                                modulus.toBigInteger(),
                                strong && modulus.toBigInteger() < iv.ub
                            )
                        }
                    }
                }
            }
        return if (qualGen.isNotEmpty()) {
            Arbitraries.oneOf(qualGen)
        } else {
            null
        }
    }

    private fun qualifiedIntInState(
        x: TACSymbol.Var,
        s: SimpleQualifiedIntState,
        nonEmptyQuals: Boolean = false
    ): Arbitrary<SimpleQualifiedInt> {
        val iv = if (x.tag == Tag.Bit256) {
            IntervalAnalysisTest.intValBetween(0, 10)
        } else {
            IntervalAnalysisTest.intValBetween(0, 1)
        }
        return iv.flatMap { qi ->
            qual(x, qi.x, s)?.set()?.ofMinSize(
                if (nonEmptyQuals) {
                    1
                } else {
                    0
                }
            )?.map {
                SimpleQualifiedInt(qi, it.filterNotNull().toSet())
            } ?: Arbitraries.of(SimpleQualifiedInt(qi, treapSetOf()))
        }
    }

    fun Int.bv() = TACSymbol.Var("x$this", tag = Tag.Bit256)
    fun Int.bool() = TACSymbol.Var("b$this", tag = Tag.Bool)

    fun state(size: Int, s: SimpleQualifiedIntState): Arbitrary<SimpleQualifiedIntState> {
        if (size == 0) {
            return Arbitraries.of(s)
        }
        val x = size.bv()
        val b = size.bool()

        return combine(qualifiedIntInState(x, s), qualifiedIntInState(b, s)) { xv, bv ->
            SimpleQualifiedIntState(s.s + treapMapOf(x to xv, b to bv))
        }.flatMap {
            state(size - 1, it)
        }
    }

    @Provide
    fun state(): Arbitrary<SimpleQualifiedIntState> {
        return state(3, SimpleQualifiedIntState(treapMapOf()))
    }

    // Returns an AssignExpCmd whose rhs is simple arithmetic
    @Provide
    fun assignBinExp(): Arbitrary<TACCmd.Simple.AssigningCmd.AssignExpCmd> {
        return Arbitraries.of(
            TACExpr.Vec.Add(1.bv().asSym(), 2.bv().asSym()),
            TACExpr.Vec.Mul(1.bv().asSym(), 2.bv().asSym()),
            TACExpr.BinOp.Sub(1.bv().asSym(), 2.bv().asSym()),
            TACExpr.BinOp.Div(1.bv().asSym(), 2.bv().asSym()),
        ).map {
            TACCmd.Simple.AssigningCmd.AssignExpCmd(0.bv(), it)
        }
    }

    // Returns an arbitrary state with a distinguished bool [0.bool()]
    // that has at least one qualifier on it and whose truth value is unknown
    @Provide
    fun propagationState(): Arbitrary<SimpleQualifiedIntState> {
        return state().flatMap { s ->
            val iv = IntValue(BigInteger.ZERO, BigInteger.ONE)
            qual(0.bool(), iv, s)
                ?.set()
                ?.ofMinSize(1)
                ?.ofMaxSize(2)
                ?.map {
                    SimpleQualifiedIntState(s.s + (0.bool() to SimpleQualifiedInt(iv, it)))
                } ?: Arbitraries.of(SimpleQualifiedIntState(s.s + (0.bool() to SimpleQualifiedInt(iv, treapSetOf()))))

        }
    }

    /**
     * @return a test graph containing a single block with [cmds] and the symbol table derived from [dom]
     */
    private fun testGraph(cmds: List<TACCmd.Simple>, dom: Set<TACSymbol.Var>): TACCommandGraph {
        val allocer = TACMockLanguage.NBIdAlloc()
        val blocks = MutableBlockGraph()
        val code = mutableMapOf<NBId, List<TACCmd.Simple>>()
        val block = allocer.freshBlock()
        blocks[block] = treapSetOf()
        code[block] = cmds
        return TACCommandGraph(blocks, code, TACSymbolTable.withTags(dom), name = "test")
    }

    private fun SimpleQualifiedIntState.declareVars(script: SmtExpScriptBuilder) {
        s.keys.forEach {
            script.declare(it.toSMTRep())
        }
    }

    private val blaster = Z3Blaster

    /**
     * Propagate the state s assuming [0.bool()] is nonzero/zero
     * and check the soundness of the resulting state
     */
    private fun runPropagationTest(s: SimpleQualifiedIntState) {
        val graph = testGraph(listOf(TACCmd.Simple.NopCmd), s.s.keys)
        val block = graph.blocks.first().id

        val interp = IntervalInterpreter(graph)
        val outTrue = interp.pathSemantics.propagate(
            graph.elab(block, 0), s, s, PathCondition.NonZero(0.bool())
        )
        val outFalse = interp.pathSemantics.propagate(
            graph.elab(block, 0), s, s, PathCondition.EqZero(0.bool())
        )
        val tm = SmtExpIntTermBuilder
        val sem = SimpleQualifiedIntSMTSemantics(tm)
        val script = SmtExpScriptBuilder(tm)

        s.declareVars(script)

        // Check
        //  Valid([[InState]] && [[NonZero(x1)]] ==> [[OutState]])
        //  UNSAT([[InState]] && [[NonZero(x1)]] && ![[OutState]])

        // [[InState]]
        with(sem) {
            s.denotation.forEach {
                script.assert { it }
            }
        }

        val trueScript = script.fork()
        val falseScript = script.fork()

        ///// True case
        // [[NonZero(x1)]]
        with(sem) {
            trueScript.assert {
                0.bool().asSMT.bvPosBool
            }
            falseScript.assert {
                0.bool().asSMT.bvNegBool
            }
        }

        // ![[OutState]]
        // Note that if OutState is null (unreachable), then its denotation is "false"
        // i.e. Valid([[InState]] && [[NonZero(x1)]] ==> false)
        // <==> UNSAT([[InState]] && [[NonZero(x1)]] && true)
        // <==> UNSAT([[InState]] && [[NonZero(x1)]])
        with(sem) {
            trueScript.assert {
                with(tm) {
                    lnot(
                        outTrue?.denotation?.let(SmtExpIntTermBuilder::land) ?: eq(const(0), const(1))
                    )
                }
            }
            falseScript.assert {
                with(tm) {
                    lnot(
                        outFalse?.denotation?.let(SmtExpIntTermBuilder::land) ?: eq(const(0), const(1))
                    )
                }
            }
        }
        trueScript.checkSat()
        falseScript.checkSat()

        blaster.blastSmtOrTimeout(trueScript.cmdList).let { (result, timeout) ->
            Assertions.assertTrue(timeout || result) {
                "true case failed $result\n$outTrue\n${trueScript.cmdList.joinToString(separator = "\n")}"
            }
        }
        blaster.blastSmtOrTimeout(falseScript.cmdList).let { (result, timeout) ->
            Assertions.assertTrue(timeout || result) {
                "false case failed $result\n$outFalse\n${falseScript.cmdList.joinToString(separator = "\n")}"
            }
        }
    }

    /**
     * Starting in state [inState] generate [outState] from [cmd] that assigns to, [0.bv()]
     * and check the soundness of the abstract value for [0.bv()] in the resulting state
     */
    private fun runBinExpTest(inState: SimpleQualifiedIntState, cmd: TACCmd.Simple.AssigningCmd.AssignExpCmd) {
        val graph = testGraph(listOf(cmd), inState.s.keys)
        val block = graph.blocks.single().id
        val interp = IntervalInterpreter(graph)
        val outState = interp.step(graph.elab(block, 0), inState)

        val blaster = SmtExpBitBlaster()
        val tm = SmtExpBitVectorTermBuilder
        val sem = SimpleQualifiedIntSMTSemantics(tm)
        val script = SmtExpScriptBuilder(tm)

        inState.declareVars(script)
        script.declare(cmd.lhs.toSMTRep())

        // Check
        //   Valid([[InputState]] && lhs = [[cmd.rhs]](inputState)[lhs] ==> [[ outputState[lhs] ]]
        //   UNSAT([[InputState]] && lhs = [[cmd.rhs]](inputState)[lhs] && ![[ outputState[lhs] ]]

        // Input state
        with(sem) {
            inState.denotation.forEach {
                script.assert { it }
            }
        }
        // Semantics of expression
        val rhsSMT = blaster.blastExpr(cmd.rhs) { v -> v.toSMTRep() }!!
        with(sem) {
            script.assert {
                eq(cmd.lhs.asSMT, rhsSMT)
            }
        }
        // Negated lhs assertion
        with(sem) {
            script.assert {
                lnot(outState.denotation.let(::land))
            }
        }

        script.checkSat()
        val (result, timeout) = Z3Blaster.blastSmtOrTimeout(script.cmdList)
        Assertions.assertTrue(timeout || result) {
            "Got $result\n${script.cmdList.joinToString(separator = "\n")}"
        }
    }

    @Property(tries = 1000)
    fun testPropagation(@ForAll("propagationState") s: SimpleQualifiedIntState) {
        runPropagationTest(s)
    }

    @Property(tries = 1000)
    fun testBinExp(
        @ForAll("state") s: SimpleQualifiedIntState,
        @ForAll("assignBinExp") binExp: TACCmd.Simple.AssigningCmd.AssignExpCmd
    ) {
        runBinExpTest(s, binExp)
    }
}

context(SimpleQualifiedIntSMTSemantics)
private val SimpleQualifiedIntState.denotation: List<SmtExp>
    get() = s.entries.map { (x, a) ->
        a.smtRep(x.asSMT)
    }
