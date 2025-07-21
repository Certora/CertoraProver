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

package analysis

import analysis.storage.StorageAnalysis.Base
import analysis.storage.StorageAnalysis.AnalysisPath
import analysis.storage.StorageTree
import analysis.storage.StorageTree.Type
import annotations.TestTags
import compiler.applyKeccak
import compiler.applyKeccakList
import datastructures.stdcollections.*
import instrumentation.StoragePathAnnotation
import net.jqwik.api.*
import net.jqwik.kotlin.api.*
import org.junit.jupiter.api.Assertions.*
import vc.data.*
import java.math.BigInteger

@Tag(TestTags.EXPENSIVE)
class StoragePathAnnotationTest {
    @Property
    fun testAccessPathIndexComputation(
        @ForAll("arbitraryTestCase") t: Pair<Set<StorageTree.Root>, Triple<AnalysisPath, AnalysisPath, BigInteger>>
    ) {
        val (tree, path) = t
        runCheckFixIndices(tree, path.first, path.second, path.third)
    }

    private fun runCheckFixIndices(
        tree: Set<StorageTree.Root>,
        path: AnalysisPath,
        nulledPath: AnalysisPath,
        slot: BigInteger,
    ) {

        val newVars = mutableSetOf<TACSymbol.Var>()

        val (newCmds, indexMap) = StoragePathAnnotation.fixupArrayIndices(
            slot.asTACSymbol(),
            nulledPath,
            newVars,
            tree
        )

        val state = mutableMapOf<TACSymbol.Var, BigInteger>()
        newCmds.forEach {
            it.interpret(state)
        }

        val evalMap = indexMap.mapValues {(_, idx) ->
            val idxVal = state[idx]
            assertTrue(idxVal != null)
            idxVal!!
        }

        assertTrue(checkPaths(path, nulledPath, evalMap)) {
            val fixed = StoragePathAnnotation.substituteArrayIndices(nulledPath, indexMap)
            val stateStrs = state.map { (x, v) -> "  $x |-> $v" }.joinToString(separator = "\n")
            "Paths not equal?\nReference=${path}\nWithVars=${fixed}\n" + newCmds.joinToString(separator = ";\n") + "\nFinal State=\n$stateStrs"
        }
    }

    private fun checkPaths(p1: AnalysisPath, p2: AnalysisPath, indexMap: Map<AnalysisPath, BigInteger>): Boolean {
        return when {
            p1 is AnalysisPath.ArrayLikePath && p2 is AnalysisPath.ArrayLikePath && p1::class == p2::class -> {
                val v1 = (p1.index as TACSymbol.Const).value
                val v2 = (p2.index as? TACSymbol.Const)?.value ?: indexMap[p2]
                (v1 == v2) && checkPaths(p1.base, p2.base, indexMap)
            }

            p1 is AnalysisPath.StructAccess && p2 is AnalysisPath.StructAccess -> {
                (p1.offset == p2.offset) && checkPaths(p1.base, p2.base, indexMap)
            }

            p1 is AnalysisPath.MapAccess && p2 is AnalysisPath.MapAccess -> {
                p1.key == p2.key &&
                p1.baseSlot == p2.baseSlot &&
                p1.hashResult == p2.hashResult &&
                checkPaths(p1.base, p2.base, indexMap)
            }


            p1 is AnalysisPath.Root && p2 is AnalysisPath.Root -> {
                p1.slot == p2.slot
            }

            else -> false
        }
    }

    private fun TACExpr.eval(state: Map<TACSymbol.Var, BigInteger>): BigInteger? {
        return when(this) {
            is TACExpr.BinOp.Div -> this.o1.eval(state)!!.div(this.o2.eval(state)!!)
            is TACExpr.BinOp.Sub -> this.o1.eval(state)!! - this.o2.eval(state)!!
            is TACExpr.BinOp.IntSub -> this.o1.eval(state)!! - this.o2.eval(state)!!
            is TACExpr.BinOp.Mod -> this.o1.eval(state)!!.mod(this.o2.eval(state)!!)
            is TACExpr.Sym.Var -> {
                assertTrue(this.s in state)
                state[this.s]!!
            }
            is TACExpr.Sym.Const -> this.evalAsConst()
            is TACExpr.Apply ->
                (f as? TACExpr.TACFunctionSym.BuiltIn)?.let {
                    if (it.bif is TACBuiltInFunction.SafeMathNarrow) {
                        this.ops.singleOrNull()?.eval(state)
                    } else {
                        error("Unexpected function application $this")
                    }
                }
            else ->
                error("Unexpected expression form $this")
        }
    }

    private fun TACCmd.Simple.interpret(state: MutableMap<TACSymbol.Var, BigInteger>) {
        when (this) {
            is TACCmd.Simple.AssigningCmd.AssignExpCmd -> {
                state[this.lhs] = this.rhs.eval(state)!!
            }

            is TACCmd.Simple.AssigningCmd.AssignSimpleSha3Cmd -> {
                val argvs = this.args.map { it.asSym().eval(state)!! }
                state[this.lhs] = applyKeccakList(argvs)
            }

            else ->
                error("Unexpected command $this")
        }
    }

