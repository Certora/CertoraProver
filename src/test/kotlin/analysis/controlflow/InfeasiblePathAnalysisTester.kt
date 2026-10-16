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

package analysis.controlflow

import analysis.TACTypeChecker
import config.ReportTypes
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import tac.Tag
import testing.ttl.TACMockLanguage
import vc.data.CoreTACProgram
import vc.data.TACBuilderAuxiliaries
import vc.data.TACCmd
import vc.data.TACExpr
import vc.data.TACProgramBuilder
import vc.data.asTACExpr
import vc.data.TACSymbol
import verifier.CoreToCoreTransformer

class InfeasiblePathAnalysisTester : TACBuilderAuxiliaries() {

    private fun run(p: CoreTACProgram): CoreTACProgram {
        return CoreTACProgram.Linear(p).map(CoreToCoreTransformer(ReportTypes.OPTIMIZE_INFEASIBLE_PATHS) { c ->
            InfeasiblePaths.doInfeasibleBranchAnalysisAndPruning(c)
        }).ref
    }

    @Test
    fun test1() {
        val g = TACMockLanguage.make {
            L1023 = 0
            L(1024, Tag.Bool) `=` `*`
            `if`(L(1024,Tag.Bool)) {
                L1023 = 3
                // split here
                jump()
                L1021 = 3
                L(1022, Tag.Bool) `=` "!L1023 == L1021"
                assume(L(1022, Tag.Bool))
            } `else` {
                L1023 = 4
            }
        }
        val decls = setOf(
            TACSymbol.Var("L1024",Tag.Bool),
            TACSymbol.Var("L1023",Tag.Bit256),
            TACSymbol.Var("L1022",Tag.Bool),
            TACSymbol.Var("L1021",Tag.Bit256),
        )
        val p =
            CoreTACProgram.empty("").copy(code = g.code, blockgraph = g.blockSucc, symbolTable = g.symbolTable.mergeDecls(decls))
                .let { TACTypeChecker.checkProgram(it) }
        val pOptimized = run(p)
        Assertions.assertEquals(4, p.blockgraph.size)
        Assertions.assertEquals(1, pOptimized.blockgraph.size)
    }

    @Test
    fun test2() {
        val g = TACMockLanguage.make {
            L(1018,Tag.Bool) `=` `*`
            `if`(L(1018,Tag.Bool)) {
                L1023 = 2

                L(1024, Tag.Bool) `=` `*`
                `if`(L(1024, Tag.Bool)) {
                    L(1022, Tag.Bool) `=` `*`
                    `if`(L(1022, Tag.Bool)) {
                        L(1019) `=` 2
                        L(1020, Tag.Bool) `=` "!L1023 == L1019"
                        assume(L(1020, Tag.Bool))
                    } `else` {
                        L(1019) `=` 2
                        L(1020, Tag.Bool) `=` "!L1023 == L1019"
                        assume(L(1020, Tag.Bool))
                    }
                } `else` {
                    L1021 = 4
                }
            } `else` {
                L1021 = 5
            }
        }
        val decls = setOf(
            TACSymbol.Var("L1024",Tag.Bool),
            TACSymbol.Var("L1023",Tag.Bit256),
            TACSymbol.Var("L1022",Tag.Bool),
            TACSymbol.Var("L1021",Tag.Bit256),
            TACSymbol.Var("L1020",Tag.Bool),
            TACSymbol.Var("L1019",Tag.Bit256),
            TACSymbol.Var("L1018",Tag.Bool)
            )
        val p =
            CoreTACProgram.empty("").copy(code = g.code, blockgraph = g.blockSucc, symbolTable = g.symbolTable.mergeDecls(decls))
                .let { TACTypeChecker.checkProgram(it) }
        val pOptimized = run(p)
        Assertions.assertEquals(7, p.blockgraph.size)
        Assertions.assertEquals(3, pOptimized.blockgraph.size)
    }

