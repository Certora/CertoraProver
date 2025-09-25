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

package spec

import spec.cvlast.SpecCallSummary

interface IWithSummaryInfo {
    val internalSummaries: Map<CVL.SummarySignature.Internal, SpecCallSummary.ExpressibleInCVL>
    val externalSummaries: Map<CVL.SummarySignature.External, SpecCallSummary.ExpressibleInCVL>
    val unresolvedSummaries: Map<CVL.SummarySignature.External, SpecCallSummary.ExpressibleInCVL>
}
