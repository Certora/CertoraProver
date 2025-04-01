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
import utils.KSerializable
import utils.Range
import utils.hash

// This file contains the simple top-level "Java" AST nodes, such as rules, definitions, etc.  More complicated syntactic
// forms have their own files.  See README.md for a description of the Java AST.

/** This is a wrapper for a CVLError that is generated during parsing; it just returns the error during kotlinization */
interface ErrorASTNode<T> : Kotlinizable<T> {
    val error : CVLError
    override fun kotlinize(resolver: TypeResolver, scope: CVLScope): CollectingResult<Nothing, CVLError>
        = error.asError()
}

class Ast(
    val astBaseBlocks: AstBaseBlocks,
    val importedContracts: List<ImportedContract>,
    val importedSpecFiles: List<ImportedSpecFile>,
) : Kotlinizable<CVLAst> {

    override fun kotlinize(resolver: TypeResolver, scope: CVLScope): CollectingResult<CVLAst, CVLError> {
        val contractAliasesFlattened = importedContracts.map { resolver.registerContractAlias(it) }.flatten()
        val methodBlockAnnotation    = astBaseBlocks.methods.kotlinize(resolver, scope)
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
                scope).lift()
        }
    }

    fun toCVLAst(resolver: TypeResolver, scope: CVLScope): CollectingResult<CVLAst, CVLError> {
        return kotlinize(resolver, scope)
    }
}

// TODO CERT-3743: add a common super type to the different kinds of base blocks and remove this class
class AstBaseBlocks {
    val rules   = mutableListOf<Rule>()
    val subs    = mutableListOf<CVLFunction>()
    val invs    = mutableListOf<Invariant>()
    val sorts   = mutableListOf<UninterpretedSortDecl>()
    val ghosts  = mutableListOf<GhostDecl>()
    val macros  = mutableListOf<MacroDefinition>()
    val hooks   = mutableListOf<Hook>()
    val methods = mutableListOf<MethodEntry>()

    val useImportedRuleDeclarations      = mutableListOf<UseDeclaration.ImportedRule      >()
    val useImportedInvariantDeclarations = mutableListOf<UseDeclaration.ImportedInvariant >()
    val useBuiltInRuleDeclarations       = mutableListOf<UseDeclaration.BuiltInRule       >()

    val overrideDefinitionDeclarations   = mutableListOf<OverrideDeclaration.MacroDefinition >()
    val overrideFunctionDeclarations     = mutableListOf<OverrideDeclaration.CVLFunction     >()

