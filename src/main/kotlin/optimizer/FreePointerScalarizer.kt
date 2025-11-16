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

package optimizer

import analysis.*
import analysis.CommandWithRequiredDecls.Companion.withDecls
import analysis.icfg.ExpressionSimplifier
import analysis.numeric.*
import analysis.opt.*
import analysis.opt.intervals.*
import analysis.opt.numeric.*
import analysis.opt.numeric.VROInt.Companion.nondetOf
import analysis.worklist.*
import bridge.SourceLanguage
import com.certora.collect.*
import config.Config
import datastructures.stdcollections.*
import disassembler.DisassembledEVMBytecode
import tac.*
import utils.*
import vc.data.*
import vc.data.SimplePatchingProgram.Companion.patchForEach
import vc.data.TACMeta.CONSTANT_SCALARIZATION
import vc.data.tacexprutil.*
import java.math.BigInteger

/**
 * Replace any remaining loads and stores into memory where the location is definitely 0x40 with the scalar
 * [TACKeyword.MEM64], representing the reserved slot for the free memory pointer.
 */

val FREE_POINTER_SCALARIZED = MetaKey<Boolean>("fps.free-pointer-is-scalarized")

val NO_MONOTONICITY_ASSUMPTIONS = MetaKey.Nothing("no.monotonicity.assumptions")

private val CONDITIONAL_SCALARIZATION = MetaKey.Nothing("conditional.scalarization")


object FreePointerScalarizer {
    fun normalizeFPAccess(c: CoreTACProgram) : CoreTACProgram {
        val mca = MustBeConstantAnalysis(
            c.analysisCache.graph,
            NonTrivialDefAnalysis(c.analysisCache.graph)
        )
        return c.ltacStream().filter { lt ->
            (lt.cmd as? TACCmd.Simple.DirectMemoryAccessCmd)?.takeIf {
                it.base == TACKeyword.MEMORY.toVar()
            }?.loc?.let {it as? TACSymbol.Var }?.let { v ->
                mca.mustBeConstantAt(lt.ptr, v)
            } == 0x40.toBigInteger()
        }.patchForEach(c, false) {
            when(it.cmd) {
                is TACCmd.Simple.AssigningCmd.ByteLoad -> this.replaceCommand(it.ptr, listOf(it.cmd.copy(
                    loc = 0x40.asTACSymbol()
                )))
                is TACCmd.Simple.AssigningCmd.ByteStore -> this.replaceCommand(it.ptr, listOf(it.cmd.copy(
                    loc = 0x40.asTACSymbol()
                )))
                else -> `impossible!`
            }
        }
    }

    private fun patchMEM64(
        g: TACCommandGraph,
        bytecode: DisassembledEVMBytecode,
        expSimplifier: ExpressionSimplifier,
        patch: SimplePatchingProgram
    ) {
        val freeMemoryPointerReservedOffset = BigInteger.valueOf(64)
        var patched = false
        g.commands
            .asSequence()
            .filter { it.cmd is TACCmd.Simple.DirectMemoryAccessCmd && it.cmd !is TACCmd.Simple.AssigningCmd.ByteStoreSingle }
            .forEach { lcmd ->
                lcmd.cmd as TACCmd.Simple.DirectMemoryAccessCmd // safe due to filter above
                if (lcmd.cmd.base == TACKeyword.MEMORY.toVar()) {
                    val simplifiedLoc = expSimplifier.simplify(lcmd.cmd.loc, lcmd.ptr, true).getAsConst()
                    if (simplifiedLoc == freeMemoryPointerReservedOffset) {
                        patched = true

                        when (lcmd.cmd) {
                            is TACCmd.Simple.AssigningCmd.ByteStore -> {
                                val mstoreCmd = lcmd.cmd
                                patch.replaceCommand(
                                    lcmd.ptr,
                                    listOf(
                                        TACCmd.Simple.AssigningCmd.AssignExpCmd(
                                            TACKeyword.MEM64.toVar(),
                                            mstoreCmd.value,
                                            mstoreCmd.meta
                                        )
                                    )
                                )
                            }

                            is TACCmd.Simple.AssigningCmd.ByteLoad -> {
                                val mloadCmd = lcmd.cmd
                                patch.replaceCommand(
                                    lcmd.ptr,
                                    listOf(
                                        TACCmd.Simple.AssigningCmd.AssignExpCmd(
                                            mloadCmd.lhs,
                                            TACKeyword.MEM64.toVar(),
                                            mloadCmd.meta
                                        )
                                    )
                                )
                            }

                            else -> {}
                        }
                    }
                }
            }

        if (patched) {
            patch.addVarDecl(TACKeyword.MEM64.toVar())
        }
        if(!Config.EquivalenceCheck.get()) {
            patchScalarsDueToLongCopy(g, bytecode, expSimplifier, patch)
        }
    }

