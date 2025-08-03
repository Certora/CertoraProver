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

package analysis.numeric

import analysis.CmdPointer
import analysis.dataflow.IMustEqualsAnalysis
import utils.*
import vc.data.TACSymbol

/**
 * A base implementation that manages killing qualifiers mentioned in the left-hand side of an assignment, and
 * also attempting to preserve those qualifiers with equality information (via [me]).
 *
 * [Q] is the type of qualifiers and [I] is an abstract value with these qualifiers. [S] is assumed to map
 * variables (among other things) to instances of type [I]
 */
abstract class QualifierManager<Q: SelfQualifier<Q>, I: WithQualifiers<Q, I>, S>(val me: IMustEqualsAnalysis?) {
    fun killLHS(lhs: TACSymbol.Var, lhsVal: I?, s: S, where: CmdPointer) : S {
        val (lhsEquiv, equiv) = mkEquiv(where, lhs)
        val toPropagate = lhsVal?.qual?.flatMap {
            if(!it.relates(lhs)) {
                listOf(it)
            } else {
                it.saturateWith(equiv)
            }
        }
        return mapValues(s) { v: TACSymbol.Var, i: I ->
            if(v == lhs) {
                i
            } else {
                val newQual = if(v in lhsEquiv) {
                    toPropagate
                } else {
                    null
                }
                val noLHSQuals by lazy {
                    i.qual.filter {
                        !it.relates(lhs)
                    }
                }
                val upd = mutableSetOf<Q>()
                var relatesLHS = false
                for(q in i.qual) {
                    if(!q.relates(lhs)) {
                        continue
                    }
                    relatesLHS = true
                    upd.addAll(q.saturateWith(equiv))
                }
                if(newQual == null && upd.isEmpty()) {
                    return@mapValues if (relatesLHS) {
                        i.withQualifiers(noLHSQuals)
                    } else {
                        i
                    }
                }
                val quals =
                        (newQual ?: emptyList()) +
                                if(upd.isNotEmpty()) {
                                    noLHSQuals + upd
                                } else {
                                    i.qual
                                }
                i.withQualifiers(quals)
            }
        }
    }

    private fun mkEquiv(where: CmdPointer, lhs: TACSymbol.Var): Pair<Set<TACSymbol.Var>, (TACSymbol.Var) -> Set<TACSymbol.Var>> {
        val lhsEquiv = me?.let { it.equivBefore(where, lhs) - lhs }.orEmpty()
        val equiv = { v: TACSymbol.Var ->
            if (v == lhs) {
                lhsEquiv
            } else {
                setOf(v)
            }
        }
        return Pair(lhsEquiv, equiv)
    }

    /**
     * Visit every variable of type [I] in state [S], updating the value for each variable with the result
     * of [mapper]
     */
    protected abstract fun mapValues(s: S, mapper: (TACSymbol.Var, I) -> I) : S

    fun assign(toStep: S, lhs: TACSymbol.Var, newValue: I, where: CmdPointer): S {
        val eq by lazy {
            mkEquiv(where, lhs).second
        }
        val id by lazy {
            newValue.qual.filter { !it.relates(lhs) }
        }
        val upd = mutableSetOf<Q>()
        for(q in newValue.qual) {
            if(q.relates(lhs)) {
                upd.addAll(q.saturateWith(eq))
            }
        }
        val toWrite = if(upd.isNotEmpty()) {
            newValue.withQualifiers(upd + id)
        } else {
            newValue
        }
        return assignVar(toStep, lhs, toWrite, where)
    }

    /**
     * Assign the value [toWrite] to [lhs] in the state [toStep], in context [where]
     */
    protected abstract fun assignVar(toStep: S, lhs: TACSymbol.Var, toWrite: I, where: CmdPointer) : S
}
