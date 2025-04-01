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

package cvl

import analysis.abi.Traversal
import analysis.abi.arbitraryPrimitive
import analysis.abi.subTraversals
import analysis.icfg.InternalSummarizer
import analysis.icfg.Summarizer
import annotation.SolidityVersion
import annotations.PollutesGlobalState
import annotations.TestTags
import builders.LocatedType
import builders.SolidityTestBuilder
import builders.Type
import com.certora.collect.*
import config.Config
import evm.EVM_WORD_SIZE
import extension.DependentPair
import extension.setTo
import infra.CVLFlow
import infra.CertoraBuildException
import kotlinx.coroutines.runBlocking
import log.*
import net.jqwik.api.*
import net.jqwik.api.lifecycle.AfterProperty
import net.jqwik.api.lifecycle.BeforeProperty
import net.jqwik.kotlin.api.any
import net.jqwik.kotlin.api.combine
import net.jqwik.kotlin.api.ofSize
import org.junit.jupiter.api.Assertions
import org.opentest4j.TestAbortedException
import report.DummyLiveStatsReporter
import rules.CompiledRule
import scene.ProverQuery
import solver.SolverResult
import spec.CVLCompiler
import utils.*
import vc.data.TACCmd
import verifier.TACVerifier
import java.io.Serializable
import java.math.BigInteger

private typealias TraversalCombinator = (Traversal<Unit>) -> Traversal<Unit>

// Left commented during review to ensure this is tested
@Tag(TestTags.EXPENSIVE)
class ComplexTypeRandomTests {

    companion object {
        const val MAX_ITER_SIZE = 3

    }

    data class PrimitiveTraversal(val argType: Type, val primitive: Type.Primitive, val traversal: Traversal<Unit>)

    @Provide
    fun compilerVersions() : Arbitrary<SolidityVersion> = Arbitraries.of(
        SolidityVersion.V8_19,
        SolidityVersion.V8_13,
        SolidityVersion.V8_16,
        SolidityVersion.V8_11,
        SolidityVersion.V8_9
    )

    private fun arbitraryTraversal(p: Type) : Arbitrary<Pair<Type.Primitive, List<TraversalCombinator>>> {
        return when(p) {
            is Type.Array -> {
                arbitraryTraversal(p.elements).map { (prim, comb) ->
                    prim to (comb + {
                        Traversal.Indexed(next = it, index = Unit)
                    })
                }
            }
            is Type.Primitive -> Arbitraries.just(p to listOf())
            is Type.Struct -> {
                Arbitraries.of(p.members.keys).flatMap { k ->
                    val fieldType = p.members[k]!!
                    arbitraryTraversal(fieldType).map { (prim, comb) ->
                        prim to (comb + {
                            Traversal.Field(field = k, next = it)
                        })
                    }
                }
            }

            is Type.StaticArray -> {
                arbitraryTraversal(p.elements).map { (prim, comb) ->
                    prim to (comb + {
                        Traversal.Indexed(next = it, index = Unit)
                    })
                }
            }
        }
    }

    private fun arbitraryPrimitiveTraversal(p: Arbitrary<Type>) : Arbitrary<PrimitiveTraversal> {
        return p.flatMap { typ ->
            arbitraryTraversal(typ).map { (prim, comb) ->
                PrimitiveTraversal(
                    argType = typ,
                    primitive = prim,
                    traversal = comb.reversed().fold(Traversal.Root(typ)) { acc: Traversal<Unit>, stepComb: TraversalCombinator ->
                        stepComb(acc)
                    }
                )
            }
        }
    }

    @Provide
    fun arbitraryStruct(arrayDepth: Int, totalDepth: Int): Arbitrary<Type.Struct> {
        return nestedType(arrayDepth, totalDepth - 1).list().ofSize(1 .. 3).map {
            var offs = BigInteger.ZERO
            val m = treapMapBuilderOf<BigInteger, Type>()
            for(t in it) {
                m[offs] = t
                offs += t.sizeInBytes
            }
            Type.Struct(
                m.build()
            )
        }
    }