    @Test
    fun test2b() {
        /*
        This variation on test2 should have no prunings, as the two assumes are *not* definitely false
         */
        val g = TACMockLanguage.make {
            L(1018,Tag.Bool) `=` `*`
            `if`(L(1018,Tag.Bool)) {
                L1023 = 2
                L(1018, Tag.Bool) `=` `*`
                `if`(L(1018, Tag.Bool)) {
                    L1023 = 4
                } `else` {
                    nop
                }

                L(1024, Tag.Bool) `=` `*`
                `if`(L(1024, Tag.Bool)) {
                    L(1022, Tag.Bool) `=` `*`
                    `if`(L(1022, Tag.Bool)) {
                        L(1019) `=` 2
                        L(1020, Tag.Bool) `=` "!L1023 == L1019"
                        assume(L(1020, Tag.Bool))
                    } `else` {
                        L(1019) `=` 2
                        L(1020, Tag.Bool) `=` "!L1023 == L1019"
                        assume(L(1020, Tag.Bool))
                    }
                } `else` {
                    L1021 = 4
                }
            } `else` {
                L1021 = 5
            }
        }
        val decls = setOf(
            TACSymbol.Var("L1024",Tag.Bool),
            TACSymbol.Var("L1023",Tag.Bit256),
            TACSymbol.Var("L1022",Tag.Bool),
            TACSymbol.Var("L1021",Tag.Bit256),
            TACSymbol.Var("L1020",Tag.Bool),
            TACSymbol.Var("L1019",Tag.Bit256),
            TACSymbol.Var("L1018",Tag.Bool)
        )
        val p =
            CoreTACProgram.empty("").copy(code = g.code, blockgraph = g.blockSucc, symbolTable = g.symbolTable.mergeDecls(decls))
                .let { TACTypeChecker.checkProgram(it) }
        val pOptimized = run(p)
        Assertions.assertEquals(p.blockgraph.size, pOptimized.blockgraph.size)
    }


    @Test
    fun test3() {
        val g = TACMockLanguage.make {
            L(1018,Tag.Bool) `=` `*`
            `if`(L(1018,Tag.Bool)) {
                L1023 = 2
                L(1022, Tag.Bool) `=` `*`
                `if`(L(1022, Tag.Bool)) {
                    L(1019) `=` 2
                    L(1020, Tag.Bool) `=` "!L1023 == L1019"
                    assume(L(1020, Tag.Bool))
                } `else` {
                    L(1019) `=` 2
                    L(1020, Tag.Bool) `=` "!L1023 == L1019"
                    assume(L(1020, Tag.Bool))
                }
            } `else` {
                L1021 = 5
            }
        }
        val decls = setOf(
            TACSymbol.Var("L1023",Tag.Bit256),
            TACSymbol.Var("L1022",Tag.Bool),
            TACSymbol.Var("L1021",Tag.Bit256),
            TACSymbol.Var("L1020",Tag.Bool),
            TACSymbol.Var("L1019",Tag.Bit256),
            TACSymbol.Var("L1018",Tag.Bool)
        )
        val p =
            CoreTACProgram.empty("").copy(code = g.code, blockgraph = g.blockSucc, symbolTable = g.symbolTable.mergeDecls(decls))
                .let { TACTypeChecker.checkProgram(it) }
        val pOptimized = run(p)
        Assertions.assertEquals(5, p.blockgraph.size)
        Assertions.assertEquals(1, pOptimized.blockgraph.size)
    }

