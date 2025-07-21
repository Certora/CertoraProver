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

package rules.genericrulecheckers.msgvalueinloop

import analysis.*
import analysis.icfg.CallGraphBuilder
import scene.ISceneIdentifiers
import tac.Tag
import vc.data.*
import vc.data.tacexprutil.TACExprFactSimple
import java.math.BigInteger
import java.util.stream.Collectors

/**
 * For delegate calls:
 * For a delegate call inside a loop to unresolved contract an error occurs in the following cases
 * when msg.value is greater than zero at the delegatecall statement
 * 1. The call is to this and there is a payable function in the current contract.
 * 2. the call is to an unresolved contract.
 * @param[tacProgram] - tac of one function.
 * @param[cmdsInLoops] - nodes inside loops.
 * @param[delegateErrorVar] - the variable for monitoring the existence of  delegatecalls inside loops.
 * */
class DelegateCallInLoop(
    val scene: ISceneIdentifiers,
    val tacProgram: CoreTACProgram,
    private val cmdsInLoops: Set<CmdPointer>,
    private val delegateErrorVar: TACSymbol.Var
) {
    val cfg = tacProgram.analysisCache.graph

    /**
     * @param[callSummary] the delegate call summary.
     * @return the expr <callee contract> == <current contract>
     */
    private fun isCurrentContractExpr(callSummary: CallSummary, contractAddressSym: TACSymbol.Var): TACExpr {
        val callee = callSummary.toVar
        return TACExprFactSimple.Eq(callee.asSym(), contractAddressSym.asSym(), Tag.Bool)
    }

    /**
     * @param[summ] - the summary of a delegate call
     * @return true in the following cases:
     * Explanation on FullResolved callee:
     * If calleeresolution is CalledContract.FullyResolved:
     * If the sighash (i.e., summ.sigResolution is not empty) is also resolved, then the (delegate) call
     * would be inlined, and you would not see a CallSummary command, but instead,
     * a PUSH annotation that marks the start of the callee function.
     * Otherwise, if the sighash is unresolved (i.e., summ.sigResolution is empty), then you will have a CallSummary.
     * Cases for which delegateErrorVar is updated:
     * The callee is unresolved (null or SymbolicInput)
     * sighash is empty && (calleeresolution is fullyresolved and equal to THIS)
     *
     * Examples:
     * this.delegateCall(symbolic unresolved) or this.delegateCall("unresolved function") - calleeresolution is
     * FullyResolved.ConstantAddress
     * <known other contract>.resolved - null calleeresolution. sigresolution.length == 1.
     * 1. byAddressContract.delegatecall(calls['i']) - sighash empty, calleeresolutionis FullyResolved.storageLink.
     * 2. _addr.delegatecall(calls['i']) where _addr, calls unresolved - sighash empty, callresolution null
     * 3. callee.delegatecall(abi.encodeWithSignature("addBalance(address)", receivers['i'])) callee unresolved -
     *    sighash.size==1 calleeresolution symbolicInput.
     * 4. _addr.delegatecall(abi.encodeWithSignature("doDelegated()") - sighash.size==1, calleeresolution - symbolic
     * fallback - calleeresolution StorageLink(contractId=<int>)
     * 5. address(this).delegatecall(calls['i']) - sighash empty, calleesummary fullyresolved.constantaddress
     * 6. address(this).delegatecall(abi.encodeWithSignature("doDelegate(address)", receivers['i'])) - doDelegate not in
     * the contract and considered external by cvt. sigresolution.size==1, calleeresolution is FullyResolved.SelfLink.
     * Should give an error because it will fall to the fallback.
     * For this case no update to the error:
     * address(this).delegatecall(abi.encodeWithSignature("addBalance(address)", receivers['i'])) - addBalance resolved
     *  no summary.
     *  todo:
     *  Support delegate calls to functions that are not in the contract. Currently we check if a resolved function is
     *  payable so if it is not payable and not in the contract an error will not be given. This is in order to avoid
     *  false possitives for non payable functions in the contract when the callee is symbolic.
     *  However, in case the called function is not in the contract the run will get to the fallback.
     *  If the delegate call is in a loop and the fallback is payable an error should be issued but is currently not.
     *  We need to have special handling for this case.
     */
    private fun maybeUnresolvedFunctionOfCurrentContract(
        summ: CallSummary,
        payableFunctions: Set<BigInteger>
    ): Boolean {
        // Checking the number of function sigResolutions is as expected.
        require(summ.sigResolution.size <= 1) {
            "Expected at most one sigResolution but got " + "${summ.sigResolution.size}"
        }
        return summ.sigResolution.isEmpty() || summ.callTarget.any { (it is CallGraphBuilder.CalledContract.SymbolicInput || it is CallGraphBuilder.CalledContract.Unresolved) &&
            summ.sigResolution.first() in payableFunctions }
    }

    private fun getUnresolvedDelegateCalls(): List<LTACCmdView<TACCmd.Simple.SummaryCmd>> =
        tacProgram.parallelLtacStream().filter {
            it.snarrowOrNull<CallSummary>()?.callType == TACCallType.DELEGATE
        }.map { cmd -> LTACCmdView<TACCmd.Simple.SummaryCmd>(cmd) }.collect(Collectors.toList())

    /**
     * @param[cmdToInstrumentation] - maps a cmdPointer to the list of commands to be inserted after it.
     * @param[contractAddressSym] - the tac variable referring to the current contract.
     * At this point tacAddress (the tac variable of the current contract is not in typescope yet.
     * @return a map c:CmdPointer to the list of commands to instrument after c.
     */
    fun findUnresolvedDelegateCalls(
        cmdToInstrumentation: MutableMap<CmdPointer, MutableList<SimpleCmdsWithDecls>>,
        contractAddressSym: TACSymbol.Var?,
        payableFunctions: Set<BigInteger>): Map<CmdPointer, List<SimpleCmdsWithDecls>> {
        require(contractAddressSym != null) { "tacAddress var undefined" }
        val unresolvedDelegateCalls = getUnresolvedDelegateCalls()
        val delegateInLoops =
            unresolvedDelegateCalls.filter { it.ptr in cmdsInLoops }
        delegateInLoops.forEach { ltacCmd ->
            val summ = ltacCmd.cmd.summ as CallSummary
            // The error variable is updated iff either the callee is unresolved or it is resolved and payable.
            // Since we are interested only in callees where the contract is this, it is enough that we look in the
            // payable functions of this.
            if (maybeUnresolvedFunctionOfCurrentContract(summ = summ, payableFunctions = payableFunctions)) {
                isCurrentContractExpr(summ, contractAddressSym).let { expr ->
                    cmdToInstrumentation.computeIfAbsent(ltacCmd.ptr) { mutableListOf() }
                        .add(ErrorCmdGenerator.errorVarUpdateCmd(delegateErrorVar, expr))
                }
            }
        }
        return cmdToInstrumentation
    }
}
