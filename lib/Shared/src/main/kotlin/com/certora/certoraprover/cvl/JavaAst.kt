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

package com.certora.certoraprover.cvl

import com.certora.certoraprover.cvl.formatter.ITokenTable
import datastructures.stdcollections.*
import spec.TypeResolver
import spec.cvlast.*
import spec.cvlast.CVLScope.Item.CVLFunctionScopeItem
import spec.cvlast.CVLScope.Item.RuleScopeItem
import spec.cvlast.CVLType.PureCVLType
import spec.cvlast.typechecker.CVLError
import spec.rules.CVLSingleRule
import spec.rules.ICVLRule
import spec.rules.SingleRuleGenerationMeta
import utils.CollectingResult
import utils.CollectingResult.Companion.asError
import utils.CollectingResult.Companion.bind
import utils.CollectingResult.Companion.bindMany
import utils.CollectingResult.Companion.flatten
import utils.CollectingResult.Companion.lift
import utils.CollectingResult.Companion.map
import utils.CollectingResult.Companion.safeForce
import utils.ErrorCollector.Companion.collectingErrors
import utils.HasRange
import utils.KSerializable
import utils.Range

// This file contains the simple top-level "Java" AST nodes, such as rules, definitions, etc.  More complicated syntactic
// forms have their own files.  See README.md for a description of the Java AST.

/** This is a wrapper for a CVLError that is generated during parsing; it just returns the error during kotlinization */
interface ErrorASTNode<T> : Kotlinizable<T> {
    val error : CVLError
    override fun kotlinize(resolver: TypeResolver, scope: CVLScope): CollectingResult<Nothing, CVLError>
        = error.asError()
}

data class Ast(
    val astBaseBlocks: AstBaseBlocks,
    val importedContracts: List<ImportedContract>,
    val importedSpecFiles: List<ImportedSpecFile>,
    val tokenTable: ITokenTable,
) {
    fun toCVLAst(resolver: TypeResolver, scope: CVLScope): CollectingResult<CVLAst, CVLError> {
        val contractAliasesFlattened = importedContracts.map { resolver.registerContractAlias(it) }.flatten()
        val methodBlockAnnotation    = astBaseBlocks.methodsBlocks.kotlinize(resolver, scope).map { it.flatten() }
        val sorts                    = astBaseBlocks.sorts.map { it.kotlinize(resolver, scope).safeForce() }
        resolver.registerSorts(sorts)
        val useDeclarations          = astBaseBlocks.kotlinizeUseDeclarations(resolver, scope)
        val rules                    = astBaseBlocks.rules.kotlinize(resolver, scope)
        val subs                     = astBaseBlocks.subs.kotlinize(resolver, scope)
        val invs                     = astBaseBlocks.invs.kotlinize(resolver, scope)
        val ghostDecls               = astBaseBlocks.ghosts.kotlinize(resolver, scope)
        val defs                     = astBaseBlocks.macros.kotlinize(resolver, scope)
        val hooks                    = astBaseBlocks.hooks.kotlinize(resolver, scope)
        val contractImports          = importedContracts.map { it.kotlinize(resolver, scope) }.flatten()
        val specImports              = importedSpecFiles.map { it.kotlinize(resolver, scope) }.flatten()
        val overrideDeclarations     = astBaseBlocks.kotlinizeOverrideDeclarations(resolver, scope)
        return bindMany(
            methodBlockAnnotation,
            useDeclarations,
            rules,
            subs,
            invs,
            ghostDecls,
            defs,
            hooks,
            contractImports,
            specImports,
            overrideDeclarations,
            contractAliasesFlattened
        ) {
            CVLAst(
                methodBlockAnnotation.force(),
                useDeclarations.force(),
                rules.force(),
                subs.force(),
                invs.force(),
                sorts,
                ghostDecls.force(),
                defs.force(),
                hooks.force(),
                contractImports.force(),
                specImports.force(),
                overrideDeclarations.force(),
                scope,
            ).lift()
        }
    }
}

/** parsed elements that are allowed to appear at the file-level ("top level") */
sealed interface TopLevel<T> : Kotlinizable<T> {
    override val range: Range.Range
}

