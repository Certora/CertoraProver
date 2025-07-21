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

package com.certora.certoraprover.cvl.formatter

import com.certora.certoraprover.cvl.*
import com.certora.certoraprover.cvl.formatter.util.ensure
import com.certora.certoraprover.cvl.formatter.util.zipWithPrevious
import datastructures.stdcollections.*

internal typealias Batch = List<Token>

internal fun preprocess(ast: Ast): List<Batch> {
    val topLevels = topLevels(ast).flatten().sortedBy { it.range }
    val tokenTable = ast.tokenTable.downcast()

    if (topLevels.isEmpty()) {
        /**
         * special casing for when the entire file is just comments, or empty.
         * see [CommentsBuilder.build].
         *
         * continuing with this input will break a whole bunch of asserts and assumptions.
         * either way, there's nothing to preprocess here, just output as-is.
         */
        val batch = tokenTable.fileComments.map(Token::Comments)
        return listOf(batch)
    }

    val rangeCollector = RangeCollector()
    for (top in topLevels) {
        rangeCollector.traverse(top)
    }

    val tokenizer = Tokenizer(
        tokenTable.fileComments,
        rangeCollector.nodeToRange,
        rangeCollector.rangeToNode
    )

    val batches = topLevels
        .zipWithPrevious { prevTop, top ->
            val needsSeparator = needsSeparator(prevTop, top)
            val batch = tokenizer.tokenize(top)
            Pair(batch, needsSeparator)
        }
        .fold(mutableListOf<Batch>()) { batches, (batch, needsSeparator) ->
            if (needsSeparator) {
                batches.add(batch)
            } else {
                val prevBatch = batches.removeLastOrNull()?.toMutableList()
                ensure(prevBatch != null, "we add to `batches` on every iteration and this is not the first batch")

                prevBatch.add(Token.LineBreak.Soft)
                prevBatch.addAll(batch)

                batches.add(prevBatch)
            }

            batches
        }

    val uninserted = tokenizer.uninsertedComments()
    ensure(uninserted.isEmpty(), "all nodes were visited") { "got $uninserted" }

    return batches
}

private fun needsSeparator(prevTop: TopLevel<*>?, top: TopLevel<*>): Boolean = when {
    prevTop == null -> true
    prevTop is ImportedContract && top is ImportedContract -> false
    prevTop is ImportedSpecFile && top is ImportedSpecFile -> false
    prevTop is UseDeclaration<*> && top is UseDeclaration<*> -> {
        // we might want to sort these declarations,
        // so declarations like `use invariant` cluster together,
        // and all declarations are sorted in alphabetical order.
        false
    }
    else -> true
}

/**
 * this is a list of the top-level elements (each of which is a rooted AST).
 * there's no guarantee of order between top-levels.
 */
private fun topLevels(ast: Ast): List<List<TopLevel<*>>> = listOf(
    ast.astBaseBlocks.rules,
    ast.astBaseBlocks.subs,
    ast.astBaseBlocks.invs,
    ast.astBaseBlocks.sorts,
    ast.astBaseBlocks.ghosts,
    ast.astBaseBlocks.macros,
    ast.astBaseBlocks.hooks,
    ast.astBaseBlocks.methodsBlocks,

    ast.astBaseBlocks.useImportedRuleDeclarations,
    ast.astBaseBlocks.useImportedInvariantDeclarations,
    ast.astBaseBlocks.useBuiltInRuleDeclarations,

    ast.astBaseBlocks.overrideDefinitionDeclarations,
    ast.astBaseBlocks.overrideFunctionDeclarations,

    ast.importedContracts,
    ast.importedSpecFiles,
)