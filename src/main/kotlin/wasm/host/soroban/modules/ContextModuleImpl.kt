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

package wasm.host.soroban.modules

import analysis.CommandWithRequiredDecls.Companion.mergeMany
import datastructures.stdcollections.*
import tac.*
import tac.generation.*
import vc.data.*
import wasm.host.soroban.*
import wasm.host.soroban.types.*
import java.math.BigInteger

internal object ContextModuleImpl : ModuleImpl() {
    private val I64_NEGATIVE_ONE = (BigInteger.TWO.pow(64) - BigInteger.ONE).asTACExpr

    fun init() = mergeMany(
        assignHavoc(TACKeyword.SOROBAN_LEDGER_VERSION.toVar()),
        assignHavoc(TACKeyword.SOROBAN_LEDGER_SEQUENCE.toVar()),
        assignHavoc(TACKeyword.SOROBAN_LEDGER_TIMESTAMP.toVar()),
        assignHavoc(TACKeyword.SOROBAN_MAX_LIVE_UNTIL_LEDGER.toVar()),
        BytesType.new(TACKeyword.SOROBAN_LEDGER_NETWORK_ID.toVar()) { 32.asTACExpr }
    )

    override fun getFuncImpl(funcName: String, args: List<TACSymbol>, retVar: TACSymbol.Var?) =
        when(funcName) {
            "log_from_linear_memory" -> noVisibleEffect()
            "contract_event" -> noVisibleEffect()

            "fail_with_error" -> Trap.trap("fail_with_error")

            "get_current_contract_address" -> Contract.getCurrentAddress(retVar!!)

            "get_ledger_version" -> getLedgerVersion(retVar!!)
            "get_ledger_sequence" -> getLedgerSequence(retVar!!)
            "get_ledger_timestamp" -> getLedgerTimestamp(retVar!!)
            "get_ledger_network_id" -> getLedgerNetworkId(retVar!!)
            "get_max_live_until_ledger" -> getMaxLiveUntilLedger(retVar!!)

            "obj_cmp" -> compareObjects(retVar!!, args[0], args[1])

            else -> null
        }

    fun getLedgerVersion(dest: TACSymbol.Var) =
        assign(dest) { TACKeyword.SOROBAN_LEDGER_VERSION.toVar().asSym() }

    fun getLedgerSequence(dest: TACSymbol.Var) =
        assign(dest) { TACKeyword.SOROBAN_LEDGER_SEQUENCE.toVar().asSym() }

    fun getLedgerTimestamp(dest: TACSymbol.Var) =
        assign(dest) { TACKeyword.SOROBAN_LEDGER_TIMESTAMP.toVar().asSym() }

    fun getLedgerNetworkId(dest: TACSymbol.Var) =
        assign(dest) { TACKeyword.SOROBAN_LEDGER_NETWORK_ID.toVar().asSym() }

    fun getMaxLiveUntilLedger(dest: TACSymbol.Var) =
        assign(dest) { TACKeyword.SOROBAN_MAX_LIVE_UNTIL_LEDGER.toVar().asSym() }

    /**
        Compares two vals structurally. I256 vals have precise signed ordering semantics; other vals are compared for
        equality using their digests, while their ordering remains nondeterministic.
     */
    private fun compareObjects(retVar: TACSymbol.Var, obj1: TACSymbol, obj2: TACSymbol) =
        TACKeyword.TMP(Tag.Bit256, "!obj1I256Value").let { obj1I256Value ->
            TACKeyword.TMP(Tag.Bit256, "!obj2I256Value").let { obj2I256Value ->
                Val.withDigest(obj1.asSym()) { obj1Digest ->
                    Val.withDigest(obj2.asSym()) { obj2Digest ->
                        mergeMany(
                            IntType.I256.decodeVal(obj1I256Value, obj1.asSym()),
                            IntType.I256.decodeVal(obj2I256Value, obj2.asSym()),
                            assignHavoc(retVar),
                            assume {
                                val obj1IsI256 = Val.hasTag(obj1.asSym(), Val.Tag.I256Small) or
                                    Val.hasTag(obj1.asSym(), Val.Tag.I256Object)
                                val obj2IsI256 = Val.hasTag(obj2.asSym(), Val.Tag.I256Small) or
                                    Val.hasTag(obj2.asSym(), Val.Tag.I256Object)
                                val bothAreI256 = obj1IsI256 and obj2IsI256
                                val i256Comparison = ite(
                                    i = obj1I256Value.asSym() eq obj2I256Value.asSym(),
                                    t = 0.asTACExpr,
                                    e = ite(
                                        i = obj1I256Value.asSym() sLt obj2I256Value.asSym(),
                                        t = I64_NEGATIVE_ONE,
                                        e = 1.asTACExpr
                                    )
                                )
                                val digestComparison =
                                    ((obj1Digest eq obj2Digest) implies (retVar.asSym() eq 0.asTACExpr)) and (
                                        (obj1Digest neq obj2Digest) implies (
                                            (retVar.asSym() eq 1.asTACExpr) or
                                                (retVar.asSym() eq I64_NEGATIVE_ONE)
                                        )
                                    )

                                (bothAreI256 implies (retVar.asSym() eq i256Comparison)) and
                                    (not(bothAreI256) implies digestComparison)
                            }
                        )
                    }
                }
            }
        }
}