// TODO CERT-3743: add a common super type to the different kinds of base blocks and remove this class
data class AstBaseBlocks(
    val rules: MutableList<Rule> = mutableListOf(),
    val subs: MutableList<CVLFunction> = mutableListOf(),
    val invs: MutableList<Invariant> = mutableListOf(),
    val sorts: MutableList<UninterpretedSortDecl> = mutableListOf(),
    val ghosts: MutableList<GhostDecl> = mutableListOf(),
    val macros: MutableList<MacroDefinition> = mutableListOf(),
    val hooks: MutableList<Hook> = mutableListOf(),
    val methodsBlocks: MutableList<MethodsBlock> = mutableListOf(),

    val useImportedRuleDeclarations: MutableList<UseDeclaration.ImportedRule> = mutableListOf(),
    val useImportedInvariantDeclarations: MutableList<UseDeclaration.ImportedInvariant> = mutableListOf(),
    val useBuiltInRuleDeclarations: MutableList<UseDeclaration.BuiltInRule> = mutableListOf(),

    val overrideDefinitionDeclarations: MutableList<OverrideDeclaration.MacroDefinition> = mutableListOf(),
    val overrideFunctionDeclarations: MutableList<OverrideDeclaration.CVLFunction> = mutableListOf(),
) {

    fun add (it : UseDeclaration<*>) = when(it) {
        is UseDeclaration.BuiltInRule       -> useBuiltInRuleDeclarations.add(it)
        is UseDeclaration.ImportedInvariant -> useImportedInvariantDeclarations.add(it)
        is UseDeclaration.ImportedRule      -> useImportedRuleDeclarations.add(it)
    }

    fun add(it: OverrideDeclaration) = when(it) {
        is OverrideDeclaration.MacroDefinition -> overrideDefinitionDeclarations.add(it)
        is OverrideDeclaration.CVLFunction     -> overrideFunctionDeclarations.add(it)
    }

    fun kotlinizeUseDeclarations(resolver: TypeResolver, scope: CVLScope): CollectingResult<UseDeclarations, CVLError> = collectingErrors {
        val invs     = useImportedInvariantDeclarations.kotlinize(resolver, scope)
        val rules    = useImportedRuleDeclarations.kotlinize(resolver, scope)
        val builtins = useBuiltInRuleDeclarations.kotlinize(resolver, scope)
        map(rules, invs, builtins) { importedRules, importedInvariants, builtInRulesInUse -> UseDeclarations(importedRules, importedInvariants, builtInRulesInUse) }
    }

    fun kotlinizeOverrideDeclarations(resolver: TypeResolver, scope: CVLScope): CollectingResult<OverrideDeclarations, CVLError> = collectingErrors {
        val _defs = overrideDefinitionDeclarations.kotlinize(resolver, scope)
        val _funs = overrideFunctionDeclarations.kotlinize(resolver, scope)
        map(_defs, _funs) { defs, funs -> OverrideDeclarations(defs, funs) }
    }
}

data class CVLFunction @JvmOverloads constructor(
    override val range: Range.Range,
    val id: String,
    val params: List<CVLParam>,
    val returnType: TypeOrLhs? = null, // null == void return type
    val block: CmdList,
) : TopLevel<spec.cvlast.CVLFunction> {
    override fun toString() = "CVLSubroutine($id, ${params.joinToString()}"

    override fun kotlinize(resolver: TypeResolver, scope: CVLScope): CollectingResult<spec.cvlast.CVLFunction, CVLError> {
        return scope.extendInCollecting(::CVLFunctionScopeItem) { subScope: CVLScope -> collectingErrors {
            val _params     = params.map { it.kotlinize(resolver, scope) }.flatten()
            val _block      = block.kotlinize(resolver, subScope)
            val _returnType = returnType?.toCVLType(resolver, subScope) ?: PureCVLType.Void.lift()

            map(_params, _block, _returnType) { params, block, returnType ->
                CVLFunction(range, id, params, returnType, block, subScope)
            }
        }}
    }
}

data class Rule(
    internal val isImportedSpecFile: Boolean,
    override val range: Range.Range,
    internal val id: String,
    internal val params: List<CVLParam>,
    internal val methodParamFilters: MethodParamFiltersMap,
    internal val description: String?,
    internal val goodDescription: String?,
    internal val block: CmdList,
) : TopLevel<ICVLRule> {

    override fun toString() = "Rule($id,${params.joinToString()},$description,$goodDescription)"

    override fun kotlinize(resolver: TypeResolver, scope: CVLScope): CollectingResult<ICVLRule, CVLError> {
        return scope.extendInCollecting(::RuleScopeItem) { subScope: CVLScope -> collectingErrors {
            val _params  = params.map { it.kotlinize(resolver, subScope) }.flatten()
            val _block   = block.kotlinize(resolver, subScope)
            val _filters = methodParamFilters.kotlinize(resolver, subScope)
            val (params, block, filters) = map(_params, _block, _filters) { p,b,f -> Triple(p,b,f) }
            val specFile =
                if (isImportedSpecFile) { SpecType.Single.FromUser.ImportedSpecFile }
                else { SpecType.Single.FromUser.SpecFile }

            CVLSingleRule(
                RuleIdentifier.freshIdentifier(id), range, params, description.orEmpty(), goodDescription.orEmpty(), block, specFile,
                subScope, filters, ruleGenerationMeta = SingleRuleGenerationMeta.Empty
            )
        }}
    }
}


data class UninterpretedSortDecl(override val range: Range.Range, val id: String) : TopLevel<SortDeclaration> {
    override fun kotlinize(resolver: TypeResolver, scope: CVLScope): CollectingResult<SortDeclaration, Nothing>
        = SortDeclaration(PureCVLType.Sort(id), range).lift()
}

