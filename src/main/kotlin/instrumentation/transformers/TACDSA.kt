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

import algorithms.postOrder
import algorithms.topologicalOrderOrNull
import analysis.*
import datastructures.MutableBijection
import datastructures.stdcollections.*
import instrumentation.transformers.TACDSA.TACDSARenaming
import log.*
import spec.CVLKeywords
import spec.toVar
import tac.*
import utils.*
import vc.data.*
import vc.data.TACCmd.Simple.AssigningCmd.AssignExpCmd
import verifier.BlockMerger
import java.util.stream.Collectors
import java.util.stream.Stream


private val logger = Logger(LoggerTypes.DSA)

val DSA_BLOCK_START = MetaKey<String>("dsa.assign.start", erased = true)
val DSA_BLOCK_END = MetaKey<String>("dsa.assign.end", erased = true)

private typealias Substitution = Map<TACSymbol.Var, TACSymbol.Var>
private typealias MutableSubstitution = MutableMap<TACSymbol.Var, TACSymbol.Var>

private fun emptySubstitution(): Substitution = mapOf()
private fun mutableSubstitution(): MutableSubstitution = mutableMapOf()

/**
 * Rewrites the program according to DSA, which is actually very similar to SSA. In both of them, in a block where a few
 * versions of a variable meet, a new version of the variable is defined. In SSA it is then assigned once at the
 * beginning of this block with an ite expression checking from which block it came. In DSA, this new variable is
 * assigned on the "edge" from the predecessor going into the block. Therefore this new variable will have many
 * assignments, but all are just before entering the block.
 */
object TACDSA {
    private val keywordVars = (TACKeyword.entries.map {
        it.toVar()
    } + CVLKeywords.entries.map {
        it.toVar()
    }).toSet()


    /**
     * Interface for defining the prefix to use for a variable during DSA. The DSA pass will
     * append a numeric identifier onto the String returned by this function, care should be taken
     * that the resulting identifier does not collide with existing names in the typescope.
     */
    fun interface TACDSARenaming {
        fun variablePrefix(v: TACSymbol.Var) : String

        companion object {
            val default = TACDSARenaming { it: TACSymbol.Var ->
                when (val t = it.tag) {
                    Tag.Bit256 -> "R"
                    is Tag.Bits -> "R${t.bitwidth}_"
                    Tag.Bool -> "B"
                    Tag.ByteMap -> "M"
                    Tag.WordMap -> "W"
                    Tag.Int -> "I"
                    is Tag.UserDefined.UninterpretedSort -> "US"
                    is Tag.UserDefined.Struct -> "ST"
                    is Tag.GhostMap -> `impossible!`
                    Tag.BlockchainState -> "BS"
                    is Tag.CVLArray -> "A"
                }
            }
        }
    }

