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
import tac.Tag
import tac.generation.withDecls
import vc.data.*
import wasm.host.soroban.*
import wasm.impCfg.WasmToTacInfo
import java.math.BigInteger

internal object CryptoModuleImpl : ModuleImpl() {
    // TODO: https://certora.atlassian.net/browse/CERT-6439
    override fun getFuncImpl(funcName: String, args: List<TACSymbol>, retVar: TACSymbol.Var?) =
        when(funcName) {
            "compute_hash_keccak256" -> {
                check(retVar != null) { "expected a return variable for $funcName"}
                check(args.size == 1) { "expected a single argument to $funcName"}
                keccak256(retVar, args[0])
            }

            else ->
                null
        }


    private fun keccak256(output: TACSymbol.Var, input: TACSymbol): WasmToTacInfo {
        val hash = TACKeyword.TMP(Tag.Bit256, "!sha3")
        return Val.withDigest(input.asSym()) { inputDigest ->
            mergeMany(
                // We just need an injective function, and it is convenient to use
                // the TAC version to represent *user* hashes
                TACCmd.Simple.AssigningCmd.AssignSimpleSha3Cmd(
                    lhs = hash,
                    length = BigInteger.ZERO.asTACSymbol(), // The length param doesn't matter for us
                    args = listOf(inputDigest.s)
                ).withDecls(hash),

                Val.allocHandle(output, Val.Tag.BytesObject) {
                    listOf(hash.asSym())
                },
            )
        }
    }

}
