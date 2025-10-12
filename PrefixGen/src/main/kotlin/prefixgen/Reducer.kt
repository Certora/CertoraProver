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
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package prefixgen

import algorithms.UnionFind
import algorithms.computeCommutativeCrossProduct
import bridge.types.PrimitiveType
import bridge.types.SolidityTypeDescription
import com.certora.collect.*
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.types.file
import prefixgen.FileUtils.deserialize
import prefixgen.fuzzer.RunData
import datastructures.stdcollections.*
import prefixgen.CodeGen.pp
import prefixgen.FileUtils.mustDirectory
import prefixgen.FileUtils.serialize
import prefixgen.data.*
import prefixgen.data.TaggedFile.Companion.into
import prefixgen.fuzzer.FuzzRunner
import spec.cvlast.typedescriptors.EVMTypeDescriptor
import utils.*
import java.io.File
import java.nio.file.Path

/**
 * Attempts to determine which aliasing relationships are "required".
 * That is, if we see in a CEX:
 * ```
 * c.foo(someVar);
 * c.bar(someOtherVar, someVar);
 * ```
 * does the second argument to `bar` *always* have to be the same value passed
 * as the argument to `foo`?
 */
object Reducer {
    /**
     * The reduced AST, with some aliasing decisions replaced with must aliasing assignments instead.
     */
    @KSerializable
    data class Reduced(
        override val ast: List<SimpleSolidityAst>,
        override val fixedPrefix: FixedPrefix,
        override val counter: Int,
        override val importManager: ImportManager,
        override val sceneData: SequenceGenerator.SceneData
    ) : SequenceGenerator.Extendable, AmbiSerializable