    @Provide
    fun arbitraryDynamicArray(i: Int, totalDepth: Int): Arbitrary<Type.Array> {
        return nestedType(i-1, totalDepth - 1).map(Type::Array)
    }

    @Provide
    fun arbitraryStaticArray(i: Int, totalDepth: Int): Arbitrary<Type.StaticArray> {
        return combine(
            Int.any(2 .. MAX_ITER_SIZE),
            nestedType(i-1, totalDepth = totalDepth - 1)
        ) { sz, elemType ->
            Type.StaticArray(elements = elemType, len = sz.toBigInteger())
        }
    }

    private fun nestedType(arrayDepth: Int, totalDepth: Int) : Arbitrary<Type> {
        return if(totalDepth == 0) {
            arbitraryPrimitive().uncheckedAs()
        } else if(arrayDepth <= 0) {
            Arbitraries.lazy {
                Arbitraries.oneOf(
                    arbitraryPrimitive(),
                    arbitraryStruct(arrayDepth, totalDepth)
                )
            }
        } else {
            Arbitraries.lazy {
                Arbitraries.oneOf(
                    arbitraryPrimitive(),
                    arbitraryStruct(arrayDepth, totalDepth),
                    arbitraryStaticArray(arrayDepth,totalDepth),
                    arbitraryDynamicArray(arrayDepth, totalDepth)
                )

            }
        }
    }

    @Provide
    fun arbitraryTraversal(): Arbitrary<PrimitiveTraversal> {
        return arbitraryPrimitiveTraversal(nestedType(2, 5))
    }

    private abstract class ComplexTypeTestBuilder(contract: String, prim: PrimitiveTraversal) : SolidityTestBuilder(contract) {
        protected fun Traversal<String>.compile(rootVar: String) : String {
            return when(this) {
                is Traversal.Field -> "${this.next.compile(rootVar)}.${fieldName(this.field)}"
                is Traversal.Indexed -> "${this.next.compile(rootVar)}[${this.index}]"
                is Traversal.Root -> rootVar
            }
        }

        @JvmName("traversalInt")
        protected fun Traversal<Int>.compile(complexParamInd: Int = numIndexArgs): String {
            return when(this) {
                is Traversal.Field -> {
                    this.next.compile(complexParamInd) + ".${fieldName(this.field)}"
                }
                is Traversal.Indexed -> this.next.compile(complexParamInd) + "[${inputVar(this.index)}]"
                is Traversal.Root -> inputVar(complexParamInd)
            }
        }

        abstract val spec: String

        private var ctr = 0
        protected val withIndex : Traversal<Int>
        protected val withIndexVars : Traversal<String>
        private val indexVars = mutableMapOf<Int, String>()
        protected val numIndexArgs : Int get() = ctr
        init {
            withIndex = prim.traversal.mapIndices {
                ctr++
            }
            withIndexVars = withIndex.mapIndices {
                indexVars.computeIfAbsent(it) { _ ->
                    "i$it"
                }
            }
        }

        private val capture = prim
        protected val complexVar = "complexVal"
        protected val complexDeclaration get() = "${qualifiedTypeName(capture.argType)} $complexVar;"

        protected val envName = "e"

        private val indexAccesses get() = withIndexVars.subTraversals {
            it as? Traversal.Indexed
        }

        protected val standardAssumes get() =  indexAccesses.map {
            val nextLength = when(val ty = it.next.type) {
                is Type.Array -> it.next.compile(complexVar) + ".length"
                is Type.StaticArray -> ty.len.toString()
                is Type.Primitive,
                is Type.Struct ->  `impossible!`
            }
            "require(${it.index} < $nextLength);"
        }

        protected fun standardRuleParams() = listOf("env $envName") + standardIndexParams()

        protected fun standardCallArgs() = listOf(envName) + standardIndexArgs()

        protected fun standardIndexParams() = List(ctr) {
            "uint ${indexVars[it]!!}"
        }

