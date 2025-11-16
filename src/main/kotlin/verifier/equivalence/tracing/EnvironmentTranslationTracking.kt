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

package verifier.equivalence.tracing

import analysis.CommandWithRequiredDecls
import vc.data.TACCmd
import vc.data.TACSymbol
import vc.data.TXF
import vc.data.ToTACExpr
import vc.data.tacexprutil.ExprUnfolder
import datastructures.stdcollections.*

/**
 * Used to insert instrumentation at a read site to constrain the [translatedOffset] to be equal to the
 * sum of [sourceMemoryOffset] + [envMapOffset]. NB we need to insert this instrumentation at the point of the long read
 * to make sure we capture the correct values.
 *
 * This is *something* of an abuse of the instrumentation facilities, but it is a convenient way to insert arbitrary
 * instrumentation at long read sites that has to coexist with other instrumentation.
 */
internal class EnvironmentTranslationTracking(
    val envMapOffset: TACSymbol,
    val translatedOffset: TACSymbol.Var,
    val sourceMemoryOffset: TACSymbol
) : InstrumentationMixin {
    override fun atPrecedingUpdate(
        s: IBufferUpdate,
        overlapSym: TACSymbol.Var,
        writeEndPoint: TACSymbol.Var,
        baseInstrumentation: ILongReadInstrumentation
    ): CommandWithRequiredDecls<TACCmd.Simple> {
        return CommandWithRequiredDecls()
    }

    override fun atLongRead(s: ILongRead): CommandWithRequiredDecls<TACCmd.Simple> {
        return ExprUnfolder.unfoldPlusOneCmd("translation", TXF {
            translatedOffset eq (envMapOffset add sourceMemoryOffset)
        }) {
            TACCmd.Simple.AssumeCmd(it.s, "translate offsets")
        }
    }

    override val havocInitVars: List<TACSymbol.Var>
        get() = listOf(translatedOffset)
    override val constantInitVars: List<Pair<TACSymbol.Var, ToTACExpr>>
        get() = listOf()
}
