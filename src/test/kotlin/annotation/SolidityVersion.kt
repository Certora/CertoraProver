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

package annotation

enum class SolidityVersion(val pointSuffix: String, val majorVersion: Int) {
    ANY_4("4.24", 4),
    ANY_5("5", 5),


    V4_24("4.24", 4),
    V4_25("4.25", 4),

    V5_2("5.2", 5),
    V5_3("5.3", 5),
    V5_9("5.9", 5),
    V5_11("5.11", 5),
    V5_12("5.12", 5),
    V5_13("5.13", 5),
    V5_16("5.16", 5),

    V6_1("6.1", 6),
    V6_2("6.2", 6),
    V6_6("6.6", 6),
    V6_8("6.8", 6),
    V6_10("6.10", 6),
    V6_11("6.11", 6),
    V6_12("6.12", 6),

    V7_0("7.0", 7),
    V7_6("7.6", 7),

    V8_1("8.1", 8),
    V8_2("8.2", 8),
    V8_9("8.9", 8),
    V8_11("8.11", 8),
    V8_13("8.13", 8),
    V8_16("8.16", 8),
    V8_18("8.18", 8),
    V8_19("8.19", 8),
    V8_27("8.27", 8),
    V8_28("8.28", 8),
    V8_29("8.29", 8)
    ;

    fun compilerName() = "solc${this.pointSuffix}"
}
