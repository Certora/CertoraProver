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

package normalizer

import analysis.LTACCmd
import analysis.PatternMatcher
import analysis.maybeNarrow
import analysis.narrow
import log.*
import utils.isNotEmpty
import vc.data.*
import vc.data.TACMeta.RESERVED_MEMORY_SLOT
import java.math.BigInteger

/**
 * An object for identifying sighash reads and converting it to a scalar object out of a heap-style object.
 * In Solidity, this "heap" is the calldata. It is pretty trivial because the calldata is read only.
 *
 * This class is able to scalarize both reads that are casting the sighash to uint32, and ones that leave it as bytes4.
 */
object SighashScalarizer {
    data class CalldataReadAndMatchingUse(
        val calldataAt0SourceRead: LTACCmd,
        val matchingSighashUse: LTACCmd
    )

    val max4bytes = "ffffffff".toBigInteger(16)

    private fun constExact(c: BigInteger) = PatternMatcher.Pattern.FromConstant.exactly(c)
    private val const4byteMask = constExact(max4bytes)
    private val const4byteHighMask = constExact(max4bytes * BigInteger.TWO.pow(224))
    private val const224 = constExact(224.toBigInteger())
    private val const2to224 = constExact(BigInteger.TWO.pow(224))

    private val readFromCalldata0 = PatternMatcher.Pattern.AssigningPattern1(
        TACCmd.Simple.AssigningCmd.ByteLoad::class.java,
        extract = { _: LTACCmd, e: TACCmd.Simple.AssigningCmd.ByteLoad ->
            if (e.base.meta.containsKey(TACMeta.IS_CALLDATA)) {
                e.loc
            } else {
                null
            }
        },
        nested = PatternMatcher.Pattern.FromConstant.exactly(BigInteger.ZERO),
        out = { where, _ -> where }
    )

    private val readFromCalldataVar0 =
        PatternMatcher.Pattern.FromVar(extractor = { where, v ->
            if (v.meta.find(TACMeta.WORDMAP_KEY) == BigInteger.ZERO) {
                PatternMatcher.VariableMatch.Match(where)
            } else {
                PatternMatcher.VariableMatch.Continue
            }
        })

    // the alias analysis may split out tacCalldata!0
    private val definiteReadFromCalldataOffset0 = PatternMatcher.Pattern.XOr.orSame(readFromCalldata0, readFromCalldataVar0)

    private val divBy2To224 = PatternMatcher.Pattern.FromBinOp.from(
            TACExpr.BinOp.Div::class.java,
            p1 = definiteReadFromCalldataOffset0,
            p2 = const2to224,
            comb = { _, where, _ -> where }
    )
    // 0xffffffff00..00 & calldatabuf0 read
    private val calldata4bytereadUnshifted =
        PatternMatcher.Pattern.FromBinOp.fromCommuted(
            TACExpr.BinOp.BWAnd::class.java,
            p1 = definiteReadFromCalldataOffset0,
            p2 = const4byteHighMask,
            comb1to2 = { _, where, _ -> where },
            comb2to1 = { _, _, where -> where }
        )
    private val calldata4byteRead =
            PatternMatcher.Pattern.XOr.orSame(
                    PatternMatcher.Pattern.FromBinOp.from(
                            TACExpr.BinOp.ShiftRightLogical::class.java,
                            p1 = definiteReadFromCalldataOffset0,
                            p2 = const224,
                            comb = { _, loc, _ -> loc }
                    ),
                    divBy2To224,
                    PatternMatcher.Pattern.FromBinOp.fromCommuted(
                            TACExpr.BinOp.BWAnd::class.java,
                            p1 = divBy2To224,
                            p2 = const4byteMask,
                            comb1to2 = { _, where, _ -> where },
                            comb2to1 = { _, _, where -> where }
                    )
        )

