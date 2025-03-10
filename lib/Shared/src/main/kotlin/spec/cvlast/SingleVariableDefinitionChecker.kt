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

package spec.cvlast

import spec.CVLKeywords
import spec.cvlast.transformer.CVLAstTransformer
import spec.cvlast.transformer.CVLCmdTransformer
import spec.cvlast.transformer.CVLExpTransformer
import utils.CollectingResult
import utils.CollectingResult.Companion.asError
import utils.CollectingResult.Companion.map
import utils.CollectingResult.Companion.ok
import utils.ErrorCollector.Companion.collectingErrors
import utils.VoidResult
import datastructures.stdcollections.*
import spec.CVLKeywords.Companion.CURRENT_CONTRACT
import spec.cvlast.typechecker.*
import utils.CollectingResult.Companion.lift
import utils.arrayDequeOf

/**
 * Checks that every variable usage adheres to the following:
 *  - Undeclared variables are not used
 *  - Variables cannot be declared twice (no shadowing)
 *  - Once used, a variable cannot be assigned to
 *  - Each variable is either defined after both branches of an `if` or undefined after both branches
 *  - Keywords are not (re)defined
 *
 * Note: wildcards ("_") and ghosts can be redefined at any time
 */
class SingleVariableDefinitionChecker {

    // Note: this class only defines the traversals; the variable states and transitions are managed in [VarDefState]
    private val variables = VarDefState()

    private val expTransformer = object : CVLExpTransformer<CVLError> {

        // Note: this is a `use` rather than an `assign` because [CVLLhs] is used in places other than just definitions
        // (e.g. as an array base or havoc variable).  The call to `assign` happens in [cmdTransformer.def]
        override fun idLhs(idLhs: CVLLhs.Id): CollectingResult<CVLLhs.Id, CVLError>
            = variables.use(idLhs.id, idLhs.cvlRange).map { idLhs }

        override fun variable(exp: CVLExp.VariableExp): CollectingResult<CVLExp, CVLError>
            = variables.use(exp.id, exp.tag.cvlRange).map { exp }

        override fun quant(exp: CVLExp.QuantifierExp): CollectingResult<CVLExp, CVLError>
            = defineAndCheckCVL(listOf(exp.param)) { super.quant(exp) }

        override fun sum(exp: CVLExp.SumExp): CollectingResult<CVLExp, CVLError>
            = defineAndCheckCVL(exp.params) { super.sum(exp) }
    }

    private val cmdTransformer = object : CVLCmdTransformer<CVLError>(expTransformer) {
        override fun def(cmd: CVLCmd.Simple.Definition): CollectingResult<CVLCmd, CVLError> = collectingErrors {
            // check rhs before defining the variable (since the variable shouldn't be used in its own definition)
            val newRhs = expTransformer.expr(cmd.exp)

            if (cmd.type != null) {
                // something like `mathint x = e`
                cmd.definedVariables.forEach { collect(variables.declareLocal(it.id, cmd.cvlRange)) }
            }
            cmd.definedVariables.forEach { collect(variables.assign(it.id, it.cvlRange)) }

            // if the lhs is just an identifier, we want to leave it unused, but otherwise we need to traverse its components.
            val newLhs = collectAndFilter(cmd.idL.map { (it as? CVLLhs.Id)?.lift() ?: expTransformer.lhs(it) })

            cmd.copy(exp = bind(newRhs), idL = newLhs)
        }

        // Note: this doesn't include things like `mathint x = 5`; these are `def`s rather than `decl`s
        override fun decl(cmd: CVLCmd.Simple.Declaration): CollectingResult<CVLCmd, CVLError>
            = variables.declareLocal(cmd.id, cmd.cvlRange).map { cmd }

        override fun ifCmd(cmd: CVLCmd.Composite.If): CollectingResult<CVLCmd, CVLError> = collectingErrors {
            // check the condition
            val _condResult = expTransformer.expr(cmd.cond)

            // check the then branch
            variables.push()
            val _thenResult = cmd(cmd.thenCmd)
            val thenSnapshot = variables.pop()

            // check the else branch
            variables.push()
            val _elseResult = cmd(cmd.elseCmd)
            val elseSnapshot = variables.pop()

            collect(variables.combineBranches(cmd.cvlRange, thenSnapshot, elseSnapshot))

            return@collectingErrors map(_condResult, _thenResult, _elseResult) { condResult, thenCmd, elseCmd ->
                cmd.copy(cond = condResult, thenCmd = thenCmd, elseCmd = elseCmd)
            }
        }

        override fun blockCmd(cmd: CVLCmd.Composite.Block): CollectingResult<CVLCmd, CVLError> = collectingErrors {
            variables.push()
            val blockResult = super.blockCmd(cmd)
            val blockSnapshot = variables.pop()

            variables.mergeSubscope(blockSnapshot)

            return@collectingErrors bind(blockResult)
        }
    }

