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

import com.certora.collect.*
import config.Config
import datastructures.stdcollections.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.plus
import tac.*
import utils.*
import vc.data.*
import vc.data.parser.infix.TACInfixExprParser
import vc.data.parser.infix.TacInfixLexer
import vc.data.tacexprutil.subs
import java.io.BufferedWriter
import java.io.FileInputStream
import java.io.FileWriter
import java.io.InputStream
import java.util.function.Function

annotation class CustomTacParser

val mapOfMetaMapSerializer = MapSerializer(Int.serializer(), MetaMap.Serializer)

sealed class CmdArguments {
    data class TACExprArg(val exp: TACExpr): CmdArguments()
    data class BlockID(val blockIds: NBId): CmdArguments()
    data class TACString(val str:String): CmdArguments()
    companion object {

        fun lift(expr: TACExpr) = TACExprArg(expr)
        fun lift(blockId: NBId) = BlockID(blockId)
        //fun lift(sym:TACSymbol) = TACSymbolArg(sym)
        fun lift(strArg:String) = TACString(strArg)

    }
}

/**
 * cup parser intermediate representation classes: needs some post processing, to get the final TACProgram
 */

val META_REF_INDEX = MetaKey<Int>("vc.data.parser.META_REF_INDEX")
//used by cup parser
fun varWithMetaRefIndex(v: TACSymbol.Var,i: Int) = v.withMeta(META_REF_INDEX , i)
data class ProgramCupOutput(val blocks : Map<NBId, List<Function<TACCmdParserImplementation, TACCmd.Simple>>>,
                            val graph : Map<NBId, Set<NBId>>)

data class TACSymbolTableCupOutput(val userDefined: Map<String, Tag.UserDefined>, val bifs: BuiltinFunctions,
                                   val uifs: Map<String, FunctionInScope.UF>, val tagged : Set<TACSymbol.Var> )

data class CupOutput(val programCupOutput: ProgramCupOutput, val symbolTable: TACSymbolTableCupOutput,
                     val axioms: UfAxioms, val jsonOfMetas:String) {

    fun processCmds(symbolTable: TACSymbolTable, mapOfMetaMap: Map<Int, MetaMap>) : Map<NBId, List<TACCmd.Simple>>{
        val parser = TACCmdParserImplementation(symbolTable, mapOfMetaMap)
        val entries : Set<Map.Entry<NBId,List<Function<TACCmdParserImplementation, TACCmd.Simple>>>> = programCupOutput.blocks.entries
        val metaRefMapper = MetaRefToMetaMapper(mapOfMetaMap)
        //replace meta reference with the actual metas
        return entries.associateBy({it.key},{it.value.map{cmdTmp -> cmdTmp.apply(parser).let { cmd -> metaRefMapper.map(cmd) }}})
            .toPreferredMap()
    }
}


fun serializeTAC(tacProgram: TACProgram<*>, filename: String) {
    val (fileWriter, actualFileName) = ArtifactFileUtils.getWriterAndActualFileName(filename)
    val symbolTable = tacProgram.symbolTable
    val code = tacProgram.code
    val blocks = tacProgram.blockgraph
    val ufAxioms = if (tacProgram is WithUFAxioms) { tacProgram.ufAxioms } else { UfAxioms.empty() }
    val metas: MutableMap<MetaMap, Int> = mutableMapOf()
    val bifs: Set<FunctionInScope.Builtin> = if (tacProgram is CoreTACProgram || tacProgram is CanonicalTACProgram<*,*>) {
        /**
         * In order to be able to deserialize bif applications from the name they're given when serializing those
         * applications, we collect here all the bifs so that we can later write into the tac file a mapping from name
         * to bif.
         * Note that we don't support deserialization for non [CoreTACProgram]s anyway, so no need to do this in that case.
         */
        val ret = mutableSetOf<FunctionInScope.Builtin>()
        val bifCollector = object : AbstractDefaultTACCmdMapper() {
            override fun mapExpr(expr: TACExpr, index: Int): TACExpr {
                ret.addAll(
                    expr.subs.filterIsInstance<TACExpr.Apply>()
                        .map { it.f }
                        .filterIsInstance<TACExpr.TACFunctionSym.BuiltIn>()
                        .map { it.bif.toInScopeBuiltinFunc() }
                )

                return expr
            }

            override fun mapSymbol(t: TACSymbol, index: Int) = t
            override fun mapVar(t: TACSymbol.Var, index: Int) = t
            override fun mapLhs(t: TACSymbol.Var, index: Int) = t
            override fun mapConstant(t: TACSymbol.Const, index: Int) = t
        }
        tacProgram.code.values.flatten().forEach {
            if (it is TACCmd.Simple) {
                bifCollector.map(it)
            }
        }
        ret
    } else {
        setOf()
    }

    fileWriter.use { w ->
        writeTACSymbolTable(w, symbolTable, bifs, metas)
        writeProgram(w, code, blocks, metas)
        writeAxiom(w, symbolTable, ufAxioms)
        writeMetas(w, metas)
    }
    if (Config.VerifyTACDumps.get() && tacProgram is CoreTACProgram) {
        val deserialized = CoreTACProgram.parseStream(FileInputStream(actualFileName), ArtifactFileUtils.getBasenameOnly(actualFileName)) {
                nodes, blockgraph, typescope, axioms ->
            CoreTACProgram(
                nodes,
                blockgraph,
                filename,
                typescope,
                axioms,
                IProcedural.empty(),
            )
        }

        if (!compareCoreTACPrograms(tacProgram, deserialized)) {
            throw SerializationException("deserialized tac program is different")
        }
    }
}

