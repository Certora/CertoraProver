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

@file:kotlinx.serialization.UseSerializers(BigIntegerSerializer::class)
package vc.data

import com.certora.collect.*
import datastructures.Bijection
import datastructures.stdcollections.*
import log.*
import smt.GenerateEnv
import smt.LExpressionToSmtLibScene
import smt.solverscript.LExpressionFactory
import smt.solverscript.SmtTheory
import smt.solverscript.functionsymbols.*
import smtlibutils.data.HasSmtExp
import smtlibutils.data.ISmtScript
import smtlibutils.data.SmtExp
import smtlibutils.data.SmtExpWithComment
import tac.MetaMap
import tac.Tag
import utils.*
import vc.data.lexpression.LExpressionTypeException
import vc.summary.LExpTransFormula
import java.math.BigInteger
import java.util.*

private val logger = Logger(LoggerTypes.LEXPRESSION)

sealed class LExpressionWithComment {
    abstract val lExpression: LExpression
    abstract val comment: String?

    private data class NoComment(override val lExpression: LExpression) : LExpressionWithComment() {
        override val comment: String? get() = null
    }

    private data class WithComment(override val lExpression: LExpression, override val comment: String) :
        LExpressionWithComment()

    fun toSMTLib(toSmtLibData: ToSmtLibData, script: ISmtScript): HasSmtExp =
        SmtExpWithComment(lExpression.toSMTLib(toSmtLibData, script), comment)

    fun mapLExp(f: (LExpression) -> LExpression): LExpressionWithComment =
        LExpressionWithComment(lExpression = f(lExpression), comment = comment)

    companion object {
        operator fun invoke(lExpression: LExpression, comment: String?): LExpressionWithComment =
            if (comment == null) {
                NoComment(lExpression)
            } else {
                WithComment(lExpression, comment)
            }

        operator fun invoke(lExpression: LExpression): LExpressionWithComment =
            NoComment(lExpression)
    }
}

/**
 * data that's needed for the toSMTLib translation of LExpression, FunctionSignature, DefType, etc
 */
interface ToSmtLibData {
    val lExprFact: LExpressionFactory
    val targetTheory: SmtTheory
}

data class ToSmtLibDataWithScene(private val lExpressionToSmtLibScene: LExpressionToSmtLibScene) : ToSmtLibData {
    override val lExprFact: LExpressionFactory = lExpressionToSmtLibScene.lExpressionFactory
    override val targetTheory get() = lExpressionToSmtLibScene.targetTheory
}

/**
 * A generic logical expression.
 *
 * Any [LExpression] han hold meta information within a [MetaMap]. Such meta information is meant to be used further
 * down in the pipeline to guide heuristic choices. It is not guaranteed to be kept when an [LExpression] is modified or
 * transformed. As a general rule: when an [LExpression] `e` is modified to `e'`, meta information should be retained
 * (or rather copied) when `e` and `e'` are equivalent under model evaluation. Generally, back-translations should not
 * rely on meta information, but rather keep explicit information locally.
 */
@KSerializable
@Treapable
sealed class LExpression: AmbiSerializable {

    abstract val tag: Tag
    abstract fun toSMTLib(toSmtLibData: ToSmtLibData, script: ISmtScript): SmtExp

    abstract val meta: MetaMap


    @KSerializable
    data class Identifier(val id: String, override val tag: Tag, override val meta: MetaMap = MetaMap()) :
        LExpression() {
        override fun toString(): String = id
        override fun toSMTLib(toSmtLibData: ToSmtLibData, script: ISmtScript): SmtExp =
            script.qualIdentifier(script.simpleIdentifier(id))

        override fun equals(other: Any?): Boolean =
            (this === other) || (other as? Identifier)?.let { o -> this.id == o.id && this.tag == o.tag } ?: false

        override fun hashCode(): Int = hash { it + id + tag }

    }

    @KSerializable
    data class Literal(val i: BigInteger, override val tag: Tag) : LExpression() {
        init {
            when (tag) {
                is Tag.Bits -> Unit // only happens in ExpNormalizer
                Tag.Bool -> check(BigInteger.ZERO <= i && i <= BigInteger.ONE) { "constant $i is out of range for $tag" }
                Tag.Int -> Unit
                else -> error("NumericLiteral can't be $tag")
            }
        }

        override val meta: MetaMap
            get() = MetaMap()

