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

import analysis.*
import datastructures.stdcollections.*
import spec.cvlast.CVLGhostDeclaration
import spec.cvlast.CVLType
import tac.MetaMap
import tac.NBId
import tac.Tag
import utils.*
import vc.data.*
import vc.data.tacexprutil.TACExprFolder
import vc.data.tacexprutil.subs
import kotlin.streams.asSequence

/**
 * Instrument a [CoreTACProgram] with updates to sum-ghosts in all places needed.
 *
 * Definition - a _summed ghost_ is a ghost that has some [spec.cvlast.CVLExp.SumExp] associated
 * with it in the program.
 *
 * The idea is that for every write of a summed ghost we insert the following code:
 * ```
 * // In spec we have somewhere a `sum int a. myGhost[a][b]`.
 * // so for each write to the ghost `myGhost[a][b] = value`:
 * oldVal = myGhost[a][b]
 * myGhostSum[b] = myGhostSum[b] + value - oldVal
 * myGhost[a][b] = value
 * ```
 *
 * If the sum is an unsigned sum (`usum` in spec), we add some more instrumentation to promise that the sum is greater
 * than its parts. This is done be adding for each write _or read_ of a summed ghost the following code:
 * ```
 * // In spec we have somewhere a `usum int a. myGhost[a][b]`.
 * // We declare 2 new ghosts - `_is_accessed_myGhost[a][b]` and `_unaccessed_myGhost[b]`
 * // and for each write/read to the ghost `myGhost[a][b]`:
 * require oldVal >= 0 // This is here for the case that the usum is over a mathint
 * if (!_is_accessed_myGhost[a][b]) {
 *     _is_accessed_myGhost[a][b] = true;
 *     _unaccessed_myGhost[b] = _unaccessed_myGhost[b] - oldVal;
 *     require _unaccessed_myGhost[b] >= 0;
 * }
 *
 * // In case of write - insert here also the previous write instrumentation
 * // also add assert value >= 0 in the write case to cover cases where the usum is over a mathint
 * ```
 * Additionally, for each usummed ghost we need to add some initial-state assumptions, so we prepend to the program the
 * following:
 * ```
 * // In spec we have somewhere a `usum int a. myGhost[a][b]`
 * require forall a, b. !_is_accessed_myGhost[a][b]
 * require forall b. _unaccessed_myGhost[b] == myGhostSum[b]
 * require forall b. myGhostSum[b] >= 0
 * ```
 *
 * Finally, if a ghost that is summed is havoc'ed, we need to havoc the corresponding sums as well (and if the ghost is
 * usummed we need to havoc the _unaccessed_* ghosts associated with those usum ghosts, and re-assume the initial-state
 * of the unsigned sum ghosts).
 */
class GhostSumInstrumenter(ghosts: List<CVLGhostDeclaration>) : CodeTransformer() {
    private val sumGhosts = ghosts.filterIsInstance<CVLGhostDeclaration.Sum>()

    private val isAccessedGhosts: MutableMap<String, TACSymbol.Var> = mutableMapOf()

    /**
     * @return the [TACSymbol.Var] of the mapping `_is_accessed_origGhostId`.
     * It is used to determine if there was an access to the [origGhost] at some location.
     * Note this symbol has the same type as the [origGhost]
     */
    private fun getIsAccessedGhost(origGhost: CVLGhostDeclaration.Variable): TACSymbol.Var {
        return isAccessedGhosts.getOrPut(origGhost.id) {
            TACKeyword.TMP(
                Tag.GhostMap(origGhost.paramTypes.map { it.toTag() }, Tag.Bool),
                "_is_accessed_${origGhost.id}"
            ).let {
                it.withMeta(TACMeta.CVL_GHOST)
                    .withMeta(TACMeta.CVL_DISPLAY_NAME, it.namePrefix)
                    .withMeta(TACMeta.CVL_TYPE, origGhost.type)
            }
        }
    }

    private val unaccessedGhosts: MutableMap<String, TACSymbol.Var> = mutableMapOf()

