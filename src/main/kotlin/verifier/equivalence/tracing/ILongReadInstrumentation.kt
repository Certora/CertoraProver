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
package verifier.equivalence.tracing

import vc.data.TACSymbol

/**
 * "Public" version of instrumentation data
 * exposed to the [InstrumentationMixin] classes.
 */
internal sealed interface ILongReadInstrumentation {
    /**
     * Internal id of the long read
     */
    val id: Int

    /**
     * Hash accumulator, equivalent of `r.hash`
     */
    val hashVar: TACSymbol.Var

    /**
     * length prophecy, equivalent of r.lenProphecy
     */
    val lengthProphecy: TACSymbol.Var

    /**
     * base pointer prophecy, equivalent of r.bpProphecy
     */
    val baseProphecy: TACSymbol.Var

    /**
     * Alignment flag, equivalent of `r.aligned`
     */
    val allAlignedVar: TACSymbol.Var

    val transparentCopyData: TransparentCopyData?
}
