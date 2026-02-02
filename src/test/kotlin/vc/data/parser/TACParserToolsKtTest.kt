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

import analysis.storage.BytesKeyHash
import analysis.storage.StorageAnalysis
import analysis.storage.StorageAnalysisResult
import com.certora.collect.*
import datastructures.stdcollections.*
import evm.EVM_WORD_SIZE
import kotlinx.serialization.encodeToString
import loaders.WithTACSource
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tac.*
import testing.ttl.TACMockLanguage
import utils.uncheckedAs
import vc.data.*
import vc.data.TACCmd.Simple
import vc.data.TACCmd.Simple.AnnotationCmd.Annotation
import vc.data.TACMeta.ACCESS_PATHS
import java.io.File
import java.math.BigInteger
import kotlin.reflect.KClass

fun getAllConcreteSubclasses(klass : KClass<*>) : List<KClass<*>>{
    if (klass.isSealed) {
        return klass.sealedSubclasses.fold(mutableListOf()) { acc, it -> acc += getAllConcreteSubclasses(it); acc }
    }
    return listOf(klass)
}
internal class TACParserToolsKtTest: WithTACSource {

    object TACLoader : WithTACSource {}
    fun emptyInstrumentation() : InstrumentationTAC =
        InstrumentationTAC(UfAxioms.empty())


    class exhaustiveCheck(): DefaultTACCmdMapper() {
        val allCmds : MutableMap<KClass<out Simple>, Boolean> = getAllConcreteSubclasses(Simple::class).uncheckedAs<List<KClass<Simple>>>().associateWith{_ -> false}.toMutableMap()
        override fun map(t: Simple): Simple {
            allCmds.put(t::class, true)
            return t
        }
        fun getUnChecked() = allCmds.entries.filter { entry -> !entry.value }
        fun allChecked() = getUnChecked().size==0
    }

    @Test
    fun testExhaustivityOfParsing() {
        val x1 = TACSymbol.Var.stackVar(1)
        val x2 = TACSymbol.Var("x2",Tag.Bit256)
        val one = TACSymbol.One
        val zero = TACSymbol.Zero
        val b1 = BlockIdentifier(0,0,0,0,0,0)
        val b2 = BlockIdentifier(1000,0,0,0,0,0)
        val b3 = BlockIdentifier(2000,0,0,0,0,0)
        val b4 = BlockIdentifier(3000,0,0,0,0,0)
        val m1 = TACKeyword.MEMORY.toVar()
        val bytemap1 = TACSymbol.Var("bm1", Tag.ByteMap,0,MetaMap())
        val almostAllCmdsList = listOf(
            Simple.AssigningCmd.AssignExpCmd(x1,x2),
            Simple.AssigningCmd.AssignSha3Cmd(x1, one, zero),
            Simple.AssigningCmd.AssignSimpleSha3Cmd(x1, EVM_WORD_SIZE.asTACSymbol(), listOf(x2)),
            Simple.AssigningCmd.ByteLoad(x1, zero, x2),
            Simple.AssigningCmd.ByteStore(zero,zero,bytemap1),
            Simple.AssigningCmd.ByteStoreSingle(zero, zero, bytemap1),
            Simple.AssigningCmd.WordLoad(x1, zero, x2),
            Simple.AssigningCmd.AssignMsizeCmd(x1),
            Simple.AssigningCmd.AssignGasCmd(x1,),
            Simple.AssigningCmd.AssignHavocCmd(x1,),
            Simple.LabelCmd("labelCmd"),
            Simple.LabelCmd("labelCmdWithQuote\""),
            Simple.LabelCmd("labelCmd\b\r\t"),
            Simple.LabelCmd("labelCmd\\{"),
            Simple.WordStore(zero, one, x1),
            Simple.ByteLongCopy(zero, one, TACSymbol.Const(BigInteger.valueOf(1000)), x1, x2),
            Simple.LogCmd(listOf(x1)),
            Simple.CallCore(zero, zero, zero, zero, x1,zero, zero, x2,TACCallType.REGULAR_CALL,zero),
            Simple.ReturnCmd(x1, x2),
            Simple.ReturnSymCmd(one),
            Simple.RevertCmd(zero, one, TACRevertType.THROW, x1),
            Simple.AssumeCmd(TACSymbol.True, "assumeCmd"),
            Simple.AssumeExpCmd(x1.asSym()),
            Simple.AssumeNotCmd(TACSymbol.False),
            Simple.AssertCmd(TACSymbol.True,"assertCmd"),
            Simple.AnnotationCmd(Annotation(MetaKey<String>("withQuotes"), "\"string without quotes\"")),
            Simple.AnnotationCmd(MetaKey.Nothing("noValue")),
            Simple.AnnotationCmd(Annotation(MetaKey<String>("withoutQuotes"), "string without quotes")),
            Simple.AssigningCmd.AssignExpCmd(
                x1,
                TACExpr.AnnotationExp(
                    x1.asSym(),
                    ACCESS_PATHS,
                    StorageAnalysisResult.AccessPaths(
                        treapSetOf(StorageAnalysis.AnalysisPath.Root(BigInteger.ONE, StorageAnalysis.Base.STORAGE))
                    )
                )
            ),
            Simple.SummaryCmd(BytesKeyHash(x1, zero, x2, treapSetOf(b2), b1, b3, treapSetOf(x1)), MetaMap()),
            Simple.NopCmd,
            Simple.JumpdestCmd(b2,),
        )
        val vars = treapSetOf(x1, x2, m1, bytemap1)
        val symbolTable = TACSymbolTable.withVars(vars)
        val allCommandsType = mapOf<NBId, List<Simple>>(
            b1 to almostAllCmdsList,
            b2 to listOf(Simple.JumpiCmd(b3, b4,TACSymbol.True)),
            b3 to listOf(Simple.JumpCmd(b4)),
            b4 to listOf(Simple.NopCmd))
        val code : BlockNodes<TACCmd.Simple> = allCommandsType
        val graph : BlockGraph = BlockGraph(b1 to treapSetOf(b2), b2 to treapSetOf(b3,b4), b3 to treapSetOf(b4), b4 to treapSetOf())
        val coreTacProgram = CoreTACProgram(code, graph,"test" , symbolTable,
            emptyInstrumentation(), emptySet(),
            true)
        exhaustiveCheck().let {chk->
            code.values.flatten().forEach { chk.map(it) }
            Assertions.assertTrue(chk.allChecked()) {"command list not exhaustive, define missing commands in program: ${chk.getUnChecked()}"}
        }

        val path = File("test.tac")
        serializeTAC(coreTacProgram, path.absolutePath)
        val de = CoreTACProgram.fromStream(path.inputStream(),"test")
        exhaustiveCheck().let { chk ->
            de.code.values.flatten().forEach { chk.map(it) }
            Assertions.assertTrue(chk.allChecked()) {"missing commands in deserialized program: ${chk.getUnChecked()}"}
        }
        Assertions.assertDoesNotThrow {
            compareCoreTACPrograms(coreTacProgram, de)
        }
    }




