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

package ksp.hooks

import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSType
import ksp.CertoraAnnotationProcessor
import ksp.dynamicconversion.uncheckedAs

class CVLHookProcessor(val environment: SymbolProcessorEnvironment) : SymbolProcessor, CertoraAnnotationProcessor {

    override val annotationPackage: String
        get() = "annotation"

    private val annotationName = "OpcodeHookType"

    data class Instrumentation(
        val hookDeclaration: String,
        val hookMapper: String,
        val hookPatternChecker: String,
        val numParamsDeclared: Int,
        val hookParser: String,
        val enumEntry: String
    )

    private val accountIdType = "spec.cvlast.CVLType.PureCVLType.Primitive.AccountIdentifier"

    private val evmTypes = "spec.cvlast.typedescriptors.EVMTypeDescriptor"

    private fun parseHookCVLType(
        tyName: String
    ) : String? {
        return when (tyName) {
            "address" -> accountIdType
            "uint256" -> "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK(256)"
            "bytes32" -> "spec.cvlast.CVLType.PureCVLType.Primitive.BytesK(32)"
            "bytes4" -> "spec.cvlast.CVLType.PureCVLType.Primitive.BytesK(4)"
            "uint32" -> "spec.cvlast.CVLType.PureCVLType.Primitive.UIntK(32)"
            else -> {
                environment.logger.error("Could not understand type ${tyName}: we only support address, bytes32, bytes4, and uint256")
                return null
            }
        }
    }

