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

/**
 * Reason why the solvers of a portfolio can answer differently on the same verification condition.
 */
enum class SolverPortfolioConflict(val explanation: String) {
    BV_WITH_INT_ARITHMETIC(
        "bit-vector solvers model mathints as machine words that may over/underflow, while LIA and NIA solvers " +
            "model them as unbounded integers"
    ),
    NIA_WITH_UNVERIFIED_LIA(
        "LIA solvers without a counterexample verifier assume the verification condition is linear, while NIA " +
            "solvers do not"
    ),
}

/**
 * The conflict in the solver portfolio described by [strategy], [useBV], [useLIA] and [useNIA], or `null` if the
 * portfolio is consistent.
 *
 * Only [BackendStrategyEnum.SINGLE_RACE] can be inconsistent: there every solver of the portfolio races on the same
 * verification condition and the first answer becomes the result of the check. A portfolio whose members can answer
 * differently therefore makes the result depend on which solver happens to win the race.
 */
fun solverPortfolioConflict(
    strategy: BackendStrategyEnum,
    useBV: Boolean,
    useLIA: UseLIAEnum,
    useNIA: Boolean,
): SolverPortfolioConflict? = when {
    strategy != BackendStrategyEnum.SINGLE_RACE -> null
    useLIA == UseLIAEnum.WITHOUT_VERIFIER && useNIA -> SolverPortfolioConflict.NIA_WITH_UNVERIFIED_LIA
    useBV && (useLIA != UseLIAEnum.NONE || useNIA) -> SolverPortfolioConflict.BV_WITH_INT_ARITHMETIC
    else -> null
}

/**
 * The LIA setting to use when it was not requested explicitly, given that bit-vector solving is [useBV]. Asking for
 * bit-vector solving is asking for machine-word semantics, so the integer-arithmetic solvers step aside; this keeps
 * the derived portfolio consistent for every [BackendStrategyEnum], see [solverPortfolioConflict].
 */
fun defaultUseLIA(useBV: Boolean): UseLIAEnum = if (useBV) { UseLIAEnum.NONE } else { UseLIAEnum.WITH_VERIFIER }

/** The NIA setting to use when it was not requested explicitly. See [defaultUseLIA]. */
fun defaultUseNIA(useBV: Boolean): Boolean = !useBV
