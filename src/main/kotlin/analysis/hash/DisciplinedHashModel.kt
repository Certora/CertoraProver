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

package analysis.hash

import analysis.*
import analysis.icfg.CallGraphBuilder
import analysis.icfg.CmdPointerSet
import analysis.icfg.MemoryMap
import analysis.icfg.PointsTo
import analysis.numeric.linear.LVar
import analysis.pta.*
import analysis.storage.BytesKeyHash
import config.Config
import datastructures.stdcollections.*
import evm.EVM_WORD_SIZE
import evm.MASK_SIZE
import evm.twoToThe
import instrumentation.calls.CalldataEncoding
import log.Logger
import log.LoggerTypes
import scene.PrecompiledContractCode
import scene.TACMethod
import tac.MetaMap
import tac.Tag
import tac.generation.letVar
import utils.*
import vc.data.*
import vc.data.TACProgramCombiners.andThen
import java.math.BigInteger

private val logger = Logger(LoggerTypes.OPTIMIZE)

/**
 * credit: jtoman for the bytes key handling, shelly for the original implementation of the disciplined hash model.
 */
object DisciplinedHashModel {

    private class HashCandidate(
        val lenSymbol: TACSymbol,
        val ptr: CmdPointer,
        val fieldVar: TACSymbol.Var,
        val rewrite: Rewriter
    )

    /**
     * Basic interface for rewriting a hash application.
     */
    private fun interface Rewriter {
        /**
         * Rewrite the original hash application. [cb] is invoked with a variable into which the rewritten hash should
         * be placed, and is expected to return the code that effects the rewritten hash. This hashing code is then
         * incorporated into the rewrite.
         */
        fun fullRewrite(
            cb: (TACSymbol.Var) -> CommandWithRequiredDecls<TACCmd.Simple>
        ) : CommandWithRequiredDecls<TACCmd.Simple>
    }

    /**
     * A rewriter that allows conditionally rewriting the hash.
     */
    private interface ConditionalRewriter : Rewriter {
        /**
         * Conditionall rewrite the original hash application. [cb] receives two arguments. The first is the variable which
         * is expected to hold the hash application. The second is function which provides access to the original hash;
         * it returns the original hashing command, rewritten to place its results into the variable provided
         * as an argument. [cb] is expected to return code that conditionally
         * rewrites the hash, placing it into the first argument.
         */
        fun conditionalRewrite(
            cb: (TACSymbol.Var, (TACSymbol.Var) -> TACCmd.Simple.AssigningCmd) -> CommandWithRequiredDecls<TACCmd.Simple>
        ) : CommandWithRequiredDecls<TACCmd.Simple>
    }

    private fun IPointsToInformation.constantValueAt(where: CmdPointer, sym: TACSymbol) = when(sym) {
        is TACSymbol.Const -> sym.value
        is TACSymbol.Var -> this.query(ConstantValue(where, sym))
    }

    /**
     * Applies a series of hash-handling passes, and returns a new program from [code].
     * Relies on availability of a memory map [memoryModel] and points-to information [pta].
     */
    fun disciplinedHashModel(
        method: TACMethod,
        memoryModel: MemoryMap,
        pta: IPointsToInformation
    ): CoreTACProgram {
        val code = method.code as CoreTACProgram
        // for testing only: we check if the disciplined hash model is disabled
        // this allows to test Test/ECRecover/runBV check4 rule using encodePacked
        if (!Config.EnabledDisciplinedHashModel.get()) {
            return code
        }

        val patch = code.toPatchingProgram()
        val eff = applyDisciplinedHashModelOnPatch(patch, method, memoryModel, pta)
        eff.applyOnPatch(patch)
        return patch.toCode(code)
    }

    private fun HashRewriteEffect.applyOnPatch(p: SimplePatchingProgram) {
        for((staged, target) in stagedSave) {
            p.addBefore(staged.ptr, listOf(
                TACCmd.Simple.AssigningCmd.AssignExpCmd(
                    lhs = target,
                    rhs = staged.v.asSym()
                )
            ))
            p.addVarDecl(target)
        }
    }

    /**
     * The effects of hash rewriting. [rewrittenCores] have been rewritten by the hash model, and should be ignored by
     * [analysis.icfg.ExtCallSummarization]. [stagedSave] are staged instrumentations which saves the value
     * of [LTACVar] v@L to some temp variable t (the key and value of the map resp)
     *
     */
    data class HashRewriteEffect(
        val stagedSave: Map<LTACVar, TACSymbol.Var>,
        val rewrittenCores: Set<CmdPointer>
    ) {
        companion object {
            val empty = HashRewriteEffect(mapOf(), rewrittenCores = setOf())
        }
    }

