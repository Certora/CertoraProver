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

package sbf.tac

import sbf.cfg.SbfInstruction
import vc.data.TACCmd

private val DEBUG_INLINED_FUNC_START = tac.MetaKey<String>("debug.sbf.function.start")
private val DEBUG_INLINED_FUNC_END = tac.MetaKey<String>("debug.sbf.function.end")
private val DEBUG_UNREACHABLE_CODE = tac.MetaKey<String>("debug.unreachable_code")
private val DEBUG_EXTERNAL_CALL = tac.MetaKey<String>("debug.external_call")

/** This class annotates TAC to make easier debugging (only for devs) **/
object Debug {
    fun unreachable(inst: SbfInstruction)  =
        TACCmd.Simple.AnnotationCmd(TACCmd.Simple.AnnotationCmd.Annotation(DEBUG_UNREACHABLE_CODE, "$inst"))

    fun externalCall(fname: String): TACCmd.Simple =
        TACCmd.Simple.AnnotationCmd(TACCmd.Simple.AnnotationCmd.Annotation(DEBUG_EXTERNAL_CALL, fname))

    fun externalCall(inst: SbfInstruction.Call): TACCmd.Simple = externalCall(inst.name)

    fun satisfy(inst: SbfInstruction.Call): TACCmd.Simple = externalCall(inst)

    fun startFunction(name: String, msg: String = ""): TACCmd.Simple {
        return TACCmd.Simple.AnnotationCmd(
            TACCmd.Simple.AnnotationCmd.Annotation(
                DEBUG_INLINED_FUNC_START,
                "$name$msg"
            )
        )
    }

    fun endFunction(name: String, msg: String = ""): TACCmd.Simple {
        return TACCmd.Simple.AnnotationCmd(
            TACCmd.Simple.AnnotationCmd.Annotation(
                DEBUG_INLINED_FUNC_END,
                "$name$msg"
            )
        )
    }
}
