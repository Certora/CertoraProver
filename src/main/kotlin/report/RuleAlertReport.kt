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

package report

import kotlinx.serialization.json.*
import datastructures.stdcollections.*
import datastructures.toNonEmptyList
import utils.*

/**
 * Alerts that should appear in problems view of a specific rule.
 * @property msg: detailed description of the alert
 * @property severity: info, warning or error
 * @property reason: a possible exception that causes the alert
 */
sealed class RuleAlertReport : TreeViewReportable, Comparable<RuleAlertReport> {
    abstract val msg: String
    abstract val severity: String
    abstract val reason: CertoraException?
    override val treeViewRepBuilder = TreeViewRepJsonObjectBuilder {
        put(key = TreeViewReportAttribute.SEVERITY(), value = severity)
        put(key = TreeViewReportAttribute.MESSAGE(), value = msg)
    }
    companion object {
        operator fun invoke(ruleAlertReports: List<RuleAlertReport>): RuleAlertReport? =
            ruleAlertReports.toNonEmptyList()?.let(::invoke)
    }

    data class Info(override val msg: String, override val reason: CertoraException? = null) : RuleAlertReport() {
        init {
            checkMsgIsNotEmpty()
        }

        override val severity: String
            get() = "info"
    }

    data class Warning(override val msg: String, override val reason: CertoraException? = null) : RuleAlertReport() {
        init {
            checkMsgIsNotEmpty()
        }
        constructor(msg: String, throwable: Throwable?) : this(
            msg, throwable?.let { CertoraException.fromException(throwable) }
        )

        override val severity: String
            get() = "warning"
    }

    data class Error(override val msg: String, override val reason: CertoraException? = null) : RuleAlertReport() {
        init {
            checkMsgIsNotEmpty()
        }
        constructor(msg: String, throwable: Throwable?) : this(
            msg, throwable?.let { CertoraException.fromException(throwable) }
        )
        constructor(reason: CertoraException) : this(reason.msg, reason)

        override val severity: String
            get() = "error"
    }

    protected fun checkMsgIsNotEmpty() {
        check(msg.isNotEmpty()) {
            "Was about to create a new RuleAlertReport.Single with an empty message (alert severity: $severity)"
        }
    }

    override fun compareTo(other: RuleAlertReport): Int {
        return when(this){
            is Error -> when(other){
                is Error -> 0
                is Warning, is Info -> 1
            }
            is Warning -> when(other){
                is Error -> -1
                is Warning -> 0
                is Info -> 1
            }
            is Info -> when(other){
                is Error, is Warning -> -1
                is Info -> 0
            }
        }
    }
}
