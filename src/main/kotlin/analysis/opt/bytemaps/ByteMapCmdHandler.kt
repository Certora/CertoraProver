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

package analysis.opt.bytemaps

import tac.Tag
import utils.runIf
import vc.data.TACCmd
import vc.data.TACCmd.Simple.AssigningCmd.AssignExpCmd
import vc.data.TACExpr
import vc.data.TACSymbol
import vc.data.tacexprutil.asSym
import vc.data.tacexprutil.asVar

/**
 * A class to simplify handling of commands related to bytemaps. A few of these commands have two versions: as a command
 * and as an expression. Note that it is assumed here that commands handling bytemaps are in 3-address-form.
 */
interface ByteMapCmdHandler<T> {

    fun simpleAssign(lhs: TACSymbol.Var, rhs: TACSymbol.Var): T? = null
    fun ite(lhs: TACSymbol.Var, i : TACSymbol, t: TACSymbol.Var, e: TACSymbol.Var): T? = null
    fun load(lhs: TACSymbol.Var, base: TACSymbol.Var, loc: TACSymbol): T? = null
    fun store(lhs: TACSymbol.Var, rhsBase: TACSymbol.Var, loc: TACSymbol, value: TACSymbol): T? = null
    fun byteSingleStore(lhs: TACSymbol.Var, base: TACSymbol.Var, loc: TACSymbol, value : TACSymbol): T? = null
    fun havoc(lhs: TACSymbol.Var): T? = null
    fun mapDefinition(lhs: TACSymbol.Var, param: TACSymbol.Var, definition: TACExpr): T? = null
    fun longstore(
        lhs: TACSymbol.Var,
        srcOffset: TACSymbol,
        dstOffset: TACSymbol,
        srcMap: TACSymbol.Var,
        dstMap: TACSymbol.Var,
        length: TACSymbol
    ): T? = null

    fun fallthrough(): T? = null

    fun handle(cmd: TACCmd.Simple): T? = with(cmd) {
        when (this) {
            is AssignExpCmd -> with(rhs) {
                val lhs = lhs
                val lhsBytemap = lhs.tag is Tag.ByteMap
                when (this) {
                    is TACExpr.Sym -> runIf(lhsBytemap) {
                        simpleAssign(lhs, s.asVar)
                    }

                    is TACExpr.TernaryExp.Ite -> runIf(lhsBytemap) {
                        ite(lhs, i.asSym, t.asVar, e.asVar)
                    }

                    is TACExpr.Select -> runIf(base.tag is Tag.ByteMap) {
                        load(lhs, base.asVar, loc.asSym)
                    }

                    is TACExpr.Store -> runIf(lhsBytemap) {
                        store(lhs, base.asVar, loc.asSym, value.asSym)
                    }

                    is TACExpr.MapDefinition -> runIf(lhsBytemap) {
                        mapDefinition(lhs, defParams.single().s, definition)
                    }

                    is TACExpr.LongStore ->
                        longstore(
                            lhs,
                            srcOffset.asSym,
                            dstOffset.asSym,
                            srcMap.asVar,
                            dstMap.asVar,
                            length.asSym
                        )

                    else -> null
                }
            }

            is TACCmd.Simple.ByteLongCopy ->
                longstore(dstBase, srcOffset, dstOffset, srcBase, dstBase, length)


            is TACCmd.Simple.AssigningCmd.ByteStore ->
                store(lhs, base, loc, value)

            is TACCmd.Simple.AssigningCmd.ByteLoad ->
                load(lhs, base, loc)

            is TACCmd.Simple.AssigningCmd.ByteStoreSingle ->
                byteSingleStore(lhs, base, loc, value)

            is TACCmd.Simple.AssigningCmd.AssignHavocCmd ->
                runIf(lhs.tag is Tag.ByteMap) {
                    havoc(lhs)
                }

            else -> null
        }
    } ?: fallthrough()
}
