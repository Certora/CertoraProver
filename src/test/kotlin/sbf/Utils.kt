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

package sbf

import org.junit.jupiter.api.*

inline fun <reified E : Throwable> expectException(block: () -> Unit) {
    var thrown = false
    try {
        block()
    } catch (e: Throwable) {
        if (e is E) {
            println("Test failed as expected because $e")
            thrown = true
        } else {
            throw e // unexpected exception
        }
    }
    Assertions.assertTrue(thrown, "Expected exception ${E::class.simpleName} was not thrown")
}