        override fun toString() =
            when (tag) {
                Tag.Bool -> (i == BigInteger.ONE).toString()
                is Tag.Bits -> "(_ bv${i.toString(10)} ${tag.bitwidth})"
                Tag.Int -> i.toString()
                else -> error("NumericLiteral can't be $tag")
            }

        override fun toSMTLib(toSmtLibData: ToSmtLibData, script: ISmtScript): SmtExp =
            when (tag) {
                Tag.Bool -> script.boolLit(i == BigInteger.ONE)
                is Tag.Bits -> script.bitvector(i, tag.bitwidth)
                Tag.Int -> script.number(i)
                else -> error("NumericLiteral can't be $tag")
            }
        companion object {
            val False = Literal(BigInteger.ZERO, Tag.Bool)
            val True = Literal(BigInteger.ONE, Tag.Bool)
            operator fun invoke(b : Boolean) = ite(b, True, False)
        }
    }

    @KSerializable
    sealed class ApplyExpr: LExpression() {
        abstract val f: FunctionSymbol

        fun getResultTag(f: FunctionSymbol, args: List<LExpression>): Tag = try {
            f.getResultTag(args.map { it.tag })
        } catch (e: LExpressionTypeException) {
            logger.debug(e) { "Type check failed: ${e.internalMsg} on\n${this}" }
            f.signature.resultSort
            // Enable once all typing issues have been resolved
            //throw LExpressionTypeException("${e.internalMsg} on\n${this}", e)
        }

        companion object {
            operator fun invoke(f: FunctionSymbol, vararg args: LExpression, meta: MetaMap = MetaMap()) = when (args.size) {
                0 -> Nullary(f, meta)
                1 -> Unary(f, args[0], meta)
                2 -> Binary(f, args[0], args[1], meta)
                3 -> Ternary(f, args[0], args[1], args[2], meta)
                4 -> Quaternary(f, args[0], args[1], args[2], args[3], meta)
                else -> Nary(f, args.toList(), meta)
            }

            operator fun invoke(f: FunctionSymbol, l: List<LExpression>, meta: MetaMap = MetaMap()): ApplyExpr {
                return when (l.size) {
                    0 -> Nullary(f, meta)
                    1 -> Unary(f, l[0], meta)
                    2 -> Binary(f, l[0], l[1], meta)
                    3 -> Ternary(f, l[0], l[1], l[2], meta)
                    4 -> Quaternary(f, l[0], l[1], l[2], l[3], meta)
                    else -> Nary(f, l, meta)
                }
            }
        }


        @KSerializable
        class Nullary internal constructor(override val f: FunctionSymbol, override val meta: MetaMap = MetaMap()) : ApplyExpr() {
            override val args: List<LExpression> = listOf()
            override val tag: Tag = getResultTag(f, args)

            override fun equals(other: Any?): Boolean {
                return other is Nullary && other.f == this.f
            }

            override fun hashCode(): Int = hash { it + f }
        }

        @KSerializable
        class Unary internal constructor(override val f: FunctionSymbol, private val arg1: LExpression, override val meta: MetaMap = MetaMap()) : ApplyExpr() {
            override val args: List<LExpression>
                get() = listOf(arg1)
            override val tag: Tag = getResultTag(f, args)

            override fun equals(other: Any?): Boolean {
                return other is Unary && other.f == this.f && this.arg1 == other.arg1
            }

            override fun hashCode(): Int = hash { it + f + arg1 }
        }

        @KSerializable
        class Binary internal constructor(override val f: FunctionSymbol, val arg1: LExpression, val arg2: LExpression, override val meta: MetaMap = MetaMap()) : ApplyExpr() {
            override val args: List<LExpression>
                get() = listOf(arg1, arg2)
            override val tag: Tag = getResultTag(f, args)

            override fun equals(other: Any?): Boolean {
                return other is Binary && other.f == this.f && other.arg1 == this.arg1 && other.arg2 == this.arg2
            }

            override fun hashCode(): Int = hash { it + f + arg1 + arg2 }
        }