    private val astTransformer = object : CVLAstTransformer<CVLError>(cmdTransformer) {

        override fun rule(rule: CVLSingleRule): CollectingResult<CVLSingleRule, CVLError>
            = defineAndCheckCVL(rule.params) { super.rule(rule) }

        override fun sub(sub: CVLFunction): CollectingResult<CVLFunction, CVLError>
            = defineAndCheckCVL(sub.params) { super.sub(sub) }

        override fun hook(hook: CVLHook): CollectingResult<CVLHook, CVLError> {
            variables.push()
            variables.registerKeyword(CVLKeywords.executingContract.keyword)
            val cb : () -> CollectingResult<CVLHook, CVLError> = {
                defineAndCheckVM(hook.pattern.definedVariables) { super.hook(hook) }
            }
            return (if(hook.pattern is OpcodeHookWithEnv) {
                defineAndCheckVM(hook.pattern.environmentParams(), cb)
            } else {
                cb()
            }).also { variables.pop() }
        }

        override fun def(def: CVLDefinition): CollectingResult<CVLDefinition, CVLError>
            = defineAndCheckCVL(def.params) { super.def(def) }

        override fun preserved(preserved: CVLPreserved): CollectingResult<CVLPreserved, CVLError>
            = defineAndCheckCVL(preserved.params + preserved.withParams) { super.preserved(preserved) }

        override fun inv(inv: CVLInvariant): CollectingResult<CVLInvariant, CVLError> {
            // We need to check the filters separately because filtered blocks for rules expect the filter variables to
            // be defined, while filtered blocks for invariants expect the filter variables to be fresh.  Therefore, we
            // define the filter variables in the scope where the filters are checked.  This isn't quite right in the
            // case that we have multiple filters (since all filter variables are defined in all expressions), but I
            // think multiple filters are disallowed for invariants anyway

            return defineAndCheckCVL(
                params    = inv.params,
                checkBody = { collectingErrors {
                    val preserved = collectAndFilter(inv.proof.preserved.map(::preserved))
                    val _expr     = expTransformer.expr(inv.exp)
                    val _filters  = defineAndCheck(
                        params   = inv.methodParamFilters.methodParamToFilter.keys.toList().map { it to inv.methodParamFilters.cvlRange },
                        checkBody = { methodParamFilters(inv.methodParamFilters) }
                    )
                    return@collectingErrors map(_expr, _filters) { expr, filters ->
                        inv.copy(proof = inv.proof.copy(preserved = preserved), exp = expr, methodParamFilters = filters)
                    }
                }}
            )
        }

        override fun ast(ast: CVLAst): CollectingResult<CVLAst, CVLError> = collectingErrors {
            CVLKeywords.values()
                .filter { it.alwaysDefined }
                .forEach { variables.registerKeyword(it.keyword) }

            variables.registerKeyword(CURRENT_CONTRACT)
            variables.registerWildcard()

            ast.importedContracts
                .forEach { collect(variables.declareLocal(it.solidityContractVarId, it.cvlRange)) }

            ast.ghosts
                .forEach { collect(variables.declareGhost(it.id, it.cvlRange)) }

            return@collectingErrors bind(super.ast(ast))
        }

        override fun importedMethod(importedMethod: ConcreteMethodBlockAnnotation): CollectingResult<ConcreteMethodBlockAnnotation, CVLError> = collectingErrors {
            variables.push()
            variables.registerKeyword(CVLKeywords.calledContract.keyword)
            variables.registerKeyword(CVLKeywords.executingContract.keyword)

            val result = defineAndCheckVM(
                params    = importedMethod.methodParameterSignature.params.filterIsInstance<VMParam.Named>(),
                checkBody = {
                    (importedMethod.summary as? SpecCallSummary.Exp)?.withClause?.let {
                        collect(variables.declareLocal(it.param.id, it.param.range))
                        collect(variables.assign(it.param.id, it.param.range))
                    }

                    super.importedMethod(importedMethod)
                }
            )
            variables.pop()
            return@collectingErrors bind(result)
        }
    }

