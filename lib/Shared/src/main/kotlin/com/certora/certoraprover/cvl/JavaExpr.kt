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

import config.Config
import spec.CVLCastFunction
import spec.CVLKeywords
import spec.TypeResolver
import spec.cvlast.*
import spec.cvlast.CVLExp.Constant.*
import spec.cvlast.CVLExp.Constant.SignatureLiteralExp
import spec.cvlast.CVLType.PureCVLType
import spec.cvlast.typechecker.CVLError
import spec.cvlast.typechecker.NoEnumMember
import spec.cvlast.typechecker.SumOnNonGhostVariable
import utils.CollectingResult
import utils.CollectingResult.Companion.asError
import utils.CollectingResult.Companion.flatten
import utils.CollectingResult.Companion.lift
import utils.CollectingResult.Companion.map
import utils.ErrorCollector.Companion.collectingErrors
import utils.Range
import utils.hash
import java.math.BigInteger

// This file contains the "Java" AST nodes for all expressions.  See README.md for information about the Java AST.

/** Super type for all the CVL expressions, e.g., [AddExp] etc. */
sealed class Exp(val range : Range, var hasParens: Boolean = false) : Kotlinizable<CVLExp> {
    fun addParens() { hasParens = true }
}

class ErrorExpr(override val error : CVLError) : Exp(error.location), ErrorASTNode<CVLExp>

sealed class BinaryExp(val l : Exp, val r : Exp, val g : Generator, range : Range) : Exp(range) {
    fun interface Generator {
        fun generate(l : CVLExp, r : CVLExp, tag : CVLExpTag) : CVLExp
    }

    override fun kotlinize(resolver : TypeResolver, scope : CVLScope) : CollectingResult<CVLExp, CVLError> = collectingErrors {
        map(l.kotlinize(resolver,scope), r.kotlinize(resolver, scope)) { l, r ->
            g.generate(l,r, CVLExpTag(scope, range, hasParens))
        }
    }
}

class AddExp          (l: Exp, r: Exp, range: Range) : BinaryExp(l, r, CVLExp.BinaryExp::AddExp, range)
class SubExp          (l: Exp, r: Exp, range: Range) : BinaryExp(l, r, CVLExp.BinaryExp::SubExp, range)
class ModExp          (l: Exp, r: Exp, range: Range) : BinaryExp(l, r, CVLExp.BinaryExp::ModExp, range)
class MulExp          (l: Exp, r: Exp, range: Range) : BinaryExp(l, r, CVLExp.BinaryExp::MulExp, range)
class LorExp          (l: Exp, r: Exp, range: Range) : BinaryExp(l, r, CVLExp.BinaryExp::LorExp, range)
class LandExp         (l: Exp, r: Exp, range: Range) : BinaryExp(l, r, CVLExp.BinaryExp::LandExp, range)
class BwLeftShiftExp  (l: Exp, r: Exp, range: Range) : BinaryExp(l, r, CVLExp.BinaryExp::BwLeftShiftExp, range)
class BwRightShiftExp (l: Exp, r: Exp, range: Range) : BinaryExp(l, r, CVLExp.BinaryExp::BwRightShiftExp, range)
class BwAndExp        (l: Exp, r: Exp, range: Range) : BinaryExp(l, r, CVLExp.BinaryExp::BwAndExp, range)
class BwOrExp         (l: Exp, r: Exp, range: Range) : BinaryExp(l, r, CVLExp.BinaryExp::BwOrExp, range)
class BwXOrExp        (l: Exp, r: Exp, range: Range) : BinaryExp(l, r, CVLExp.BinaryExp::BwXOrExp, range)
class ExponentExp     (l: Exp, r: Exp, range: Range) : BinaryExp(l, r, CVLExp.BinaryExp::ExponentExp, range)
class DivExp          (l: Exp, r: Exp, range: Range) : BinaryExp(l, r, CVLExp.BinaryExp::DivExp, range)
class IffExp          (l: Exp, r: Exp, range: Range) : BinaryExp(l, r, CVLExp.BinaryExp::IffExp, range)
class ImpliesExp      (l: Exp, r: Exp, range: Range) : BinaryExp(l, r, CVLExp.BinaryExp::ImpliesExp, range)
class BwRightShiftWithZerosExp (l: Exp, r: Exp, range: Range) : BinaryExp(l, r, CVLExp.BinaryExp::BwRightShiftWithZerosExp, range)

sealed class UnaryExp(val o: Exp, private val g: Generator, range: Range) : Exp(range) {
    fun interface Generator {
        fun generate(o: CVLExp, tag: CVLExpTag): CVLExp
    }

