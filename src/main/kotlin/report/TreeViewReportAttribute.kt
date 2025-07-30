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

enum class TreeViewReportAttribute(private val repString: String) {
    JUMP_TO_DEFINITION("jumpToDefinition"),
    TIMESTAMP("timestamp"),
    RULES("rules"),
    IS_SAT_GOOD("isSATGood"),
    ASSERTS("asserts"),
    DURATION("duration"),
    STATUS("status"),
    MESSAGE("message"),
    ID("id"),
    OUTPUT("output"),
    CONTRACT("contract"),
    SPEC("spec"),
    NAME("name"),
    CHILDREN("children"),
    UI_ID("uiId"),
    IS_RUNNING("isRunning"),
    AVAILABLE_CONTRACTS("availableContracts"),
    ERRORS("errors"),
    HIGHEST_NOTIFICATION_LEVEL("highestNotificationLevel"),
    SEVERITY("severity"),
    GLOBAL_CALL_RESOLUTION("globalCallResolution"),
    NODE_TYPE("nodeType"),
    SPLIT_PROGRESS("splitProgress"),
    // used for LiveCheckInfo
    LABEL("label"),
    VALUE("value"),
    HSEV("hSev"), // "heuristical severity" -- not extremely happy with the name, but we'll see ..
    LINK("link"),
    LIVE_CHECK_INFO("LiveCheckInfo"),
    CHILDREN_TOTAL_NUM("childrenTotalNum"),
    CHILDREN_FINISHED_NUM("childrenFinishedNum"),
    DAP_CALLTRACE_FILE_NAME("debugAdapterCallTraceFileName")
    ;

    operator fun invoke(): String = repString

}
