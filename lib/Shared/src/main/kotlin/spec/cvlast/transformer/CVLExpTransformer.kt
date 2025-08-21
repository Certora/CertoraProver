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

import datastructures.stdcollections.*
import spec.cvlast.CVLExp
import spec.cvlast.CVLLhs
import utils.CollectingResult
import utils.CollectingResult.Companion.bind
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
 * A [CVLExpTransformer] encapsulates a pass over the AST of an expression using the visitor pattern.
 * There is a method corresponding to each type of expression; this method should return a copy of the passed expression
 * with the appropriate transformations applied.
 *
 * The default implementations recursively visit all children of the provided expression, and update the expression with
 * the transformed children.
 *
 * @param E the error type for the returned [CollectingResult]s
 */
interface CVLExpTransformer<out E> {
    companion object {
        /** A transformer that copies the original expressions */
        fun copyTransformer() = object : CVLExpTransformer<Nothing> { }
    }

    fun lhs(lhs: CVLLhs): CollectingResult<CVLLhs, E> = when (lhs) {
        is CVLLhs.Array -> arrayLhs(lhs)
        is CVLLhs.Id -> idLhs(lhs)
    }

    fun idLhs(idLhs: CVLLhs.Id): CollectingResult<CVLLhs.Id, E> = idLhs.lift()

    fun expr(exp: CVLExp) = when (exp) {
        is CVLExp.RelopExp -> relop(exp)
        is CVLExp.BinaryExp.AddExp -> add(exp)
        is CVLExp.Constant -> const(exp)
        is CVLExp.BinaryExp.DivExp -> div(exp)
        is CVLExp.BinaryExp.ModExp -> mod(exp)
        is CVLExp.BinaryExp.ExponentExp -> exponent(exp)
        is CVLExp.BinaryExp.IffExp -> iff(exp)
        is CVLExp.BinaryExp.ImpliesExp -> implies(exp)
        is CVLExp.BinaryExp.BwOrExp -> bwor(exp)
        is CVLExp.BinaryExp.BwXOrExp -> bwxor(exp)
        is CVLExp.BinaryExp.BwAndExp -> bwand(exp)
        is CVLExp.BinaryExp.BwLeftShiftExp -> bwlsh(exp)
        is CVLExp.BinaryExp.BwRightShiftExp -> bwrsh(exp)
        is CVLExp.BinaryExp.BwRightShiftWithZerosExp -> bwrshwzeros(exp)
        is CVLExp.UnaryExp.BwNotExp -> bwnot(exp)

        is CVLExp.ApplicationExp -> application(exp)

        is CVLExp.BinaryExp.LandExp -> land(exp)
        is CVLExp.BinaryExp.LorExp -> lor(exp)
        is CVLExp.BinaryExp.MulExp -> mul(exp)
        is CVLExp.UnaryExp.LNotExp -> neg(exp)
        is CVLExp.QuantifierExp -> quant(exp)
        is CVLExp.SumExp -> sum(exp)
        is CVLExp.FieldSelectExp -> fieldSel(exp)
        is CVLExp.SetMemExp -> setmem(exp)
        is CVLExp.ArrayDerefExp -> arrayderef(exp)
        is CVLExp.BinaryExp.SubExp -> sub(exp)
        is CVLExp.UnaryExp.UnaryMinusExp -> unaryMinus(exp)
        is CVLExp.VariableExp -> variable(exp)
        is CVLExp.CondExp -> condexp(exp)
        is CVLExp.ArrayLitExp -> arrayexp(exp)
        is CVLExp.CastExpr -> castExpression(exp)
    }

    fun application(exp: CVLExp.ApplicationExp) = when(exp) {
        is CVLExp.ApplyExp -> applyExp(exp)
        is CVLExp.UnresolvedApplyExp -> unresolvedApplyExp(exp)
        is CVLExp.AddressFunctionCallExp -> addressFunctionCall(exp)
    }

    fun applyExp(exp: CVLExp.ApplyExp) = when (exp) {
        is CVLExp.ApplyExp.ContractFunction -> invokeExp(exp)
        is CVLExp.ApplyExp.Ghost -> ghost(exp)
        is CVLExp.ApplyExp.Definition -> definition(exp)
        is CVLExp.ApplyExp.CVLFunction -> call(exp)
        is CVLExp.ApplyExp.CVLBuiltIn -> builtin(exp)
    }

