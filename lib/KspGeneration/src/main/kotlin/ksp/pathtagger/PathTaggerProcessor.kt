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

package ksp.pathtagger

import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.symbol.*

/**
 * Consumes `TaggedPath` annotations on properties whose name end in `XPath`, and produces and extension
 * property called `XFile` which is a TaggedFile that wraps the type parameter provided to TaggedPath.
 *
 * For example:
 * ```
 * @TaggedPath(SomeType)
 * val myDataPath: Path = ...
 * ```
 *
 * generates:
 *
 * ```
 * val TestCase.myDataFile: TaggedFile<SomeType> get() = this.myDataPath().into()
 * ```
 *
 * currently only supports annotations on the special `TestCase` class, although that could be
 * weakened if need be.
 */
class PathTaggerProcessor(val environment: SymbolProcessorEnvironment) : SymbolProcessor {
    override fun process(resolver: Resolver): List<KSAnnotated> {
        val declarations = mutableListOf<String>()
        val imports = mutableSetOf<String>()
        val sourceFiles = mutableSetOf<KSFile>()
        for(t in resolver.getSymbolsWithAnnotation(
            "prefixgen.data.TaggedPath"
        ).mapNotNull {
            it as? KSPropertyDeclaration
        }) {
            val propName = t.simpleName.asString()
            if(!propName.endsWith("Path")) {
                environment.logger.error(
                    "Annotated field does not end with `Path` as expected ${t.simpleName}"
                )
                continue
            }
            if(t.parentDeclaration?.qualifiedName?.asString() != "prefixgen.TestCase") {
                environment.logger.error("The TaggedPath annotation is intended only for use on TestCase for now")
            }
            val ty : KSType = t.type.resolve()
            if(ty.declaration !is KSClassDeclaration || ty.declaration.qualifiedName?.asString() != "java.nio.file.Path") {
                check(ty.declaration is KSClassDeclaration)
                environment.logger.error(
                    "Field is not a `Path` type: ${ty.declaration.simpleName.asString()}"
                )
                continue
            }
            val annot = t.annotations.single { a ->
                a.shortName.asString() == "TaggedPath"
            }

            val newName = propName.replace("Path", "File")
            val wrappedType = ((annot.arguments.single { arg ->
                arg.name?.asString() == "ty"
            }.value as KSType).declaration as KSClassDeclaration).qualifiedName?.asString()!!
            declarations.add(
                """
                    val TestCase.$newName : TaggedFile<$wrappedType> get() = $propName.toFile().into()
                """.trimIndent()
            )
            imports.add(wrappedType)
            sourceFiles.add(t.containingFile!!)
        }
        if(declarations.isEmpty()) {
            return emptyList()
        }
        environment.codeGenerator.createNewFile(Dependencies(aggregating = true, sources = sourceFiles.toTypedArray()), "prefixgen.data", "TestCaseFileExt", "kt").use { out ->
            val outBuffer = out.bufferedWriter()
            outBuffer.write("""
                package prefixgen.data

                import prefixgen.TestCase
                import prefixgen.data.TaggedFile.Companion.into
                ${imports.joinToString("\n") { imp ->
                    "import $imp"
                }}

                object TestCaseFileExt {
                    ${declarations.joinToString("\n")}
                }
            """.trimIndent())
            outBuffer.flush()
        }
        return listOf()
    }
}