    private sealed class Scalarization {
        object Nothing : Scalarization()
        data class ConstantCopy(val c: BigInteger) : Scalarization()
        object Expr : Scalarization()
    }

    private fun patchScalarsDueToLongCopy(
        g: TACCommandGraph,
        bytecode: DisassembledEVMBytecode,
        expSimplifier: ExpressionSimplifier,
        patch: SimplePatchingProgram
    ) {
        // Some useful BigInteger constants
        val `0x0` = 0x0.toBigInteger()
        val `0x20` = 0x20.toBigInteger()
        val `0x60` = 0x60.toBigInteger()

        val mca = MustBeConstantAnalysis(graph = g, wrapped = NonTrivialDefAnalysis(g))

        fun interpretAt(s: TACSymbol, where: LTACCmd): BigInteger? =
            expSimplifier.simplify(s, where.ptr, true).getAsConst() ?: mca.mustBeConstantAt(where.ptr, s)

        /**
         * Updates the scalarizer to also instrument long reads (identified by the LongAccesses interface) that might
         * need to observe values of the scalarized slots. If that is the case, we just in time write the scalars into the location they mirror.
         */
        val toInsertBefore = mutableMapOf<CmdPointer, CommandWithRequiredDecls<TACCmd.Simple>>()

        g.commands.mapNotNull { lc ->
            lc `to?` (lc.cmd as? TACCmd.Simple.LongAccesses)?.accesses?.filter {
                // about to be handled below
                !it.isWrite && it.base == TACKeyword.MEMORY.toVar()
            }?.takeIf(List<TACCmd.Simple.LongAccess>::isNotEmpty)
        }.forEach { (where, accList) ->
            if(where.cmd is TACCmd.Simple.AssigningCmd.AssignSha3Cmd) {
                return@forEach
            }
            val toAdd = MutableCommandWithRequiredDecls<TACCmd.Simple>()


            for(access in accList) {
                val start = interpretAt(access.offset, where) ?: continue
                val len = interpretAt(access.length, where)
                if(len == `0x0`) {
                    continue
                }
                if(start >= `0x60`) {
                    continue
                }
                fun checkOverlap(
                    scalar: TACSymbol.Var
                ) {
                    val offs = scalar.meta[TACMeta.RESERVED_MEMORY_SLOT] ?: error("Invariant broken: trying to scalarize memory for variable $scalar but it doesn't have the meta?")
                    if(offs !in (start - 31.toBigInteger()..(start + (len?.minus(BigInteger.ONE) ?: `0x60`)))) {
                        return
                    }
                    toAdd.extend(listOf(
                        TACCmd.Simple.AssigningCmd.ByteStore(
                            base = TACKeyword.MEMORY.toVar(),
                            loc = offs.asTACSymbol(),
                            value = scalar
                        )
                    ), scalar, TACKeyword.MEMORY.toVar())
                }
                checkOverlap(TACKeyword.MEM0.toVar())
                checkOverlap(TACKeyword.MEM32.toVar())
                checkOverlap(TACKeyword.MEM64.toVar())
            }
            if(!toAdd.isEmpty()) {
                toInsertBefore[where.ptr] = toAdd.toCommandWithRequiredDecls()
            }
        }

        g.commands.mapNotNull {
            it `to?` (it.cmd as? TACCmd.Simple.WithLongCopy)?.takeIf { it.copy.dest.base == TACKeyword.MEMORY.toVar() }
        }.forEach { (longcopyLcmd, longcopyCmd) ->
            /**
               Q: Why use the [MustBeConstantAnalysis] when we have the [ExpressionSimplifier]?
               A: The [ExpressionSimplifier] does *not* like disjunctive reasoning, i.e., it doesn't do well reasoning
               about multiple possible definition sites. The [MustBeConstantAnalysis] however handles it just fine, provided
               all of those definition sites yield a constant.
             */
            val startTargetRange = interpretAt(longcopyCmd.copy.dest.offset, longcopyLcmd)
            val startSourceRange = interpretAt(longcopyCmd.copy.source.offset, longcopyLcmd)
            val sz = interpretAt(longcopyCmd.copy.length, longcopyLcmd)
            if (startTargetRange == null || startTargetRange !in `0x0`..<`0x60` || sz == `0x0`) {
                return@forEach
            }
            // there are so many options here, trying to be sound but also useful. this means sometimes updating
            // where havocing may make more sense (e.g. partial-word writes)

            fun MutableList<CommandWithRequiredDecls<TACCmd.Simple>>.updateScalar(
                scalar: TACSymbol.Var
            ) : Scalarization {
                val scalarOffset = scalar.meta[TACMeta.RESERVED_MEMORY_SLOT] ?: error("Something is very wrong, scalar $scalar doesn't have the meta info?")
                if (scalarOffset !in startTargetRange..<(startTargetRange+(sz?:`0x60`))) {
                    return Scalarization.Nothing
                }
                val offsetDelta = scalarOffset - startTargetRange

                /*
                    valueExpr = if (word-aligned) { load(sourceOffset + offsetDelta) } else { havoc }
                 */
                val (valueExpr, constValue) = if (startTargetRange.mod(`0x20`) == `0x0`) {
                    // For constant offset in CODEDATA, get the constant value
                    runIf(TACMeta.CODEDATA_KEY in longcopyCmd.copy.source.base.meta) codedata@{
                        val sourceOffset = startSourceRange?.plus(scalarOffset)?.toIntOrNull()
                            ?: return@codedata null
                        bytecode.bytes.toPositiveBigIntegerOrNull(sourceOffset, 32)?.let {
                            it.asTACExpr to it
                        }
                    } ?: run {
                        val tmpValue = TACKeyword.TMP(Tag.Bit256, "!scalarValue")
                        if (offsetDelta == `0x0`) {
                            this += listOf(
                                TACCmd.Simple.AssigningCmd.ByteLoad( // read from srcOffset
                                    tmpValue,
                                    longcopyCmd.copy.source.offset,
                                    longcopyCmd.copy.source.base
                                ),
                            ).withDecls(tmpValue)
                        } else {
                            val tmpOffset = TACKeyword.TMP(Tag.Bit256, "!readOffset")
                            this += listOf(
                                TACCmd.Simple.AssigningCmd.AssignExpCmd(
                                    tmpOffset,
                                    TACExpr.Vec.Add(longcopyCmd.copy.source.offset.asSym(), offsetDelta.asTACExpr, Tag.Bit256)
                                ),
                                TACCmd.Simple.AssigningCmd.ByteLoad( // read from srcOffset
                                    tmpValue,
                                    tmpOffset,
                                    longcopyCmd.copy.source.base
                                )
                            ).withDecls(tmpValue, tmpOffset)
                        }
                        tmpValue.asSym() to null
                    }
                } else {
                    TACExpr.Unconstrained(Tag.Bit256) to null
                }

                /*
                    if (length > offsetDelta) { scalar = valueExpr }
                 */
                if (sz != null) {
                    // Length is constant; just do the check here.
                    if (sz > offsetDelta) {
                        this += listOf(
                            TACCmd.Simple.AssigningCmd.AssignExpCmd(scalar, valueExpr)
                        ).withDecls(scalar)
                        return constValue?.let(Scalarization::ConstantCopy) ?: Scalarization.Expr
                    } else {
                        return Scalarization.Nothing
                    }
                } else {
                    // Add the length check in the TAC.  Note that we may still be able to eliminate this check,
                    // after DSA has run and we can do deeper analysiss.  We do this in `cleanup`.
                    val tmpCondition = TACKeyword.TMP(Tag.Bool, "!condition")
                    this += listOf(
                        TACCmd.Simple.AssigningCmd.AssignExpCmd(
                            tmpCondition,
                            TACExpr.BinRel.Gt(longcopyCmd.copy.length.asSym(), offsetDelta.asTACExpr),
                        ),
                        TACCmd.Simple.AssigningCmd.AssignExpCmd(
                            scalar,
                            TACExpr.TernaryExp.Ite(
                                tmpCondition.asSym(),
                                valueExpr,
                                scalar.asSym()
                            ),
                            meta = MetaMap(CONDITIONAL_SCALARIZATION)
                        )
                    ).withDecls(tmpCondition, scalar)
                    return Scalarization.Expr
                }
            }

            val scalarResults = mutableListOf<Scalarization>()
            val updates = CommandWithRequiredDecls.mergeMany(
                buildList {
                    this += CommandWithRequiredDecls(
                        TACCmd.Simple.LabelCmd(
                            "→ Scalarizing for longcopy targeting offset $startTargetRange, size $sz, source offset ${longcopyCmd.copy.source.offset} source base ${longcopyCmd.copy.source.base}"
                        )
                    )

                    scalarResults.add(updateScalar(TACKeyword.MEM0.toVar()))
                    scalarResults.add(updateScalar(TACKeyword.MEM32.toVar()))
                    scalarResults.add(updateScalar(TACKeyword.MEM64.toVar().withMeta(NO_MONOTONICITY_ASSUMPTIONS)))

                    this += CommandWithRequiredDecls(TACCmd.Simple.LabelCmd("←"))
                }
            )
            val prefix = toInsertBefore[longcopyLcmd.ptr]
            val constantScalar = (scalarResults.filter { it !is Scalarization.Nothing }.singleOrNull() as? Scalarization.ConstantCopy)?.c
            val copyAnnot = if(constantScalar != null) {
                longcopyLcmd.cmd.mapMeta {
                    it + (CONSTANT_SCALARIZATION to constantScalar)
                }
            } else {
                longcopyLcmd.cmd
            }
            if(prefix != null) {
                patch.replaceCommand(longcopyLcmd.ptr, prefix.merge(copyAnnot).merge(updates))
                toInsertBefore.remove(longcopyLcmd.ptr)
            } else {
                patch.replaceCommand(longcopyLcmd.ptr, CommandWithRequiredDecls(copyAnnot).merge(updates))
            }
        }
        for((where, prefix) in toInsertBefore) {
            patch.addBefore(where, prefix)
        }
    }

