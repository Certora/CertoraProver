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

package analysis.icfg

import com.certora.collect.*
import utils.Range
import spec.cvlast.SpecCallSummary
import utils.AmbiSerializable
import utils.KSerializable

/**
 * The "reason" why a summary has been applied to a function call,
 * intended to be shown in the CallResolutionTableSummaryInfo of the
 * underlying summary.
 */
@KSerializable
@Treapable
sealed class SummaryApplicationReason : AmbiSerializable {
    abstract val reasonMsg: String

    /**
     * The reason is derived from an application policy,
     * defined (explicitly or by a default policy) in the spec itself,
     * for each Solidity function in the "methods" block.
     */
    @KSerializable
    sealed class Spec : SummaryApplicationReason() {

        /**
         * The method's signature as appears in the methods block.
         */
        abstract val methodSignature: String?
        abstract val loc: Range
        protected val locMsg: String
            get() = if (loc is Range.Empty) {
                val comment = (loc as Range.Empty).comment
                comment.ifBlank {
                    "in the specification"
                }
            } else {
                "at $loc"
            }

        companion object {

            fun reasonFor(summ: SpecCallSummary.ExpressibleInCVL, methodSignature: String?): Spec =
                when (summ.summarizationMode) {
                    SpecCallSummary.SummarizationMode.UNRESOLVED_ONLY -> Unresolved(summ.range, methodSignature)
                    SpecCallSummary.SummarizationMode.ALL -> All(summ.range, methodSignature)
                    SpecCallSummary.SummarizationMode.DELETE -> Delete(summ.range, methodSignature)
                }
        }

        @KSerializable
        data class Delete(override val loc: Range, override val methodSignature: String?) : Spec() {
            override val reasonMsg: String
                get() = "declared $loc to apply to calls to the callee and to remove the method from the scene"
        }


        @KSerializable
        data class Unresolved(override val loc: Range, override val methodSignature: String?) : Spec() {
            override val reasonMsg: String
                get() = "declared $locMsg; applied to calls where no callee could be resolved"
        }

        @KSerializable
        data class All(override val loc: Range, override val methodSignature: String?) : Spec() {
            override val reasonMsg: String
                get() = "declared $locMsg to apply to all calls to the callee"
        }
    }

    /**
     * A decision made by CVT, e.g., havoc a function
     * call when the Prover failed to resolve the callee.
     */
    @KSerializable
    object Prover : SummaryApplicationReason() {
        override val reasonMsg: String
            get() = "chosen automatically by the Prover"

        fun readResolve(): Any = Prover
        override fun hashCode() = utils.hashObject(this)
    }

    /**
     * CLI-related run configuration decision.
     */
    @KSerializable
    data class Cli(val configFlagName: String) : SummaryApplicationReason() {
        override val reasonMsg: String
            get() = "summarized as instructed by command-line flag- $configFlagName"
    }

    @KSerializable
    data class SpecialReason(override val reasonMsg: String): SummaryApplicationReason()

    companion object {
        operator fun invoke(): String = "summary application reason"
    }
}
