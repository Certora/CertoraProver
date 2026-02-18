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

package config

import cli.ConversionException
import cli.Converter
import datastructures.stdcollections.*

enum class DestructiveOptimizationsModeEnum {
    /** Disable destructive optimizations */
    DISABLE,
    /** Enable destructive optimizations and then verify violations without them */
    TWOSTAGE,
    /** Enable destructive optimizations and then verify violations without them. Fail when a violation can not be checked */
    TWOSTAGE_CHECKED,
    /** Enable destructive optimizations */
    ENABLE,
}

val DestructiveOptimizationsModeConverter = Converter {
    when (it.lowercase()) {
        "disable", "false", "no", "default"
            -> DestructiveOptimizationsModeEnum.DISABLE
        "twostage" -> DestructiveOptimizationsModeEnum.TWOSTAGE
        "twostage-checked" -> DestructiveOptimizationsModeEnum.TWOSTAGE_CHECKED
        "enable", "true", "yes"
            -> DestructiveOptimizationsModeEnum.ENABLE
        else -> throw ConversionException(it, DestructiveOptimizationsModeEnum::class.java)
    }
}

fun DestructiveOptimizationsModeEnum.isTwoStageMode(): Boolean {
    return when(this) {
        DestructiveOptimizationsModeEnum.TWOSTAGE,
        DestructiveOptimizationsModeEnum.TWOSTAGE_CHECKED -> true
        DestructiveOptimizationsModeEnum.DISABLE,
        DestructiveOptimizationsModeEnum.ENABLE -> false
    }
}