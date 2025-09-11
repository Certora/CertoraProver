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

package instrumentation

import analysis.CommandWithRequiredDecls
import analysis.storage.*
import analysis.storage.StorageAnalysis.Base.Companion.toBase
import com.certora.collect.*
import config.ReportTypes
import datastructures.stdcollections.*
import evm.EVM_WORD_SIZE
import log.*
import report.CVTAlertSeverity
import report.CVTAlertType
import report.CVTAlertReporter
import scene.IContractClass
import scene.ITACMethod
import tac.Tag
import utils.*
import vc.data.*
import vc.data.tacexprutil.ExprUnfolder
import verifier.ChainedMethodTransformers
import verifier.ContractUtils
import verifier.MethodToCoreTACTransformer
import java.math.BigInteger

object StoragePathAnnotation {
    @kotlinx.serialization.Serializable
    @Treapable
    object StoragePathPrinter : PrintableCommandMeta, HasKSerializable {
        override fun toString(): String = "StoragePathPrinter"
        override fun hashCode(): Int = toString().hashCode()
        override fun equals(other: Any?): Boolean = other is StoragePathPrinter

        private fun extractPaths(v: TACSymbol.Var): String? {
            val meta = v.meta
            return meta.find(TACMeta.DISPLAY_PATHS)?.paths?.joinToString(" | ") { it.toString() }
                ?: meta.find(TACMeta.ACCESS_PATHS)?.paths?.joinToString(" | ") {
                    it.toString()
                }
        }

        override fun output(v: TACCmd.Simple): String {
            return "accessPaths = " + when (v) {
                is TACCmd.Simple.AssigningCmd.AssignExpCmd -> {
                    when (v.rhs) {
                        is TACExpr.Sym.Var -> v.rhs.s
                        is TACExpr.Select -> (v.rhs.loc as? TACExpr.Sym.Var)?.s
                        is TACExpr.Store -> (v.rhs.loc as? TACExpr.Sym.Var)?.s
                        else -> null
                    }?.let(this::extractPaths) ?: this.extractPaths(v.lhs) ?: "?"
                }
                is TACCmd.Simple.StorageAccessCmd -> {
                    when (val l = v.loc) {
                        is TACSymbol.Const -> l.toAccessPath(v.base.meta.toBase())?.paths
                        is TACSymbol.Var -> extractPaths(l)
                    } ?: "?"
                }
                else -> "?"
            }
        }

        fun readResolve(): Any = StoragePathPrinter
    }

    fun annotateClass(c: IContractClass, res: Map<MethodRef, StorageAnalysisResults>) {
        val transformers = mutableListOf<MethodToCoreTACTransformer<ITACMethod>>()
        transformers.add(
            MethodToCoreTACTransformer(ReportTypes.STORAGE_ANALYSIS) { m ->
                val code = m.code as CoreTACProgram
                when (val storageResult = res[m.toRef()]!!) {
                    is StorageAnalysisResults.SkippedLibrary -> {
                        code.prependToBlock0(
                            CommandWithRequiredDecls(
                                TACCmd.Simple.AnnotationCmd(
                                    STORAGE_ANALYSIS_SKIPPED_LIBRARY,
                                )
                            )
                        )
                    }
                    is StorageAnalysisResults.CompleteFailure -> {
                        val failures = reportFailuresAndGenerateAnnotations(storageResult.reasons, m)
                        code.prependToBlock0(
                            failures
                        )
                    }
                    is StorageAnalysisResults.MergedResults -> {
                        val instrumented = instrumentAccessPaths(code, storageResult)
                        if (storageResult.failures.isNotEmpty()) {
                            val failures = reportFailuresAndGenerateAnnotations(storageResult.failures, m)
                            instrumented.prependToBlock0(failures)
                        } else {
                            instrumented
                        }
                    }
                }
            },
        )
        val transformer = ChainedMethodTransformers(transformers)

        for (m in c.getDeclaredMethods()) {
            if (m.toRef() in res) {
                ContractUtils.transformMethodInPlace(method = m, transformers = transformer)
            }
        }
    }

