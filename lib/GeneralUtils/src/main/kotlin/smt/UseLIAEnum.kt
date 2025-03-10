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

package smt

import cli.ConversionException
import cli.Converter

/**
 * Enum indicating how linear solvers are used in LExpVcChecker.
 */
enum class UseLIAEnum {
    /** Do not run LIA solvers at all */
    NONE,
    /** Run LIA solvers, assuming the problem is in fact linear */
    WITHOUT_VERIFIER,
    /** Run LIA solvers and verify violations with a NIA CEXVerifier */
    WITH_VERIFIER,
    /** Run LIA solvers, taking UNSAT results as UNSAT, but SAT results as UNKNOWN (i.e. without a CEXVerifier)*/
    UNSAT_ONLY
}

val UseLIAEnumConverter = Converter {
    when (it.lowercase()){
        "false", "no", "none" -> UseLIAEnum.NONE
        "without-verifier" -> UseLIAEnum.WITHOUT_VERIFIER
        "true", "yes", "with-verifier" -> UseLIAEnum.WITH_VERIFIER
        "unsat-only" -> UseLIAEnum.UNSAT_ONLY
        else -> throw ConversionException(it, UseLIAEnum::class.java)
    }
}