    fun builtin(exp: CVLExp.ApplyExp.CVLBuiltIn): CollectingResult<CVLExp.ApplyExp.CVLBuiltIn, E> {
        return exp.args.map {
            expr(it)
        }.flatten().map {
            exp.copy(args = it)
        }
    }

    fun relop(exp: CVLExp.RelopExp) = when (exp) {
        is CVLExp.RelopExp.ArithRelopExp -> arithRelop(exp)
        is CVLExp.RelopExp.EqExp -> eq(exp)
        is CVLExp.RelopExp.NeExp -> ne(exp)
    }

    fun arithRelop(exp: CVLExp.RelopExp.ArithRelopExp) = when (exp) {
        is CVLExp.RelopExp.ArithRelopExp.GeExp -> ge(exp)
        is CVLExp.RelopExp.ArithRelopExp.GtExp -> gt(exp)
        is CVLExp.RelopExp.ArithRelopExp.LeExp -> le(exp)
        is CVLExp.RelopExp.ArithRelopExp.LtExp -> lt(exp)
    }

    fun const(exp: CVLExp.Constant) = when (exp) {
        is CVLExp.Constant.BoolLit -> boolLit(exp)
        is CVLExp.Constant.NumberLit -> numberLit(exp)
        is CVLExp.Constant.StringLit -> stringLit(exp)
        is CVLExp.Constant.StructLit -> structLit(exp)
        is CVLExp.Constant.SignatureLiteralExp -> signatureLit(exp)
        is CVLExp.Constant.EnumConstant -> enumConstant(exp)
    }

    fun arrayLhs(arrayLhs: CVLLhs.Array) =
        lhs(arrayLhs.innerLhs).bind { lhs ->
            expr(arrayLhs.index).bind { index ->
                arrayLhs.copy(innerLhs = lhs, index = index).lift()
            }
        }

    fun unresolvedApplyExp(exp: CVLExp.UnresolvedApplyExp): CollectingResult<CVLExp.UnresolvedApplyExp, E> = collectingErrors {
        val args = collectAndFilter(exp.args.map(::expr))
        val base = exp.base?.let { bind(expr(it)) }
        return@collectingErrors exp.copy(args = args, base = base)
    }

    fun addressFunctionCall(exp: CVLExp.AddressFunctionCallExp): CollectingResult<CVLExp.AddressFunctionCallExp, E> = collectingErrors {
        val args = collectAndFilter(exp.args.map(::expr))
        val base = bind(expr(exp.base))
        return@collectingErrors exp.copy(args = args, base = base)
    }

    fun ge(exp: CVLExp.RelopExp.ArithRelopExp.GeExp): CollectingResult<CVLExp.RelopExp.ArithRelopExp.GeExp, E> =
        expr(exp.l).bind { l ->
            expr(exp.r).bind { r ->
                exp.copy(l = l, r = r, tag = exp.tag).lift()
            }
        }

    fun gt(exp: CVLExp.RelopExp.ArithRelopExp.GtExp): CollectingResult<CVLExp.RelopExp.ArithRelopExp.GtExp, E> =
        expr(exp.l).bind { l ->
            expr(exp.r).bind { r ->
                exp.copy(l = l, r = r, tag = exp.tag).lift()
            }
        }
    fun le(exp: CVLExp.RelopExp.ArithRelopExp.LeExp): CollectingResult<CVLExp.RelopExp.ArithRelopExp.LeExp, E> =
        expr(exp.l).bind { l ->
            expr(exp.r).bind { r ->
                exp.copy(l = l, r = r, tag = exp.tag).lift()
            }
        }
    fun lt(exp: CVLExp.RelopExp.ArithRelopExp.LtExp): CollectingResult<CVLExp.RelopExp.ArithRelopExp.LtExp, E> =
        expr(exp.l).bind { l ->
            expr(exp.r).bind { r ->
                exp.copy(l = l, r = r, tag = exp.tag).lift()
            }
        }
    fun eq(exp: CVLExp.RelopExp.EqExp): CollectingResult<CVLExp.RelopExp.EqExp, E> =
        expr(exp.l).bind { l ->
            expr(exp.r).bind { r ->
                exp.copy(l = l, r = r, tag = exp.tag).lift()
            }
        }