    /*
     * Bump allocator detection heuristic.
     *
     * This code needs to differentiate between four different cases:
     * 1) All allocations are done via bump allocator (e.g. bog standard Solidity)
     * 2) All allocations are done via constant offset allocation (e.g. Vyper)
     * 3) Allocations are done via bump allocator and then we switch to constant offset (Solitidy with inline assembly)
     * 4) Allocations are done via bump allocator, but there are none/one allocation.
     *  (We have a lot of code that depends on MEM64 conversion that we would have to rewrite if we didn't handle this case)
     *
     * We assume a "bump" to be an assignment to mem[0x40] of a some non-trivial function of a previously loaded value of mem[0x40]
     *
     * In other words, if we ever store into mem[0x40] some previous value of mem[0x40] plus some constant, we call that a bump
     *
     * The overall algorithm looks like:
     * 1) Assume nothing to start.  Look for an assignment of 0x80 to mem[0x40], which is how the Solidity bump allocator starts its work.
     * 2) If we see an assignment of something other than 0x80, or an assignment to a constant location other than 0x40, inject doubt that this is bump
     * 3) Once we see what looks like an initialization of the bump allocator, we assume bump allocation if we see no further evidence one way or the other
     * 4) Seeing something after the assignment mem[0x40] = 0x80 that looks like a bump of the allocator will result in assuming bump
     * 5) If we see an assignment to a constant offset other than 0x40 after the mem[0x40] = 0x80, but without seeing a bump, assume that that was a coincidence and return not bump alloc
     */
    private object BumpAllocatorHeuristicAnalysis {
        private enum class STATE {
            UNKNOWN,
            SEEN_ASSIGN_80,
            SEEN_NOT_BUMP,
            SEEN_BUMP_ALLOC
        }

