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

package com.certora.detekt.utils

import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.incremental.components.NoLookupLocation
import org.jetbrains.kotlin.name.*
import org.jetbrains.kotlin.resolve.descriptorUtil.*

fun ClassDescriptor.implementsInterface(name: FqName) = getAllSuperClassifiers().any { it.fqNameSafe == name }

val ClassDescriptor.isConcreteClass get() = when (kind) {
    ClassKind.CLASS, ClassKind.OBJECT, ClassKind.ENUM_CLASS -> {
        when (modality) {
            Modality.OPEN, Modality.FINAL -> true
            Modality.SEALED, Modality.ABSTRACT -> false
        }
    }
    else -> false
}

val ClassDescriptor.isEnum get() = kind == ClassKind.ENUM_CLASS
val ClassDescriptor.isObject get() = kind == ClassKind.OBJECT
val ClassDescriptor.isInterface get() = kind == ClassKind.INTERFACE

tailrec fun ClassDescriptor.findDeclaredFunction(
    name: String,
    checkSuperClasses: Boolean,
    filter: (FunctionDescriptor) -> Boolean
): FunctionDescriptor? {
    val func =
        unsubstitutedMemberScope.getContributedFunctions(
            Name.identifier(name),
            NoLookupLocation.WHEN_RESOLVE_DECLARATION
        ).firstOrNull {
            it.containingDeclaration == this && it.kind == CallableMemberDescriptor.Kind.DECLARATION && filter(it)
        }

    return when {
        func != null -> func
        checkSuperClasses -> getSuperClassOrAny().findDeclaredFunction(name, checkSuperClasses, filter)
        else -> null
    }
}
