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

import com.certora.collect.*
import datastructures.stdcollections.*
import analysis.*
import vc.data.*
import utils.*
import utils.CertoraInternalException
import utils.CertoraInternalErrorType
import tac.*
import java.math.BigInteger
import vc.data.tacexprutil.*
import analysis.dataflow.AbstractValuation.*

/*
 * contact: Andrew Ferraiuolo (andrew@certora.com)
 * This implements a Conditional Constant Propagation dataflow analysis
 * and transformation pass.  Here are several references about CCP:
 * https://www.cs.cornell.edu/courses/cs4120/2021sp/lectures/23ccp/lec23-sp19.pdf (shortest / fastest to read)
 * https://en.wikipedia.org/wiki/Sparse_conditional_constant_propagation
 * https://www.cs.wustl.edu/~cytron/531Pages/f11/Resources/Papers/cprop.pdf
 *
 * Essentially, CCP improves over constant
 * propagation and constant folding by also taking control flow into
 * account. When a branching condition can be rewritten as a constant,
 * CCP will also use only the always-taken branch to determine the values
 * of variables asigned under the branch. For example,
 *
 * x = 3;
 * if (x == 5) {
 *     y = x + 12
 * } else {
 *     y = 0
 * }
 * z = y + 1
 *
 * would be simplified to
 * x = 3
 * y = 17
 * z = 18
 *
 * whereas a conventional constant propagation pass will not take the constant
 * branch into account.
 *
 * Notably, after this pass has run, it will leave behind many assignments
 * to variables that are no longer live, so a live variable analysis should
 * be redone after this pass. For this specific instance of CCP, it will also
 * leave behind many commands simplified to:
 * - `assume true`
 * - unconditional jumps to a branch only reachalbe from this unconditional jump
 * As a result this pass will be followed by calls to optimizations
 * that address these.
 *
 * For a complete description of how this works, see the links. In short,
 * it is a DFA with a state that tracks both: 1) a mapping from variables
 * to an abstract interpretation which is either a constant or under/over
 * defined, and 2) whether a command is reachable or not. Reachability
 * is taken into account when assigning abstract valuations to variables.
 *
 * This version of CCP has some tweaks to adapt to TAC. Importantly:
 * 1) In this case, because it is an assembly-like language involving
 * conditional jumps to labels / block identifiers, the part of the abstract
 * state used to track reachability needs to be adapted. Usually, it is
 * just a single bool tracking whether or not the command is reached.
 * In this case, since blocks may not be immediate successors of the
 * jump command, the analysis dynamically controls which commands to process
 * or skip -- provably unreachable commands re not processed at all, and
 * the state is mapped to Null for these.
 * 2) Because this language also defines valuations of variables with assumes,
 * these are also used to populate the valuations of variables when the assumed
 * expressions involve equality.
 */

// This is either a TacSymbol.Const, Underdefined, or Overdefined.
private sealed class AbstractValuation {
    // UNDERDEFINED: The analysis has no value for this var (yet/ever)
    object UNDERDEFINED: AbstractValuation()
    // OVERDEFINED: The analysis has 2 or more conflicting values for this var
    // (possibly in two different branches that might be reachable)
    object OVERDEFINED: AbstractValuation()
    data class ConstValue(val value: BigInteger): AbstractValuation()

}


private object AbstractValuationLattice: JoinLattice<AbstractValuation> {
    override fun join(x: AbstractValuation, y: AbstractValuation): AbstractValuation =
        when {
            x is OVERDEFINED || y is OVERDEFINED -> OVERDEFINED
            x is UNDERDEFINED -> y
            y is UNDERDEFINED -> x
            else -> {
                check(x is ConstValue && y is ConstValue)
                if (x.value == y.value) {
                    ConstValue(x.value)
                } else {
                    OVERDEFINED
                }
            }
        }

    override fun equiv(x: AbstractValuation, y: AbstractValuation): Boolean
        = x == y

}

private typealias CCPState = TreapMap<TACSymbol.Var, AbstractValuation>

private object CCPStateLattice: JoinLattice<CCPState> {