    fun fallbackHashModel(
        patch: SimplePatchingProgram,
        code: CoreTACProgram
    ) : HashRewriteEffect {
        handleBytesKeyHashes(code, object : BytesKeyIndexLogic {
            override fun isFinalWordWrite(
                ctp: CoreTACProgram,
                hashLoc: LTACCmdView<TACCmd.Simple.AssigningCmd.AssignSha3Cmd>,
                writePtr: LTACCmdView<TACCmd.Simple.AssigningCmd.ByteStore>
            ): Boolean {
                fun PatternDSL.writePtrVar() = Var { v: TACSymbol.Var, where ->
                    if(v in ctp.analysisCache.gvn.findCopiesAt(
                            target = where.ptr, source = writePtr.ptr to (writePtr.cmd.loc as TACSymbol.Var)
                        )) {
                        PatternMatcher.VariableMatch.Match(Unit)
                    } else {
                        PatternMatcher.VariableMatch.NoMatch
                    }
                }
                fun PatternDSL.hashLocVar() = Var { v: TACSymbol.Var, where ->
                    if(v in ctp.analysisCache.gvn.findCopiesAt(
                            target = where.ptr, source = hashLoc.ptr to (hashLoc.cmd.op1 as TACSymbol.Var)
                        )) {
                        PatternMatcher.VariableMatch.Match(Unit)
                    } else {
                        PatternMatcher.VariableMatch.NoMatch
                    }
                }
                val pattern = PatternDSL.build {
                    ((writePtrVar() + 0x20()).commute - hashLocVar()).second `lor` (0x20() + (writePtrVar() - hashLocVar())).commute.second
                }
                return PatternMatcher.compilePattern(ctp.analysisCache.graph, pattern).query(hashLoc.cmd.op2 as TACSymbol.Var, hashLoc.wrapped) is PatternMatcher.ConstLattice.Match
            }
        }, patcher = patch)
        return HashRewriteEffect.empty
    }

    fun applyDisciplinedHashModelOnPatch(
        patch: SimplePatchingProgram,
        method: TACMethod,
        pointsTo: PointsTo?
    ) : HashRewriteEffect {
        return if(pointsTo?.pta is FlowPointsToInformation && pointsTo.pta.isCompleteSuccess) {
            applyDisciplinedHashModelOnPatch(patch, method, pointsTo.memoryMap, pointsTo.pta)
        } else {
            fallbackHashModel(patch, method.code as CoreTACProgram)
        }
    }

    /**
     * Applies the 3 different 'disciplined hashing' passes on the code with the associated [patch],
     * but just updates the [patch] without returning a new program.
     * @return the set of hash commands that were updated that actually came from [TACCmd.Simple.CallCore] commands
     */
    private fun applyDisciplinedHashModelOnPatch(
        patch: SimplePatchingProgram,
        method: TACMethod,
        memoryModel: MemoryMap,
        pta: IPointsToInformation
    ): HashRewriteEffect {
        val code = method.code as CoreTACProgram
        // this only rewrites callcores calling the hash precompiled, and AssignSha3Cmd with constant length
        val updatedHashCallCoreCmds = adjustHashesToWritePatterns(method, memoryModel, pta, patch)

        if (pta is WithSummaryInformation) {
            // this rewrites [AssignSha3Cmd] commands with non-constant length, and some associated [ByteStore]s
            handleBytesKeyHashes(code, indexLogic = object : BytesKeyIndexLogic {
                override fun isFinalWordWrite(
                    ctp: CoreTACProgram,
                    hashLoc: LTACCmdView<TACCmd.Simple.AssigningCmd.AssignSha3Cmd>,
                    writePtr: LTACCmdView<TACCmd.Simple.AssigningCmd.ByteStore>
                ): Boolean {
                    /**
                    Now we check whether the predecessor write at location l is defined as
                    len = (l + 32) - start

                    where start is the base pointer of the hash and len is the length of the hash (as recorded
                    in the hash command). If so, then this buffer must be at least 32 bytes long, and the last 32 bytes in the buffer
                     *must* be the value written in the bytestore [writePtr]
                     */
                    val start = hashLoc.cmd.op1 as TACSymbol.Var
                    val len = hashLoc.cmd.op2 as TACSymbol.Var
                    return pta.query(QueryInvariants(hashLoc.ptr) {
                        len `=` ((v("base") + 32) - start)
                    }).orEmpty().any { m ->
                        val finalWrite = (m.symbols["base"]!! as LVar.PVar).v
                        finalWrite in ctp.analysisCache.gvn.findCopiesAt(
                            hashLoc.ptr,
                            writePtr.ptr to (writePtr.cmd.loc as TACSymbol.Var)
                        )
                    }
                }
            }, patcher = patch)
            // this rewrites summary commands
            handleExternalGetterHashes(code.analysisCache.graph, pta, patch)
        }
        return updatedHashCallCoreCmds
    }

