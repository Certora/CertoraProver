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

package analysis.opt

import algorithms.dominates
import allocator.Allocator
import analysis.CmdPointer
import analysis.LTACCmd
import analysis.PatternMatcher
import datastructures.stdcollections.*
import tac.Tag
import utils.*
import vc.data.*
import java.util.stream.Collector
import java.util.stream.Stream

/**
 * Given variable/location pairs (represented by [T], a subtype of [ReplacementCandidate])
 * replace uses of the variables after said location with other aliases of the free pointer,
 * or a fresh read. The reason for doing this is to avoid scratch pointer/free pointer reuse
 * in a way that confuses the allocation analysis.
 */
interface FreePointerReadFixupMixin<T: FreePointerReadFixupMixin.ReplacementCandidate> {
    /**
     * [fpAlias] is variable that is an alias of the free pointer after [rewriteUseAfter];
     * uses of [fpAlias] *after* [rewriteUseAfter] should
     * be replaced with fresh reads or other aliases of the free pointer.
     */
    interface ReplacementCandidate {
        val fpAlias: TACSymbol.Var
        val rewriteUseAfter: CmdPointer
    }

    private class RewriteAggregator(
        val newRead: MutableMap<CmdPointer, TACSymbol.Var> = mutableMapOf(),
        val rewrites: MutableMap<CmdPointer, MutableMap<TACSymbol.Var, TACSymbol.Var>> = mutableMapOf()
    )


    /**
     * [requireLiveAlias] means we require that the alias of the free pointer must be live;
     * if it isn't live we generate a fresh read (or there is no such alias).
     */
    fun Stream<T>.doRewrite(p: CoreTACProgram, requireLiveAlias: Boolean = false) : CoreTACProgram {
        val dom by lazy {
            p.analysisCache.domination
        }
        val gvn = p.analysisCache.gvn
        infix fun CmdPointer.dominates(x: CmdPointer) = dom.dominates(this, x)

        val rewrite = this.map {
            it to p.analysisCache.use.useSitesAfter(pointer = it.rewriteUseAfter, v = it.fpAlias).filter { u ->
                u != it.rewriteUseAfter
            }
        }.flatMap { (exp, useSites) ->
            // is the value in these use sites definitely observing the value being written at exp
            val dominatedUseSites = useSites.filter {
                exp.rewriteUseAfter dominates it
            }
            if(dominatedUseSites.isEmpty()) {
                Stream.empty()
            } else {
                Stream.of(exp to dominatedUseSites)
            }
        }.map { (write, rewriteLocs) ->
            /**
             * The variable/location pair of the write to the FP that is being reused. Call
             * the value being reused `v`.
             */
            val newValSource = write.rewriteUseAfter to write.fpAlias
            val src = write.fpAlias
            var freshName : TACSymbol.Var? = null
            /**
             * Determine if a known alias of the FP is defined by a read of the free pointer
             * vs copying `v` we are trying to replace.
             */
            val isFreshReadAlias = PatternMatcher.Pattern.FromVar(
                extractor = m@{ where: LTACCmd, v: TACSymbol.Var ->
                    if(v == TACKeyword.MEM64.toVar()) {
                        PatternMatcher.VariableMatch.Match(Unit)
                    } else if(v == write.fpAlias && v in gvn.findCopiesAt(where.ptr, source = newValSource)) {
                        PatternMatcher.VariableMatch.NoMatch
                    } else {
                        PatternMatcher.VariableMatch.Continue
                    }
                }
            ).let {
                PatternMatcher.compilePattern(graph = p.analysisCache.graph, it)
            }

            /**
             * Rewrite the use sites, using extant aliases if possible
             */
            val subst = rewriteLocs.map { useSite ->
                /**
                 * Is there another alias of `v` (that isn't the free pointer itself) and which was copied from the
                 * free pointer? If so, just replace `v` with that variable.
                 */
                val otherAlias = gvn.findCopiesAt(useSite, source = write.rewriteUseAfter to src).firstOrNull { alias ->
                    alias != src && isFreshReadAlias.query(alias, src = p.analysisCache.graph.elab(useSite)).toNullableResult() != null && alias != TACKeyword.MEM64.toVar() &&
                        (!requireLiveAlias || p.analysisCache.lva.isLiveBefore(
                            useSite, alias
                        ))
                }
                if(otherAlias != null) {
                    useSite to (src to otherAlias)
                } else {
                    /**
                     * Otherwise, generate a fresh read to insert at this write point.
                     */
                    if(freshName == null) {
                        val rewriteId = Allocator.getFreshId(Allocator.Id.TEMP_VARIABLE)
                        freshName = TACSymbol.Var("freshRead$rewriteId", Tag.Bit256)
                    }
                    useSite to (src to freshName!!)
                }
            }
            (write.rewriteUseAfter `to?` freshName) to subst
        }.collect(Collector.of({ ->
            RewriteAggregator()
        }, { u: RewriteAggregator, t: Pair<Pair<CmdPointer, TACSymbol.Var>?, List<Pair<CmdPointer, Pair<TACSymbol.Var, TACSymbol.Var>>>> ->
            val (readInst, useInst) = t
            if(readInst != null) {
                u.newRead[readInst.first] = readInst.second
            }
            useInst.forEach {(where, subst) ->
                u.rewrites.computeIfAbsent(where) { mutableMapOf() }.put(subst.first, subst.second)
            }
        }, { h1: RewriteAggregator, h2: RewriteAggregator ->
            h2.newRead.forEachEntry { (t, u) ->
                h1.newRead[t] = u
            }
            h2.rewrites.forEachEntry { (where, subst) ->
                h1.rewrites.computeIfAbsent(where) { mutableMapOf() }.putAll(subst)
            }
            h1
        }))

        return p.patching { patch ->
            rewrite.newRead.forEachEntry { (where, newVar) ->
                patch.replace(where) { orig ->
                    listOf(
                        orig,
                        TACCmd.Simple.AssigningCmd.AssignExpCmd(
                            lhs = newVar,
                            rhs = TACKeyword.MEM64.toVar().asSym()
                        )
                    )
                }
                patch.addVarDecl(newVar)
            }
            rewrite.rewrites.forEachEntry { (where, subst) ->
                patch.replace(where) { c ->
                    listOf(TACVariableSubstitutor(subst).map(c))
                }
            }
        }
    }
}
