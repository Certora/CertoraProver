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
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package wasm.analysis

import datastructures.stdcollections.*
import analysis.PatternDSL
import analysis.PatternMatcher
import analysis.maybeNarrow
import tac.MetaKey
import tac.Tag
import utils.mapNotNull
import utils.`to?`
import vc.data.CoreTACProgram
import vc.data.TACCmd
import vc.data.TACSymbol
import java.math.BigInteger
import java.util.stream.Collectors

val WASM_STACK_DEC = MetaKey<BigInteger>("wasm.stack.dec")
val WASM_STACK_INC = MetaKey<BigInteger>("wasm.stack.inc")

/**
 * Annotate the program with stack pointer changes (dec and inc): as variables get rewritten,
 * it is not apparent which "RN" is the stack pointer.
 */
class WASMStackFrame {
    private val twoPow32: BigInteger = BigInteger.TWO.pow(32)
    private val stackPointer = TACSymbol.Var("global_\$__stack_pointer", Tag.Bit256)

    // match (stackPointer - k) % 2**32 ==> returns -k
    private val dec = PatternDSL.build {
        ((!stackPointer - Const).withAction { _, k -> k } mod twoPow32()).withAction { _, amt, _ -> amt.negate() }
    }

    // match (stackPointer + k) % 2**32 ==> returns k
    private val inc = PatternDSL.build {
        ((Var.withLocation + Const).commute.withAction { x, k -> x to k } mod twoPow32()).withAction { _, (x, amt), _ -> x to amt }
    }


    /**
     * Annotate stack push/pops in [ctp]
     */
    fun annotate(ctp: CoreTACProgram): CoreTACProgram {
        //    we have e.g.
        //    tmp_pc_67_0 = global_$__stack_pointer
        //    12: tacTmp!t49!50 = tmp_pc_67_0-0x20
        //    13: tmp_pc_65_0 = tacTmp!t49!50%2^32
        //    14: local_0_0 = tmp_pc_65_0
        //    15: global_$__stack_pointer = tmp_pc_65_0
        val g = ctp.analysisCache.graph
        val matchDec = PatternMatcher.compilePattern(g, dec)
        val matchInc = PatternMatcher.compilePattern(g, inc)

        // All stack decrements (i.e., "pushes")
        val decs = ctp.parallelLtacStream().mapNotNull {
            it.maybeNarrow<TACCmd.Simple.AssigningCmd.AssignExpCmd>()?.takeIf {
                it.cmd.lhs == stackPointer
            }
        }.mapNotNull {
            it.ptr `to?` matchDec.queryFrom(it).toNullableResult()
        }.collect(Collectors.toMap({ it.first }, { it.second }))

        // All stack increments (i.e., "pops")
        // such that if we have an increment of k at point L
        // then the previous definition (note, singular) of the stack pointer was a decrement of k
        val incs = ctp.parallelLtacStream().mapNotNull {
            it.maybeNarrow<TACCmd.Simple.AssigningCmd.AssignExpCmd>()?.takeIf {
                it.cmd.lhs == stackPointer && it.ptr !in decs
            }
        }.mapNotNull {
            it `to?` matchInc.queryFrom(it).toNullableResult()
        }.mapNotNull { (inc, incData) ->
            val (xdata, amt) = incData
            val (where, x) = xdata
            matchDec.query(x, g.elab(where)).toNullableResult()?.let {
                inc to amt
            }
        }.collect(Collectors.toMap({it.first},{it.second}))


        return ctp.patching {
            // Dump in the annotations
            for ((where, amt) in incs) {
                it.insertAfter(where.ptr, listOf(TACCmd.Simple.AnnotationCmd(TACCmd.Simple.AnnotationCmd.Annotation(WASM_STACK_INC, amt))))
            }
            for ((where, amt) in decs) {
                it.insertAfter(where, listOf(TACCmd.Simple.AnnotationCmd(TACCmd.Simple.AnnotationCmd.Annotation(WASM_STACK_DEC, amt))))
            }
        }
    }
}
