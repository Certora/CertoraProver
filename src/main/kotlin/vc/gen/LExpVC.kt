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

package vc.gen

import analysis.TACCommandGraph
import datastructures.stdcollections.*
import smt.LExpressionToSmtLib
import smt.newufliatranslator.AxiomGeneratorContainer
import smt.axiomgenerators.fullinstantiation.StorageHashAxiomGenerator
import smt.solverscript.functionsymbols.subs
import statistics.data.TACProgramMetadata
import vc.data.CoreTACProgram
import vc.data.LExpression
import vc.data.LExpressionWithComment
import vc.data.state.TACState
import verifier.LExpVcChecker
import java.math.BigInteger

/**
 * The metadata contained in an [LExpVC], i.e. everything but the [LExpression]s themselves.
 * (This is useful to make the footprint of [AxiomGeneratorContainer.visitVCMetaData] explicit.)
 */
interface LExpVCMetaData {
    /**
     * A set of literals that the [StorageHashAxiomGenerator] should not consider as "large constants in code", i.e.,
     * not as potential results of the keccack hash function.
     * Current use case: the literals that occur in the state constraint coming from the [TACState] should not be
     * considered as large constants in code, since it leads to unsoundness. (Further discussion + example in
     * [https://certora.atlassian.net/wiki/spaces/CER/pages/343408653/Hashing+Semantics+in+CVT#(Alex%2C-Yoav%2C-on-Slack%2C-2022-08-04)]
     * )
     */
    val notLargeConstantsInCode: Set<BigInteger>

    /**
     * The user can give a set of terms that in case of a SAT result, it will want to get model values for.
     * If this is null, the default computation in [LExpressionToSmtLib] will be used to determine that set of terms.
     */
    val termsOfInterest: Set<LExpression>?

    /**
     * Tac program with all transformations applied (in simplesimpleSSA form or so.., the state it is
     *  given to [LeinoWP] in); note that this is the full program, as it comes from splitting.
     */
    val tacProgram: CoreTACProgram

    val vcTacCommandGraph: TACCommandGraph
        get() = tacProgram.analysisCache.graph

    /** The CVL Rule that this VC belongs to. */
    val tacProgramMetadata: TACProgramMetadata
}

/**
 * A verification condition (VC) that we feed into [LExpVcChecker].
 * Consists of a conjunction of [LExpression]s, basically the VC itself, and some extra meta info.
 * Note this VC contains no axiomatization yet, that will be added in [LExpressionToSmtLib], which takes this as input.
 */
interface LExpVC : LExpVCMetaData {

    /**
     * The VC, in the form of [LExpression]s.
     */
    val lExpressions: List<LExpressionWithComment>

    fun expressionSequence() = lExpressions.asSequence().map { it.lExpression }

    fun subExpressionsSequence() = expressionSequence().flatMap { it.subs }
}

class LeinoLExpVC(
    blockDefinitions: List<LExpression>,
    startNotOkay: List<LExpression>,
    reachability: List<LExpression>,
    override val termsOfInterest: Set<LExpression>?,
    override val tacProgram: CoreTACProgram,
    override val tacProgramMetadata: TACProgramMetadata,
    override val notLargeConstantsInCode: Set<BigInteger> = emptySet(),
) : LExpVC {
    override val lExpressions: List<LExpressionWithComment> =
        (blockDefinitions + startNotOkay + reachability).map { LExpressionWithComment(it) }
}