    fun computeOffsetTaggingFor(
        offsets: List<BigInteger>
    ) : List<BigInteger> {
        val toReturn = mutableListOf<BigInteger>()
        var acc = BigInteger.ZERO
        var counter = 0
        for (sp in offsets.sorted()) {
            if (counter == 8) {
                toReturn.add(acc)
                acc = BigInteger.ZERO
                counter = 0
            }
            check(sp < BigInteger.TWO.pow(32)) {
                "Implausibly large start point of hash $sp"
            }
            acc = acc.shiftLeft(8) or sp
            counter++
        }
        if (counter != 0) {
            toReturn.add(acc)
        }
        return toReturn
    }

    /**
     * Holder class for deciding how to rewrite a hash. [length] is the (potentially assumed) constant length
     * of the buffer being hashed. [expectedStart] is where the hash is expected to start. This is always zero,
     * but this could be relaxed later.
     *
     * [generateUpdate] expects to receive a function that generates the rewritten hash; it returns the code to
     * replace the original hash operation with.
     */
    private data class HashSizes(
        val length: BigInteger,
        val expectedStart: BigInteger,
        val generateUpdate: ((TACSymbol.Var) -> CommandWithRequiredDecls<TACCmd.Simple>) -> CommandWithRequiredDecls<TACCmd.Simple>
    )

