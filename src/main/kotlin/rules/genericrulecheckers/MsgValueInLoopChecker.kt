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

package rules.genericrulecheckers

import datastructures.stdcollections.*
import log.Logger
import log.LoggerTypes
import rules.genericrulecheckers.msgvalueinloop.NoMsgValueInLoopInstrumenting
import scene.IScene
import scene.ITACMethod
import utils.Range
import spec.genericrulegenerators.BuiltInRuleId
import spec.genericrulegenerators.MsgValueInLoopGenerator
import vc.data.CoreTACProgram
import java.math.BigInteger

private val logger = Logger(LoggerTypes.GENERIC_RULE)

/**
 * A checker that looks for existence of references to msg.value inside loops.
 */
object MsgValueInLoopChecker: InstrumentingBuiltinRuleChecker<MsgValueInLoopGenerator>() {

    override val eId: BuiltInRuleId
        get() = BuiltInRuleId.msgValueInLoopRule

    /**
     * @return the payable functions of the current (main) contract.
     * We assume for now that currentContract == THIS.
     */
    private fun getPayableFunctionsOfCurrentContract(scene: IScene, currentContractId: BigInteger): Set<BigInteger>{
        val payableFunctions = mutableSetOf<BigInteger>()
        scene.mapContractMethodsInPlace("noMsgValueInLoop_transform") { _, method ->
            if (method.getContainingContract().instanceId == currentContractId) {
                if (method.evmExternalMethodInfo?.stateMutability?.isPayable == true) {
                        payableFunctions.add(method.sigHash?.n ?: error("No sighash for payable function $method"))
                    }
                }
            }
        return payableFunctions
    }

    /**
     * Add instrumentation to each function in the contract.
     * See NoMsgValueInLoopInstrumenting class for more information on how it works.”
     */
    override fun instrumentingTransform(scene: IScene, currentContractId: BigInteger, range: Range, m: ITACMethod): CoreTACProgram {
        val payableFunctions = getPayableFunctionsOfCurrentContract(scene, currentContractId)
        val newProg = NoMsgValueInLoopInstrumenting( scene = scene,
            m.code as
                CoreTACProgram
        ).instrumentMsgValueOccurences(m.name, payableFunctions,
            m.getContainingContract().instanceId ==
                currentContractId).newProg
        logger.debug{"containing contract ${m.getContainingContract().addressSym} " +
            "id ${m.getContainingContract().instanceId}"}
        m.updateCode(newProg)
        return m.code as CoreTACProgram
    }

    override val functionSetCanBeEmpty = true
}
