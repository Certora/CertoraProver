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
import analysis.*
import analysis.dataflow.VariableLookupComputation
import evm.EVM_WORD_SIZE
import utils.`to?`
import utils.mapNotNull
import vc.data.*
import java.math.BigInteger
import java.util.stream.Collectors
import datastructures.stdcollections.*

object InitializationPointerShift {
    private data class AddKtoFP(
        val addLoc: LTACCmd,
        val readLoc: CmdPointer,
        val const: BigInteger
    )

    private data class Instrument(
        val replaceRHS: LTACCmdView<TACCmd.Simple.AssigningCmd>,
        val holder: TACSymbol.Var,
        val k: BigInteger
    )

    /**
     * An initialization write of the form:
     * x + k
     * where x is an alias of the free pointer
     */
    private val singlePattern = PatternDSL.build {
        (TACKeyword.MEM64.toVar().withLocation + Const).commute.withAction { addLoc, readLoc, const ->
            AddKtoFP(
                readLoc = readLoc,
                addLoc = addLoc,
                const = const
            )
        }
    }


    /**
     * Analyze this command to see if we should shift it up. The command ([matchLoc]) is some addition of a
     * constant k to some variable `v` (either the free pointer or some other variable yielded by iteratively adding 0x20).
     * In either case, we're calculating a field pointer `f` w.r.t. some base pointer B (which is an alias of the free pointer
     * at some point in time).
     *
     * We use the following heuristic to see if we should shift up [matchLoc]:
     * Find the definition site of `v`, call it D. If there exists a read R of the free pointer along the path
     * from D to [matchLoc] that is *likely* not equal to B (the base pointer of the field we're computing)
     * we try to shift [matchLoc] to before that FP read.
     *
     * Within this heuristic there is another heuristic: how do we know if the FP read is not likely equal to B?
     * At the definition site D, we see if `v` is equal to the free pointer. If so, then expect to see some
     * write to the free pointer before R: R necessarily will alias the free pointer, and is
     * thus likely to be part of the same allocation. If not, any read of the free pointer is considered unlikely
     * to be equal to B: in practice we see Solidity generate these strings of additions only after updating the free pointer.
     *
     * If we decide we have a shift candidate we then check the following:
     * 1. There is exists some alias of `v` immediately before R
     * 2. All uses of `f` visible from R are dominated by [matchLoc] (so we aren't changing previous iterations)
     *
     * If so we return the pair (R, i), where i is [Instrument] recording the addition that needs to be placed before R.
     */
    private fun analyzeShiftCand(
        matchLoc: LTACCmdView<TACCmd.Simple.AssigningCmd>,
        cand: AddKtoFP,
        ctxt: TACCommandGraph
    ) : Pair<CmdPointer, Instrument>? {
        val mca = ctxt.cache[MustBeConstantAnalysis]

        /**
         * aka v
         */
        val otherOperand = cand.addLoc.maybeExpr<TACExpr.Vec.Add>()?.exp?.ls?.singleOrNull {
            it is TACExpr.Sym && mca.mustBeConstantAt(cand.addLoc.ptr, it.s) != EVM_WORD_SIZE
        } as? TACExpr.Sym.Var ?: return null

        /**
         * The definition site D
         */
        val otherOpDefSite = ctxt.cache.strictDef.defSitesOf(otherOperand.s, pointer = cand.addLoc.ptr).singleOrNull() ?: return null


        /**
         * If our base `v` isn't equal to the free pointer, we're probably outside an allocation window, and can
         * flag any reads of the FP as potentially non-aliased.
         */
        var seenFPWrite = TACKeyword.MEM64.toVar() !in ctxt.cache.gvn.equivAfter(
            otherOpDefSite, otherOperand.s
        )
        // in between our other operand and this one, did we see a new FP read? this suggests the need to rewrite
        var found: LTACCmd? = null
        for(lc in ctxt.iterateBlock(otherOpDefSite, excludeStart = true)) {
            /**
             * We reached [matchLoc] without hitting a read from the FP, nothing to do here
             */
            if(lc.ptr == matchLoc.ptr) {
                return null
            }

            if(!seenFPWrite) {
                if(lc.cmd is TACCmd.Simple.AssigningCmd && lc.cmd.lhs == TACKeyword.MEM64.toVar()) {
                    seenFPWrite = true
                }
                continue
            }
            /**
             * We have found `R`
             */
            if(lc.maybeExpr<TACExpr.Sym.Var>()?.exp?.s == TACKeyword.MEM64.toVar()) {
                // seen one
                found = lc
                break
            }
        }
        // no new read found, nothing to do here
        if(found == null) {
            return null
        }
        // we can't shift the definition up to this location: it will trample some other definition...
        // that is, find uses of otherOperand from R (aka found), make sure they are all dominated by matchloc
        if(ctxt.cache.use.useSitesAfter(matchLoc.cmd.lhs, found.ptr).any { useSite ->
            !ctxt.cache.domination.dominates(matchLoc.ptr, useSite)
        }) {
            return null
        }
        val operandAliases = ctxt.cache.gvn.findCopiesAt(
            found.ptr, cand.addLoc.ptr to otherOperand.s
        )
        val target = otherOperand.s.takeIf {
            it in operandAliases
        } ?: operandAliases.firstOrNull() ?: return null
        return found.ptr to Instrument(
            replaceRHS = matchLoc,
            holder = target,
            k = cand.const
        )
    }

