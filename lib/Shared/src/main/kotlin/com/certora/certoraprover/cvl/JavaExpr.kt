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
import datastructures.stdcollections.*
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
import java.math.BigInteger

// This file contains the "Java" AST nodes for all expressions.  See README.md for information about the Java AST.

/** Super type for all the CVL expressions, e.g., [AddExp] etc. */
sealed class Exp : Kotlinizable<CVLExp> {
    /**
     * if this expression is surrounded by a pair of parenthesis.
     * note that it may have additional (redundant) pairs.
     *
     * note that this doesn't impl [OptionalParenthesis] for now
     * due to different semantics, but maybe it should
     */
    var hasParenthesis = false
        private set

    abstract override val range : Range

    /**
     * changing [Exp.hasParenthesis] means that the range of the expression itself also changes.
     * therefore this method takes [parensRange] and updates the class accordingly,
     * returning the same subclass as the calling class.
     *
     * impl: the parser constructs expressions inside-out:
     * it first constructs them without the surrounding parenthesis (if any),
     * and "discovers" the parenthesis in later production rules.
     * we therefore construct expressions with [hasParenthesis] as false, and update here.
     */
    fun withParenthesis(parensRange: Range.Range): Exp {
        // we keep only the range of the outermost pair of parenthesis.
        // this may not be the only pair.
        val after = this.withRange(parensRange)

        check(this::class == after::class) { "class of $this changed" }
        after.hasParenthesis = true

        return after
    }

    fun isTwoStateVariable(): Boolean {
        val variable = this as? VariableExp ?: return false
        return when (variable.annotation) {
            VariableExp.Annotation.OLD, VariableExp.Annotation.NEW -> true
            VariableExp.Annotation.NONE -> false
        }
    }
}

data class ErrorExpr(override val error : CVLError) : Exp(), ErrorASTNode<CVLExp> {
    override val range: Range get() = error.location
}

sealed class BinaryExp : Exp() {
    abstract override val range : Range

    abstract val l: Exp
    abstract val r: Exp
    abstract val g: Generator

    fun interface Generator {
        fun generate(l : CVLExp, r : CVLExp, tag : CVLExpTag) : CVLExp
    }

    override fun kotlinize(resolver : TypeResolver, scope : CVLScope) : CollectingResult<CVLExp, CVLError> = collectingErrors {
        map(l.kotlinize(resolver,scope), r.kotlinize(resolver, scope)) { l, r ->
            g.generate(l,r, CVLExpTag(scope, range, hasParenthesis))
        }
    }
}

