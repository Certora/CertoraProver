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

package report.calltrace

import com.certora.collect.*
import datastructures.stdcollections.*
import spec.cvlast.*
import spec.cvlast.CVLType.PureCVLType.VMInternal
import utils.*
import java.math.BigInteger

/** Wraps existing types to allow custom String formatting implementations, independent of the existing implementations defined by the type. */
@KSerializable
@Treapable
sealed class CVLReportLabel : AmbiSerializable, HasRange {
    @KSerializable
    data class Message(val s: String, override val range: Range = Range.Empty()) : CVLReportLabel() {
        override fun toString() = s
    }

    @KSerializable
    data class Cmd(val cmd: CVLCmd.Simple) : CVLReportLabel() {
        override fun toString() = cmd.p()
        override val range get() = cmd.range
    }

    @KSerializable
    data class Exp(val exp: CVLExp) : CVLReportLabel() {
        override fun toString() = exp.p()
        override val range get() = exp.getRangeOrEmpty()
    }

    @KSerializable
    data class Return(val stmt: CVLCmd.Simple.Return) : CVLReportLabel() {
        override fun toString() = stmt.p()
        override val range get() = stmt.range
    }

    @KSerializable
    data class Revert(val stmt: CVLCmd.Simple.Revert) : CVLReportLabel() {
        override fun toString() = stmt.p()
        override val range get() = stmt.range
    }

    @KSerializable
    data class ApplyHook(val hookPatternString: String, override val range: Range) : CVLReportLabel() {
        override fun toString() = "Apply hook $hookPatternString"
    }

}

fun CVLCmd.Simple.p(): String =
    when (this) {
        is CVLCmd.Simple.Apply -> exp.p()
        is CVLCmd.Simple.Assert -> "assert ${exp.p()}"
        is CVLCmd.Simple.Satisfy -> "satisfy ${exp.p()}"
        is CVLCmd.Simple.AssumeCmd.Assume -> "require ${exp.p()}"
        is CVLCmd.Simple.AssumeCmd.AssumeInvariant -> "requireInvariant ${id}${params.join()}"
        is CVLCmd.Simple.Definition -> "${idL.singleOrJoin()} = ${exp.p()}"
        is CVLCmd.Simple.Havoc -> {
            val formattedTargets = targets.joinToString(separator = ", ") { it.asExp.p() }

            if (assumingExp != null) {
                "havoc $formattedTargets assuming ${assumingExp.p()}"
            } else {
                "havoc $formattedTargets"
            }
        }
        is CVLCmd.Simple.ResetStorage -> "reset storage ${exp.p()}"
        is CVLCmd.Simple.ResetTransientStorage -> "reset transient storage ${exp.p()}"
        is CVLCmd.Simple.Return -> if (exps.isNotEmpty()) {
            val returnOperand = if(exps.size == 1) {
                exps.single().p()
            } else {
                exps.joinToString(",", "(", ")") {
                    it.p()
                }
            }
            "return $returnOperand"
        } else { "return" }
        is CVLCmd.Simple.Exp -> exp.p()
        is CVLCmd.Simple.Declaration -> "${cvlType} ${id}"
        is CVLCmd.Simple.Label.End -> "end label"
        is CVLCmd.Simple.Label.Start -> "start label"
        is CVLCmd.Simple.Nop -> "nop"
        is CVLCmd.Simple.Revert -> "revert(${reason.orEmpty()})"
    }

