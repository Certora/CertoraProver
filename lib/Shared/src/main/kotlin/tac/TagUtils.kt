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

package tac

import utils.*

// For primitives we allow "lifting" to types with fewer assumptions (for example Bit256 is constrained
// to 256 bits, but Ints are not and Bools only require 0/non-0
fun Tag.isSubtypeOf(other: Tag): Boolean = when (this) {
    is Tag.Bits -> other == this || other == Tag.Int
    is Tag.UserDefined.Struct -> other is Tag.UserDefined.Struct && this.fields.size == other.fields.size &&
        this.fields.all { field ->
            val otherField = other.getField(field.name)
            otherField != null && field.type.isSubtypeOf(otherField.type)
        }
    else -> this == other
}

/** Check that all tags are identical and return that tag. */
fun Iterable<Tag>.commonTag() = sameValueOrNull()
    ?: error("expected identical tags, but got $this")

/**
 * Check that [tags] have a direct common super tag and return that one.
 * Note that this function has some restriction due to the ad-hoc'ish nature of [isSubtypeOf] and the very restricted
 * ways we make use of it: we only find a common super tag if [tags ]are the same or if one is the direct sub
 * tag of the other. As of now, this is sufficient, though.
 */
fun commonSuperTag(tags : Iterable<Tag>) =
    tags.reduce { lhs, rhs ->
        when {
            lhs == rhs -> lhs
            lhs.isSubtypeOf(rhs) -> rhs
            rhs.isSubtypeOf(lhs) -> lhs
            else -> error("can not compute common super tag of $lhs and $rhs")
        }
    }

fun commonSuperTag(vararg tags : Tag) = commonSuperTag(tags.toList())




/**
 * Check that all tags are [Tag.Int] or a single [Tag.Bits] and return the largest of
 * those: [Tag.Int], or the [Tag.Bits] if only that one occurs.
 * This method is pretty ad-hoc'ish and implements a particular special case of a more
 * general "least common supertype" method that we frequently need to compute function
 * signatures from the operands tags.
 */
fun Iterable<Tag>.commonBitsOrInt(): Tag? {
    var hasInt = false
    var bitTag: Tag.Bits? = null
    forEach {
        when {
            it == Tag.Int -> hasInt = true
            it is Tag.Bits -> when {
                bitTag == null -> bitTag = it
                bitTag == it -> Unit
                else -> return null
            }
            else -> return null
        }
    }
    return ite(hasInt, Tag.Int, bitTag)
}
