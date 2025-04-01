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

import com.certora.certoraprover.cvl.Ast
import com.certora.certoraprover.cvl.Lexer
import com.certora.certoraprover.cvl.ParserCVL
import config.Config
import java_cup.runtime.ComplexSymbolFactory
import datastructures.stdcollections.*
import spec.cvlast.*
import spec.cvlast.typechecker.CVLError
import spec.rules.CVLSingleRule
import spec.rules.ICVLRule
import utils.CollectingResult
import utils.CollectingResult.Companion.bind
import utils.CollectingResult.Companion.flatten
import utils.CollectingResult.Companion.flattenToVoid
import utils.CollectingResult.Companion.lift
import utils.CollectingResult.Companion.map
import utils.Range
import java.io.FilterInputStream
import java.io.Reader

sealed class CVLInput {

    // static methods
    companion object {
        /**
         * Parses [cvlSource].
         * Receives a [resolver] for types.
         */
        fun parseCVLSpec(cvlSource: CVLSource, resolver: TypeResolver): CollectingResult<CVLAst, CVLError> {
            val reader = cvlSource.getReader()
            return reader.use { cvlReader ->
                parseCVLSpecFromReader(cvlReader, cvlSource.isImported, resolver, cvlSource.origpath) // TODO Merge: Config.prependInternalDir(cvlFile)
            }
        }

        /**
         * Parses and returns an AST given a [reader] of the CVL contents, with file name indication [fileName],
         * a type [resolver], and a boolean setting if the file is imported or not [isImportedCvlSource].
         */
        private fun parseCVLSpecFromReader(reader: Reader, isImportedCvlSource: Boolean, resolver: TypeResolver, fileName: String): CollectingResult<CVLAst, CVLError> {
            val csf = ComplexSymbolFactory()
            val lexer = Lexer(csf, reader)
            val vmConfig = Config.VMConfig
            val allKeywords = allCastFunctions.toList() + vmConfig.memoryLocations.toList() +
                    vmConfig.preReturnMethodQualifiers.toList() + vmConfig.postReturnMethodQualifiers.toList() + vmConfig.hookableOpcodes.toList()
            check(allKeywords.distinct().size == allKeywords.size) { "duplicate keywords exist in $this which may lead to lexer ambiguities"}
            lexer.setCastFunctions(allCastFunctions)
            lexer.setMemoryLocations(vmConfig.memoryLocations)
            lexer.setHookableOpcodes(vmConfig.hookableOpcodes)
            lexer.setMethodQualifiers(vmConfig.preReturnMethodQualifiers, vmConfig.postReturnMethodQualifiers)
            lexer.setConstVals(CVLKeywords.constVals.compute(vmConfig).keys)
            lexer.setBuiltInFunctions(CVLBuiltInName.entries.mapTo(mutableSetOf()) {
                it.bifName
            })
            lexer.theFilename = fileName
            return ParserCVL.parse_with_errors(lexer, csf, fileName, isImportedCvlSource).bind { ast : Ast ->
                ast.toCVLAst(resolver, CVLScope.AstScope)
            }
        }

        protected fun addUnresolvedUseDeclsError(unresolvedUseDecl: UseDeclaration): CVLError =
            CVLError.General(
                unresolvedUseDecl.range,
                "Cannot resolve the 'use' declaration: unknown identifier ${unresolvedUseDecl.id}"
            )


        protected fun addRepeatingIdInUseDeclsError(repeatingId: String, range: Range): CVLError =
            CVLError.General(
                range,
                repeatingIdInDeclsErrorMsg(repeatingId, "use")
            )

        protected fun repeatingIdInDeclsErrorMsg(repeatingId: String, declType: String): String =
            "Another '$declType' declaration also uses the identifier $repeatingId. " +
                    "Each identifier can occur in at most one '$declType' declaration"


    }

    /** The main .spec file of the verification query */
    abstract val cvlSource: CVLSource

    abstract fun getRawCVLAst(resolver: TypeResolver): CollectingResult<CVLAst, CVLError>




    /**
     * "Plain" CVL input file. In this mode, one cannot import .spec files.
     */
    data class Plain(override val cvlSource: CVLSource) : CVLInput() {

