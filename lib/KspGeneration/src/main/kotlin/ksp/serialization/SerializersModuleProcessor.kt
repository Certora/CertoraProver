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

package ksp.serialization

import com.google.devtools.ksp.*
import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.*
import com.google.devtools.ksp.visitor.*
import ksp.*

class SerializersModuleProcessor(private val environment: SymbolProcessorEnvironment) : SymbolProcessor {
    override fun process(resolver: Resolver): List<KSAnnotated> {

        val hasKSerializableType = resolver.getClassDeclarationByName("utils.HasKSerializable")?.asType(listOf())
            ?: return emptyList()

        val types = mutableListOf<KSClassDeclaration>()

        resolver.getNewFiles().forEach { file ->
            file.accept(ClassDeclarationVisitor) {
                if (it.isPublic() && it.qualifiedName != null && it.isOpen()) {
                    if (hasKSerializableType.isAssignableFrom(it.asStarProjectedType())) {
                        types += it
                    }
                }
            }
        }

        if (types.isNotEmpty()) {
            val propName = environment.options["serializersModuleName"]?.let { resolver.getKSNameFromString(it) }
            if (propName == null) {
                environment.logger.warn(
                    "Contains HasKSerializable-derived abstract types. Set KSP option `serializersModuleName` to generate a polymorphic serializers module.",
                    types.first().containingFile
                )
                return emptyList()
            }

            environment.codeGenerator.createNewFile(
                Dependencies(true, *types.mapNotNull { it.containingFile }.toTypedArray<KSFile>()),
                propName.getQualifier(),
                propName.getShortName()
            ).bufferedWriter().use { writer ->
                writer.write(
"""
// Generated by [${this::class.qualifiedName}]

package ${propName.getQualifier()}

import utils.reflectivePolymorphic

val ${propName.getShortName()} = kotlinx.serialization.modules.SerializersModule {
${types.joinToString(separator = "\n") { "reflectivePolymorphic<${it.fullyQualifiedStarProjectedName()}>()" }}
}
"""
                )
            }
        }

        return emptyList()
    }

    fun KSAnnotated.hasSerializableAnnotation() =
        hasAnnotation("Serializable", "kotlinx.serialization.Serializable") ||
        hasAnnotation("KSerializable", "utils.KSerializable")

    object ClassDeclarationVisitor : KSDefaultVisitor<(KSClassDeclaration) -> Unit, Unit>() {
        override fun visitClassDeclaration(classDeclaration: KSClassDeclaration, data: (KSClassDeclaration) -> Unit) {
            data(classDeclaration)
            super.visitClassDeclaration(classDeclaration, data)
        }
        override fun visitDeclarationContainer(declarationContainer: KSDeclarationContainer, data: (KSClassDeclaration) -> Unit) {
            declarationContainer.declarations.forEach { it.accept(this, data) }
            super.visitDeclarationContainer(declarationContainer, data)
        }
        override fun defaultHandler(node: KSNode, data: (KSClassDeclaration) -> Unit) = Unit
    }
}