    @Test
    fun test4() {
        val g = TACMockLanguage.make {
            L(1018,Tag.Bool) `=` `*`
            L(1017) `=` 1
            `if`(L(1018,Tag.Bool)) {
                L1023 = 2

                L(1024, Tag.Bool) `=` `*`
                `if`(L(1024, Tag.Bool)) {
                    L(1022, Tag.Bool) `=` `*`
                    `if`(L(1022, Tag.Bool)) {
                        L(1019) `=` 2
                        L(1020, Tag.Bool) `=` "!L1023 == L1019"
                        assume(L(1020, Tag.Bool))
                    } `else` {
                        L(1019) `=` 1
                        L(1020, Tag.Bool) `=` "!L1017 == L1019"
                        assume(L(1020, Tag.Bool))
                    }
                } `else` {
                    L1021 = 4
                }
            } `else` {
                L1021 = 5
            }
        }
        val decls = setOf(
            TACSymbol.Var("L1024",Tag.Bool),
            TACSymbol.Var("L1023",Tag.Bit256),
            TACSymbol.Var("L1022",Tag.Bool),
            TACSymbol.Var("L1021",Tag.Bit256),
            TACSymbol.Var("L1020",Tag.Bool),
            TACSymbol.Var("L1019",Tag.Bit256),
            TACSymbol.Var("L1018",Tag.Bool),
            TACSymbol.Var("L1017",Tag.Bit256)
        )
        val p =
            CoreTACProgram.empty("").copy(code = g.code, blockgraph = g.blockSucc, symbolTable = g.symbolTable.mergeDecls(decls))
                .let { TACTypeChecker.checkProgram(it) }
        val pOptimized = run(p)
        Assertions.assertEquals(7, p.blockgraph.size)
        Assertions.assertEquals(3, pOptimized.blockgraph.size)
    }

    @Test
    fun test5() {
        val g = TACMockLanguage.make {
            L(1) `=` 1
            L(2) `=` 2
            L(10,Tag.Bool) `=` `*`
            `if`(L(10,Tag.Bool)) {
                L(11, Tag.Bool) `=` `*`
                `if`(L(11, Tag.Bool)) {
                    L(3) `=` 10
                } `else` {
                    L(3) `=` 11
                }
            } `else` {
                L(3) `=` 12
            }
            L(4) `=` 7
            L(12,Tag.Bool) `=` `*`
            `if`(L(12,Tag.Bool)) {
                L(20) `=` 1
                L(21, Tag.Bool) `=` "!L1 == L20"
                assume(L(21, Tag.Bool))
            } `else` {
                L(20) `=` 2
                L(21, Tag.Bool) `=` "!L2 == L20"
                assume(L(21, Tag.Bool))
            }
        }
        val decls = setOf(
            TACSymbol.Var("L1",Tag.Bit256),
            TACSymbol.Var("L2",Tag.Bit256),
            TACSymbol.Var("L3",Tag.Bit256),
            TACSymbol.Var("L4",Tag.Bit256),
            TACSymbol.Var("L20",Tag.Bit256), // tmp var
            TACSymbol.Var("L10",Tag.Bool),
            TACSymbol.Var("L11",Tag.Bool),
            TACSymbol.Var("L12",Tag.Bool),
            TACSymbol.Var("L21",Tag.Bool) // tmp var
        )
        val p =
            CoreTACProgram.empty("").copy(code = g.code, blockgraph = g.blockSucc, symbolTable = g.symbolTable.mergeDecls(decls))
                .let { TACTypeChecker.checkProgram(it) }
        val pOptimized = run(p)
        Assertions.assertEquals(8, p.blockgraph.size)
        Assertions.assertEquals(1, pOptimized.blockgraph.size)
    }