    override fun join(x: CCPState, y: CCPState): CCPState {
        return x.pointwiseMerge(y, {
            vx, vy -> AbstractValuationLattice.join(vx, vy)}
        )
    }
    override fun equiv(x: CCPState, y: CCPState): Boolean {
        return x == y
    }

    val emptyMap =
        treapMapOf<TACSymbol.Var, AbstractValuation>()
}

object ConditionalConstantPropagation {

    public fun optimize(prog: CoreTACProgram): CoreTACProgram {
        // The DFA generates states that describe the optimizing transformations
        // we need to do, but it does not actually do them yet.
        val stateMapIn = ConditionalConstantPropagationDFA(prog.analysisCache.graph).cmdIn

        val optimizingPairs = prog.ltacStream().toList().map {
            if(it.ptr !in stateMapIn) {
                listOf()
            } else if (it.cmd !is TACCmd.Simple.JumpiCmd) {
                listOf()
            } else {
                val state = stateMapIn.get(it.ptr)
                val condVal = absInterpSymbol(state!!, it.cmd.cond)
                if (condVal is ConstValue) {
                    val cmdView = LTACCmdView<TACCmd.Simple.JumpiCmd>(it)
                    listOf(Pair(cmdView, BigIntConstToBool(condVal.value)))
                } else { listOf() }
            }
        }.flatten()
        val cmdsPreOpti = prog.ltacStream()
        return prog.patching {
            for (cmd in cmdsPreOpti) {
                if (cmd.ptr !in stateMapIn) {
                     continue
                }
                val thisCmdState = stateMapIn.get(cmd.ptr)
                val optimizationResult = optimizeConstants(cmd, thisCmdState!!)
                if (optimizationResult != null) {
                    it.replaceCommand(cmd.ptr, listOf(optimizationResult))
                }
            }
            it.selectConditionalEdges(optimizingPairs)
        }
    }

    // This function modifies the commands so that the propagated constants
    // are substituted in. Because CCP accounts for control flow when building
    // this state, constants used in branching are already accounted for.
    //
    // At present, a limitation of this is that only commands that can
    // completely be reduced to constants are simplified. For example
    // x = 3
    // y = x + 4
    // will be simplified to
    // x = 3
    // y = 7
    // but y = z + 4 will not be simplified at all (unless we have
    // a constant valuation for z)
    private fun optimizeConstants(cmd: LTACCmd, thisCmdState: CCPState): TACCmd.Simple? {
        fun symConstExp(v: BigInteger, tag: Tag?) =
            if (tag == null) { v.asTACExpr }
            else { v.asTACExpr(tag) }

        val tac = cmd.cmd
        return when(tac) {
            is TACCmd.Simple.AssigningCmd.AssignExpCmd -> {
                if(tac.rhs is TACExpr.Sym.Const) { return null }
                val rhsAbs = absInterpExp(thisCmdState, tac.rhs)
                if(rhsAbs is ConstValue) {
                    TACCmd.Simple.AssigningCmd.AssignExpCmd(tac.lhs, symConstExp(rhsAbs.value, tac.rhs.tag), tac.meta)
                }
                else {
                    null
                }
            }
            is TACCmd.Simple.AssumeExpCmd -> {
                if(tac.cond is TACExpr.Sym.Const) { return null }
                val absCond = absInterpExp(thisCmdState, tac.cond)
                if(absCond is ConstValue) {
                    TACCmd.Simple.AssumeExpCmd(symConstExp(absCond.value, tac.cond.tag), tac.meta)
                } else {
                    null
                }
            }
            is TACCmd.Simple.AssumeCmd-> {
                if(tac.cond is TACSymbol.Const) { return null }
                val absCond = absInterpSymbol(thisCmdState, tac.cond)
                if(absCond is ConstValue) {
                    TACCmd.Simple.AssumeCmd(absCond.value.asTACSymbol(tac.cond.tag), tac.msg)
                } else {
                    null
                }
            }
            is TACCmd.Simple.AssumeNotCmd -> {
                if(tac.cond is TACSymbol.Const) { return null }
                val absCond = absInterpSymbol(thisCmdState, tac.cond)
                if(absCond is ConstValue) {
                    TACCmd.Simple.AssumeNotCmd(absCond.value.asTACSymbol(tac.cond.tag))
                } else {
                    null
                }
            }
            else -> null
        }
    }

