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

package verifier.equivalence.data

import vc.data.CoreTACProgram

/**
 * A program that we know how to "call", in that we can symbolically bind the arguments/inputs
 * used by [program]. The information necessary to perform this binding is represented by type [I],
 * and is available in the [callingInfo] field. [I] should (but is not required to) record information about the
 * identity of [program] for the purposes of debugging/maintenance. Such a program in
 * the context of equivalence checking is always one of the programs being checked for equivalence or a derivative thereof,
 * which equivalence program is recorded by [M].
 */
interface CallableProgram<M: MethodMarker, out I> {
    val program: CoreTACProgram
    val callingInfo: I

    /**
     * "pretty print" the name/identity of this program.
     */
    fun pp(): String

    companion object {
        fun <M: MethodMarker, I> CallableProgram<M, I>.map(f: (CoreTACProgram) -> CoreTACProgram) : CallableProgram<M, I> {
            val newProg = f(this.program)
            val src = this
            /**
             * Q: Why not use delegation for this?
             * A: Friends, I tried that, and the behavior of the resulting program was wrong in some cursed way I did not
             * feel like debugging.
             */
            return object : CallableProgram<M, I> {
                override val program: CoreTACProgram
                    get() = newProg
                override val callingInfo: I
                    get() = src.callingInfo

                override fun pp(): String {
                    return src.pp()
                }
            }
        }
    }
}