    @Test
    fun test6() {
        val g = TACMockLanguage.make {
            L(1) `=` 1
            L(10,Tag.Bool) `=` `*`
            `if`(L(10,Tag.Bool)) {
                L(2) `=` 2
                L(11, Tag.Bool) `=` `*`
                `if`(L(11, Tag.Bool)) {
                    L(3) `=` 10
                } `else` {
                    L(3) `=` 11
                }
            } `else` {
                L(2) `=` 3
                L(3) `=` 12
            }
            L(4) `=` 7
            L(12,Tag.Bool) `=` `*`
            `if`(L(12,Tag.Bool)) {
                L(20) `=` 1
                L(21, Tag.Bool) `=` "!L1 == L20"
                assume(L(21, Tag.Bool))
            } `else` {
                L(20) `=` 2
                L(21, Tag.Bool) `=` "!L2 == L20"
                assume(L(21, Tag.Bool))
            }
        }
        val decls = setOf(
            TACSymbol.Var("L1",Tag.Bit256),
            TACSymbol.Var("L2",Tag.Bit256),
            TACSymbol.Var("L3",Tag.Bit256),
            TACSymbol.Var("L4",Tag.Bit256),
            TACSymbol.Var("L20",Tag.Bit256), // tmp var
            TACSymbol.Var("L10",Tag.Bool),
            TACSymbol.Var("L11",Tag.Bool),
            TACSymbol.Var("L12",Tag.Bool),
            TACSymbol.Var("L21",Tag.Bool) // tmp var
        )
        val p =
            CoreTACProgram.empty("").copy(code = g.code, blockgraph = g.blockSucc, symbolTable = g.symbolTable.mergeDecls(decls))
                .let { TACTypeChecker.checkProgram(it) }
        val pOptimized = run(p)
        Assertions.assertEquals(8, p.blockgraph.size)
        Assertions.assertEquals(6, pOptimized.blockgraph.size)
    }

    @Test
    fun test7() {
        val g = TACMockLanguage.make {
            L(11, Tag.Bool) `=` `*`
            `if`(L(11, Tag.Bool)) {
                L(3) `=` 2
            } `else` {
                L(3) `=` 3
            }
            L(20) `=` 2
            L(21, Tag.Bool) `=` "!L3 == L20"
            assume(L(21, Tag.Bool))
        }
        val decls = setOf(
            TACSymbol.Var("L3",Tag.Bit256),
            TACSymbol.Var("L20",Tag.Bit256), // tmp var
            TACSymbol.Var("L11",Tag.Bool),
            TACSymbol.Var("L21",Tag.Bool) // tmp var
        )
        val p =
            CoreTACProgram.empty("").copy(code = g.code, blockgraph = g.blockSucc, symbolTable = g.symbolTable.mergeDecls(decls))
                .let { TACTypeChecker.checkProgram(it) }
        val pOptimized = run(p)
        Assertions.assertEquals(4, p.blockgraph.size)
        Assertions.assertEquals(1, pOptimized.blockgraph.size)
    }

    @Test
    fun test8() {
        val g = TACMockLanguage.make {
            L(202) `=` 2 // L202 = 2
            L(11, Tag.Bool) `=` `*`
            `if`(L(11, Tag.Bool)) {
                L(3) `=` 4 // L3 = x
            } `else` {
                L(3) `=` 2
            }
            L(20) `=` 2 // L20 = z
            L(21, Tag.Bool) `=` "L20 == L202"
            `if`(L(21,Tag.Bool)) {
                L(12) `=` 4
                L(22, Tag.Bool) `=` "!L3 == L12"
                assume(L(22, Tag.Bool))
            } `else` { // bad graph otherwise
                nop
            }

        }
        val decls = setOf(
            TACSymbol.Var("L3",Tag.Bit256), // x
            TACSymbol.Var("L20",Tag.Bit256), // z
            TACSymbol.Var("L202",Tag.Bit256), // z constant 2
            TACSymbol.Var("L11",Tag.Bool),
            TACSymbol.Var("L12",Tag.Bit256), // tmp var
            TACSymbol.Var("L21",Tag.Bool), // tmp var
            TACSymbol.Var("L22",Tag.Bool) // tmp var
        )
        val p =
            CoreTACProgram.empty("").copy(code = g.code, blockgraph = g.blockSucc, symbolTable = g.symbolTable.mergeDecls(decls))
                .let { TACTypeChecker.checkProgram(it) }
        val pOptimized = run(p)
        Assertions.assertEquals(6, p.blockgraph.size)
        Assertions.assertEquals(6, pOptimized.blockgraph.size)
    }