    @Test
    fun testQuotesInsideStringInJson() {
        val annot =
            Annotation(MetaKey<String>("the string have quotes") ,"string with \"quotes\"")
        val ser = inlinedTACJson.encodeToString<Annotation<String>>(annot)
        val de = inlinedTACJson.decodeFromString<Annotation<String>>(ser)
        Assertions.assertEquals(annot,de)
    }

    //Test that a metaMap can be used as a key in a Map<MetaMap, *> (used when serializing tac files)
    @Test
    fun TestMetaMapUniqueness() {
        val mkey1 = MetaKey<Boolean>("I'm Booly")
        val mkey2 = MetaKey<BigInteger>("I'm BigI")
        val mm1 = MetaMap().plus(mkey1 to true).
                    plus(mkey2 to BigInteger.ONE)
        val mm2 = MetaMap().plus(MetaKey<Boolean>("I'm Booly") to true).
                    plus(MetaKey<BigInteger>("I'm BigI") to BigInteger.ONE)
        val mm3 = MetaMap().plus(mkey1 to false).
            plus(mkey2 to BigInteger.TWO)
        Assertions.assertTrue(mapOf(mm1 to 1, mm2 to 2)[mm1]==2) //same metas, value should be updated
        Assertions.assertFalse(mapOf(mm1 to 1, mm2.plus(MetaKey<Int>("IntKey") to 5) to 2)[mm1]==2)
        Assertions.assertFalse(mapOf(mm1 to 1).containsKey(mm3)) //mm3 is the same keys but different values
    }

    @Test
    fun parseExpr01() {
        val exp = "$namePrefix:$tag".parseExpr()
        assertTrue(exp is TACExpr.Sym)
        val expSym = exp as TACExpr.Sym
        assertTrue(expSym.s is TACSymbol.Var)
        val sym = expSym.s as TACSymbol.Var
        assertTrue(sym.callIndex == 0)
    }


    @Test
    fun parseExpr02() {
        val exp = "$namePrefix$callIndexPrefix$callIndex:$tag".parseExpr()
        assertTrue(exp is TACExpr.Sym)
        val expSym = exp as TACExpr.Sym
        assertTrue(expSym.s is TACSymbol.Var)
        val sym = expSym.s as TACSymbol.Var
        assertTrue(sym.callIndex == callIndex)
    }

    @Test
    fun parseExpr03() {
        val zero = (TACSymbol.Const(BigInteger.ZERO).asSym())
        val exp = TACExpr.BinBoolOp.LAnd(listOf(zero, TACExpr.BinRel.Le(zero, zero)))
        val cmd = Simple.AssigningCmd.AssignExpCmd(TACSymbol.Var("L1020", Tag.Bit256),exp)
        Assertions.assertDoesNotThrow {
            val tac = TACMockLanguage.make {
                L1020 = "LAnd(0x0 (0x0 <= 0x0))"
            }
            Assertions.assertEquals(tac.code.values.first().first() , cmd)
        }
    }

    private val callIndexPrefix = TACIdentifiers.callIndexPrefix

    private val namePrefix = "blabla"
    private val callIndex = 13
    val tag = Tag.Bool

}