    /**
     * Shift pointer computations to occur "closer" to an allocation. If we have
     * ```
     * x = FP;
     * FP = x + c;
     * ...
     * y = FP
     * FP = y + k;
     * z = x + j;
     * ```
     *
     * As another example we may have:
     * ```
     * x = FP;
     * FP = x + k;
     * t1 = x + 0x20;
     * t2 = t1 + 0x20
     * ...
     * tk = tj + 0x20
     *
     * y = FP;
     * z = tk + 0x20
     * ```
     * where `ti` are the field pointers for some struct.
     *
     * This analysis will shift the definition of z to before the second read of the FP (if it is sound to do so).
     *
     * This optimization is necessary to make the initialization analysis happy: it will ignore code that occurs as part
     * of sub-object allocations, and in the above patterns, the initialization for the object allocated in x will "miss"
     * the definition of z, leading to an initialization analysis failure.
     */
    fun shiftPointerComputation(c: CoreTACProgram) : CoreTACProgram {

        /**
         * The iterated version of the initialization. Matches `μp.(fp | p) + 32`, aka `fp + 32 + 32 + ...`.
         */
        val iteratedPattern = PatternMatcher.Pattern.RecursivePattern<AddKtoFP>(
            builder = { base: PatternMatcher.Pattern<AddKtoFP> ->
                PatternDSL.build {
                    (PatternMatcher.Pattern.XOr(
                        // base case of the recursion, we're adding 0x20 to the free pointer
                        first = TACKeyword.MEM64.toVar().withLocation.named("base").toPattern(),
                        // recursive case, we're adding 0x20 to something matched by `p`
                        second = base,
                        adapt1 = { it },
                        adapt2 = { it -> it.readLoc },
                        patternName = "addition-base"
                    ).asBuildable() + 32()).named("word-addition").commute.named("word-addition-commute").withAction { addLoc, readLoc, _ ->
                        AddKtoFP(
                            addLoc = addLoc,
                            readLoc = readLoc,
                            const = EVM_WORD_SIZE
                        )
                    }
                }
            },
            patternName = "recursive-pattern"
        )

        val unifiedPattern = PatternMatcher.Pattern.Or(
            iteratedPattern,
            { it },
            singlePattern,
            { it }
        )
        val touchedVars = c.analysisCache[VariableLookupComputation]
        val matcher = PatternMatcher.compilePattern(graph = c.analysisCache.graph, patt = unifiedPattern, traverseFilter = { it != TACKeyword.MEM64.toVar() })
        val toRewrite = c.ltacStream().filter {
            it.ptr.block !in c.analysisCache.revertBlocks
        }.filter {
            TACKeyword.MEM64.toVar() in touchedVars[it.ptr.block]!!
        }.mapNotNull {
            it.maybeNarrow<TACCmd.Simple.AssigningCmd>()?.takeIf {
                it.cmd.lhs != TACKeyword.MEM64.toVar()
            }
        }.mapNotNull {
            // find all straightline code where we read the FP in some block,
            // then compute this candidate
            it `to?` matcher.queryFrom(it).toNullableResult()?.takeIf { (_, read, _) ->
                read.block == it.ptr.block && read.pos < it.ptr.pos
            }
        }.mapNotNull { (def, read) ->
            analyzeShiftCand(
                def,
                read,
                c.analysisCache.graph
            )
        }.collect(Collectors.groupingBy({ it.first }, Collectors.mapping({it.second}, Collectors.toList())))

        return c.patching {
            for((where, toAdd) in toRewrite) {
                val tempDefs = mutableListOf<TACCmd.Simple>()
                for(a in toAdd) {
                    tempDefs.add(
                        TACCmd.Simple.AssigningCmd.AssignExpCmd(
                            lhs = a.replaceRHS.cmd.lhs,
                            rhs = TACExpr.Vec.Add(
                                a.holder.asSym(),
                                a.k.asTACSymbol().asSym()
                            ),
                            meta = a.replaceRHS.cmd.meta
                        )
                    )
                    it.replaceCommand(a.replaceRHS.ptr, listOf())
                }
                it.replace(where) { _, l ->
                    tempDefs + listOf(l)
                }
            }
        }
    }
}
