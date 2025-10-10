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

import bridge.types.PrimitiveType
import bridge.types.SolidityTypeDescription
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.int
import datastructures.stdcollections.*
import prefixgen.CodeGen.pp
import prefixgen.FileUtils.deserialize
import prefixgen.FileUtils.serialize
import prefixgen.data.*
import prefixgen.data.TaggedFile.Companion.into
import prefixgen.fuzzer.RunData
import prefixgen.selection.SelectorGenerator
import spec.cvlast.typedescriptors.EVMTypeDescriptor
import utils.*
import utils.ModZm.Companion.from2s
import java.math.BigInteger
import kotlin.io.path.createDirectory
import kotlin.io.path.exists

/**
 * Resumes a sequence with fixed choice of arguments taken from a fuzzer CEX.
 */
object Resumer {

    /**
     * Computes the new fixed prefix to add to the current test case which fixes the
     * choices of arguments to the fuzz test.
     *
     * For example, suppose our Fuzz test was (conceptually):
     * ```
     * contract FuzzTest {
     *     uint inputVar4;
     *     function bind() {
     *        inputVar4 = 5;
     *        myContract.foo(inputVar4);
     *     }
     *
     *     function testFuzz_interestingInputs(uint inputVar5) external {
     *         vm.assumeNoRevert();
     *         myContract.bar(inputVar5);
     *         vm.assertTrue(false);
     *     }
     * }
     * ```
     *
     * And foundry found that the choice of `inputVar5 == 16` led the `bar` call to not revert.
     * This function then updates the prefix in `bind()` to be:
     *
     * ```
     *   function bind() {
     *      inputVar4 = 5;
     *      myContract.foo(inputVar4);
     *      inputVar5 = 16;
     *      myContract.bar(inputVar5);
     *   }
     * ```
     *
     * And then begins generating fresh fuzz tests from the state reached via these
     * concrete args. This occasional "compaction" of the symbolic sequences to "concrete" prefixes is crucial
     * for the scalability of this exploration (I mean, probably).
     *
     * [targetContractType] the target contract type to exercise in [SequenceGenerator].
     * [resumeFile] the file containing the [prefixgen.SequenceGenerator.ResumeData].
     * [calldata] the calldata to interpret using the struct/param information in the referenced [prefixgen.SequenceGenerator.ResumeData]
     * [mutationDataPath] record which enums and alias discriminators were chosen in this counterexample represented by [calldata].
     * this [prefixgen.Mutator.RegenerationData] can be consumed by the [Mutator] to find different concrete arguments (in particular,
     * with different aliasing choices) for the same sequence.
     * [depth] the length of the new sequence to generate
     * [selector] the selector strategy to use for the new sequence.
     */
    fun resume(
        targetContractType: String,
        resumeFile: TaggedFile<SequenceGenerator.ResumeData>,
        calldata: String,
        mutationDataPath: TaggedFile<Mutator.RegenerationData>,
        depth: Int,
        selector: SelectorGenerator
    ): Sequence<SequenceGenerator.ResumableArtifact> {
        val resumeData = resumeFile.deserialize()

        val bindings = RunData.parseBindings(calldata, resumeData.structIds, resumeData.topLevelParams)

        /**
         * the discriminators used for potentially aliased variables.
         */
        val disc = resumeData.ast.associateNotNull { a ->
            when(a) {
                is SimpleSolidityAst.BindArray,
                is SimpleSolidityAst.BindEnum,
                is SimpleSolidityAst.FunctionCall,
                is SimpleSolidityAst.SemanticLine,
                is SimpleSolidityAst.Literal -> null
                is SimpleSolidityAst.BindMust -> a.boundId to a.discriminator
                is SimpleSolidityAst.BindInputChoice -> a.boundId `to?` a.aliases
            }
        }

        /**
         * Resolve the discriminators using the concrete bindings.
         * Recall that just because the discriminator for some variable == k > 0
         * that does not mean we use alias k - 1, as that referent may itself be an alias.
         * The "normalization" is hanadled by [Discriminator.getChosenAliasIndex].
         */
        val resolvedDisc = disc.values.associateWith {
            it.getChosenAliasIndex(valuation = bindings, disc)
        }

        fun Discriminator.resolve() = resolvedDisc[this]!!

        /**
         * Find out which alias selections were non-zero, and get the referenced IDs.
         */
        val usedAliases = disc.values.mapNotNullToSet { d ->
            val sel = d.resolve()
            if(sel == 0) {
                return@mapNotNullToSet null
            }
            d.aliases[sel - 1].id
        }

        /**
         * Compute the new storage variables to add to the [FixedPrefix],
         * and the new code to append.
         */
        val newPrefix = mutableListOf<String>()
        val newStorageVars = mutableSetOf<VarDef>()

        /**
         * Get the constant representation of the input with [symbolicName] and type [valueType].
         * There is no distinction between whether the input is an input state field or a top level value,
         * they are intermixed in `bindings` (see [RunData.Companion.parseBindings]).
         */
        fun getConstantVal(
            symbolicName: String,
            valueType: SolidityTypeDescription
        ) : String {
            val concreteValue = bindings[symbolicName]!!
            val init = when(valueType) {
                is SolidityTypeDescription.UserDefined.ValueType,
                is SolidityTypeDescription.StaticArray,
                is SolidityTypeDescription.Mapping,
                is SolidityTypeDescription.Array,
                is SolidityTypeDescription.UserDefined.Struct,
                is SolidityTypeDescription.Function -> throw IllegalStateException("not a valid const type")
                is SolidityTypeDescription.Contract -> {
                    check(concreteValue is SimpleValue.Word)
                    "${valueType.contractName}(address(uint160(" + concreteValue.hexString.substring(12 * 2) + ")))"
                }
                is SolidityTypeDescription.PackedBytes -> {
                    /**
                     * A lot the string/bytes bodies that foundry chooses are not valid utf-8 string, or are just ugly.
                     * Luckily, solidity has a "hex string literal" syntax, so we use that.
                     */
                    check(concreteValue is SimpleValue.HexString)
                    "hex\"" + concreteValue.hexString + "\""
                }
                is SolidityTypeDescription.Primitive -> {
                    val desc = valueType.toDescriptor()
                    check(concreteValue is SimpleValue.Word)
                    when(desc) {
                        is EVMTypeDescriptor.EVMContractTypeDescriptor -> throw IllegalStateException("Expected to find it in contract case above")
                        is EVMTypeDescriptor.BytesK -> {
                            val hexPrefixSize = desc.bytewidth * 2
                            "0x" + concreteValue.hexString.substring(0, hexPrefixSize)
                        }
                        is EVMTypeDescriptor.EVMEnumDescriptor -> throw IllegalStateException("Should be caught in enum overrides")
                        is EVMTypeDescriptor.IntK -> {
                            "int${desc.bitwidth}(${concreteValue.toBigInt().from2s().toString(10)})"
                        }
                        is EVMTypeDescriptor.UIntK -> {
                            "uint${desc.bitwidth}(${concreteValue.toBigInt().toString(10)})"
                        }
                        is EVMTypeDescriptor.UserDefinedValueType -> throw IllegalStateException("not a valid const type")
                        EVMTypeDescriptor.address -> {
                            "address(uint160(0x00" + concreteValue.hexString.substring(12 * 2) + "))"
                        }
                        EVMTypeDescriptor.bool -> {
                            if(concreteValue.toBigInt() == BigInteger.ZERO) {
                                "false"
                            } else {
                                "true"
                            }
                        }
                    }
                }
                is SolidityTypeDescription.StringType -> {
                    check(concreteValue is SimpleValue.HexString)
                    "string(hex\"" + concreteValue.hexString + "\")"
                }
                is SolidityTypeDescription.UserDefined.Enum -> throw IllegalStateException("Should have been caught in enum overrides")
            }
            return init
        }

        /**
         * Recursively pretty print an expression,
         */
        fun concretizeExp(
            s: SymExp
        ) : String {
            return when(s) {
                is SymExp.StaticArrayLiteral -> {
                    s.elems.joinToString(",", prefix = "[", postfix = "]") { elem ->
                        s.elemTy + "(" + concretizeExp(elem) + ")"
                    }
                }
                is SymExp.StructLiteral -> {
                    val fields = s.fields.joinToString(",\n") { fldExp ->
                        concretizeExp(fldExp)
                    }
                    "${s.structName}($fields)"
                }
                is SymExp.Symbol -> {
                    s.rawId
                }
            }
        }

        for(a in resumeData.ast) {
            when(a) {
                is SimpleSolidityAst.BindArray -> {
                    /**
                     * Get the concrete chosen length.
                     */
                    val len = bindings[a.lengthSym]!!.bounded(a.elems.size + 1)
                    /**
                     * No need for the array prototypes, we know the length so we can just "inline" allocate and initalize
                     * the array.
                     */
                    newPrefix.add("""
                        ${a.elemType.pp()}[] memory ${a.rawId} = new ${a.elemType.pp()}[]($len);
                    """.trimIndent())
                    for(i in 0 ..< len) {
                        newPrefix.add("""
                            ${a.rawId}[$i] = ${a.elems[i].rawId};
                        """.trimIndent())
                    }
                }
                is SimpleSolidityAst.BindEnum -> {
                    /**
                     * Bind the symbolic name of the enum based off the chosen ordinal by foundry.
                     */
                    val ordinal = bindings[a.inputDiscriminator]!!.bounded(a.ty.enumMembers.size)
                    newPrefix.add(
                        """
                            ${a.ty.qualifiedName} ${a.rawId} = ${a.ty.qualifiedName}.${a.ty.enumMembers[ordinal]};
                        """.trimIndent()
                    )
                }
                is SimpleSolidityAst.BindInputChoice -> {
                    /**
                     * If this is a fresh input value, then we need to create a storage binding
                     * that our extended fuzz test can alias against.
                     * If, however, this binding is an alias, we can just create a binding
                     * local to the `bind` function.
                     */
                    val isFreshInput = a.aliases == null || a.aliases.resolve() == 0
                    val varDef = if(isFreshInput) {
                        newStorageVars.add(VarDef(a.ty, a.boundId))
                        "${a.boundId} ="
                    } else {
                        "${a.ty.pp()} ${a.boundId} = "
                    }

                    /**
                     * If a fresh value, get the syntax for the value chosen by foundry
                     */
                    val rhs = if(isFreshInput) {
                        getConstantVal(
                            a.inputId,
                            a.ty
                        )
                    } else {
                        check(a.aliases != null)
                        val d = a.aliases.resolve()
                        check(d != 0)
                        /**
                         * otherwise, use the alias syntax. NB that the referent identifier must be in scope;
                         * its either an extant storage variable, a prior "fresh value" which was just
                         * promoted to a new storage variable, OR a variable bound by a previous function call
                         * (in which case that variable has also been promoted to a storage var).
                         */
                        a.aliases.aliases[d - 1].apply()
                    }
                    newPrefix.add("$varDef = $rhs;")
                }
                is SimpleSolidityAst.BindMust -> {
                    /**
                     * A simpler version of the above bind case, no need to pretty print a value representation.
                     */
                    val resolvedId = a.discriminator.resolve()
                    check(resolvedId != 0)
                    "${a.ty.pp()} ${a.boundId} = ${a.discriminator.aliases[resolvedId - 1].apply()};"
                }
                /**
                 * perform the function call, and bind any aliased
                 * return values.
                 */
                is SimpleSolidityAst.FunctionCall -> {
                    val postCallOps = mutableListOf<String>()
                    val lhs = if(a.binds == null) {
                        ""
                    } else if(a.binds.singleOrNull() != null) {
                        val bindDef = a.binds.single()!!
                        if(bindDef.rawId !in usedAliases) {
                            ""
                        } else {
                            newStorageVars.add(bindDef)
                            "${bindDef.rawId} = "
                        }
                    } else {
                        if(a.binds.all {
                                it == null || it.rawId !in usedAliases
                            }) {
                            ""
                        } else {
                            val lhsBinds = mutableListOf<String>()
                            for(vd in a.binds) {
                                if(vd == null || vd.rawId !in usedAliases) {
                                    lhsBinds.add("/* not bound */")
                                } else {
                                    /**
                                     * If the return value here is used as a referent, add it to storage
                                     * as a future possible referent.
                                     */
                                    newStorageVars.add(vd)
                                    val bindName = "${vd.rawId}_Bind"
                                    lhsBinds.add("${vd.ty.pp()} $bindName")
                                    postCallOps.add("${vd.rawId} = $bindName;")
                                }
                            }
                            lhsBinds.joinToString(", ", prefix = "(", postfix = ") = ")
                        }
                    }

                    /**
                     * pretty print the args.
                     */
                    val args = a.args.map {
                        concretizeExp(it)
                    }
                    /**
                     * make the call, prank the selected sender, and call.
                     */
                    newPrefix.addAll(listOf(
                        "vm.prank(address(${a.asSender.rawId}));",
                        "$lhs ${a.contract}.${a.functionName}${args.joinToString(", ", prefix = "(", postfix = ")")};"
                    ))
                    /**
                     * bind into storage.
                     */
                    newPrefix.addAll(postCallOps)
                }
                is SimpleSolidityAst.Literal -> { }
                is SimpleSolidityAst.SemanticLine -> newPrefix.add(a.line)
            }
        }

        /**
         * the generator for the fuzz test extension
         */
        val generator = SequenceGenerator(
            resumeData.sceneData, targetContractType, depth, selector
        )

        /**
         * Record the choice of booleans and aliases for later reduction or mutation.
         */
        val mutationData = Mutator.RegenerationData(
            aliasSelectors = resolvedDisc.mapKeys { it.key.discriminator },
            selectedBools = resumeData.structIds.filter { (ty, _) ->
                ty is SolidityTypeDescription.Primitive && ty.primitiveName == PrimitiveType.bool
            }.associate {
                it.rawId to ((bindings[it.rawId]!! as SimpleValue.Word).toBigInt() != BigInteger.ZERO)
            },
        )
        mutationDataPath.serialize(mutationData)

        val newSequence = resumeData.fixedPrefix.currCalls + resumeData.ast.mapNotNull {
            (it as? SimpleSolidityAst.FunctionCall)?.sighash
        }

        val storageVars = resumeData.fixedPrefix.storageVars + newStorageVars
        return generator.resume(
            idCounter = resumeData.counter,
            // Update the fixed prefix with new bindings, new code, and new function calls
            fixedPrefix = FixedPrefix(
                storageVars = storageVars,
                setup = resumeData.fixedPrefix.setup + newPrefix,
                currCalls = newSequence
            ),
            imports = resumeData.importManager
        )
    }

