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

package spec

import report.CVTAlertSeverity
import report.CVTAlertType
import report.CVTAlertReporter
import report.TreeViewLocation
import utils.Range
import java.util.concurrent.ConcurrentHashMap

object CVLWarningLogger {
    // Keeps track of all emitted warnings in order to avoid emitting the same one multiple times
    private val warnings = ConcurrentHashMap<String, Boolean>()

    private fun warn(type: CVTAlertType, msg: String, location: TreeViewLocation?) {
        if (!warnings.containsKey(msg)) {
            warnings[msg] = true
            CVTAlertReporter.reportAlert(
                type = type,
                severity = CVTAlertSeverity.WARNING,
                jumpToDefinition = location,
                message = msg,
                hint = null
            )
        }
    }

    fun generalWarning(msg: String) {
        warn(CVTAlertType.GENERAL, "Warning: $msg", null)
    }

    /**
     * @param range the range in the spec file that led to the error (use range.Empty if there is none)
     */
    fun syntaxWarning(msg: String, range: Range) {
        warn(CVTAlertType.CVL, "Syntax warning in spec file $range: $msg", range as? TreeViewLocation)
    }

    fun warning(type: CVTAlertType, msg: String, location: TreeViewLocation? = null) {
        warn(type, msg, location)
    }

    fun warning(type: CVTAlertType, msg: String, location: Range?) {
        warning(type, msg, location as? TreeViewLocation)
    }
}
