/*
 *     The Certora Prover
 *     Copyright (C) 2025  Certora Ltd.
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, version 3 of the License.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY, without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR a PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package analysis.opt.bytemaps

import config.Config
import instrumentation.transformers.FilteringFunctions
import instrumentation.transformers.InsertMapDefinitions
import tac.Tag
import vc.data.CoreTACProgram
import vc.data.tacexprutil.ExprUnfolder.Companion.unfoldAll
import vc.data.tacexprutil.subs

/**
 * Runs our set of bytemap optimizations.
 *
 * [cheap] is useful when running before [analysis.opt.overflow.OverflowPatternRewriter]. It's much faster, and it's
 * all that is needed to get rid of vyper use of memory, which obfuscates the overflow patterns.
 */
fun optimizeBytemaps(
    code: CoreTACProgram,
    filteringFunctions: FilteringFunctions,
    cheap : Boolean = false
): CoreTACProgram {
    if (Config.PreciseByteMaps.get() || !Config.BytemapOptimizations.get()) {
        return code
    }
    val unfolded =
        unfoldAll(code) { it.rhs.subs.any { it.tag is Tag.ByteMap } }
            .let { InsertMapDefinitions.transform(it) }

    return if(cheap) {
        unfolded.let { BytemapInliner.go(it, filteringFunctions::isInlineable, cheap = true) }
    } else {
        unfolded
            .let { BytemapInliner.go(it, filteringFunctions::isInlineable, cheap = false) }
            .let { BytemapConeOfInf.go(it, filteringFunctions) }
            .let(BytemapScalarizer::go)
            .let { BytemapConeOfInf.go(it, filteringFunctions) }
    }
}