        private class BumpAllocatorHeuristicAnalysisWorker(private val code: CoreTACProgram) :
            VisitingWorklistIteration<NBId, STATE, Boolean>() {
            val graph = code.analysisCache.graph
            val expSimplifier = ExpressionSimplifier(code.analysisCache.graph)

            override val scheduler: IWorklistScheduler<NBId> = graph.cache.naturalBlockScheduler

            fun combineStates(l: STATE, r: STATE) : STATE =
                when(l) {
                    STATE.UNKNOWN -> r

                    STATE.SEEN_ASSIGN_80 -> {
                        when (r) {
                            STATE.UNKNOWN -> STATE.SEEN_ASSIGN_80
                            STATE.SEEN_ASSIGN_80 -> STATE.SEEN_ASSIGN_80
                            STATE.SEEN_BUMP_ALLOC -> STATE.SEEN_BUMP_ALLOC
                            STATE.SEEN_NOT_BUMP -> STATE.SEEN_NOT_BUMP
                        }
                    }

                    STATE.SEEN_NOT_BUMP -> {
                        when (r) {
                            STATE.UNKNOWN -> STATE.SEEN_NOT_BUMP
                            STATE.SEEN_ASSIGN_80 -> STATE.SEEN_NOT_BUMP
                            STATE.SEEN_BUMP_ALLOC -> STATE.SEEN_BUMP_ALLOC
                            STATE.SEEN_NOT_BUMP -> STATE.SEEN_NOT_BUMP
                        }
                    }

                    STATE.SEEN_BUMP_ALLOC -> l
                }

            val endBlockState: MutableMap<NBId, STATE> = mutableMapOf()
            val endBlockFPValueVars: MutableMap<NBId, Set<TACSymbol.Var>> = mutableMapOf() // variables that currently contain some value of the free pointer
            val endBlockFPFunctionVars: MutableMap<NBId, Set<TACSymbol.Var>> = mutableMapOf() // variables that currently contain some non-trivial function of the free pointer

            override fun reduce(results: List<STATE>): Boolean {
                /* Assume bump alloc if we see anything that looks like a bump.
                 * Also, if we can't positively identify non-bump behavior, default to bump.
                 * Only if we see no bumps AND see something we identify as non-bump do be we return false
                 */
                val isBumpAlloc = (results.any { it == STATE.SEEN_BUMP_ALLOC } || results.none { it == STATE.SEEN_NOT_BUMP })
                return isBumpAlloc
            }

            fun convStoreToMEM64(ltacCmd: LTACCmd) : TACCmd.Simple.AssigningCmd.ByteStore? {
                if(ltacCmd.cmd is TACCmd.Simple.AssigningCmd.ByteStore &&
                        ltacCmd.cmd.base == TACKeyword.MEMORY.toVar() &&
                        ltacCmd.cmd.loc is TACSymbol.Const &&
                        ltacCmd.cmd.loc.value == 0x40.toBigInteger()) {
                    return ltacCmd.cmd
                }
                return null
            }

            override fun process(it: NBId): StepResult<NBId, STATE, Boolean> {
                var currentState = STATE.UNKNOWN
                val fpValueVars: MutableSet<TACSymbol.Var> = mutableSetOf()
                val functionOfFPVars: MutableSet<TACSymbol.Var> = mutableSetOf()

                graph.pred(it).forEach { blk ->
                    currentState = combineStates(currentState, endBlockState.getOrDefault(blk, STATE.UNKNOWN))
                    fpValueVars.addAll(endBlockFPValueVars.getOrDefault(blk, setOf()))
                    functionOfFPVars.addAll(endBlockFPFunctionVars.getOrDefault(blk, setOf()))
                }

                graph.elab(it).commands.forEach{ ltc ->
                    val liveVars = code.analysisCache.lva.liveVariablesBefore(ltc.ptr)
                    fpValueVars.removeIf { it !in liveVars }
                    functionOfFPVars.removeIf { it !in liveVars }

                    val storeMem64 = convStoreToMEM64(ltc)
                    val newState = when(currentState) {
                        STATE.UNKNOWN -> {
                            if (storeMem64 != null) {
                                if (storeMem64.value is TACSymbol.Const && storeMem64.value.value == 0x80.toBigInteger()) {
                                    STATE.SEEN_ASSIGN_80
                                } else {
                                    STATE.SEEN_NOT_BUMP
                                }
                            } else if(ltc.cmd is TACCmd.Simple.AssigningCmd.ByteStore &&
                                ltc.cmd.base == TACKeyword.MEMORY.toVar() &&
                                ltc.cmd.loc is TACSymbol.Const &&
                                ltc.cmd.loc.value > 0x40.toBigInteger()) {
                                // This covers the case where there is an assigment to a constant offset before seeing an assignment to mem[0x40]
                                STATE.SEEN_NOT_BUMP
                            } else {
                                STATE.UNKNOWN
                            }
                        }
                        STATE.SEEN_ASSIGN_80 -> {
                            if (storeMem64 != null) {
                                /*
                                 * Look for something that looks like a bump.
                                 * A bump is defined as an assignment to mem[0x40] of something that is a non-trivial function
                                 * of some previous value of mem[0x40] after mem[0x40] has been assigned the value 0x80
                                 */
                                val simplifiedValue = expSimplifier.simplify(storeMem64.value, ltc.ptr, true)
                                if(simplifiedValue.getFreeVars().any { it in functionOfFPVars }) {
                                    STATE.SEEN_BUMP_ALLOC
                                } else {
                                    STATE.SEEN_ASSIGN_80
                                }
                            } else if(ltc.cmd is TACCmd.Simple.AssigningCmd.ByteStore &&
                                ltc.cmd.base == TACKeyword.MEMORY.toVar() &&
                                ltc.cmd.loc is TACSymbol.Const &&
                                ltc.cmd.loc.value > 0x40.toBigInteger()) {
                                // This covers the case where the first assignment to the first constant offset available just happens to be 0x80
                                // but it's not actually a bump allocator.
                                STATE.SEEN_NOT_BUMP
                            } else {
                                STATE.SEEN_ASSIGN_80
                            }
                        }
                        STATE.SEEN_BUMP_ALLOC -> STATE.SEEN_BUMP_ALLOC
                        STATE.SEEN_NOT_BUMP -> STATE.SEEN_NOT_BUMP
                    }

                    if(ltc.cmd is TACCmd.Simple.AssigningCmd.ByteLoad &&
                        ltc.cmd.base == TACKeyword.MEMORY.toVar() &&
                        ltc.cmd.loc is TACSymbol.Const &&
                        ltc.cmd.loc.value == 0x40.toBigInteger()) {
                        // This is a potential load of the FP.  Note that the LHS has an FP value and kill other properties of the lhs
                        fpValueVars.add(ltc.cmd.lhs)
                    } else if(ltc.cmd is TACCmd.Simple.AssigningCmd.AssignExpCmd) {
                        // Copy FP valued-ness for a trivial assignment.  Give "function of FP" state to anything non-trival for which RHS is a value or function of the FP
                        val rhsFreeVars = ltc.cmd.getFreeVarsOfRhs()
                        val rhsContainsFPValue = rhsFreeVars.any { it in fpValueVars }
                        val rhsContainsFunctionOfFPValue = rhsFreeVars.any { it in functionOfFPVars }
                        if(ltc.cmd.rhs is TACExpr.Sym) {
                            fpValueVars.takeIf { rhsContainsFPValue }?.add(ltc.cmd.lhs)
                            functionOfFPVars.takeIf { rhsContainsFunctionOfFPValue }?.add(ltc.cmd.lhs)
                        } else if(rhsContainsFPValue || rhsContainsFunctionOfFPValue) {
                            functionOfFPVars.add(ltc.cmd.lhs)
                        }
                    }
                    currentState = combineStates(currentState, newState)
                }

                endBlockState[it] = currentState
                endBlockFPValueVars[it] = fpValueVars
                endBlockFPFunctionVars[it] = functionOfFPVars

                return StepResult.Ok(graph.succ(it), listOf(currentState))
            }
        }

