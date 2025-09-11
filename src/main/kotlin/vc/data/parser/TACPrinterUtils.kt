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

package vc.data.parser

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import tac.MetaMap
import tac.Tag
import vc.data.*


fun String.Quoted() = inlinedTACJson.encodeToString(String.serializer(),this)

fun vc.data.TACCmd.printCmd(mapOfMetaMap: MutableMap<MetaMap, Int>) = when(this) {
    is TACCmd.Simple -> printCmd(mapOfMetaMap)
    else -> toStringNoMeta()
}

fun TACExpr.QuantifiedFormula.printer(mapOfMetaMap: MutableMap<MetaMap,Int>): String {
    val quantifier = if (isForall) "Forall" else "Exists"
    val varlist = quantifiedVars.joinToString(" ")
    return "$quantifier( QVars($varlist) ${body.printExpr(mapOfMetaMap)})"
}

fun TACExpr.StructConstant.printer(mapOfMetaMap: MutableMap<MetaMap, Int>) : String{
    val entriesList =
        fieldNameToValue.entries.map { (fieldName, fieldValue) -> "$fieldName=${fieldValue.printExpr(mapOfMetaMap)}" }
    return "StructConstant(${entriesList.joinToString(" ")})"
}

private fun addAndIndex(metaRefs: MutableMap<MetaMap, Int>, meta:MetaMap) = metaRefs.computeIfAbsent(meta) { metaRefs.size }

fun printMetaString(metaRefs: MutableMap<MetaMap, Int>, meta: MetaMap):String {
    var metaRefStr = ""
    if (!meta.isEmpty()) {
        metaRefStr = ":${addAndIndex(metaRefs, meta)}"
    }
    return metaRefStr
}

fun TACSymbol.printer(mapOfMetaMap: MutableMap<MetaMap, Int>): String {
    return when (this) {
        is TACSymbol.Const -> {
            val tagStr = if (this.tag != Tag.Bit256 && this.tag != Tag.Bool) {
                "(${this.tag})"
            } else {
                ""
            }
            "${this.toString()}$tagStr"
        }
        is TACSymbol.Var -> printer(mapOfMetaMap) //Var.printer(mapOfMetaMap)
    }
}

@OptIn(ExperimentalSerializationApi::class)
public fun TACCmd.Simple.SummaryCmd.printer(mapOfMetaMap: MutableMap<MetaMap, Int>) : String {
    val jsonStr = inlinedTACJson.encodeToString<TACSummary>( this.summ)
    return "${this::class.simpleName}${printMetaString(mapOfMetaMap, meta)} $jsonPrefixInCmd$jsonStr"
}


public fun TACCmd.Simple.AnnotationCmd.printer(mapOfMetaMap: MutableMap<MetaMap, Int>) : String {
    val annotJson = inlinedTACJson.encodeToString(MetaMap.EntrySerializationSurrogate.serializer(),
        MetaMap.EntrySerializationSurrogate(annot.k, annot.v))
    return "${this::class.simpleName}${printMetaString(mapOfMetaMap, meta)} $jsonPrefixInCmd$annotJson"
}

fun TACExpr.AnnotationExp<*>.printer(mapOfMetaMap: MutableMap<MetaMap,Int>): String {
    val annotJson = inlinedTACJson.encodeToString(MetaMap.EntrySerializationSurrogate.serializer(),
        MetaMap.EntrySerializationSurrogate(annot.k, annot.v))
    return "AnnotationExp(${o.printExpr(mapOfMetaMap)} $jsonPrefixInCmd$annotJson)"
}



fun TACSymbol.Var.printer(mapOfMetaMap: MutableMap<MetaMap, Int>): String {
    return toSMTRep() + printMetaString(mapOfMetaMap, this.meta)
}
fun TACExpr.Apply.printer(mapOfMetaMap: MutableMap<MetaMap, Int>) =
    "Apply($f ${ops.joinToString(" ") {it.printExpr(mapOfMetaMap)}})"

fun TACExpr.Vec.IntMul.printer(mapOfMetaMap: MutableMap<MetaMap, Int>) = "IntMul(${ls.joinToString(" ") {it.printExpr(mapOfMetaMap)}})"
fun TACExpr.Vec.IntAdd.printer(mapOfMetaMap: MutableMap<MetaMap, Int>) = "IntAdd(${ls.joinToString(" ") {it.printExpr(mapOfMetaMap)}})"
fun TACExpr.Vec.Add.printer(mapOfMetaMap: MutableMap<MetaMap, Int>) = "Add(${ls.joinToString(" ") {it.printExpr(mapOfMetaMap)}})"
fun TACExpr.StructAccess.printer(mapOfMetaMap: MutableMap<MetaMap, Int>) = "StructAccess(${struct.printExpr(mapOfMetaMap)}  $fieldName)"
fun TACExpr.Unconstrained.printer() = "Unconstrained($tag)"

fun TACExpr.MapDefinition.printer(mapOfMetaMap: MutableMap<MetaMap, Int>) =
    "MapDefinition(${defParams.map { it.s }.joinToString(" ") { it.printDeclaration() }} -> ${
        definition.printExpr(mapOfMetaMap)
    } $tag)"

fun TACExpr.SimpleHash.printer(mapOfMetaMap: MutableMap<MetaMap, Int>) : String {
    val jsonHashFamily = "${inlinedTACJson.encodeToString(HashFamily.serializer(), hashFamily)}"
    return "SimpleHash(${length.printExpr(mapOfMetaMap)} " +
        "${args.joinToString(" ") { it.printExpr(mapOfMetaMap) }} " +
        "$jsonPrefixInCmd$jsonHashFamily)"
}