    /** @return the result of [checkBody] in a scope where [params] are declared and defined. */
    private fun<T> defineAndCheckCVL(params : List<CVLParam>, checkBody : () -> CollectingResult<T,CVLError>) : CollectingResult<T,CVLError>
        = defineAndCheck(params.map { it.id to it.range}, checkBody)

    /** @return the result of [checkBody] in a scope where [params] are declared and defined. */
    private fun<T> defineAndCheckVM(params : List<VMParam.Named>, checkBody : () -> CollectingResult<T,CVLError>) : CollectingResult<T,CVLError>
        = defineAndCheck(params.map { it.id to it.range }, checkBody)

    /** @return the result of [checkBody] in a scope where [params] are declared and defined. */
    private fun <T> defineAndCheck(
        params : List<Pair<String, CVLRange>>,
        checkBody : () -> CollectingResult<T,CVLError>
    ) : CollectingResult<T, CVLError> = collectingErrors {
        variables.push()
        params.forEach { (param, loc) ->
            collect(variables.declareLocal(param, loc))
            collect(variables.assign(param, loc))
        }
        val checkedBody = checkBody()
        // if a local variable was used in the body, we need to keep it marked as used.  This doesn't merge in the
        // quantifier variable because it wasn't defined before we pushed.
        variables.mergeSubscope(variables.pop())
        return@collectingErrors bind(checkedBody)
    }

    fun check(ast: CVLAst): CollectingResult<CVLAst,CVLError> {
        return astTransformer.ast(ast)
    }
}

// VARIABLE STATE MANAGEMENT ///////////////////////////////////////////////////////////////////////////////////////

/**
 * Utility class to manage the states of variables.  Variables are updated by calling methods like [declareLocal], [assign],
 * etc.  These methods update the internal symbol table appropriately, returning a [CVLError] if the variable is used
 * improperly.
 */
private class VarDefState {
    /** Set up a CVL keyword */
    fun registerKeyword(id : String) { this.variableState[id] = Keyword }

    /** Set up the wildcard */
    fun registerWildcard() { variableState[CVLKeywords.wildCardExp.keyword] = Wildcard }

    /** Declare a ghost variable */
    fun declareGhost(id : String, location : CVLRange): VoidResult<CVLError> {
        return declareLocal(id, location).map { variableState[id] = Ghost(location) }
    }

    /** Declare a variable (e.g. `mathint x` or `mathint x = 5`) */
    fun declareLocal(id: String, location: CVLRange): VoidResult<CVLError> {
        return when(val previous = variableState[id]) {
            null        -> ok.also { variableState[id] = Local(location) }
            is Keyword  -> markError(id, DeclaredKeyword(id, location))
            is Local    -> markError(id, DuplicateDeclaration(id, location, previous.declaration))
            is Ghost    -> markError(id, DuplicateDeclaration(id, location, previous.declaration))

            is Error, is Wildcard -> ok
        }
    }