        fun getBumpAllocatorInfo(code: CoreTACProgram) : Boolean {
            return BumpAllocatorHeuristicAnalysisWorker(code).submit(code.analysisCache.graph.rootBlocks.map {it.id})
        }
    }

    /**
     * Gets a parameter [isConstructor] which is set to true when running on constructors.
     * (it can be abused to force patching freememptr to a scalar, but let's not do that please)
     */
    fun doWork(
        code: CoreTACProgram,
        lang: SourceLanguage,
        bytecode: DisassembledEVMBytecode,
        isConstructor: Boolean = false
    ): CoreTACProgram {
        if(!Config.Mem0x0To0x40AsScalar) {
            return code
        }
        val graph = code.analysisCache.graph
        val expSimplifier = ExpressionSimplifier(graph, defaultTo256Bits = true)
        if(graph.roots.isEmpty()) {
            return code
        }
        val patch = code.toPatchingProgram()
        val isBumpAllocator by lazy { BumpAllocatorHeuristicAnalysis.getBumpAllocatorInfo(code) }
        val toScalarize = isConstructor
            || lang == SourceLanguage.Solidity
            || isBumpAllocator

        patch.addBefore(
            graph.roots.first().ptr, listOf(
                TACCmd.Simple.AnnotationCmd(FREE_POINTER_SCALARIZED, toScalarize)
            )
        )

        if (toScalarize) {
            patchMEM64(graph, bytecode, expSimplifier, patch)
        }

        return patch.toCodeNoTypeCheck(code)
    }