    /**
     * @return the [TACSymbol.Var] of the `_unaccessed_usumGhostId` which represents the sum of _unaccessed_ values.
     * Note this symbol has the same type as the [usumGhost].
     */
    private fun getUnaccessedGhost(usumGhost: CVLGhostDeclaration.Sum): TACSymbol.Var {
        return unaccessedGhosts.getOrPut(usumGhost.id) {
            TACKeyword.TMP(usumGhost.type.toTag(), "_unaccessed_${usumGhost.id}").let {
                it.withMeta(TACMeta.CVL_GHOST)
                    .withMeta(TACMeta.CVL_DISPLAY_NAME, it.namePrefix)
                    .withMeta(TACMeta.CVL_TYPE, usumGhost.type)
            }
        }
    }

    /**
     * See [GhostSumInstrumenter] for details on what this function generates
     */
    private fun generateUnsignedSumInitialization(patching: SimplePatchingProgram, usumGhosts: List<CVLGhostDeclaration.Sum>, globalScope: Map<String, TACSymbol. Var>): List<TACCmd.Simple> {
        require(usumGhosts.all {it.isUnsigned} )

        fun generateIndexVars(
            paramTypes: List<CVLType.PureCVLType>,
        ): Array<TACSymbol.Var> = paramTypes.mapIndexed { idx, ty ->
            TACKeyword.TMP(ty.toTag(), "idx_$idx")
        }.toTypedArray().also {
            patching.addVarDecls(it.toSet())
        }

        /** For each usummed ghost, declare the corresponding [getIsAccessedGhost] and assume the whole mapping is false */
        val initIsAccessedGhosts = usumGhosts.map { it.origGhost }.distinctBy { it.id }.flatMap { origGhost ->
            val indexVars = generateIndexVars(origGhost.paramTypes)
            val isAccessedGhostVar = getIsAccessedGhost(origGhost)
            val allUnaccessedVar = TACKeyword.TMP(Tag.Bool, "AllUnaccessed")
            patching.addVarDecls(setOf(isAccessedGhostVar, allUnaccessedVar))

            listOf(
                TACCmd.Simple.AssigningCmd.AssignExpCmd(
                    lhs = allUnaccessedVar,
                    rhs = TACExprFactUntyped {
                        forall(*indexVars) {
                            LNot(select(isAccessedGhostVar.asSym(), *(indexVars.map { it.asSym() }).toTypedArray()))
                        }
                    }
                ),
                TACCmd.Simple.AssumeCmd(
                    allUnaccessedVar, "AllUnaccessed"
                )
            )
        }

        val (initUnaccessedGhosts, initUsumGhosts) = usumGhosts.map { usumGhost ->
            val unaccessedIndexVars = generateIndexVars(usumGhost.paramTypes)
            val unaccessedGhostVar = getUnaccessedGhost(usumGhost)
            val unaccessedGhostCondVar = TACKeyword.TMP(Tag.Bool, "UnaccessedGhostCond")
            val sumGhostVar = globalScope[usumGhost.id] ?: error("should have been there")

            /** Initialize the [getUnaccessedGhost] to be equal to the [usumGhost] (since we didn't access anything yet) */
            val unaccessedGhostInitialState = listOf(
                TACCmd.Simple.AssigningCmd.AssignExpCmd(
                    lhs = unaccessedGhostCondVar,
                    rhs = TACExprFactUntyped {
                        if (unaccessedIndexVars.isEmpty()) {
                            Eq(unaccessedGhostVar.asSym(), sumGhostVar.asSym())
                        } else {
                            forall(*unaccessedIndexVars) {
                                Eq(
                                    select(unaccessedGhostVar.asSym(), *(unaccessedIndexVars.map { it.asSym() }).toTypedArray()),
                                    select(sumGhostVar.asSym(), *(unaccessedIndexVars.map { it.asSym() }).toTypedArray())
                                )
                            }
                        }
                    }
                ),
                TACCmd.Simple.AssumeCmd(unaccessedGhostCondVar, "UnaccessedGhostCond")
            )

            val nonNegativeUsumCIndexVars = generateIndexVars(usumGhost.paramTypes)
            val nonNegativeUsumCondVar = TACKeyword.TMP(Tag.Bool, "NonNegativeUsumCond")

            /** Require that the [usumGhost] is non-negative */
            val nonNegativeUsum = listOf(
                TACCmd.Simple.AssigningCmd.AssignExpCmd(
                    lhs = nonNegativeUsumCondVar,
                    rhs = TACExprFactUntyped {
                        forall(*nonNegativeUsumCIndexVars) {
                            select(sumGhostVar.asSym(), *(nonNegativeUsumCIndexVars.map { it.asSym() }).toTypedArray()) ge Zero
                        }
                    }
                ),
                TACCmd.Simple.AssumeCmd(nonNegativeUsumCondVar, "NonNegativeUsumCond")
            )

            patching.addVarDecls(setOf(unaccessedGhostVar, unaccessedGhostCondVar, nonNegativeUsumCondVar))
            unaccessedGhostInitialState to nonNegativeUsum
        }.unzip()

        return initIsAccessedGhosts + initUnaccessedGhosts.flatten() + initUsumGhosts.flatten()
    }

