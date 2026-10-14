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

import analysis.*
import analysis.dataflow.IGlobalValueNumbering
import analysis.dataflow.IUseAnalysis
import config.Config
import datastructures.stdcollections.*
import kotlinx.serialization.Serializable
import tac.MetaKey
import tac.Tag
import utils.*
import vc.data.*
import vc.data.TACMeta.STORAGE_KEY
import java.math.BigInteger
import java.util.stream.Collectors
import java.util.stream.Stream

/**
 * credit: jtoman
 * (todo Please explain how it is different from the regular constant propagation)
 */
object ConstantComputationInliner : ArrayLengthHeuristicMixin {
    private val keywordVars = TACKeyword.values().mapTo(mutableSetOf()) {
        it.toVar()
    }

    private val assignmentsOfInterest = { it: LTACCmd ->
        it.cmd is TACCmd.Simple.AssigningCmd
            && it.cmd.lhs !in keywordVars
            && it.cmd.lhs.tag != Tag.Bool
            && !it.cmd.lhs.meta.containsKey(STORAGE_KEY)
    }

    fun rewriteConstantCalculations(prog: CoreTACProgram) : CoreTACProgram {
        val singleDefVariables = prog.parallelLtacStream().filter(assignmentsOfInterest).map {
            (it.cmd as TACCmd.Simple.AssigningCmd).lhs to it
        }.collect(
            Collectors.groupingBy(
                { varToSingleDef: Pair<TACSymbol.Var, LTACCmd> ->
                    varToSingleDef.first
                },
                Collectors.mapping(
                    { varToSingleDef: Pair<TACSymbol.Var, LTACCmd> -> varToSingleDef.second },
                    Collectors.toList()
                )
            )
        ).filterValues {
            it.size == 1
        }.mapValues {
            it.value.first()
        }
        val allSymbolDefs = prog.parallelLtacStream().filter(assignmentsOfInterest).map {
            it.narrow<TACCmd.Simple.AssigningCmd>().cmd.lhs to (it.maybeNarrow<TACCmd.Simple.AssigningCmd.AssignExpCmd>()?.takeIf {
                it.cmd.rhs is TACExpr.Sym.Var
            })
        }.collect(
            Collectors.groupingBy(
                { it.first },
                Collectors.mapping({ it: Pair<TACSymbol.Var, LTACCmdView<TACCmd.Simple.AssigningCmd.AssignExpCmd>?> -> it.second },
                    Collectors.toList()))).mapValues {
            it.value.map {
                (it?.cmd?.rhs as? TACExpr.Sym.Var)?.s
            }
        }.filterValues {v ->
            v.all {
                it != null
            }
        }.uncheckedAs<Map<TACSymbol.Var, List<TACSymbol.Var>>>()
        val multiplicationAnnot = singleDefVariables.mapNotNull { (k, v) ->
            v.maybeNarrow<TACCmd.Simple.AssigningCmd.AssignExpCmd>()?.takeIf {
                it.cmd.rhs is TACExpr.Vec.Mul
            }?.wrapped?.enarrow<TACExpr.Vec.Mul>()?.takeIf {
                it.exp.ls.size == 2 && it.exp.operandsAreSyms()
            }?.let {
                k to (INLINED_MULTIPLICATION to InlinedMultiplication(it.exp.o1AsTACSymbol(), it.exp.o2AsTACSymbol()))
            }
        }.toMap()
        val constantVariables = singleDefVariables.mapNotNull { (v, l) ->
            /*
             find the candidates to inline. We don't want to inline occurrences of 0x60 in general, because it could be an empty array
             pointer. The exception is if every use of the value is in an addition statement, then we can be (fairly)
             certain this particular instance of 0x60 is not being used as the empty array pointer.
             */
            if(l.cmd is TACCmd.Simple.AssigningCmd.AssignExpCmd && l.cmd.rhs is TACExpr.Sym.Const && (l.cmd.rhs.s.value != 0x60.toBigInteger() || prog.analysisCache.use.useSitesAfter(v, l.ptr).all {
                        prog.analysisCache.graph.elab(it).maybeNarrow<TACCmd.Simple.AssigningCmd.AssignExpCmd>()?.cmd?.rhs is TACExpr.Vec.Add
                    })) {
                v to l.cmd.rhs.s.value
            } else {
                null
            }
        }.toMap().toMutableMap()
        val foldCandidates = singleDefVariables.filter { (_, singleDefSite) ->
            singleDefSite.cmd is TACCmd.Simple.AssigningCmd.AssignExpCmd && singleDefSite.cmd.getFreeVarsOfRhs().all {
                it in singleDefVariables || it in allSymbolDefs
            }
        }.map { (_, it) ->
            it.enarrow<TACExpr>()
        }.toList()
        var oldSize: Int
        var newSize: Int
        fun TACSymbol.interp() : BigInteger? =
            when(this) {
                is TACSymbol.Const -> this.value.takeIf { it != 0x60.toBigInteger() }
                is TACSymbol.Var -> constantVariables[this]
            }
        do {
            oldSize = constantVariables.size
            val newValues = foldCandidates.stream().flatMap {
                when(val exp = it.exp) {
                    is TACExpr.BinOp -> {
                        if(exp.operandsAreSyms()) {
                            val o1 = exp.o1AsTACSymbol().interp()
                            val o2 = exp.o2AsTACSymbol().interp()
                            val folded = if (o1 != null && o2 != null) { exp.eval(o1, o2) } else { null }
                            if (folded != null) {
                                return@flatMap Stream.of(it.lhs to folded)
                            }
                        }
                    }
                    is TACExpr.UnaryExp -> {
                        val o = (exp.o as? TACExpr.Sym)?.s?.interp()
                        if (o != null) {
                            return@flatMap Stream.of(it.lhs to exp.eval(o))
                        }
                    }
                    is TACExpr.Vec -> {
                        val constArgs = exp.ls.monadicMap {
                            (it as? TACExpr.Sym)?.s?.interp()
                        }
                        if(constArgs != null) {
                            return@flatMap Stream.of(it.lhs to exp.eval(constArgs))
                        }
                    }
                    is TACExpr.Sym.Var -> {
                        return@flatMap Stream.ofNullable<Pair<TACSymbol.Var, BigInteger>>(it.lhs `to?`exp.s.interp())
                    }
                    else -> return@flatMap Stream.empty<Pair<TACSymbol.Var, BigInteger>>()
                }
                Stream.empty()
            }.collect(Collectors.filtering({ it.first !in constantVariables }, Collectors.toMap({ it.first}, { it.second })))
            for((v, c) in newValues) {
                constantVariables[v] = c
            }
            for((v, syms) in allSymbolDefs) {
                val alwaysConst = syms.monadicMap {
                    constantVariables[it]
                }?.uniqueOrNull() ?: continue
                constantVariables[v] = alwaysConst
            }
            newSize = constantVariables.size
        } while(newSize > oldSize)

        fun locToReservedLocationScalar(base: TACSymbol, loc: BigInteger) : TACSymbol.Var? {
            /**
             * We have some tests that opt out of scalarization via [Config._Mem0x0To0x40AsScalar] BUT
             * actually rely on that flag being IGNORED in the below (so storage analyses work).
             *
             * So, narrow the check here to check the explicit equivalence check opt out.
             */
            if(Config.EquivalenceCheck.get()) {
                return null
            }
            if(base == TACKeyword.MEMORY.toVar() &&
                loc in setOf(0x0.toBigInteger(), 0x20.toBigInteger())) {
                if(loc == 0x0.toBigInteger()) {
                    return TACKeyword.MEM0.toVar()
                }
                return TACKeyword.MEM32.toVar()
            }
            return null
        }
        fun locToReservedLocationScalar(base: TACSymbol, loc: TACSymbol) : TACSymbol.Var? {
            if(loc is TACSymbol.Const) {
                return locToReservedLocationScalar(base, loc.value)
            }
            return null
        }
        // now, inline variables
        val commandTransformer = object : DefaultTACCmdMapper() {
            override fun mapSymbol(t: TACSymbol): TACSymbol =
                when(t) {
                    is TACSymbol.Const -> t
                    is TACSymbol.Var -> constantVariables[t]?.asTACSymbol() ?: t
                }

            override fun map(t: TACCmd.Simple): TACCmd.Simple {
                val mapped = super.map(t)
                val inlinedVar = t.getFreeVarsOfRhs().singleOrNull {
                    it in multiplicationAnnot
                }
                return if(inlinedVar != null) {
                    mapped.mapMeta {
                        it.add(multiplicationAnnot[inlinedVar]!!)
                    }
                } else {
                    mapped
                }
            }

            override fun mapByteStore(base: TACSymbol.Var,
                                      loc: TACSymbol,
                                      value: TACSymbol,
                                      metaMap: tac.MetaMap) : TACCmd.Simple {
                val mappedBase = mapVar(base)
                val mappedLoc = mapSymbol(loc)
                val mappedValue = mapSymbol(value)
                val mappedMeta = mapMeta(metaMap)
                return locToReservedLocationScalar(mappedBase, mappedLoc)?.let {
                    TACCmd.Simple.AssigningCmd.AssignExpCmd(
                        lhs = it,
                        rhs = mappedValue,
                        meta = mappedMeta
                    )
                } ?: TACCmd.Simple.AssigningCmd.ByteStore(
                    loc = mappedLoc,
                    value = mappedValue,
                    base = mappedBase,
                    meta = mappedMeta)
            }

            override fun mapByteLoad(lhs: TACSymbol.Var,
                                     base: TACSymbol.Var,
                                     loc: TACSymbol,
                                     metaMap: tac.MetaMap) : TACCmd.Simple {
                val mappedLhs = mapVar(lhs)
                val mappedBase = mapVar(base)
                val mappedLoc = mapSymbol(loc)
                val mappedMeta = mapMeta(metaMap)
                return locToReservedLocationScalar(mappedBase, mappedLoc)?.let {
                    TACCmd.Simple.AssigningCmd.AssignExpCmd(
                        lhs = mappedLhs,
                        rhs = it,
                        meta = mappedMeta
                    )
                } ?: TACCmd.Simple.AssigningCmd.ByteLoad(
                    lhs = mappedLhs,
                    loc = mappedLoc,
                    base = mappedBase,
                    meta = mappedMeta)
            }
        }

        fun handleByteLongCopy(blc: TACCmd.Simple.ByteLongCopy): CommandWithRequiredDecls<TACCmd.Simple> {
            /* NOTE: this doesn't actually handle all the possible cases.
            Theoretically, one could use ByteLongCopy to write into the middle of MEM0 or MEM32, resulting
            in the need for us to generate non-linear byte-wise masking operations.  This would be bad.
            Additionally, if someone is doing a write that potentially continues on into the free pointer at 0x40,
            are we even in a Solidity managed memory regime anymore?  What do MEM0 and MEM32 even mean?
            We're just going to handle some of the more obvious cases here.

            A lot of this code looks pedantic.  That's intentional.  I'm trying to make the code for
            a transformation with a lot of corner cases somewhat easier to read.

            This transform is in two parts:  first we transform all the left hand sides to possible reserved
            locations.  We then take that output and transform it again to handle any possible reserved locations
            on the right hand sides.
             */
            fun incrementSymBy(sym: TACSymbol, by: BigInteger): Pair<CommandWithRequiredDecls<TACCmd.Simple>, TACSymbol> {
                return if(sym !is TACSymbol.Const) {
                    val temp = TACKeyword.TMP(tag = Tag.Bit256, suffix = "handleByteLongCopy").toUnique()
                    CommandWithRequiredDecls<TACCmd.Simple>(
                        TACCmd.Simple.AssigningCmd.AssignExpCmd(
                            lhs = temp,
                            rhs = TACExpr.Vec.Add(
                                TACExpr.Sym(sym),
                                TACExpr.Sym.Const(TACSymbol.Const(by))
                            )
                        ), temp) to temp
                } else {
                    CommandWithRequiredDecls<TACCmd.Simple>() to TACSymbol.Const(sym.value + by)
                }
            }

            fun decrementSymBy(sym: TACSymbol, by: BigInteger): Pair<CommandWithRequiredDecls<TACCmd.Simple>, TACSymbol> {
                return if(sym !is TACSymbol.Const) {
                    val temp = TACKeyword.TMP(tag = Tag.Bit256, suffix = "handleByteLongCopy").toUnique()
                    CommandWithRequiredDecls<TACCmd.Simple>(
                        TACCmd.Simple.AssigningCmd.AssignExpCmd(
                            lhs = temp,
                            rhs = TACExpr.BinOp.Sub(
                                TACExpr.Sym(sym),
                                TACExpr.Sym.Const(TACSymbol.Const(by))
                            )
                        ), temp) to temp
                } else {
                    CommandWithRequiredDecls<TACCmd.Simple>() to TACSymbol.Const(sym.value - by)
                }
            }

            fun codegenLoad64(blc: TACCmd.Simple.ByteLongCopy): CommandWithRequiredDecls<TACCmd.Simple> {
                val (incCrd, incSym) = incrementSymBy(blc.srcOffset, 32.toBigInteger())
                return CommandWithRequiredDecls<TACCmd.Simple>(
                    TACCmd.Simple.AssigningCmd.ByteLoad(
                        lhs = TACKeyword.MEM0.toVar(),
                        loc = blc.srcOffset,
                        base = blc.srcBase,
                        meta = blc.meta
                    )).merge(incCrd).merge(
                    CommandWithRequiredDecls<TACCmd.Simple>( TACCmd.Simple.AssigningCmd.ByteLoad(
                        lhs = TACKeyword.MEM32.toVar(),
                        loc = incSym,
                        base = blc.srcBase,
                        meta = blc.meta
                    ))
                )
            }

            fun codegenStore64(blc: TACCmd.Simple.ByteLongCopy) : CommandWithRequiredDecls<TACCmd.Simple> {
                check(blc.length is TACSymbol.Const)
                check(blc.srcOffset is TACSymbol.Const)
                val (crdDstOffsetInc32, dstOffsetIncSym32) = incrementSymBy(blc.dstOffset, 32.toBigInteger())
                val partialResult = CommandWithRequiredDecls<TACCmd.Simple>(
                    TACCmd.Simple.AssigningCmd.ByteStore(
                        loc = blc.dstOffset,
                        base = blc.dstBase,
                        value = TACKeyword.MEM0.toVar(),
                        meta = blc.meta
                    )).merge(crdDstOffsetInc32).merge(
                    CommandWithRequiredDecls<TACCmd.Simple>(
                        TACCmd.Simple.AssigningCmd.ByteStore(
                            loc = dstOffsetIncSym32,
                            base = blc.dstBase,
                            value = TACKeyword.MEM32.toVar(),
                            meta = blc.meta
                        )))
                val result = if(blc.length.value > 64.toBigInteger()) {
                    val (crdDstOffsetInc64, dstOffsetIncSym64) = incrementSymBy(blc.dstOffset, 64.toBigInteger())
                    partialResult.merge(crdDstOffsetInc64).merge(
                        CommandWithRequiredDecls(
                        TACCmd.Simple.ByteLongCopy(
                            dstBase = blc.dstBase,
                            dstOffset = dstOffsetIncSym64,
                            srcBase = blc.srcBase,
                            srcOffset = TACSymbol.Const(blc.srcOffset.value + 64.toBigInteger()),
                            length = TACSymbol.Const(blc.length.value - 64.toBigInteger()),
                            meta = blc.meta
                        )))
                } else {
                    partialResult
                }
                return result
            }

            fun codegenStore32(blc: TACCmd.Simple.ByteLongCopy) : CommandWithRequiredDecls<TACCmd.Simple> {
                check(blc.srcOffset is TACSymbol.Const)
                val partialResult = CommandWithRequiredDecls<TACCmd.Simple>(
                    TACCmd.Simple.AssigningCmd.ByteStore(
                        loc = blc.dstOffset,
                        base = blc.dstBase,
                        value = locToReservedLocationScalar(blc.srcBase, blc.srcOffset)!!,
                        meta = blc.meta
                    ))

                val result = if(blc.length is TACSymbol.Const &&
                    blc.length.value == 32.toBigInteger()) {
                    // we're done
                    partialResult
                } else {
                    val (crdDstOffsetInc32, dstOffsetIncSym32) = incrementSymBy(blc.dstOffset, 32.toBigInteger())
                    val (crdLengthDec32, lengthDecSym32) = decrementSymBy(blc.length, 32.toBigInteger())
                    partialResult.merge(crdDstOffsetInc32).merge(crdLengthDec32).merge(
                        TACCmd.Simple.ByteLongCopy(
                            dstBase = blc.dstBase,
                            dstOffset = dstOffsetIncSym32,
                            srcBase = blc.srcBase,
                            srcOffset = TACSymbol.Const(blc.srcOffset.value + 32.toBigInteger()),
                            length = lengthDecSym32,
                            meta = blc.meta

                        )
                    )
                }
                return result
            }

            val lhsCmds = if(blc.length is TACSymbol.Const &&
                blc.dstOffset is TACSymbol.Const &&
                blc.dstBase == TACKeyword.MEMORY.toVar()) {

                if (blc.length.value == 32.toBigInteger() &&
                    blc.dstOffset.value == 0.toBigInteger()) {

                    CommandWithRequiredDecls(
                        TACCmd.Simple.AssigningCmd.ByteLoad(
                            lhs = TACKeyword.MEM0.toVar(),
                            loc = blc.srcOffset,
                            base = blc.srcBase,
                            meta = blc.meta
                        )
                    )
                } else if (blc.length.value == 32.toBigInteger() &&
                    blc.dstOffset.value == 32.toBigInteger()) {

                    CommandWithRequiredDecls(
                        TACCmd.Simple.AssigningCmd.ByteLoad(
                            lhs = TACKeyword.MEM32.toVar(),
                            loc = blc.srcOffset,
                            base = blc.srcBase,
                            meta = blc.meta
                        )
                    )
                } else if (blc.length.value == 64.toBigInteger() &&
                    blc.dstOffset.value == 0.toBigInteger()) {

                    codegenLoad64(blc)
                } else {
                    CommandWithRequiredDecls(blc)
                }
            } else {
                CommandWithRequiredDecls(blc)
            }

            // process back through the results of breaking up lhs's to check for rhs transforms
            val resultCmds = CommandWithRequiredDecls.mergeMany(
                lhsCmds.cmds.map {
                    if(it is TACCmd.Simple.AssigningCmd.ByteLoad &&
                        locToReservedLocationScalar(it.base, it.loc) != null) {
                        // if locToReservedLocationScalar is not null, this is a simple 32 bit replacement
                        CommandWithRequiredDecls(
                            TACCmd.Simple.AssigningCmd.AssignExpCmd(
                                lhs = it.lhs,
                                rhs = locToReservedLocationScalar(it.base, it.loc)!!,
                                meta = it.meta
                            )
                        )
                    } else if(it is TACCmd.Simple.ByteLongCopy &&
                        it.srcBase == TACKeyword.MEMORY.toVar() &&
                        it.srcOffset is TACSymbol.Const) {

                        // separate out the 64 bit cases because we can only do all 64 bits if length is constant
                        if(it.srcOffset.value == 0.toBigInteger() &&
                            it.length is TACSymbol.Const &&
                            it.length.value >= 64.toBigInteger()) {

                            codegenStore64(blc)
                        } else if(locToReservedLocationScalar(it.srcBase, it.srcOffset) != null) {
                            // this is a 32 bit replacement
                            codegenStore32(blc)
                        } else {
                            CommandWithRequiredDecls(it)
                        }
                    } else {
                        CommandWithRequiredDecls(it)
                    }
                }
            )
            return resultCmds.merge(*lhsCmds.varDecls.toTypedArray())
        }

        val patching = prog.toPatchingProgram()
        patching.addVarDecl(TACKeyword.MEM0.toVar())
        patching.addVarDecl(TACKeyword.MEM32.toVar())
        prog.analysisCache.graph.commands.parallelStream().filter {
            (it.cmd.getFreeVarsOfRhs().any {
                it in constantVariables
            } || (it.cmd is TACCmd.Simple.AssigningCmd && it.cmd.lhs in constantVariables))
        }.map {
            if(it.cmd is TACCmd.Simple.AssigningCmd && it.cmd.lhs in constantVariables) {
                val meta = if(it.cmd.lhs in multiplicationAnnot) {
                    it.cmd.meta.add(multiplicationAnnot[it.cmd.lhs]!!)
                } else {
                    it.cmd.meta
                }
                it.ptr to listOf(TACCmd.Simple.AssigningCmd.AssignExpCmd(
                    it.cmd.lhs,
                    constantVariables[it.cmd.lhs]!!.asTACSymbol().asSym(),
                    meta = meta
                ))
            } else {
                val mappedC = commandTransformer.map(it.cmd)
                it.ptr to
                        if(mappedC is TACCmd.Simple.ByteLongCopy) {
                            val result = handleByteLongCopy(mappedC)
                            patching.addVarDecls(result.varDecls)
                            result.cmds
                        } else {
                            listOf(mappedC)
                        }
            }
        }.sequential().forEach { (where, c) ->
            patching.replaceCommand(where, c)
        }
        return patching.toCode(prog)
    }