data class AddExp(override val l: Exp, override val r: Exp, override val range: Range) : BinaryExp() {
    override val g: Generator get() = Generator(CVLExp.BinaryExp::AddExp)
}
data class SubExp(override val l: Exp, override val r: Exp, override val range: Range) : BinaryExp() {
    override val g: Generator get() = Generator(CVLExp.BinaryExp::SubExp)
}
data class ModExp(override val l: Exp, override val r: Exp, override val range: Range) : BinaryExp() {
    override val g: Generator get() = Generator(CVLExp.BinaryExp::ModExp)
}
data class MulExp(override val l: Exp, override val r: Exp, override val range: Range) : BinaryExp() {
    override val g: Generator get() = Generator(CVLExp.BinaryExp::MulExp)
}
data class LorExp(override val l: Exp, override val r: Exp, override val range: Range) : BinaryExp() {
    override val g: Generator get() = Generator(CVLExp.BinaryExp::LorExp)
}
data class LandExp(override val l: Exp, override val r: Exp, override val range: Range) : BinaryExp() {
    override val g: Generator get() = Generator(CVLExp.BinaryExp::LandExp)
}
data class BwLeftShiftExp(override val l: Exp, override val r: Exp, override val range: Range) : BinaryExp() {
    override val g: Generator get() = Generator(CVLExp.BinaryExp::BwLeftShiftExp)
}
data class BwRightShiftExp(override val l: Exp, override val r: Exp, override val range: Range) : BinaryExp() {
    override val g: Generator get() = Generator(CVLExp.BinaryExp::BwRightShiftExp)
}
data class BwAndExp(override val l: Exp, override val r: Exp, override val range: Range) : BinaryExp() {
    override val g: Generator get() = Generator(CVLExp.BinaryExp::BwAndExp)
}
data class BwOrExp(override val l: Exp, override val r: Exp, override val range: Range) : BinaryExp() {
    override val g: Generator get() = Generator(CVLExp.BinaryExp::BwOrExp)
}
data class BwXOrExp(override val l: Exp, override val r: Exp, override val range: Range) : BinaryExp() {
    override val g: Generator get() = Generator(CVLExp.BinaryExp::BwXOrExp)
}
data class ExponentExp(override val l: Exp, override val r: Exp, override val range: Range) : BinaryExp() {
    override val g: Generator get() = Generator(CVLExp.BinaryExp::ExponentExp)
}
data class DivExp(override val l: Exp, override val r: Exp, override val range: Range) : BinaryExp() {
    override val g: Generator get() = Generator(CVLExp.BinaryExp::DivExp)
}
data class IffExp(override val l: Exp, override val r: Exp, override val range: Range) : BinaryExp() {
    override val g: Generator get() = Generator(CVLExp.BinaryExp::IffExp)
}
data class ImpliesExp(override val l: Exp, override val r: Exp, override val range: Range) : BinaryExp() {
    override val g: Generator get() = Generator(CVLExp.BinaryExp::ImpliesExp)
}
data class BwRightShiftWithZerosExp(override val l: Exp, override val r: Exp, override val range: Range) : BinaryExp() {
    override val g: Generator get() = Generator(CVLExp.BinaryExp::BwRightShiftWithZerosExp)
}

sealed class UnaryExp : Exp() {
    abstract override val range: Range

    abstract val o: Exp
    abstract val g: Generator

    fun interface Generator {
        fun generate(o: CVLExp, tag: CVLExpTag): CVLExp.UnaryExp
    }

    override fun kotlinize(resolver: TypeResolver, scope: CVLScope): CollectingResult<CVLExp, CVLError>
        = o.kotlinize(resolver, scope).map { o: CVLExp -> g.generate(o, CVLExpTag(scope, range, hasParenthesis)) }
}

data class LNotExp(override val o: Exp, override val range: Range) : UnaryExp() {
    override val g: Generator get() = Generator(CVLExp.UnaryExp::LNotExp)
}
data class BwNotExp(override val o: Exp, override val range: Range) : UnaryExp() {
    override val g: Generator get() = Generator(CVLExp.UnaryExp::BwNotExp)
}
data class UMinusExp(override val o: Exp, override val range: Range) : UnaryExp() {
    override val g: Generator get() = Generator(CVLExp.UnaryExp::UnaryMinusExp)
    override fun kotlinize(resolver: TypeResolver, scope: CVLScope): CollectingResult<CVLExp, CVLError> {
        return if (o is NumberExp) {
            val literal = "-${o.n}" // a potentially-lossy approximation of the original
            NumberExp.newUnchecked(literal, o.n.negate(), range, o.printHint).kotlinize(resolver, scope)
        } else {
            super.kotlinize(resolver, scope)
        }
    }
}

sealed class ConstExp : Exp() {
    abstract override val range: Range
    abstract fun asCVLConstant(scope: CVLScope): CVLExp.Constant
    override fun kotlinize(resolver: TypeResolver, scope: CVLScope): CollectingResult<CVLExp.Constant, CVLError>
        = asCVLConstant(scope).lift()
}

data class BoolExp(val asBoolean: Boolean, override val range: Range) : ConstExp() {
    val asBigInt: BigInteger get() = if (asBoolean) { BigInteger.ONE } else { BigInteger.ZERO }

    override fun asCVLConstant(scope: CVLScope): CVLExp.Constant {
        return BoolLit(asBigInt, CVLExpTag(scope, range, hasParenthesis))
    }
}