    /**
     * See [GhostSumInstrumenter] for details on what this function generates
     */
    private fun updateAccessorGhosts(
        patching: SimplePatchingProgram,
        usumGhosts: List<CVLGhostDeclaration.Sum>,
        currValReadCmd: TACCmd.Simple.AssigningCmd.AssignExpCmd,
        indexExprs: List<TACExpr>,
        contBlock: NBId
    ) {
        require(usumGhosts.all { it.isUnsigned })
        val origGhost = usumGhosts.map { it.origGhost }.uniqueOrNull()
            ?: error("This function should be called with a list of usum ghosts all for the same orig ghost")
        val isAccessedGhostVar = getIsAccessedGhost(origGhost)
        val currVal = currValReadCmd.lhs

        val thenBlockContents = usumGhosts.flatMap { usumGhost ->
            val usumhostIndices = indexExprs.filterIndexed { idx, _ -> idx in usumGhost.nonSummedIndices }.toTypedArray()
            val unaccessedGhostVar = getUnaccessedGhost(usumGhost)
            listOf(
                TACCmd.Simple.AssigningCmd.AssignExpCmd(
                    lhs = unaccessedGhostVar,
                    rhs = TACExprFactUntyped {
                        if (usumhostIndices.isEmpty()) {
                            unaccessedGhostVar.asSym() intSub currVal.asSym()
                        } else {
                            Store(
                                unaccessedGhostVar.asSym(),
                                *usumhostIndices,
                                v = Select(unaccessedGhostVar.asSym(), *usumhostIndices) intSub currVal.asSym()
                            )
                        }
                    },
                    meta = MetaMap(TACMeta.GHOST_SUM_ASSIGNMENT)
                ),
                TACCmd.Simple.AssumeExpCmd(
                    TACExprFactUntyped { Select(unaccessedGhostVar.asSym(), *usumhostIndices) ge Zero }
                )
            )
        } + TACCmd.Simple.AssigningCmd.AssignExpCmd(
            lhs = isAccessedGhostVar,
            rhs = TACExprFactUntyped { Store(isAccessedGhostVar.asSym(), indexExprs, v = True) }
        ) + TACCmd.Simple.JumpCmd(contBlock)
        val thenBlock = patching.addBlock(contBlock, thenBlockContents)

        val condVar = TACKeyword.TMP(Tag.Bool, "IsAccessedCond").also { patching.addVarDecl(it) }
        val condBlock = patching.createOpenBlockFrom(contBlock)
        patching.reroutePredecessorsTo(contBlock, condBlock) { it != thenBlock }

        val currValNonNegativeCondVar = TACKeyword.TMP(Tag.Bool, "CurrValNonNegativeCond")
        patching.addVarDecl(currValNonNegativeCondVar)

        patching.populateBlock(condBlock, listOf(
            currValReadCmd,
            TACCmd.Simple.AssigningCmd.AssignExpCmd(
                lhs = currValNonNegativeCondVar,
                rhs = TACExprFactUntyped { currVal.asSym() ge Zero }
            ),
            TACCmd.Simple.AssumeCmd(currValNonNegativeCondVar, "CurrValNonNegativeCond"),
            TACCmd.Simple.AssigningCmd.AssignExpCmd(
                lhs = condVar,
                rhs = TACExprFactUntyped {
                    LNot(select(isAccessedGhostVar.asSym(), *indexExprs.toTypedArray()))
                }
            ),
            TACCmd.Simple.JumpiCmd(
                cond = condVar,
                dst = thenBlock,
                elseDst = contBlock
            )
        ))
    }