    fun ne(exp: CVLExp.RelopExp.NeExp): CollectingResult<CVLExp.RelopExp.NeExp, E> =
        expr(exp.l).bind { l ->
            expr(exp.r).bind { r ->
                exp.copy(l = l, r = r, tag = exp.tag).lift()
            }
        }

    fun add(exp: CVLExp.BinaryExp.AddExp): CollectingResult<CVLExp.BinaryExp.AddExp, E> =
        expr(exp.l).bind(expr(exp.r)) { l, r ->
            exp.copy(l = l, r = r, tag = exp.tag).lift()
        }

    fun enumConstant(exp: CVLExp.Constant.EnumConstant): CollectingResult<CVLExp, E> = exp.lift()

    fun boolLit(exp: CVLExp.Constant.BoolLit): CollectingResult<CVLExp.Constant.BoolLit, E> =
        exp.lift() // if no expr() calls, why copy? exp.copy(b = exp.b, tag = exp.tag).lift()

    fun numberLit(exp: CVLExp.Constant.NumberLit): CollectingResult<CVLExp.Constant.NumberLit, E> =
        exp.lift() // if no expr() calls, why copy? exp.copy(n = exp.n, tag = exp.tag).lift()

    fun stringLit(exp: CVLExp.Constant.StringLit): CollectingResult<CVLExp.Constant.StringLit, E> =
        exp.lift() // if no expr() calls, why copy? exp.copy(s = exp.s, tag = exp.tag).lift()

    @Suppress("NAME_SHADOWING")
    fun structLit(exp: CVLExp.Constant.StructLit): CollectingResult<CVLExp.Constant.StructLit, E> =
        exp.fieldNameToEntry.entries.map { (fieldName, entry) ->
            expr(entry).bind { entry ->
                (fieldName to entry).lift()
            }
        }.flatten().bind { fieldNameToEntry ->
            exp.copy(
                fieldNameToEntry = fieldNameToEntry.toMap(),
                tag = exp.tag
            ).lift()
        }

    fun signatureLit(exp: CVLExp.Constant.SignatureLiteralExp): CollectingResult<CVLExp.Constant.SignatureLiteralExp, E> =
        exp.copy(function = exp.function, tag = exp.tag).lift()

    fun ghost(exp: CVLExp.ApplyExp.Ghost): CollectingResult<CVLExp.ApplyExp.Ghost, E> =
        exp.args.map { arg -> expr(arg) }
            .flatten()
            .bind { args ->
                exp.copy(args = args, twoStateIndex = exp.twoStateIndex).lift()
            }

    fun castExpression(exp: CVLExp.CastExpr): CollectingResult<CVLExp.CastExpr, E> =
        expr(exp.arg).map {
            exp.copy(arg = it)
        }

    // return type changed from CVLExp.ApplyExp.Definition to CVLExp because some transformations generate
    // different type of CLVExp (as in inlining)
    fun definition(exp: CVLExp.ApplyExp.Definition): CollectingResult<CVLExp, E> =
        exp.args.map { arg ->
            expr(arg)
        }.flatten()
            .bind { args ->
                exp.copy(args = args).lift()
            }

    fun call(exp: CVLExp.ApplyExp.CVLFunction): CollectingResult<CVLExp.ApplyExp.CVLFunction, E> =
        exp.args.map { arg ->
            expr(arg)
        }.flatten()
            .bind { args ->
                exp.copy(args = args).lift()
            }

    fun div(exp: CVLExp.BinaryExp.DivExp): CollectingResult<CVLExp.BinaryExp.DivExp, E> =
        expr(exp.l).bind(expr(exp.r)) { l, r ->
            exp.copy(l = l, r = r, tag = exp.tag).lift()
        }

