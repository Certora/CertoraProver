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

package analysis.opt

import algorithms.dominates
import analysis.CmdPointer
import com.certora.collect.*
import datastructures.stdcollections.*
import instrumentation.transformers.DSAChecker
import log.*
import tac.MetaKey
import utils.*
import vc.data.*
import vc.data.tacexprutil.TACExprUtils.contains
import vc.data.tacexprutil.asVarOrNull
import vc.data.tacexprutil.getFreeVars
import java.io.Serializable

private val logger = Logger(LoggerTypes.OPTIMIZE)

/**
 * Folds syntactically identical definitions: when one assignment of a deterministic rhs dominates another
 * of the same rhs, the two provably assign the same value. The dominated definition is rewritten to a copy
 * of the dominating one (`dup := canonical`), and uses of `dup` are redirected to `canonical` where that is
 * sound (see [alias] and [blockAlias]), so definitions computed from the two copies fold in turn.
 *
 * The motivating case is buffer hash unification. When the same logical data is hashed (or read) through two
 * independently built memory buffers — e.g., the same `bytes` argument abi-decoded once in the rule and once
 * in the contract — copy-loop summarization gives both buffers syntactically identical `LongStore`
 * constructions, but nothing downstream merges them: the reads of the two buffers stay distinct terms, so the
 * hash applications over them stay distinct, everything keyed by those hashes (storage reads/writes) fails to
 * alias statically, and the solver is left to re-derive the equalities through hash congruence — which on
 * hard rules it cannot do within the timeout. Folding the buffer versions makes the word reads fold, then
 * the hash applications, then the storage keys computed from them, and so on down the chain.
 *
 * Runs between [instrumentation.transformers.TACDSA] and [instrumentation.transformers.DSAToSSA] (see
 * [verifier.AbstractTACChecker.Companion.toSimpleSimpleSSA]), where every variable — maps included — is in
 * the diamond-DSA form: a variable's definitions are confined to sibling diamond arms, and every use comes
 * after the taken arm's definition (or after the join), so its value cannot change between a dominating
 * occurrence and a dominated one. Rather than assuming this, the pass runs [DSAChecker.nonDsaVars] and
 * excludes any variable violating the invariant (e.g. keyword variables protected from renaming) from
 * folding altogether.
 *
 * Soundness: only definitions whose rhs is deterministic (no [TACExpr.Unconstrained]) participate, and a
 * duplicate is folded only when the canonical definition dominates it, so the canonical variable is in scope
 * — and constrained — wherever the duplicate is. Use redirection is governed by [registerDef]'s bookkeeping
 * of plain-copy definitions; see [alias] and [blockAlias] for why each needs no dominance queries. This is a
 * pure semantics-preserving rewrite; no assumptions are introduced.
 */
class CommonExpressionFolder private constructor(private val code: CoreTACProgram) {

    companion object {
        fun fold(code: CoreTACProgram) = CommonExpressionFolder(code).rewrite()
    }

    private val g = code.analysisCache.graph
    private val dom = code.analysisCache.domination

    /**
     * Variables violating the diamond-DSA invariant the whole pass rests on. Excluded from folding entirely:
     * as operands their value at an occurrence is not pinned by the diamond structure, and their uses may
     * appear where no visit-order argument protects them. Expected to be (nearly) empty right after [TACDSA].
     */
    private val nonDsa = DSAChecker.nonDsaVars(code).let { it.badDefForm + it.useBeforeDef }

    init {
        if (nonDsa.isNotEmpty()) {
            logger.info { "${code.name}: ${nonDsa.size} variables violate DSA and are excluded from folding" }
        }
    }

    /**
     * Global copy chart: `d -> c` when every definition of `d` seen so far is a plain copy of the same `c`
     * (after our own def rewrites — a folded duplicate becomes such a copy). A definition of `d` that is
     * anything else moves `d` into [poisoned], permanently.
     *
     * Uses may be redirected through the live chart with no dominance queries, by the visit order alone:
     * under diamond-DSA a use of `d` is either (i) in a defining block after that block's def — which was
     * visited just above and either agreed with the chart or poisoned it — or (ii) at/after the join, which
     * topological order visits after ALL defining blocks, so the chart is final there: `d == c` on every path
     * (`phi(c, ..., c) = c`) or poisoned. Chart targets are never themselves charted as duplicates (a
     * canonical's own def took the miss path), so no transitive lookup is needed.
     */
    private val alias = mutableMapOf<TACSymbol.Var, TACSymbol.Var>()
    private val poisoned = mutableSetOf<TACSymbol.Var>()

    /**
     * Block-local copy chart, cleared at each block start and taking precedence over [alias]. Within a
     * straight-line block, after `d := c` nothing can redefine `d` before the block ends, so the entry is
     * unconditionally valid for the rest of the block — this wins back the arms of "mixed" diamonds, where
     * sibling defs copy different canonicals and poison the global chart.
     */
    private val blockAlias = mutableMapOf<TACSymbol.Var, TACSymbol.Var>()

