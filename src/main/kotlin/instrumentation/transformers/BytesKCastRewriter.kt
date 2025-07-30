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

package instrumentation.transformers

import analysis.*
import analysis.smtblaster.*
import datastructures.stdcollections.*
import evm.*
import log.*
import parallel.Parallel
import parallel.ParallelPool
import parallel.ParallelPool.Companion.runInherit
import parallel.Scheduler
import parallel.pcompute
import smtlibutils.data.SmtExp
import smtlibutils.data.Sort
import solver.SolverConfig
import tac.MetaKey
import tac.NBId
import utils.*
import vc.data.*
import vc.data.SimplePatchingProgram.Companion.patchForEach
import java.math.BigInteger
import kotlin.math.min

private val logger = Logger(LoggerTypes.NORMALIZER)

/**
 * Recognize and annotate `bytesK(...)` casts.
 *
 * Solidity allows you to write `bytes4(x)` where `x` is a `bytes` array.
 * However, the way it does this is *super* unfortunate for the PTA. It effectively generates:
 * ```
 * l = mem[x]
 * data = mem[x + 32]
 * masked = data & 0xffffffff000....00
 * if l < 4 {
 *    out = clean(masked)
 * } else {
 *    out = masked
 * }
 * ```
 *
 * where `clean` will get rid of any garbage bytes read past the end of the array. The PTA does not like this
 * and failed on the unconditional access of `x + 32`. For other bytesK types it will switch
 * the constants around but it follows this general pattern.
 *
 * *sigh*
 *
 * So the solution is to detect this pattern and annotate that read from `mem[x + 32]` as being okay
 * if `x` the array elem start of a `bytes` array.
 */
object BytesKCastRewriter {
    val BYTESK_CAST = MetaKey.Nothing("bytesk.normalizer.read")

    /**
     * [dataLoadLocation] is the location we read `data`
     * [loadBase] is the `x` and the location at where we are capturing that value
     * [kSize] the size (in bytes) of the mask we're applying (i.e., how many upper bytes are we masking)
     * [maskLoc] where are we masking
     */
    private data class BytesKRewriteCand(
        val dataLoadLocation: LTACCmd,
        val loadBase: LTACVar,
        val kSize: Int,
        val maskLoc: LTACCmd
    )

    /**
     * To seed our search, look for (mem[x + 0x20] & 0xff...fff00) where `fff` is a string
     * of upper 1s.
     */
    private val bytesKPattern = PatternDSL.build {
        (PatternMatcher.Pattern.AssigningPattern1(
            klass = TACCmd.Simple.AssigningCmd.ByteLoad::class.java,
            patternName = "read-data",
            extract = ext@{ _: LTACCmd, load: TACCmd.Simple.AssigningCmd.ByteLoad ->
                if(load.base != TACKeyword.MEMORY.toVar()) {
                    return@ext null
                }
                load.loc
            },
            nested = (Var.withLocation + EVM_WORD_SIZE()).commute.first.toPattern(),
            out = { loadLoc, base ->
                loadLoc to LTACVar(
                    ptr = base.first,
                    v = base.second
                )
            }
        ).asBuildable() and PatternMatcher.Pattern.FromConstant<Int, Int>(
            extractor = { c: TACSymbol.Const ->
                /**
                 * See if we have a sequence of high bytes set, and compute that length.
                 */
                val k = c.value
                val invertedPlus1 = MAX_EVM_UINT256.andNot(k) + BigInteger.ONE
                if(invertedPlus1.bitCount() != 1) {
                    return@FromConstant null
                }
                val invertedLenBits = EVM_BITWIDTH256 - (invertedPlus1.bitLength() - 1)
                if((invertedLenBits % EVM_BYTE_SIZE_INT) != 0) {
                    return@FromConstant null
                }
                invertedLenBits / EVM_BYTE_SIZE_INT
            },
            out = { _, it -> it }
        ).asBuildable()).commute.withAction { maskLoc, (loadLoc, loadSource), kSize ->
            BytesKRewriteCand(
                kSize = kSize,
                maskLoc = maskLoc,
                dataLoadLocation = loadLoc,
                loadBase = loadSource
            )
        }
    }