private fun writeMetas(w: FileWriter, metas: Map<MetaMap, Int>) {
    w.append("Metas ")
    val mapOfMetaMap = metas.entries.associate { it.value to it.key }
    BufferedWriter(w).use { bufferedWriter ->
        val jsonMap = ReadableTACJson.encodeToString(mapOfMetaMapSerializer, mapOfMetaMap)
        bufferedWriter.write(jsonMap)
    }
    if (Config.VerifyTACDumps.get()) {
        testMetaMapSerialization(mapOfMetaMap)
    }
}

private fun writeAxiom(w: FileWriter, symbolTable: TACSymbolTable, ufAxioms: UfAxioms) {
    //Axioms
    w.appendLine("Axioms {")
    symbolTable.uninterpretedFunctions().forEach { func ->
        ufAxioms[func]?.apply { w.appendLine("\t\t${func.name}") }?.forEach { axiom ->
            w.appendLine("\t\t\tAxiom ${axiom.exp.printExpr(mutableMapOf())}")
        }
    }
    w.appendLine("}")
}
private fun writeProgram(
    w: FileWriter,
    code: BlockNodes<TACCmd>,
    blocks: BlockGraph,
    metas: MutableMap<MetaMap, Int>
) {
    w.appendLine("Program {")
    code.forEachEntry { (id, commands) ->
        w.appendLine("\tBlock $id Succ ${blocks[id]} {")
        commands.forEach { cmd ->
            w.appendLine("\t\t${cmd.printCmd(metas)}")
        }
        w.appendLine("\t}")
    }
    w.appendLine("}") //close Program
}



private fun writeTACSymbolTable(
    w: FileWriter,
    symbolTable: TACSymbolTable,
    bifs: Set<FunctionInScope.Builtin>,
    metas: MutableMap<MetaMap, Int>
) {
    w.appendLine("TACSymbolTable {")
    //user defined:
    w.appendLine("\tUserDefined {")
    symbolTable.userDefinedTypes.forEach {
        w.appendLine("\t\t${it.decl}")
    }
    w.appendLine("\t}")
    //Bifs:
    w.appendLine("\tBuiltinFunctions {")
    val mappedBifs = bifs.associateBy(
        { it.name },
        { inlinedTACJson.encodeToString(TACBuiltInFunction.serializer(), it.tacBif) })
    check(mappedBifs.size == bifs.size) { "bif mapping should be 1-to-1. name is not unique. " +
        "Got bifs $bifs" }
    check(mappedBifs.keys.all { TACIdentifiers.valid(it) }) { "unique alias must match identifier pattern. " +
        "Found mismatch in ${mappedBifs.keys.filterNot{TACIdentifiers.valid(it)}}" }
    mappedBifs.forEachEntry {
        w.appendLine("\t\t${it.key}:$jsonPrefixInCmd${it.value}")
    }
    w.appendLine("\t}")
    //Uifs:
    w.appendLine("\tUninterpretedFunctions {")
    check(symbolTable.uninterpretedFunctions().associateBy { it.name }.size == symbolTable.uninterpretedFunctions().size) {
        "duplicate uninterpreted functions ${symbolTable.uninterpretedFunctions() - symbolTable.uninterpretedFunctions().associateBy { it.name }.values}"
    }
    symbolTable.uninterpretedFunctions().forEach { func ->
        w.appendLine("\t\t${func.name}:$jsonPrefixInCmd${inlinedTACJson.encodeToString(FunctionInScope.UF.serializer(), func)}")
    }
    w.appendLine("\t}")
    //Vars:
    symbolTable.vars.forEach { variable ->
        w.appendLine(
            "\t${variable.printDeclaration()}${
                printMetaString(
                    metas,
                    variable.meta
                )
            }"
        )
    }
    w.appendLine("}") //typescope
}

/**
 * utility function to Compares 2 programs. (not checking meta maps
 * serialization, it is checked after serializing [testMetaMapSerialization]
 * It is tested there because metas in vars was excluded for Var.hashCode and it is not available after parsing...
 */
