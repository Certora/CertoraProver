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

package verifier.equivalence.summarization

import analysis.*
import analysis.icfg.Summarization
import analysis.ip.*
import analysis.opt.ConstantComputationInliner
import analysis.opt.PointerSimplification
import bridge.ContractInstanceInSDC
import com.certora.collect.*
import compiler.SolidityFunctionStateMutability
import datastructures.stdcollections.*
import instrumentation.transformers.TACDSA
import scene.IContractWithSource
import scene.TACMethod
import spec.cvlast.QualifiedMethodSignature
import spec.cvlast.SolidityContract
import spec.cvlast.Visibility
import spec.cvlast.typedescriptors.VMValueTypeDescriptor
import tac.MetaKey
import tac.MetaMap
import tac.NBId
import tac.Tag
import utils.*
import vc.data.*
import vc.data.SimplePatchingProgram.Companion.patchForEach
import java.util.stream.Collectors
import kotlin.jvm.optionals.getOrNull

/**
 * Extract "common" pure internal functions from a method. These are pure (as annotated in the original
 * source code and checked by the compiler) functions which appear one or more times within a given method.
 * For one of these functions F, this class determines whether all instances of F have some unified, canonical
 * representation, and if so, what the canonical representation is.
 */
object PureFunctionExtraction {
    interface CallingConvention<R: CallingConvention<R>> {
        val argSymbols: List<TACSymbol>
        val exitVars: List<TACSymbol.Var>
        fun withArgsAndReturns(args: List<TACSymbol>, rets: List<TACSymbol.Var>) : R
        fun bindOutputs(): Pair<List<TACSymbol.Var>, CommandWithRequiredDecls<TACCmd.Simple>>
    }

    /**
     * An instance of a canonicalized function with signature [qual] which starts at [where].
     *
     * The canonicalized program is given in [subProgram].
     *
     * [callingConvention] describes the input/output behavior of the function. For Solidity functions, these are
     * the argument/return symbols. For vyper functions, this will be the the input argument offset
     * and the variable that holds the return location.
     */
    private data class CanonicalizedFunction<R: CallingConvention<R>>(
        val qual: QualifiedMethodSignature,
        val where: LTACCmd,
        val callingConvention: R,
        val subProgram: SimpleCanonicalization.CanonicalProgram,
    )

    /**
     * A dummy annotation intended to keep the exit variables of a program alive to prevent their removal by dead assignment
     * elimination optimizations at the like.
     */
    @KSerializable
    @Treapable
    private data class ExitKeepAlive(
        val exitVars: List<TACSymbol.Var>
    ) : WithSupport, AmbiSerializable {
        override val support: Set<TACSymbol.Var>
            get() = exitVars.toSet()
    }

    abstract class StandardInternalFunctionAnnotator : PureFunctionExtractor<InternalFuncStartAnnotation, InternalFuncExitAnnotation, UnifiedCallingConvention> {
        override fun exitFinder(prog: CoreTACProgram): Summarization.ExitFinder {
            return InternalFunctionExitFinder(prog)
        }

        override val startMeta: MetaKey<InternalFuncStartAnnotation>
            get() = INTERNAL_FUNC_START
        override val endMeta: MetaKey<InternalFuncExitAnnotation>
            get() = INTERNAL_FUNC_EXIT


        override fun toCallingConvention(
            start: InternalFuncStartAnnotation,
            exits: List<InternalFuncExitAnnotation>,
            startSyms: List<TACSymbol>,
            exitVars: List<TACSymbol.Var>
        ): UnifiedCallingConvention {
            require(exits.map {
                it.rets.size
            }.allSame())
            require(start.args.size == startSyms.size)
            /**
             * Ensure that every return symbol i in every exit annotatino
             * has the same location and offset. Do this by computing the set of all
             * pairs of "location and offset" used for return symbol i, and then seeing if that
             * set is singleton.
             */
            val retPayload = exits.map {
                it.rets.map {
                    setOf(it.location to it.offset)
                }
            }.reduce { a, b ->
                a.zip(b) { accumSet, newElems ->
                    accumSet + newElems
                }
            }.monadicMap {
                it.singleOrNull()
            } ?: error("differeng sorts and locations")
            val translatedArgs = start.args.zip(startSyms) { a, sym ->
                a.copy(s = sym)
            }
            val rets = retPayload.zip(exitVars) { (loc, offs), sym ->
                InternalFuncRet(
                    location = loc,
                    s = sym,
                    offset = offs
                )
            }
            return UnifiedCallingConvention(translatedArgs, rets)
        }

