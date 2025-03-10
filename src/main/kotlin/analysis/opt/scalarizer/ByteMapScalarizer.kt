/*
 *     The Certora Prover
 *     Copyright (C) 2025  Certora Ltd.
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, version 3 of the License.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY, without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR a PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package analysis.opt.scalarizer

import analysis.LTACCmd
import analysis.worklist.volatileDagDataFlow
import com.certora.collect.*
import config.Config
import datastructures.Memoized2
import datastructures.TreapMultiMap
import datastructures.add
import datastructures.stdcollections.*
import datastructures.treapMultiMapUnion
import log.*
import tac.NBId
import tac.Tag
import vc.data.*
import vc.data.TACCmd.Simple.AssigningCmd.AssignExpCmd
import vc.data.tacexprutil.*
import vc.data.tacexprutil.ExprUnfolder.Companion.unfoldAll
import java.math.BigInteger

private typealias Queries = TreapMultiMap<TACSymbol.Var, BigInteger>

private val logger = Logger(LoggerTypes.BYTEMAP_SCALARIZER)

/**
 * Detects bytemaps which are accessed only at constant locations (the whole bytemap assignments chain), and
 * replaces them with simple variables standing for specific locations of these maps.
 */
class ByteMapScalarizer private constructor(code: CoreTACProgram, private val goodBases: Set<TACSymbol.Var>) {
    private val g = code.analysisCache.graph
    private val patcher = ConcurrentPatchingProgram(code)

    private val scalarized = Memoized2 { base: TACSymbol.Var, loc: BigInteger ->
        // require(base in goodBases)
        // we don't check, because unfolding can create new bases.
        TACSymbol.Var("${base.namePrefix}_${loc.toString(16)}", Tag.Bit256, base.callIndex)
            .also(patcher::addVar)
    }

    init {
        /**
         * Going backwards in the block DAG, calculate for each block the
         * set of constant locations for each bytemap we care for (as detected in [ScalarizerCalculator].
         * Along the way, it also rewrites the such bytemap operations into scalars.
         */
        volatileDagDataFlow<NBId, Queries>(g.blockSucc, ::processBlock)
    }

    private fun processBlock(nbid: NBId, queries: List<Queries>): Queries {
        var newQueries = queries.reduceOrNull { map1, map2 ->
            map1 treapMultiMapUnion map2
        } ?: treapMapOf()
        g.blockCmdsBackwardSeq(nbid).forEach { lcmd ->
            newQueries = processCmd(lcmd, newQueries)
        }
        return newQueries
    }

