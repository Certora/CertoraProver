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

package utils

/**
 * a closed set of the URLs used throughout the code, which are user-visible.
 * add new enum variants as necessary.
 *
 * prefer using this over raw URLs or strings, since URLs here will be checked
 * for validity, which includes making sure they're still online (TODO CERT-6978: actually implement this)
 */
enum class CheckedUrl(val url: java.net.URL) {
    LATEST_DOCS("https://docs.certora.com/en/latest/"),

    CVL_OVERVIEW("https://docs.certora.com/en/latest/docs/cvl/overview.html"),
    SUMMARIES("https://docs.certora.com/en/latest/docs/cvl/methods.html#summaries"),
    ENVFREE_ANNOTATIONS("https://docs.certora.com/en/latest/docs/cvl/methods.html#envfree-annotations"),

    SANITY_TRIVIAL_INVARIANT_CHECKS("https://docs.certora.com/en/latest/docs/prover/checking/sanity.html#trivial-invariant-checks"),
    SANITY_REDUNDANT_REQUIRES("https://docs.certora.com/en/latest/docs/prover/checking/sanity.html#redundant-require-checks"),
    SANITY_ASSERTS_TAUTOLOGY("https://docs.certora.com/en/latest/docs/prover/checking/sanity.html#assert-tautology-checks"),
    SANITY_VACUITY("https://docs.certora.com/en/latest/docs/prover/checking/sanity.html#vacuity-checks"),
    SANITY_ASSERTIONS_STRUCTURE("https://docs.certora.com/en/latest/docs/prover/checking/sanity.html#assertion-structure-checks"),

    PROVER_TECHNIQUES("https://docs.certora.com/en/latest/docs/prover/techniques/index.html"),
    ANALYSIS_OF_STORAGE("https://docs.certora.com/en/latest/docs/prover/techniques/index.html#analysis-of-evm-storage-and-evm-memory"),

    CLI_ARGS("https://docs.certora.com/en/latest/docs/prover/cli/options.html"),
    CLI_OPTIMISTIC_FALLBACK("https://docs.certora.com/en/latest/docs/prover/cli/options.html#optimistic-fallback"),

    OUT_OF_RESOURCES("https://docs.certora.com/en/latest/docs/user-guide/out-of-resources/timeout.html"),
    TIMEOUT("https://docs.certora.com/en/latest/docs/user-guide/out-of-resources/timeout.html"),
    TIMEOUT_PREVENTION("https://docs.certora.com/en/latest/docs/user-guide/out-of-resources/timeout.html#timeout-prevention"),
    OUT_OF_MEMORY("https://docs.certora.com/en/latest/docs/user-guide/out-of-resources/memout.html"),
    FOUNDRY_INTEGRATION("https://docs.certora.com/en/latest/docs/cvl/foundry-integration.html"),

    SOLANA_INLINING("https://docs.certora.com/en/latest/docs/solana/options.html#solana-inlining")
    ;

    constructor(str: String) : this(java.net.URI(str).toURL())

    override fun toString() = url.toExternalForm()
}