        protected fun standardIndexArgs() = List(ctr) {
            indexVars[it]!!
        }

        protected fun standardParams() = List(ctr) {
            LocatedType(Type.Primitive.Integer(
                signed = false, bytes = EVM_WORD_SIZE
            ), false)
        }
    }


    private class FaithfulCallEncoding(contract: String, traverse: PrimitiveTraversal, calldata: Boolean) : ComplexTypeTestBuilder(contract, traverse) {
        override val spec : String

        init {
            val complexParamInd = numIndexArgs
            val argType = LocatedType(traverse.argType, !calldata)
            val indexTypes = standardParams()
            val paramTypes = indexTypes + argType
            val namedParams = paramTypes.map {
                addType(it)
            }
            val retType = addType(LocatedType(traverse.primitive, false))
            val methodName = addMethodTriple(
                namedParams, retType, listOf(
                    "return ${withIndex.compile(complexParamInd)}"
                )
            )
            val primitiveVar = "theValue"
            val assumes = standardAssumes + """
                require(${withIndexVars.compile(complexVar)} == $primitiveVar);
            """.trimIndent()
            val ruleParams = standardRuleParams() + "${typeName(traverse.primitive)} $primitiveVar"
            val callParams = standardCallArgs() + complexVar
            spec = """
                rule roundtrip_correct(${ruleParams.joinToString(", ")}) {
                     $complexDeclaration
                     ${assumes.joinToString("\n")}
                     assert($methodName(${callParams.joinToString(", ")}) == $primitiveVar);
                }
            """.trimIndent()
        }
    }

    private val configs = listOf(
        Config.IsAssumeUnwindCondForLoops setTo true,
        Config.IsCIMode setTo true,
        Config.LowFootprint setTo true,
        Config.EnableConditionalSnippets setTo false,
        Config.EnableStatistics setTo false,
        Config.LoopUnrollConstant setTo MAX_ITER_SIZE,
        Config.SolverTimeout setTo 5,
        Config.MediumSplitTimeout setTo 2,
        Config.SplittingDepthPrivate setTo 3
    )

    private val saved = mutableListOf<DependentPair<*>>()

    @PollutesGlobalState
    private fun <T: Serializable> saveAndSet(v: DependentPair<T>) {
        saved.add(
            v.opt setTo v.opt.get()
        )
        v.opt.set(v.desired)
    }

    @PollutesGlobalState
    private fun <T: Serializable> restore(v: DependentPair<T>) {
        v.opt.set(v.desired)
    }

    @BeforeProperty
    @PollutesGlobalState
    fun beforeProperty() {
        for (config in configs) {
            saveAndSet(config)
        }
    }

    // Need this because jqwik doesn't respect jupiter stuff
    @AfterProperty
    @PollutesGlobalState
    fun afterProperty() {
        for (config in saved) {
            restore(config)
        }
        saved.clear()
    }


    @Property(tries = 20, maxDiscardRatio = 20)
    fun testFaithfulEncoding(
        @ForAll("arbitraryTraversal") prim: PrimitiveTraversal,
        @ForAll isCalldata: Boolean,
        @ForAll optimized: Boolean,
        @ForAll("compilerVersions") compiler: SolidityVersion
    ) {
        val contract : ComplexTypeTestBuilder = FaithfulCallEncoding("test", prim, isCalldata)
        runComplexTypeCheck(prim, contract, optimized, compiler)
    }