/** A number literal  */
data class NumberExp(val literal: String, val n: BigInteger, override val range : Range, val printHint: String) : ConstExp() {
    companion object {
        @JvmStatic fun parse(s: String, range: Range): NumberExp {
            val (n, printHint) = CVLKeywords.constVals.compute(Config.VMConfig)[s]?.let { BigInteger(it) to s } // one of the "max_*" constants
                ?: @Suppress("ForbiddenMethodCall") if (s.startsWith("0x")) {
                    BigInteger(s.substring(2), 16) to "16" // a hex string
                } else {
                    BigInteger(s) to "10" // a base 10 string
                }

            return newUnchecked(s, n, range, printHint)
        }

        internal fun newUnchecked(literal: String, n: BigInteger, range : Range, printHint: String) =
            NumberExp(literal, n, range, printHint)
    }

    override fun toString() = "$n."
    override fun asCVLConstant(scope: CVLScope): CVLExp.Constant {
        return NumberLit(n, CVLExpTag(scope, range, hasParenthesis), printHint)
    }
}

// TODO CERT-3750
data class CastExpr(val castExpr: CVLCastFunction, val exp: Exp, override val range: Range) : Exp() {
    override fun kotlinize(resolver: TypeResolver, scope: CVLScope): CollectingResult<CVLExp, CVLError> {
        return exp.kotlinize(resolver, scope).map { e: CVLExp ->
            CVLExp.CastExpr(
                castExpr.targetCVLType,
                e,
                CVLExpTag(scope, range, hasParenthesis),
                castExpr.castType
            )
        }
    }
}

data class BifApplicationExpr(internal val bifName: String, val args: List<Exp>, override val range: Range) : Exp() {
    override fun kotlinize(resolver: TypeResolver, scope: CVLScope): CollectingResult<CVLExp, CVLError> {
        return args.map {
            it.kotlinize(resolver, scope)
        }.flatten().map {
            CVLExp.ApplyExp.CVLBuiltIn(
                args = it,
                id = CVLBuiltInName.entries.single {
                    it.bifName == bifName
                },
                tag = CVLExpTag(scope, range, hasParenthesis)
            )
        }
    }
}

data class CondExp(val c: Exp, val e1: Exp, val e2: Exp, override val range: Range) : Exp() {
    override fun kotlinize(resolver: TypeResolver, scope: CVLScope): CollectingResult<CVLExp, CVLError> = collectingErrors {
        map(c.kotlinize(resolver, scope), e1.kotlinize(resolver, scope), e2.kotlinize(resolver, scope))
            { c: CVLExp, e1: CVLExp, e2: CVLExp -> CVLExp.CondExp(c, e1, e2, CVLExpTag(scope, range, hasParenthesis)) }
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
data class RelopExp(val relop: ERelop, val l: Exp, val r: Exp, override val range: Range) : Exp() {
    constructor(_relop: String, _l: Exp, _r: Exp, _range: Range) : this(ERelop.fromString(_relop), _l, _r, _range)
    override fun toString() = "$relop($l,$r)"

    override fun kotlinize(resolver: TypeResolver, scope: CVLScope): CollectingResult<CVLExp, CVLError> = collectingErrors {
        map(l.kotlinize(resolver, scope), r.kotlinize(resolver, scope)) { l, r ->
            relop.generator.generate(l, r, CVLExpTag(scope,range, hasParenthesis))
        }
    }
}

data class VariableExp(val id: String, val annotation: Annotation, override val range: Range) : Exp() {
    constructor(_id: String, range: Range) : this(_id, Annotation.NONE, range)
    constructor(kw: CVLKeywords, range: Range) : this(kw.keyword, Annotation.NONE, range)

    enum class Annotation(val kotlinized : TwoStateIndex) {
        // two state indices for ghosts and variables
        OLD(TwoStateIndex.OLD),
        NEW(TwoStateIndex.NEW),
        NONE(TwoStateIndex.NEITHER)
    }

    override fun toString() = id

    override fun kotlinize(resolver: TypeResolver, scope: CVLScope): CollectingResult<CVLExp.VariableExp, CVLError> {
        return CVLExp.VariableExp(id, CVLExpTag(scope, range, hasParenthesis), annotation.kotlinized).lift()
    }

    companion object {
        @JvmStatic fun oldVariable(_id: String, range: Range) = VariableExp(_id, Annotation.OLD, range)
        @JvmStatic fun newVariable(_id: String, range: Range) = VariableExp(_id, Annotation.NEW, range)
    }
}

data class ArrayLitExp(val a: List<Exp>, override val range: Range) : Exp() {
    override fun kotlinize(resolver: TypeResolver, scope: CVLScope): CollectingResult<CVLExp, CVLError> {
        return a.map { it.kotlinize(resolver, scope) }.flatten().map { CVLExp.ArrayLitExp(it, CVLExpTag(scope, range, hasParenthesis)) }
    }
}

// TODO CERT-3750
/** Represents array dereferences */
data class ArrayDerefExp(val ad: Exp, val indx: Exp, override val range: Range) : Exp() {
    override fun kotlinize(resolver: TypeResolver, scope: CVLScope): CollectingResult<CVLExp, CVLError> = collectingErrors {
        map(ad.kotlinize(resolver, scope), indx.kotlinize(resolver, scope)) { ad, indx ->
            CVLExp.ArrayDerefExp(ad, indx, CVLExpTag(scope, range, hasParenthesis))
        }
    }
}

/** Represents access to a struct field. E.g. "msg.value", or "userStruct.field" */
data class FieldSelectExp(val b: Exp, val m: String, override val range: Range) : Exp() {
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
                        CVLExpTag(scope, range, hasParenthesis)
                    ).lift()
                } else {
                    NoEnumMember(range, type, m).asError()
                }
            }
        }
        val originalRange = range
        return b.kotlinize(resolver, scope).map { CVLExp.FieldSelectExp(it, m, CVLExpTag(scope, originalRange, hasParenthesis)) }
    }

    override fun toString() = "$b.$m"
}