    override fun kotlinize(resolver: TypeResolver, scope: CVLScope): CollectingResult<CVLExp, CVLError>
        = o.kotlinize(resolver, scope).map { o: CVLExp -> g.generate(o, CVLExpTag(scope, range, hasParens)) }
}

class LNotExp  (_e: Exp, range: Range) : UnaryExp(_e, CVLExp.UnaryExp::LNotExp, range)
class BwNotExp (_e: Exp, range: Range) : UnaryExp(_e, CVLExp.UnaryExp::BwNotExp, range)

class UMinusExp(_exp: Exp, range: Range) : UnaryExp(_exp, CVLExp.UnaryExp::UnaryMinusExp, range) {
    override fun kotlinize(resolver: TypeResolver, scope: CVLScope): CollectingResult<CVLExp, CVLError> {
        return if (o is NumberExp) {
            NumberExp(o.n.negate(), range, o.printHint).kotlinize(resolver, scope)
        } else {
            super.kotlinize(resolver, scope)
        }
    }
}

sealed class ConstExp(range: Range) : Exp(range) {
    abstract fun asCVLConstant(scope: CVLScope): CVLExp.Constant
    override fun kotlinize(resolver: TypeResolver, scope: CVLScope): CollectingResult<CVLExp.Constant, CVLError>
        = asCVLConstant(scope).lift()
}

class BoolExp(_b: Boolean, range: Range) : ConstExp(range) {
    val b: BigInteger = if (_b) { BigInteger.ONE } else { BigInteger.ZERO }

    override fun asCVLConstant(scope: CVLScope): CVLExp.Constant {
        return BoolLit(b, CVLExpTag(scope, range, hasParens))
    }
}

/** A number literal  */
class NumberExp(val n: BigInteger, range : Range, val printHint: String) : ConstExp(range) {
    constructor(s: String, range: Range) : this(parse(s), range)
    private constructor(p: Pair<BigInteger, String>, range: Range) : this(p.first, range, p.second)

    companion object {
        fun parse(s: String): Pair<BigInteger, String> =
            CVLKeywords.constVals.compute(Config.VMConfig)[s]?.let { BigInteger(it) to s } // one of the "max_*" constants
                ?: @Suppress("ForbiddenMethodCall") if (s.startsWith("0x")) {
                    BigInteger(s.substring(2), 16) to "16" // a hex string
                } else {
                    BigInteger(s) to "10" // a base 10 string
                }
    }

    override fun toString() = "$n."
    override fun asCVLConstant(scope: CVLScope): CVLExp.Constant {
        return NumberLit(n, CVLExpTag(scope, range, hasParens), printHint)
    }
}

// TODO CERT-3750
class CastExpr(val castExpr: CVLCastFunction, val exp: Exp, range: Range) : Exp(range) {
    override fun kotlinize(resolver: TypeResolver, scope: CVLScope): CollectingResult<CVLExp, CVLError> {
        return exp.kotlinize(resolver, scope).map { e: CVLExp ->
            CVLExp.CastExpr(
                castExpr.targetCVLType,
                e,
                CVLExpTag(scope, range, hasParens),
                castExpr.castType
            )
        }
    }
}

class BifApplicationExpr(private val bifName: String, val args: List<Exp>, range: Range) : Exp(range) {
    override fun kotlinize(resolver: TypeResolver, scope: CVLScope): CollectingResult<CVLExp, CVLError> {
        return args.map {
            it.kotlinize(resolver, scope)
        }.flatten().map {
            CVLExp.ApplyExp.CVLBuiltIn(
                args = it,
                id = CVLBuiltInName.entries.single {
                    it.bifName == bifName
                },
                tag = CVLExpTag(scope, range, hasParens)
            )
        }
    }
}

class CondExp(val c: Exp, val e1: Exp, val e2: Exp, range: Range) : Exp(range) {
    override fun kotlinize(resolver: TypeResolver, scope: CVLScope): CollectingResult<CVLExp, CVLError> = collectingErrors {
        map(c.kotlinize(resolver, scope), e1.kotlinize(resolver, scope), e2.kotlinize(resolver, scope))
            { c: CVLExp, e1: CVLExp, e2: CVLExp -> CVLExp.CondExp(c, e1, e2, CVLExpTag(scope, range, hasParens)) }
    }
}


enum class ERelop(val symbol : String, val generator : BinaryExp.Generator) {
    LT("<",  CVLExp.RelopExp.ArithRelopExp::LtExp),
    LE("<=", CVLExp.RelopExp.ArithRelopExp::LeExp),
    GT(">",  CVLExp.RelopExp.ArithRelopExp::GtExp),
    GE(">=", CVLExp.RelopExp.ArithRelopExp::GeExp),
    EQ("==", CVLExp.RelopExp::EqExp),
    NE("!=", CVLExp.RelopExp::NeExp),
    ;