    @Test
    fun test9() {
        val g = TACMockLanguage.make {
            L(11, Tag.Bool) `=` `*`
            `if`(L(11, Tag.Bool)) {
                L(3) `=` 4 // L3 = x
            } `else` {
                L(3) `=` 2
            }
            L(202) `=` 2 // L202 = 2
            L(20) `=` 2 // L20 = z
            L(21, Tag.Bool) `=` "L20 == L202" // will see 2 = 2 as a definition at the block's beginning, and infeasible path analysis wants a single free var
            `if`(L(21,Tag.Bool)) {
                L(12) `=` 4
                L(22, Tag.Bool) `=` "!L3 == L12"
                assume(L(22, Tag.Bool))
            } `else` { // bad graph otherwise
                nop
            }

        }
        val decls = setOf(
            TACSymbol.Var("L3",Tag.Bit256), // x
            TACSymbol.Var("L20",Tag.Bit256), // z
            TACSymbol.Var("L202",Tag.Bit256), // z constant 2
            TACSymbol.Var("L11",Tag.Bool),
            TACSymbol.Var("L12",Tag.Bit256), // tmp var
            TACSymbol.Var("L21",Tag.Bool), // tmp var
            TACSymbol.Var("L22",Tag.Bool) // tmp var
        )
        val p =
            CoreTACProgram.empty("").copy(code = g.code, blockgraph = g.blockSucc, symbolTable = g.symbolTable.mergeDecls(decls))
                .let { TACTypeChecker.checkProgram(it) }
        val pOptimized = run(p)
        Assertions.assertEquals(6, p.blockgraph.size)
        Assertions.assertEquals(6, pOptimized.blockgraph.size)
    }

    @Test
    fun test9b() {
        val g = TACMockLanguage.make {
            L(11, Tag.Bool) `=` `*`
            `if`(L(11, Tag.Bool)) {
                L(3) `=` 4 // L3 = x
            } `else` {
                L(3) `=` 2
            }
            L(202) `=` 2 // L202 = 2
            jump()
            L(20) `=` 2 // L20 = z
            L(21, Tag.Bool) `=` "L20 == L202" // will see 2 = 2 as a definition at the block's beginning, and infeasible path analysis wants a single free var
            `if`(L(21,Tag.Bool)) {
                L(12) `=` 4
                L(22, Tag.Bool) `=` "!L3 == L12"
                assume(L(22, Tag.Bool))
            } `else` { // bad graph otherwise
                nop
            }

        }
        val decls = setOf(
            TACSymbol.Var("L3",Tag.Bit256), // x
            TACSymbol.Var("L20",Tag.Bit256), // z
            TACSymbol.Var("L202",Tag.Bit256), // z constant 2
            TACSymbol.Var("L11",Tag.Bool),
            TACSymbol.Var("L12",Tag.Bit256), // tmp var
            TACSymbol.Var("L21",Tag.Bool), // tmp var
            TACSymbol.Var("L22",Tag.Bool) // tmp var
        )
        val p =
            CoreTACProgram.empty("").copy(code = g.code, blockgraph = g.blockSucc, symbolTable = g.symbolTable.mergeDecls(decls))
                .let { TACTypeChecker.checkProgram(it) }
        val pOptimized = run(p)
        Assertions.assertEquals(7, p.blockgraph.size)
        Assertions.assertEquals(6, pOptimized.blockgraph.size)
    }

    @Test
    fun test10() {
        val g = TACMockLanguage.make {
            L(11, Tag.Bool) `=` `*`
            `if`(L(11, Tag.Bool)) {
                L(3) `=` 4 // L3 = x
            } `else` {
                L(3) `=` 2
            }
            L(202) `=` 2 // L202 = 2
            jump()
            L(20) `=` 2 // L20 = z
            L(21, Tag.Bool) `=` "L20 == L202" // will see 2 = 2 as a definition at the block's beginning, and infeasible path analysis wants a single free var
            `if`(L(21,Tag.Bool)) {
                L(12) `=` 4
                L(22, Tag.Bool) `=` "!L3 == L12"
                assume(L(22, Tag.Bool))
            } `else` { // bad graph otherwise
                nop
            }

        }
        val decls = setOf(
            TACSymbol.Var("L3",Tag.Bit256), // x
            TACSymbol.Var("L20",Tag.Bit256), // z
            TACSymbol.Var("L202",Tag.Bit256), // z constant 2
            TACSymbol.Var("L11",Tag.Bool),
            TACSymbol.Var("L12",Tag.Bit256), // tmp var
            TACSymbol.Var("L21",Tag.Bool), // tmp var
            TACSymbol.Var("L22",Tag.Bool) // tmp var
        )
        val p =
            CoreTACProgram.empty("").copy(code = g.code, blockgraph = g.blockSucc, symbolTable = g.symbolTable.mergeDecls(decls))
                .let { TACTypeChecker.checkProgram(it) }
        val pOptimized = run(p)
        Assertions.assertEquals(7, p.blockgraph.size)
        Assertions.assertEquals(6, pOptimized.blockgraph.size)
    }