    /**
     * Rewrites used symbols through the copy charts. Metas and annotation payloads are reporting data, not
     * uses — they are left untouched (as in [ConstantPropagatorAndSimplifier]'s special-casing): rewriting
     * them detaches e.g. call-trace snippets from the variables they describe, and it is never needed, since
     * every variable they mention keeps its definition and value (a folded duplicate's def remains as a
     * copy). [DefaultTACCmdMapper.mapMetaPair] is the single point all such payloads pass through — command
     * metas, variable metas, annotation commands, and annotation expressions.
     */
    private val useRewriter = object : DefaultTACCmdMapper() {
        override fun mapVar(t: TACSymbol.Var) = blockAlias[t] ?: alias[t] ?: t
        override fun mapLhs(t: TACSymbol.Var) = t
        override fun <T : Serializable> mapMetaPair(k: MetaKey<T>, v: T): T = v
    }

    /**
     * Is it sound to key the annotation-stripped [core] of a definition's rhs by its syntactic form? Requires
     * determinism, and that every free variable is diamond-DSA (as checked by [DSAChecker]), so that no def
     * of it can execute between a dominating occurrence and a dominated one. Note this covers a variable
     * appearing in its own defining rhs: its use there is not strictly dominated by its def, so [DSAChecker]
     * flags it. rhs's that are just a symbol are of no interest — they are what feeds [registerDef].
     * A [TACExpr.AnnotationExp] below the top level (see [peelAnnotations]) is excluded not for soundness but
     * because the rewrite could not preserve it for reporting.
     */
    private fun isUnifiable(core: TACExpr) =
        core !is TACExpr.Sym &&
            !core.contains { it is TACExpr.Unconstrained || it is TACExpr.AnnotationExp<*> } &&
            core.getFreeVars().none { it in nonDsa }

    /**
     * Peels top-level [TACExpr.AnnotationExp] layers off [e]: returns the unannotated core, and a function
     * rebuilding the same annotation layers around a replacement core. Annotations are value-transparent, so
     * definitions are folded up to them — but each duplicate keeps its own annotations wrapped around the
     * canonical variable (`dup := AnnotationExp(canonical, annot)`), since they feed the call trace and
     * unsat-core reporting.
     */
    private fun peelAnnotations(e: TACExpr): Pair<TACExpr, (TACExpr) -> TACExpr> =
        if (e is TACExpr.AnnotationExp<*>) {
            fun <@Treapable T : Serializable> rewrap(a: TACExpr.AnnotationExp<T>, core: TACExpr) =
                TACExpr.AnnotationExp(core, a.annot)
            val (core, rebuild) = peelAnnotations(e.o)
            core to { newCore: TACExpr -> rewrap(e, rebuild(newCore)) }
        } else {
            e to { it }
        }

    /**
     * Updates the copy charts with the (final, possibly rewritten) definition [cmd]. A definition whose
     * annotation-stripped rhs is a plain variable `c` (with `c` diamond-DSA, so `c` itself cannot be
     * redefined where the entry is consulted) records `lhs -> c`; any other definition invalidates `lhs`.
     * The lhs itself must be diamond-DSA for the global chart — for the block-local one the straight-line
     * argument holds for any variable.
     */
    private fun registerDef(cmd: TACCmd.Simple) {
        val lhs = cmd.getModifiedVar() ?: return
        val copyOf = (cmd as? TACCmd.Simple.AssigningCmd.AssignExpCmd)
            ?.let { peelAnnotations(it.rhs).first.asVarOrNull }
            ?.takeIf { it !in nonDsa }
        if (copyOf != null) {
            blockAlias[lhs] = copyOf
        } else {
            blockAlias -= lhs
        }
        if (lhs in nonDsa || lhs in poisoned) {
            return
        }
        val current = alias[lhs]
        when {
            copyOf == null || (current != null && current != copyOf) -> {
                alias -= lhs
                poisoned += lhs
            }

            else -> alias[lhs] = copyOf
        }
    }

    private fun rewrite(): CoreTACProgram {
        // per rhs, the defs seen so far that no other seen def dominates (one per parallel branch; usually one)
        val canonicalDefs = mutableMapOf<TACExpr, MutableList<Pair<CmdPointer, TACSymbol.Var>>>()
        var count = 0
        val patcher = code.toPatchingProgram()
        for (block in code.topoSortFw) {
            blockAlias.clear()
            for ((ptr, origCmd) in g.lcmdSequence(block)) {
                val cmd = runIf(alias.isNotEmpty() || blockAlias.isNotEmpty()) { useRewriter.map(origCmd) }
                    ?: origCmd
                var finalCmd = cmd
                if (cmd is TACCmd.Simple.AssigningCmd.AssignExpCmd) {
                    val (core, rebuildAnnotations) = peelAnnotations(cmd.rhs)
                    if (isUnifiable(core)) {
                        val candidates = canonicalDefs.getOrPut(core) { mutableListOf() }
                        val canonical = candidates.firstOrNull { (defPtr, lhs) ->
                            lhs.tag == cmd.lhs.tag && dom.dominates(defPtr, ptr)
                        }
                        if (canonical == null) {
                            // a canonical variable must be constrained by its defining equation wherever
                            // it replaces something, so it must itself be diamond-DSA
                            if (cmd.lhs !in nonDsa) {
                                candidates += ptr to cmd.lhs
                            }
                        } else {
                            finalCmd = cmd.copy(rhs = rebuildAnnotations(canonical.second.asSym()))
                            count++
                        }
                    }
                }
                registerDef(finalCmd)
                if (finalCmd != origCmd) {
                    patcher.update(ptr, finalCmd)
                }
            }
        }
        logger.info { "${code.name}: folded $count duplicate definitions" }
        return patcher.toCode(code)
    }
}