    fun mod(exp: CVLExp.BinaryExp.ModExp): CollectingResult<CVLExp.BinaryExp.ModExp, E> =
        expr(exp.l).bind(expr(exp.r)) { l, r ->
            exp.copy(l = l, r = r, tag = exp.tag).lift()
        }

    fun exponent(exp: CVLExp.BinaryExp.ExponentExp): CollectingResult<CVLExp.BinaryExp.ExponentExp, E> =
        expr(exp.l).bind(expr(exp.r)) { l, r ->
            exp.copy(l = l, r = r, tag = exp.tag).lift()
        }

    fun iff(exp: CVLExp.BinaryExp.IffExp): CollectingResult<CVLExp.BinaryExp.IffExp, E> =
        expr(exp.l).bind(expr(exp.r)) { l, r ->
            exp.copy(l = l, r = r, tag = exp.tag).lift()
        }

    fun implies(exp: CVLExp.BinaryExp.ImpliesExp): CollectingResult<CVLExp.BinaryExp.ImpliesExp, E> =
        expr(exp.l).bind(expr(exp.r)) { l, r ->
            exp.copy(l = l, r = r, tag = exp.tag).lift()
        }

    fun bwor(exp: CVLExp.BinaryExp.BwOrExp): CollectingResult<CVLExp.BinaryExp.BwOrExp, E> =
        expr(exp.l).bind(expr(exp.r)) { l, r ->
            exp.copy(l = l, r = r, tag = exp.tag).lift()
        }

    fun bwxor(exp: CVLExp.BinaryExp.BwXOrExp): CollectingResult<CVLExp.BinaryExp.BwXOrExp, E> =
        expr(exp.l).bind(expr(exp.r)) { l, r ->
            exp.copy(l = l, r = r, tag = exp.tag).lift()
        }

    fun bwand(exp: CVLExp.BinaryExp.BwAndExp): CollectingResult<CVLExp.BinaryExp.BwAndExp, E> =
        expr(exp.l).bind(expr(exp.r)) { l, r ->
            exp.copy(l = l, r = r, tag = exp.tag).lift()
        }

    fun bwlsh(exp: CVLExp.BinaryExp.BwLeftShiftExp): CollectingResult<CVLExp.BinaryExp.BwLeftShiftExp, E> =
        expr(exp.l).bind(expr(exp.r)) { l, r ->
            exp.copy(l = l, r = r, tag = exp.tag).lift()
        }

    fun bwrsh(exp: CVLExp.BinaryExp.BwRightShiftExp): CollectingResult<CVLExp.BinaryExp.BwRightShiftExp, E> =
        expr(exp.l).bind(expr(exp.r)) { l, r ->
            exp.copy(l = l, r = r, tag = exp.tag).lift()
        }

    fun bwrshwzeros(exp: CVLExp.BinaryExp.BwRightShiftWithZerosExp): CollectingResult<CVLExp.BinaryExp.BwRightShiftWithZerosExp, E> =
        expr(exp.l).bind(expr(exp.r)) { l, r ->
            exp.copy(l = l, r = r, tag = exp.tag).lift()
        }

    fun bwnot(exp: CVLExp.UnaryExp.BwNotExp): CollectingResult<CVLExp.UnaryExp.BwNotExp, E> =
        expr(exp.e).bind { e ->
            exp.copy(e = e, tag = exp.tag).lift()
        }

    fun invokeExp(exp: CVLExp.ApplyExp.ContractFunction): CollectingResult<CVLExp.ApplyExp.ContractFunction, E> =
        exp.args.map { arg -> expr(arg) }.flatten().bind { args ->
            when (exp) {
                is CVLExp.ApplyExp.ContractFunction.Concrete -> {
                    exp.copy(
                        methodIdWithCallContext = exp.methodIdWithCallContext,
                        args = args,
                        noRevert = exp.noRevert,
                        storage = exp.storage,
                        isWhole = exp.isWhole,
                        tag = exp.tag
                    ).lift()
                }
                is CVLExp.ApplyExp.ContractFunction.Symbolic -> {
                        exp.copy(
                            methodIdWithCallContext = exp.methodIdWithCallContext,
                            args = args,
                            noRevert = exp.noRevert,
                            storage = exp.storage,
                            tag = exp.tag
                        ).lift()
                }
            }
        }