    /**
        Post-DSA cleanup of any unneeded conditional scalar updates, to avoid breaking PTA unnecessarily.

        We are primarily interested in scenarios like this one:

        ```
            switch returndatasize()
            case 32 {
                returndatacopy(0, 0, returndatasize())
            }
        ```

        I.e., where the length of the LongCopy is known to be some constant due to a previous branch.
     */
    fun cleanup(code: CoreTACProgram): CoreTACProgram {
        val pathSem = object : SaturatingVROPathSemantics<VROIntMap>() {
            override fun assignVar(
                toStep: VROIntMap,
                lhs: TACSymbol.Var,
                toWrite: VROInt,
                where: LTACCmd
            ) = toStep + (lhs to toWrite)
        }

        val capture = VROIntMap::plus

        val expressionInterpreter = object : VROExpressionInterpreter<VROIntMap>(VROBaseValueSemantics) {
            @Suppress("EXTENSION_SHADOWED_BY_MEMBER")
            override fun VROIntMap.plus(entry: Pair<TACSymbol.Var, VROInt>) = capture(this, entry)
        }


        val stmtInterp = object : VROStatementInterpreter<VROIntMap>(
            expressionInterpreter, pathSem
        ) {
            override fun forget(
                lhs: TACSymbol.Var,
                toStep: VROIntMap,
                input: VROIntMap,
                whole: Any,
                l: LTACCmd
            ) = toStep.forget(lhs)

            @Suppress("EXTENSION_SHADOWED_BY_MEMBER")
            override fun VROIntMap.plus(
                entry: Pair<TACSymbol.Var, VROInt>
            ) = capture(this, entry)
        }

        val interpreter = object : AbstractAbstractInterpreter<VROIntMap, VROIntMap>() {
            override val statementInterpreter: IStatementInterpreter<VROIntMap, VROIntMap> = stmtInterp
            override val pathSemantics: IPathSemantics<VROIntMap, VROIntMap> = pathSem

            override fun project(l: LTACCmd, w: VROIntMap) = w

            override fun killLHS(
                lhs: TACSymbol.Var,
                s: VROIntMap,
                w: VROIntMap,
                narrow: LTACCmdView<TACCmd.Simple.AssigningCmd>
            ): VROIntMap {
                return s.kill(lhs)
            }

            override fun postStep(stepped: VROIntMap, l: LTACCmd) = stepped
        }

        val g = code.analysisCache.graph

        return code.patching { p ->
            object : StatefulWorklistIteration<NBId, Unit, Unit>() {
                override val scheduler = g.cache.naturalBlockScheduler

                val state = mutableMapOf<NBId, VROIntMap>()
                val conditions = mutableMapOf<LTACCmd, VROIntMap>()

                override fun process(it: NBId): StepResult<NBId, Unit, Unit> {
                    var s = state[it].orEmpty()

                    val block = g.elab(it)

                    for (lcmd in block.commands) {
                        if (CONDITIONAL_SCALARIZATION in lcmd.cmd.meta) {
                            conditions[lcmd] = s
                        }
                        s = interpreter.step(lcmd, s)
                    }

                    return cont(
                        g.pathConditionsOf(it).entries.mapNotNull { (k, v) ->
                            k `to?` interpreter.propagate(
                                l = block.commands.last(), w = s, pathCondition = v
                            )
                        }.mapNotNull { (succ, st) ->
                            val prev = state[succ]
                            val new = when(prev) {
                                null -> st
                                else -> prev.merge(st) { k, a1, a2 ->
                                    // We don't care about loops, etc., and want to converge quickly
                                    if (a1 == a2) { a1 } else { k.tag.nondetOf() }
                                }
                            }
                            if (new != prev) {
                                state[succ] = new
                                succ
                            } else {
                                null
                            }
                        }
                    )
                }

                override fun reduce(results: List<Unit>) {
                    conditions.forEachEntry { (lcmd, s) ->
                        check(
                            lcmd.cmd is TACCmd.Simple.AssigningCmd.AssignExpCmd
                            && lcmd.cmd.rhs is TACExpr.TernaryExp.Ite
                            && lcmd.cmd.rhs.i is TACExpr.Sym
                        ) {
                            "Conditional scalarization does not follow the expected pattern: $lcmd"
                        }

                        val result = when (s[lcmd.cmd.rhs.i.s]?.x?.interval?.asConstOrNull) {
                            null -> null
                            BigInteger.ZERO -> lcmd.cmd.rhs.e
                            else -> lcmd.cmd.rhs.t
                        }

                        if (result != null) {
                            p.update(lcmd.ptr, TACCmd.Simple.AssigningCmd.AssignExpCmd(lcmd.cmd.lhs, result))
                        }
                    }
                }
            }.submit(g.roots.map { it.ptr.block })
        }
    }
}