        override fun getRawCVLAst(resolver: TypeResolver): CollectingResult<CVLAst, CVLError> {
            return parseCVLSpec(cvlSource, resolver).bind { rawAst ->
                val errors = mutableListOf<CVLError>()
                if (rawAst.importedSpecFiles.isNotEmpty()) {
                    if (Config.BytecodeFiles.getOrNull().isNullOrEmpty()) {
                        errors.add(
                            CVLError.General(
                                Range.Empty(),
                                "'import' declarations of .spec files are unsupported; if you're running through certora-cli, do you have the latest version installed?",
                            )
                        )
                    } else {
                        errors.add(
                            CVLError.General(
                                Range.Empty(),
                                "'import' declarations of .spec files are unsupported in bytecode mode",
                            )
                        )
                    }
                }
                if (rawAst.useDeclarations.importedRulesAndInvariants.isNotEmpty()) {
                    errors.add(
                        CVLError.General(
                            rawAst.useDeclarations.importedRulesAndInvariants.first().range,
                            "'use' declarations of imported rules or invariants are unsupported; if you're running through certora-cli, do you have the latest version installed?",
                        )
                    )
                }

                if (rawAst.overrideDeclarations.allDecls.isNotEmpty()) {
                    errors.add(
                        CVLError.General(
                            rawAst.overrideDeclarations.allDecls.first().range,
                            "'override' declarations are unsupported; if you're running through certora-cli, do you have the latest version installed?",
                        )
                    )
                }

                errors.flattenToVoid().map {
                    rawAst
                }
            }
        }
    }

