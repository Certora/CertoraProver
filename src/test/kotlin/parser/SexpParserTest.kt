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

package parser
import org.junit.jupiter.api.Test

class SexpParserTest {
    @Test
    fun test01() {
        val s = parseSexp("(* 3 test)", 0).first
        assert(s.toString() == "(* 3 test)")
    }

    @Test
    fun test02() {
        val s = parseSexp("(+ (- x y) 3)", 0).first
        assert(s.toString() == "(+ (- x y) 3)")
    }

    @Test
    fun test03() {
        val s = parseSexp("(+ (- x y) (* 3 test))", 0).first
        assert(s.toString() == "(+ (- x y) (* 3 test))")
    }

    @Test
    fun test04() {
        val s = parseSexp("(* (~ (+ 4 6)) (~ (~ x)))", 0).first
        assert(s.toString() == "(* (~ (+ 4 6)) (~ (~ x)))")
    }

    @Test
    fun test05() {
        val s = parseSexp("foobar", 0).first
        assert(s.toString() == "foobar")
    }
}