    private class Worker(
        private val core: CoreTACProgram,
        private val protectedVars: Set<TACSymbol.Var>,
        private val annotate: Boolean,
        private val preserveCallIndices: Set<TACSymbol.Var>,
        private val _isErasable: (TACCmd.Simple.AssigningCmd) -> Boolean,
        private val prefixStrategy: TACDSARenaming,
        private val isTypechecked: Boolean
    ) {
        private fun isErasable(cmd : TACCmd.Simple.AssigningCmd) =
            cmd.lhs !in protectedVars && _isErasable(cmd)

        private val g = core.analysisCache.graph
        private val lva = core.analysisCache.lva

        private val patcher = core.toPatchingProgram()
        private val blockToPrePhi = mutableMapOf<NBId, MutableSubstitution>()

        private val immutableBoundsOf: Map<TACSymbol.Var, Set<TACSymbol.Var>> =
            core.parallelLtacStream().flatMap { lc ->
                val builder = Stream.builder<Pair<TACSymbol.Var, TACSymbol.Var>>()
                lc.cmd.getFreeVarsOfRhs().forEach { v ->
                    v.meta.find(TACMeta.EVM_IMMUTABLE_ARRAY)?.let {
                        it.sym as? TACSymbol.Var
                    }?.let { bound ->
                        builder.add(bound to v)
                    }
                }
                if (lc.cmd is TACCmd.Simple.AssigningCmd) {
                    lc.cmd.lhs.meta.find(TACMeta.EVM_IMMUTABLE_ARRAY)?.let {
                        it.sym as? TACSymbol.Var
                    }?.let {
                        builder.add(it to lc.cmd.lhs)
                    }
                }
                builder.build()
            }.collect(Collectors.groupingBy({
                it.first
            }, Collectors.mapping({ it.second }, Collectors.toSet())))


        private val assignmentRewrites = mutableMapOf<CmdPointer, TACSymbol.Var>()
        private val deadAssignments = mutableSetOf<CmdPointer>()
        private val newTags = tagsBuilder<TACSymbol.Var>()
        private val ufRewrites = mutableSetOf<FunctionInScope.UF>()
        private val newUfAxioms = mutableMapOf<FunctionInScope.UF, List<TACAxiom>>()

        private var newVarCounter = 0

        private fun toRegister(b: Int, v: TACSymbol.Var, callIndex: CallId?, meta: MetaMap = MetaMap()): TACSymbol.Var {
            val tag = v.tag
            val prefix = prefixStrategy.variablePrefix(v)
            val namePrefix = "$prefix$b"
            /*
            There is no syntax (a la ocaml optional arguments) for "use the default for callIndex if the argument is null"
            leading to this awful version
             */
            return if(callIndex == null) {
                TACSymbol.Var(namePrefix = namePrefix, tag = tag, meta = meta)
            } else {
                TACSymbol.Var(namePrefix = namePrefix, tag = tag, meta = meta, callIndex = callIndex)
            }
        }

        private fun TACSymbol.Var.registerTag() =
            this.also { newTags.safePut(it, it.tag) }

        private fun newVar(v: TACSymbol.Var, moreMeta: MetaMap = MetaMap()) =
            if (v.tag is Tag.GhostMap) {
                v.copy(
                    namePrefix = "${v.smtRep}!${newVarCounter++}",
                    meta = v.meta + moreMeta
                ).registerTag()
            } else {
                toRegister(
                    newVarCounter++, v, v.callIndex.takeIf {
                        v in preserveCallIndices
                    }, v.meta + moreMeta
                ).registerTag()
            }

        /** This works only on the original version of the variables, and returns the original uf */
        private fun ufOf(v: TACSymbol.Var) =
            core.symbolTable.getUninterpretedFunctions(v.smtRep)
                .also {
                    check(it.size <= 1) {
                        "Expected the symbolTable ${core.symbolTable.uninterpretedFunctions()} to contain " +
                            "exactly one uninterpreted function with the name $v"
                    }
                }
                .singleOrNull()

        /** unlike [ufOf], this will also contain all the new versions */
        private val ufToVar = MutableBijection<FunctionInScope.UF, TACSymbol.Var>()

        /** Returns the original uf of a later version uf */
        private val ufToOriginal = mutableMapOf<FunctionInScope.UF, FunctionInScope.UF>()

        /**
         * Registers if [lhs] is indeed a variable corresponding to a uf. This does not do any axiom rewrites, and is
         * called as an initial step, before any of the rewriting is done.
         */
        private fun registerUFRewrite(lhs: TACSymbol.Var, newLhs: TACSymbol.Var) =
            ufOf(lhs)?.let { funcToReplace ->
                val rewrittenFunc = funcToReplace.copy(name = newLhs.smtRep)
                ufRewrites += rewrittenFunc
                ufToVar[funcToReplace] = lhs
                ufToVar[rewrittenFunc] = newLhs
                ufToOriginal[rewrittenFunc] = funcToReplace
                logger.debug { "UF rewrite : $lhs ---> $newLhs" }
            }

        /**
         * A command rewriter according to [mut]. If [lhs] is non-null, rewrites the lhs of assignment commands
         * according to it.
         */
        private fun rewriter(mut: Substitution, lhs: TACSymbol.Var?) = object : DefaultTACCmdMapper() {
            override fun mapVar(t: TACSymbol.Var): TACSymbol.Var {
                return mut[t]?.let {
                    it.copy(meta = mapMeta(t.meta + it.meta))
                } ?: t.withMeta {
                    mapMeta(it)
                }
            }

            override fun mapLhs(t: TACSymbol.Var): TACSymbol.Var {
                return lhs ?: t
            }
        }


        /** A command we wish to rewrite */
        private fun isAssignable(cmd: TACCmd.Simple) =
            cmd is TACCmd.Simple.AssigningCmd &&
                cmd !is TACCmd.Simple.AssigningCmd.ByteStore &&
                cmd !is TACCmd.Simple.AssigningCmd.ByteStoreSingle &&
                cmd.lhs !in protectedVars

        /**
         * We allow blocks to be in their own dominance frontier. This will happen for example if a block is in a
         * self loop, and has another incoming edge. In such a case, if there is an assignment to x within the
         * block, we would still need a fresh variable for x at the beginning of the block.
         */
        val dominanceFrontier = core.analysisCache.domination.calcFrontiers(true)

        /**
         * For each command that [isAssignable] in block [nbid], get a new variable for its lhs, and go over all places
         * in the domination frontier, i.e., places where this definition meets another one, and define a new variable
         * to be used from that point on.
         */
        private fun assignConfluenceVars(nbid: NBId) {
            val varsToPropagate = g.elab(nbid).commands
                .filter { isAssignable(it.cmd) }
                .mapNotNullToSet { (ptr, cmd) ->
                    check(cmd is TACCmd.Simple.AssigningCmd)
                    val lhs = cmd.lhs
                    if (!lva.isLiveAfter(ptr, lhs) && isErasable(cmd)) {
                        deadAssignments += ptr
                        null
                    } else {
                        val newVar = newVar(lhs)
                        assignmentRewrites[ptr] = newVar
                        registerUFRewrite(lhs, newVar)
                        lhs
                    }
                }

            varsToPropagate.forEach { v ->
                val df = dominanceFrontier[nbid].orEmpty()
                val queue = ArrayDeque(df)
                queue.consume { block ->
                    // so how come we check here only for liveness and not the array bound thing?
                    if (!lva.isLiveBefore(block, v)) {
                        return@consume
                    }
                    if (blockToPrePhi[block]?.keys?.contains(v) == true) {
                        return@consume
                    }
                    val newV = newVar(v)
                    blockToPrePhi.getOrPut(block) { mutableSubstitution() }[v] = newV
                    registerUFRewrite(v, newV)
                    queue += dominanceFrontier[block].orEmpty()
                }
            }
        }


        /**
         * Takes the [initialSubstitution] as we enter a block, goes through the block and accumulates the new
         * substitution while rewriting commands in the block according to it. Returns the new [Substitution].
         */
        private fun processBlock(nbid: NBId, initialSubstitution: Substitution): Substitution {
            val sub = initialSubstitution.toMutableMap()

            // rewrite assignments
            for ((ptr, cmd) in g.elab(nbid).commands) {

                if (cmd is TACCmd.Simple.NopCmd || ptr in deadAssignments) {
                    patcher.delete(ptr)
                    continue
                }

                if (cmd is AssignExpCmd && isErasable(cmd)) {
                    val lhs = cmd.lhs
                    val rhs = cmd.rhs
                    if (rhs is TACExpr.Sym.Var &&
                        rhs.s in sub &&
                        ptr in assignmentRewrites &&
                        rhs.s.tag == lhs.tag &&
                        ufOf(cmd.lhs) == null
                    ) {
                        // We are inlining this assignment, and so deleting it.
                        val newVar = sub[rhs.s]!!
                        sub[lhs] = newVar.withMeta { newVarMeta -> newVarMeta + lhs.meta }
                        patcher.delete(ptr)
                        continue
                    }
                }

                val metaRewriter = rewriter(sub, null)

                // everything else gets rewritten.
                val rewriter = rewriter(sub, assignmentRewrites[ptr]?.withMeta {
                    metaRewriter.mapMeta(it)
                })
                patcher.update(ptr, rewriter.map(cmd))

                assignmentRewrites[ptr]?.let { newVar ->
                    check(cmd is TACCmd.Simple.AssigningCmd)
                    sub[cmd.lhs] = newVar.withMeta {
                        metaRewriter.mapMeta(it)
                    }
                }
            }
            return sub
        }


        /**
         * Returns the list of commands corresponding to the given parallel assignment [lhsToRhs]. This would be trivial
         * except the assignments can interfere, e.g.:
         *    (r1, r2, r3) = (r2, r3, r1)
         * The solution is to proceed with the assignments in some order:
         *    r1 := r2
         *    r2 := r3
         * Until we discover r3 should be assigned r1, but r1's value was already erased, so we add a new temporary
         * variable to save r1's value before it was assigned:
         *    temp := r1
         *    r1 := r2
         *    r2 := r3
         *    r3 := temp
         */
        private fun parallelAssignment(lhsToRhs: Map<TACSymbol.Var, TACSymbol.Var>): List<TACCmd.Simple> {
            val alreadyAssigned = mutableSetOf<TACSymbol.Var>()
            val tempOf = mutableMapOf<TACSymbol.Var, TACSymbol.Var>()
            val cmds = ArrayDeque<TACCmd.Simple>()
            for ((lhs, rhs) in lhsToRhs) {
                alreadyAssigned += lhs
                val newRhs = if (rhs in alreadyAssigned) {
                    tempOf.getOrPut(rhs) {
                        newVar(rhs).also {
                            cmds.addFirst(AssignExpCmd(it, rhs))
                        }
                    }
                } else {
                    rhs
                }
                cmds.addLast(AssignExpCmd(lhs, newRhs))
            }
            return cmds.toList()
        }

        /**
         * Adds all the assignments when moving from [src] block to [dst] block. [edgeSub] contains the variable
         * renaming of the move from [src] to [dst], i.e., the variables where [dst] is the confluence point of
         * different versions - so they get a new variable version. [srcEndSub] is the substitution at the end
         * of the [src] block, but before the edge move.
         *
         * The method returns the substitution as we enter block [dst], i.e., [srcEndSub] + [edgeSub], with the non
         * live variables removed.
         */
        private fun processEdge(src: NBId, dst: NBId, edgeSub: Substitution, srcEndSub: Substitution): Substitution {
            if (edgeSub.isNotEmpty()) {
                val lhsToRhs = mutableMapOf<TACSymbol.Var, TACSymbol.Var>()

                val metaRewriter = rewriter(srcEndSub, null)
                for ((v, joinVar) in edgeSub) {
                    val pre = srcEndSub[v]
                        /*
                          Without this, we can get a situation where, e.g., tacReturnDataSizeBool gets
                          assigned in one branch but not in another. Because tacReturnDataSize (the variable the above
                          is based off of) is a tackeyword, it is in the protected set and not rewritten, but its bool counterpart
                          is not, and is thus registered in the edge substitutions. But there is the mismatch:
                          tacReturnDataSize is assumed to always be defined, but its bool counterpart is not, leading to a "could not
                          find substitute". The idea here is to pseudo-protect bool counterparts of keyword variables.
                          If we're asked to find a substitute for one, but don't have one, that means we haven't
                          seen an assignment to the source keyword along the current path, and thus use the "original"
                          (and thus, havoced) version.

                          Note that the above only happens if you conditionally make a call, and then *unconditionally*
                          access returndatasize (which we have seen in safe code, see https://certora.atlassian.net/browse/CERT-2127
                         */
                        ?: v.takeIf { missing ->
                            protectedVars.any {
                                "${it.namePrefix}bool" == missing.namePrefix
                            }
                        } ?: error("Could not find Value substitute for $v substitute in postBlock for $src")
                    lhsToRhs[joinVar.withMeta {
                        metaRewriter.mapMeta(it)
                    }] = pre.withMeta {
                        metaRewriter.mapMeta(it)
                    }
                }

                val cmds = mutableListOf<TACCmd.Simple>()
                fun annotateCmd(key: MetaKey<String>) {
                    if (annotate) {
                        cmds += TACCmd.Simple.AnnotationCmd(
                            TACCmd.Simple.AnnotationCmd.Annotation(key, "$src -> $dst")
                        )
                    }
                }
                annotateCmd(DSA_BLOCK_START)
                cmds += TACCmd.Simple.LabelCmd(
                    "Parallel assignment for ${lhsToRhs.map { it.key }.joinToString()} := " +
                        lhsToRhs.map { it.value }.joinToString()
                )
                cmds += parallelAssignment(lhsToRhs)
                annotateCmd(DSA_BLOCK_END)

                patcher.insertAlongEdge(src, dst, cmds)
            }

            val live = lva.liveVariablesBefore(dst)
            val newSub = srcEndSub.toMutableMap().apply {
                putAll(edgeSub)
                entries.retainAll {
                    it.key in live || (immutableBoundsOf[it.key]?.containsAny(live) == true)
                }
            }
            return newSub
        }


        /** Entry point */
        fun process(): CoreTACProgram {
            logger.info {
                "Beginning assignment of confluence variables ${core.name}"
            }
            g.code.keys.forEach(::assignConfluenceVars)

            logger.info {
                "Inlining/alias resolution process for ${core.name}"
            }
            val roots = g.roots.map { it.ptr.block }
            // The substitution at the beginning of each block
            val blockSubstitution = roots.associateWith { emptySubstitution() }.toMutableMap()

            // For each block we need at least one of its predecessors to be already processed, which is exactly what
            // is guaranteed by reversed post order.
            val order = postOrder(roots, g.blockSucc).reversed()

            for (nbid in order) {
                val sub = processBlock(nbid, blockSubstitution[nbid]!!)

                for (succ in g.succ(nbid)) {
                    val subs = processEdge(nbid, succ, blockToPrePhi[succ].orEmpty(), sub)

                    if (succ !in blockSubstitution) {
                        logger.trace {
                            "Setting successor start $succ as $subs from $nbid"
                        }
                        blockSubstitution[succ] = subs
                    } else {
                        check(blockSubstitution[succ] == subs) {
                            "${blockSubstitution[succ]} vs. $subs at $succ from $nbid"
                        }
                    }
                }
            }

            logger.info {
                "Done: ${core.name}. Beginning graph rewrite..."
            }

            val (code, block) = patcher.toCode(TACCmd.Simple.NopCmd)

            val postDSA = core.copy(
                code = code,
                blockgraph = block,
                symbolTable = core.symbolTable.mergeDecls(newTags.build()).mergeUfs(ufRewrites),
                instrumentationTAC = core.instrumentationTAC.copy(
                    ufAxioms = core.instrumentationTAC.ufAxioms.merge(
                        UfAxioms(newUfAxioms)
                    )
                ),
                check = isTypechecked
            )

            logger.info {
                "Done rewriting graph for ${core.name}, starting last optimizations"
            }

            val final = removeUnusedAssignments(
                code = postDSA,
                expensive = false,
                isErasable = ::isErasable,
                isTypechecked = isTypechecked
            ).let(BlockMerger::mergeBlocks)

            logger.info {
                "Finished ${core.name}."
            }

            return final
        }
    }