    /**
     * Imports of .spec files are supported.
     */
    data class WithImportedSpecs(
        override val cvlSource: CVLSource,
        /** Maps each .spec file to the import declarations that it contains (as fetched in certoraBuild.py) */
        val cvlSourceNameToImportDecls: Map<String, List<String>>,
        /** Maps each imported .spec file to its original path */
        val importedCVLSources: List<CVLSource>
    ) : CVLInput() {

        override fun getRawCVLAst(resolver: TypeResolver): CollectingResult<CVLAst, CVLError> {
            /**CERT-4488 Do the parsing of all imported files _before_ parsing the actual file using the imports. This is necessary in the
             * case there are [ContractAliasDefinition] that need to be registered with the [TypeResolver]**/
            val importedFileToParsedAst = importedCVLSources.map { importedFile ->
                importedFile to parseCVLSpec(importedFile, resolver = resolver)}

            return parseCVLSpec(cvlSource, resolver = resolver).bind { astWithoutMergedImports ->
                val errors = mutableListOf<CVLError>()

                if (astWithoutMergedImports.useDeclarations.importedRulesAndInvariantsIds.toSet().size < astWithoutMergedImports.useDeclarations.importedRulesAndInvariantsIds.size) {
                    val repeatingIdsToUseDeclLocs =
                        astWithoutMergedImports.useDeclarations.importedRulesAndInvariants.groupBy { it.id }
                            .filter { it.value.size > 1 }
                            .mapValues { it.value.map { useDecl -> useDecl.range }.toSet() }
                    check(repeatingIdsToUseDeclLocs.isNotEmpty())
                    errors += addRepeatingIdsInDeclsErrors(repeatingIdsToUseDeclLocs, ::addRepeatingIdInUseDeclsError)
                }

                if (astWithoutMergedImports.overrideDeclarations.allDeclsIds.toSet().size < astWithoutMergedImports.overrideDeclarations.allDeclsIds.size) {
                    val repeatingIdsToOverrideDeclLocs =
                        astWithoutMergedImports.overrideDeclarations.allDecls.groupBy { it.id }
                            .filter { it.value.size > 1 }
                            .mapValues { it.value.map { overrideDecl -> overrideDecl.range }.toSet() }
                    check(repeatingIdsToOverrideDeclLocs.isNotEmpty())
                    errors += addRepeatingIdsInDeclsErrors(
                        repeatingIdsToOverrideDeclLocs,
                        ::addRepeatingIdInOverrideDeclsError
                    )
                }

                val unresolvedImpUseDecls: MutableList<UseDeclaration> =
                    astWithoutMergedImports.useDeclarations.importedRulesAndInvariants.toMutableList()

                val overrideDefDeclsToMatchingDefs =
                    mutableMapOf<OverrideDeclaration<CVLDefinition>, MutableSet<CVLDefinition>>().also {
                        astWithoutMergedImports.overrideDeclarations.overriddenDefinitions.associateWithTo(it) {
                            mutableSetOf()
                        }
                    }
                val overrideFuncDeclsToMatchingFuncs =
                    mutableMapOf<OverrideDeclaration<CVLFunction>, MutableSet<CVLFunction>>().also {
                        astWithoutMergedImports.overrideDeclarations.overriddenCVLFunctions.associateWithTo(it) {
                            mutableSetOf()
                        }
                    }

                if (astWithoutMergedImports.importedSpecFiles.isNotEmpty()) {
                    checkNoImportsAreMissing(cvlSource, astWithoutMergedImports.importedSpecFiles)
                }

                val impImportedMethods: MutableList<MethodBlockEntry> = mutableListOf()
                val impSubs: MutableList<CVLFunction> = mutableListOf()
                val impSorts: MutableList<SortDeclaration> = mutableListOf()
                val impGhosts: MutableList<CVLGhostDeclaration> = mutableListOf()
                val impDefinitions: MutableList<CVLDefinition> = mutableListOf()
                val impHooks: MutableList<CVLHook> = mutableListOf()
                val impImportedContracts: MutableList<CVLImportedContract> = mutableListOf()

                val impRulesInUse: MutableList<ICVLRule> = mutableListOf()
                val impInvsInUse: MutableList<CVLInvariant> = mutableListOf()

                fun addImportedFileDecls(importedSpecFileAst: CVLAst) {
                    impImportedMethods.addAll(importedSpecFileAst.importedMethods)
                    impSubs.addAll(
                        getImportedElementsWithOverriding(
                            astWithoutMergedImports.overrideDeclarations.overriddenCVLFunctions,
                            importedSpecFileAst.subs,
                            overrideFuncDeclsToMatchingFuncs
                        )
                    )
                    impSorts.addAll(importedSpecFileAst.sorts)
                    impGhosts.addAll(importedSpecFileAst.ghosts)
                    impDefinitions.addAll(
                        getImportedElementsWithOverriding(
                            astWithoutMergedImports.overrideDeclarations.overriddenDefinitions,
                            importedSpecFileAst.definitions,
                            overrideDefDeclsToMatchingDefs
                        )
                    )
                    impHooks.addAll(importedSpecFileAst.hooks)
                    impImportedContracts.addAll(importedSpecFileAst.importedContracts)

                    // Add rules and invariants that match the use declarations in [astWithoutMergedImports]
                    // NOTE: use declarations, as opposed to spec import declarations, are NOT transitive, i.e.,
                    // use declarations in [importedSpecFileAst] are ignored (including overriding of filters, preserved blocks, etc.).
                    // This reflects that usage is an option but not an obligation.
                    val matchingImpRules = importedSpecFileAst.rules.filter {
                        it.declarationId in astWithoutMergedImports.useDeclarations.importedRulesDistinctIds
                    }
                    val matchingImpInvs = importedSpecFileAst.invs.filter {
                        it.id in astWithoutMergedImports.useDeclarations.importedInvariantsDistinctIds
                    }

                    impRulesInUse.addAll(
                        getImpRulesWithOverriddenMethodParamFilters(
                            matchingImpRules, astWithoutMergedImports
                        )
                    )
                    unresolvedImpUseDecls.removeAll { unresUseDecl ->
                        (unresUseDecl is UseDeclaration.ImportedRule && unresUseDecl.id in matchingImpRules.map { it.declarationId }) ||
                            (unresUseDecl is UseDeclaration.ImportedInvariant && unresUseDecl.id in matchingImpInvs.map { it.id })
                    }
                    getImpInvsWithOverriddenPreservedAndParamFilters(
                        importedSpecFileAst.invs, astWithoutMergedImports
                    ).map { invariants ->
                        impInvsInUse.addAll(invariants)
                    }
                }

                fun addInternalImports() {
                    if (Config.Foundry.get()) {
                        val cheatcodeSpec =
                            ClassLoader.getSystemResource("Foundry/cheatcodes.spec").content as FilterInputStream
                        val specContents = cheatcodeSpec.bufferedReader().use { it.readText() }
                        val cheatCodeSource = CVLSource.Raw("cheatcodes.spec", specContents, true)
                        val parsedSpec = parseCVLSpec(cheatCodeSource, resolver)
                        addImportedFileDecls(parsedSpec.force())
                    }
                }

                val mergedAst = importedFileToParsedAst.map { (importedFile, importedAst) ->
                    importedAst.map { importedSpecFileAst ->
                        if (importedSpecFileAst.importedSpecFiles.isNotEmpty()) {
                            checkNoImportsAreMissing(
                                importedFile, importedSpecFileAst.importedSpecFiles
                            )
                        }
                        addImportedFileDecls(importedSpecFileAst)
                    }
                }.flatten().map { _ ->
                    addInternalImports()
                    astWithoutMergedImports.copy(
                        rules = impRulesInUse.also { it.addAll(astWithoutMergedImports.rules) },
                        invs = impInvsInUse.also { it.addAll(astWithoutMergedImports.invs) },
                        importedMethods = impImportedMethods.also { it.addAll(astWithoutMergedImports.importedMethods) },
                        subs = impSubs.also { it.addAll(astWithoutMergedImports.subs) },
                        sorts = impSorts.also { it.addAll(astWithoutMergedImports.sorts) },
                        ghosts = impGhosts.also { it.addAll(astWithoutMergedImports.ghosts) },
                        definitions = impDefinitions.also { it.addAll(astWithoutMergedImports.definitions) },
                        hooks = impHooks.also { it.addAll(astWithoutMergedImports.hooks) },
                        importedContracts = impImportedContracts.also { it.addAll(astWithoutMergedImports.importedContracts) },
                        importedSpecFiles = emptyList(), // Treat the "merged" .spec file as if it has no imports
                        overrideDeclarations = OverrideDeclarations() // Treat the "merged" .spec file as if it has no overrides
                    )
                }

                // Report any unresolved imported rules/invariants use declarations
                errors += unresolvedImpUseDecls.map { unresolvedDecl -> addUnresolvedUseDeclsError(unresolvedDecl) }

                errors += addOverrideDeclsErrors(overrideDefDeclsToMatchingDefs.mapValues {
                    it.value.map { def -> def.range }.toSet()
                })

                errors += addOverrideDeclsErrors(overrideFuncDeclsToMatchingFuncs.mapValues {
                    it.value.map { sub -> sub.range }.toSet()
                })
                // Report any unresolved or ambiguous override declarations
                errors.flattenToVoid().map(mergedAst) { _, ast ->
                    ast
                }
            }
        }

        /**
         * Returns the imported CVL spec elements [importedElements] such that each element that is overridden with a declaration in [overrideDecls] is replaced
         * with its overridden definition. [overrideDeclsToMatchingElems] should map each override declaration in [overrideDecls]
         * to the imported elements that it has been resolved to override thus far.
         * This function updates the map values in [overrideDeclsToMatchingElems] accordingly.
         */
        private fun <T> getImportedElementsWithOverriding(
            overrideDecls: List<OverrideDeclaration<T>>,
            importedElements: List<T>,
            overrideDeclsToMatchingElems: MutableMap<OverrideDeclaration<T>, MutableSet<T>>
        ): List<T> {
            return if (overrideDecls.isEmpty()) { // No definition is overridden
                importedElements
            } else {
                importedElements.map { impElem ->
                    val matchingOverrideDecl: OverrideDeclaration<T>? =
                        overrideDecls.singleOrNull { it.overrides(impElem) }
                    if (matchingOverrideDecl != null) {
                        val matchingElems: MutableSet<T> =
                            overrideDeclsToMatchingElems[matchingOverrideDecl]!!
                        matchingElems.add(impElem) // mark [matchingOverrideDecl] as matching to [impElem]
                        if (matchingElems.size == 1) {
                            matchingOverrideDecl.materialize() // add the overridden element (instead of the original, imported one)
                        } else {
                            check(matchingElems.size > 1)
                            impElem // [matchingOverrideDecl] matches multiple imported elements: this will trigger a syntax error
                        }
                    } else {
                        impElem
                    }
                }
            }
        }

        /**
         * Updates the imported rules in [matchingImpRules] with the overridden method parameters' filters that are specified
         * in the corresponding use declarations in [astWithoutMergedImports].
         * Each rule in [matchingImpRules] is assumed to have at least one use declaration. If a rule has more
         * than one use declaration, its filters are not updated since that triggers a syntax error.
         */
        private fun getImpRulesWithOverriddenMethodParamFilters(
            matchingImpRules: List<ICVLRule>,
            astWithoutMergedImports: CVLAst
        ): List<ICVLRule> =
            matchingImpRules.map { matchingRule ->
                val useDecl: UseDeclaration.ImportedRule? =
                    astWithoutMergedImports.useDeclarations.importedRules.singleOrNull { it.id == matchingRule.declarationId }
                if (useDecl?.methodParamFilters?.methodParamToFilter?.isNotEmpty() == true) {
                    check(matchingRule is CVLSingleRule) { "Expected a CVLSingleRule, but got $matchingRule" }
                    val filtersInUseDecl = useDecl.methodParamFilters.methodParamToFilter
                    val filtersInMatching = matchingRule.methodParamFilters.methodParamToFilter
                    val overriddenFilters: MutableMap<String, MethodParamFilter> =
                        filtersInUseDecl.toMutableMap()
                    // Add remaining, non-overridden filters
                    filtersInMatching.forEach { (methodParam, filterExp) ->
                        if (methodParam !in filtersInUseDecl) {
                            overriddenFilters[methodParam] = filterExp
                        }
                    }
                    matchingRule.copy(
                        methodParamFilters = matchingRule.methodParamFilters.copy(
                            methodParamToFilter = overriddenFilters,
                            range = useDecl.methodParamFilters.range
                        )
                    )
                } else { // No method param filter to override/add
                    matchingRule
                }
            }

        /**
         * Updates the imported invariants in [matchingImpInvs] with the overridden preserved blocks that are specified
         * in the corresponding use declarations in [astWithoutMergedImports].
         * Each invariant in [matchingImpInvs] is assumed to have at least one use declaration. If an invariant has more
         * than one use declaration, its preserved blocks are not updated since that triggers a syntax error.
         * Moreover, if an overridden invariant in [matchingImpInvs] has "unambiguous" preserved blocks, they are not updated
         * in order to propagate this syntax error.
         */
        private fun getImpInvsWithOverriddenPreservedAndParamFilters(
            matchingImpInvs: List<CVLInvariant>,
            astWithoutMergedImports: CVLAst
        ) = matchingImpInvs.map { matchingInv ->
            val useDecl: UseDeclaration.ImportedInvariant? =
                astWithoutMergedImports.useDeclarations.importedInvariants.singleOrNull { it.id == matchingInv.id }
            if (useDecl == null) {
                return@map matchingInv.lift()
            }

            // This invariant as a `use` declaration, so it shoulc be verified
            var inv = matchingInv.copy(needsVerification = true)

            if (useDecl.proof.preserved.isNotEmpty()) {
                inv.checkPreservedIsErrorFree().map {
                    val overriddenPreserved: MutableList<CVLPreserved> =
                        useDecl.proof.preserved.toMutableList()
                    val explicitPreservedInUseDecl: List<CVLPreserved.ExplicitMethod> =
                        useDecl.proof.preserved.filterIsInstance<CVLPreserved.ExplicitMethod>()
                    val genericPreservedInUseDecl =
                        useDecl.proof.preserved.filterIsInstance<CVLPreserved.Generic>()
                    inv.proof.preserved.forEach { origPreserved ->
                        if ((origPreserved is CVLPreserved.Generic && genericPreservedInUseDecl.isEmpty()) ||
                            (origPreserved is CVLPreserved.ExplicitMethod && explicitPreservedInUseDecl.none {
                                it.methodSignature.matchesSigHash(
                                    origPreserved.methodSignature
                                )
                            })
                        ) {
                            // The preserved (either generic or explicit) block in the original invariant is not
                            // overridden in the use declaration
                            overriddenPreserved.add(origPreserved)
                        }
                    }
                    inv = inv.copy(
                        proof = inv.proof.copy(preserved = overriddenPreserved),
                        range = useDecl.range
                    )
                }
            }
            if (useDecl.methodParamFilters.methodParamToFilter.isNotEmpty()) {
                inv = inv.copy(
                    methodParamFilters = inv.methodParamFilters.copy(
                        methodParamToFilter = useDecl.methodParamFilters.methodParamToFilter,
                        range = useDecl.methodParamFilters.range
                    )
                )
            }
            inv.lift()
        }.flatten()

        /**
         * Checks that all the actual import declarations in [cvlSource] ([actualImportedSpecFiles]) are exactly those
         * that were fetched in certoraBuild.py.
         * Assumes that [actualImportedSpecFiles] is non-empty.
         */
        private fun checkNoImportsAreMissing(
            cvlSource: CVLSource,
            actualImportedSpecFiles: List<CVLImportedSpecFile>
        ) {
            check(actualImportedSpecFiles.isNotEmpty())
            val actualImportedSpecFileNames = actualImportedSpecFiles.map { it.specFileName }
            val approxImportedSpecFileNames = cvlSourceNameToImportDecls[cvlSource.name]
            if (approxImportedSpecFileNames == null || !approxImportedSpecFileNames.containsAll(
                    actualImportedSpecFileNames
                )
            ) {
                val missingImportDecls =
                    actualImportedSpecFileNames.minus(approxImportedSpecFileNames ?: emptyList())
                throw IllegalStateException(
                    "certoraBuild.py approximated $approxImportedSpecFileNames as the .spec files' " +
                            "'import' declarations in ${cvlSource.origpath}, but those are missing the " +
                            "actual declarations: $missingImportDecls (actual 'import' declarations: ${
                                actualImportedSpecFileNames
                            })"
                )
            }
            if (!actualImportedSpecFileNames.containsAll(approxImportedSpecFileNames)) {
                val spuriousImportDecls =
                    approxImportedSpecFileNames.minus(actualImportedSpecFileNames)
                throw IllegalStateException(
                    "certoraBuild.py approximated $approxImportedSpecFileNames as the .spec files' " +
                            "'import' declarations in ${cvlSource.origpath}, but those erroneously include ${
                                spuriousImportDecls
                            } (actual 'import' declarations: $actualImportedSpecFileNames)"
                )
            }
        }

        private fun addRepeatingIdsInDeclsErrors(
            repeatingIdsToDeclsLocs: Map<String, Set<Range>>,
            errorLogger: (String, Range) -> CVLError
        ): List<CVLError> {
            return repeatingIdsToDeclsLocs.flatMap { (repeatingId, declsLocs) ->
                declsLocs.map { range ->
                    errorLogger(
                        repeatingId,
                        range
                    )
                }
            }
        }

        private fun <T> addOverrideDeclsErrors(overrideDeclsToMatchingElems: Map<OverrideDeclaration<T>, Set<Range>>): List<CVLError> =
            overrideDeclsToMatchingElems.mapNotNull { (overrideDecl, matchingElemsLocs) ->
                if (matchingElemsLocs.isEmpty()) { // [overrideDecl] matches no imported elements
                    addUnresolvedOverrideDeclError(overrideDecl)
                } else if (matchingElemsLocs.size > 1) { // [overrideDecl] matches multiple imported elements
                    addAmbiguousOverrideDeclError(overrideDecl, matchingElemsLocs)
                } else {
                    null
                }
            }

        private fun addUnresolvedOverrideDeclError(unresolvedOverrideDecl: OverrideDeclaration<*>) =
            CVLError.General(
                unresolvedOverrideDecl.range,
                "Cannot resolve the 'override' declaration: '${
                    unresolvedOverrideDecl.materialize()
                }' does not match any imported spec element",
            )

        private fun addAmbiguousOverrideDeclError(
            ambiguousOverrideDecl: OverrideDeclaration<*>,
            overriddenAlternatives: Set<Range>
        ) =
            CVLError.General(
                ambiguousOverrideDecl.range,
                "Cannot resolve the 'override' declaration: '${
                    ambiguousOverrideDecl.materialize()
                }' matches multiple imported spec elements ${
                    overriddenAlternatives.joinToString(prefix = "(", separator = ", ", postfix = ")")
                }",
            )

        private fun addRepeatingIdInOverrideDeclsError(repeatingId: String, range: Range) =
            CVLError.General(
                range,
                repeatingIdInDeclsErrorMsg(repeatingId, "override"),
            )
    }
}
