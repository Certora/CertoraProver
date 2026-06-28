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

package instrumentation

import analysis.ip.INTERNAL_FUNC_EXIT
import analysis.ip.INTERNAL_FUNC_START
import analysis.ip.InternalFuncExitAnnotation
import analysis.ip.InternalFuncStartAnnotation
import analysis.maybeAnnotation
import analysis.worklist.SimpleWorklist
import com.certora.collect.*
import datastructures.PersistentStack
import datastructures.persistentStackOf
import datastructures.topOrNull
import infra.MiniTACTestMixin
import instrumentation.transformers.InternalReturnFixup
import kotlinx.serialization.builtins.serializer
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import spec.cvlast.QualifiedFunction
import spec.cvlast.QualifiedMethodSignature
import spec.cvlast.SolidityContract
import tac.NBId
import vc.data.CoreTACProgram
import vc.data.TACCmd
import vc.data.minitac.MiniTACCompiler

class InternalStackNormalizerTest : MiniTACTestMixin {
    private val dummyMethod = QualifiedMethodSignature.QualifiedMethodSig(
        res = listOf(),
        params = listOf(),
        qualifiedMethodName = QualifiedFunction(
            SolidityContract("Test"),
            "test"
        )
    )

    private fun getStartAnnotation(id: Int) = InternalFuncStartAnnotation(
        id = id,
        args = listOf(),
        methodSignature = dummyMethod,
        callSiteSrc = null,
        calleeSrc = null,
        startPc = 10
    )

    private fun getEndAnnotation(id: Int) = InternalFuncExitAnnotation(
        id = id,
        methodSignature = dummyMethod,
        rets = listOf()
    )

    private fun startAndEndsAreWellMatched(c: CoreTACProgram) : Boolean {
        val blockState = treapMapBuilderOf<NBId, PersistentStack<Int>>()
        val g = c.analysisCache.graph
        g.rootBlockIds.forEach {
            blockState[it] = persistentStackOf()
        }
        var error = false
        SimpleWorklist.iterateWorklist(g.rootBlockIds) { curr, nxt ->
            if(error) {
                return@iterateWorklist
            }
            var st = blockState[curr]!!
            val cmds = g.elab(curr).commands
            for(lc in cmds) {
                val enter = lc.maybeAnnotation(INTERNAL_FUNC_START)
                val exit = lc.maybeAnnotation(INTERNAL_FUNC_EXIT)

                if(enter != null) {
                    st = st.push(enter.id)
                } else if(exit != null) {
                    if(exit.id == st.topOrNull()) {
                        st = st.pop()
                    } else {
                        error = true
                        return@iterateWorklist
                    }
                }
            }
            val succ = g.succ(curr)
            if(succ.isEmpty()) {
                if(!st.isEmpty()) {
                    error = true
                }
            } else {
                for(s in succ) {
                    val succState = blockState[s]
                    if(succState == null) {
                        blockState[s] = st
                        nxt.add(s)
                    } else if(succState != st) {
                        error = true
                        return@iterateWorklist
                    }
                }
            }
        }
        return !error
    }

    override val handlers: Map<String, MiniTACCompiler.PluginHandler>
        get() = mapOf(
            MiniTACCompiler.handler("enter", Int.serializer()) { i, _ ->
                listOf(TACCmd.Simple.AnnotationCmd(
                    INTERNAL_FUNC_START, getStartAnnotation(i)
                ))
            },
            MiniTACCompiler.handler("exit", Int.serializer()) { i, _ ->
                listOf(TACCmd.Simple.AnnotationCmd(
                    INTERNAL_FUNC_EXIT, getEndAnnotation(i)
                ))
            }
        )

    private fun runTest(src: String) = this.loadMiniTAC(src).let(InternalReturnFixup::transform).let {
        Assertions.assertTrue(startAndEndsAreWellMatched(it))
    }

    @Test
    fun basicSharing() {
        runTest("""
            if(*) {
               embed #1# "enter";
               if(*) {
                  embed #1# "exit";
                  goto done;
               } else {
                  goto shared_exit;
               }
            } else {
               embed #2# "enter";
               if(*) {
                 embed #2# "exit";
                 goto done;
               } else {
                 goto shared_exit;
               }
            }
            shared_exit:
              embed #1# "exit";
              embed #2# "exit";
            done:
              x = 1;
        """.trimIndent())
    }

    @Test
    fun nestedSharing() {
        runTest("""
            if(*) {
               embed #1# "enter";
            } else {
               if(*) {
                  embed #2# "enter";
               } else {
                  embed #3# "enter";
               }
            }
            if(*) {
               x = 3;
            } else {
               x = 4;
            }
            if (Lt(x, 4)) {
               embed #3# "exit";
               embed #2# "exit";
               embed #1# "exit";
            } else {
               embed #1# "exit";
               embed #2# "exit";
               embed #3# "exit";
            }
        """.trimIndent())
    }

    private fun runNegativeTest(src: String) = loadMiniTAC(src).let(InternalReturnFixup::transform).let {
        Assertions.assertFalse(startAndEndsAreWellMatched(it))
    }

    /*
       This doesn't work because there is not an unconditional popping of all of the stacks grouped together
     */
    @Test
    fun negativeTest() {
        runNegativeTest("""
            if(*) {
                embed #1# "enter";
                x = 1;
            } else {
                embed #2# "enter";
                x = 2;
            }
            if (Lt(x, 2)) {
               embed #1# "exit";
            } else {
               embed #2# "exit";
            }
        """.trimIndent())
    }
}
