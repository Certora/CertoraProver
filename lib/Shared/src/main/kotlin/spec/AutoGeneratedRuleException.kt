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

import utils.CertoraInternalErrorType
import utils.CertoraInternalException
import java.lang.Exception

class AutoGeneratedRuleException private constructor(msg: String) : Exception(msg) {

    companion object {

        private const val syntaxErrorMsgPrefix = "Syntax error in auto-generated rule:"
        private const val genRuleIdErrorMsgPrefix = "Invalid identifier of auto-generated rule:"
        private const val idInScopeErrorMsgPrefix = "Declared identifiers collision in auto-generated rule's scope:"

        @Suppress("unused")
        fun cvlSyntaxError(msg: String) {
            throw CertoraInternalException(
                CertoraInternalErrorType.CVL_AST,
                "$syntaxErrorMsgPrefix $msg",
                AutoGeneratedRuleException("$syntaxErrorMsgPrefix $msg")
            )
        }

        fun autoGenRuleIdError(msg: String) {
            throw CertoraInternalException(
                CertoraInternalErrorType.CVL_AST,
                "$genRuleIdErrorMsgPrefix $msg",
                AutoGeneratedRuleException("$genRuleIdErrorMsgPrefix $msg")
            )
        }

        fun idCollisionInAutoGenScope(msg: String) {
            throw CertoraInternalException(
                CertoraInternalErrorType.CVL_AST,
                "$idInScopeErrorMsgPrefix $msg",
                AutoGeneratedRuleException("$idInScopeErrorMsgPrefix $msg")
            )
        }

    }
}