    private fun parseHookEVMType(
        tyName: String
    ) : String? {
        return when(tyName) {
            "address" -> "$evmTypes.address"
            "uint256" -> "$evmTypes.UIntK(256)"
            "bytes32" -> "$evmTypes.BytesK(256)"
            "bytes4" -> "$evmTypes.BytesK(4)"
            "uint32" -> "$evmTypes.UIntK(32)"
            else -> {
                environment.logger.error("Could not understand evm type $tyName: we only support address, bytes32, bytes4, and uint256, uint32")
                return null
            }
        }
    }

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val files = mutableSetOf<KSFile>()
        val instr = resolver.getSymbolsWithAnnotation("$annotationPackage.$annotationName").mapNotNull outer@{ enumEntry ->
            val hookableOpcodeEntry = enumEntry as KSClassDeclaration
            val opcodeName = hookableOpcodeEntry.simpleName.asString()
            enumEntry.containingFile?.let(files::add)
                ?: environment.logger.warn("Could not find Kotlin file containing ${enumEntry.qualifiedName}", enumEntry)

            // Casing XXX as Xxx.
            val declarationName = opcodeName.substring(0, 1) + opcodeName.substring(1).lowercase()

            val opcodeAnnotation = enumEntry.annotations.find {
                it.isAnnotation(annotationName)
            }!!
            val hasOutput = opcodeAnnotation.arguments.find {
                it.name?.asString() == "withOutput"
            }!!.value as Boolean
            val parameters = opcodeAnnotation.arguments.find {
                it.name?.asString() == "params"
            }!!.value.uncheckedAs<List<String>>()

            val valueType = opcodeAnnotation.arguments.find {
                it.name?.asString() == "valueType"
            }!!.value as String

            val dependentOnStorageSplitting = opcodeAnnotation.arguments.find {
                it.name?.asString() == OpcodeHooksProcessor.onlyNoStorageSplittingProp
            }!!.value!! as Boolean

            val envParams = opcodeAnnotation.arguments.find {
                it.name?.asString() == "envParams"
            }!!.value!!.uncheckedAs<List<String>>()

            val extraInterfaces = opcodeAnnotation.arguments.find {
                it.name?.asString() == "extraInterfaces"
            }!!.value!!.uncheckedAs<List<KSType>>()


            val opcodeParamsAndTypes = parameters.mapNotNull {
                val parsed = it.split("\\s+".toRegex())
                if(parsed.size != 2) {
                    environment.logger.error("Could not parse parameter declaration $it", enumEntry)
                    return@mapNotNull null
                }
                val typeName = parseHookCVLType(parsed[0]) ?: return@mapNotNull null
                parsed[1] to typeName
            }

            val envParamsAndTypes = envParams.mapNotNull {
                val parsed = it.split("\\s+".toRegex())
                if(parsed.size != 2) {
                    environment.logger.error("Could not parse parameter declaration $it", enumEntry)
                    return@mapNotNull null
                }
                val typeName = parseHookEVMType(parsed[0]) ?: return@mapNotNull null
                parsed[1] to typeName
            }

            val extendsList = mutableListOf<String>(
                "GeneratedOpcodeHook"
            )

            if(envParamsAndTypes.isNotEmpty()) {
                extendsList.add("OpcodeHookWithEnv")
            }

            val allHookParams = if(hasOutput) {
                extendsList.add(
                    "spec.cvlast.PatternWithValue"
                )
                listOf(
                    "value" to (parseHookCVLType(valueType) ?: return@outer null)
                ) + opcodeParamsAndTypes
            } else {
                opcodeParamsAndTypes
            }

            if(extraInterfaces.isNotEmpty()) {
                extraInterfaces.mapTo(extendsList) {
                    it.declaration.qualifiedName?.asString()!!
                }
            }

            val paramList = allHookParams.map {
                val prefix = if((it.first == "value" && hasOutput)) {
                    "override "
                } else {
                    ""
                }
                prefix + "val ${it.first}: VMParam.Named"
            }

            val paramProperty = if (opcodeParamsAndTypes.isEmpty()) {
                "emptyList()"
            } else {
                """listOf(${
                    opcodeParamsAndTypes.joinToString(", ") {
                        it.first
                    }
                })"""
            }

            var prettyPrint = "\$name"
            if (opcodeParamsAndTypes.isNotEmpty()) {
                prettyPrint += opcodeParamsAndTypes.joinToString(",", prefix = "(", postfix = ")") {
                    "$" + it.first
                }
            }
            if(hasOutput) {
                prettyPrint += " = \$value"
            }

            val envParamDecl = if(envParamsAndTypes.isEmpty()) {
                ""
            } else {
                val envFields = envParamsAndTypes.joinToString("\n") { (fieldName, ty) ->
                    "val $fieldName : VMParam.Named get() = VMParam.Named(\"$fieldName\", $ty, utils.Range.Empty(\"environment variable $fieldName\"))"
                }
                val fieldGenerator = "override fun environmentParams() = listOf(${envParamsAndTypes.joinToString(", ") { it.first } })"
                "$envFields \n $fieldGenerator"
            }

            // first things first, the hook declaration itself
            val hookDeclaration = """
                @kotlinx.serialization.Serializable
                data class $declarationName(${paramList.joinToString(", ")}) : CVLHookPattern.Opcode(), ${extendsList.joinToString(", ")} {
                   override val name: String = "$opcodeName"
                   override val params: List<VMParam.Named> = $paramProperty
                   override fun toString(): String = "$prettyPrint"
                   $envParamDecl
                }
            """.trimIndent()

            val fqn = "spec.cvlast.$declarationName" // fqn is short for fully qualified name

            val hookMapper = run {

                val remappings = allHookParams.map {
                    "val ${it.first} = namedVMParam(pattern.${it.first})"
                }
                val rebuild = """
                pattern.copy(
                   ${allHookParams.joinToString(",\n") {
                    "${it.first} = ${it.first}.force()"
                }}
                ).lift()
            """.trimIndent()

                """
                    is $fqn -> {
                        ${remappings.joinToString("\n")}
                        bindMany(${allHookParams.joinToString(", ") {
                        it.first
                    }}) { -> $rebuild }
                    }
                """.trimIndent()
            }

            val hookTypeChecker = run {
                val fieldChecks = mutableListOf<String>()
                allHookParams.mapTo(fieldChecks) {(fld, type) ->
                    """
                        checkHookParam(pattern.$fld.type, $type, "$fld", pattern)
                    """.trimIndent()
                }
                // TODO CERT-2787: we currently do not pass prover_args to typechecker, so this cannot be checked in typechecker
                // in addition, if there are no packed variables, no reason to disallow splitting
                if (dependentOnStorageSplitting) {
                    fieldChecks.add("""if (Config.EnableStorageSplitting.get() && !Config.IsTypeChecking.get()) {
                            CVLError.General(range, "Must disable storage splitting to handle hook $opcodeName").asError()
                        } else {
                            ok
                        }"""
                    )
                }
                """
                    is $fqn -> {
                        bindMany(
                            ${fieldChecks.joinToString(",\n")}
                        ) { pattern.lift() }
                    }
                """.trimIndent()
            }
            val qualifiedEnumName = """com.certora.certoraprover.cvl.HookType.$opcodeName"""
            val hookParser = run {
                val argumentParsers = mutableListOf<Pair<String, String>>()
                val prefix = mutableListOf<String>()
                if(hasOutput) {
                    prefix.add("""
                        if(valueParam == null) {
                           return CVLError.General(
                              message = "Opcode $opcodeName produces a value, but no output was declared",
                              range = range
                           ).asError()
                        }
                    """.trimIndent())
                }
                prefix.add("""
                    if(params.size != ${opcodeParamsAndTypes.size}) {
                       return CVLError.General(
                          message = "Opcode $opcodeName expects ${opcodeParamsAndTypes.size} parameters, got " + params.size,
                          range = range
                       ).asError()
                    }
                """.trimIndent())

                if(hasOutput) {
                    argumentParsers.add("value" to """
                       valueParam.kotlinize(resolver, scope)
                    """.trimIndent()
                    )
                }

                opcodeParamsAndTypes.forEachIndexed { idx, (paramName, _) ->
                    argumentParsers.add(
                        paramName to """
                            params[$idx].kotlinize(resolver, scope)
                        """.trimIndent()
                    )
                }

                """
                    $qualifiedEnumName -> {
                       ${prefix.joinToString("\n")}

                       ${argumentParsers.joinToString("\n") {(nm, def) ->
                            "val $nm = $def"
                        }}
                        bindMany(${argumentParsers.joinToString(", ") { it.first }}) {
                            $fqn(
                                ${argumentParsers.joinToString(",\n") {(nm, _) ->
                                    "$nm = $nm.force()"
                                }}
                            ).lift()
                        }
                    }
                """.trimIndent()
            }
            Instrumentation(
                hookDeclaration = hookDeclaration,
                hookMapper = hookMapper,
                hookPatternChecker = hookTypeChecker,
                numParamsDeclared = parameters.size,
                hookParser = hookParser,
                enumEntry = qualifiedEnumName
            )
        }.toList()
        if(instr.isEmpty()) {
            return listOf()
        }
        val deps = Dependencies(true, *files.toTypedArray())
        environment.codeGenerator.createNewFile(deps, "spec.cvlast", "GeneratedHookHelpers", "kt").bufferedWriter().use { writer ->
            writer.write("""
                package spec.cvlast

                import config.Config
                import datastructures.stdcollections.*
                import spec.cvlast.typedescriptors.*
                import spec.cvlast.typechecker.CVLError
                import utils.CollectingResult.Companion.bindMany
                import utils.CollectingResult.Companion.lift
                import utils.CollectingResult
                import utils.VoidResult
                import utils.CollectingResult.Companion.asError
                import utils.CollectingResult.Companion.ok
                import utils.Range

                object GeneratedHookHelpers {
            """.trimIndent())

            writer.write("""
                fun typeCheckPattern(
                    range: Range,
                    pattern: GeneratedOpcodeHook,
                    checkHookParam: (VMTypeDescriptor, CVLType.PureCVLType, String, CVLHookPattern.Opcode) -> VoidResult<CVLError>
                ) : CollectingResult<CVLHookPattern.Opcode, CVLError> {
                   return when(pattern) {
            """.trimIndent())

            instr.forEach {
                writer.newLine()
                writer.write(it.hookPatternChecker)
            }

            writer.write("""
                } // end when
            } // end function
            """.trimIndent())

            writer.newLine()
            writer.write("""
                fun <E> mapPattern(pattern: GeneratedOpcodeHook, namedVMParam: (VMParam.Named) -> CollectingResult<VMParam.Named, E>): CollectingResult<CVLHookPattern, E> {
                    return when(pattern) {
            """.trimIndent())
            instr.forEach {
                writer.newLine()
                writer.write(it.hookMapper)
            }
            writer.write("""
                } // end when
             } // end function
             } // end object
            """.trimIndent())
        }