    private fun runComplexTypeCheck(
        prim: PrimitiveTraversal,
        contract: ComplexTypeTestBuilder,
        optimized: Boolean,
        compiler: SolidityVersion
    ) {
        Assume.that(prim.argType !is Type.Primitive)
        val flow = CVLFlow()
        val res = try {
            try {
                flow.getProverQueryWithScene(
                    solc = compiler.compilerName(),
                    contract = contract.getContract(),
                    cvlText = contract.spec,
                    withOptimize = optimized
                )
            } catch(e: CertoraBuildException) {
                val message = e.message
                if(message != null && "CompilerError: Stack too deep" in message) {
                    flow.getProverQueryWithScene(
                        solc = compiler.compilerName(),
                        contract = contract.getContract(),
                        cvlText = contract.spec,
                        withOptimize = optimized,
                        viaIR = true
                    )
                } else {
                    throw e
                }
            }
        } catch(e: Exception) {
            Assertions.fail("Failed to build ${contract.spec}\n${contract.getContract()}", e)
        }
        val (scene, checkable) = try {
            flow.transformResultsToTACs(res)
        } catch (e: Exception) {
            Assertions.fail("building ${contract.spec}\n${contract.getContract()}", e)
        }
        for (check in checkable) {
            Assume.that(
                !check.tac.parallelLtacStream().anyMatch {
                    (it.cmd as? TACCmd.Simple.AssertCmd)?.description?.contains("EVM stack overflow") == true
                }
            )
            val verification = try {
                val cvl = (res.force().second as ProverQuery.CVLQuery.Single).cvl
                val linking = Summarizer.LinkingState<Int>()
                val withSummaries = InternalSummarizer.summarizeInternalFunctions(
                    code = check.tac,
                    summaries = cvl.internal,
                    expressionSummaryHandler = InternalSummarizer.ExpressionSummaryMaterializer(
                        cvlCompiler = CVLCompiler(
                            scene = scene,
                            cvl = cvl,
                            ruleName = "dummy",
                            globalScope = mapOf()
                        ),
                        scene = scene,
                        internalLinking = linking
                    )
                ).first.let {
                    CompiledRule.optimize(scene.toIdentifiers(), it)
                }
                runBlocking {
                    TACVerifier.verify(scene.toIdentifiers(), withSummaries, DummyLiveStatsReporter)
                }
            } catch(e: TestAbortedException) {
                throw e
            } catch (e: Exception) {
                Assertions.fail("Failed verifying contract: ${contract.getContract()}\n${contract.spec}", e)
            }
            Assume.that(verification.finalResult != SolverResult.TIMEOUT)
            Assertions.assertEquals(SolverResult.UNSAT, verification.finalResult) {
                "$compiler $optimized Contract: ${contract.getContract()}\n${contract.spec}"
            }
        }
    }

    private class FaithfulReturns(contract: String, prim: PrimitiveTraversal) : ComplexTypeTestBuilder(contract, prim) {
        override val spec: String
        init {
            val complexType = prim.argType
            val returnType = LocatedType(complexType, true)
            val primType = prim.primitive
            val internalArgs = standardParams() + LocatedType(prim.primitive, false)
            val namedArgs = internalArgs.map {
                addType(it)
            }
            val namedReturn = addType(returnType)
            val internalMethod = addInternalMethodTriple(
                input = namedArgs,
                output = namedReturn,
                body = listOf(
                    "${typeName(complexType)} memory toRet",
                    "return toRet"
                )
            )
            val internalCallArgs = List(numIndexArgs) {
                inputVar(it)
            } + inputVar(numIndexArgs)
            val externalMethod = addMethodTriple(
                input = namedArgs,
                output = namedReturn,
                body = listOf("return $internalMethod(${internalCallArgs.joinToString(", ")})")
            )

            val primitiveName = "theValue"

            val primitiveParam = "${typeName(primType)} $primitiveName"
            val summaryParams = standardIndexParams() + primitiveParam
            val summaryArgs = standardIndexArgs() + primitiveName

            spec = """
                methods {
                    function $contract.$internalMethod(${summaryParams.joinToString(", ")}) internal returns (${qualifiedTypeName(prim.argType)} memory) => summaryFun(${summaryArgs.joinToString(", ")});
                }

                function summaryFun(${summaryParams.joinToString(", ")}) returns ${qualifiedTypeName((prim.argType))} {
                    $complexDeclaration
                    ${standardAssumes.joinToString("\n")}
                    require(${withIndexVars.compile(complexVar)} == $primitiveName);
                    return $complexVar;
                }

                rule the_rule(${(standardRuleParams() + primitiveParam).joinToString(", ")}) {
                    ${qualifiedTypeName(prim.argType)} $complexVar = $externalMethod(${(standardCallArgs() + primitiveName).joinToString(", ")});
                    assert(${withIndexVars.compile(complexVar)} == $primitiveName);
                }
            """.trimIndent()
        }
    }