    private fun reportFailuresAndGenerateAnnotations(failures: Map<StorageAnalysis.Base, StorageAnalysisResult.Failure>, m: ITACMethod): List<TACCmd.Simple> {
        val code = m.code as CoreTACProgram
        return failures.map { (base, failure) ->
            val reasonException = failure.reason
            val locOfFail = (reasonException as? StorageAnalysisFailedException)?.loc
            val addendum = locOfFail?.let { " @ $it" }.orEmpty()
            val msg = "Storage analysis for $base failed with message: ${failure.reason.message}${addendum}"
            val graph = code.analysisCache.graph
            val rangeWithMsgDetails = locOfFail
                ?.let { getSourceHintWithRange(graph.elab(it), graph, m) }
                ?: FailureInfo.NoFailureInfo

            val userMsg = UserFailMessage.StorageAnalysisFailureMessage(
                m.getContainingContract().name,
                m.soliditySignature ?: m.name,
                CertoraException.getErrorCodeForException(reasonException),
                rangeWithMsgDetails.additionalUserFacingMessage,
                base.toString()
            )
            CVTAlertReporter.reportAlert(
                type = CVTAlertType.STORAGE_ANALYSIS,
                severity = CVTAlertSeverity.WARNING,
                jumpToDefinition = rangeWithMsgDetails.range,
                message = userMsg.getFullMessage(),
                url = CheckedUrl.ANALYSIS_OF_STORAGE
            )
            TACCmd.Simple.AnnotationCmd(
                STORAGE_ANALYSIS_FAILURE,
                StorageAnalysisFailureInfo(
                    msg,
                    userMsg,
                    base
                )
            )
        }
    }

    /**
    A static array access path is a sequence of [Step]s, either constant offsets (i.e.
    into a struct) or some stride (i.e. indexing into an array). Only used internally to
    reconstruct static array indices.
     */
    sealed interface Step
    sealed interface HashInputStep: Step {
        val baseSlot: TACSymbol
        val parentPath: StorageAnalysis.AnalysisPath
    }
    data class Const(val offset: BigInteger) : Step
    data class StaticArray(
        val elementSize: BigInteger,
        val thisPath: StorageAnalysis.AnalysisPath.StaticArrayAccess
    ) : Step
    data class Array(
        val elementSize: BigInteger,
        override val baseSlot: TACSymbol,
        val thisPath: StorageAnalysis.AnalysisPath.ArrayAccess
    ) : HashInputStep {
        override val parentPath
            get() = thisPath.base
    }
    data class Mapping(
        val dynamicBase: TACSymbol,
        override val baseSlot: TACSymbol,
        override val parentPath: StorageAnalysis.AnalysisPath
    ) : HashInputStep

