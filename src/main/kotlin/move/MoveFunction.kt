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

package move

import datastructures.stdcollections.*
import java.util.Objects
import utils.*

/**
    A fully instantiated Move function, with all type parameters resolved to concrete types.
 */
class MoveFunction private constructor(
    val name: MoveFunctionName,
    val typeArguments: List<MoveType.Value>,
    val params: List<MoveType>,
    val returns: List<MoveType>,
    private val definition: MoveModule.FunctionDefinition,
    private val scene: MoveScene
) {
    override fun toString() = when {
        typeArguments.isEmpty() -> "$name"
        else -> "$name<${typeArguments.joinToString(", ")}>"
    }
    override fun hashCode() = Objects.hash(name, typeArguments, params, returns)
    override fun equals(other: Any?) = when {
        other === this -> true
        other !is MoveFunction -> false
        other.name != name -> false
        other.typeArguments != typeArguments -> false
        other.params != params -> false
        other.returns != returns -> false
        else -> true
    }

    data class Code(
        val locals: List<MoveType>,
        val instructions: List<MoveInstruction>,
        private val sourceMap: Map<Int, MoveSourceMap.Location>?
    ) {
        private val branchTargets by lazy { instructions.mapNotNullToSet { it.branchTarget } }

        /** Gets the sequence of instructions for the basic block starting at [start]. */
        fun block(start: Int): Sequence<Triple<Int, MoveInstruction, MoveSourceMap.Location?>> = sequence {
            var offs = start
            while(true) {
                check(offs < instructions.size) {
                    "Reached end of instructions ${instructions.size} while parsing block at $start"
                }
                val instruction = instructions[offs]
                yield(Triple(offs, instruction, sourceMap?.get(offs)))
                when (instruction) {
                    is MoveInstruction.BrTrue,
                    is MoveInstruction.BrFalse,
                    is MoveInstruction.Branch,
                    is MoveInstruction.Call,
                    is MoveInstruction.Ret,
                    is MoveInstruction.Abort -> break
                    else -> {
                        ++offs
                        if (offs in branchTargets) {
                            break
                        }
                    }
                }
            }
        }
    }

    val code by lazy {
        with(scene) {
            definition.code?.let { code ->
                Code(
                    locals = code.locals.map { it.toMoveType(typeArguments) },
                    instructions = code.instructions.map { it.toMoveInstruction(typeArguments) },
                    sourceMap = sourceContext[definition]?.codeMap
                )
            }
        }
    }

    companion object {
        context(MoveScene)
        operator fun invoke(
            func: MoveModule.FunctionHandle,
            typeArguments: List<MoveType.Value> = listOf()
        ): MoveFunction {
            check(func.typeParameters.size == typeArguments.size) {
                "Function ${func.qualifiedName} expects ${func.typeParameters.size} type parameters, but got ${typeArguments.size}"
            }
            return MoveFunction(
                name = func.name,
                typeArguments = typeArguments,
                params = func.params.map { it.toMoveType(typeArguments) },
                returns = func.returns.map { it.toMoveType(typeArguments) },
                definition = definition(func),
                scene = this@MoveScene
            )
        }
    }
}