    /**
     * See [GhostSumInstrumenter] for details on what this function generates
     */
    private fun instrumentSummedGhostRead(
        patching: SimplePatchingProgram,
        ptr: CmdPointer,
        exp: TACExpr,
        usummedGhostSymbolToSumGhosts: Map<TACSymbol.Var, List<CVLGhostDeclaration.Sum>>
    ) {
        val instrumentSelect = object : TACExprFolder<Unit>() {
            private val quantifierVars: MutableSet<TACSymbol.Var> = mutableSetOf()

            override fun const(acc: Unit, v: TACSymbol.Const) = acc

            override fun variable(acc: Unit, v: TACSymbol.Var) = acc

            override fun select(acc: Unit, v: TACExpr.Select) {
                if (v.tagAssumeChecked !is Tag.Int) {
                    // This is not a "top-level" select, so not interesting for us
                    return acc
                }

                val indices = mutableListOf(v.loc)
                var curr = v.base
                while (curr is TACExpr.Select) {
                    indices.add(0, curr.loc)
                    curr = curr.base
                }
                val base = (curr as? TACExpr.Sym.Var)?.s ?: return acc
                val sumGhosts = usummedGhostSymbolToSumGhosts[base]
                    ?: return acc // This is a select of something other than a usummed ghost

                if (indices.any { i ->
                        i.subs.any { (it as? TACExpr.Sym)?.s in quantifierVars }
                    }) {
                    // This select uses quantified variables in the indices, so we can't really reason about them.
                    return acc
                }

                val currValReadVar = TACKeyword.TMP(CVLType.PureCVLType.Primitive.Mathint.toTag(), "CurrReadVal")
                val currValReadCmd = TACCmd.Simple.AssigningCmd.AssignExpCmd(
                    lhs = currValReadVar,
                    rhs = TACExprFactUntyped { Select(base.asSym(), *indices.toTypedArray()) }
                )

                patching.addVarDecl(currValReadVar)
                updateAccessorGhosts(patching, sumGhosts, currValReadCmd, indices, patching.splitBlockBefore(ptr))
            }

            override fun quantifiedFormula(acc: Unit, v: TACExpr.QuantifiedFormula) {
                quantifierVars.addAll(v.quantifiedVars)
                super.quantifiedFormula(acc, v)
                quantifierVars.removeAll(v.quantifiedVars.toSet())
            }
        }

        instrumentSelect.expr(Unit, exp)
    }