    /**
     * Try to reduce the counter example in [resFile] and [cd], seeing which aliasing decisions are "required'.
     *
     * NB this is all heuristic, we're just using a fuzzer to see if we can get a non-reverting path that does have some
     * existing aliasing. If the fuzzer gets super, super unlucky, we may prune the state space too eagerly.
     */
    fun reduce(
        resFile: TaggedFile<SequenceGenerator.ResumeData>,
        cd: TaggedFile<RunData>,
        generationDir: Path,
        workingDir: Path
    ) : SequenceGenerator.Extendable {
        generationDir.mustDirectory()
        val res = resFile.deserialize()
        val bindings = cd.deserialize().parseBindings(res.structIds, res.topLevelParams)

        /**
         * Binding level roughly indicates how "early" an identifier is bound. By convention,
         * everything pre-bound before the fuzz test runs is at level 0.
         */
        val bindingLevels = mutableMapOf<String, Int>()
        res.fixedPrefix.storageVars.forEach { vd ->
            bindingLevels[vd.rawId] = 0
        }

        /**
         * Associate identifiers to their type, starting with the storage vars and init bindigns.
         */
        val typeTable = res.fixedPrefix.storageVars.associate { (ty, id) ->
            id to ty
        }.toMutableMap()
        val initialBindings = res.sceneData.initialBinding.let(::File).into<Map<String, InitialBinding>>().deserialize()

        initialBindings.forEachEntry { (nm, bind) ->
            typeTable[nm] = bind.ty
            bindingLevels[nm] = 0
        }

        val mustEqual = mutableMapOf<String, TreapSet<String>>()

        /**
         * Records the alias chosen for identifier k.
         */
        val chosenAliasFor = mutableMapOf<String, Int>()
        for((i, a) in res.ast.withIndex()) {
            val bindingLevel = i + 1
            when(a) {
                is SimpleSolidityAst.SemanticLine,
                is SimpleSolidityAst.FunctionCall,
                is SimpleSolidityAst.Literal,
                is SimpleSolidityAst.BindArray,
                is SimpleSolidityAst.BindEnum -> continue
                is SimpleSolidityAst.BindInputChoice -> {
                    bindingLevels[a.boundId] = bindingLevel
                    typeTable[a.boundId] = a.ty
                    if(a.aliases == null) {
                        continue
                    }
                    val disc = bindings[a.aliases.discriminator]!!.bounded(a.aliases.aliases.size + 1).let { disc ->
                        if(disc == 0) {
                            return@let 0
                        }
                        val chosenAlias = a.aliases.aliases[disc - 1].id
                        val aliasesAlias = chosenAliasFor[chosenAlias] ?: return@let disc
                        if(aliasesAlias != 0) {
                            0
                        } else {
                            disc
                        }
                    }

                    chosenAliasFor[a.boundId] = disc
                    /**
                     * If we chose an alias, record this identifier as a "must alias" candidate for the referent.
                     */
                    if(disc != 0) {
                        val chosenId = a.aliases.aliases[disc - 1].id
                        mustEqual[chosenId] = mustEqual.getOrDefault(chosenId, treapSetOf()) + a.boundId
                    }
                }
                is SimpleSolidityAst.BindMust -> {
                    typeTable[a.boundId] = a.ty
                    val disc = bindings[a.discriminator.discriminator]!!.bounded(a.discriminator.aliases.size + 1).also {
                        check(it != 0)
                    }
                    // as above, but no chance for no aliasing.
                    chosenAliasFor[a.boundId] = disc
                    val aliasName = a.discriminator.aliases[disc - 1].id
                    mustEqual[aliasName] = mustEqual.getOrDefault(aliasName, treapSetOf()) + a.boundId
                }

            }
        }

        val setupCode = File(res.sceneData.setupFile).readText()


        /**
         * Now, generate hypothesized must aliasing pairs from the *observed* aliasing behavior in the CEX.
         *
         * For each (referent, alias) pair in `mustEqual` we generate a hypothesis. However, we *also* generate
         * a must-alias hypothesis for each unique pair in the codomain of mustEq.
         * Why?
         *
         * Suppose the fuzzer found and CEX where we have:
         * ```
         * address x = inputs.inputField
         * contract.foo(x);
         * address y = x;
         * contract.bar(y);
         * address z = x;
         * contract.baz(x);
         * ```
         *
         * Here, `z` and `y` were both chosen to alias `x`. However, it's possible that `z` was only chosen to alias `x`
         * because whatever the first argument to `bar` is must be the same argument to `bar`. Because THAT argument was itself
         * chosen to be an alias of `x`, `z` also had to be. Thus, there is *really* a must aliasing relationship between the
         * the arguments of `bar` and `baz`, expressed "transitively" through a shared alias `x`
         */
        val hypotheses = mustEqual.asSequence().flatMap { (targ, mustEq) ->
            mustEq.asSequence().map { i ->
                targ to i
            } + computeCommutativeCrossProduct(mustEq.toList(), skipSelfPairing = true) { a: String, b: String ->
                a to b
            }
        }.mapIndexedNotNull { ind, (id1, id2) ->
            /**
             * For each mustequal hypothesis, generate a test case that assumes the aliasing relationship *doesn't* hold.
             * If we fail to find one, consider that strong evidence that the must aliasing relationship is "real".
             */
            val comparison = generateEqualityExpression(id1, id2, typeTable) ?: return@mapIndexedNotNull null
            val sourceCode = object : Compiler() {
                override fun finalHook(): String {
                    return "vm.assume(!($comparison));"
                }
            }.compileFuzzContract(res, setupCode, initialBindings).soliditySource
            val outputPath = generationDir.resolve("hypothesis$ind.sol")
            outputPath.toFile().writeText(sourceCode)
            MustAliasHypothesis(
                id1, id2, sourceCode, outputPath
            )
        }.toList()

        val runner = FuzzRunner(workingDir)

        /**
         * Run the tests
         */
        val fuzzRes = runner.fuzzTests(hypotheses.map { it.outputPath }.asSequence()).getOrNull() ?: return res
        val unionFind = UnionFind<String>()
        for(hy in hypotheses) {
            if(hy.outputPath !in fuzzRes) {
                continue
            }
            /**
             * `null` in this case means "couldn't find a counterexample" meaning "it seems likely
             * the two hypothesized must aliasing relationship actually exists."
             */
            if(fuzzRes[hy.outputPath] != null) {
                continue
            }
            /**
             * Thus, record the must aliasing relationship in a union find.
             */
            unionFind.union(hy.id1, hy.id2)
        }
        // replace references to k with v (and remove the binding of k)
        val rewrites = mutableMapOf<String, String>()
        /**
         * Find all of the "must alias" classes, and for each, pick the earliest bound one using
         * binding levels. Then replace all other identifiers in the equivalence class
         * with reference to the chosen identifier.
         */
        unionFind.getAllEquivalenceClasses().forEach { mustEqualClass ->
            val repr = mustEqualClass.filter {
                it in bindingLevels
            }.minByOrNull {
                bindingLevels[it]!!
            } ?: return@forEach
            for(other in mustEqualClass) {
                if(other == repr) {
                    continue
                }
                rewrites[other] = repr
            }
        }
        if(rewrites.isEmpty()) {
            return res
        }
        // now, finally, rewrite the AST
        val newAst = res.ast.map { node ->
            when(node) {
                is SimpleSolidityAst.BindArray -> {
                    node
                }
                is SimpleSolidityAst.BindEnum -> node
                is SimpleSolidityAst.BindInputChoice -> {
                    /**
                     * if this identifier is known to always alias something else replace this binding with
                     * said alias.
                     */
                    if(node.boundId in rewrites) {
                        val mustAliasWith = rewrites[node.boundId]!!
                        val alias = node.aliases!!.aliases.find { a ->
                            a.id == mustAliasWith
                        } ?: error("must alias target was not a valid alias?")
                        return@map SimpleSolidityAst.SemanticLine(
                            "${node.ty.pp()} ${node.boundId} = ${alias.apply()};"
                        )
                    }
                    /**
                     * An identifier which was discovered to always alias something else can be removed
                     * from aliasing candidates; we should just try to alias with whatever it's referent is.
                     */
                    node.copy(
                        aliases = node.aliases?.let { al ->
                            al.copy(
                                aliases = al.aliases.filter { a ->
                                    a.id !in rewrites
                                }
                            )
                        }?.takeIf { it.aliases.isNotEmpty() }
                    )
                }
                is SimpleSolidityAst.BindMust -> {
                    // same as above
                    if(node.boundId in rewrites) {
                        val mustAliasWith = rewrites[node.boundId]!!
                        val al = node.discriminator.aliases.find {
                            it.id == mustAliasWith
                        } ?: error("Must alias with not an alias target?")
                        return@map SimpleSolidityAst.SemanticLine(
                            "${node.ty.pp()} ${node.boundId} = ${al.apply()};"
                        )
                    }
                    node.copy(
                        discriminator = node.discriminator.copy(
                            aliases = node.discriminator.aliases.filter {
                                it.id !in rewrites
                            }.takeIf {
                                it.isNotEmpty()
                            } ?: error("Pruned all possible aliases for ${node.boundId}")
                        )
                    )
                }
                is SimpleSolidityAst.FunctionCall -> {
                    node
                }
                is SimpleSolidityAst.Literal -> node
                is SimpleSolidityAst.SemanticLine -> node
            }
        }
        return Reduced(
            ast = newAst,
            importManager = res.importManager,
            fixedPrefix = res.fixedPrefix,
            sceneData = res.sceneData,
            counter = res.counter
        )
    }

