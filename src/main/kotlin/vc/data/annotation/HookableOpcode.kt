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

package vc.data.annotation

import vc.data.OpcodeEnvironmentBinder
import kotlin.reflect.KClass

/**
 * @param name - the name of the hook as written in CVL
 * @param hookname - used by the KSP processor to give a friendlier name for the summaries generated
 * @param onlyNoStorageSplitting - used to mark hookable opcodes that should be instrumented only if storage splitting is disabled
 * @param additionalInterfaces - Extra interfaces for the generated summary to extend
 */
@Target(AnnotationTarget.CLASS)
annotation class HookableOpcode(
    val name: String,
    val hookname: String = "",
    val onlyNoStorageSplitting: Boolean = false,
    val additionalInterfaces: Array<KClass<*>> = []
)

@Target(AnnotationTarget.CLASS)
@Repeatable
annotation class OpcodeEnvironmentParam(
    val paramName: String,
    val generator: KClass<out OpcodeEnvironmentBinder>
)

@Target(AnnotationTarget.FIELD)
annotation class OpcodeOutput

@Target(AnnotationTarget.FIELD)
annotation class OpcodeParameter(val name: String = "")