        @KSerializable
        class Ternary internal constructor(
            override val f: FunctionSymbol,
            val arg1: LExpression,
            val arg2: LExpression,
            val arg3: LExpression,
            override val meta: MetaMap = MetaMap()
        ) : ApplyExpr() {
            override val args: List<LExpression>
                get() = listOf(arg1, arg2, arg3)
            override val tag: Tag = getResultTag(f, args)

            override fun equals(other: Any?): Boolean {
                return other is Ternary && other.f == this.f && other.arg1 == this.arg1 && other.arg2 == this.arg2 && other.arg3 == this.arg3
            }

            override fun hashCode(): Int = hash { it + f + arg1 + arg2 + arg3 }
        }

        @KSerializable
        class Quaternary internal constructor(
            override val f: FunctionSymbol,
            val arg1: LExpression,
            val arg2: LExpression,
            val arg3: LExpression,
            val arg4: LExpression,
            override val meta: MetaMap = MetaMap()
        ) :
            ApplyExpr() {
            override val args: List<LExpression>
                get() = listOf(arg1, arg2, arg3, arg4)
            override val tag: Tag = getResultTag(f, args)

            override fun equals(other: Any?): Boolean {
                return other is Quaternary && other.f == this.f && other.arg1 == this.arg1 && other.arg2 == this.arg2 && other.arg3 == this.arg3 && other.arg4 == this.arg4
            }

            override fun hashCode(): Int = hash { it + f + arg1 + arg2 + arg3 + arg4 }
        }

        @KSerializable
        class Nary internal constructor(
            override val f: FunctionSymbol,
            override val args: List<LExpression>,
            override val meta: MetaMap = MetaMap()
        ) : ApplyExpr() {
            override val tag: Tag = getResultTag(f, args)

            override fun equals(other: Any?): Boolean {
                return other is Nary && this.f == other.f && this.args == other.args
            }

            override fun hashCode(): Int = hash { it + f + args }
        }

        abstract val args: List<LExpression>

        override fun toString(): String = "(${f.name} ${args.joinToString(" ")})"
        override fun toSMTLib(toSmtLibData: ToSmtLibData, script: ISmtScript): SmtExp =
            script.apply(f.toSMTLib(toSmtLibData, script), args.map { it.toSMTLib(toSmtLibData, script) })

        val lhs: LExpression
            get() {
                check(args.size == 2) { "Using `lhs` of LExpression $this, but it has ${args.size} arguments (should be exactly 2)" }
                return args[0]
            }

        val rhs: LExpression
            get() {
                check(args.size == 2) { "Using `rhs` of LExpression $this,but it has ${args.size} arguments (should be exactly 2)" }
                return args[1]
            }

        val arg: LExpression
            get() {
                check(args.size == 1) { "Using 'arg' of LExpression $this, but it has ${args.size} arguments (should be exactly 1)" }
                return args[0]
            }

        /**
         * This function should not be used directly, use [LExpressionFactory] to create new expressions. Usually
         * [copyArgs] can do the trick.
         */
        fun copy(
            f: FunctionSymbol = this.f,
            args: List<LExpression> = this.args,
            meta: MetaMap = this.meta
        ): LExpression {
            return ApplyExpr(f, args, meta)
        }
    }

    @KSerializable
    data class QuantifiedExpr private constructor(
        val quantifier: Quantifier,
        val quantifiedVar: List<Identifier>,
        val body: LExpression
    ) : LExpression() {
        override val tag: Tag
            get() = Tag.Bool
        override val meta: MetaMap
            get() = MetaMap()

        init {
            check(body !is QuantifiedExpr || body.quantifier != quantifier) { "not flattened" }
        }

        override fun toString(): String =
            "$quantifier ${quantifiedVar.joinToString(" ") { "${it.id}:${it.tag}" }} . $body"

        override fun hashCode(): Int = hash { it + quantifier + quantifiedVar + body }

        override fun toSMTLib(toSmtLibData: ToSmtLibData, script: ISmtScript): SmtExp {
            val bodySmtLib = body.toSMTLib(toSmtLibData, script)
            val qVarsSmtLib = quantifiedVar.map {
                script.sortedVar(
                    it.id,
                    LExpressionFactory.tagToSort(it.tag, toSmtLibData)
                )
            }
            return when (quantifier) {
                Quantifier.FORALL -> {
                    script.forAllOrId(qVarsSmtLib, bodySmtLib)
                }

                Quantifier.EXISTS -> {
                    script.existsOrId(qVarsSmtLib, bodySmtLib)
                }
            }
        }

        companion object {
            operator fun invoke(
                quantifier: Quantifier,
                quantifiedVar: List<Identifier>,
                body: LExpression
            ): LExpression = if (quantifiedVar.isEmpty()) {
                body
            } else {
                QuantifiedExpr(quantifier, quantifiedVar, body)
            }
        }

    }


}