    /**
     * Vars in [protectedVars] will maintain their original names and assignments.
     * If [isErasable] if true on a command, it will not erased it even though its not in the cone-of-influence.
     * Vars in [protectCallIndex] will keep their original call index when renamed.
     */
    fun simplify(
        prog: CoreTACProgram,
        protectedVars: Set<TACSymbol.Var> = keywordVars,
        annotate: Boolean = true,
        isTypechecked: Boolean = true,
        isErasable: (TACCmd.Simple.AssigningCmd) -> Boolean = FilteringFunctions.default(prog)::isErasable,
        protectCallIndex: Set<TACSymbol.Var> = emptySet(),
        renaming: TACDSARenaming = TACDSARenaming.default
    ): CoreTACProgram {
        return if (prog.code.isEmpty()) {
            // Crashes on empty bytecode. Wrapping from here, because we don't want to create the worker which computes stuff non-lazily
            prog
        } else {
            Worker(
                prog,
                protectedVars,
                annotate,
                protectCallIndex,
                isErasable,
                renaming,
                isTypechecked
            ).process()
        }
    }

    /**
        Collapse empty dsa assignment blocks (happens if variables are removed)
     */
    fun collapseEmptyAssignmentBlocks(c: CoreTACProgram): CoreTACProgram {
        val toRemove = c.analysisCache.graph.blocks.parallelStream().filter {
            it.commands.first().maybeAnnotation(DSA_BLOCK_START) != null &&
                it.commands.last().maybeAnnotation(DSA_BLOCK_END) != null &&
                c.analysisCache.graph.succ(it).size == 1 &&
                it.commands.none {
                    it.cmd is TACCmd.Simple.AssigningCmd
                }
        }.map { it.id }.collect(Collectors.toSet())

        val nodes = topologicalOrderOrNull(
            toRemove.associateWith {
                c.analysisCache.graph.succ(it).orEmpty() intersect toRemove
            },
            ignoreSelfLoops = true,
        )?.reversed() ?: return c

        return c.patching {patching ->
            nodes.forEach {
                patching.reroutePredecessorsTo(it, c.analysisCache.graph.succ(it).first())
                patching.removeBlock(it)
            }
        }
    }
}