data class QuantifierExp(val is_forall: Boolean, val param: CVLParam, val body: Exp, override val range: Range) : Exp() {
    override fun kotlinize(resolver: TypeResolver, scope: CVLScope): CollectingResult<CVLExp, CVLError> = collectingErrors {
        val _param = param.kotlinize(resolver, scope)
        val _body  = body.kotlinize(resolver, scope)
        map(_param, _body) { param, body -> CVLExp.QuantifierExp(is_forall, param, body, CVLExpTag(scope, range, hasParenthesis))}
    }
}

data class SumExp(val params: List<CVLParam>, val body: Exp, override val range: Range, internal val isUnsigned: Boolean) : Exp() {
    override fun kotlinize(resolver: TypeResolver, scope: CVLScope): CollectingResult<CVLExp, CVLError> = collectingErrors {
        val _params = params.kotlinize(resolver, scope)
        val _body  = body.kotlinize(resolver, scope)
        bind(_params, _body) { params, body ->
            if (body is CVLExp.ArrayDerefExp) {
                CVLExp.SumExp(params, body, isUnsigned, CVLExpTag(scope, range, hasParenthesis)).lift()
            } else {
                SumOnNonGhostVariable(body).asError()
            }
        }
    }
}


// TODO CERT-3750: could be Binop
/** Note: `SetMem` stands for "set membership" not "set memory".  This is used for `f.selector in Contract` expressions */
data class SetMemExp(val e: Exp, val set: Exp, override val range: Range) : Exp() {
    override fun kotlinize(resolver: TypeResolver, scope: CVLScope): CollectingResult<CVLExp, CVLError> = collectingErrors {
        map(e.kotlinize(resolver, scope), set.kotlinize(resolver, scope)) { e: CVLExp, set: CVLExp ->
            CVLExp.SetMemExp(e, set, CVLExpTag(scope, range, hasParenthesis))
        }
    }
}

// TODO CERT-3750 This could be a ConstExp
data class SignatureLiteralExp(val methodSig: MethodSig) : Exp() {
    constructor(range: Range, methodReference: MethodReferenceExp, paramTypes: List<VMParam>) : this(MethodSig(range, methodReference, paramTypes, emptyList()))

    override val range: Range = methodSig.range
    override fun toString() = "sig:$methodSig"