    @Provide
    fun arbitraryTestCase(): Arbitrary<Pair<Set<StorageTree.Root>, Triple<AnalysisPath, AnalysisPath, BigInteger>>> {
       return Int.any(0..10).flatMap { root ->
            arbitraryStorageTreeType().flatMap { type ->
                val rootTree = StorageTree.Root(
                    root.toBigInteger(),
                    Base.STORAGE,
                    if (type is Type.Struct) {
                        type.elements.values.single()
                    } else {
                        type
                    }
                )
                arbitraryPathFromRoot(rootTree).flatMap { (path, slot) ->
                    partiallyNullify(path).map { partiallyNull ->
                        Pair(setOf(rootTree), Triple(path, partiallyNull, slot))
                    }
                }
            }
        }
    }

    private fun partiallyNullify(path: AnalysisPath): Arbitrary<AnalysisPath> {
        return when (path) {
            is AnalysisPath.Root ->
                Arbitraries.of(path)
            is AnalysisPath.ArrayAccess ->
                partiallyNullify(path.base).flatMap { nullBase ->
                    Arbitraries.of(
                        path.copy(base = nullBase),
                        path.copy(base = nullBase, index = null),
                    )
                }
            is AnalysisPath.StaticArrayAccess ->
                partiallyNullify(path.base).flatMap { nullBase ->
                    Arbitraries.of(
                        path.copy(base = nullBase),
                        path.copy(base = nullBase, index = null),
                    )
                }
            is AnalysisPath.MapAccess ->
                partiallyNullify(path.base).map { nullBase ->
                    path.copy(base = nullBase)
                }
            is AnalysisPath.StructAccess ->
                partiallyNullify(path.base).map { nullBase ->
                    path.copy(base = nullBase)
                }
            is AnalysisPath.WordOffset ->
                partiallyNullify(path.parent).map { nullBase ->
                    path.copy(parent = nullBase)
                }
        }
    }

    // OK technically the path is already determined by the shape of [root],
    // we're just going to choose arbitrary indices
    private fun arbitraryPathFromRoot(root: StorageTree.Root): Arbitrary<Pair<AnalysisPath, BigInteger>> {
        fun walk(t: Type, p: AnalysisPath, baseSlot: BigInteger): Arbitrary<Pair<AnalysisPath, BigInteger>> {
            return when(t) {
                is Type.Word ->
                    Arbitraries.of(p to baseSlot)

                is Type.Struct ->
                    walk(t.elements.values.single(), AnalysisPath.StructAccess(p, t.elements.keys.single()), baseSlot + t.elements.keys.single())

                is Type.Array ->
                    Int.any(1..3).flatMap { idx ->
                        walk(
                            t.element,
                            AnalysisPath.ArrayAccess(p, idx.asTACSymbol(), baseSlot.asTACSymbol()),
                            applyKeccak(baseSlot) + (t.elementSize*idx.toBigInteger())
                        )
                    }

                is Type.StaticArray ->
                    Int.any(0 until t.numElements.toInt()).flatMap { idx ->
                        walk(t.element, AnalysisPath.StaticArrayAccess(p, idx.asTACSymbol()), baseSlot + (idx.toBigInteger()*t.elementSize))
                    }

                is Type.Mapping -> {
                    Int.any(0..5).flatMap { key ->
                        walk(
                            t.codomain,
                            AnalysisPath.MapAccess(base = p, key = key.asTACSymbol(), hashResult = applyKeccak(key.toBigInteger(), baseSlot).asTACSymbol(), baseSlot = baseSlot.asTACSymbol()),
                            applyKeccak(key.toBigInteger(), baseSlot)
                        )
                    }
                }

                Type.Bottom -> throw UnsupportedOperationException("Type.Bottom in storage tree")
            }
        }
        return walk(root.types, AnalysisPath.Root(root.slot, Base.STORAGE), root.slot)
    }

    private fun arbitraryStorageTreeType(): Arbitrary<Type> {
        fun ensureStruct(t: Type): Type =
            if (t is Type.Struct) {
                t
            } else {
                Type.Struct(mapOf(BigInteger.ZERO to t))
            }

        return Int.any(1..5).flatMap { depth ->
            Arbitraries.recursive(
                { Arbitraries.of(Type.Word) },

                {
                    it.flatMap { t ->
                        val options = listOf(
                            Arbitraries.of(Type.Array(ensureStruct(t), size(t))),

                            Arbitraries.of(Type.Mapping(ensureStruct(t))),

                            Int.any(1..5).map { size ->
                                Type.StaticArray(size.toBigInteger(), size(t), ensureStruct(t))
                            }
                        ) + if (t !is Type.Struct) {
                            listOf(
                                Int.any(0..5).map { offset ->
                                    Type.Struct(mapOf(offset.toBigInteger() to t))
                                }
                            )
                        } else {
                            listOf()
                        }

                        Arbitraries.oneOf(options)
                    }
                },

                depth
            )
        }
    }

    private fun size(t: Type): BigInteger {
        return when (t) {
            is Type.Array -> 1.toBigInteger()
            is Type.Mapping -> 1.toBigInteger()
            is Type.Struct -> t.elements.entries.single().let { it.key + size(it.value) }
            is Type.StaticArray -> t.numElements * size(t.element)
            Type.Word -> 1.toBigInteger()
            Type.Bottom -> throw UnsupportedOperationException("size(Type.Bottom)")
        }
    }
}