    private fun Type.hasStaticArray(): Boolean = when(this) {
        is Type.Primitive -> false
        is Type.StaticArray -> true
        is Type.Struct -> this.members.any { it.value.hasStaticArray() }
        is Type.Array -> this.elements.hasStaticArray()
    }

    @Property(tries = 20, maxDiscardRatio = 20)
    fun testInternalReturns(
        @ForAll("arbitraryTraversal") prim: PrimitiveTraversal,
        @ForAll optimized: Boolean,
        @ForAll("compilerVersions") compiler: SolidityVersion
    ) {
        // static arrays make the IFF unhappy
        Assume.that(!prim.argType.hasStaticArray())
        runComplexTypeCheck(prim, FaithfulReturns("test", prim), optimized, compiler)
    }

    private class InternalSummaryParams(prim: PrimitiveTraversal, isCalldata: Boolean, contract: String) : ComplexTypeTestBuilder(contract, prim) {
        override val spec: String
        init {
            val complexArg = LocatedType(prim.argType, !isCalldata)
            val primitiveArg = LocatedType(prim.primitive, false)
            val primitiveInput = numIndexArgs + 2
            val methodParams = standardParams() + complexArg + primitiveArg
            val namedParams = methodParams.map {
                addType(it)
            }
            val namedReturn = addType(LocatedType(Type.Primitive.Integer(signed = false, bytes = EVM_WORD_SIZE), false))
            val internalMethod = addInternalMethodTriple(
                namedParams,
                namedReturn,
                listOf("return block.timestamp")
            )
            val internalArgs = List(primitiveInput) {
                inputVar(it)
            }
            val externalMethod = addMethodTriple(
                namedParams,
                namedReturn,
                listOf("return $internalMethod(${internalArgs.joinToString(", ")})")
            )
            val primName = "primitive"
            val primitiveParam = "${qualifiedTypeName(prim.primitive)} $primName"
            val summaryArgs = standardIndexParams() + "${qualifiedTypeName(prim.argType)} ${if(isCalldata) "calldata" else "memory"} $complexVar" + primitiveParam
            spec = """
                methods {
                    function $contract.$internalMethod(${summaryArgs.joinToString(", ")}) internal returns (uint) => summaryFun(${(standardIndexArgs() + complexVar + primName).joinToString(", ")});
                }

                function summaryFun(${(standardIndexParams() + "${qualifiedTypeName(prim.argType)} $complexVar" + primitiveParam).joinToString(",")}) returns uint {
                    assert(${withIndexVars.compile(complexVar)} == $primName);
                    return 4;
                }

                rule the_rule(${(standardRuleParams() + primitiveParam).joinToString(", ")}) {
                     $complexDeclaration
                     ${standardAssumes.joinToString("\n")}
                     require($primName == ${withIndexVars.compile(complexVar)});
                     assert $externalMethod(${(standardCallArgs() + complexVar + primName).joinToString(", ")}) == 4;
                }
            """.trimIndent()
        }
    }

    @Property(tries = 20, maxDiscardRatio = 20)
    fun testInternalSummaryArgs(
        @ForAll("arbitraryTraversal") prim: PrimitiveTraversal,
        @ForAll isCalldata: Boolean,
        @ForAll optimized: Boolean,
        @ForAll("compilerVersions") compiler: SolidityVersion
    ) {
        runComplexTypeCheck(
            prim,
            InternalSummaryParams(prim = prim, isCalldata = isCalldata, contract = "test"),
            optimized,
            compiler
        )
    }
}