    companion object {
        @JvmStatic fun fromString(s: String) : ERelop
            = values().find { it.symbol == s } ?: throw IllegalArgumentException("Illegal relop $s")
    }
}

// TODO CERT-3750
class RelopExp(val relop: ERelop, val l: Exp, val r: Exp, range: Range) : Exp(range) {
    constructor(_relop: String, _l: Exp, _r: Exp, _range: Range) : this(ERelop.fromString(_relop), _l, _r, _range)
    override fun toString() = "$relop($l,$r)"

    override fun kotlinize(resolver: TypeResolver, scope: CVLScope): CollectingResult<CVLExp, CVLError> = collectingErrors {
        map(l.kotlinize(resolver, scope), r.kotlinize(resolver, scope)) { l, r ->
            relop.generator.generate(l, r, CVLExpTag(scope,range, hasParens))
        }
    }
}

class VariableExp(val id: String, val annotation: Annotation, range: Range) : Exp(range) {
    constructor(_id: String, range: Range) : this(_id, Annotation.NONE, range)

    enum class Annotation(val kotlinized : TwoStateIndex) {
        // two state indices for ghosts and variables
        OLD(TwoStateIndex.OLD),
        NEW(TwoStateIndex.NEW),
        NONE(TwoStateIndex.NEITHER)
    }

    val isOldVersion: Boolean
        get() = annotation == Annotation.OLD
    val isNewVersion: Boolean
        get() = annotation == Annotation.NEW

    override fun equals(other: Any?)
        = other is VariableExp && other.annotation == this.annotation && other.id == this.id

    override fun hashCode(): Int = hash { it + annotation + id }

    override fun toString() = id

    override fun kotlinize(resolver: TypeResolver, scope: CVLScope): CollectingResult<CVLExp.VariableExp, CVLError> {
        return CVLExp.VariableExp(id, CVLExpTag(scope, range, hasParens), annotation.kotlinized).lift()
    }

    companion object {
        @JvmStatic fun oldVariable(_id: String, range: Range) = VariableExp(_id, Annotation.OLD, range)
        @JvmStatic fun newVariable(_id: String, range: Range) = VariableExp(_id, Annotation.NEW, range)
    }
}

class ArrayLitExp(val a: List<Exp>, range: Range) : Exp(range) {
    override fun kotlinize(resolver: TypeResolver, scope: CVLScope): CollectingResult<CVLExp, CVLError> {
        return a.map { it.kotlinize(resolver, scope) }.flatten().map { CVLExp.ArrayLitExp(it, CVLExpTag(scope, range, hasParens)) }
    }
}

// TODO CERT-3750
/** Represents array dereferences */
class ArrayDerefExp(val ad: Exp, val indx: Exp, range: Range) : Exp(range) {
    override fun kotlinize(resolver: TypeResolver, scope: CVLScope): CollectingResult<CVLExp, CVLError> = collectingErrors {
        map(ad.kotlinize(resolver, scope), indx.kotlinize(resolver, scope)) { ad, indx ->
            CVLExp.ArrayDerefExp(ad, indx, CVLExpTag(scope, range, hasParens))
        }
    }
}

/** Represents access to a struct field. E.g. "msg.value", or "userStruct.field" */
class FieldSelectExp(val b: Exp, val m: String, range: Range) : Exp(range) {
    override fun kotlinize(resolver: TypeResolver, scope: CVLScope): CollectingResult<CVLExp, CVLError> {
        /*
         * Enum constants have the form `ContractName.EnumName.MemberName`.
         *
         * This gets parsed as `FieldSelectExp(FieldSelectExp(VariableExp(ContractName) EnumName) MemberName)`
         *
         * The following nested ifs check for this case, and if that's really what we have just return
         * an `CVLExp.Constant.EnumConstant`.
         */
        if (b is FieldSelectExp && b.b is VariableExp) {
            val type = resolver.resolveCVLType(b.b.id, b.m).resultOrNull()
            if (type is PureCVLType.Enum) {
                return if (type.elements.contains(m)) {
                    EnumConstant(
                        SolidityContract(b.b.id),
                        type.name,
                        m,
                        CVLExpTag(scope, range, hasParens)
                    ).lift()
                } else {
                    NoEnumMember(range, type, m).asError()
                }
            }
        }
        val originalRange = range
        return b.kotlinize(resolver, scope).map { CVLExp.FieldSelectExp(it, m, CVLExpTag(scope, originalRange, hasParens)) }
    }