    private fun processCmd(lcmd: LTACCmd, queries: Queries): Queries {
        val (ptr, cmd) = lcmd

        fun replace(newCmd: TACCmd.Simple) =
            patcher.replace(ptr, newCmd.withMeta(cmd.meta))

        fun replace(newCmds: List<TACCmd.Simple>) =
            patcher.replace(ptr, newCmds.map { it.withMeta(cmd.meta)})

        var newQueries = queries

        fun load(lhs: TACSymbol.Var, base: TACSymbol.Var, loc: BigInteger) {
            replace(AssignExpCmd(lhs, scalarized(base, loc)))
            newQueries = queries.add(base, loc)
        }

        fun store(lhsBase: TACSymbol.Var, rhsBase: TACSymbol.Var, loc: BigInteger, value: TACExpr) {
            val newLocs = queries[lhsBase].orEmpty()
            replace(
                newLocs.map { l ->
                    if (l == loc) {
                        AssignExpCmd(scalarized(lhsBase, l), value)
                    } else {
                        AssignExpCmd(scalarized(lhsBase, l), scalarized(rhsBase, l))
                    }
                }
            )
            newQueries = queries
                .remove(lhsBase)
                .put(rhsBase, queries[rhsBase].orEmpty() + newLocs - loc)
        }

        fun longStore(
            lhs: TACSymbol.Var,
            srcBase: TACSymbol.Var,
            srcOffset: BigInteger,
            dstBase: TACSymbol.Var,
            dstOffset: BigInteger,
            len: BigInteger
        ) {
            val locs = queries[lhs].orEmpty()
            newQueries -= lhs
            locs.forEach { l ->
                newQueries = if (l in dstOffset..<dstOffset + len) {
                    newQueries.add(srcBase, l - dstOffset + srcOffset)
                } else {
                    newQueries.add(dstBase, l)
                }
            }
            replace(
                locs.map { l ->
                    if (l in dstOffset..<dstOffset + len) {
                        AssignExpCmd(scalarized(lhs, l), scalarized(srcBase, l - dstOffset + srcOffset))
                    } else {
                        AssignExpCmd(scalarized(lhs, l), scalarized(dstBase, l))
                    }
                }
            )
        }

        when (cmd) {
            is TACCmd.Simple.AssigningCmd.ByteLoad -> with(cmd) {
                if (base in goodBases) {
                    load(
                        lhs = lhs,
                        base = base,
                        loc = loc.asConst
                    )
                }
            }

            is TACCmd.Simple.AssigningCmd.ByteStore -> with(cmd) {
                if (base in goodBases) {
                    store(
                        lhsBase = lhs,
                        rhsBase = base,
                        loc = loc.asConst,
                        value = value.asSym()
                    )
                }
            }

            is TACCmd.Simple.ByteLongCopy -> with(cmd) {
                if (dstBase in goodBases) {
                    longStore(
                        lhs = dstBase,
                        srcBase = srcBase,
                        srcOffset = srcOffset.asConst,
                        dstBase = dstBase,
                        dstOffset = dstOffset.asConst,
                        len = length.asConst
                    )
                }
            }

            is AssignExpCmd -> when (val e = cmd.rhs) {
                is TACExpr.Sym.Var -> if (cmd.lhs in goodBases) {
                    queries[cmd.lhs]?.let { locs ->
                        replace(
                            locs.map {
                                AssignExpCmd(scalarized(cmd.lhs, it), scalarized(e.s, it))
                            }
                        )
                        newQueries = queries
                            .remove(cmd.lhs)
                            .put(e.s, queries[e.s].orEmpty() + locs)
                    }
                }

                is TACExpr.Select ->
                    if (e.base.asVarOrNull in goodBases) {
                        load(
                            lhs = cmd.lhs,
                            base = e.base.asVar,
                            loc = e.loc.asConst
                        )
                    }

                is TACExpr.Store ->
                    if (e.base.asVarOrNull in goodBases) {
                        store(
                            lhsBase = cmd.lhs,
                            rhsBase = e.base.asVar,
                            loc = e.loc.asConst,
                            value = e.value
                        )
                    }

                is TACExpr.TernaryExp.Ite ->
                    queries[cmd.lhs]?.let { locs ->
                        replace(
                            locs.map { loc ->
                                AssignExpCmd(
                                    scalarized(cmd.lhs, loc),
                                    e.copy(
                                        t = scalarized(e.t.asVar, loc).asSym(),
                                        e = scalarized(e.e.asVar, loc).asSym()
                                    )
                                )
                            }
                        )
                        newQueries = queries
                            .remove(cmd.lhs)
                            .put(e.t.asVar, locs)
                            .put(e.e.asVar, locs)
                    }

                is TACExpr.MapDefinition -> if (cmd.lhs in goodBases) {
                    val defVarExpr = e.defParams.single()
                    replace(
                        queries[cmd.lhs].orEmpty().flatMap { loc ->
                            val newVars = mutableListOf<TACSymbol.Var>()
                            val groundedDef = e.definition.postTransform {
                                when (it) {
                                    defVarExpr -> loc.asTACExpr
                                    is TACExpr.Unconstrained ->
                                        patcher.newTempVar("mapDefHavoc", it.tag).also { newVars += it }.asSym()

                                    else -> it
                                }
                            }
                            newVars.map { TACCmd.Simple.AssigningCmd.AssignHavocCmd(it) } +
                                AssignExpCmd(scalarized(cmd.lhs, loc), groundedDef)
                        }
                    )
                    newQueries = queries.remove(cmd.lhs)
                }

                is TACExpr.LongStore -> with(e) {
                    if (dstMap.asVarOrNull in goodBases) {
                        longStore(
                            lhs = cmd.lhs,
                            srcBase = srcMap.asVar,
                            srcOffset = srcOffset.asConst,
                            dstBase = dstMap.asVar,
                            dstOffset = dstOffset.asConst,
                            len = length.asConst
                        )
                    }
                }

                else -> Unit
            }

            else -> Unit
        }

        return newQueries
    }

    companion object {
        fun go(code: CoreTACProgram): CoreTACProgram {
            if (Config.exactByteMaps.get()) {
                return code
            }
            val goodBases = ScalarizerCalculator.goodBases(code)
            if (goodBases.isEmpty()) {
                return code
            }
            logger.info {
                "Bytemap bases to scalarize: $goodBases"
            }

            // [ScalarizerCalculator] works well even if the program is not in 3-address-form, but the actual
            // rewriting doesn't (it can be, but that would complicate the code). So we first unfold the parts
            // we are interested in into 3-address-form.
            val unfoldedCode = unfoldAll(code) {
                it.lhs in goodBases || it.rhs.subs.any { it is TACExpr.Sym.Var && it.s in goodBases }
            }
            val s = ByteMapScalarizer(unfoldedCode, goodBases)
            logger.trace {
                s.patcher.debugPrinter().toString(unfoldedCode, "BytemapScalarizer")
            }
            return s.patcher.toCode()
        }
    }
}