sealed interface UseDeclaration<T: spec.cvlast.UseDeclaration> : TopLevel<T> {
    override val range: Range.Range
    val id: String
    val methodParamFilters: MethodParamFiltersMap

    class ImportedRule(
        override val range: Range.Range,
        override val id: String, override val methodParamFilters: MethodParamFiltersMap
    ) : UseDeclaration<spec.cvlast.UseDeclaration.ImportedRule> {
        override fun kotlinize(
            resolver: TypeResolver,
            scope: CVLScope
        ): CollectingResult<spec.cvlast.UseDeclaration.ImportedRule, CVLError> =
            methodParamFilters.kotlinize(resolver, scope)
                .map { spec.cvlast.UseDeclaration.ImportedRule(id, range, it) }
    }

    data class ImportedInvariant(
        override val range: Range.Range,
        override val id: String,
        val proof: InvariantProof,
        override val methodParamFilters: MethodParamFiltersMap
    ) : UseDeclaration<spec.cvlast.UseDeclaration.ImportedInvariant> {
        override fun kotlinize(
            resolver: TypeResolver,
            scope: CVLScope
        ): CollectingResult<spec.cvlast.UseDeclaration.ImportedInvariant, CVLError> = proof.kotlinize(resolver, scope)
            .bind { p ->
                methodParamFilters.kotlinize(resolver, scope)
                    .map { mpf -> spec.cvlast.UseDeclaration.ImportedInvariant(id, range, p, mpf) }
            }
    }

    data class BuiltInRule(
        override val range: Range.Range,
        override val id: String,
        override val methodParamFilters: MethodParamFiltersMap
    ) : UseDeclaration<spec.cvlast.UseDeclaration.BuiltInRule> {
        override fun kotlinize(resolver: TypeResolver, scope: CVLScope): CollectingResult<spec.cvlast.UseDeclaration.BuiltInRule, CVLError>
            = methodParamFilters.kotlinize(resolver, scope)
                .map { mpf -> spec.cvlast.UseDeclaration.BuiltInRule(id, range, mpf) }
    }
}


data class MacroDefinition(override val range: Range.Range, val id: String, val param: List<CVLParam>, val returnType: TypeOrLhs, val body: Exp) : TopLevel<CVLDefinition>{
    override fun kotlinize(resolver: TypeResolver, scope: CVLScope): CollectingResult<CVLDefinition, CVLError> = collectingErrors {
        val _params     = param.map { it.kotlinize(resolver, scope) }.flatten()
        val _returnType = returnType.toCVLType(resolver, scope)
        val _body       = body.kotlinize(resolver,scope)
        map(_params, _body, _returnType) { params, body, returnType ->
            CVLDefinition(range, id, params, returnType, body, scope)
        }
    }
}

@KSerializable
data class ImportedContract(override val alias: String, override val contractName: String, override val range: Range.Range) : TopLevel<CVLImportedContract>, ContractAliasDefinition {
    override fun toString() = "ImportedContract($alias,$contractName)"

    override fun kotlinize(resolver: TypeResolver, scope: CVLScope): CollectingResult<CVLImportedContract, CVLError> =
        checkIdValidity(alias, range, "contract alias").map { alias ->
            CVLImportedContract(alias, SolidityContract(resolver.resolveContractName(contractName)), range)
        }
}


data class ImportedSpecFile(val specFileOrigPath: String, override val range: Range.Range) : TopLevel<CVLImportedSpecFile> {
    override fun toString() = "ImportedSpecFile($specFileOrigPath)"

    @Suppress("ForbiddenMethodCall") // TODO CERT-3748
    override fun kotlinize(resolver: TypeResolver, scope: CVLScope): CollectingResult<CVLImportedSpecFile, CVLError>
        = CVLImportedSpecFile(specFileOrigPath.replace("^\"|\"$".toRegex(), "")).lift()
}


sealed interface OverrideDeclaration : HasRange {
    data class MacroDefinition(val def: com.certora.certoraprover.cvl.MacroDefinition, override val range: Range.Range) : OverrideDeclaration, TopLevel<spec.cvlast.OverrideDeclaration.CVLDefinition> {
        override fun kotlinize(resolver: TypeResolver, scope: CVLScope): CollectingResult<spec.cvlast.OverrideDeclaration.CVLDefinition, CVLError>
            = def.kotlinize(resolver, scope).map { spec.cvlast.OverrideDeclaration.CVLDefinition(it) }
    }

    data class CVLFunction(val function: com.certora.certoraprover.cvl.CVLFunction, override val range: Range.Range) : OverrideDeclaration, TopLevel<spec.cvlast.OverrideDeclaration.CVLFunction> {
        override fun kotlinize(resolver: TypeResolver, scope: CVLScope): CollectingResult<spec.cvlast.OverrideDeclaration.CVLFunction, CVLError>
            = function.kotlinize(resolver, scope).map { spec.cvlast.OverrideDeclaration.CVLFunction(it) }
    }
}
