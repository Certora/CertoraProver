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

package spec.cvlast.transformer

import spec.cvlast.*
import spec.rules.*
import utils.CollectingResult
import utils.CollectingResult.Companion.bind
import utils.CollectingResult.Companion.bindMany
import utils.CollectingResult.Companion.flatten
import utils.CollectingResult.Companion.lift
import utils.CollectingResult.Companion.map
import utils.ErrorCollector.Companion.collectingErrors

// TODO: CERT-2401
// This (and other MonadicTransformers) could be auto-generated.  This would have a few benefits:
//   - reduce and simplify the codebase, increasing consistency
//   - adapt to changes in the AST; adding new types or new fields would automatically update the transformer
//   - maybe reduce memory consumption (by applying only-copy-on-change transformation)
// See `mike/transformers-generate` for a sketch of an implementation



/**
 * A [CVLAstTransformer] encapsulates a pass over the AST of a spec file using the visitor pattern.
 * There is a method corresponding to each type in the AST; this method should return a copy of the passed AST node
 * with the appropriate transformations applied.
 *
 * The default implementations recursively visit all children of the provided node, and update the node with the
 * transformed children.
 *
 * @param E the error type for the returned [CollectingResult]s
 * @param cmdTransformer is used to transform the nested commands in the AST
 * @param expTransformer is used to transform the nested expressions in the AST
 */