private fun CVLExp.p(
    surroundingExpPrecedence: PrecedenceLevel = PrecedenceLevel.Exp,
    nestedLeft: Boolean = false
): String {
    return when (this) {
        is CVLExp.ApplyExp.ContractFunction.Concrete -> {
            buildString {
                append(methodIdWithCallContext.host.toString())
                append(".")

                val signatureAndArgs = formatFunctionSignatureAndArgs(methodIdWithCallContext, args, !noRevert, hasEnv())
                append(signatureAndArgs)

                if (isWhole) { append(" whole") }

                if (!storage.isLastStorage()) {
                    val store = storage.p(this@p.precedence)
                    append(" at $store")
                }
            }
        }
        is CVLExp.ApplyExp.ContractFunction.Symbolic -> {
            buildString {
                if (methodIdWithCallContext.host !is AllContracts) {
                    append(methodIdWithCallContext.host.toString())
                    append(".")
                }

                val signatureAndArgs = formatFunctionSignatureAndArgs(methodIdWithCallContext, args, !noRevert, hasEnv())
                append(signatureAndArgs)

                if (!storage.isLastStorage()) {
                    val store = storage.p(this@p.precedence)
                    append(" at $store")
                }
            }
        }
        is CVLExp.ApplyExp.Ghost -> "$methodIdWithCallContext$twoStateIndex${args.join()}"
        is CVLExp.ApplyExp.CVLFunction -> "${methodIdWithCallContext.printQualifiedFunctionName()}${if (noRevert) {""} else {"@withrevert"}}${args.join()}"
        is CVLExp.ApplyExp -> {
            "${methodIdWithCallContext.printQualifiedFunctionName()}${args.join()}"
        }
        is CVLExp.UnresolvedApplyExp -> {
            val prefix = if (base != null) {
                "${base.p(precedence, nestedLeft = true)}."
            } else {
                ""
            }

            return "$prefix$methodId${args.join()}"
        }
        is CVLExp.AddressFunctionCallExp -> {
            return "$base.$callableName${args.join()}"
        }
        is CVLExp.ArrayDerefExp ->
            /** passing [PrecedenceLevel.Exp] to the second p() call because the result is already in []s */
            "${array.p(precedence, nestedLeft = true)}[${index.p(PrecedenceLevel.Exp)}]"
        is CVLExp.ArrayLitExp -> elements.joinWithBrackets()

        is CVLExp.BinaryExp -> formatBinary(this)

        is CVLExp.CastExpr ->
            /** passing [PrecedenceLevel.Exp] to the second p() call because the result is already in []s */
            "${castType.str}_$toCastType(${arg.p(PrecedenceLevel.Exp)})"

        is CVLExp.CondExp -> "${c.p(precedence)} ? ${e1.p(precedence)} : ${e2.p(precedence)}"
        is CVLExp.Constant.BoolLit -> if (b == BigInteger.ONE) { "true" } else { "false" }
        is CVLExp.Constant.EnumConstant -> "$enumName.$memberName"

        // NumberLit has this smart "printHint" thing that we're using here
        // -- generally, all of this should ideally look as it did in the CVL source code, so we do want to take what
        // we can get from CVL Land here (ideal, from this perspective, would be if printhint would contain just the
        // unprocessed source code token)
        is CVLExp.Constant.NumberLit -> this.toString()

        is CVLExp.Constant.SignatureLiteralExp -> "sig:$function"
        is CVLExp.Constant.StringLit -> "\"$s\""
        is CVLExp.Constant.StructLit -> fieldNameToEntry.mapValues{ (_, v) -> v.p(precedence) }.toString()
        is CVLExp.FieldSelectExp ->
            // CVL grammar forces field selects to be left-nested
            "${structExp.p(precedence, nestedLeft = true)}.$fieldName"
        is CVLExp.QuantifierExp -> {
            val prefix = if (isForall) { "forall" } else { "exists" }
            "$prefix $qVarType $originalName. ${body.p(precedence)}"
        }
        is CVLExp.SumExp -> {
            "sum ${params.joinToString { "${it.type} ${it.id}" }}. ${body.p(precedence)}"
        }

        is CVLExp.RelopExp -> "${l.p(precedence, nestedLeft = true)} $relop ${r.p(precedence)}"

        is CVLExp.SetMemExp -> "${e.p(precedence, nestedLeft = true)} in ${set.p(precedence)}"
        is CVLExp.UnaryExp.BwNotExp -> "~${e.p(precedence)}"
        is CVLExp.UnaryExp.LNotExp -> "!(${e.p(precedence)})"
        is CVLExp.UnaryExp.UnaryMinusExp -> "-${e.p(precedence)}"
        is CVLExp.VariableExp -> "$originalName$twoStateIndex"
    }.let {
        addNecessaryParentheses(it, precedence, surroundingExpPrecedence, nestedLeft)
    }
}

