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

package verifier.equivalence.instrumentation

import allocator.Allocator
import allocator.GenerateRemapper
import allocator.GeneratedBy
import datastructures.stdcollections.*
import analysis.CommandWithRequiredDecls
import analysis.maybeExpr
import tac.MetaKey
import tac.MetaMap
import tac.NBId
import tac.Tag
import utils.*
import vc.data.*
import vc.data.TACSymbol.Companion.atSync
import java.lang.IllegalStateException
import java.util.stream.Collectors

/**
 * Collapse all return (or revert) sites to a single node. Before running this pass, all
 * sinks of the method have an instance of copying from memory to a return buffer.
 *
 * For [verifier.equivalence.tracing.EnvironmentTranslationTracking] this is not ideal; we need to have
 * a single longread to which to translate.
 *
 * The solution is to move the various copies from memory to returndata into a newly created sink node which is the successor
 * of all the previous sink nodes. These (former) sink nodes now instead set a "start offset" variable, from which returndata
 * is populated in the new sink node.
 *
 * In other words, this pass transforms:
 * ```
 * if(*) {
 *    rc = 1
 *    returndatasize = len
 *    returndata[0:len] = mem[x:len]
 * } else {
 *    rc = 0
 *    returndatasize = len2
 *    returndata[0:len2] = mem[y:len2]
 * }
 * ```
 * into:
 * ```
 * if(*) {
 *    rc = 1
 *    returndatasize = len
 *    return_offset = x
 * } else {
 *    rc = 0
 *    returndatasize = len2
 *    return_offset = y
 * }
 * returndata[0:returndatasize] = mem[return_offset:returndatasize]
 * ```
 *
 * In addition, this newly created returndata copy is tagged with [ConfluenceCopyId] to facilitate
 * [ReturnCopyCorrelation].
 */
object ReturnCopyCollapser {

    @GenerateRemapper
    data class ConfluenceCopyId(
        @GeneratedBy(Allocator.Id.RETURN_COPY_CONFLUENCE, source = true)
        val id: Int
    ) : RemapperEntity<ConfluenceCopyId>

    val CONFLUENCE_COPY = MetaKey<ConfluenceCopyId>("equivalence.confluence.copy")

    private fun TACSymbol.Var.isKeyword(t: TACKeyword) = this.meta.get(TACSymbol.Var.KEYWORD_ENTRY)?.let { ent ->
        ent.maybeTACKeywordOrdinal == t.ordinal
    } == true

    fun collapseReturns(c: CoreTACProgram) : CoreTACProgram {
        val inlinedReturns = c.parallelLtacStream().mapNotNull {
            it.maybeExpr<TACExpr.LongStore>()?.takeIf { blc ->
                TACMeta.IS_RETURNDATA in blc.cmd.lhs.meta && blc.ptr.block.calleeIdx != NBId.ROOT_CALL_ID &&
                    blc.exp.dstMap == blc.cmd.lhs.asSym() && blc.exp.dstOffset == TACExpr.zeroExpr &&
                    blc.exp.srcMap is TACExpr.Sym.Var
            }
        }.collect(Collectors.groupingBy { it.ptr.block.calleeIdx })
        val g = c.analysisCache.graph
        val patcher = c.toPatchingProgram()
        for((id, group) in inlinedReturns) {
            // sanity check
            check(group.mapToTreapSet { it.ptr.block }.size == group.size) {
                "Multiple blocks"
            }
            val successorBlock = group.flatMap { lc ->
                g.succ(lc.ptr.block)
            }.uniqueOrNull() ?: error("no return block confluence for $id in ${c.name}")

            val returnCopySource = TACKeyword.TMP(Tag.Bit256, "!retCopySource").atSync(id)
            var copyBase : TACSymbol.Var? = null
            var copyDest : TACSymbol.Var? = null
            var calleeId : Int? = null

            for(retCopy in group) {
                val srcMap = retCopy.exp.srcMap as TACExpr.Sym.Var
                if(copyBase == null) {
                    copyBase = srcMap.s
                } else if(copyBase != srcMap.s) {
                    throw IllegalStateException("Cannot coalesce return copies, have different basemaps")
                }
                if(copyDest == null) {
                    copyDest = retCopy.lhs
                } else if(copyDest != retCopy.lhs) {
                    throw IllegalStateException("Cannot coalesce return copies, have different target maps")
                }
                var seenRCSet = false
                for(rem in g.iterateBlock(retCopy.ptr, excludeStart = true)) {
                    if(rem.cmd is TACCmd.Simple.DirectMemoryAccessCmd && TACMeta.EVM_MEMORY in rem.cmd.base.meta) {
                        throw IllegalStateException("Cannot coalesce returncopies, have intervening memory write: $rem")
                    } else if(rem.cmd is TACCmd.Simple.LongAccesses && rem.cmd.accesses.any { la ->
                        la.isWrite && TACMeta.EVM_MEMORY in la.base.meta
                    }) {
                        throw IllegalStateException("Cannot coalesce return copies, have intervening memory copy $rem")
                    }
                    if(rem.cmd is TACCmd.Simple.AssigningCmd) {
                        if(rem.cmd.lhs.isKeyword(TACKeyword.RETURNCODE)) {
                            if (seenRCSet) {
                                throw IllegalStateException("Multiple RC writes in suffix; conservatively failing")
                            }
                            seenRCSet = true
                            if(calleeId == null) {
                                calleeId = rem.cmd.lhs.callIndex
                            } else if(calleeId != rem.cmd.lhs.callIndex) {
                                throw IllegalStateException("Inconsistent caller ID")
                            }
                        }
                    }
                }
                if(!seenRCSet) {
                    throw IllegalStateException("suffix did not contain value set commands, failing")
                }
            }
            check(copyBase != null && copyDest != null && calleeId != null && copyDest.callIndex == calleeId) {
                throw IllegalStateException("Copy base not set in any sink?")
            }
            val confluenceBlock = Allocator.getNBId().copy(
                calleeIdx = id
            )
            val returnSizeVar = TACKeyword.RETURN_SIZE.toVar(calleeId)
            val returnSizeCopy = TACKeyword.TMP(Tag.Bit256, "!returnValueCopy").atSync(calleeId)
            val newId = patcher.addBlock(
                confluenceBlock, CommandWithRequiredDecls(
                    listOf(
                        TACCmd.Simple.AssigningCmd.AssignExpCmd(
                            lhs = returnSizeCopy,
                            rhs = returnSizeVar.asSym(),
                        ),
                        TACCmd.Simple.ByteLongCopy(
                            dstBase = copyDest,
                            srcBase = copyBase,
                            length = returnSizeCopy,
                            dstOffset = TACSymbol.Zero,
                            srcOffset = returnCopySource,
                            meta = MetaMap(CONFLUENCE_COPY to ConfluenceCopyId(Allocator.getFreshId(Allocator.Id.RETURN_COPY_CONFLUENCE)))
                        ),
                        TACCmd.Simple.JumpCmd(successorBlock)
                    ),
                    returnCopySource, returnSizeCopy
                )
            )
            patcher.reroutePredecessorsTo(successorBlock, newId) { pred ->
                pred != newId
            }
            for(r in group) {
                val toReplace = CodeGen.codeGen {
                    returnCopySource `=` r.exp.srcOffset
                    val copySize by e(r.exp.length)
                    val copySizeEq by e(Tag.Bool) {
                        returnSizeVar eq copySize
                    }
                    +TACCmd.Simple.AssertCmd(copySizeEq, "return size sanity")
                }
                patcher.replaceCommand(r.ptr, toReplace)
            }
        }
        return patcher.toCode(c)
    }
}