    // The fact that this optimization works by using a subclass of
    // CommandDataflowAnalysis is an implementation detail that may be changed
    // at any time. Hence, this is implemented as a private nested class.
    private class ConditionalConstantPropagationDFA(graph: TACCommandGraph):
        TACCommandDataflowAnalysis<CCPState>(graph, CCPStateLattice,
        // Note that the initial state for this DFA is more like
        // a map from all variables to OVERDEFINED.
        // (See that absInterpSymbol maps missing elements to OVERDEFINED)
        CCPStateLattice.emptyMap, Direction.FORWARD) {

        init {
            runAnalysis()
        }

        // Skip branches that are provably not taken. For this implementation
        // this is done by dynamically controlling which blocks are traversed at
        // all by the DFA. If a block is skipped, it will be left with a
        // null value map which is used to indicate the block is unreachable.
        override protected fun filterNext(succ: Collection<NBId>, currBlock: NBId, postState: CCPState) : Collection<NBId> =
            graph.elab(currBlock).commands.last().maybeNarrow<TACCmd.Simple.JumpiCmd>()?.let {
                val jumpCmd = it.cmd
                // The unsafe dereference here should be safe in practice
                // because filterNext is only called on blocks that have
                // been processed.
                val condVal = absInterpSymbol(postState, jumpCmd.cond) as? ConstValue ?: return@let null
                check(succ.toSet() == setOf(jumpCmd.dst, jumpCmd.elseDst))
                val ret = if(BigIntConstToBool(condVal.value)) {
                   setOf(jumpCmd.dst)
                } else {
                   setOf(jumpCmd.elseDst)
                }
                ret
        } ?: succ

        // For the given command and state, return the state updated to map
        // each variable newly defined in that command to its value which
        // is interpreted abstractly (using the incoming CCPState)
        private fun definesState(inState: CCPState, ltacCmd: LTACCmd): CCPState {
            // This helper function is used to deal with assumes over
            // equalities. If constExp is constant C, then it will return a
            // pair that "implies" varExp == const
            fun mapVarEquality(varExp: TACExpr, constExp: TACExpr): Pair<TACSymbol.Var, AbstractValuation>? {
                val constExpInterp = absInterpExp(inState, constExp)
                return if(varExp is TACExpr.Sym.Var &&
                    constExpInterp is ConstValue) {
                        (varExp.s to constExpInterp)
                } else {
                    null
                }
            }

            val tacCmd = ltacCmd.cmd
            return when(tacCmd) {
                is TACCmd.Simple.AssigningCmd.AssignExpCmd -> {
                    val absRHS = absInterpExp(inState, tacCmd.rhs)
                    inState + (tacCmd.lhs to absRHS)
                }
                // The assigning commands that deal with any of: modifying arrays,
                // hashes, or memory are treated conservatively (mapping the RHS to
                // a dynamic/overdefined value) for now.
                is TACCmd.Simple.AssigningCmd -> {
                    inState + (tacCmd.lhs to OVERDEFINED)
                }
                // For the special (and somewhat narrow but also common enough
                // to be useful) case when an AssumeExp
                // is exactly an Eq operator where one side is exactly a Var
                // and the other side can be interpreted as a constant,
                // define the var as the constant.
                is TACCmd.Simple.AssumeExpCmd -> {
                    when(val cond = tacCmd.cond) {
                        is TACExpr.BinRel.Eq -> {
                            val tryLeftIsVar = mapVarEquality(cond.o1, cond.o2)
                            val tryRightIsVar = mapVarEquality(cond.o2, cond.o1)
                            if(tryLeftIsVar != null) {
                                inState + tryLeftIsVar
                            } else if(tryRightIsVar != null) {
                                inState + tryRightIsVar
                            } else {
                                inState
                            }
                        }
                        else -> {
                            inState
                        }

                    }
                }
                else -> {
                    inState
                }
            }
        }

        override fun transformCmd(inState: CCPState, cmd: LTACCmd)
            = definesState(inState, cmd)
    }