sealed class LExpressionWithEnvironment {
    abstract val exp: LExpression
    abstract val env: GenerateEnv

    open operator fun component1() = exp
    open operator fun component2() = env

    private data class NoEnv(override val exp: LExpression) : LExpressionWithEnvironment() {
        override val env: GenerateEnv
            get() = GenerateEnv()
    }

    private data class WithEnv(override val exp: LExpression, override val env: GenerateEnv) :
        LExpressionWithEnvironment()

    /** return those vars in [exp] that are quantified in [env] */
    fun getQuantifiedVars() = env.quantifiedVariables intersect exp.getFreeIdentifiers()

    companion object {
        operator fun invoke(exp: LExpression, env: GenerateEnv): LExpressionWithEnvironment =
            if (env.isEmpty()) {
                NoEnv(exp)
            } else {
                WithEnv(exp, env)
            }
    }
}

enum class Quantifier {
    FORALL, EXISTS;

    override fun toString(): String = this.name.lowercase()
}

abstract class TraverseLExpression {

    open fun expr(exp: LExpression) = when (exp) {
        is LExpression.Identifier -> identifier(exp)
        is LExpression.Literal -> literal(exp)
        is LExpression.ApplyExpr -> applyExpr(exp)
        is LExpression.QuantifiedExpr -> quantifiedExpr(exp)
    }

    open fun applyExpr(exp: LExpression.ApplyExpr) {
        exp.args.forEach { expr(it) }
    }

    open fun quantifiedExpr(exp: LExpression.QuantifiedExpr) {
        expr(exp.body)
    }

    open fun identifier(id: LExpression.Identifier) { /* do nothing */
    }

    open fun literal(lit: LExpression.Literal) { /* do nothing */
    }
}


class CollectDefinitionsLExp private constructor(val definedVars: Set<LExpression.Identifier>) : TraverseLExpression() {
    private val result = mutableMapOf<LExpression.Identifier, LExpression>()

    override fun applyExpr(exp: LExpression.ApplyExpr) {
        if (exp.f is TheoryFunctionSymbol.Binary.Eq) {
            if (exp.args[0] in definedVars) result[exp.args[0] as LExpression.Identifier] = exp.args[1]
            if (exp.args[1] in definedVars) result[exp.args[1] as LExpression.Identifier] = exp.args[0]
        }
        if (exp.f is NonSMTInterpretedFunctionSymbol.Binary.AssignEq) {
            check(exp.args[0] in definedVars)
            result[exp.args[0] as LExpression.Identifier] = exp.args[1]
        }
        // is exp a conjunction?
        if (exp.f is TheoryFunctionSymbol.Vec.LAnd) {
            super.applyExpr(exp) // go recursively into conjunction
        }
        // nothing more to do (in particular, ignore everything that is below a function symbol that's neither "=" or "and"
    }

    companion object {
        fun computeDefinitions(
            tf: LExpTransFormula,
            definedVars: Set<LExpression.Identifier>
        ): Map<LExpression.Identifier, LExpTransFormula> {
            val cd = CollectDefinitionsLExp(definedVars)
            cd.expr(tf.exp)
            /**
            for each definition build a special transformula (which not describes a transition... hm...) whose exp
            is the value of the definition, and whose inVars map the definitions vars to TaCSummaryVars
            this is analogue to [CollectDefinitionsTACExpr]
             */
            return cd.result.mapValues { (_, exp) ->
                val varsInExp = LExpTransFormula.getVariables(exp)
                LExpTransFormula(
                    exp,
                    Bijection.fromMap(tf.inVars.filter { (_, symVar) -> symVar in varsInExp }),
                    Bijection(),
                    setOf(),
                    tf.auxVars.filter { it in varsInExp }.toSet()
                )
            }
        }
    }
}

@JvmInline
value class ApplyExprView<FS: FunctionSymbol>(val exp: LExpression.ApplyExpr) {
    val f: FS get() = exp.f.uncheckedAs()

}

inline fun <reified FS : FunctionSymbol> LExpression.ApplyExpr.narrow(): ApplyExprView<FS> {
    check(this.f is FS) { "failed to narrow LExpression.ApplyExpr" }
    return ApplyExprView(this)
}