    private fun instrumentAccessPaths(ctp: CoreTACProgram, storageResult: StorageAnalysisResults.MergedResults) : CoreTACProgram {
        val commandGraph = ctp.analysisCache.graph
        val newVariables = mutableSetOf<TACSymbol.Var>()

        val mutations = storageResult.accessedPaths.mapNotNull { (where, paths) ->
            val cmd = commandGraph.elab(where).cmd
            fun constructArrayIndexCommands(
                path: StorageAnalysis.AnalysisPath,
                slot: TACSymbol.Var
            ): Pair<List<TACCmd.Simple>, Map<StorageAnalysis.AnalysisPath, TACSymbol.Var>> =
                if (cmd is TACCmd.Simple.WordStore || cmd is TACCmd.Simple.AssigningCmd.WordLoad) {
                    fixupArrayIndices(slot, path, newVariables, storageResult.contractTree)
                } else {
                    listOf<TACCmd.Simple>() to mapOf()
                }

            /**
             * for every WordStore loc ... or WordLoad ... loc:
             * If there is a (non Top) [accessPaths] entry for loc:
             *      make sure all access paths are of the same type
             *      add this access path to the meta map of loc
             */

            if (paths.paths.isNotEmpty() && paths.paths.monadicFold { l, r ->
                    l.takeIf { l.javaClass == r.javaClass }
                } == null) {
                return@mapNotNull null
            }
            val slotSym = (cmd as TACCmd.Simple.StorageAccessCmd).loc
            val getIndexCommands = mutableListOf<TACCmd.Simple>()
            val (slot, fullPaths) = if (slotSym is TACSymbol.Var) {
                val pathsWithIndices = paths.map { path ->
                    val (indexCommands, newIndexVariables) =
                        constructArrayIndexCommands(path, slotSym)
                    getIndexCommands += indexCommands
                    substituteArrayIndices(path, newIndexVariables)
                }
                slotSym.copy(meta = slotSym.meta + (TACMeta.ACCESS_PATHS to pathsWithIndices)) to pathsWithIndices
            } else if (paths.paths.any { it !is StorageAnalysis.AnalysisPath.Root}) {
                // Generate a new variable to "hold" the access path in its meta.
                // If we don't do this, then later passes may assume that
                // a constant storage index is just a root access path.
                val indexVar = TACSymbol.Factory.getFreshAuxVar(
                    TACSymbol.Factory.AuxVarPurpose.ACCESS_PATH_INDEX,
                    TACSymbol.Var("staticArrayIndex", Tag.Bit256)
                ).withMeta(TACMeta.ACCESS_PATHS, paths)
                newVariables.add(indexVar)
                getIndexCommands += listOf(
                    TACCmd.Simple.AssigningCmd.AssignExpCmd(indexVar, slotSym)
                )
                indexVar to paths
            } else {
                slotSym to paths
            }
            val simplePaths = fullPaths.paths.map { it.toNonIndexed() }.toSet()
            when (cmd) {
                is TACCmd.Simple.WordStore -> {
                    where to getIndexCommands.plus(
                        cmd.copy(
                            loc = slot, meta = cmd.meta + (
                                TACMeta.STORAGE_PATHS to StorageAnalysisResult.StoragePaths(simplePaths)
                                ) + (
                                TACMeta.STORAGE_PRINTER to StoragePathPrinter
                                )
                        )
                    )
                }
                is TACCmd.Simple.AssigningCmd.WordLoad -> {
                    where to getIndexCommands.plus(
                        cmd.copy(
                            loc = slot, meta = cmd.meta + (
                                TACMeta.STORAGE_PATHS to StorageAnalysisResult.StoragePaths(simplePaths)
                                ) + (
                                TACMeta.STORAGE_PRINTER to StoragePathPrinter
                                )
                        )
                    )
                }
            }
        }
        val patching = ctp.toPatchingProgram()
        patching.addVarDecls(newVariables)
        for ((where, repl) in mutations) {
            patching.replaceCommand(where, repl)
        }
        for ((_, joinInstrumentation) in storageResult.joinInstrumentations) {
            for ((toSet, flags) in joinInstrumentation.flagSet) {
                val cmds = flags.map { (k, v) ->
                    patching.addVarDecl(k)
                    TACCmd.Simple.AssigningCmd.AssignExpCmd(
                        lhs = k,
                        rhs = v.asSym()
                    )
                }
                patching.insertAlongEdge(
                    toSet.first,
                    toSet.second,
                    cmds
                )
            }
        }
        for ((_, sideConditions) in storageResult.sideConditions) {
            for ((where, sc) in sideConditions) {
                val rangeOK = TACExpr.BinBoolOp.LAnd(
                    TACExpr.BinRel.Le(sc.range.lb.asTACExpr, sc.v.asSym()),
                    TACExpr.BinRel.Le(sc.v.asSym(), sc.range.ub.asTACExpr),
                    Tag.Bool,
                )

                val unfold = ExprUnfolder.unfoldPlusOneCmd("storageSideCondition", rangeOK) { x ->
                    TACCmd.Simple.AssertCmd(x.s, "Side condition on written storage")
                }

                patching.addVarDecls(unfold.varDecls)
                patching.insertAfter(where, unfold.cmds)
            }
        }
        // Add our hash result variables. The special case
        // is when we create a hash as a result of a BytesKeyHash summary,
        // which is the last command in the block and hence requires the
        // addition of a new block to perform the assignment to the result var.

            val byLocation = storageResult.hashInstrumentations.values.flatMap { hashInstrumentation ->  hashInstrumentation.hashResults.entries.flatMap { (locSym, tmp) ->
                locSym.first.mapToSet { ptr ->
                    Triple(ptr, locSym.second, tmp)
                }
            }}.groupBy(
                keySelector = { (ptr, _, _) -> ptr },
                valueTransform = { (_, sym, new) -> Pair(sym, new) }
            )
            for ((where, entries) in byLocation) {
                val newCmds = entries.map { (sym, new) ->
                    patching.addVarDecl(new)
                    TACCmd.Simple.AssigningCmd.AssignExpCmd(
                        lhs = new,
                        rhs = sym.asSym(),
                    )
                }

                val lastCmd = patching.originalCode[where.block]?.last()
                if (lastCmd is TACCmd.Simple.SummaryCmd && lastCmd.summ is BytesKeyHash) {
                    val assignHashBlock = patching.addBlock(
                        where.block,
                        newCmds +
                            listOf(
                                TACCmd.Simple.JumpCmd(lastCmd.summ.skipTarget)
                            )
                    )
                    patching.reroutePredecessorsTo(lastCmd.summ.skipTarget, assignHashBlock) {
                        it != assignHashBlock
                    }
                } else {
                    patching.insertAfter(where, newCmds)
                }
            }
        return patching.toCode(ctp)
    }