        environment.codeGenerator.createNewFile(deps, "spec.cvlast", "GeneratedOpcodeHooks", "kt").bufferedWriter().use { writer ->
            writer.write("""
                package spec.cvlast

                import datastructures.stdcollections.*
            """.trimIndent())

            instr.forEach {
                writer.newLine()
                writer.write(it.hookDeclaration)
            }
        }


        environment.codeGenerator.createNewFile(deps, "spec.cvlast.parser", "GeneratedOpcodeParsers", "kt").bufferedWriter().use { writer ->
            writer.write("""
                @file:Suppress("ReplaceSizeCheckWithIsNotEmpty", "ConvertToStringTemplate") // we generate const checks that might be against 0
                   package spec.cvlast.parser

                import datastructures.stdcollections.*
                import spec.cvlast.*
                import utils.CollectingResult.Companion.bindMany
                import utils.CollectingResult.Companion.lift
                import utils.CollectingResult.Companion.asError
                import spec.cvlast.typechecker.CVLError
                import utils.CollectingResult
                import utils.Range
                import spec.TypeResolver
                import spec.cvlast.CVLScope

                object GeneratedOpcodeParsers {
                   fun supportsAutoParse(
                      hookType: com.certora.certoraprover.cvl.HookType
                   ) : Boolean {
                      return ${instr.joinToString(" ||\n") {
                             "hookType == ${it.enumEntry}"
                        }}
                   }

                   fun handleParse(
                      resolver: TypeResolver,
                      scope: CVLScope,
                      hookType: com.certora.certoraprover.cvl.HookType,
                      valueParam: com.certora.certoraprover.cvl.NamedVMParam?,
                      params: List<com.certora.certoraprover.cvl.NamedVMParam>,
                      range: Range
                   ) : CollectingResult<CVLHookPattern, CVLError> {
                      return when(hookType) {
            """.trimIndent())
            instr.forEach {
                writer.newLine()
                writer.write(it.hookParser)
            }
            writer.newLine()
            writer.write("""
                    else -> throw UnsupportedOperationException("cannot auto parse " + hookType)
                }
               }
            }
            """.trimIndent())

        }
        return listOf()
    }
}