    fun rewrite(c: CoreTACProgram) : CoreTACProgram {
        val graph = c.analysisCache.graph
        val matcher = PatternMatcher.compilePattern(
            graph, bytesKPattern
        )
        val def = c.analysisCache.def
        return ParallelPool.allocInScope(args = (2000 to SolverConfig.cvc5.default), mk = { (timeout, conf) ->
            Z3BlasterPool(
                z3TimeoutMs = timeout,
                fallbackSolverConfig = conf
            )
        }) { pool ->
            c.parallelLtacStream().mapNotNull { lc ->
                lc.maybeNarrow<TACCmd.Simple.AssigningCmd>()?.let { lcAssign ->
                    matcher.queryFrom(lcAssign).toNullableResult()
                }
            }.mapNotNull { cand ->
                /**
                 * Find the `l = mem[x]` candidate; do this by finding use sites of `x` which are in the
                 * same block as the data load
                 */
                val lengthLoadSite = def.defSitesOf(v = cand.loadBase.v, pointer = cand.loadBase.ptr).flatMap { dSite ->
                    c.analysisCache.use.useSitesAfter(cand.loadBase.v, dSite).filter { useSite ->
                        // same block as loading the payload
                        useSite.block == cand.dataLoadLocation.ptr.block &&
                            graph.elab(useSite).maybeNarrow<TACCmd.Simple.AssigningCmd.ByteLoad>()?.cmd?.base == TACKeyword.MEMORY.toVar()
                    }
                }.uniqueOrNull() ?: return@mapNotNull null
                analyzeBytesKCast(
                    cand, lengthLoadSite, graph, pool
                )
            }.toList().pcompute().runInherit(ParallelPool.SpawnPolicy.GLOBAL).stream().mapNotNull { it }.patchForEach(
                c, check = true
            ) { thunk ->
                thunk(this)
            }
        }
    }

    /**
     * Intermediate class for translating the bytesk cast code.
     */
    private data class TranslationResult(
        val target: NBId,
        val finalBloc: NBId,
        val definedVars: Set<TACSymbol.Var>,
        val script: SmtExpScriptBuilder
    )

