/*
 *     The Certora Prover
 *     Copyright (C) 2026  Certora Ltd.
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

package normalizer

import analysis.*
import analysis.smtblaster.*
import datastructures.stdcollections.*
import parallel.Parallel
import parallel.ParallelPool
import parallel.Scheduler
import parallel.pcompute
import solver.SolverConfig
import tac.NBId
import tac.Tag
import utils.*
import vc.data.*
import vc.data.tacexprutil.asSymOrNull
import java.math.BigInteger
import kotlin.collections.single

object RedundantJumpComparisonRemoval {
    private val basePredicate = pred@{ _: LTACCmd, cmd: TACCmd.Simple.AssigningCmd  ->
        val exp = (cmd as? TACCmd.Simple.AssigningCmd.AssignExpCmd)?.rhs ?: return@pred null
        if(exp !is TACExpr.BinRel) {
            return@pred null
        }
        exp.o1.asSymOrNull?.let { op1 ->
            op1 `to?` exp.o2.asSymOrNull
        }
    }

    private data class SymPred(
        val locVar: Pair<CmdPointer, TACSymbol.Var>,
        val withVar: (TACSymbol.Var) -> TACExpr
    )

    private val symbolPred = PatternDSL.build {
        val leftSymbolPred = basePredicate.lift<Pair<CmdPointer, TACSymbol.Var>, BigInteger>()(
            Var.withLocation, Const
        ).withAction { l, leftVar, rightConst ->
            SymPred(leftVar) { subst ->
                l.maybeExpr<TACExpr.BinRel>()!!.exp.copy(
                    o1 =  subst.asSym(),
                    o2 = rightConst.asTACExpr()
                )
            }
        }
        val rightSymbolPred = basePredicate.lift<BigInteger, Pair<CmdPointer, TACSymbol.Var>>()(
            Const, Var.withLocation
        ).withAction { l, leftConst, rightVar ->
            SymPred(rightVar) { subst ->
                l.maybeExpr<TACExpr.BinRel>()!!.exp.copy(
                    o1 = leftConst.asTACExpr(),
                    o2 = subst.asSym()
                )
            }
        }
        leftSymbolPred `^` rightSymbolPred
    }

    private data class CheckablePaths(
        val prevCondGen: (TACSymbol.Var) -> TACExpr,
        val prevEqZero: Boolean,
        val currGen: (TACSymbol.Var) -> TACExpr,
        val currBlock: NBId,
        val jumpICmd: LTACCmdView<TACCmd.Simple.JumpiCmd>
    )

    private fun redundantChecker(
        c: CheckablePaths,
        blasterPool: Z3BlasterPool,
    ) : Parallel<Pair<LTACCmdView<TACCmd.Simple.JumpiCmd>, Boolean>?> {
        val conditionVar = TACSymbol.Var("condVar", Tag.Bit256)
        val script = SmtExpScriptBuilder(termBuilder = SmtExpBitVectorTermBuilder)
        script.declare("condVar")
        val prevPath = c.prevCondGen(conditionVar)
        val blaster = SmtExpBitBlaster()
        val prevCondBlasted = blaster.blastExpr(prevPath) {
            check(it == conditionVar) {
                "Unexpected free var in PC from pred of ${c.currBlock} -> $prevPath & $it"
            }
            it.namePrefix
        } ?: return Scheduler.`return`(null)
        val currCond = c.currGen(conditionVar)
        val currCondBlasted = blaster.blastExpr(currCond) {
            check(it == conditionVar) {
                "Unexpected free var in PC of ${c.currBlock} -> $currCond & $it"
            }
            it.namePrefix
        } ?: return Scheduler.`return`(null)
        script.define("currCond") {
            currCondBlasted
        }
        script.assert {
            eq(prevCondBlasted, const(0)).letIf(!c.prevEqZero) { p ->
                lnot(p)
            }
        }
        return listOf(
            false,
            true
        ).map { trueBranch ->
            val forked = script.fork()
            forked.assert {
                eq(toIdent("currCond"), const(0)).letIf(trueBranch) {
                    lnot(it)
                }
            }
            forked.checkSat()
            Scheduler.rpc {
                blasterPool.submit(forked.cmdList)
            }.map solverMap@{ (unsat, timeout) ->
                if(!unsat || timeout) {
                    return@solverMap null
                }
                // this branch is infeasible, pick the OTHER one
                c.jumpICmd to !trueBranch
            }
        }.pcompute().map { res ->
            res.filterNotNull().singleOrNull()
        }
    }

    fun removeRedundantRechecks(
        c: CoreTACProgram
    ): CoreTACProgram {
        val copy = c.analysisCache.gvn
        val graph = c.analysisCache.graph
        val patt = PatternMatcher.compilePattern(graph, symbolPred)
        val redundantPaths = ParallelPool.inherit(ParallelPool.SpawnPolicy.GLOBAL) { pool ->
            ParallelPool.allocInScope(500, { to ->
                Z3BlasterPool(z3TimeoutMs = to, solverConfig = SolverConfig.z3.def, fallbackSolverConfig = SolverConfig.cvc5.bv)
            }) { blaster ->
                val checkJobs = c.parallelLtacStream().mapNotNull {
                    it.maybeNarrow<TACCmd.Simple.JumpiCmd>()
                }.filter {
                    graph.pred(it.ptr.block).size == 1
                }.mapNotNull { jumpPred ->
                    val prevBlockJump = graph.predBlock(jumpPred.ptr.block).single().commands.last().maybeNarrow<TACCmd.Simple.JumpiCmd>() ?: return@mapNotNull null
                    val prevJumpSym = (prevBlockJump.cmd.cond as? TACSymbol.Var) ?: return@mapNotNull null

                    val jumpSym = (jumpPred.cmd.cond as? TACSymbol.Var) ?: return@mapNotNull null
                    val jumpCond = patt.query(jumpSym, jumpPred.wrapped).toNullableResult() ?: return@mapNotNull null
                    val prevJumpCond = patt.query(prevJumpSym, prevBlockJump.wrapped).toNullableResult() ?: return@mapNotNull null
                    val sameVar = jumpCond.locVar.second in copy.findCopiesAt(target = jumpCond.locVar.first, source = prevJumpCond.locVar)
                    if(!sameVar) {
                        return@mapNotNull null
                    }
                    val pc = graph.pathConditionsOf(prevBlockJump.ptr.block)[jumpPred.ptr.block]
                    check(pc is PathCondition.ConditionalOn) {
                        "jumpi command $prevBlockJump does not yield conditional PC?"
                    }
                    check(pc.v == prevBlockJump.cmd.cond) {
                        "jumpi command $prevJumpCond names a different conditional than expected ${pc.v} vs ${prevBlockJump.cmd.cond}"
                    }
                    CheckablePaths(
                        prevCondGen = prevJumpCond.withVar,
                        currGen = jumpCond.withVar,
                        currBlock = jumpPred.ptr.block,
                        prevEqZero = pc is PathCondition.EqZero,
                        jumpICmd = jumpPred
                    )
                }.map {
                    redundantChecker(it, blaster)
                }.toList().pcompute()
                pool.run(checkJobs).filterNotNull()
            }
        }
        if(redundantPaths.isEmpty()) {
            return c
        }
        return c.patching { patcher ->
            patcher.selectConditionalEdges(redundantPaths)
        }
    }

}
