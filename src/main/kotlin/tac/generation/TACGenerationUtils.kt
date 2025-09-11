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

package tac.generation

import analysis.*
import analysis.CommandWithRequiredDecls.Companion.mergeMany
import analysis.CommandWithRequiredDecls.Companion.withDecls
import datastructures.stdcollections.*
import tac.*
import vc.data.*
import vc.data.tacexprutil.*

fun assignHavoc(dest: TACSymbol.Var, meta: MetaMap = MetaMap()) =
    listOf(TACCmd.Simple.AssigningCmd.AssignHavocCmd(dest, meta)).withDecls(dest)

fun assign(dest: TACSymbol.Var, meta: MetaMap = MetaMap(), exp: TACExprFact.() -> TACExpr) =
    ExprUnfolder.unfoldTo(TACExprFactUntyped(exp), dest, meta).merge(dest)

/** Helper for logical implication */
infix fun TACExpr.implies(other: TACExpr): TACExpr =
    TACExprFactUntyped { not(this@implies) or other }

fun TACExpr.letVar(
    name: String,
    tag: Tag = Tag.Bit256,
    meta: MetaMap = MetaMap(),
    f: (TACExpr.Sym.Var) -> CommandWithRequiredDecls<TACCmd.Simple>
) = TACKeyword.TMP(tag, name).let { v ->
    mergeMany(
        assign(v, meta) { this@TACExpr },
        f(v.asSym())
    )
}

/** Like [letVar], but can return arbitrary [TACCmd]s */
fun TACExpr.letVarEx(
    name: String,
    tag: Tag = Tag.Bit256,
    meta: MetaMap = MetaMap(),
    f: (TACExpr.Sym.Var) -> CommandWithRequiredDecls<TACCmd>
) = TACKeyword.TMP(tag, name).let { v ->
    mergeMany(
        assign(v, meta) { this@TACExpr },
        f(v.asSym())
    )
}

fun memStore(l: TACExpr, v: TACExpr) =
    l.letVar { ls ->
        v.letVar { vs ->
            TACCmd.Simple.AssigningCmd.ByteStore(ls.s, vs.s, TACKeyword.MEMORY.toVar())
                .withDecls(TACKeyword.MEMORY.toVar())
        }
    }

fun TACExpr.letVar(
    tag: Tag = Tag.Bit256,
    meta: MetaMap = MetaMap(),
    f: (TACExpr.Sym.Var) -> CommandWithRequiredDecls<TACCmd.Simple>
) = letVar("", tag, meta, f)

/** Like [letVar], but can return arbitrary [TACCmd]s */
fun TACExpr.letVarEx(
    tag: Tag = Tag.Bit256,
    meta: MetaMap = MetaMap(),
    f: (TACExpr.Sym.Var) -> CommandWithRequiredDecls<TACCmd>
) = letVarEx("", tag, meta, f)

fun (TACExprFact.() -> TACExpr).letVar(
    name: String = "",
    tag: Tag = Tag.Bit256,
    meta: MetaMap = MetaMap(),
    f: (TACExpr.Sym.Var) -> CommandWithRequiredDecls<TACCmd.Simple>
) = TACExprFactUntyped.this().letVar(name, tag, meta, f)

/** Like [letVar], but can return arbitrary [TACCmd]s */
fun (TACExprFact.() -> TACExpr).letVarEx(
    name: String = "",
    tag: Tag = Tag.Bit256,
    meta: MetaMap = MetaMap(),
    f: (TACExpr.Sym.Var) -> CommandWithRequiredDecls<TACCmd>
) = TACExprFactUntyped.this().letVarEx(name, tag, meta, f)

fun TACCmd.Simple.withDecls(vararg decls: TACSymbol.Var) = listOf(this).withDecls(*decls)
fun TACCmd.withDecls(vararg decls: TACSymbol.Var) = listOf(this).withDecls(*decls)

fun assert(msg: String, meta: MetaMap = MetaMap(), cond: TACExprFact.() -> TACExpr) =
    cond.letVar("a", Tag.Bool) {
        TACCmd.Simple.AssertCmd(it.s, msg, meta).withDecls()
    }

fun assume(meta: MetaMap = MetaMap(), cond: TACExprFact.() -> TACExpr) =
    cond.letVar("a", Tag.Bool, meta) {
        TACCmd.Simple.AssumeCmd(it.s, "", meta).withDecls()
    }

fun label(label: String, meta: MetaMap = MetaMap()) = TACCmd.Simple.LabelCmd(label, meta).withDecls()

fun TACExprFact.mapDefinition(
    mapType: Tag.Map,
    def: TACExprFact.(List<TACExpr.Sym.Var>) -> TACExpr
): TACExpr.MapDefinition {
    val queryVars = mapType.paramSorts.map { TACKeyword.TMP(it) }
    val queryParams = queryVars.map { it.asSym() }
    return MapDefinition(
        queryParams,
        def(queryParams),
        mapType
    )
}

fun defineMap(
    map: TACSymbol.Var,
    meta: MetaMap = MetaMap(),
    def: TACExprFact.(List<TACExpr.Sym.Var>) -> TACExpr
): CommandWithRequiredDecls<TACCmd.Simple> {
    val mapType = map.tag as Tag.Map
    return assign(map, meta) { mapDefinition(mapType, def) }
}

val LONG_COPY_STRIDE = MetaKey<Int>("tac.generation.long.copy.stride")

/** Copies from a ByteMap into a temporary map, and calls [f] with the temporary map var. */
fun letBuf(
    fromByteMap: TACSymbol.Var,
    fromPos: TACExprFact.() -> TACExpr,
    len: TACExprFact.() -> TACExpr,
    stride: Int,
    f: (TACExpr.Sym.Var) -> CommandWithRequiredDecls<TACCmd.Simple>
) = fromPos.letVar { pos ->
    len.letVar { len ->
        TACKeyword.TMP(Tag.ByteMap).let { toByteMap ->
            mergeMany(
                listOf(
                    TACCmd.Simple.ByteLongCopy(
                        srcBase = fromByteMap,
                        srcOffset = pos.s,
                        dstBase = toByteMap,
                        dstOffset = TACSymbol.Zero,
                        length = len.s,
                        meta = MetaMap(LONG_COPY_STRIDE to stride)
                    )
                ).withDecls(toByteMap),
                f(toByteMap.asSym())
            )
        }
    }
}