    /**
     * With the candidate [cand] and length load [lengthLoadSite], see if the
     * program fragment starting at [lengthLoadSite] does a bytesk cast. We
     * use smt for this, hence the [pool].
     *
     * The return, if non-null, is thunk which applies the [BytesKCastRewriter.BYTESK_CAST]
     * annotation which the PTA consumes.
     */
    private fun analyzeBytesKCast(
        cand: BytesKRewriteCand,
        lengthLoadSite: CmdPointer,
        graph: TACCommandGraph,
        pool: Z3BlasterPool
    ): Parallel<((SimplePatchingProgram) -> Unit)?>? {
        fun debugFail(s: () -> Any) : Nothing? {
            logger.debug {
                "Analyzing: ${cand.dataLoadLocation}: ${s()}"
            }
            return null
        }

        val workBlock = cand.dataLoadLocation.ptr.block
        check(workBlock == lengthLoadSite.block) {
            "Invariant broken, we have cross block behavior: $workBlock $lengthLoadSite"
        }
        val expBlaster = SmtExpBitBlaster()
        val initScript = SmtExpScriptBuilder(
            SmtExpBitVectorTermBuilder
        )
        /**
         * UF for modeling memory.
         */
        initScript.declareFun("read", 1)
        initScript.declare(cand.loadBase.v.namePrefix)

        val startPos = min(lengthLoadSite.pos, cand.dataLoadLocation.ptr.pos)

        /**
         * Helper functions for generating applications of read
         */
        fun ISMTTermBuilder<Sort, SmtExp>.read(v: TACSymbol.Var) = apply("read", listOf(toIdent(v.namePrefix)))
        fun ISMTTermBuilder<Sort, SmtExp>.dataStart() = plus(
            const(EVM_WORD_SIZE),
            toIdent(cand.loadBase.v.namePrefix)
        )
        fun ISMTTermBuilder<Sort, SmtExp>.read(s: SmtExp) = apply("read", listOf(s))

        /**
         * We translate two blocks, the first we expect to see branch on the length of the array,
         * and the second does any cleaning (if need be).
         * [part1] indicates whether we expect the current block [workBlock]
         * to end in the branch, or whether this is the successor block of the "part1".
         * [work] is the sequence of commands that is a (not necessarily strict) suffix of [workBlock],
         * these are the commands we translate to smt in [scriptBuilder].
         * [definedVars] is an accumulator of variables defined along the current path of the program and
         * is returned in [TranslationResult].
         *
         * Returns null if translation failed, or a list of the translation of the paths (we expect there to be 2).
         */
        fun translateBlock(
            workBlock: NBId,
            work: Sequence<LTACCmd>,
            scriptBuilder: SmtExpScriptBuilder,
            definedVars: MutableSet<TACSymbol.Var>,
            part1: Boolean
        ) : List<TranslationResult>? {
            for(lc in work) {
                /**
                 * Just havoc the variable defined by [a]
                 */
                fun andHavoc(a: TACCmd.Simple.AssigningCmd) {
                    scriptBuilder.declare(a.lhs.namePrefix)
                }
                when(lc.cmd) {
                    is TACCmd.Simple.JumpCmd -> {
                        if(part1) {
                            return debugFail {
                                "Hit end of opening block with jump: $lc"
                            }
                        }
                    }
                    is TACCmd.Simple.TransientCmd -> continue
                    is TACCmd.Simple.ReturnSymCmd,
                    is TACCmd.Simple.SummaryCmd,
                    is TACCmd.Simple.WordStore,
                    is TACCmd.Simple.LongAccesses,
                    is TACCmd.Simple.Assume,
                    is TACCmd.Simple.AssertCmd -> {
                        return debugFail {
                            "Unsupported command $lc"
                        }
                    }
                    is TACCmd.Simple.AssigningCmd -> {
                        if(lc.cmd.lhs in definedVars) {
                            return debugFail {
                                "At $lc, redefining ${lc.cmd.lhs}"
                            }
                        } else {
                            definedVars.add(lc.cmd.lhs)
                        }
                        @Suppress("KotlinConstantConditions")
                        when(lc.cmd) {
                            is TACCmd.Simple.AssigningCmd.AssignExpCmd -> {
                                val exp = expBlaster.blastExpr(lc.cmd.rhs) {
                                    if (it !in definedVars) {
                                        /**
                                         * It seems unlikely, but if we're pulling in an unknown variable here declare that
                                         */
                                        definedVars.add(it)
                                        scriptBuilder.declare(it.namePrefix)
                                    }
                                    it.namePrefix
                                }
                                if (exp == null) {
                                    andHavoc(lc.cmd)
                                } else {
                                    scriptBuilder.define(lc.cmd.lhs.namePrefix) {
                                        exp
                                    }
                                }
                            }
                            /**
                             * imprecise model
                             */
                            is TACCmd.Simple.AssigningCmd.AssignGasCmd,
                            is TACCmd.Simple.AssigningCmd.AssignHavocCmd,
                            is TACCmd.Simple.AssigningCmd.AssignMsizeCmd,
                            is TACCmd.Simple.AssigningCmd.WordLoad,
                            is TACCmd.Simple.AssigningCmd.AssignSimpleSha3Cmd -> andHavoc(lc.cmd)
                            is TACCmd.Simple.AssigningCmd.ByteStore,
                            is TACCmd.Simple.AssigningCmd.ByteStoreSingle -> {
                                return debugFail {
                                    "Cannot handle memory mutations: $lc"
                                }
                            }

                            is TACCmd.Simple.AssigningCmd.ByteLoad -> {
                                if(lc.cmd.base != TACKeyword.MEMORY.toVar() || lc.cmd.loc !is TACSymbol.Var) {
                                    return debugFail {
                                        "Illegal byte load $lc"
                                    }
                                }
                                scriptBuilder.define(
                                    lc.cmd.lhs.namePrefix
                                ) {
                                    read(lc.cmd.loc)
                                }
                            }
                            // this is a long access, but the compiler insists we have it
                            is TACCmd.Simple.AssigningCmd.AssignSha3Cmd -> `impossible!`
                        }
                    }
                    is TACCmd.Simple.JumpiCmd -> {
                        if(!part1) {
                            return debugFail {
                                "Found a second jumpi $lc, this is unexpected"
                            }
                        }
                        /**
                         * Translate our successor blocks, forking the defined variable set and the current script
                         * while asserting the path condition along that branch.
                         */
                        return graph.pathConditionsOf(workBlock).entries.monadicMap { (happyPath, cond) ->
                            val sequel = scriptBuilder.fork()
                            if(cond !is TACCommandGraph.PathCondition.ConditionalOn) {
                                return@monadicMap debugFail {
                                    "Unrecognized condition to happy path $happyPath: $cond"
                                }
                            }
                            when(cond) {
                                is TACCommandGraph.PathCondition.EqZero -> {
                                    sequel.assert {
                                        eq(const(0), toIdent(cond.v.namePrefix))
                                    }
                                }
                                is TACCommandGraph.PathCondition.NonZero -> {
                                    sequel.assert {
                                        lnot(eq(const(0), toIdent(cond.v.namePrefix)))
                                    }
                                }
                            }
                            translateBlock(
                                workBlock = happyPath,
                                part1 = false,
                                definedVars = definedVars.toMutableSet(),
                                scriptBuilder = sequel,
                                work = graph.elab(happyPath).commands.asSequence()
                            )
                        }?.flatten()
                    }
                }
            }
            if(part1) {
                return debugFail {
                    "Ended opening block $workBlock without a branch, this is odd"
                }
            }
            val succ = graph.succ(workBlock).singleOrNull() ?: return debugFail {
                "Branch block $workBlock doesn't have a single successor"
            }
            /**
             * And done.
             */
            return listOf(
                TranslationResult(
                    finalBloc = workBlock,
                    definedVars = definedVars,
                    target = succ,
                    script = scriptBuilder
                )
            )
        }
        val translationRes = translateBlock(
            scriptBuilder = initScript,
            definedVars = mutableSetOf(cand.loadBase.v),
            work = graph.iterateBlock(CmdPointer(
                workBlock, startPos
            ), excludeStart = false).asSequence(),
            part1 = true,
            workBlock = workBlock
        ) ?: return debugFail {
            "Translation of code failed"
        }
        if(translationRes.mapToSet { it.target }.size != 1) {
            return debugFail { "Didn't rejoin at single point" }
        }
        /**
         * Along the paths we enumerated find the only variable we defined that is still
         * live after joining; if there isn't a single such variable, conservatively bail out
         * we don't know what this is doing...
         */
        val outputVar = translationRes.monadicMap { res ->
            val finalCommand = graph.elab(res.finalBloc).commands.last()
            res.definedVars.singleOrNull { definedVar ->
                graph.cache.lva.isLiveAfter(finalCommand.ptr, definedVar)
            }
        }?.uniqueOrNull() ?: return debugFail {
            "Failed to find unique output variable"
        }
        val expectedMaskSize = EVM_BYTE_SIZE_INT * cand.kSize
        /**
         * The upper mask we expect to mask out the top K bytes of the cast array.
         */
        val expectedMask = MASK_SIZE(expectedMaskSize).shiftLeft(
            EVM_BITWIDTH256 - expectedMaskSize
        )
        return translationRes.map {
            val scriptBuilder = it.script
            scriptBuilder.assert {
                /**
                 * Get ready!
                 */
                lnot(lor(
                    // length was long enough case:
                    land(
                        ge(
                            read(cand.loadBase.v),
                            const(cand.kSize)
                        ),
                        /**
                         * We just mask the full word read from the start of the array
                         */
                        eq(
                            toIdent(outputVar.namePrefix),
                            bwAnd(
                                read(dataStart()),
                                const(expectedMask)
                            )!!
                        )
                    ),
                    land(
                        // too smol
                        lt(
                            read(cand.loadBase.v),
                            const(cand.kSize)
                        ),
                        /**
                         * Give solidity credit, this is pretty clever, if l < k they do:
                         * `expectedMask << ((k - l) << 3)`
                         * aka delete the lowest (k - l) bytes in expectedMask` and remask the read value.
                         */
                        eq(
                            toIdent(outputVar.namePrefix),
                            bwAnd(
                                read(dataStart()),
                                shl(
                                    const(expectedMask),
                                    shl(
                                        minus(
                                            const(cand.kSize),
                                            read(cand.loadBase.v)
                                        ),
                                        const(3)
                                    )!!
                                )!!
                            )!!
                        )
                    )
                ))
            }
            scriptBuilder.checkSat()
            Scheduler.rpc {
                pool.submit(scriptBuilder.cmdList).first
            }
        }.pcompute().map { res ->
            if(!res.all { it }) {
                return@map null
            }
            { p: SimplePatchingProgram ->
                p.replace(cand.dataLoadLocation.ptr) { c ->
                    listOf(c.plusMeta(BYTESK_CAST))
                }
            }
        }
    }
}
