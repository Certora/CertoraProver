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

package verifier.equivalence.cex

import utils.Either
import utils.toHexString
import verifier.equivalence.EquivalenceChecker.Companion.asBufferRepr
import verifier.equivalence.tracing.BufferTraceInstrumentation
import java.math.BigInteger
import datastructures.stdcollections.*

/**
 * Records information about an environment interaction, AKA an external call.
 *
 */
internal sealed interface ExternalCall : IEvent {
    sealed interface ReturnDataSample {
        data class Complete(
            val values: List<BigInteger>
        ) : ReturnDataSample {
            override fun prettyPrint(): String {
                return if(values.isEmpty()) {
                    "! Empty buffer"
                } else {
                    values.joinToString("") { v ->
                        v.toString(16).padStart(64, '0')
                    }
                }
            }
        }

        data object None: ReturnDataSample {
            override fun prettyPrint(): String {
                return "! No data collected"
            }
        }
        data class Prefix(
            val values: List<BigInteger>,
            val bytesMissing: BigInteger
        ) : ReturnDataSample {
            override fun prettyPrint(): String {
                return values.joinToString("") { v ->
                    v.toString(16).padStart(64, '0')
                } + "... missing $bytesMissing more bytes"
            }
        }

        fun prettyPrint(): String
    }

    /**
     * An external call whose details were fully resolved. Implements the [EventWithData] interface.
     */
    data class Complete(
        /**
         * The callee address
         */
        val callee: BigInteger,
        /**
         * The value sent along with the call
         */
        val value: BigInteger,
        /**
         * The codesize chosen by the solver for the target address
         */
        val calleeCodesize: BigInteger,
        /**
         * The returnsize chosen by the solver for the external call
         */
        val returnSize: BigInteger,
        /**
         * Whether the external call was modeled to revert/return
         */
        val callResult: Boolean,
        /**
         * The calldata as a list of bytes or an explanation for why we couldn't extract it
         */
        val calldata: Either<List<UByte>, String>,

        /**
         * sample of return data
         */
        val returnData: ReturnDataSample
    ) : ExternalCall, EventWithData {
        override val params: List<EventParam>
            get() = listOf(
                EventParam(
                    ContextLabel.ExternalCallLabel.CALLEE,
                    callee.toHexString()
                ),
                EventParam(
                    ContextLabel.ExternalCallLabel.CALL_VALUE,
                    value.toString()
                ),
                EventParam(
                    ContextLabel.ExternalCallLabel.CALLEE_CODESIZE,
                    calleeCodesize.toString()
                ),
                EventParam(
                    ContextLabel.ExternalCallLabel.CALL_RESULT, if (callResult) {
                        "Successful return"
                    } else {
                        "Revert"
                    }
                ),
                EventParam(
                    ContextLabel.ExternalCallLabel.RETURNSIZE,
                    returnSize.toString()
                ),
                EventParam(
                    ContextLabel.ExternalCallLabel.RETURNDATA,
                    returnData.prettyPrint()
                )
            )
        override val sort: BufferTraceInstrumentation.TraceEventSort
            get() = BufferTraceInstrumentation.TraceEventSort.EXTERNAL_CALL
        override val bufferRepr: List<UByte>?
            get() = calldata.leftOrNull()

        override fun prettyPrint(): String {
            val calldataStr = when(calldata) {
                is Either.Left -> calldata.d.asBufferRepr("(An empty buffer)")
                is Either.Right -> "Failed to extract calldata: ${calldata.d}"
            }
            val resultStr = if(callResult) {
                "A successful return"
            } else {
                "A revert"
            }
            return "\tExternal call to 0x${callee.toString(16)} with eth: $value\n" +
                "\tThe calldata buffer was:\n" +
                "\t\t $calldataStr\n" +
                "\t The callee codesize was chosen as: $calleeCodesize\n" +
                "\t The call result was:\n" +
                "\t\t $resultStr\n" +
                "\t\t With a buffer of length: $returnSize\n" +
                "\t\t The returned buffer model is:\n" +
                "\t\t\t " + returnData.prettyPrint() + "\n"
        }
    }

    /**
     * Error data holder explaining why we couldn't resolve all the call information
     */
    data class Incomplete(override val msg: String) : ExternalCall, IncompleteEvent {
        override fun prettyPrint(): String {
            return "\tCouldn't extract information for this call: $msg"
        }
    }
}