    /**
     * Compute any indices that are null in [path].
     * [slot] is the value of the pointer that [path] abstracts.
     * [newVars] accumulates any variables needed by this method
     * [contractTree] is the storage from the contract where [slot] and [path] were computed
     *
     * @return (cmds, map) such that the straight-line commands in cmds compute
     * the image of map, and map sends analysis paths with null indices to the
     * index that should be used.
     */
    fun fixupArrayIndices(
        slot: TACSymbol,
        path: StorageAnalysis.AnalysisPath,
        newVars : MutableSet<TACSymbol.Var>,
        contractTree: Set<StorageTree.Root>,
    ): Pair<List<TACCmd.Simple>, Map<StorageAnalysis.AnalysisPath, TACSymbol.Var>> {
        val cmds = mutableListOf<TACCmd.Simple>()
        val map = mutableMapOf<StorageAnalysis.AnalysisPath, TACSymbol.Var>()
        recomputeSegment(slot, path, newVars, cmds, map, contractTree)
        /*
           We need this dead code elimination pass because the split rewriter will run next.
           If we generate {index := bloop(R857)}
           where 1) index is never used and 2) R857 is eliminated,
           then the rewriter will complain that it is eliminating a used variable.
         */
        deadCodeElimIndexCommands(cmds, map.values.toSet())
        return Pair(cmds, map)
    }