/**
 * This mirrors the precedences in our CVL parsing as defined in `cvl.cup` -- the enum names are also taken from there.
 * Note that we use the `ordinal` field of this enum, so the order of enum items as written below counts.
 */
enum class PrecedenceLevel {
    // "Exp" level means the expression is not a subexpression of another expression
    // -- parentheses are handled by the surrounding construct
    Exp,
    Arrayexp,
    Quantexp,
    SumExp,
    Condexp,
    Iff,
    Impl,
    Disj,
    Conj,
    Relop,
    Bwor,
    Bwxor,
    Bwand,
    Bwshift,
    Additive,
    Multiplicative,
    Unary,
    Power,
    Setinc,
    Term,
    FieldAccess,
    ;
}
private val CVLExp.precedence: PrecedenceLevel get() = when (this) {
    is CVLExp.ArrayLitExp -> PrecedenceLevel.Arrayexp
    is CVLExp.QuantifierExp -> PrecedenceLevel.Quantexp
    is CVLExp.SumExp -> PrecedenceLevel.SumExp
    is CVLExp.CondExp -> PrecedenceLevel.Condexp
    is CVLExp.BinaryExp.IffExp -> PrecedenceLevel.Iff
    is CVLExp.BinaryExp.ImpliesExp -> PrecedenceLevel.Impl
    is CVLExp.BinaryExp.LorExp -> PrecedenceLevel.Disj
    is CVLExp.BinaryExp.LandExp -> PrecedenceLevel.Conj
    is CVLExp.RelopExp -> PrecedenceLevel.Relop
    is CVLExp.BinaryExp.BwOrExp -> PrecedenceLevel.Bwor
    is CVLExp.BinaryExp.BwXOrExp -> PrecedenceLevel.Bwxor
    is CVLExp.BinaryExp.BwAndExp -> PrecedenceLevel.Bwand
    is CVLExp.BinaryExp.BwLeftShiftExp,
    is CVLExp.BinaryExp.BwRightShiftExp,
    is CVLExp.BinaryExp.BwRightShiftWithZerosExp -> PrecedenceLevel.Bwshift
    is CVLExp.BinaryExp.AddExp,
    is CVLExp.BinaryExp.SubExp -> PrecedenceLevel.Additive
    is CVLExp.BinaryExp.MulExp,
    is CVLExp.BinaryExp.DivExp,
    is CVLExp.BinaryExp.ModExp -> PrecedenceLevel.Multiplicative
    is CVLExp.UnaryExp -> PrecedenceLevel.Unary
    is CVLExp.BinaryExp.ExponentExp -> PrecedenceLevel.Power
    is CVLExp.SetMemExp -> PrecedenceLevel.Setinc
    is CVLExp.Constant,
    is CVLExp.CastExpr,
    is CVLExp.ApplyExp,
    is CVLExp.AddressFunctionCallExp,
    is CVLExp.UnresolvedApplyExp -> PrecedenceLevel.Term
    is CVLExp.VariableExp,
    is CVLExp.FieldSelectExp,
    is CVLExp.ArrayDerefExp -> PrecedenceLevel.FieldAccess
}

private fun addNecessaryParentheses(
    expString: String,
    expPrecedence: PrecedenceLevel,
    surroundingExpPrecedence: PrecedenceLevel,
    nestedLeft: Boolean,
): String =
    if (expPrecedence < surroundingExpPrecedence || (expPrecedence == surroundingExpPrecedence && !nestedLeft)) {
        "($expString)"
    } else {
        expString
    }