    @JvmStatic
    fun main(args: Array<String>) {
        object : PrefixGenCommand("Resumer", "Resume a concrete sequence generated by the fuzzer") {
            val calldata by argument(
                help = "The raw calldata string of the CEX found by the fuzzer (without a leading 0x)"
            )

            val dataFile by argument(
                help = "The resume data json file. Usually has extension `.resume.json`"
            ).file(
                mustExist = true,
                canBeFile = true,
                canBeDir = false
            )

            val outputDirPath by argument(
                help = "The output directory into which to generate the extensions"
            ).file(
                mustExist = false,
                canBeFile = false,
                canBeDir = true
            )

            val mutationDataPath by argument(
                "mutationDataPath",
                help = "The file to record the key concrete values from the given CEX, for use in the Mutator command."
            ).file(
                canBeFile = true,
                mustExist = false,
                canBeDir = false
            )

            val depth by option(
                help = "The length of the extended sequences"
            ).int().default(3)

            override fun run() {
                val paths = outputDirPath.toPath()
                if(!paths.exists()) {
                    paths.createDirectory()
                }
                for((ind, r) in resume(
                    targetContractType = targetContract,
                    resumeFile = dataFile.into(),
                    calldata = calldata,
                    mutationDataPath = mutationDataPath.into(),
                    depth = depth,
                    selector = samplingStrategy
                ).take(samples).withIndex()) {
                    r.saveToPath(paths.resolve("harness$ind").toAbsolutePath().toString())
                }
            }
        }.main(args)
    }
}