open class CVLAstTransformer<E>(
    private val cmdTransformer: CVLCmdTransformer<E>,
    private val expTransformer: CVLExpTransformer<E> = cmdTransformer.expTransformer
) {

    open fun block(block: List<CVLCmd>): CollectingResult<List<CVLCmd>, E> =
        block.map { cmd -> cmdTransformer.cmd(cmd) }.flatten()

    open fun hook(hook: CVLHook): CollectingResult<CVLHook, E> =
            block(hook.block).bind(hookPattern(hook.pattern)) { block, pattern ->
                hook.copy(block = block, pattern = pattern).lift()
            }

    open fun hooks(hooks: List<CVLHook>) = hooks.map(::hook).flatten()

    private fun hookPattern(pattern: CVLHookPattern): CollectingResult<CVLHookPattern, E> = when (pattern) {
        is GeneratedOpcodeHook -> GeneratedHookHelpers.mapPattern(pattern, ::namedVMParam)
        is CVLHookPattern.StoragePattern.Load -> {
            namedVMParam(pattern.value).bind(slotPattern(pattern.slot)) {  value, slot ->
                pattern.copy(
                        value = value,
                        slot = slot
                ).lift()
            }
        }
        is CVLHookPattern.StoragePattern.Store -> {
            namedVMParam(pattern.value).bind(
                    pattern.previousValue?.let(::namedVMParam)?: null.lift(),
                    slotPattern(pattern.slot)) { value, previousValue, slot ->
                        pattern.copy(
                                value = value,
                                previousValue = previousValue,
                                slot = slot
                        ).lift()

            }
        }
        is CVLHookPattern.Create -> namedVMParam(pattern.value).bind { value ->
            pattern.copy(value = value).lift()
        }
    }

    private fun slotPattern(pattern: CVLSlotPattern): CollectingResult<CVLSlotPattern, E> = when (pattern) {
        is CVLSlotPattern.ArrayAccess -> slotPattern(pattern.base).bind(namedVMParam(pattern.index)) { base, index ->
            CVLSlotPattern.ArrayAccess(base = base, index = index).lift()
        }
        is CVLSlotPattern.MapAccess -> slotPattern(pattern.base).bind(namedVMParam(pattern.key)) { base, key ->
            CVLSlotPattern.MapAccess(base = base, key = key).lift()
        }
        is CVLSlotPattern.StructAccess -> slotPattern(pattern.base).bind { base ->
            CVLSlotPattern.StructAccess(base = base, offset = pattern.offset).lift()
        }
        is CVLSlotPattern.FieldAccess -> slotPattern(pattern.base).bind { base ->
            CVLSlotPattern.FieldAccess(base = base, field = pattern.field).lift()
        }
        is CVLSlotPattern.Static -> pattern.lift()
    }

    @Suppress("NAME_SHADOWING")
    open fun rule(rule: ICVLRule): CollectingResult<ICVLRule, E> = when (rule) {
        is CVLSingleRule -> rule(rule)
        is GroupRule -> rule.rules.map { rule -> rule(rule) }.flatten().bind { rules ->
            rule.copy(rules = rules).lift()
        }
        is AssertRule -> rule.lift()
        is StaticRule -> rule.lift()
    }

    open fun rules(rules: List<ICVLRule>) = rules.map(::rule).flatten()

    open fun param(param: CVLParam): CollectingResult<CVLParam, E> = param.lift()

    open fun rule(rule: CVLSingleRule): CollectingResult<CVLSingleRule, E> =
        block(rule.block).bind(
            methodParamFilters(rule.methodParamFilters),
            rule.params.map { param -> param(param) }.flatten()) { block, methodParamFilters, params ->
        rule.copy(
            block = block,
            methodParamFilters = methodParamFilters,
            params = params
        ).lift()
    }

    open fun sub(sub: CVLFunction): CollectingResult<CVLFunction, E> =
            block(sub.block)
                    .bind(sub.params.map { param -> param(param) }.flatten())
                    { block, params ->
                        sub.copy(
                                block = block,
                                params = params).lift()
                    }

    open fun subs(subs: List<CVLFunction>) = subs.map(::sub).flatten()

    open fun preserved(preserved: CVLPreserved): CollectingResult<CVLPreserved, E> =
            block(preserved.block)
                    .bind(preserved.withParams.map(::param).flatten()) { block, withParams ->
                        when (preserved) {
                            is CVLPreserved.Generic -> preserved.copy(block = block, withParams = withParams).lift()
                            is CVLPreserved.Fallback -> preserved.copy(block = block, withParams = withParams).lift()
                            is CVLPreserved.TransactionBoundary -> preserved.copy(block = block, withParams = withParams).lift()
                            is CVLPreserved.ExplicitMethod -> preserved.methodSignature.params.map(::vmParam).flatten().bind { params: List<VMParam> ->
                                preserved.copy(block = block, withParams = withParams, methodSignature = preserved.methodSignature.withParameters(newParams = params)).lift()
                            }
                        }
                    }

    open fun vmParam(param: VMParam) : CollectingResult<VMParam, E> = when(param) {
        is VMParam.Named -> namedVMParam(param)
        is VMParam.Unnamed -> unnamedVMParam(param)
    }

    open fun unnamedVMParam(param: VMParam.Unnamed): CollectingResult<VMParam, E> = param.lift()

    open fun namedVMParam(param: VMParam.Named): CollectingResult<VMParam.Named, E> = param.lift()

    @Suppress("NAME_SHADOWING")
    open fun inv(inv: CVLInvariant): CollectingResult<CVLInvariant, E> =
            inv.proof.preserved.map(::preserved).flatten()
                .bind { preserved ->
                    inv.proof.copy(preserved = preserved).lift()
                }.bind(expTransformer.expr(inv.exp)) { proof, exp ->
                    inv.copy(exp = exp, proof = proof).lift()
                }.bind(inv.params.map(::param).flatten(), methodParamFilters(inv.methodParamFilters)) { inv, params, methodParamFilters ->
                    inv.copy(methodParamFilters = methodParamFilters, params = params).lift()
                }

    open fun invs(invs: List<CVLInvariant>) = invs.map(::inv).flatten()

    open fun ghost(ghost: CVLGhostDeclaration): CollectingResult<CVLGhostDeclaration, E> =
        (ghost as? CVLGhostWithAxiomsAndOldCopy)?.axioms?.map { axiom ->
            expTransformer.expr(axiom.exp).bind { exp ->
                axiom.mapExp { _ -> exp }.lift()
            }
        }?.flatten()?.bind { axioms ->
            (ghost.withAxioms(axioms) as CVLGhostDeclaration).lift()
        } ?: ghost.lift()

    open fun ghosts(ghosts: List<CVLGhostDeclaration>) = ghosts.map(::ghost).flatten()

    open fun def(def: CVLDefinition): CollectingResult<CVLDefinition, E> =
            expTransformer.expr(def.body).bind(def.params.map(::param).flatten()) { body, params ->
                def.copy(body = body, params = params).lift()
            }

    open fun defs(defs: List<CVLDefinition>): CollectingResult<List<CVLDefinition>, E> = defs.map { def -> def(def) }.flatten()

    open fun importedMethod(
        importedMethod: ConcreteMethodBlockAnnotation
    ): CollectingResult<ConcreteMethodBlockAnnotation, E> = collectingErrors {
        val newSummary = when (val summary = importedMethod.summary) {
            is SpecCallSummary.Exp -> {
                val exp        = expTransformer.expr(summary.exp)
                val withClause = summary.withClause?.let { withClause ->
                    param(withClause.param).map { SpecCallSummary.Exp.WithClause(it, withClause.range) }
                } ?: null.lift()
                val funParams = summary.funParams.map(::vmParam).flatten()
                map(exp, withClause, funParams) { _exp, _withClause, _funParams ->
                    summary.copy(exp = _exp, withClause = _withClause, funParams = _funParams)
                }
            }
            is SpecCallSummary.Always -> {
                summary.copy(exp = bind(expTransformer.expr(summary.exp)))
            }
            else -> summary
        }
        return@collectingErrors importedMethod.copy(summary = newSummary)
    }

    open fun importedMethods(importedMethods: List<MethodBlockEntry>) : CollectingResult<List<MethodBlockEntry>, E> = importedMethods.map {
        when(it) {
            is CatchAllSummaryAnnotation -> importedMethod(it)
            is ConcreteMethodBlockAnnotation -> importedMethod(it)
            is CatchUnresolvedSummaryAnnotation -> importedMethod(it)
        }
    }.flatten()

    open fun importedMethod(importedMethod: CatchAllSummaryAnnotation): CollectingResult.Result<CatchAllSummaryAnnotation> = importedMethod.lift()

    open fun importedMethod(importedMethod: CatchUnresolvedSummaryAnnotation): CollectingResult.Result<CatchUnresolvedSummaryAnnotation> = importedMethod.lift()

    open fun ast(ast: CVLAst): CollectingResult<CVLAst, E> {
        val importedMethods = importedMethods(ast.importedMethods)
        val rules = rules(ast.rules)
        val subs = subs(ast.subs)
        val invs = invs(ast.invs)
        val ghosts = ghosts(ast.ghosts)
        val defs = defs(ast.definitions)
        val hooks = hooks(ast.hooks)
        return bindMany(importedMethods, rules, subs, invs, ghosts, defs, hooks) {
            CVLAst(
                importedMethods = importedMethods.force(),
                rules = rules.force(),
                subs = subs.force(),
                invs = invs.force(),
                ghosts = ghosts.force(),
                definitions = defs.force(),
                hooks = hooks.force(),
                importedContracts = ast.importedContracts,
                importedSpecFiles = ast.importedSpecFiles,
                overrideDeclarations = ast.overrideDeclarations,
                scope = ast.scope,
                sorts = ast.sorts,
                useDeclarations = ast.useDeclarations
            ).lift()
        }
    }


    @Suppress("NAME_SHADOWING")
    open fun methodParamFilters(methodParamFilters: MethodParamFilters): CollectingResult<MethodParamFilters, E> =
            methodParamFilters.methodParamToFilter.entries.map { (variable, filter) ->
                expTransformer.expr(filter.filterExp).bind { filterExp ->
                    filter.copy(filterExp = filterExp).lift()
                }.bind { filter ->
                    (variable to filter).lift()
                }
            }.flatten().bind { paramToFilterEntries ->
                methodParamFilters.copy(
                        methodParamToFilter = paramToFilterEntries.associate { it },
                ).lift()
            }
}