    /**
     * Does the classic "disciplined hash model" which adjusts the hash to see the write patterns based on the [memoryModel] and [pta]
     * @return the set of updated [CmdPointer]s that are CallCores
     */
    private fun adjustHashesToWritePatterns(method: TACMethod, memoryModel: MemoryMap, pta: IPointsToInformation, patch: SimplePatchingProgram): HashRewriteEffect {
        val code = method.code as CoreTACProgram
        val g = code.analysisCache.graph
        val updated = mutableSetOf<CmdPointer>()
        val stagedSaves = mutableMapOf<LTACVar, TACSymbol.Var>()
        val hashesToRewrite = g.commands.mapNotNull {
            if(it.ptr !in memoryModel) {
                return@mapNotNull null
            }
            if(it.cmd is TACCmd.Simple.AssigningCmd.AssignSha3Cmd) {
                HashCandidate(
                    lenSymbol = it.cmd.op2,
                    fieldVar = it.cmd.op1 as? TACSymbol.Var ?: return@mapNotNull null,
                    ptr = it.ptr,
                    rewrite = object : ConditionalRewriter {
                        override fun conditionalRewrite(cb: (TACSymbol.Var, (TACSymbol.Var) -> TACCmd.Simple.AssigningCmd) -> CommandWithRequiredDecls<TACCmd.Simple>): CommandWithRequiredDecls<TACCmd.Simple> {
                            return cb(it.cmd.lhs) { newLhs ->
                                it.cmd.copy(lhs = newLhs)
                            }
                        }

                        override fun fullRewrite(cb: (TACSymbol.Var) -> CommandWithRequiredDecls<TACCmd.Simple>): CommandWithRequiredDecls<TACCmd.Simple> {
                            return cb(it.cmd.lhs)
                        }
                    }
                )
            } else if(it.cmd is TACCmd.Simple.CallCore && it.cmd.inBase == TACKeyword.MEMORY.toVar() &&
                it.cmd.inOffset is TACSymbol.Var && pta.constantValueAt(it.ptr, it.cmd.to) == PrecompiledContractCode.sha256.address &&
                pta.constantValueAt(it.ptr, it.cmd.outSize) == EVM_WORD_SIZE) {
                HashCandidate(
                    lenSymbol = it.cmd.inSize,
                    fieldVar = it.cmd.inOffset,
                    ptr = it.ptr,
                    rewrite = { gen ->
                        val tmp = TACKeyword.TMP(Tag.Bit256, "!sha256").toUnique("!")
                        val output = gen(tmp)
                        output.merge(listOf(
                            TACCmd.Simple.AssigningCmd.ByteStore(
                                base = it.cmd.outBase,
                                loc = it.cmd.outOffset,
                                value = tmp
                            ),
                            TACCmd.Simple.AssigningCmd.ByteStore(
                                base = TACKeyword.RETURNDATA.toVar(),
                                loc = BigInteger.ZERO.asTACSymbol(),
                                value = tmp
                            ),
                            TACCmd.Simple.AssigningCmd.AssignExpCmd(
                                lhs = TACKeyword.RETURN_SIZE.toVar(),
                                rhs = EVM_WORD_SIZE.asTACExpr
                            ),
                            TACCmd.Simple.AssigningCmd.AssignExpCmd(
                                lhs = TACKeyword.RETURNCODE.toVar(),
                                rhs = BigInteger.ONE.asTACExpr
                            )
                        )).merge(
                            TACKeyword.RETURN_SIZE.toVar(),
                            TACKeyword.RETURNCODE.toVar(),
                            TACKeyword.RETURNDATA.toVar(),
                            tmp

                        )
                    }
                )
            } else {
                null
            }
        }
        hashesToRewrite.forEach { hashCmd ->
            val constLen = pta.constantValueAt(hashCmd.ptr, hashCmd.lenSymbol)
            val (length, expectedStart, rewriter) = if(constLen != null) {
                HashSizes(
                    constLen, BigInteger.ZERO, generateUpdate = hashCmd.rewrite::fullRewrite
                )
            } else if(hashCmd.lenSymbol is TACSymbol.Var && pta.query(QueryInvariants(hashCmd.ptr) {
                hashCmd.lenSymbol `=` TACKeyword.CALLDATASIZE.toVar()
            }).isNullOrEmpty() && hashCmd.rewrite is ConditionalRewriter) {
                /**
                 * If we are hashing a buffer of the length of calldata, AND we have a good guess as to what calldata *should*
                 * be, we can generate a rewrite conditional on the calldata buffer being the expected (constant) size.
                 *
                 * NB it is *not* sound to just use this constant size, as someone could pass along extra junk past the
                 * expected args.
                 */
                val cd = method.calldataEncoding as CalldataEncoding
                if(!cd.valueTypesArgsOnly || cd.expectedCalldataSize == null) {
                    return@forEach logger.info {
                        "Length of hash $hashCmd in ${code.name} is calldatasize without a reasonable guess for its size; skipping"
                    }
                }
                HashSizes(cd.expectedCalldataSize, BigInteger.ZERO) { gen ->
                    hashCmd.rewrite.conditionalRewrite { lhs, originalGen ->
                        val orig = TACKeyword.TMP(Tag.Bit256, "!orig")
                        val origAssign = originalGen(orig)
                        val newHash = TACKeyword.TMP(Tag.Bit256, "rewrite")
                        val newAssign = gen(newHash)
                        TXF {
                            TACKeyword.CALLDATASIZE.toVar() eq cd.expectedCalldataSize
                        }.letVar(Tag.Bool) { decider ->
                            origAssign andThen newAssign andThen CommandWithRequiredDecls(listOf(
                                TACCmd.Simple.AssigningCmd.AssignExpCmd(
                                    lhs = lhs,
                                    rhs = TXF {
                                        ite(
                                            decider,
                                            newHash,
                                            orig
                                        )
                                    }
                                )
                            ), lhs, orig, newHash)
                        }
                    }
                }
            } else {
                return@forEach logger.info {
                    "Length of hash $hashCmd in ${code.name} is non-constant, cannot handle this case"
                }
            }
            val nodes = memoryModel[hashCmd.ptr]!!.nodes
            val baseNode = (pta.fieldNodesAt(
                hashCmd.ptr,
                hashCmd.fieldVar
            ) as? IndexedWritableSet)?.indexed?.singleOrNull() // expecting just a single pointer
            val deconstruction = baseNode?.let { nodes.byteAddressed[it.node] }?.filter {
                it.key.start < length
            } ?: return@forEach logger.info {
                "Failed to find a singleton, indexed byte-addressed node for base pointer for hash $hashCmd in ${code.name}"
            }
            /* do not support hashing within arrays.
               We could, but the logic isn't implemented yet
             */
            if(!baseNode.index.isConstant || baseNode.index.c != expectedStart) {
                logger.info {
                    "Within ${code.name}, found hash command $hashCmd which does not begin at expected start $expectedStart. Not dealing with this case"
                }
                return@forEach
            }
            val startPoints = deconstruction.keys.map { it.start }
            val lastElem = deconstruction.maxByOrNull {
                it.key.start
            } ?: return@forEach
            /*
             * hole-free, contiguous blocks
             */
            val contiguous = startPoints.all { startPoint ->
                startPoint == BigInteger.ZERO || deconstruction.keys.any { it.end == startPoint - BigInteger.ONE }
            } &&
                startPoints.size == startPoints.distinct().size && // no duplicates of start point
                startPoints.any { it == BigInteger.ZERO } && // starts at zero
                lastElem.key.end >= (length - BigInteger.ONE) // ends (at least) at length
            if (!contiguous) {
                return@forEach
            }
            val useWordAlignedHashing = startPoints.all { start ->
                start.mod(EVM_WORD_SIZE) == BigInteger.ZERO
            } && !Config.AggressiveHashDecomposition.get()

            // hole-free, continuous blocks:
            val args = deconstruction
                .mapValues { (it.value.writeCmdPtrSet as? CmdPointerSet.CSet)?.cs?.singleOrNull() }
                .mapKeys { it.key.start }
            if(args.values.any { it == null }) {
                logger.warn { "Could not extract definite writes for buffer for hash at ${hashCmd.ptr} in ${code.name}" }
                return@forEach
            }

            /**
             * Get the value of the symbol. If not a constant or global, use a special "save" variable which captures the value
             * of the symbol at the location it is written.
             *
             * Q: What about loops; is the saving still sound?
             * A: Yes. The concern to address here is whether by saving a value within a loop iteration, we end up saving
             * the value of a variable _v_ at iteration _i_, when the actual value in the buffer is _v_ at iteration _j_
             * where j < i.
             *
             * It turns out this can't happen from the soundness of the CGB analysis and the [CmdPointerSet] abstraction.
             * The [CmdPointerSet] abstraction attached to a heap location is a [CmdPointerSet.CSet] iff the heap location
             * was populated by a strong update. Further, we insist that this [CmdPointerSet.CSet] is singleton. The assignment
             * that is our singleton member must therefore:
             * a. reach our hash location
             * b. Be the only such defining write that reaches the hash location
             * c. definitely occurs
             *
             * In other words, when we reach the hash location, the value in the buffer *must* have been written at the
             * most recent execution of L. Thus, if L is in a loop, it must have been the last iteration.
             */
            fun CmdPointerSet.CSet.BufferSymbol.resolve() = when(this) {
                is CmdPointerSet.CSet.BufferSymbol.Global -> this.v
                is CmdPointerSet.CSet.BufferSymbol.WrittenAt -> {
                    when(this.what) {
                        is TACSymbol.Var -> {
                            val toRet = LTACVar(where, what)
                            val repl = stagedSaves.getOrPut(toRet) {
                                TACSymbol.Var(
                                    what.namePrefix + "!saved",
                                    what.tag
                                ).toUnique("!")
                            }
                            repl
                        }
                        is TACSymbol.Const -> this.what
                    }
                }
            }

            // SimpleHash should be used instead of HashStringLen in certain cases - if it could still happen here, we want to throw an exception
            if (Config.MatchStorageLikeHashesInUnreservedSlots.get() &&
                ((length == EVM_WORD_SIZE && startPoints.size == 1 && startPoints.containsAll(
                    listOf(
                        BigInteger.ZERO
                    )
                ))
                    || (length == EVM_WORD_SIZE * BigInteger.TWO && startPoints.size == 2 && startPoints.containsAll(
                    listOf(BigInteger.ZERO, EVM_WORD_SIZE)
                ))
                    )
            ) {
                if (g.elab(hashCmd.ptr).cmd is TACCmd.Simple.CallCore) {
                    updated.add(hashCmd.ptr)
                }
                patch.replaceCommand(
                    hashCmd.ptr,
                    rewriter { lhs ->
                        CommandWithRequiredDecls(listOf(
                            TACCmd.Simple.AssigningCmd.AssignSimpleSha3Cmd(
                                lhs,
                                length = length.asTACSymbol(),
                                startPoints.sorted().map { sp -> args[sp]!!.first.resolve() }
                            )
                        ), lhs)
                    }
                )
                return@forEach
            }
            val argInstrumentation = mutableListOf<TACCmd.Simple>()
            val tempVars = mutableSetOf<TACSymbol.Var>()
            val instArgs = mutableListOf<TACSymbol>()
            if(!useWordAlignedHashing) {
                computeOffsetTaggingFor(startPoints).mapTo(instArgs) {
                    it.asTACSymbol()
                }
            }
            startPoints.sorted().forEachIndexed { ind, sp ->
                val (store, range) = args[sp]!!
                val isLast = ind == startPoints.lastIndex
                val clampedRange = if(useWordAlignedHashing) {
                    CmdPointerSet.fullWord
                } else if(isLast) {
                    val lastRange = deconstruction.keys.find {
                        it.start == sp
                    }!!
                    if(lastRange.end == length - BigInteger.ONE) {
                        range
                    } else {
                        val clampSpec = lastRange.intersect(CallGraphBuilder.ByteRange(sp, end = length - BigInteger.ONE))
                        check(clampSpec is CallGraphBuilder.ByteRange.OverlapEffect.StrictlyContainedWithin)
                        clampSpec.narrowRange(range)
                    }
                } else {
                    range
                }
                val intersected = CmdPointerSet.fullWord.intersect(clampedRange)
                /**
                 * Q: What about truncation? How do we know we're dealing with containment?
                 * A: The ranges under consideration here are the ranges that describe the "slice" of the full
                 * word write that remains live in a buffer. In other words, we start with [0,31]
                 * and only ever shrink this range. In other words, if we had truncate upper or lower, this would imply that somehow we
                 * were talking about a range that went off the end of the word (e.g. byterange [-4, 5] or [22, 43];
                 * the latter looks initially reasonable, until you realize it's describing bytes in a 32 byte word...)
                 */
                check(intersected is CallGraphBuilder.ByteRange.OverlapEffect.Containment)
                val seedExpr : TACSymbol = when(val sym = store.resolve()) {
                    is TACSymbol.Const -> {
                        sym
                    }
                    is TACSymbol.Var -> {
                        if (sym.tag == Tag.Bool) {
                            val ret = TACKeyword.TMP(Tag.Bit256, "!").toUnique()
                            argInstrumentation.add(exp(tempVars) {
                                ret `=` TACExpr.TernaryExp.Ite(
                                    i = sym.asSym(),
                                    t = BigInteger.ONE.asTACSymbol().asSym(),
                                    e = BigInteger.ZERO.asTACSymbol().asSym()
                                )
                            })
                            ret
                        } else {
                            sym
                        }
                    }
                }
                if(intersected is CallGraphBuilder.ByteRange.OverlapEffect.Contains) {
                    check(range == CmdPointerSet.fullWord || isLast)
                    instArgs.add(seedExpr)
                } else {
                    check(intersected is CallGraphBuilder.ByteRange.OverlapEffect.StrictlyContainedWithin)
                    val ret = TACKeyword.TMP(Tag.Bit256, "!").toUnique()
                    argInstrumentation.add(exp(tempVars) {
                        ret `=` TACExpr.BinOp.BWAnd(
                            MASK_SIZE(intersected.sz.toInt() * 8).asTACSymbol().asSym(),
                            TACExpr.BinOp.ShiftRightLogical(
                                seedExpr.asSym(),
                                (intersected.offsetFromEnd * twoToThe(3)).asTACSymbol().asSym()
                            )
                        )
                    })
                    instArgs.add(ret)
                }
            }
            if (g.elab(hashCmd.ptr).cmd is TACCmd.Simple.CallCore) {
                updated.add(hashCmd.ptr)
            }
            patch.replaceCommand(
                hashCmd.ptr,
                rewriter { lhs ->
                    CommandWithRequiredDecls(
                        argInstrumentation + TACCmd.Simple.AssigningCmd.AssignSimpleSha3Cmd(lhs, length.asTACSymbol(), instArgs).mapMeta {
                            it.plus(TACMeta.DECOMPOSED_USER_HASH)
                        },
                        tempVars
                    )
                }
            )
        }
        return HashRewriteEffect(rewrittenCores = updated, stagedSave = stagedSaves)
    }