    override fun toString() = "$b.$m"
}


class QuantifierExp(var is_forall: Boolean, val param: CVLParam, val body: Exp, range: Range) : Exp(range) {
    override fun kotlinize(resolver: TypeResolver, scope: CVLScope): CollectingResult<CVLExp, CVLError> = collectingErrors {
        val _param = param.kotlinize(resolver, scope)
        val _body  = body.kotlinize(resolver, scope)
        map(_param, _body) { param, body -> CVLExp.QuantifierExp(is_forall, param, body, CVLExpTag(scope, range, hasParens))}
    }
}

class SumExp(val params: List<CVLParam>, val body: Exp, range: Range, private val isUnsigned: Boolean) : Exp(range) {
    override fun kotlinize(resolver: TypeResolver, scope: CVLScope): CollectingResult<CVLExp, CVLError> = collectingErrors {
        val _params = params.kotlinize(resolver, scope)
        val _body  = body.kotlinize(resolver, scope)
        bind(_params, _body) { params, body ->
            if (body is CVLExp.ArrayDerefExp) {
                CVLExp.SumExp(params, body, isUnsigned, CVLExpTag(scope, range, hasParens)).lift()
            } else {
                SumOnNonGhostVariable(body).asError()
            }
        }
    }
}


// TODO CERT-3750: could be Binop
/** Note: `SetMem` stands for "set membership" not "set memory".  This is used for `f.selector in Contract` expressions */
class SetMemExp(val e: Exp, val set: Exp, range: Range) : Exp(range) {
    override fun kotlinize(resolver: TypeResolver, scope: CVLScope): CollectingResult<CVLExp, CVLError> = collectingErrors {
        map(e.kotlinize(resolver, scope), set.kotlinize(resolver, scope)) { e: CVLExp, set: CVLExp ->
            CVLExp.SetMemExp(e, set, CVLExpTag(scope, range, hasParens))
        }
    }
}

// TODO CERT-3750 This could be a ConstExp
class SignatureLiteralExp(
    range: Range,
    _methodReference: MethodReferenceExp,
    paramTypes: List<VMParam>
) : Exp(range) {
    private val methodSig = MethodSig(range, _methodReference, paramTypes, ArrayList())

    override fun toString() = "sig:$methodSig"

    override fun kotlinize(resolver: TypeResolver, scope: CVLScope): CollectingResult<CVLExp, CVLError>
        = methodSig.kotlinize(resolver, scope).map() { sig -> SignatureLiteralExp(sig, CVLExpTag(scope, range, hasParens)) }
}

// TODO CERT-3750 This could be a ConstExp
class StringExp(_s: String, range: Range) : Exp(range) {
    // TODO CERT-3748
    // remove quotes
    val s: String
        = if (_s[0] == '"' && _s[_s.length - 1] == '"') {
            _s.substring(1, _s.length - 1)
        } else {
            _s
        }

    override fun toString() = "\"$s\""

    override fun kotlinize(resolver: TypeResolver, scope: CVLScope): CollectingResult<CVLExp, CVLError>
        = StringLit(s, CVLExpTag(scope, range, hasParens)).lift()
}


class UnresolvedApplyExp(@JvmField var base: Exp?, val method: String, val args: List<Exp>, val annotation: Annotation, val storage: VariableExp, range: Range) : Exp(range) {
    override fun kotlinize(resolver: TypeResolver, scope: CVLScope): CollectingResult<CVLExp.UnresolvedApplyExp, CVLError> = collectingErrors {
        val ind = when(annotation) {
            Annotation.NEW -> TwoStateIndex.NEW
            Annotation.OLD -> TwoStateIndex.OLD
            else -> TwoStateIndex.NEITHER
        }

        return@collectingErrors map(
            args.map { it.kotlinize(resolver, scope) }.flatten(),
            storage.kotlinize(resolver, scope),
            base?.kotlinize(resolver, scope) ?: null.lift()
        ) { args: List<CVLExp>, storage: CVLExp.VariableExp, base: CVLExp? ->
            CVLExp.UnresolvedApplyExp(
                base,
                method,
                args,
                CVLExpTag(scope, range, hasParens),
                ind,
                storage,
                invokeIsSafe = annotation != Annotation.WITHREVERT,
                false
            )
        }
    }

    enum class Annotation {
        // two state indices for ghosts and variables
        OLD,
        NEW,

        // to make an invoke "safe"
        NOREVERT,
        WITHREVERT,
        NONE
    }
}
