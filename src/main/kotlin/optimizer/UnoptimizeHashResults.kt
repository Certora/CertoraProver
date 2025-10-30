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
import compiler.applyKeccak
import config.Config
import datastructures.stdcollections.*
import evm.EVM_WORD_SIZE
import evm.EVM_WORD_SIZE_INT
import log.*
import tac.Tag
import utils.mapNotNull
import vc.data.*
import java.math.BigInteger
import java.util.stream.Stream

/**
 * ## Background
 *
 * The Solidity compiler likes to optimize hash results for storage access if all arguments are constant.
 * This applies to both simple and nested hash applications (i.e., flat and nested storage accesses to maps and arrays).
 * Due to our (current) hash modeling, this can lead to:
 * - Imprecision. See rule `eq` in `Test/StorageOptimize/eq.spec` - get getting 0 as an argument and get0 with 0 as a constant are not the same.
 * - Unsoundness. See rule `update` in `Test/StorageOptimize/eq.spec` - we don't see the write to 0 since we're querying the constant slot
 *
 * We will therefore replace those pre-computed constants with the original hash applications.
 * We can do that because the Solidity compiler, despite the optimizations, is too lazy to optimize away the memory writes
 * reserved for hash computations (aka M[0x0] and M[0x20]).
 *
 * We can then, for each storage access to a constant slot, attempt to resolve M[0x0] and M[0x20] and
 * if the hash of either M[0x0] (array access) or M[0x0],M[0x20] (map access) match, replace the constant.
 *
 * This gets especially tricky if structs are involved, since the offset to a particular field in the struct is also optimized.
 * We will utilize the already existing configuration ([Config.StructSizeLimit]) for struct sizes to allow deviations from the hash result.
 * The same applies to constant indices of an array.
 *
 * ## Limitations
 * Our ability to actually rebuild the access path is limited though,
 * as the storage of arrays of structs could be non-discernible.
 * (e.g., when we access array_hash+2, is it a struct of size 2 and we access a[0].secondElement,
 * or is it a struct of size 1 and we access a[1].onlyElement?)
 *
 * Another problem is that for nested accesses, the Solidity compiler does optimize away the outer accesses.
 *
 * ## Conclusion
 * Thus, the process of "unoptimizing" is only for attempting to rebuild more of the structure for the sake of the storage analysis.
 * Precise SMT handling should be corrected by correcting the hash model we use.
 */
class UnoptimizeHashResults(private val code: CoreTACProgram) {
    private val g = code.analysisCache.graph
    private val def = NonTrivialDefAnalysis(g)
    private val patch = code.toPatchingProgram()

    private val constantAt = MustBeConstantAnalysis(g, def)


    private sealed class ConstantBaseRewrite {
        abstract val rewriteLoc: LTACCmdView<TACCmd.Simple.AssigningCmd>
        abstract fun applyRewrite(patch: SimplePatchingProgram)

        /**
         * [baseSlot] == M0x0 constant @ [rewriteLoc]
         * hash(M0x0) == val @ [rewriteLoc]
         * ----------------------------------------------------
         * [rewriteLoc]: x := val ~> [rewriteLoc]: x := hash([baseSlot])
         */
        data class Simple(
            override val rewriteLoc: LTACCmdView<TACCmd.Simple.AssigningCmd>,
            val baseSlot: BigInteger,
        ) : ConstantBaseRewrite() {
            override fun applyRewrite(patch: SimplePatchingProgram) {
                patch.replaceCommand(
                    rewriteLoc.ptr,
                    listOf(
                        TACCmd.Simple.AssigningCmd.AssignSimpleSha3Cmd(
                            lhs = rewriteLoc.cmd.lhs,
                            length = EVM_WORD_SIZE.asTACSymbol(),
                            args = listOf(baseSlot.asTACSymbol())
                        ),
                    )
                )
            }
        }