    private interface BytesKeyIndexLogic {
        /**
         * Return true if the write of a word at [writePtr] writes the final 32 bytes hashed at [hashLoc] in
         * [ctp]. In other words, if the hash at [hashLoc] has `start` and `len`, check if the location `l` written
         * at [writePtr] satisfies `len = (l + 32) - start`
         */
        fun isFinalWordWrite(
            ctp: CoreTACProgram,
            hashLoc: LTACCmdView<TACCmd.Simple.AssigningCmd.AssignSha3Cmd>,
            writePtr: LTACCmdView<TACCmd.Simple.AssigningCmd.ByteStore>,
        ) : Boolean
    }

    /**
     * Recognize code that looks like a bytes key storage mapping hash. The criteria for such a hash
     * is a keccak256 of a buffer in memory where the last 32 bytes of the buffer are given by the write of some
     * symbol M. For such a buffer, M is the mapping location, and the prefix of the buffer less these last 32 bytes are
     * the bytes key. This code inserts a BytesKeyHash summary indicating that we are getting a storage slot out in [BytesKeyHash.output]
     * for some key in map [BytesKeyHash.slot], whose representative hash is given in [BytesKeyHash.keyHash].
     */
    private fun handleBytesKeyHashes(ctp: CoreTACProgram, indexLogic: BytesKeyIndexLogic, patcher: SimplePatchingProgram) {
        /**
         * Find all hashes where the base and length are variables, and with a single successor
         */
        ctp.parallelLtacStream().mapNotNull {
            it.maybeNarrow<TACCmd.Simple.AssigningCmd.AssignSha3Cmd>()?.takeIf {
                it.cmd.memBaseMap == TACKeyword.MEMORY.toVar() && it.cmd.op1 is TACSymbol.Var && it.cmd.op2 is TACSymbol.Var &&
                    ctp.analysisCache.graph.succ(it.ptr).size == 1
            }
        }.mapNotNull {
            /**
             * Look in the prefix within the same block for the most recent byte store, where the location of the
             * byte store is a variable. Failure to find one excludes this hash from further consideration
             */
            it `to?` ctp.analysisCache.graph.iterateUntil(it.ptr).reversed().takeWhile {
                it.cmd !is TACCmd.Simple.ByteLongCopy
            }.firstMapped {
                it.maybeNarrow<TACCmd.Simple.AssigningCmd.ByteStore>()?.takeIf {
                    it.cmd.base == TACKeyword.MEMORY.toVar() && it.cmd.loc is TACSymbol.Var
                }
            }
        }.filter { (hash, prev) ->
            /**
              Now we check whether the predecessor write at location l is defined as
              len = (l + 32) - start

             The method we use for this depends on whether we have PTA successes or not
             */
            indexLogic.isFinalWordWrite(
                ctp, hash, prev
            )
        }.sequential().forEach { (hash, prevSlotWrite) ->
            val stringHash = TACSymbol.Factory.getFreshAuxVar(
                TACSymbol.Factory.AuxVarPurpose.SUMMARY,
                hash.cmd.op1 as TACSymbol.Var
            )
            val len = TACSymbol.Factory.getFreshAuxVar(
                TACSymbol.Factory.AuxVarPurpose.SUMMARY,
                hash.cmd.op2 as TACSymbol.Var
            )

            /**
             * Hash the prefix of the buffer, excluding the final 32 bytes. the result of this hash is used as the representative
             * hash of the bytes key
             */
            val instrumentation = mutableListOf<TACCmd.Simple>(
                TACCmd.Simple.AssigningCmd.AssignExpCmd(
                    lhs = len,
                    rhs = TACExpr.BinOp.Sub(
                        hash.cmd.op2.asSym(),
                        EVM_WORD_SIZE.asTACExpr
                    )
                ),
                TACCmd.Simple.AssigningCmd.AssignSha3Cmd(
                    lhs = stringHash,
                    memBaseMap = TACKeyword.MEMORY.toVar(),
                    op1 = hash.cmd.op1,
                    op2 = len
                )
            )
            val added = mutableSetOf(len, stringHash)

            /**
             * Record the value written in our predecessor byte store as the base map.
             */
            val slot = when(val writtenSlot = prevSlotWrite.cmd.value) {
                is TACSymbol.Const -> writtenSlot
                /**
                 * Save the value of slot at the point of the bytestore in a temporary variable in case it gets overwritten
                 * between the bytestore and the hash (extremely unlikely)
                 */
                is TACSymbol.Var -> if(writtenSlot !in ctp.analysisCache.gvn.findCopiesAt(hash.ptr, prevSlotWrite.ptr to writtenSlot)) {
                    val savedSlot = TACSymbol.Factory.getFreshAuxVar(
                        TACSymbol.Factory.AuxVarPurpose.SUMMARY,
                        writtenSlot
                    )
                    patcher.addBefore(prevSlotWrite.ptr, listOf(TACCmd.Simple.AssigningCmd.AssignExpCmd(
                        lhs = savedSlot,
                        rhs = writtenSlot.asSym()
                    )))
                    added.add(savedSlot)
                    savedSlot
                } else {
                    writtenSlot
                }
            }
            val (isolated, succ) = patcher.splitBlockRange(hash.ptr, hash.ptr)
            check(succ.size == 1)
            instrumentation.add(TACCmd.Simple.SummaryCmd(BytesKeyHash(
                output = hash.cmd.lhs,
                keyHash = stringHash,
                slot = slot,
                originalBlockStart = isolated,
                skipTarget = succ.single(),
                modifiedVars = setOf(hash.cmd.lhs),
                summarizedBlocks = setOf(isolated)
            ), meta = MetaMap()
            ))
            val summaryBlock = patcher.addBlock(isolated, instrumentation)
            patcher.reroutePredecessorsTo(isolated, summaryBlock) { pred ->
                pred != summaryBlock
            }
            patcher.addVarDecls(added)
        }
    }

