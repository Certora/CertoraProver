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

package instrumentation.transformers

import algorithms.dominates
import analysis.PatternDSL
import analysis.PatternMatcher
import analysis.alloc.AllocationAnalysis.roundUp
import analysis.maybeExpr
import analysis.maybeNarrow
import utils.*
import vc.data.SimplePatchingProgram.Companion.patchForEach
import datastructures.stdcollections.*
import vc.data.*

/**
 *
 */
object SpuriousFreePointerUpdateRemoval {
    private val updatePattern = PatternDSL.build {
        (roundUp { 0() } + Var { v, where ->
            if(v == TACKeyword.MEM64.toVar()) {
                PatternMatcher.VariableMatch.Match(where)
            } else {
                PatternMatcher.VariableMatch.Continue
            }
        }).commute.second
    }

    fun transformAddition(c: CoreTACProgram) : CoreTACProgram {
        val matcher = PatternMatcher.compilePattern(graph = c.analysisCache.graph, patt = updatePattern)
        val dom = c.analysisCache.domination
        val gvn = c.analysisCache.gvn
        // find all MEM64 assignments
        return c.parallelLtacStream().mapNotNull {
            it.maybeNarrow<TACCmd.Simple.AssigningCmd>()?.takeIf {
                it.cmd.lhs == TACKeyword.MEM64.toVar()
            }
        }.mapNotNull {
            it `to?` matcher.queryFrom(it).toNullableResult()
        }.filter { (assignLoc, readLoc) ->
            dom.dominates(readLoc.ptr, assignLoc.ptr) && TACKeyword.MEM64.toVar() in gvn.findCopiesAt(assignLoc.ptr, readLoc.ptr to TACKeyword.MEM64.toVar())
        }.patchForEach(c) { (toRemove, _) ->
            replaceCommand(toRemove.ptr, listOf(TACCmd.Simple.NopCmd))
        }
    }

    fun transformIdentity(c: CoreTACProgram) : CoreTACProgram {
        val gvn = c.analysisCache.gvn
        return c.parallelLtacStream().mapNotNull {
            it.maybeExpr<TACExpr.Sym.Var>()
        }.filter {
            it.lhs == TACKeyword.MEM64.toVar()
        }.filter {
            TACKeyword.MEM64.toVar() in gvn.equivBefore(it.ptr, it.exp.s)
        }.map { e ->
            { p: SimplePatchingProgram ->
                p.replaceCommand(e.ptr, listOf(TACCmd.Simple.NopCmd))
            }
        }.patchForEach(c) { thunk ->
            thunk(this)
        }
    }
}
