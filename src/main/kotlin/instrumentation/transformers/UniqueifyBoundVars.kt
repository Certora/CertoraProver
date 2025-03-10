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

import com.certora.collect.*
import datastructures.stdcollections.*
import tac.Tag
import vc.data.*
import vc.data.tacexprutil.DefaultAccumulatingTACExprTransformer
import java.io.Serializable
import java.util.stream.Collectors

/**
 * Rename all bound variables of [TACExpr.QuantifiedFormula] and [TACExpr.MapDefinition] so the names are unique.
 * This is done because some parts of the calltrace assume this (for example the [report.DynamicSlicer])
 */
class UniqueifyBoundVars {

    /**
     * This transformer needs an [AbstractDefaultTACCmdMapper] that could handle the remapping of bound vars within
     * [TACExpr.AnnotationExp]s.
     */
    private fun boundVarTransformer(cmdMapper: AbstractDefaultTACCmdMapper) = object : DefaultAccumulatingTACExprTransformer<Unit>() {
        val boundVarMap: MutableMap<TACSymbol.Var, TACSymbol.Var> = mutableMapOf()

        override fun transformVar(acc: Unit, exp: TACExpr.Sym.Var): TACExpr {
            return boundVarMap[exp.s]?.let { exp.copy(s = it) } ?: exp
        }

        override fun transformQuantifiedFormula(
            acc: Unit,
            isForall: Boolean,
            quantifiedVars: List<TACSymbol.Var>,
            body: TACExpr,
            tag: Tag?
        ): TACExpr {
            check(quantifiedVars.none { it in boundVarMap }) {
                "Found nested QuantifiedFormula/MapDefinition with the same bound variable name"
            }
            boundVarMap.putAll(quantifiedVars.associateWith { it.toUnique(".") })
            val ret = super.transformQuantifiedFormula(acc, isForall, quantifiedVars.map { boundVarMap[it]!! }, body, tag)
            quantifiedVars.forEach { boundVarMap.remove(it) }
            return ret
        }

        override fun transformMapDefinition(
            acc: Unit,
            defParams: List<TACExpr.Sym.Var>,
            definition: TACExpr,
            tag: Tag.Map
        ): TACExpr {
            val defVars = defParams.map { it.s }
            check(defVars.none { it in boundVarMap }) {
                "Found nested QuantifiedFormula/MapDefinition with the same bound variable name"
            }
            boundVarMap.putAll(defVars.associateWith { it.toUnique(".") })
            val ret = super.transformMapDefinition(acc, defParams.map { it.copy(s = boundVarMap[it.s]!!) }, definition, tag)
            defVars.forEach { boundVarMap.remove(it) }
            return ret
        }

        override fun <@Treapable T : Serializable> transformAnnotationExp(acc: Unit, exp: TACExpr.AnnotationExp<T>): TACExpr {
            val annot = cmdMapper.mapAnnotationCmd(TACCmd.Simple.AnnotationCmd(exp.annot)) as TACCmd.Simple.AnnotationCmd
            return TACExpr.AnnotationExp(transformArg(acc, exp.o, 0), annot.annot)
        }
    }

    private val cmdMapper = object : AbstractDefaultTACCmdMapper() {
        private val bTransformer = boundVarTransformer(this)

        override fun mapExpr(expr: TACExpr, index: Int): TACExpr {
            return bTransformer.transformArg(Unit, expr, index)
        }

        override fun mapSymbol(t: TACSymbol, index: Int): TACSymbol = bTransformer.boundVarMap[t] ?: t
        override fun mapVar(t: TACSymbol.Var, index: Int): TACSymbol.Var = bTransformer.boundVarMap[t] ?: t
        override fun mapLhs(t: TACSymbol.Var, index: Int): TACSymbol.Var = t
        override fun mapConstant(t: TACSymbol.Const, index: Int): TACSymbol.Const = t
    }

    fun transform(prog: CoreTACProgram): CoreTACProgram {
        return prog.patching { patching ->
            prog.parallelLtacStream().filter { lcmd ->
                lcmd.cmd.subExprs().any { it is TACExpr.QuantifiedFormula || it is TACExpr.MapDefinition }
            }.collect(Collectors.toList()).forEach { lcmd ->
                // Note - the toList above is required because the cmdMapper has state so can't be called concurrently.
                patching.replaceCommand(lcmd.ptr, listOf(cmdMapper.map(lcmd.cmd)))
            }
        }
    }
}