    /*
      When external map getter summarization runs, we don't have allocation, init, or points to information
      so it is a "conditional summary" which only has meaning if the assumptions about the behavior are correct,
      i.e., the copy we observe is to a freshly allocated object, etc. etc.

      At this point we do have the information to fully interpret the summary (hence the call below) and translate
      the conditional summary into a BytesKeyHash summary (hashing the string key for hooks as usual).
     */
    private fun handleExternalGetterHashes(
        g: TACCommandGraph,
        pta: IPointsToInformation,
        patch: SimplePatchingProgram
    ) {
        g.commands.parallelStream().mapNotNull {
            it.maybeNarrow<TACCmd.Simple.SummaryCmd>()?.takeIf {
                it.cmd.summ is ExternalMapGetterSummarization.ExternalGetterHash
            }
        }.filter {
            pta.query(GetterHashValid(it.ptr)) == true
        }.sequential().forEach {
            val getterHash = it.cmd.summ as ExternalMapGetterSummarization.ExternalGetterHash
            val len = TACSymbol.Factory.getFreshAuxVar(TACSymbol.Factory.AuxVarPurpose.SUMMARY, getterHash.keyArray)
            val elemData =
                TACSymbol.Factory.getFreshAuxVar(TACSymbol.Factory.AuxVarPurpose.SUMMARY, getterHash.keyArray)
            val hash = TACSymbol.Factory.getFreshAuxVar(TACSymbol.Factory.AuxVarPurpose.SUMMARY, getterHash.keyArray)
            val body = mutableListOf<TACCmd.Simple>()
            body.add(
                TACCmd.Simple.AssigningCmd.ByteLoad(
                    lhs = len,
                    loc = getterHash.keyArray,
                    base = TACKeyword.MEMORY.toVar()
                )
            )
            body.add(
                TACCmd.Simple.AssigningCmd.AssignExpCmd(
                    lhs = elemData,
                    rhs = TACExpr.Vec.Add(
                        getterHash.keyArray.asSym(),
                        32.toBigInteger().asTACSymbol().asSym()
                    )
                )
            )
            body.add(
                TACCmd.Simple.AssigningCmd.AssignSha3Cmd(
                    memBaseMap = TACKeyword.MEMORY.toVar(),
                    op2 = len,
                    op1 = elemData,
                    lhs = hash
                )
            )
            patch.addVarDecls(
                setOf(
                    len, elemData, hash
                )
            )
            patch.replaceCommand(
                it.ptr, body + TACCmd.Simple.SummaryCmd(
                    BytesKeyHash(
                        slot = getterHash.slot,
                        keyHash = hash,
                        skipTarget = getterHash.skipTarget,
                        originalBlockStart = getterHash.originalBlockStart,
                        summarizedBlocks = setOf(getterHash.originalBlockStart),
                        modifiedVars = setOf(getterHash.output),
                        output = getterHash.output,
                    ),
                    meta = MetaMap()
                ), getterHash.successors
            )
        }
    }
}