        override fun getArgSymbols(l: LTACAnnotation<InternalFuncStartAnnotation>): List<TACSymbol> {
            return l.annotation.args.map {
                it.s
            }
        }

        override fun getExitSymbols(l: LTACAnnotation<InternalFuncExitAnnotation>): List<TACSymbol.Var> {
            return l.annotation.rets.map { it.s }
        }


    }

    object VyperExtractor : StandardInternalFunctionAnnotator() {
        override fun accept(sig: QualifiedMethodSignature, src: ContractInstanceInSDC): Boolean {
            return sig.resType.singleOrNull() is VMValueTypeDescriptor
        }
    }


    private val exitKeepAliveMeta = MetaKey<ExitKeepAlive>("keep.alive")

    /**
     * An inclusion predicate used to select which commands are included by [SimpleCanonicalization].
     */
    private fun canonicalizationInclusionPredicate(lc: LTACCmd) : Boolean {
        return when(lc.cmd) {
            is TACCmd.Simple.AnnotationCmd,
            is TACCmd.Simple.LabelCmd,
            is TACCmd.Simple.SummaryCmd,
            is TACCmd.Simple.JumpdestCmd,
            is TACCmd.Simple.JumpCmd,
            TACCmd.Simple.NopCmd -> false
            is TACCmd.Simple.LogCmd,
            is TACCmd.Simple.AssertCmd,
            is TACCmd.Simple.AssigningCmd,
            is TACCmd.Simple.Assume,
            is TACCmd.Simple.ByteLongCopy,
            is TACCmd.Simple.CallCore,
            is TACCmd.Simple.JumpiCmd,
            is TACCmd.Simple.ReturnCmd,
            is TACCmd.Simple.ReturnSymCmd,
            is TACCmd.Simple.RevertCmd,
            is TACCmd.Simple.WordStore -> true
        }
    }

    /**
     * Represents one or more [CanonicalizedFunction] which all have the exact same canonicalized body ([equivClass])
     * (as determined by [SimpleCanonicalization.CanonicalProgram.equivTo]) and the same [callingConvention].
     *
     * Intuitively, this class represents "all bodies of a function called with the same combination of constant/non-constant args"
     *
     * NB that if multiple [CanonicalizedFunction] are grouped together with the same calling conventions, that doesn't mean the
     * argument *values* are always the same, simply that all all of the function bodies accessed their arguments
     * (whether symbolic or constant) in the same way.
     */
    private data class EquivalenceClass<R: CallingConvention<R>>(
        val equivClass: SimpleCanonicalization.CanonicalProgram,
        val callingConvention: R
    ) {
        fun includes(other: CanonicalizedFunction<R>) : Boolean {
            return equivClass equivTo other.subProgram && this.callingConvention == other.callingConvention
        }
    }

    private fun <R: CallingConvention<R>> Collection<EquivalenceClass<R>>.equivOrNull() : EquivalenceClass<R>? {
        var curr : EquivalenceClass<R>?= null
        for(i in this) {
            if(curr == null) {
                curr = i
            } else if(curr.callingConvention != i.callingConvention || !(curr.equivClass equivTo i.equivClass)) {
                return null
            }
        }
        return curr
    }

    /**
     * Given a canonical representation of some [EquivalenceClass], run some optimizations and then recanonicalize
     * the function [f].
     *
     * The optimizations applied to this program are [TACDSA], [ConstantComputationInliner], and [PointerSimplification].
     * The commands in [prefix] are appended to the materialized canonicalized program before any of these
     * optimizations are run.
     */
    private fun optimizeAndRecanonicalizeForMatch(f: EquivalenceClass<*>, prefix: List<TACCmd.Simple> = listOf()): SimpleCanonicalization.CanonicalProgram {
        val meta = TACCmd.Simple.AnnotationCmd(
            exitKeepAliveMeta,
            ExitKeepAlive(f.callingConvention.exitVars)
        )
        val r = f.equivClass.code.prependToBlock0(prefix).appendToSinks(CommandWithRequiredDecls(meta))
        return standardReoptimizePipeline(
            r
        ) {
            SimpleCanonicalization.canonicalize(
                it,
                start = CmdPointer(it.entryBlockId, 0),
                forceVariableEquiv = listOf(),
                end = { _ ->  false },
                excludeStart = false,
                include = ::canonicalizationInclusionPredicate,
                variableCanonicalizer = environmentCanonicalizer
            )
        }
    }