    /**
     * Compute any missing indices for [path], as in fixupArrayIndices.
     */
    private fun recomputeSegment(
        slot: TACSymbol,
        path: StorageAnalysis.AnalysisPath,
        newVariables: MutableSet<TACSymbol.Var>,
        cmds: MutableList<TACCmd.Simple>,
        map: MutableMap<StorageAnalysis.AnalysisPath, TACSymbol.Var>,
        contractTree: Set<StorageTree.Root>,
    ) {
        // Imagine we have:
        //
        // struct S { uint x; uint y[2][4]; }
        // S[3][] arr; // root = 0
        //
        // and we have some [slot] derived from the computation
        // for arr[i][j].y[k]. Then [slot] can always be written:
        // [slot] = hash(0) + 27*i + 9*j + 1 + 2*k
        //
        // now we want to recompute, say, j from slot.
        // pathToHere looks like: [Array(27,0), StaticArray(9), Const(1), StaticArray(2)]
        // we'll store the answer in `last`.
        // we start with last = slot.
        // the general recipe is:
        //   - for each `Const(k)`, we subtract the offset k from the answer
        //   - for each `StaticArray(k)`, we take the residue mod k
        //   - for each `Map(k, base)`, we subtract sha3(k, base)
        //   - for each `Array(k, base)`, we subtract sha3(base) and then take residue mod k
        // finally, since [pathToHere] refers to the base, we _divide_
        // the answer by the arrayAccess's element size.
        //
        // in our example, the computation we generate looks like
        // idx0 = (slot - sha3(0)) % 27
        // idx1 = idx0 % 9
        // idx2 = idx1 - 1
        // index = idx2 / 2
        //
        //
        val pathToHere = unfoldStaticAccess(contractTree, path)

        when (val fst = pathToHere.first()) {
            is HashInputStep ->
                recomputeSegment(fst.baseSlot, fst.parentPath, newVariables, cmds, map, contractTree)
            else ->
                Unit
        }

        var last = if (slot is TACSymbol.Var) {
            slot
        } else {
            tmpVar("slot", newVariables).also {
                cmds.add(
                    TACCmd.Simple.AssigningCmd.AssignExpCmd(
                        it, slot.asSym()
                    )
                )
            }
        }

        fun rememberIndex(last: TACSymbol.Var, size: BigInteger, path: StorageAnalysis.AnalysisPath) {
            // This looks silly, but this stops an optimization pass from
            // removing a computation without updating the access path
            if (size != BigInteger.ONE) {
                val index = tmpVar("idx", newVariables).also {
                    newVariables.add(it)
                }
                cmds += listOf(
                    TACCmd.Simple.AssigningCmd.AssignExpCmd(
                        index,
                        TACExpr.BinOp.Div(last.asSym(), size.asTACSymbol().asSym())
                    )
                )
                map[path] = index
            } else {
                map[path] = last
            }
        }

        check(pathToHere.drop(1).all { it !is HashInputStep })

        for (step in pathToHere) {
            val next = tmpVar("idx", newVariables)

            when (step) {
                is Mapping -> {
                    // n.b. this will always be the first element, so
                    // last - sha3(key, slot) is the same as slot - sha3(key, slot)
                    cmds += listOf(
                        TACCmd.Simple.AssigningCmd.AssignExpCmd(next,
                            TACExpr.BinOp.Sub(last.asSym(), step.dynamicBase.asSym())
                        )
                    )
                }
                is Array -> {
                    // uint[3][]
                    // p = arr[i][j]
                    // p = hash(arr) + 3*i + j
                    // j = (p - hash(arr) % 3)
                    // n.b. this will always be the first element, so
                    // last - sha3(slot) is the same as slot - sha3(slot)
                    val base = tmpVar("base", newVariables)
                    val tmp = tmpVar("arrayMinusBase", newVariables)

                    cmds += listOf(
                        TACCmd.Simple.AssigningCmd.AssignSimpleSha3Cmd(base, EVM_WORD_SIZE.asTACSymbol(), listOf(step.baseSlot)),
                        TACCmd.Simple.AssigningCmd.AssignExpCmd(tmp, mathIntSub(last.asSym(), base.asSym()))
                    )

                    if (step.thisPath.index == null) {
                        rememberIndex(tmp, step.elementSize, step.thisPath)
                    }

                    cmds += TACCmd.Simple.AssigningCmd.AssignExpCmd(
                                next,
                                TACExpr.BinOp.Mod(tmp.asSym(), step.elementSize.asTACExpr)
                            )
                }
                is Const -> {
                    // Perform [last] -= constant offset
                    cmds += TACCmd.Simple.AssigningCmd.AssignExpCmd(
                                next, mathIntSub(last.asSym(), step.offset.asTACSymbol().asSym())
                            )
                }
                is StaticArray -> {
                    if (step.thisPath.index == null) {
                        rememberIndex(last, step.elementSize, step.thisPath)
                    }
                    // Remove an outer level of array indexing, i.e. use mod
                    cmds += listOf(
                        TACCmd.Simple.AssigningCmd.AssignExpCmd(
                            next,
                            TACExpr.BinOp.Mod(last.asSym(), step.elementSize.asTACSymbol().asSym())
                        )
                    )
                }
            }
            last = next
        }
    }

    private fun unfoldStaticAccess(
        contractTree: Set<StorageTree.Root>,
        path: StorageAnalysis.AnalysisPath
    ): List<Step> =
        when (path) {
            is StorageAnalysis.AnalysisPath.Root -> listOf(Const(path.slot))
            is StorageAnalysis.AnalysisPath.WordOffset -> listOf()

            is StorageAnalysis.AnalysisPath.StaticArrayAccess -> {
                unfoldStaticAccess(contractTree, path.base) + listOf(StaticArray(path.elementSize(contractTree), path))
            }
            is StorageAnalysis.AnalysisPath.StructAccess -> {
                unfoldStaticAccess(contractTree, path.base) + listOf(Const(path.offset.words)).takeIf { path.offset.words != BigInteger.ZERO}.orEmpty()
            }
            is StorageAnalysis.AnalysisPath.ArrayAccess ->
                listOf(Array(path.elementSize(contractTree), path.baseSlot, path))

            is StorageAnalysis.AnalysisPath.MapAccess ->
                listOf(Mapping(path.hashResult, path.baseSlot, path.base))
        }

