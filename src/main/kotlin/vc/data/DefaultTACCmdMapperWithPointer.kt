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

package vc.data

import analysis.CmdPointer
import analysis.ExpPointer
import analysis.LTACCmd
import vc.data.tacexprutil.QuantDefaultTACExprTransformerWithPointer

/**
 * An extension of [DefaultTACCmdMapper] with CmdPointer-awareness.
 *
 * Note that you have to update [currentPtr] yourself when overriding this. See e.g.
 * [IndexingDefaultTACCmdMapperWithPointer] for a version that does the updating (but also more).
 */
open class DefaultTACCmdMapperWithPointer : TACCmdMapperWithPointer, DefaultTACCmdMapper() {
    override var currentPtr: CmdPointer? = null
    override val decls: MutableSet<TACSymbol.Var> = mutableSetOf()

    override fun mapCommand(c: LTACCmd) =
        listOf(map(c.cmd))
}

/**
 * Word of caution:
 *  Do not manipulate symbols within this transformer (e.g. changing the type of a variable).
 *  This can be used to structurally change expressions; a second transformation is needed when symbols need to be
 *  transformed.
 */
abstract class IndexingDefaultTACCmdMapperWithPointer : TACCmdMapperWithPointer, IndexingDefaultTACCmdMapper() {
    override var currentPtr: CmdPointer? = null
    override val decls: MutableSet<TACSymbol.Var> = mutableSetOf()

    abstract val exprMapper: QuantDefaultTACExprTransformerWithPointer

    override fun mapCommand(c: LTACCmd): List<TACCmd.Simple> {
        currentPtr = c.ptr
        return listOf(map(c.cmd))
    }


    override fun mapLhs(t: TACSymbol.Var, index: Int): TACSymbol.Var {
        return mapSymbol(t, index) as TACSymbol.Var
    }

    /** Final for the same reasons as [mapVar] (you can still override [mapConstant] if needed). */
    final override fun mapSymbol(t: TACSymbol, index: Int): TACSymbol {
        return super.mapSymbol(t, index)
    }

    /**
     * We're forbidding the updating of symbols in these Indexing*Mappers.
     * Instead symbols have to be updated in a second transformation.
     * The reason is that
     *  - these Indexing*Mappers deal in ExpPointers
     *  - usually a symbol needs to be updated everywhere it occurs,
     *  - usually ExpPointers cannot be used to point into AnnotationCmds and MetaMaps, thus updating a symbol using
     *    expPointers will usually make the MetaMaps and AnnotationCmds inconsistent
     * */
    final override fun mapVar(t: TACSymbol.Var, index: Int): TACSymbol.Var {
        return super.mapVar(t, index)
    }

    /**
     * If needed, override `mapAssignExpCmd(lhs: TACSymbol.Var, rhs: TACExpr, metaMap: MetaMap)`;
     * I'm forbidding to override this one since it's easy to get confused with the control flow (overriding this
     * might preclude the other function from being called, even though it's being overridden) -- not a strong
     * constraint, more of a guideline for future implementers.

     */
    final override fun mapAssignExpCmd(t: TACCmd.Simple.AssigningCmd.AssignExpCmd): TACCmd.Simple =
         mapAssignExpCmd(t.lhs, t.rhs, t.meta)

    override fun mapExpr(expr: TACExpr, index: Int): TACExpr =
        exprMapper.transform(
            QuantDefaultTACExprTransformerWithPointer.QuantVarsAndExpPointer.getEmpty(
                ExpPointer(
                    currentPtr!!,
                    ExpPointer.Path(index)
                )
            ),
            expr
        )
}