    @KSerializable
    private data class StartKeepAlive(
        val symbols: List<TACSymbol>
    ) : TransformableVarEntityWithSupport<StartKeepAlive>, AmbiSerializable {
        override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var): StartKeepAlive {
            return StartKeepAlive(symbols.map { sym ->
                (sym as? TACSymbol.Var)?.let(f) ?: sym
            })
        }

        override val support: Set<TACSymbol.Var>
            get() = symbols.mapNotNullToSet { it as? TACSymbol.Var }

        companion object {
            val META_KEY = MetaKey<StartKeepAlive>("canon.start.symbols")
        }
    }

    private fun <R: CallingConvention<R>> optimizeForGrouping(
        f: EquivalenceClass<R>
    ) : EquivalenceClass<R> {
        val withKeepAlive = f.equivClass.code.getEndingBlocks().map {
            f.equivClass.code.analysisCache.graph.elab(it).commands.last()
        }.filter {
            !it.cmd.isHalting()
        }.mapToSet { it.ptr }.stream().patchForEach(f.equivClass.code) { exitSite ->
            replace(exitSite) { cmd ->
                listOf(
                    cmd,
                    TACCmd.Simple.AnnotationCmd(
                        exitKeepAliveMeta,
                        ExitKeepAlive(f.callingConvention.exitVars)
                    )
                )
            }
        }.prependToBlock0(listOf(TACCmd.Simple.AnnotationCmd(
            StartKeepAlive.META_KEY,
            StartKeepAlive(f.callingConvention.argSymbols)
        )))

        return standardReoptimizePipeline(withKeepAlive) { preCanon ->
            val toCanonStart = preCanon.parallelLtacStream().mapNotNull {
                it.maybeAnnotation(StartKeepAlive.META_KEY)
            }.findFirst().getOrNull()?.symbols ?: error("standard pipeline deleted start annotation, this is a fatal error")

            val canonEnds = preCanon.parallelLtacStream().mapNotNull {
                it.maybeAnnotation(exitKeepAliveMeta)?.exitVars
            }.toList().toEquivClasses { it }

            canonicalizeWithIO(
                preCanon,
                startArgs = toCanonStart,
                funcExits = canonEnds,
                start = CmdPointer(preCanon.getStartingBlock(), 0),
                end = { _ -> false }
            ) { prog, canonArg, canonExit ->
                EquivalenceClass(
                    equivClass = prog,
                    callingConvention = f.callingConvention.withArgsAndReturns(
                        canonArg, canonExit
                    )
                )
            }
        }
    }

    private fun <T> Collection<T>.toEquivClasses(ext: (T) -> List<TACSymbol.Var>) : List<Set<TACSymbol.Var>> {
        val equivs = mutableListOf<MutableSet<TACSymbol.Var>>()
        for(exit in this) {
            for((ind, r) in ext(exit).withIndex()) {
                if(ind >= equivs.size) {
                    equivs.add(mutableSetOf())
                }
                equivs[ind].add(r)
            }
        }
        return equivs
    }

    private fun <R> standardReoptimizePipeline(
        r: CoreTACProgram,
        final: (CoreTACProgram) -> R
    ) : R {
        return TACDSA.simplify(r, protectedVars = setOf(), protectCallIndex = setOf(), annotate = false, isTypechecked = true)
            .let(ConstantComputationInliner::rewriteConstantCalculations)
            .let(PointerSimplification::simplify)
            .let {
                final(it)
            }
    }

    private val pseudoStorage = TACSymbol.Var("storage", Tag.WordMap, NBId.ROOT_CALL_ID, MetaMap(TACMeta.NO_CALLINDEX))

    private val environmentCanonicalizer = SimpleCanonicalization.VariableCanonicalizer { v, name, fresh ->
        if(TACMeta.EVM_MEMORY in v.meta) {
            TACSymbol.Var(name, Tag.ByteMap, NBId.ROOT_CALL_ID, MetaMap(TACMeta.EVM_MEMORY))
        } else if(TACSymbol.Var.KEYWORD_ENTRY in v.meta) {
            if(v.meta[TACSymbol.Var.KEYWORD_ENTRY]!!.maybeTACKeywordOrdinal?.let {
                TACKeyword.entries[it]
                } == TACKeyword.STACK_HEIGHT) {
                return@VariableCanonicalizer null
            }
            v
        } else if(TACMeta.STORAGE_KEY in v.meta) {
            pseudoStorage
        } else if(TACMeta.MCOPY_BUFFER in v.meta) {
            TACSymbol.Var("mcopyBuff${fresh()}", Tag.ByteMap, NBId.ROOT_CALL_ID, MetaMap(TACMeta.MCOPY_BUFFER))
        } else {
            null
        }
    }

    private fun <R> canonicalizeWithIO(
        prog: CoreTACProgram,
        startArgs: List<TACSymbol>,
        funcExits: List<Set<TACSymbol.Var>>,
        start: CmdPointer,
        end: (LTACCmd) -> Boolean,
        mk: (SimpleCanonicalization.CanonicalProgram, List<TACSymbol>, List<TACSymbol.Var>) -> R
    ) : R {
        /**
         * Canonicalize the function body
         */
        val canon = SimpleCanonicalization.canonicalize(
            prog,
            start,
            excludeStart = true,
            forceVariableEquiv = funcExits,
            include = ::canonicalizationInclusionPredicate,
            variableCanonicalizer = environmentCanonicalizer
        ) { maybeExit ->
            end(maybeExit)
        }
        val exitVars = funcExits.map { repr ->
            canon.variableMapping(repr.first())!!
        }
        val mca = prog.analysisCache[MustBeConstantAnalysis]

        val argVars = startArgs.mapIndexed { idx, internalArg ->
            when (internalArg) {
                is TACSymbol.Const -> internalArg
                is TACSymbol.Var -> {
                    canon.variableMapping(internalArg) ?: mca.mustBeConstantAt(
                        start, internalArg
                    )?.asTACSymbol() ?: TACSymbol.Var("certora!Unused$idx", Tag.Bit256)
                }
            }
        }
        return mk(canon, argVars, exitVars)
    }

    /**
     * Given multiple [CanonicalizedFunction] instances with the same signature,
     * attempt to extract their canonical representation.
     *
     * Roughly, all of the [CanonicalizedFunction] are grouped into [EquivalenceClass]
     * based on the criteria described there. If we have a single equivalence class,
     * then we are done and select that [EquivalenceClass.equivClass]'s as the canonical
     * representation.
     *
     * If there are multiple possible groups, we try two strategies.
     *
     * First, we heuristically identify if one of the equivalence classes has the most general form:
     * this is the equivalence class with the most symbolic (variable) inputs. Call this equivalence
     * class G. We then check whether all of the remaining classes are simply specializations of G due to constant inlining.
     * Specifically, we check whether all other equivalence classes have constants c1, c2, ... as arguments where G has
     * variables v1, v2, ... Let C be one such equivalence class. We compute `G[c1/v1, c2/v2,...]`, and then check to see whether
     * the simplification and recanonicalization of `G[c1/v1,c2/v2,...]` matches C. If this check succeeds, we conclude that
     * G is the most general, canonical form of the function.
     *
     * If the above process fails, we check if the different function bodies were just optimized differently. We do
     * this by simply reoptimizing and recanonicalizing (via [optimizeForGrouping]) all the equivalence classes
     * and then checking whether this yields a single result. If so, we return that unique result.
     *
     * Otherwise, we return null, indicating we could not infer a unique, canonical representation for all functions in [canoned].
     */
    private fun <R: CallingConvention<R>> canonicalizeGroup(
        canoned: List<CanonicalizedFunction<R>>
    ) : EquivalenceClass<R>? {
        val equiv = mutableListOf<EquivalenceClass<R>>()
        for(p in canoned) {
            if(equiv.any { it.includes(p) }) {
                continue
            }
            equiv.add(EquivalenceClass(
                callingConvention = p.callingConvention,
                equivClass = p.subProgram
            ))
        }

        /**
         * Easy case, only one equivalence class, we can call this the canonical representation of this
         * function (within some external method body).
         */
        if(equiv.size == 1) {
            return equiv.single()
        }

        /**
         * What is the most symbolic arguments?
         */
        val mostVars = equiv.maxOf { repr ->
            repr.callingConvention.argSymbols.count { sym -> sym is TACSymbol.Var }
        }

        /**
         * Is there a single equivalence class with that count?
         */
        val mostGeneralForm = equiv.singleOrNull { eq ->
            eq.callingConvention.argSymbols.count { sym ->
                sym is TACSymbol.Var
            } == mostVars
        }

        /**
         * If not, we fallback here on the "maybe things were just optimized differently" check,
         * by simply re-optimizing and re-canonicalizing every equivalence class,
         * and crossing our fingers this gives a unique result.
         */
        if(mostGeneralForm == null) {
            // one last try
            return equiv.map {
                optimizeForGrouping(it)
            }.equivOrNull()
        }
        /**
         * Otherwise, see if the other equivalence classes are just specializations of mostGeneralForm
         */
        for(eq in equiv) {
            if(eq.equivClass equivTo mostGeneralForm.equivClass) {
                continue
            }
            if(eq.callingConvention.argSymbols.size != mostGeneralForm.callingConvention.argSymbols.size) {
                return null
            }
            val zipped = mostGeneralForm.callingConvention.argSymbols.zip(eq.callingConvention.argSymbols)

            /**
             * This prefix is passed to [optimizeAndRecanonicalizeForMatch] for the general form, and effects the
             * assignment of constant arguments of `eq` to the symbolic arguments in `mostGeneralForm`.
             *
             */
            val assignPrefix = mutableListOf<TACCmd.Simple>()
            /**
             * In the following `l` is the `mostGeneralForm` argument, and `r` is the argument used
             * in `eq`.
             */
            for((l, r) in zipped) {
                /**
                 * If `eq` and `mostGeneralForm` agree on this argument, skip
                 */
                if(l == r) {
                    continue
                }
                /**
                 * If the disagree on the name of the argument, this is unrecoverable, and
                 * we give up
                 */
                if(l is TACSymbol.Var && r is TACSymbol.Var) {
                    return null
                }
                /**
                 * If `mostGeneralForm` had a constant where `eq` has a variable OR a different constant,
                 * that is also unrecoverable.
                 */
                if(l is TACSymbol.Const) {
                    return null
                }
                /**
                 * you can convince yourself that the following must succeed: l is not a constant (and is
                 * thus a variable) and r is not a variable (otherwise the above `return null` would fire)
                 * and thus must be a constant.
                 */
                check(l is TACSymbol.Var && r is TACSymbol.Const) {
                    "Broken type hierarchy: $l vs $r"
                }
                /**
                 * Assign the constant used in `eq` to the symbolic argument used in `mostGeneralForm`.
                 */
                assignPrefix.add(
                    TACCmd.Simple.AssigningCmd.AssignExpCmd(
                        lhs = l,
                        rhs = r.asSym()
                    )
                )
            }
            /**
             * Recanonicalize `mostGeneralForm` using these constant assignments, which will be folded into the body of
             * the function by [ConstantComputationInliner] used by the [optimizeAndRecanonicalizeForMatch].
             */
            val newCanon1 = optimizeAndRecanonicalizeForMatch(mostGeneralForm, assignPrefix)

            /**
             * optimize and recanonicalize. We need to do this here because due canonicalization, the optimizations
             * done in [optimizeAndRecanonicalizeForMatch] can still make non-trivial changes unrelated to the constant
             * prefix. Thus, we need those same "unrelated to constant arguments" optimizations to be applied
             * to `eq` for an apples to apples comparison.
             */
            val newCanon2 = optimizeAndRecanonicalizeForMatch(eq, listOf())
            /**
             * Well, even after this, we still do not have a match. give up
             */
            if(!(newCanon1 equivTo newCanon2)) {
                return null
            }
        }
        return mostGeneralForm
    }

    /**
     * Indicates that all instances of [sig] within some external program have a canonical representation of
     * [prog]. NB this comes with some caveats, given the handling of specialization described in [canonicalizeGroup]
     */
    data class CanonFunction<R: CallingConvention<R>>(
        val sig: QualifiedMethodSignature,
        val prog: SimpleCanonicalization.CanonicalProgram,
        val callingConvention: R
    )

    object SolidityExtractor : StandardInternalFunctionAnnotator() {

        override fun accept(sig: QualifiedMethodSignature, src: ContractInstanceInSDC): Boolean {
            return sig.paramTypes.all {
                it is VMValueTypeDescriptor
            } && sig.resType.all {
                it is VMValueTypeDescriptor
            } && src.internalFunctions.any { (_, m) ->
                m.method.toMethodSignature(SolidityContract(m.declaringContract), Visibility.INTERNAL).matchesNameAndParams(sig) && m.method.stateMutability == SolidityFunctionStateMutability.pure
            }
        }
    }

    /**
     * Given a set of [pureInternalSigs] that are expected to appear in [prog],
     * compute which can be assigned a canonical representation as represented by the [CanonFunction]
     * objects in the returned list.
     */
    private fun <T: InternalFunctionStartAnnot, U: InternalFunctionExitAnnot, R: CallingConvention<R>> findCanonicalRepresentationFor(
        prog: CoreTACProgram,
        pureInternalSigs: Collection<QualifiedMethodSignature>,
        extractor: PureFunctionExtractor<T, U, R>
    ) : List<CanonFunction<R>> {
        val exits = extractor.exitFinder(prog)
        val canoned = prog.parallelLtacStream().mapNotNull {
            it.annotationView(extractor.startMeta)
        }.filter { startAnnot ->
            pureInternalSigs.any {
                it.matchesNameAndParams(startAnnot.annotation.which)
            }
        }.map { start ->
            val where = start.ptr
            val funcExits = exits.getExits(calleeId = start.annotation.id, startPtr = where).map {
                it.annotationView(extractor.endMeta)!!
            }
            /**
             * For all exit sites, ensure the variables assigned to the exit variables are given the same canonical name.
             *
             * This is *technically* unsound, as seen in the following example:
             *
             * ```
             * r4 = 3
             * r2 = 5
             * if(*) {
             *    return r4
             * } else {
             *    return r2
             * }
             * ```
             *
             * by unifying `r2` and `r4` we will get:
             * ```
             * V1 = 3
             * V1 = 5
             * if(*) {
             *    return V1
             * } else {
             *    return V1
             * }
             * ```
             *
             * However, this sort of variable reuse is not expected in practice thanks to DSA.
             */
            val equivs = funcExits.toEquivClasses { exit ->
                extractor.getExitSymbols(exit)
            }
            canonicalizeWithIO(
                prog = prog,
                start = where,
                funcExits = equivs,
                end = { maybeExit -> maybeExit.maybeAnnotation(extractor.endMeta)?.id == start.annotation.id },
                startArgs = extractor.getArgSymbols(start)
            ) { prog, startSyms, exitVars ->
                val callingConv = extractor.toCallingConvention(
                    start.annotation, funcExits.map { it.annotation }, startSyms, exitVars
                )

                CanonicalizedFunction(
                    callingConvention = callingConv,
                    qual = start.annotation.which,
                    where = start.wrapped,
                    subProgram = prog
                )
            }
        }.collect(Collectors.groupingBy {
            it.qual.qualifiedMethodName to it.qual.paramTypes
        })

        /**
         * canoned now holds all of the [CanonicalizedFunction] grouped by signature. For each such
         * group, try to extract the canonical representation via [canonicalizeGroup]
         */
        val toRet = mutableListOf<CanonFunction<R>>()
        for((_, subFuncs) in canoned) {
            val repr = canonicalizeGroup(subFuncs) ?: continue
            toRet.add(
                CanonFunction(
                    sig = subFuncs.first().qual,
                    prog = repr.equivClass,
                    callingConvention = repr.callingConvention
                )
            )
        }
        return toRet
    }

    interface PureFunctionExtractor<T: InternalFunctionStartAnnot, U: InternalFunctionExitAnnot, R: CallingConvention<R>> {
        fun exitFinder(prog: CoreTACProgram) : Summarization.ExitFinder
        fun getExitSymbols(l: LTACAnnotation<U>) : List<TACSymbol.Var>
        fun getArgSymbols(l: LTACAnnotation<T>) : List<TACSymbol>

        val startMeta: MetaKey<T>
        val endMeta: MetaKey<U>

        fun accept(sig: QualifiedMethodSignature, src: ContractInstanceInSDC) : Boolean

        fun toCallingConvention(
            start: T,
            exits: List<U>,
            startSyms: List<TACSymbol>,
            exitVars: List<TACSymbol.Var>
        ) : R
    }

    /**
     * Within [m], find internal functions which are:
     * 1. Pure (according to the solidity annotation)
     * 2. Have only scalar input/output types
     * 3. Have some canonical representation.
     */
    fun <T: InternalFunctionStartAnnot, U: InternalFunctionExitAnnot, R: CallingConvention<R>> canonicalPureFunctionsIn(m: TACMethod, extractor: PureFunctionExtractor<T, U, R>) : List<CanonFunction<R>> {
        val src = (m.getContainingContract() as? IContractWithSource)?.src ?: return listOf()
        val prog = m.code as CoreTACProgram
        val res = prog.parallelLtacStream().mapNotNull {
            it.maybeAnnotation(extractor.startMeta)?.which
        }.filter { sig ->
            extractor.accept(sig, src)
        }.collect(Collectors.toSet())
        return findCanonicalRepresentationFor(
            prog, res, extractor
        )
    }
}