    /**
     * See [GhostSumInstrumenter] for details on what this function generates
     */
    private fun instrumentSummedGhostWrite(
        patching: SimplePatchingProgram,
        lcmd: LTACCmdView<TACCmd.Simple.AssigningCmd.AssignExpCmd>,
        summedGhostTACSymbolsToSumGhost: Map<TACSymbol.Var, List<CVLGhostDeclaration.Sum>>,
        globalScope: Map<String, TACSymbol.Var>
    ) {
        val lhs = lcmd.cmd.lhs
        val sumGhosts = summedGhostTACSymbolsToSumGhost[lhs]!!

        val oldGhostValSym = TACKeyword.TMP(Tag.Int, "OldSumVal")
        patching.addVarDecl(oldGhostValSym)
        val rhs = lcmd.cmd.rhs
        require(rhs is TACExpr.StoreExpr)

        val oldGhostValRead = TACCmd.Simple.AssigningCmd.AssignExpCmd(
            lhs = oldGhostValSym,
            rhs = TACExprFactUntyped { Select(lhs.asSym(), *rhs.locs.toTypedArray()) }
        )

        val ghostSumUpdates = sumGhosts.flatMap { sumGhost ->
            // For each ghost sum, ghostSum = oldGhostSum + newGhostVal - oldGhostVal
            val sym = globalScope[sumGhost.id]!!




            if (sumGhost.nonSummedIndices.isEmpty()) {
                listOf(
                    TACCmd.Simple.AssigningCmd.AssignExpCmd(
                        lhs = sym,
                        rhs = TACExprFactUntyped { sym.asSym() intAdd rhs.value intSub oldGhostValSym.asSym() }
                    ),
                    SnippetCmd.CVLSnippetCmd.SumGhostUpdate(
                        sym,
                        sumGhost.origGhost.id,
                        listOf(null),
                        sumGhost.persistent
                    ).toAnnotation()
                )
            } else {
                val nonSummedLocs = rhs.locs.filterIndexed { index, _ -> index in sumGhost.nonSummedIndices }

                val nonSummedVars = nonSummedLocs.map {
                    ((it as? TACExpr.Sym)?.s as? TACSymbol.Var)
                        ?: error("expected the index expressions to be symbols")
                }

                val indices = sumGhost.placeItemsInNonSummedIndices(nonSummedVars)

                // We need this temp var (instead of just using the select expression as the value of the
                // store) so that we have a "simple" variable holding the new value for the snippet to use.
                val newGhostValSym = TACKeyword.TMP(Tag.Int, "NewGhostVal")
                    .withMeta(TACMeta.CVL_TYPE, CVLType.PureCVLType.Primitive.Mathint)
                patching.addVarDecl(newGhostValSym)

                listOf(
                    TACCmd.Simple.AssigningCmd.AssignExpCmd(
                        lhs = newGhostValSym,
                        rhs = TACExprFactUntyped {
                            Select(
                                sym.asSym(),
                                *nonSummedLocs.toTypedArray()
                            ) intAdd rhs.value intSub oldGhostValSym.asSym()
                        }
                    ),
                    TACCmd.Simple.AssigningCmd.AssignExpCmd(
                        lhs = sym,
                        rhs = TACExprFactUntyped {
                            Store(
                                sym.asSym(),
                                locs = nonSummedLocs,
                                newGhostValSym.asSym()
                            )
                        },
                        meta = MetaMap(TACMeta.GHOST_SUM_ASSIGNMENT)
                    ),
                    SnippetCmd.CVLSnippetCmd.SumGhostUpdate(
                        newGhostValSym,
                        sumGhost.origGhost.id,
                        indices,
                        sumGhost.persistent
                    ).toAnnotation()
                )
            }
        }

        val usumGhosts = sumGhosts.filter { it.isUnsigned }
        if (usumGhosts.isNotEmpty()) {
            val nextBlock = patching.splitBlockBefore(lcmd.ptr)
            updateAccessorGhosts(patching, usumGhosts, oldGhostValRead, rhs.locs, nextBlock)

            val newValNonNegativeCondVar = TACKeyword.TMP(Tag.Bool, "NewValNonNegativeCond")
            patching.addVarDecl(newValNonNegativeCondVar)
            patching.addBefore(
                lcmd.ptr,
                ghostSumUpdates + listOf(
                    TACCmd.Simple.AssigningCmd.AssignExpCmd(newValNonNegativeCondVar, TACExprFactUntyped { rhs.value ge Zero }),
                    TACCmd.Simple.AssertCmd(newValNonNegativeCondVar, "A usummed ghost is assigned a negative value")
                )
            )
        } else {
            patching.addBefore(lcmd.ptr, listOf(oldGhostValRead) + ghostSumUpdates)
        }
    }

    /**
     * If a ghost that is summed is havoc'ed, we need to havoc the corresponding sums as well.
     * Additionally, if the ghost is usummed we need to havoc the _unaccessed_* ghosts associated with those sum ghosts,
     * and re-assume the initial-state of the unsigned ghosts.
     */
    private fun instrumentSummedGhostHavoc(
        patching: SimplePatchingProgram,
        lcmd: LTACCmdView<TACCmd.Simple.AssigningCmd.AssignHavocCmd>,
        summedGhostTACSymbolsToSumGhost: Map<TACSymbol.Var, List<CVLGhostDeclaration.Sum>>,
        globalScope: Map<String, TACSymbol.Var>
    ) {
        val havocedGhost = lcmd.cmd.lhs
        val sumsToHavoc = summedGhostTACSymbolsToSumGhost[havocedGhost] ?: error("it should have been there")

        val usumInitializtionCmds = if (sumsToHavoc.any { it.isUnsigned }) {
            sumsToHavoc.flatMap {
                listOf(
                    TACCmd.Simple.AssigningCmd.AssignHavocCmd(getUnaccessedGhost(it)),
                    TACCmd.Simple.AssigningCmd.AssignHavocCmd(getIsAccessedGhost(it.origGhost)),
                )} +
                generateUnsignedSumInitialization(patching, sumsToHavoc, globalScope)
        } else {
            listOf()
        }

        patching.insertAfter(
            lcmd.ptr,
            sumsToHavoc.map {
                TACCmd.Simple.AssigningCmd.AssignHavocCmd(globalScope[it.id]!!)
            } + usumInitializtionCmds
        )
    }