    @Serializable
    data class InlinedMultiplication(val o1: TACSymbol, val o2: TACSymbol)
    : AmbiSerializable, TransformableVarEntity<InlinedMultiplication> {
        override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var): InlinedMultiplication {
            val mapSymbol = { v: TACSymbol ->
                when (v) {
                    is TACSymbol.Var -> f(v)
                    is TACSymbol.Const -> v
                }
            }
            return InlinedMultiplication(o1 = mapSymbol(o1), o2 = mapSymbol(o2))
        }
    }

    val INLINED_MULTIPLICATION = MetaKey<InlinedMultiplication>("inlined.multiplication.ops")


    private data class AddK(val where: LTACCmd, val v: TACSymbol.Var, val k1: BigInteger)

    private sealed class Rewrite {
        abstract val subtractLoc: LTACCmd

        /** Check any side conditions on this rewrite & produce the actual rewrite if satisfied */
        abstract fun rewriteSpec(use: IUseAnalysis, gvn: IGlobalValueNumbering): VerifiedRewrite?

        interface VerifiedRewrite {
            fun rewrite(
                graph: TACCommandGraph,
                patch: SimplePatchingProgram,
                matchedAssignment: LTACCmdView<TACCmd.Simple.AssigningCmd>,
            )
        }

        // (x + y) - z
        // Can replace [subtractLoc] rhs with y when x == z
        data class Identity(
            override val subtractLoc: LTACCmd,
            val addLoc: LTACCmd,
            val x: TACSymbol.Var,
            val y: TACSymbol.Var,
            val z: TACSymbol.Var
        ) : Rewrite() {
            override fun rewriteSpec(use: IUseAnalysis, gvn: IGlobalValueNumbering): VerifiedRewrite? {
                val canDeleteAdd = use.useSitesAfter(
                    v = addLoc.narrow<TACCmd.Simple.AssigningCmd>().cmd.lhs,
                    pointer = addLoc.ptr
                ) == setOf(subtractLoc.ptr)


                val zCopies = gvn.findCopiesAt(subtractLoc.ptr, addLoc.ptr to z)
                val rewriteRhsTo = if (x in zCopies) {
                    y
                } else if (y in zCopies) {
                    x
                } else {
                    null
                }

                return rewriteRhsTo?.let { newRhs ->
                    object : VerifiedRewrite {
                        override fun rewrite(
                            graph: TACCommandGraph,
                            patch: SimplePatchingProgram,
                            matchedAssignment: LTACCmdView<TACCmd.Simple.AssigningCmd>
                        ) {
                            if (canDeleteAdd) {
                                patch.delete(addLoc.ptr)
                            }

                            patch.replaceCommand(
                                matchedAssignment.ptr, listOf(
                                    TACCmd.Simple.AssigningCmd.AssignExpCmd(matchedAssignment.cmd.lhs, newRhs)
                                )
                            )
                        }
                    }
                }
            }
        }

        // Rewrites that result in a constant assignment
        sealed class ConstantComputation : Rewrite() {

            abstract val finalAdd: LTACCmd
            abstract val k2: BigInteger
            abstract val k1: BigInteger

            abstract val k: BigInteger

            abstract val addPair: Pair<CmdPointer, TACSymbol.Var>
            abstract val subVar: TACSymbol.Var

            override fun rewriteSpec(use: IUseAnalysis, gvn: IGlobalValueNumbering): VerifiedRewrite? {
                if(subVar !in gvn.findCopiesAt(target = subtractLoc.ptr, source = addPair)) {
                    return null
                }

                if (k < BigInteger.ZERO) {
                    // yeet
                    return null
                }

                val elideSub = use.useSitesAfter(
                    v = subtractLoc.narrow<TACCmd.Simple.AssigningCmd>().cmd.lhs,
                    pointer = subtractLoc.ptr
                ) == setOf(finalAdd.ptr)

                return object : VerifiedRewrite {
                    override fun rewrite(
                        graph: TACCommandGraph,
                        patch: SimplePatchingProgram,
                        matchedAssignment: LTACCmdView<TACCmd.Simple.AssigningCmd>
                    ) {
                        val isPotentialArraySize = finalAdd.enarrow<TACExpr.Vec.Add>().exp.takeIf {
                            it.operandsAreSyms() && finalAdd.ptr == matchedAssignment.ptr
                        }?.let {
                            it.ls.mapNotNull {
                                (it as? TACExpr.Sym.Var)
                            }.singleOrNull()
                        }?.s?.let { potentialLength ->
                            plausibleLengthComponent(
                                graph, potentialLength, where = matchedAssignment.ptr
                            )
                        } == true
                        if (isPotentialArraySize) {
                            return
                        }
                        patch.replaceCommand(
                            matchedAssignment.ptr, listOf(
                                TACCmd.Simple.AssigningCmd.AssignExpCmd(
                                    lhs = matchedAssignment.cmd.lhs,
                                    rhs = k.asTACSymbol().asSym()
                                )
                            )
                        )

                        if (elideSub) {
                            patch.replaceCommand(subtractLoc.ptr, listOf())
                        }
                    }
                }
            }

            data class SubtractAdded(
                override val finalAdd: LTACCmd,
                override val k2: BigInteger,
                override val subtractLoc: LTACCmd,
                val lhs: TACSymbol.Var,
                val rhs: AddK
            ) : ConstantComputation() {
                override val k1: BigInteger
                    get() = rhs.k1
                override val addPair: Pair<CmdPointer, TACSymbol.Var>
                    get() = rhs.where.ptr to rhs.v
                override val subVar: TACSymbol.Var
                    get() = lhs
                override val k: BigInteger
                    get() = k2 + k1.negate()
            }

            data class SubtractFromAdded(
                override val finalAdd: LTACCmd,
                override val k2: BigInteger,
                override val subtractLoc: LTACCmd,
                val subtrahend: TACSymbol.Var,
                val minuendAdd: AddK
            ) : ConstantComputation() {
                override val k1: BigInteger
                    get() = minuendAdd.k1
                override val addPair: Pair<CmdPointer, TACSymbol.Var>
                    get() = minuendAdd.where.ptr to minuendAdd.v
                override val subVar: TACSymbol.Var
                    get() = subtrahend
                override val k: BigInteger
                    get() = k1 + k2
            }
        }
    }

    /**
      * Match the pattern:
      * d = k2 + (v1 - (v2 + k1)) ([Rewrite.ConstantComputation.SubtractAdded])
      *
      * With the requirement that v1 == v2
      */
    private val subtractAdded = PatternDSL.build {
        (Const + (Var - (Var + Const).commute.withAction { ltacCmd, v, const ->
            AddK(where = ltacCmd, v = v, k1 = const)
        }).withAction { where, v, add ->
            Triple(where, v, add)
        }).commute.withAction { finalAdd, c, payload ->
            Rewrite.ConstantComputation.SubtractAdded(
                finalAdd = finalAdd,
                k2 = c,
                lhs = payload.second,
                subtractLoc = payload.first,
                rhs = payload.third
            )
        }
    }

    /**
     * Match the pattern:
     * d = k1 + (v1 + k2) - v2 ([Rewrite.ConstantComputation.SubtractFromAdded])
     *
     * with the requirement that v1 == v2
     */
    private val subtractFromAdded = PatternDSL.build {
        (Const + ((Var + Const).commute.withAction(::AddK) - Var).withAction(::Triple)).commute.withAction { ltacCmd, k2, payload ->
            Rewrite.ConstantComputation.SubtractFromAdded(
                finalAdd = ltacCmd,
                subtractLoc = payload.first,
                k2 = k2,
                minuendAdd = payload.second,
                subtrahend = payload.third
            )
        }
    }

    /**
     * Match the pattern
     * d = (x1 + v) - x2 ([Rewrite.Identity])
     *
     * which can be rewritten to d = v when x1 = x2
     */
    private val identityAddSubtract = PatternDSL.build {
        ((Var + Var).withAction(::Triple) - Var).withAction { cmd, (add, x, y), z ->
            Rewrite.Identity(cmd, add, x, y, z)
        }
    }


    fun rewriteConstViaSubThenAdd(p: CoreTACProgram): CoreTACProgram {
        val graph = p.analysisCache.graph
        val gvn = graph.cache.gvn
        val subtractFromAddMatcher = PatternMatcher.compilePattern(
            graph, subtractFromAdded
        )
        val subtractAddedMatcher = PatternMatcher.compilePattern(graph, subtractAdded)
        val identityAddSubtractMatcher = PatternMatcher.compilePattern(graph, identityAddSubtract)

        return p.patching { patch ->
            this.parallelLtacStream().filter {
                it.cmd is TACCmd.Simple.AssigningCmd
            }.map {
                it.narrow<TACCmd.Simple.AssigningCmd>()
            }.flatMap {
                listOfNotNull(
                    it `to?` subtractFromAddMatcher.queryFrom(it).toNullableResult(),
                    it `to?` subtractAddedMatcher.queryFrom(it).toNullableResult(),
                    it `to?` identityAddSubtractMatcher.queryFrom(it).toNullableResult()
                ).stream()
            }.map { it!! }.mapNotNull { (assign, p) ->
                assign `to?` p.rewriteSpec(graph.cache.use, gvn)
            }.sequential().forEach { (matchedAssignment, rewriter) ->
                rewriter.rewrite(graph, patch, matchedAssignment)
            }
        }
    }

}