        /**
         * M0x0 constant @ [rewriteLoc]
         * hash(M0x0) + [offset] == val @ [rewriteLoc]
         * ----------------------------------------------------
         *    [rewriteLoc]:
         *      x := val + [offsetVar]
         * ~> [rewriteLoc]:
         *      result := hash([baseSlot])
         *      elemStart := result + [offsetVar]
         *      x := elemStart + [offset]
         */
        data class WithOffset(
            override val rewriteLoc: LTACCmdView<TACCmd.Simple.AssigningCmd>,
            val offsetVar: TACSymbol.Var,
            val baseSlot: BigInteger,
            val baseDefLoc: LTACCmd,
            val offset: BigInteger
        ) : ConstantBaseRewrite() {
            override fun applyRewrite(patch: SimplePatchingProgram) {
                val arrayStart = TACKeyword.TMP(Tag.Bit256, "!hashResult").toUnique("!")
                val elementStart = TACKeyword.TMP(Tag.Bit256, "!elementStart").toUnique("!")
                patch.addVarDecls(setOf(arrayStart, elementStart))
                patch.replaceCommand(
                    rewriteLoc.ptr, listOf(
                        TACCmd.Simple.AssigningCmd.AssignSimpleSha3Cmd(
                            lhs = arrayStart,
                            length = EVM_WORD_SIZE.asTACSymbol(),
                            args = listOf(baseSlot.asTACSymbol())
                        ),
                        TACCmd.Simple.AssigningCmd.AssignExpCmd(
                            lhs = elementStart,
                            rhs = TACExpr.Vec.Add(
                                offsetVar.asSym(),
                                arrayStart.asSym()
                            )
                        ),
                        TACCmd.Simple.AssigningCmd.AssignExpCmd(
                            lhs = rewriteLoc.cmd.lhs,
                            rhs = TACExpr.Vec.Add(
                                elementStart.asSym(),
                                offset.asTACExpr
                            )
                        )
                    )
                )
            }
        }
    }

    /**
     * If the index into an array is non-constant,
     * Solidity will still inline the hash of the array slot if the slot is constant.
     * If the array contains structs, then the offset within the struct is included in the inlined constant. This pattern
     * matches such cases, matching a constant that equals the hash of the current contents of mem0 + fuzz, where fuzz
     * is our struct size limit flag.
     */
    private val hashConst = PatternMatcher.Pattern.FromConstant(
        extractor = { c, where ->
            val mem0Const = constantAt.mustBeConstantAt(where = where.ptr, TACKeyword.MEM0.toVar())
            val hash = mem0Const?.let { hashBigInts(mem0Const) }
            if (hash != null && c.value >= hash && (c.value - hash) < Config.StructSizeLimit.get().toBigInteger()) {
                Triple(where, mem0Const, c.value - hash)
            } else {
                null
            }
        },
        out = { _, it -> it}
    ).asBuildable()

    private val constantArrayBasePattern = PatternDSL.build {
        (Var + hashConst).commute.withAction { where, v, (baseDefLoc, baseSlot, offset) ->
            ConstantBaseRewrite.WithOffset(
                rewriteLoc = where.narrow<TACCmd.Simple.AssigningCmd>(),
                offsetVar = v,
                baseSlot = baseSlot,
                baseDefLoc = baseDefLoc,
                offset = offset
            )
        }
    }

    private val constantArrayBaseWithOffsetPattern = PatternDSL.build {
        // x + <reversible_hash>
        constantArrayBasePattern.asBuildable() lor

            // K + (x + <reversible_hash>)
            (Const + constantArrayBasePattern.asBuildable()).commute.withAction { where, k, rewrite ->
                rewrite.copy(rewriteLoc = where.narrow(), offset = rewrite.offset + k)
            } lor

            commuteThree(
               Var, Const, hashConst, PatternDSL.CommutativeCombinator.add,
            ) { where, v, k, (baseDefLoc, baseSlot, offset) ->
                ConstantBaseRewrite.WithOffset(
                    rewriteLoc = where.narrow(),
                    offsetVar = v,
                    baseSlot = baseSlot,
                    baseDefLoc = baseDefLoc,
                    offset = offset + k
                )

            }
    }

    private val constantArrayBaseMatcher = PatternMatcher.compilePattern(g, constantArrayBaseWithOffsetPattern)

    private fun matchConstantArrayBase(src: LTACCmd, loc: TACSymbol.Var): Collection<ConstantBaseRewrite>? {
        return constantArrayBaseMatcher.query(loc,src).toNullableResult()?.let(::setOf)
    }

    private fun matchSimpleBase(src: LTACCmd, loc: TACSymbol.Var): Collection<ConstantBaseRewrite> {
        return def.nontrivialDef(loc, src).mapNotNull { defSite ->
            val defSiteCmd = g.elab(defSite)
            val c = defSiteCmd.maybeExpr<TACExpr.Sym.Const>()?.exp?.s ?: return@mapNotNull null
            val mem0Const = constantAt.mustBeConstantAt(where = defSite, TACKeyword.MEM0.toVar()) ?: return@mapNotNull null
            val hash = hashBigInts(mem0Const)

            ConstantBaseRewrite.Simple(defSiteCmd.narrow<TACCmd.Simple.AssigningCmd>(), mem0Const).takeIf {
                c.value == hash
            }
        }
    }