private fun formatBinary(binExp: CVLExp.BinaryExp): String {
    val l = binExp.l.p(binExp.precedence, nestedLeft = true)
    val r = binExp.r.p(binExp.precedence)

    return when (binExp) {
        is CVLExp.BinaryExp.AddExp -> "$l + $r"
        is CVLExp.BinaryExp.BwAndExp -> "$l & $r"
        is CVLExp.BinaryExp.BwLeftShiftExp -> "$l << $r"
        is CVLExp.BinaryExp.BwOrExp -> "$l | $r"
        is CVLExp.BinaryExp.BwRightShiftExp -> "$l >> $r"
        is CVLExp.BinaryExp.BwRightShiftWithZerosExp -> "$l >>> $r"
        is CVLExp.BinaryExp.BwXOrExp -> "$l ^ $r"
        is CVLExp.BinaryExp.DivExp -> "$l / $r"
        is CVLExp.BinaryExp.ExponentExp -> "$l^$r"
        is CVLExp.BinaryExp.IffExp -> "$l <=> $r"
        is CVLExp.BinaryExp.ImpliesExp -> "$l => $r"
        is CVLExp.BinaryExp.LandExp -> "$l && $r"
        is CVLExp.BinaryExp.LorExp -> "$l || $r"
        is CVLExp.BinaryExp.ModExp -> "$l % $r"
        is CVLExp.BinaryExp.MulExp -> "$l * $r"
        is CVLExp.BinaryExp.SubExp -> "$l - $r"
    }
}

/**
 * if param names are available, the function is envfree and caller doesn't send calldataargs,
 * format as Foo.Bar(param0=arg0, param1=arg1) otherwise, format as Foo.Bar(arg0, arg1)
 */
private fun formatFunctionSignatureAndArgs(contractCall: ResolvedContractCall, args: List<CVLExp>, isRevert: Boolean, hasEnv: Boolean): String {
    val callee = if (isRevert) { contractCall.methodId + "@withrevert" } else { contractCall.methodId }

    val paramNames = contractCall.paramNames()

    val formattedArgs = if (paramNames == null || hasEnv || args.any(CVLExp::isCalldataArgs)) {
        args.join()
    } else {
        formatArgsWithNames(args, paramNames)
    }

    return "$callee$formattedArgs"
}

private fun formatArgsWithNames(args: List<CVLExp>, paramNames: List<String>): String {
    require(paramNames.size == args.size) {
        "number of params in function signature (${paramNames.size}) does not match number of args (${args.size})"
    }

    val formatNamed: (Pair<String, CVLExp>) -> String = { (name, arg) -> "$name=${arg.p()}" }
    return paramNames
        .zip(args)
        .joinToString(separator = ", ", prefix = "(", postfix = ")", transform = formatNamed)
}

private fun CVLExp.isCalldataArgs() = getCVLTypeOrNull() is VMInternal.RawArgs

/** gets the param names of a call, but only if all of them are available */
private fun ResolvedContractCall.paramNames(): List<String>? {
    val params = when (this) {
        is ConcreteMethod -> signature.params
        is UniqueMethod -> params
        is SymbolicContractMethod -> null
    }

    return params?.monadicMap { it.tryAs<VMParam.Named>()?.name }
}

private fun CVLExp.ApplyExp.ContractFunction.hasEnv() = tag.annotation?.tryAs<CallResolution>()?.hasEnv ?: false

private fun List<CVLExp>.join(): String =
    joinToString(separator = ", ", prefix = "(", postfix = ")", transform = CVLExp::p)

private fun List<CVLExp>.joinWithBrackets(): String =
    joinToString(separator = ", ", prefix = "[", postfix = "]", transform = CVLExp::p)

private fun CVLLhs.p(): String =
    when (this) {
        is CVLLhs.Array -> "${innerLhs.p()}[${index.p()}]"
        is CVLLhs.Id -> id
    }

private fun List<CVLLhs>.singleOrJoin(): String =
    when (size) {
        0 -> ""
        1 -> single().p()
        else -> joinToString(separator = ", ", prefix = "(", postfix = ")", transform = CVLLhs::p)
    }
