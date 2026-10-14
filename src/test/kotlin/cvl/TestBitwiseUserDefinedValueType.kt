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

package cvl

import infra.CVLFlow
import org.junit.jupiter.api.Test

/**
 * A user-defined value type is an alias for its base primitive in CVL, so a bitwise operator may be
 * applied to a value of such a type (`flags() & 2`), and both the operands and the result of that
 * operator carry the user-defined type. Compilation of every bitwise operator dispatches on the base
 * primitive of the operand and result types; these rules exercise that dispatch for `&`, `|`,
 * `xor`, `~` and the three shifts, over unsigned, signed and bytesK bases.
 */
class TestBitwiseUserDefinedValueType {

    private val contract = """
        type Flags is uint8;
        type Signed is int8;
        type Word is bytes32;

        contract test {
            Flags internal _flags;
            Signed internal _signed;
            Word internal _word;

            function flags() external view returns (Flags) { return _flags; }
            function signed() external view returns (Signed) { return _signed; }
            function word() external view returns (Word) { return _word; }
        }
    """.trimIndent()

    private val methods = """
        methods {
            function flags() external returns (test.Flags) envfree;
            function signed() external returns (test.Signed) envfree;
            function word() external returns (test.Word) envfree;
        }
    """.trimIndent()

    /** Typechecks [cvlText] against [contract] and compiles the result to TAC. */
    private fun compile(cvlText: String) {
        val flow = CVLFlow()
        val res = flow.getProverQueryWithScene(
            contract = contract,
            solc = "solc8.13",
            withOptimize = false,
            cvlText = "$methods\n\n$cvlText",
        )
        res.errorOrNull()?.let { errs -> error(errs.joinToString("\n") { e -> "${e.location}: ${e.message}" }) }
        flow.transformResultsToTACs(res)
    }

    /** The reported shape: a flags word held in a user-defined value type, masked against a literal. */
    @Test
    fun bwAndWithLiteral() {
        compile(
            """
            rule maskFlags {
                require flags() & 2 != 0;
                assert true;
            }
            """.trimIndent()
        )
    }

    @Test
    fun bwAndOrXorOfTwoUserDefinedValues() {
        compile(
            """
            rule combineFlags {
                assert ((flags() & flags()) | (flags() xor flags())) == flags();
            }
            """.trimIndent()
        )
    }

    @Test
    fun bwNot() {
        compile(
            """
            rule negateFlags {
                assert ~flags() >= 0;
            }
            """.trimIndent()
        )
    }

    @Test
    fun shiftsOnUnsignedBase() {
        compile(
            """
            rule shiftFlags {
                assert (flags() << 1) >= 0;
                assert (flags() >> 1) >= 0;
                assert (flags() >>> 1) >= 0;
            }
            """.trimIndent()
        )
    }

    /** A signed base must keep its arithmetical right shift, which is chosen from the operand type. */
    @Test
    fun shiftsOnSignedBase() {
        compile(
            """
            rule shiftSigned {
                assert (signed() >> 1) <= 63;
                assert (signed() << 1) >= -128;
            }
            """.trimIndent()
        )
    }

    @Test
    fun bytesBase() {
        compile(
            """
            rule maskWord {
                assert (word() & word()) == word();
                assert (word() & to_bytes32(3)) != to_bytes32(4);
            }
            """.trimIndent()
        )
    }
}