    fun doWork(): CoreTACProgram {
        data class ConstantPatchingIR(
            val where: LTACCmdView<TACCmd.Simple.AssigningCmd.AssignExpCmd>,
            val m0x0def: BigInteger?,
            val m0x20def: BigInteger?,
            val mapAccessHash: BigInteger?,
            val arrayAccessHash: BigInteger?
        )
        val mutatedCommands = mutableSetOf<CmdPointer>()
        val constantLocsDefs = getConstantLocsDefs()
        constantLocsDefs.mapNotNull { constantLocDef ->
            // resolve M[0x0] and M[0x20]
            val m0x0def = MustBeConstantAnalysis(g, def).mustBeConstantAt(constantLocDef.ptr, TACKeyword.MEM0.toVar())
            val m0x20def = MustBeConstantAnalysis(g, def).mustBeConstantAt(constantLocDef.ptr, TACKeyword.MEM32.toVar())

            // if both m0x0 and m0x20 are not null, we deal with a map access
            val mapAccessHash = if (m0x0def != null && m0x20def != null) {
                hashBigInts(m0x0def, m0x20def)
            } else {
                null
            }
            val arrayAccessHash = if (m0x0def != null) {
                hashBigInts(m0x0def)
            } else {
                null
            }

            if (mapAccessHash == arrayAccessHash) {
                // if both map access hash and array access hash are null (i.e. no m0x0,m0x20 defined)
                // then we can skip.
                // if both are not null and equal, this is so highly improbable we can just ignore it
                if (mapAccessHash != null) {
                    Logger.alwaysWarn("M[0x0]=M[0x20]=$m0x0def, this is highly improbable, skipping constant loc definition $constantLocDef")
                }
                return@mapNotNull null
            }
            return@mapNotNull ConstantPatchingIR(constantLocDef, m0x0def, m0x20def, mapAccessHash, arrayAccessHash)
        }.sequential().forEach { (constantLocDef, m0x0def, m0x20def, mapAccessHash, arrayAccessHash) ->

            val ourConstant = getConstant(constantLocDef)
            val maxDiff = BigInteger.TWO.pow(
                Config.StructSizeLimit.get()
            )

            // The case splitting!
            val deltaMapHash = mapAccessHash?.let { ourConstant - it }
            val deltaArrayHash = arrayAccessHash?.let { ourConstant - it }
            val hashResSymbol = when {
                // We start with the simple cases - exact matches
                ourConstant == mapAccessHash -> {
                    patchConstant(
                        constantLocDef,
                        BigInteger.ZERO,
                        m0x0def!!,
                        m0x20def!!
                    )
                }
                ourConstant == arrayAccessHash -> {
                    patchConstant(
                        constantLocDef,
                        BigInteger.ZERO,
                        m0x0def!!
                    )
                }
                // if our constant is within the max bound for both mapAccessHash and arrayAccessHash, then this is weird and an error
                mapAccessHash != null && arrayAccessHash != null &&
                        BigInteger.ZERO <= deltaMapHash && deltaMapHash!! <= maxDiff
                        && BigInteger.ZERO <= deltaArrayHash && deltaArrayHash!! <= maxDiff -> {
                    Logger.alwaysWarn("Our constant $ourConstant is within $maxDiff of both map access hash $mapAccessHash and array access hash $arrayAccessHash at $constantLocDef, this cannot be handled")
                    return@forEach
                }
                // offset from mapAccessHash
                mapAccessHash != null && BigInteger.ZERO <= deltaMapHash!! && deltaMapHash <= maxDiff -> {
                    val offset = deltaMapHash
                    patchConstant(
                        constantLocDef,
                        offset,
                        m0x0def!!,
                        m0x20def!!
                    )
                }
                arrayAccessHash != null && BigInteger.ZERO <= deltaArrayHash!! && deltaArrayHash <= maxDiff -> {
                    val offset = deltaArrayHash
                    patchConstant(
                        constantLocDef,
                        offset,
                        m0x0def!!
                    )
                }
                // otherwise, we cannot replace the constant
                else -> {
                    return@forEach
                }
            }
            mutatedCommands.add(constantLocDef.ptr)

            // now rewrite all constants equal to our constant with hashResSymbol
            val substitutor = object : DefaultTACCmdMapper() {
                override fun mapSymbol(t: TACSymbol): TACSymbol {
                    return if (t is TACSymbol.Const && t.value == ourConstant) {
                        hashResSymbol
                    } else {
                        t
                    }
                }
            }

            g.iterateBlock(constantLocDef.ptr).forEach { lcmd ->
                if (lcmd.cmd is TACCmd.Simple.AssigningCmd.AssignExpCmd && ourConstant.asTACSymbol() in lcmd.cmd.getRhs()) {
                    mutatedCommands.add(lcmd.ptr)
                    patch.replaceCommand(
                        lcmd.ptr,
                        listOf(
                            substitutor.map(lcmd.cmd)
                        )
                    )
                }
            }
        }

        code.parallelLtacStream().mapNotNull {
            if(it.cmd !is TACCmd.Simple.StorageAccessCmd) {
                null
            } else if(it.cmd.loc !is TACSymbol.Var) {
                Logger.alwaysWarn("Symbol ${it.cmd.loc} at $it is already a constant - may have to introduce a dummy variable")
                null
            } else {
                it
            }
        }.mapNotNull {
            val loc = (it.cmd as TACCmd.Simple.StorageAccessCmd).loc as TACSymbol.Var
            matchConstantArrayBase(it, loc)
                ?: matchSimpleBase(it, loc)
        }.sequential().forEach { rewrites ->
            rewrites.filter {
                it.rewriteLoc.ptr !in mutatedCommands
            }.forEach { toRewrite ->
                mutatedCommands.add(toRewrite.rewriteLoc.ptr)
                toRewrite.applyRewrite(patch)
            }
        }

        return patch.toCode(code)
    }