    fun rewrite(code: CoreTACProgram): CoreTACProgram {
        val g = code.analysisCache.graph
        val patch = code.toPatchingProgram()

        val offsetOfSighashMatcher = PatternMatcher.compilePattern(g, definiteReadFromCalldataOffset0) {
            // Don't traverse def chains; we only want the actual read
            false
        }
        // start by finding all cases where we read from calldata at 0 (where solidity/evm stores the sighash)
        val sighashOffsetReads = g.commands.filter {
            if (it.cmd is TACCmd.Simple.AssigningCmd) {
                offsetOfSighashMatcher.queryFrom(it.narrow()).toNullableResult() != null
            } else {
                false
            }
        }

        val sighhashUnpackingMatcher = PatternMatcher.compilePattern(g, calldata4byteRead)
        // we want to make sure that all reads into the sighash offset are indeed sighash reads.
        // so here we read: (1) proper sighash reads (that convert to uint32) and unshifted ones
        val sighashUnpackingsToOffsetReads = g.commands.mapNotNull { lcmd ->
            val sourceRead = lcmd.maybeNarrow<TACCmd.Simple.AssigningCmd>()?.takeIf {
                RESERVED_MEMORY_SLOT !in it.cmd.lhs.meta
            }?.let { sighhashUnpackingMatcher.queryFrom(it) }
                ?.toNullableResult() ?: return@mapNotNull null
            CalldataReadAndMatchingUse(sourceRead, lcmd)
        }

        // as mentioned, some of those matches are unshifted sighash reads (i.e. in bytes4 format rather than uint32).
        // let's check, and if so, we will replace them with sighash * 2^220
        val unshiftedMatcher = PatternMatcher.compilePattern(g, calldata4bytereadUnshifted)
        val unshiftedSighashToOffsetReads = g.commands.mapNotNull { lcmd ->
            val sourceRead = lcmd.maybeNarrow<TACCmd.Simple.AssigningCmd>()?.takeIf {
                RESERVED_MEMORY_SLOT !in it.cmd.lhs.meta
            }?.let { unshiftedMatcher.queryFrom(it) }
                ?.toNullableResult() ?: return@mapNotNull null
            CalldataReadAndMatchingUse(sourceRead, lcmd)
        }

        // if there are unclassified reads of calldata at 0, we cannot scalarize the sighash
        sighashUnpackingsToOffsetReads.map { it.calldataAt0SourceRead }.let { sourceReadsOfProperlyUnpackedSighashes ->
            val sourceReadsOfUnshiftedSighashes = unshiftedSighashToOffsetReads.map { it.calldataAt0SourceRead }
            val nonMatching = sighashOffsetReads.filter { it !in sourceReadsOfProperlyUnpackedSighashes  && it !in sourceReadsOfUnshiftedSighashes}

            if (nonMatching.isNotEmpty()) {
                Logger.alwaysWarn("Reads from expected sighash offsets in ${code.name}: ${nonMatching.toList()} are not matched by an actual sighash read")
                return code
            }
        }

        // now that we know all our reads can be matched to an expression over tacSighash, let's process them

        // the standard reads:
        sighashUnpackingsToOffsetReads.map { it.matchingSighashUse }.forEach { sighashUse ->
            val readVar = sighashUse.narrow<TACCmd.Simple.AssigningCmd>().cmd.lhs
            patch.update(sighashUse.ptr) {
                TACCmd.Simple.AssigningCmd.AssignExpCmd(readVar, TACKeyword.SIGHASH.toVar())
            }
        }

        // the unshifted reads:
        unshiftedSighashToOffsetReads.map { it.matchingSighashUse }.forEach { sighashUse ->
            val readVar = sighashUse.narrow<TACCmd.Simple.AssigningCmd>().cmd.lhs
            patch.update(sighashUse.ptr) {
                TACCmd.Simple.AssigningCmd.AssignExpCmd(
                    readVar,
                    TACExpr.Vec.Mul(TACKeyword.SIGHASH.toVar().asSym(), BigInteger.TWO.pow(224).asTACSymbol().asSym())
                )
            }
        }

        // make sure to include tacSighash
        if (sighashUnpackingsToOffsetReads.isNotEmpty() || unshiftedSighashToOffsetReads.isNotEmpty()) {
            patch.addVarDecl(TACKeyword.SIGHASH.toVar())
        }

        return patch.toCode(code)
    }
}
