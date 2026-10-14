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

package report.calltrace

import datastructures.MultiMap
import datastructures.add
import datastructures.mutableMultiMapOf
import datastructures.stdcollections.*
import evm.EVM_MOD_GROUP256
import log.*
import smt.axiomgenerators.fullinstantiation.StorageHashAxiomGenerator
import solver.CounterexampleModel
import utils.*
import vc.data.CoreTACProgram
import vc.data.TACCmd
import vc.data.TACExpr
import vc.data.TACSymbol
import vc.data.tacexprutil.asSymOrNull
import java.math.BigInteger

private val logger = Logger(LoggerTypes.CALLTRACE)

/**
 * The structured representation of a storage key, used for displaying counterexamples: a [Node] is a hash application
 * (its [Node.children] are the hash's arguments, the byte-length argument first) plus an [offset], a [Basic] is a
 * root slot. Reconstructed from integer model values by [ModelSkeyReconstructor] and parsed into display paths by
 * [StorageDisplayPathGenerator].
 */
sealed class SKey {
    abstract val offset: BigInteger

    data class Basic(override val offset: BigInteger) : SKey()

    data class Node(val children: List<SKey>, override val offset: BigInteger) : SKey()
}

/**
 * Reconstructs the structured storage-key value ([SKey]) that an integer counterexample-model value denotes.
 *
 * In the hashing model, storage keys are plain integers: a hash application's value is an unconstrained (axiomatized)
 * integer, and offsets on top of it are ordinary additions. The structure of a storage key -- which hash it is based
 * on, with which arguments, at which offset -- is thus not visible in the model value itself, but it can be recovered
 * by redoing the taint analysis that the axiom generation ([StorageHashAxiomGenerator]) does: hash assignments
 * generate storage keys, additions/subtractions and assignments propagate them. Every storage-key variable's set of
 * possible base hash applications is known statically from its definition chains, so a value that equals such a
 * variable's model value decomposes exactly: base = the hash the chain bottoms out at, offset = the difference of
 * the model values (faithful, since the hashing axioms give every hash application its own region).
 *
 * Only the counterexample's executed path is considered, so each variable has a unique executed definition and
 * hence a unique base hash. Values that match no storage-key expression are deemed root slots ([SKey.Basic]). This
 * is display-only machinery, so a wrong guess can garble a counterexample's storage location, never a verification
 * result.
 */
class ModelSkeyReconstructor(private val code: CoreTACProgram, private val model: CounterexampleModel) {
    private val g = code.analysisCache.graph

    private fun valueOf(e: TACExpr): BigInteger? =
        e.asSymOrNull?.let { model.valueAsTACValue(it)?.asBigIntOrNull() }

    /** a hash application's own model value, plus those of its length and arguments */
    private data class HashApp(val value: BigInteger, val length: BigInteger, val args: List<BigInteger>)

    /** for each model value attained by some storage-key expression, the candidate base hash applications */
    private val candidatesByValue: MultiMap<BigInteger, HashApp> by lazy {
        val varCandidates = mutableMultiMapOf<TACSymbol.Var, HashApp>()

        /**
         * The storage-key taint analysis of [StorageHashAxiomGenerator.taintedStorageKeyVars], refined from a boolean
         * ("may this be a hash-derived storage key?") to the set of base hash applications the key may be derived
         * from: an expression is "tainted" iff this is non-empty. The transfer function is the same -- additions
         * propagate from any operand, subtractions from the left one -- and hash applications themselves generate
         * (handled at their defining assignment, in the pass below). For `ite`s we can do better than the both-branch
         * over-approximation: the model tells us which branch was actually taken.
         */
        fun baseCandidatesOf(e: TACExpr): Set<HashApp> = when (e) {
            is TACExpr.Sym.Var -> varCandidates[e.s].orEmpty()

            is TACExpr.Vec.Add,
            is TACExpr.Vec.IntAdd -> (e as TACExpr.Vec).ls.flatMapToSet(::baseCandidatesOf)

            is TACExpr.BinOp.Sub,
            is TACExpr.BinOp.IntSub -> baseCandidatesOf((e as TACExpr.BinOp).o1)

            is TACExpr.TernaryExp.Ite -> {
                val (success, cond) = model.evalExprByRhs(e.i)
                when {
                    !success || cond == null -> baseCandidatesOf(e.t) + baseCandidatesOf(e.e)
                    cond == BigInteger.ZERO -> baseCandidatesOf(e.e)
                    else -> baseCandidatesOf(e.t)
                }
            }

            else -> emptySet()
        }

        val byValue = mutableMultiMapOf<BigInteger, HashApp>()

        // only the executed path matters: this both makes each variable's executed definition (and hence its base)
        // unique, and keeps unexecuted branches' junk model values from injecting spurious candidates.
        // as in [StorageHashAxiomGenerator.taintedStorageKeyVars], a single forward pass over the blocks in
        // topological order processes every definition before its uses (loop-free DSA).
        g.lcmdSequence(code.topoSortFw.filter { it in model.reachableNBIds }).forEach { (_, cmd) ->
            if (cmd !is TACCmd.Simple.AssigningCmd.AssignExpCmd) {
                return@forEach
            }
            val rhs = cmd.rhs
            val rhsCandidates = if (rhs is TACExpr.SimpleHash) {
                setOfNotNull(
                    runIf(rhs.hashFamily.requiresLargeGaps) {
                        val value = valueOf(cmd.lhs.asSym()) ?: return@runIf null
                        val length = valueOf(rhs.length) ?: return@runIf null
                        val args = rhs.args.monadicMap(::valueOf) ?: return@runIf null
                        HashApp(value, length, args)
                    }
                )
            } else {
                baseCandidatesOf(rhs)
            }
            val value = valueOf(cmd.lhs.asSym())
            rhsCandidates.forEach { hashApp ->
                varCandidates.add(cmd.lhs, hashApp)
                if (value != null) {
                    byValue.add(value, hashApp)
                }
            }
        }
        byValue
    }

    /** The [SKey] that the model value [w] denotes, to the best of our knowledge. */
    fun reconstruct(w: BigInteger): SKey {
        fun rec(w: BigInteger, depth: Int): SKey {
            // models can relate hash values in ways no real skey term does (e.g. a hash whose argument's value
            // equals the hash itself), so guard the recursion.
            if (depth > MAX_DEPTH) {
                logger.warn { "skey reconstruction exceeded depth $MAX_DEPTH for value $w, giving up on its structure" }
                return SKey.Basic(w)
            }
            // on the executed path every storage-key expression has a unique base -- and two same-valued
            // expressions with different bases would contradict the hashing axioms -- so a plural candidate set
            // can only mean our analysis and the axioms' disagree on what is a storage key; give up then.
            val candidates = candidatesByValue[w].orEmpty()
            val base = candidates.uniqueOrNull()
                ?: return SKey.Basic(w).also {
                    if (candidates.isNotEmpty()) {
                        logger.warn { "several distinct base hashes for storage-key value $w: $candidates" }
                    }
                }
            return SKey.Node(
                children = (listOf(base.length) + base.args).map { rec(it, depth + 1) },
                // storage-pointer arithmetic wraps around 2^256, so the value may lie *below* its base hash
                // (e.g. an array access at a huge index) -- hence the modulo
                offset = (w - base.value).mod(EVM_MOD_GROUP256),
            )
        }
        return rec(w, depth = 0)
    }

    companion object {
        private const val MAX_DEPTH = 32
    }
}