    /**
     * We have loc := const
     * and we wish to replace it with:
     * hashRes = SimpleHash(hashedWords)
     * [optional] hashWithOffset = hashRes + offset
     * loc = hashRes (or: hashWithOffset)
     *
     * @returns the variable holding the result of the hash
     */
    private fun patchConstant(
        def: LTACCmdView<TACCmd.Simple.AssigningCmd>,
        offset: BigInteger,
        vararg hashedWords: BigInteger
    ): TACSymbol.Var {
        val hashRes = TACKeyword.TMP(Tag.Bit256, "hashRes")
        patch.addVarDecl(hashRes)
        val hashResAssignment =
            TACCmd.Simple.AssigningCmd.AssignSimpleSha3Cmd(hashRes, length = (hashedWords.size * EVM_WORD_SIZE_INT).asTACSymbol(), args = hashedWords.asList().map { TACSymbol.lift(it) })
        if (offset == BigInteger.ZERO) {
            patch.replaceCommand(
                def.ptr,
                listOf(
                    hashResAssignment,
                    TACCmd.Simple.AssigningCmd.AssignExpCmd(def.cmd.lhs, hashRes)
                )
            )
        } else {
            val hashWithOffset = TACKeyword.TMP(Tag.Bit256, "hashWithOffset")
            patch.addVarDecl(hashWithOffset)
            patch.replaceCommand(
                def.ptr,
                listOf(
                    hashResAssignment,
                    TACCmd.Simple.AssigningCmd.AssignExpCmd(
                        hashWithOffset,
                        TACExpr.Vec.Add(hashRes.asSym(), TACSymbol.lift(offset).asSym())
                    ),
                    TACCmd.Simple.AssigningCmd.AssignExpCmd(def.cmd.lhs, hashWithOffset)
                )
            )
        }
        return hashRes
    }

    private fun hashBigInts(vararg bigints: BigInteger): BigInteger {
        return applyKeccak(*bigints)
    }

    /**
     * @return the constant in the definition in [where]
     * should never fail if we call on a [where] returned from [getDefIfConstant]
     */
    private fun getConstant(lcmd: LTACCmdView<TACCmd.Simple.AssigningCmd.AssignExpCmd>): BigInteger {
        return lcmd.wrapped.enarrow<TACExpr.Sym.Const>().exp.s.value
    }

    private fun getDefIfConstant(
        s: TACSymbol,
        where: CmdPointer
    ): LTACCmdView<TACCmd.Simple.AssigningCmd.AssignExpCmd>? {
        return when (s) {
            is TACSymbol.Const -> {
                Logger.alwaysWarn("Symbol $s at $where is already a constant - may have to introduce a dummy variable")
                null
            }
            is TACSymbol.Var -> {
                def.getDefCmd<TACCmd.Simple.AssigningCmd.AssignExpCmd>(s, where)?.takeIf { c ->
                    c.cmd.rhs is TACExpr.Sym.Const
                }
            }
        }
    }

    private fun getConstantLocsDefs(): Stream<LTACCmdView<TACCmd.Simple.AssigningCmd.AssignExpCmd>> {
        return code.parallelLtacStream().mapNotNull { lcmd ->
            when (lcmd.cmd) {
                is TACCmd.Simple.WordStore -> {
                    getDefIfConstant(lcmd.cmd.loc, lcmd.ptr)
                }
                is TACCmd.Simple.AssigningCmd.WordLoad -> {
                    getDefIfConstant(lcmd.cmd.loc, lcmd.ptr)
                }
                else -> null
            }
        }
    }
}