    /** Assign to a variable (e.g. `x = 5` or `mathint x = 5`) */
    fun assign(id: String, location: CVLRange): VoidResult<CVLError> {
        return when(val previous = variableState[id]) {
            null        -> markError(id, UndeclaredVariable(id, location))
            is Keyword  -> markError(id, AssignedToKeyword(id, location))
            is Local    -> previous.use?.let      { markError(id, AssignedAfterUse(id, location, it)) }
                                       ?: ok.also { variableState[id] = previous.copy(definition = location) }

            is Ghost, is Wildcard, is Error -> ok
        }
    }

    /** Mark a variable as having been read. */
    fun use(id: String, location: CVLRange): VoidResult<CVLError> {
        return when(val previous = variableState[id]) {
            null     -> markError(id, UndeclaredVariable(id, location))
            is Local -> ok.also { variableState[id] = previous.copy(use = location) }

            is Ghost, is Keyword, is Wildcard, is Error -> ok
        }
    }

    /**
     * Collect results of merging `then` and `else` blocks.  Ensures that each local variable declared in this scope is
     * either defined in both branches, or undefined in both branches.  Updates the current state with the most recent
     * definitions and uses of preexisting variables.
     */
    fun combineBranches(
        location: CVLRange,
        thenBlock : Map<String, VariableDefState?>,
        elseBlock : Map<String, VariableDefState?>,
    ): VoidResult<CVLError> = collectingErrors {
        variableState.entries.forEach { (id, initState) ->
            // Only predefined local variables are interesting
            if (initState !is Local) { return@forEach }

            // Local variables stay local (or become errors)
            val thenState = thenBlock[id] as? Local ?: return@forEach
            val elseState = elseBlock[id] as? Local ?: return@forEach

            if (thenState.definition != null && elseState.definition == null) {
                collectError(InconsistentVariableDefinition(id, location, thenState.definition, definedInThenBlock = true))
            }
            if (thenState.definition == null && elseState.definition != null) {
                collectError(InconsistentVariableDefinition(id, location, elseState.definition, definedInThenBlock = false))
            }

            variableState[id] = initState.copy(definition = elseState.definition, use = elseState.use ?: thenState.use)
        }
    }

    /** Update the local variables that are defined in `this` with metadata from [popped] */
    fun mergeSubscope(popped : Map<String, VariableDefState?>)
        = variableState.keys.forEach { id -> popped[id]?.takeIf { it is Local }?.let { variableState[id] = it } }

    /** Begin a new scope, retaining all the bindings from the current scope */
    fun push() {
        // Note: [toMutableMap] creates a copy
        scopeStack.add(variableState.toMutableMap())
    }

    /**
     * Restore the scope from a previous [push], returns the removed scope.
     * If the enclosing scope defined a variable and the popped scope used it or marked it with an error, the
     * state is propagated to the enclosing scope.
     */
    fun pop() : Map<String, VariableDefState?> {
        return variableState.also { popped ->
            variableState = scopeStack.removeLast()
            variableState.keys.forEach { id ->
                popped[id].takeIf { it is Error }?.let { variableState[id] = it }
            }
        }
    }

    sealed class VariableDefState

    private data class Local (
        val declaration : CVLRange,
        val definition  : CVLRange? = null,
        val use         : CVLRange? = null,
    ) : VariableDefState()

    private class  Ghost(val declaration : CVLRange) : VariableDefState()
    private object Keyword                           : VariableDefState() // variable is a (currently defined) keyword
    private object Wildcard                          : VariableDefState() // variable is "_"
    private object Error                             : VariableDefState() // we've already encountered an error; no need to report again

    /** Mark a variable as having already been reported as an error, to avoid duplicates */
    private fun markError(id : String, error : CVLError) : CollectingResult<Nothing, CVLError> {
        variableState[id] = Error
        return error.asError()
    }

    /** Note: `null` represents an undeclared variable */
    private var variableState = mutableMapOf<String, VariableDefState?>()

    /** A stack of copies of the [variableState] mapping */
    private val scopeStack    = arrayDequeOf<MutableMap<String,VariableDefState?>>()

}
