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

import spec.rules.CVLSingleRule
import spec.rules.GroupRule
import spec.rules.ICVLRule
import utils.CollectingResult
import utils.CollectingResult.Companion.lift

/**
 * Traverses the rules in the ast and marks as [SpecType.Single.SkippedMissingOptionalMethod] all rules that reference
 * functions marked as "optional" and indeed aren't in the provided contract.
 */
class SkipOptionalRules(val symbolTable: CVLSymbolTable) {
    private inner class VirtualFuncInvocationFinder : CVLCmdFolder<SpecType.Single.SkippedMissingOptionalMethod?>() {
        override fun cvlExp(acc: SpecType.Single.SkippedMissingOptionalMethod?, exp: CVLExp): SpecType.Single.SkippedMissingOptionalMethod? {
            val finder = object : CVLExpFolder<SpecType.Single.SkippedMissingOptionalMethod?>() {
                override fun variable(acc: SpecType.Single.SkippedMissingOptionalMethod?, exp: CVLExp.VariableExp): SpecType.Single.SkippedMissingOptionalMethod? = acc

                override fun unresolvedApply(
                    acc: SpecType.Single.SkippedMissingOptionalMethod?,
                    exp: CVLExp.UnresolvedApplyExp
                ): SpecType.Single.SkippedMissingOptionalMethod {
                    check(exp.tag.annotation == CVLExp.UnresolvedApplyExp.VirtualFunc) {
                        "We shouldn't reach here with an unresolved apply unless it's for virtual funcs"
                    }
                    return SpecType.Single.SkippedMissingOptionalMethod(
                        exp.callableName,
                        (exp.base as? CVLExp.VariableExp)?.id ?: CurrentContract.name
                    )
                }
                override fun call(
                    acc: SpecType.Single.SkippedMissingOptionalMethod?,
                    exp: CVLExp.ApplyExp.CVLFunction
                ): SpecType.Single.SkippedMissingOptionalMethod? {
                    val funcInfo = symbolTable.lookUpFunctionLikeSymbol((exp as CVLExp.ApplyExp).callableName, exp.getScope())?.symbolValue as? CVLFunction
                    check(funcInfo != null) {
                        "Expected to find CVL function ${exp.callableName} in the symbol table"
                    }

                    // Recursively look for these invocations within CVL function calls
                    return funcInfo.block.firstNotNullOfOrNull { virtualFuncInvocationFinder.cmd(null, it) }
                }
            }
            return finder.expr(acc, exp)
        }

        override fun lhs(acc: SpecType.Single.SkippedMissingOptionalMethod?, lhs: CVLLhs): SpecType.Single.SkippedMissingOptionalMethod? = acc

        override fun message(acc: SpecType.Single.SkippedMissingOptionalMethod?, message: String): SpecType.Single.SkippedMissingOptionalMethod? = acc

    }

    private val virtualFuncInvocationFinder = VirtualFuncInvocationFinder()


    private fun skipMissingOptionalMethod(rule: CVLSingleRule): ICVLRule =
        // One missing function is enough for us to skip the rule, hence the `firstNotNull`
        rule.block.firstNotNullOfOrNull { virtualFuncInvocationFinder.cmd(null, it) }?.let {
            // OK, this rule invokes a non-existent function, so let's mark it by changing the rule type.
            rule.copy(ruleType = it)
        } ?: rule

    private fun skipMissingOptionalMethod(rule: ICVLRule) : ICVLRule {
        return when (rule) {
            is CVLSingleRule -> skipMissingOptionalMethod(rule)
            is GroupRule -> rule.copy(rules = rule.rules.map { skipMissingOptionalMethod(it) })
            else -> rule
        }
    }

    fun filterRules(ast: CVLAst): CollectingResult<CVLAst, Nothing> {
        return ast.copy(
            rules = ast.rules.map { skipMissingOptionalMethod(it) }
        ).lift()
    }
}