    // These helper functions are shared between the outer CCP object
    // and the inner nested private class, so they live in the outer object.
    private fun absInterpSymbol(state: CCPState, sym: TACSymbol): AbstractValuation {
        return when(sym) {
            is TACSymbol.Var -> state.getOrElse(sym, { OVERDEFINED })
            is TACSymbol.Const -> ConstValue(sym.value)
        }
    }

    private fun absInterpExp(state: CCPState, exp: TACExpr): AbstractValuation {
            // // For correct functionality we need to check if any of the free
            // // variables have some non-constant abstract valuation before
            // // trying to do the per-expression-type semantics. A reader
            // // may think intuitively that the right way to handle the case
            // // where not all values are constant is to take the join over the
            // // free vars. However, this does not correctly handle the case
            // // where one variable is underdefined and one variable is constant
            // // -- the join of this is the one constant, but what you want to
            // // return is underdefined.
            // (Taking the meet instead would pose a similar problem)

            return when (exp) {
                is TACExpr.Sym -> {
                    absInterpSymbol(state, exp.s)
                }
                is TACExpr.BinOp-> {
                    val v1 = absInterpExp(state, exp.o1)
                    val v2 = absInterpExp(state, exp.o2)
                    when {
                        v1 is ConstValue && v2 is ConstValue ->
                            ConstValue(exp.eval(v1.value, v2.value))
                        v1 is OVERDEFINED || v2 is OVERDEFINED -> OVERDEFINED
                        v1 is UNDERDEFINED || v2 is UNDERDEFINED -> UNDERDEFINED
                        else -> `impossible!`
                    }
                }
                is TACExpr.BinRel -> {
                    val v1 = absInterpExp(state, exp.o1)
                    val v2 = absInterpExp(state, exp.o2)
                    when {
                        v1 is ConstValue && v2 is ConstValue ->
                            ConstValue(exp.eval(v1.value, v2.value))
                        v1 is OVERDEFINED || v2 is OVERDEFINED -> OVERDEFINED
                        v1 is UNDERDEFINED || v2 is UNDERDEFINED -> UNDERDEFINED
                        else -> `impossible!`
                    }
                }
                is TACExpr.Vec -> {
                    val operandConsts = exp.ls.monadicMap { (absInterpExp(state, it)  as? ConstValue)?.value } ?: return OVERDEFINED
                    ConstValue(exp.eval(operandConsts))
                }
                is TACExpr.BinBoolOp -> {
                    val operandConsts = exp.ls.monadicMap { (absInterpExp(state, it)  as? ConstValue)?.value } ?: return OVERDEFINED
                    ConstValue(exp.eval(operandConsts))
                }

                is TACExpr.TernaryExp -> {
                    val v1 = absInterpExp(state, exp.o1)
                    val v2 = absInterpExp(state, exp.o2)
                    val v3 = absInterpExp(state, exp.o3)
                    when {
                        v1 is ConstValue && v2 is ConstValue
                            && v3 is ConstValue ->
                            ConstValue(exp.eval(v1.value, v2.value, v3.value))
                        v1 is OVERDEFINED || v2 is OVERDEFINED
                            || v3 is OVERDEFINED -> OVERDEFINED
                        v1 is UNDERDEFINED || v2 is UNDERDEFINED
                            || v3 is UNDERDEFINED -> UNDERDEFINED
                        else -> `impossible!`
                    }
                }
                is TACExpr.UnaryExp -> {
                    val opVal = absInterpExp(state, exp.o)
                    when(opVal) {
                        is ConstValue -> ConstValue(exp.eval(opVal.value))
                        is OVERDEFINED -> OVERDEFINED
                        is UNDERDEFINED -> UNDERDEFINED
                    }
                }
                // For now anything related to functions, maps, and
                // stores are modeled imprecisely as dynamic values
                // in order to build this optimization incrementally.
                else -> {
                    OVERDEFINED
                }
            }
        }

    private fun BigIntConstToBool(sym: BigInteger): Boolean {
        return when(sym) {
            BigInteger.ZERO -> false
            BigInteger.ONE -> true
            else -> throw CertoraInternalException(CertoraInternalErrorType.CVL_COMPILER, "Expecting the symbol $sym to be a boolean, but it is not.")
        }
    }

}
