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

package vc.data

import com.certora.collect.*
import utils.*
import vc.data.HashFamily.Keccack
import java.io.Serializable

/** Note that for now we assume that there is exactly one [HashFamily] that requires the "large gaps" modeling.
 * And that is [Keccack]. If this changes, the pipeline ([SkeyDetection] etc.) will need a slight rework. */
@KSerializable
@Treapable
sealed class HashFamily: Serializable, HasKSerializable {
    open val resultSizeInBytes: Int
        get() = 32

    open val requiresLargeGaps: Boolean
        get() = false

    abstract val isContractCreation: Boolean

    @KSerializable
    object Keccack : HashFamily() {
        override val requiresLargeGaps: Boolean
            get() = true

        override fun hashCode(): Int = hashObject(this)
        override fun toString(): String = "keccak"
        private fun readResolve(): Any = Keccack
        override val isContractCreation: Boolean get() = false
    }

    @KSerializable
    object Sha2 : HashFamily() {
        override val isContractCreation: Boolean
            get() = false
        override fun hashCode(): Int = hashObject(this)
        override fun toString() : String = "sha256"
        private fun readResolve(): Any = Sha2
    }

    @KSerializable
    @Suppress("Unused") // there is a ticket to put this to use ...
    object Sha3 : HashFamily() {
        override fun hashCode(): Int = hashObject(this)
        override fun toString(): String = "sha3"
        private fun readResolve(): Any = Sha3
        override val isContractCreation: Boolean get() = false
    }

    @KSerializable
    object SorobanObjectDigest : HashFamily() {
        override fun hashCode(): Int = hashObject(this)
        override fun toString(): String = "sorobanObjectDigest"
        private fun readResolve(): Any = SorobanObjectDigest
        override val isContractCreation: Boolean get() = false
    }

    @KSerializable
    data class Create(override val resultSizeInBytes: Int) : HashFamily() {
        override fun toString(): String = "create_$resultSizeInBytes"
        override val isContractCreation: Boolean get() = true
    }

    @KSerializable
    object MoveVectorDigest : HashFamily() {
        override fun hashCode(): Int = hashObject(this)
        override fun toString(): String = "mvVecDigest"
        private fun readResolve(): Any = MoveVectorDigest
        override val isContractCreation: Boolean get() = false
    }

    @KSerializable
    object MoveVectorElemHash : HashFamily() {
        override fun hashCode(): Int = hashObject(this)
        override fun toString(): String = "mvVecElem"
        private fun readResolve(): Any = MoveVectorElemHash
        override val isContractCreation: Boolean get() = false
    }

    @KSerializable
    abstract class CvlmHashFamily : HashFamily() {
        override val requiresLargeGaps get() = false
        override val isContractCreation get() = false
    }
}
