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

package sbf.domains

/**
 * Scalar analyses are used by the memory abstract domain, composite scalar abstract domains
 * which are defined on the top of an "inner" scalar domain, and CFG transformations/analyses.
 *
 * This file contains type aliases for all scalar domains used across these analyses, transformations,
 * and composite scalar domains.
 *
 * Having all aliases in one place simplifies switching scalar domain implementations, however
 * note that some domains (e.g.
 * ([ScalarStackStridePredicateDomain] and [ScalarRegisterStackEqualityDomain]) are not fully
 * inter-exchangeable because they implement different interfaces. Also, factory aliases (suffix Fac)
 * must match their corresponding domain alias, so changing a domain alias requires updating its
 * factory alias as well.
 */

/** Scalar domain used by [MemoryDomain] **/
typealias MemoryScalarDom<TNum, TOffset> = ScalarStackStridePredicateDomain<TNum, TOffset>
/** Scalar domain factory used by [SbfCFGToTAC] **/
typealias MemoryScalarDomFac<TNum, TOffset> = ScalarStackStridePredicateDomainFactory<TNum, TOffset>

/** Scalar domain used by [ScalarStackStridePredicateDomain] **/
typealias StackStrideScalarDom<TNum, TOffset> = ScalarDomain<TNum, TOffset>

/** Scalar domain used by [ScalarRegisterStackEqualityDomain] **/
typealias RegStackEqScalarDom<TNum, TOffset> = ScalarStackStridePredicateDomain<TNum, TOffset>

/** Scalar domain used by [NPDomainAnalysis] **/
typealias NPDomScalarDom<TNum, TOffset> = ScalarRegisterStackEqualityDomain<TNum, TOffset>
/** Scalar domain factory used by [NPDomainAnalysis] **/
typealias NPDomScalarDomFac<TNum, TOffset> = ScalarRegisterStackEqualityDomainFactory<TNum, TOffset>

/** Scalar domain factory used by CFG transformations: [PromoteStoresToMemcpy], [SplitWideStores]**/
typealias CFGTransformScalarDomFac<TNum, TOffset> = ScalarRegisterStackEqualityDomainFactory<TNum, TOffset>

/** Scalar domain factory used by [GlobalInferenceAnalysis] **/
typealias GlobalAnalysisScalarDomFac<TNum, TOffset> = ScalarRegisterStackEqualityDomainFactory<TNum, TOffset>