    /**
     * @param [cmds] is a straight-line sequence of assignments where no variable is assigned twice
     * @return a new list with the dead [cmds] (assuming live [neededIndices]) filtered
     */
    private fun deadCodeElimIndexCommands(cmds: MutableList<TACCmd.Simple>, neededIndices: Set<TACSymbol.Var>) {
        val live = neededIndices.toMutableSet()
        val toKill = mutableSetOf<TACSymbol.Var>()
        for (c in cmds.reversed()) {
            (c as? TACCmd.Simple.AssigningCmd)?.lhs?.let {
                if (it in live) {
                    live -= it
                    live += c.getFreeVarsOfRhs()
                } else {
                    toKill += it
                }
            }
        }
        cmds.removeIf { (it as? TACCmd.Simple.AssigningCmd)?.lhs in toKill }
    }

    private fun StorageAnalysis.AnalysisPath.ArrayLikePath.elementSize(contractTree: Set<StorageTree.Root>): BigInteger {
        tailrec fun traverse(p: StorageAnalysis.AnalysisPath, f: (StorageTree.Type) -> BigInteger): BigInteger {
            return when (p) {
                is StorageAnalysis.AnalysisPath.ArrayAccess -> traverse(p.base) {
                    f((it as StorageTree.Type.Array).element)
                }
                is StorageAnalysis.AnalysisPath.MapAccess -> traverse(p.base) {
                    f((it as StorageTree.Type.Mapping).codomain)
                }
                is StorageAnalysis.AnalysisPath.Root -> f(contractTree.firstOrNull {
                    it.slot == p.slot && it.base == p.base
                }?.types!!)
                is StorageAnalysis.AnalysisPath.StructAccess -> traverse(p.base) {
                    // make sure to access [elements] with a `word` value
                    // as it is a word indexed map
                    f((it as StorageTree.Type.Struct).elements[p.offset.words]!!)
                }
                is StorageAnalysis.AnalysisPath.WordOffset -> `impossible!`
                is StorageAnalysis.AnalysisPath.StaticArrayAccess -> traverse(p.base) {
                    f((it as StorageTree.Type.StaticArray).element)
                }
            }
        }
        return traverse(this.base) {
            when (it) {
                is StorageTree.Type.Array -> it.elementSize
                is StorageTree.Type.StaticArray -> it.elementSize
                else -> `impossible!`
            }
        }
    }

    fun substituteArrayIndices(
        path: StorageAnalysis.AnalysisPath,
        indices: Map<StorageAnalysis.AnalysisPath, TACSymbol.Var>
    ): StorageAnalysis.AnalysisPath {
        fun recurse(subPath: StorageAnalysis.AnalysisPath): StorageAnalysis.AnalysisPath = when (subPath) {
            is StorageAnalysis.AnalysisPath.Root -> subPath
            is StorageAnalysis.AnalysisPath.MapAccess -> subPath.copy(base = recurse(subPath.base))
            is StorageAnalysis.AnalysisPath.StructAccess -> subPath.copy(base = recurse(subPath.base))
            // important that replacement is done bottom-up
            is StorageAnalysis.AnalysisPath.ArrayAccess -> subPath.copy(
                base = recurse(subPath.base), index = subPath.index
                ?: indices[subPath]
            )
            is StorageAnalysis.AnalysisPath.WordOffset -> `impossible!`
            is StorageAnalysis.AnalysisPath.StaticArrayAccess -> subPath.copy(
                base = recurse(subPath.base),
                index = subPath.index ?: indices[subPath]
            )
        }
        return recurse(path)
    }

    private fun mathIntSub(e1: TACExpr, e2: TACExpr): TACExpr {
        return TACExpr.Apply(
            f = TACExpr.TACFunctionSym.BuiltIn(
                bif = TACBuiltInFunction.SafeMathNarrow.Implicit(Tag.Bit256)
            ),
            ops = listOf(TACExpr.BinOp.IntSub(e1, e2)),
            tag = Tag.Bit256,
        )
    }

    private fun tmpVar(prefix: String, newVariables: MutableSet<TACSymbol.Var>): TACSymbol.Var =
        TACKeyword.TMP(Tag.Bit256, "!$prefix").toUnique("!").also {
            newVariables.add(it)
        }
}