    override fun transform(ast: CoreTACProgram): CoreTACProgram {
        if (sumGhosts.isEmpty()) {
            // no sums, nothing to do
            return ast
        }

        val summedGhostSymbolToSumGhosts = ast.symbolTable.globalScope.map { (name, sym) ->
            sumGhosts.filter { name == it.origGhost.id }.let {
                sym to it
            }
        }.filter { it.second.isNotEmpty() }.toMap()

        val usummedGhostSymbolToSumGhosts = summedGhostSymbolToSumGhosts.filterValues { l -> l.any { it.isUnsigned } }

        return ast.patching { patching ->
            val astRoot = patching.getRoots().singleOrNull() ?: error("there should be just one root at this point")
            val astStartPtr = CmdPointer(astRoot, 0)
            val usumIntializationCmds = generateUnsignedSumInitialization(patching, sumGhosts.filter { it.isUnsigned }, ast.symbolTable.globalScope)
            patching.addBefore(astStartPtr, usumIntializationCmds)

            ast.ltacStream().filter { lcmd ->
                when {
                    lcmd.cmd is TACCmd.Simple.AssigningCmd.AssignHavocCmd && lcmd.cmd.lhs in summedGhostSymbolToSumGhosts.keys -> true
                    lcmd.cmd is TACCmd.Simple.AssigningCmd.AssignExpCmd && lcmd.cmd.lhs in summedGhostSymbolToSumGhosts.keys -> {
                        lcmd.cmd.rhs is TACExpr.StoreExpr
                    }
                    lcmd.cmd.getFreeVarsOfRhs().intersect(usummedGhostSymbolToSumGhosts.keys).isNotEmpty() &&
                        (lcmd.maybeExpr<TACExpr>() != null || lcmd.cmd is TACCmd.Simple.AssumeExpCmd) -> true
                    else -> false

                }
            }.asSequence().forEach { lcmd ->
                when {
                    lcmd.cmd is TACCmd.Simple.AssigningCmd.AssignHavocCmd && lcmd.cmd.lhs in summedGhostSymbolToSumGhosts.keys ->
                        instrumentSummedGhostHavoc(
                            patching,
                            lcmd.narrow<TACCmd.Simple.AssigningCmd.AssignHavocCmd>(),
                            summedGhostSymbolToSumGhosts,
                            ast.symbolTable.globalScope
                        )

                    lcmd.cmd is TACCmd.Simple.AssigningCmd.AssignExpCmd && lcmd.cmd.lhs in summedGhostSymbolToSumGhosts.keys && lcmd.cmd.rhs is TACExpr.StoreExpr ->
                        instrumentSummedGhostWrite(
                            patching,
                            lcmd.narrow<TACCmd.Simple.AssigningCmd.AssignExpCmd>(),
                            summedGhostSymbolToSumGhosts, ast.symbolTable.globalScope
                        )

                    lcmd.maybeExpr<TACExpr>() != null ->
                        instrumentSummedGhostRead(
                            patching,
                            lcmd.ptr,
                            lcmd.enarrow<TACExpr>().exp,
                            usummedGhostSymbolToSumGhosts
                        )

                    lcmd.cmd is TACCmd.Simple.AssumeExpCmd ->
                        instrumentSummedGhostRead(
                            patching,
                            lcmd.ptr,
                            lcmd.narrow<TACCmd.Simple.AssumeExpCmd>().cmd.condExpr,
                            usummedGhostSymbolToSumGhosts
                        )

                    else -> `impossible!`
                }
            }
        }
    }
}