    fun arrayexp(exp: CVLExp.ArrayLitExp): CollectingResult<CVLExp.ArrayLitExp, E> =
        exp.elements.map { element -> expr(element) }.flatten().bind { elements ->
            exp.copy(elements = elements, tag = exp.tag).lift()
        }

    fun condexp(exp: CVLExp.CondExp): CollectingResult<CVLExp.CondExp, E> =
        expr(exp.c).bind { c ->
            expr(exp.e1).bind { e1 ->
                expr(exp.e2).bind { e2 ->
                    exp.copy(c = c, e1 = e1, e2 = e2, tag = exp.tag).lift()
                }
            }
        }

    fun land(exp: CVLExp.BinaryExp.LandExp): CollectingResult<CVLExp.BinaryExp.LandExp, E> =
        expr(exp.l).bind(expr(exp.r)) { l, r ->
            exp.copy(l = l, r = r, tag = exp.tag).lift()
        }

    fun lor(exp: CVLExp.BinaryExp.LorExp): CollectingResult<CVLExp.BinaryExp.LorExp, E> =
        expr(exp.l).bind(expr(exp.r)) { l, r ->
            exp.copy(l = l, r = r, tag = exp.tag).lift()
        }

    fun mul(exp: CVLExp.BinaryExp.MulExp): CollectingResult<CVLExp.BinaryExp.MulExp, E> =
        expr(exp.l).bind(expr(exp.r)) { l, r ->
            exp.copy(l = l, r = r, tag = exp.tag).lift()
        }

    fun neg(exp: CVLExp.UnaryExp.LNotExp): CollectingResult<CVLExp.UnaryExp.LNotExp, E> =
        expr(exp.e).bind { e ->
            exp.copy(e = e, tag = exp.tag).lift()
        }

    // return type changed from CVLExp.QuantifierExp to CVLExp because some transformations generate different type of
    // CLVExp (as in inlining)
    fun quant(exp: CVLExp.QuantifierExp): CollectingResult<CVLExp, E> =
        expr(exp.body).bind { body ->
            exp.copy(
                isForall = exp.isForall,
                param = exp.param,
                body = body,
                tag = exp.tag
            ).lift()
        }

    fun sum(exp: CVLExp.SumExp): CollectingResult<CVLExp, E> =
        arrayderef(exp.body).bind { body ->
            exp.copy(
                params = exp.params,
                body = body,
                tag = exp.tag
            ).lift()
        }

    fun fieldSel(exp: CVLExp.FieldSelectExp): CollectingResult<CVLExp.FieldSelectExp, E> =
        expr(exp.structExp).bind { structExp ->
            exp.copy(structExp = structExp, fieldName = exp.fieldName, tag = exp.tag).lift()
        }

    fun setmem(exp: CVLExp.SetMemExp): CollectingResult<CVLExp.SetMemExp, E> =
        expr(exp.e).bind(expr(exp.set)) { e, set ->
            exp.copy(e = e, set = set, tag = exp.tag).lift()
        }

    fun arrayderef(exp: CVLExp.ArrayDerefExp): CollectingResult<CVLExp.ArrayDerefExp, E> =
        expr(exp.array).bind { array ->
            expr(exp.index).bind { index ->
                exp.copy(array = array, index = index, tag = exp.tag).lift()
            }
        }

    fun sub(exp: CVLExp.BinaryExp.SubExp): CollectingResult<CVLExp.BinaryExp.SubExp, E> =
        expr(exp.l).bind(expr(exp.r)) { l, r ->
            exp.copy(l = l, r = r, tag = exp.tag).lift()
        }

    fun unaryMinus(exp: CVLExp.UnaryExp.UnaryMinusExp): CollectingResult<CVLExp.UnaryExp.UnaryMinusExp, E> =
        expr(exp.e).bind { e ->
            exp.copy(e = e, tag = exp.tag).lift()
        }

    // return type changed from CVLExp.VariableExp to CVLExp because some overriding implementations generate
    // different types of CVLExp
    fun variable(exp: CVLExp.VariableExp): CollectingResult<CVLExp, E> = exp.lift()

}
