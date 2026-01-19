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

package config


/** Controls the level of debug information computed and maintained */
enum class DebugAdapterProtocolMode(val configString: String, val desc: String) {
    /**
     * The output for the VS code debugger is switches off (default to not dump unnecessary files).
     */
    DISABLED(configString = "disabled", desc = "no information is generated"),
    /**
     * The debugger output gg will only show the call stack and allow stepping along statements,
     * no variable mapping from TAC to source variable names will be computed (for Solana
     * the won't be stored).
     */
    CALLSTACK(configString = "callstack", desc = "only the call stack information is generated allowing the debugger to step along the call trace"),
    /**
     * The debugger output generated includes the call stack, allows stepping along statements,
     * and also evaluates.
     *
     * NOTE: This mode is experimental, it largely increases the dump outputs and the SMT time as it adds many annotations.
     */
    VARIABLES(configString = "variables", desc = "EXPERIMENTAL: full information, including call stack and variable information - may generate large dumps and extra information that cannot be optimized");

    companion object {
        fun paramDescriptions() = buildString {
            entries.forEach { appendLine("${it.configString} - ${it.desc}") }
        }
    }
}