    override fun kotlinize(resolver: TypeResolver, scope: CVLScope): CollectingResult<CVLExp, CVLError>
        = methodSig.kotlinize(resolver, scope).map() { sig -> SignatureLiteralExp(sig, CVLExpTag(scope, range, hasParenthesis)) }
}

// TODO CERT-3750 This could be a ConstExp
data class StringExp(val s: String, override val range: Range) : Exp() {
    fun withoutQuotes() = s.removeSurrounding("\"")

    override fun toString() = '\"' + this.withoutQuotes() + '\"'

    override fun kotlinize(resolver: TypeResolver, scope: CVLScope): CollectingResult<CVLExp, CVLError>
        = StringLit(this.withoutQuotes(), CVLExpTag(scope, range, hasParenthesis)).lift()
}


data class UnresolvedApplyExp(@JvmField val base: Exp?, val method: String, val args: List<Exp>, val annotation: Annotation, val storage: VariableExp?, override val range: Range) : Exp() {
    fun withBase(base: Exp?) = this.copy(base = base)

    override fun kotlinize(resolver: TypeResolver, scope: CVLScope): CollectingResult<CVLExp.UnresolvedApplyExp, CVLError> = collectingErrors {
        val ind = when(annotation) {
            Annotation.NEW -> TwoStateIndex.NEW
            Annotation.OLD -> TwoStateIndex.OLD
            else -> TwoStateIndex.NEITHER
        }

        val storageVariable = storage ?: VariableExp("lastStorage", Range.Empty());

        return@collectingErrors map(
            args.map { it.kotlinize(resolver, scope) }.flatten(),
            storageVariable.kotlinize(resolver, scope),
            base?.kotlinize(resolver, scope) ?: null.lift()
        ) { args: List<CVLExp>, storage: CVLExp.VariableExp, base: CVLExp? ->
            CVLExp.UnresolvedApplyExp(
                base,
                method,
                args,
                CVLExpTag(scope, range, hasParenthesis),
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

/* copies the class with the new range, and returns the same subclass as the caller */
private fun Exp.withRange(range: Range): Exp = when (this) {
    is ArrayDerefExp -> this.copy(range = range)
    is ArrayLitExp -> this.copy(range = range)
    is BifApplicationExpr -> this.copy(range = range)
    is AddExp -> this.copy(range = range)
    is BwAndExp -> this.copy(range = range)
    is BwLeftShiftExp -> this.copy(range = range)
    is BwOrExp -> this.copy(range = range)
    is BwRightShiftExp -> this.copy(range = range)
    is BwRightShiftWithZerosExp -> this.copy(range = range)
    is BwXOrExp -> this.copy(range = range)
    is DivExp -> this.copy(range = range)
    is ExponentExp -> this.copy(range = range)
    is IffExp -> this.copy(range = range)
    is ImpliesExp -> this.copy(range = range)
    is LandExp -> this.copy(range = range)
    is LorExp -> this.copy(range = range)
    is ModExp -> this.copy(range = range)
    is MulExp -> this.copy(range = range)
    is SubExp -> this.copy(range = range)
    is CastExpr -> this.copy(range = range)
    is CondExp -> this.copy(range = range)
    is BoolExp -> this.copy(range = range)
    is NumberExp -> this.copy(range = range)
    is FieldSelectExp -> this.copy(range = range)
    is QuantifierExp -> this.copy(range = range)
    is RelopExp -> this.copy(range = range)
    is SetMemExp -> this.copy(range = range)
    is StringExp -> this.copy(range = range)
    is SumExp -> this.copy(range = range)
    is BwNotExp -> this.copy(range = range)
    is LNotExp -> this.copy(range = range)
    is UMinusExp -> this.copy(range = range)
    is UnresolvedApplyExp -> this.copy(range = range)
    is VariableExp -> this.copy(range = range)

    is com.certora.certoraprover.cvl.SignatureLiteralExp -> {
        val methodSig = this.methodSig.copy(range = range)
        this.copy(methodSig = methodSig)
    }

    is ErrorExpr -> throw UnsupportedOperationException()
}