    /**
     * Represents a test case a [outputPath] with [sourceCode], that hypothesizes [id1] and [id2] must
     * alias
     */
    data class MustAliasHypothesis(
        @SoliditySourceIdentifier
        val id1: String,
        @SoliditySourceIdentifier
        val id2: String,
        val sourceCode: String,
        val outputPath: Path
    )

    private fun SolidityTypeDescription.isBytesArray() = this is SolidityTypeDescription.PackedBytes || this is SolidityTypeDescription.StringType

    private fun handleBytesArrayComparison(
        e1: String,
        e2: String
    ) : String {
        return "keccak256(bytes($e1)) == keccak256(bytes($e2))"
    }

    private fun String.convertBytes(): String {
        return "abi.encodePacked($this)"
    }

    private fun SolidityTypeDescription.isAddressLike() = this is SolidityTypeDescription.Contract || (this is SolidityTypeDescription.Primitive && this.primitiveName == PrimitiveType.address)

    /**
     * Given to identifiers, try to generate an equality expression based on the types in [typeTable].
     *
     * This has unfortunate overlap with the logic in [SequenceGenerator], but I'm not sure how to unify them.
     * However, it's always safe to just give up when we don't know how to do a comparison, so allow that.
     */
    private fun generateEqualityExpression(
        e1: String,
        e2: String,
        typeTable: Map<String, SolidityTypeDescription>
    ) : String? {
        val t1 = typeTable[e1] ?: error("no type for $e1")
        val t2 = typeTable[e2] ?: error("no type for $e2")
        return when {
            t1 is SolidityTypeDescription.Primitive && t2.isBytesArray() -> {
                if(t1.toDescriptor() !is EVMTypeDescriptor.BytesK) {
                    return null
                }
                return handleBytesArrayComparison(
                    e1.convertBytes(),
                    e2
                )
            }
            t1.isBytesArray() && t2 is SolidityTypeDescription.Primitive -> {
                if(t2.toDescriptor() !is EVMTypeDescriptor.BytesK) {
                    return null
                }
                return handleBytesArrayComparison(
                    e1, e2.convertBytes()
                )
            }
            t1.isBytesArray() && t2.isBytesArray() -> {
                return handleBytesArrayComparison(e1, e2)
            }
            t1.isAddressLike() && t2.isAddressLike() -> {
                return "address($e1) == address($e2)"
            }
            t1 is SolidityTypeDescription.Primitive && t2 is SolidityTypeDescription.Primitive -> {
                val t1Desc = t1.toDescriptor()
                val t2Desc = t2.toDescriptor()
                if(t1Desc == t2Desc) {
                    return "$e1 == $e2"
                } else if(t1.javaClass != t2Desc.javaClass) {
                    return null
                }
                when(t1Desc) {
                    is EVMTypeDescriptor.UIntK -> {
                        check(t2Desc is EVMTypeDescriptor.UIntK)
                        val maxK = maxOf(t1Desc.bitwidth, t2Desc.bitwidth)
                        "uint${maxK}($e1) == uint$maxK($e2)"
                    }
                    is EVMTypeDescriptor.IntK -> {
                        check(t2Desc is EVMTypeDescriptor.IntK)
                        val maxK = maxOf(t1Desc.bitwidth, t2Desc.bitwidth)
                        "int$maxK($e1) == int$maxK($e2)"
                    }
                    is EVMTypeDescriptor.BytesK -> {
                        check(t2Desc is EVMTypeDescriptor.BytesK)
                        val maxK = maxOf(t1Desc.bytewidth, t2Desc.bytewidth)
                        "bytes$maxK($e1) == bytes$maxK($e2)"
                    }
                    else -> null
                }
            }
            else -> null
        }
    }

    @JvmStatic
    fun main(l: Array<String>) {
        object : CliktCommand("Reduce asts using heuristic must aliasing") {
            val resumeData by argument().file(
                mustExist = true,
                canBeFile = true,
                canBeDir = false
            )

            val runData by argument().file(
                mustExist = true,
                canBeFile = true,
                canBeDir = false
            )

            val workingDir by argument().file(
                mustExist = true,
                canBeFile = false,
                canBeDir = true
            )

            val targetDir by argument().file(
                mustExist = false,
                canBeFile = false,
                canBeDir = true
            )

            val output by argument().file(
                mustExist = false,
                canBeFile = true,
                canBeDir = false
            )

            override fun run() {
                output.into<SequenceGenerator.Extendable>().serialize(
                    reduce(
                        resumeData.into(),
                        cd = runData.into(),
                        generationDir = targetDir.toPath(),
                        workingDir = workingDir.toPath()
                    )
                )
            }
        }.main(l)
    }
}