fun compareCoreTACPrograms(p1: CoreTACProgram, p2: CoreTACProgram) : Boolean {
    fun compareSymbolTables(s1: TACSymbolTable, s2:TACSymbolTable) {
        //compare vars
        check(s1.vars.hashCode() == s2.vars.hashCode())
        //compare user defined
        check(s1.types().hashCode() == s2.types().hashCode())
            {"userDefined types different:\n ${s1.types()},\n ${s2.types()}"}
        //compare UFs
        check(s1.uninterpretedFunctions().hashCode() == s2.uninterpretedFunctions().hashCode())
            {"UF are different: ${s1.uninterpretedFunctions()},${s2.uninterpretedFunctions()}"}
    }
    fun compareCodeBlock(b1: BlockNodes<*>, b2: BlockNodes<*>) {
        check(b1.entries.size == b2.entries.size) {"block sizes are different pre and post deserialization"}
        b1.entries.sortedBy{blcks -> blcks.key}.zip(b2.entries.sortedBy { blks -> blks.key })
        .find { blkPair -> blkPair.first.value.hashCode() != blkPair.second.value.hashCode() }?.let{
        (e1,e2) ->
            check(e1.value.size == e2.value.size) {"cmd list size is different. diff: ${e1.value - e2.value}"}
            e1.value.zip(e2.value).find {cmdPair -> cmdPair.first.hashCode() != cmdPair.second.hashCode()}?.let {
            cmdPair ->
                throw SerializationException("cmds are different $cmdPair")
            } ?:
            throw SerializationException("unknown difference $e1, $e2")
        }
    }
    compareCodeBlock(p1.code, p2.code)
    compareSymbolTables(p1.symbolTable,p2.symbolTable)
    check(p1.ufAxioms.hashCode() == p2.ufAxioms.hashCode()) { "axioms are different" }
    return true
}



fun String.parseExpr() : TACExpr {
    val inputStream: InputStream = this.byteInputStream()
    val csf = java_cup.runtime.ComplexSymbolFactory()
    val tacjfLexLexer = TacInfixLexer(csf,inputStream.reader())
    val infixParser = TACInfixExprParser(tacjfLexLexer,csf)
    val exprOutput = infixParser.parse().value as TACExpr
    return exprOutput
}

/**
 * args: [sourceName] used for logging error message
 */
fun <T> DeserializeTacStream(inputStream: InputStream, sourceName: String?, constructor: (
    nodes: BlockNodes<TACCmd.Simple>,
    blockGraph: BlockGraph,
    symbolTable: TACSymbolTable,
    axioms: UfAxioms) -> T) : T {
    val csf = java_cup.runtime.ComplexSymbolFactory()
    val tacjfLexLexer = TacLexer(csf, inputStream.reader())
    val tacParser = ParserTAC(tacjfLexLexer, csf)
    tacParser.setFileName(sourceName.orEmpty())

    val parsed = tacParser.parse()
    val parsedObject = (parsed.value as CupOutput)  // debug_parse
    //metas:
    val mapOfMetaMaps = jsonToMetaMap(parsedObject.jsonOfMetas)
    val symbolTableParsed = TACSymbolTable(
        parsedObject.symbolTable.userDefined.values.toTreapSet(),
        parsedObject.symbolTable.uifs.values.toTreapSet(),
        parsedObject.symbolTable.tagged.mapToTreapSet { it.withResolvedMetaRefs(mapOfMetaMaps) },
        mapOf()
    )

    val parsedBlocks = parsedObject.processCmds(symbolTableParsed, mapOfMetaMaps)
    val graph = parsedObject.programCupOutput.graph.toMap()
    //make the graph sorted, for some reason it makes the splitting fail the test
    val sortedGraph = graph.keys.toList().sorted().associateTo(MutableBlockGraph()) { it to graph[it]!!.toList().sorted().toTreapSet() }
    return constructor(parsedBlocks,sortedGraph,symbolTableParsed, parsedObject.axioms)

}

fun jsonToMetaMap(json: String) = ReadableTACJson.decodeFromString(mapOfMetaMapSerializer, json)
fun jsonToHashFamily(json: String) = inlinedTACJson.decodeFromString(HashFamily.serializer(), json)
fun jsonToTACBif(json: String) = inlinedTACJson.decodeFromString(TACBuiltInFunction.serializer(), json);
fun jsonToUF(json: String) = inlinedTACJson.decodeFromString(FunctionInScope.UF.serializer(), json)

@OptIn(ExperimentalSerializationApi::class)
val ReadableTACJson = Json {
    serializersModule = serializersmodules.GeneralUtils + serializersmodules.Shared + serializersmodules.CertoraProver
    allowStructuredMapKeys = true
    classDiscriminator = "#class" //used this to distinguish easily between members that are named "type"
    prettyPrint = true
    prettyPrintIndent = "  "
}

@OptIn(ExperimentalSerializationApi::class)
val inlinedTACJson = Json(from= ReadableTACJson) {
    prettyPrint = false
    prettyPrintIndent = Json.Default.configuration.prettyPrintIndent
}

private fun testMetaMapSerialization(mapOfMetaMap : Map<Int, MetaMap>) {
    mapOfMetaMap.values.forEach { mm ->
        val jsonned = ReadableTACJson.encodeToString(MetaMap.Serializer, mm)
        val deserializeMetaMap = ReadableTACJson.decodeFromString(MetaMap.Serializer, jsonned)
        check(mm == deserializeMetaMap) {
            "MetaMap serialization failed: $mm != $jsonned"
        }
    }
}
