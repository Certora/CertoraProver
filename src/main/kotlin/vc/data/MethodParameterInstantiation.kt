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

package vc.data

import bridge.EVMExternalMethodInfo
import log.*
import utils.Range
import utils.sameValueOrNull

private val logger = Logger(LoggerTypes.COMMON)

/**
 * Denotes how method parameters of a rule (as e.g. the "f" in "rule check_something(method f)") have been instantiated.
 */
class MethodParameterInstantiation(private val paramNameToEVMMethodInstance: Map<TACSymbol.Var, EVMExternalMethodInfo>) :
    Map<TACSymbol.Var, EVMExternalMethodInfo> by paramNameToEVMMethodInstance {
    fun getInstance(methodParamName: TACSymbol.Var): EVMExternalMethodInfo? = paramNameToEVMMethodInstance[methodParamName]
    fun getMethodParameterNames() = paramNameToEVMMethodInstance.keys
    infix fun conflictsWith(other: MethodParameterInstantiation): Boolean = this.getMethodParameterNames().intersect(other.getMethodParameterNames()).let { bothInstantiate ->
        bothInstantiate.any { methodName -> this.getInstance(methodName) != other.getInstance(methodName) }
    }

    operator fun plus(other: MethodParameterInstantiation) =
        MethodParameterInstantiation(this.paramNameToEVMMethodInstance + other.paramNameToEVMMethodInstance)


    companion object {
        val Empty = MethodParameterInstantiation(mapOf())
    }

    /**
     * since currently ranges are a one-to-one relation, and for UX's sake,
     * we only try outputting a range here if all method parameters instantiated to the same method
     * (so in particular, we always try if exactly one method was instantiated)
     */
    fun range(): Range {
        val instances = this.values
        val sameSourceSegment = instances.sameValueOrNull()?.sourceSegment

        return if (sameSourceSegment != null) {
            sameSourceSegment.range
        } else {
            if (instances.size > 1) {
                val names = instances.map { it.name }
                logger.info { "Not outputting range for instantiation - not all method parameters agree (got methods: $names)" }
            }
            Range.Empty()
        }
    }
}
