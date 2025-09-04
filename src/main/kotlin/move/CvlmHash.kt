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

package move

import analysis.CommandWithRequiredDecls.Companion.mergeMany
import datastructures.stdcollections.*
import config.*
import java.util.concurrent.ConcurrentHashMap
import tac.*
import tac.generation.*
import utils.*
import vc.data.*

/**
    Provides support for hashing for CVLM "ghost" and "hash" functions.
 */
object CvlmHash {
    /**
        We create a separate hash family for each hash (or ghost) function.  Hopefully this eases the burden on the
        solvers, since they don't need to reason about all hash functions as a group.
     */
    @KSerializable
    private data class FunctionHashFamily(val function: MoveFunctionName) : HashFamily.CvlmHashFamily() {
        override fun toString() = "cvlm_hash_fun_${function.toVarName()}"
    }

    /**
        Used for hashing individual (complex) arguments of a function call, before hashing the whole set of arguments.
     */
    @KSerializable
    private data class ArgumentHashFamily(val function: MoveFunctionName) : HashFamily.CvlmHashFamily() {
        override fun toString() = "cvlm_hash_arg_${function.toVarName()}"
    }

    // Type IDs so hashes can include the type arguments of the function.
    private val typeIds = ConcurrentHashMap<MoveType.Value, TACExpr>()
    private fun MoveType.Value.typeId() = typeIds.computeIfAbsent(this) { typeIds.size.asTACExpr }

    /**
        Generates TAC code to hash the arguments of the given [call] into [dst].
     */
    context(SummarizationContext)
    fun hashArguments(dst: TACSymbol.Var, call: MoveCall, skipFirstArg: Boolean = false): MoveCmdsWithDecls {
        val hashArgs = mutableListOf<TACExpr>()

        // Add any type arguments to the hash
        call.callee.typeArguments.forEach { type ->
            hashArgs.add(
                when (type) {
                    is MoveType.Nondet -> TACExpr.Select(
                        base = TACKeyword.MOVE_NONDET_TYPE_EQUIV.toVar().ensureHavocInit().asSym(),
                        loc = type.id.asTACExpr
                    )
                    else -> type.typeId()
                }
            )
        }

        // Unwrap any reference arguments to get the values
        val unwrappedArgs = mutableListOf<TACSymbol.Var>()
        val unwrapArgs = mergeMany(
            call.args.letIf(skipFirstArg) { it.drop(1) }.mapNotNull {
                when (val tag = it.tag) {
                    is MoveTag.Ref -> {
                        // Unwrap references to get the value
                        val unwrapped = TACKeyword.TMP(tag.refType.toTag(), "unwrapped")
                        unwrappedArgs.add(unwrapped)
                        TACCmd.Move.ReadRefCmd(
                            dst = unwrapped,
                            ref = it
                        ).withDecls(unwrapped)
                    }
                    else -> {
                        // Non-reference arguments can be used directly
                        unwrappedArgs.add(it)
                        null
                    }
                }
            }
        )

        // Simplify the value arguments by first hashing any complex values (structs, vectors) individually
        val hashComplexArgs = mergeMany(
            unwrappedArgs.mapNotNull {
                when (it.tag) {
                    is MoveTag -> {
                        val simplified = TACKeyword.TMP(Tag.Bit256, "simplified")
                        hashArgs.add(simplified.asSym())
                        TACCmd.Move.HashCmd(
                            dst = simplified,
                            loc = it,
                            hashFamily = ArgumentHashFamily(call.callee.name)
                        ).withDecls(simplified)
                    }
                    else -> {
                        // Non-complex arguments can be used directly
                        hashArgs.add(it.asSym())
                        null
                    }
                }
            }
        )

        // Finally, hash the simplified values together to get the final hash
        return mergeMany(
            unwrapArgs,
            hashComplexArgs,
            assign(dst) {
                SimpleHash(
                    hashArgs.size.asTACExpr,
                    hashArgs,
                    FunctionHashFamily(call.callee.name)
                )
            }
        )
    }
}
