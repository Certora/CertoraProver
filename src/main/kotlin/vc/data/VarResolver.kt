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

package vc.data

import tac.CallId
import utils.*

/**
 * A utility class for fetching special variables out of TAC code
 */
class VarResolver(private val symbolTable: TACSymbolTable, callId: CallId) {
    constructor(c: CoreTACProgram, callId: Int) : this(c.symbolTable, callId)

    private fun findCallSpecificByKeyword(keyword: TACKeyword, callId: CallId) =
        symbolTable.vars.singleOrNull { it.namePrefix == keyword.getName() && it.callIndex == callId }

    val address by lazy { findCallSpecificByKeyword(TACKeyword.ADDRESS, callId) ?: TACKeyword.ADDRESS.toVar(callId) }
    // msg
    val caller by lazy { findCallSpecificByKeyword(TACKeyword.CALLER, callId) ?: TACKeyword.CALLER.toVar(callId) }
    val callvalue by lazy { findCallSpecificByKeyword(TACKeyword.CALLVALUE, callId) ?: TACKeyword.CALLVALUE.toVar(callId) }
    // tx
    val origin by lazy {  findCallSpecificByKeyword(TACKeyword.ORIGIN, callId) ?: TACKeyword.ORIGIN.toVar(callId) }
    // block
    val basefee by lazy { findCallSpecificByKeyword(TACKeyword.BASEFEE, callId) ?: TACKeyword.BASEFEE.toVar(callId) }
    val blobbasefee by lazy { findCallSpecificByKeyword(TACKeyword.BLOBBASEFEE, callId) ?: TACKeyword.BLOBBASEFEE.toVar(callId) }
    val coinbase by lazy { findCallSpecificByKeyword(TACKeyword.COINBASE, callId) ?: TACKeyword.COINBASE.toVar(callId) }
    val difficulty by lazy { findCallSpecificByKeyword(TACKeyword.DIFFICULTY, callId) ?: TACKeyword.DIFFICULTY.toVar(callId) }
    val gasLimit by lazy { findCallSpecificByKeyword(TACKeyword.GASLIMIT, callId) ?: TACKeyword.GASLIMIT.toVar(callId) }
    val blocknum by lazy { findCallSpecificByKeyword(TACKeyword.NUMBER, callId) ?: TACKeyword.NUMBER.toVar(callId) }
    val timestamp by lazy {  findCallSpecificByKeyword(TACKeyword.TIMESTAMP, callId) ?: TACKeyword.TIMESTAMP.toVar(callId) }
    val sighash by lazy {  findCallSpecificByKeyword(TACKeyword.SIGHASH, callId) ?: TACKeyword.SIGHASH.toVar(callId) }
    val calldata by lazy {  findCallSpecificByKeyword(TACKeyword.CALLDATA, callId) ?: TACKeyword.CALLDATA.toVar(callId) }
}