    @Test
    fun test11() {
        val g = TACMockLanguage.make {
            L(1024, Tag.Bool) `=` `*`
            `if`(L(1024, Tag.Bool)) {
                L1020 = 2
                L1023 = 2
            } `else` {
                L1020 = 4
                L1023 = 4
            }
            L(1024, Tag.Bool) `=` "L1023 == 0x2"
            `if`(L(1024, Tag.Bool)) {
                L(1022, Tag.Bool) `=` "!L1020 == 0x4"
                assume(L(1022, Tag.Bool))
            } `else` {
                nop
            }
        }
        val decls = setOf(
            TACSymbol.Var("L1020",Tag.Bit256),
            TACSymbol.Var("L1023",Tag.Bit256),
            TACSymbol.Var("L1024",Tag.Bool),
            TACSymbol.Var("L1022",Tag.Bool)
        )
        val p =
            CoreTACProgram.empty("").copy(code = g.code, blockgraph = g.blockSucc, symbolTable = g.symbolTable.mergeDecls(decls))
                .let { TACTypeChecker.checkProgram(it) }
        val pOptimized = run(p)
        Assertions.assertEquals(6, p.blockgraph.size)
        Assertions.assertEquals(6, pOptimized.blockgraph.size) // not sure we expect this
    }


    /**
     * The assume's condition goes through a builtin: `!(safe_math_narrow(i) == 5)`. Fixing `i` to the value an arm
     * assigns closes it, and simplifying it then folds the builtin: the arm assigning `5` cannot reach the assume and
     * is pruned, the one assigning `7` stays.
     */
    @Test
    fun builtinInTheConditionIsFolded() {
        val prog = TACProgramBuilder {
            havoc(x)
            jumpCond(x)
            jump {
                i assign 5
                jump(1)
            }
            jump {
                i assign 7
                jump(1)
            }
            jumpDest(1) {
                b assign safeMathNarrow(iS, Tag.Bit256)
                y assign Eq(bS, 5.asTACExpr)
                assumeNot(y)
                assert(y)
            }
        }
        val optimized = run(prog.code)
        val assigned = optimized.code.values.flatten()
            .filterIsInstance<TACCmd.Simple.AssigningCmd.AssignExpCmd>()
            .filter { it.lhs == i }
            .map { it.rhs }
        Assertions.assertEquals(listOf<TACExpr>(7.asTACExpr), assigned)
    }

    // currently commented out because we need to use a more stable TAC format.
    // see CER-1111
    /*
    @Test
    fun TestFromTacBin()
    {
        Config.PostProcessCounterExamples.set(ModelPostProcessingEnum.NONE)
        val filePath = this::class.java.classLoader.getResource("analysis/controlFlow/Infeasible.tacbin").path
        val p = CoreTACProgram.readBinary(filePath)
        val optimized = run(p)
        val scene = SceneFactory.getScene(DegenerateContractSource(filePath))
        val reachabilityFailureSourceFinder = ReachabilityFailureSourceFinder(optimized, scene)
        Assertions.assertEquals(
                reachabilityFailureSourceFinder.runReachabilityFailureSourceFinder(true),
                ReachabilityIndicator.NoUnreachability,
                "Optimized code has an unreachable point")
    }
     */
}