    fun add (it : UseDeclaration) = when(it) {
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

class CVLFunction @JvmOverloads constructor(
    val range: Range,
    val id: String,
    val params: List<CVLParam>,
    val returnType: TypeOrLhs? = null, // null == void return type
    val block: List<Cmd>
) : Kotlinizable<spec.cvlast.CVLFunction> {
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

class Rule(
    private val isImportedSpecFile: Boolean,
    private val range: Range,
    private val id: String,
    private val params: List<CVLParam>,
    private val methodParamFilters: MethodParamFiltersMap,
    private val description: String?,
    private val goodDescription: String?,
    private val block: List<Cmd>
) : Kotlinizable<ICVLRule> {

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


class UninterpretedSortDecl(val range: Range, val id: String) : Kotlinizable<SortDeclaration> {
    override fun kotlinize(resolver: TypeResolver, scope: CVLScope): CollectingResult<SortDeclaration, Nothing>
        = SortDeclaration(PureCVLType.Sort(id), range).lift()
}

sealed class UseDeclaration(val range: Range, val id: String) {

    class ImportedRule(range: Range, id: String, val methodParamFilters: MethodParamFiltersMap) :
        UseDeclaration(range, id), Kotlinizable<spec.cvlast.UseDeclaration.ImportedRule> {
        override fun kotlinize(
            resolver: TypeResolver,
            scope: CVLScope
        ): CollectingResult<spec.cvlast.UseDeclaration.ImportedRule, CVLError> =
            methodParamFilters.kotlinize(resolver, scope)
                .map { spec.cvlast.UseDeclaration.ImportedRule(id, range, it) }
    }

    class ImportedInvariant(
        range: Range,
        id: String,
        val proof: InvariantProof,
        val methodParamFilters: MethodParamFiltersMap
    ) : UseDeclaration(range, id), Kotlinizable<spec.cvlast.UseDeclaration.ImportedInvariant> {
        override fun kotlinize(
            resolver: TypeResolver,
            scope: CVLScope
        ): CollectingResult<spec.cvlast.UseDeclaration.ImportedInvariant, CVLError> = proof.kotlinize(resolver, scope)
            .bind { p ->
                methodParamFilters.kotlinize(resolver, scope)
                    .map { mpf -> spec.cvlast.UseDeclaration.ImportedInvariant(id, range, p, mpf) }
            }
    }

    class BuiltInRule(range: Range, id: String, val methodParamFilters: MethodParamFiltersMap) : UseDeclaration(range, id), Kotlinizable<spec.cvlast.UseDeclaration.BuiltInRule> {
        override fun kotlinize(resolver: TypeResolver, scope: CVLScope): CollectingResult<spec.cvlast.UseDeclaration.BuiltInRule, CVLError>
            = methodParamFilters.kotlinize(resolver, scope)
                .map { mpf -> spec.cvlast.UseDeclaration.BuiltInRule(id, range, mpf) }
    }
}


class MacroDefinition(val range: Range, val id: String, val param: List<CVLParam>, val returnType: TypeOrLhs, val body: Exp) : Kotlinizable<CVLDefinition> {
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
class ImportedContract(override val alias: String, override val contractName: String, override val range: Range) : Kotlinizable<CVLImportedContract>, ContractAliasDefinition {
    override fun toString() = "ImportedContract($alias,$contractName)"

    override fun kotlinize(resolver: TypeResolver, scope: CVLScope): CollectingResult<CVLImportedContract, CVLError>
        = CVLImportedContract(alias, SolidityContract(resolver.resolveContractName(contractName)), range).lift()

    override fun hashCode() : Int = hash { it + alias + contractName }
    override fun equals(other : Any?) : Boolean = other is ImportedContract && other.alias == alias && other.contractName == contractName
}


class ImportedSpecFile(val specFileOrigPath: String) : Kotlinizable<CVLImportedSpecFile> {
    override fun toString() = "ImportedSpecFile($specFileOrigPath)"

    @Suppress("ForbiddenMethodCall") // TODO CERT-3748
    override fun kotlinize(resolver: TypeResolver, scope: CVLScope): CollectingResult<CVLImportedSpecFile, CVLError>
        = CVLImportedSpecFile(specFileOrigPath.replace("^\"|\"$".toRegex(), "")).lift()
}


sealed class OverrideDeclaration {
    class MacroDefinition(val def: com.certora.certoraprover.cvl.MacroDefinition) : OverrideDeclaration(), Kotlinizable<spec.cvlast.OverrideDeclaration.CVLDefinition> {
        override fun kotlinize(resolver: TypeResolver, scope: CVLScope): CollectingResult<spec.cvlast.OverrideDeclaration.CVLDefinition, CVLError>
            = def.kotlinize(resolver, scope).map { spec.cvlast.OverrideDeclaration.CVLDefinition(it) }
    }

    class CVLFunction(val function: com.certora.certoraprover.cvl.CVLFunction) : OverrideDeclaration(), Kotlinizable<spec.cvlast.OverrideDeclaration.CVLFunction> {
        override fun kotlinize(resolver: TypeResolver, scope: CVLScope): CollectingResult<spec.cvlast.OverrideDeclaration.CVLFunction, CVLError>
            = function.kotlinize(resolver, scope).map { spec.cvlast.OverrideDeclaration.CVLFunction(it) }
    }
}
