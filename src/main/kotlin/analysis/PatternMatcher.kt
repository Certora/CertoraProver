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

@file:Suppress("RedundantUnitExpression")

package analysis

import analysis.PatternDSL.Coalesce2
import analysis.PatternDSL.PatternBuilder
import analysis.PatternMatcher.Pattern
import analysis.PatternMatcher.VariableMatch.Continue
import analysis.PatternMatcher.VariableMatch.Match
import analysis.PatternMatcher.compilePattern
import log.*
import utils.*
import vc.data.TACCmd
import vc.data.TACExpr
import vc.data.TACMeta.STORAGE_KEY
import vc.data.TACSymbol
import java.math.BigInteger

private val logger = Logger(LoggerTypes.PATTERN)

/**
 * A DSL builder for patterns. For example, to match adding 0x40 to some variable, you would do the following:
 *
 * PatternDSL.build { (Var + 0x40()).locFirst }
 *
 * This builds a pattern which matches Var + 0x40 and gives the location and the variable being matched against.
 *
 * The basic building blocks (the base cases) are the Const and Var functions: these can be supplied with predicates
 * to narrow the variables/constants to match against. The no-arg version match any constant and argument and return as the
 * match payload the variable/constant matched. As a convenience, to match a specific constant/variable, there are the Const(BigInteger)
 * and Var(TACSymbol.Var) versions. For even less verbosity you may use the overloaded operators ! for variables and () for constants.
 * So to match `someTacVar` exactly, you need simply write `!someTacVar` and to match the constant `0x40` exactly you need only write `0x40()`.
 *
 * ## Complex operations
 *
 * Patterns will often match against binary/unary operations, often arbitrarily nested. To support naturally building these
 * patterns, the DSL uses the [PatternBuilder] class. A [PatternBuilder] is a thin wrapper around a [PatternMatcher.Pattern]
 * but which is extended to support many common binary operations. For example, suppose we have `p` and `q` each
 * of which match some arbitrary piece of code. To match code that adds together two arguments which are defined by `p` and `q`
 * we can simply write `p + q`. However, this does *not* actually yield a [PatternBuilder]; `p + q` gives no information about
 * how the result payloads of `p` and `q` should be combined. To that end, the object ([Coalesce2]) returned has a method [withAction][Coalesce2.withAction]
 * which specifies the combiner for the results of `p` and `q` and defines the overall match payload of the pattern `p + q`.
 * Thus, you need to write `p + q.withAction { where, pRes, qRes -> ... }` Then `where` parameter here is the [LTACCmd] at the point
 * the match on `+` occurred.
 *
 * As a convenience, the object returned by the binary operations provide [first][Coalesce2.first] and [locFirst][Coalesce2.locFirst]
 * fields which yield a [PatternBuilder] that returns the left pattern payload or the left pattern payload AND the match location as a pair
 * respectively. [second][Coalesce2.second] and [locSecond][Coalesce2.locSecond] are similarly defined.
 *
 * ## Commutativity
 *
 * Sometimes operations such as `+` and `*` are commutative and thus the patterns should be commutative as well. By default the
 * pattern matcher does *not* commute patterns unless explicitly requested: certain DSL operations will return a [Coalesce2] extended
 * with a `commute` method. This commute method returns a [Coalesce2] which when provided an action will actualy yield a pattern
 * that matches EITHER `p + q` or `q + p` for addition, `p * q` OR `q * p` for multiplication etc.
 *
 * ## Naming and debugging
 *
 * It is hard to debug the low-level internal pattern machinery. To aid in diagnosing pattern bugs, [PatternBuilders][PatternBuilder]
 * and [Coalesce2] objects have a `named` method which allows an informative name to be given to a pattern. By default
 * this name does nothing, but if the pattern debug level is activated, patterns with names will verbosely log their operations.
 *
 * ### Note
 *
 * The [Coalesce2] and [PatternBuilder] objects are meant to be used in a fluent API style and intermediate objects should
 * not be stored and re-used. This is especially important w.r.t. naming which is actually implemented using side-effects
 * and could behave unpredictably if a [PatternBuilder] or [Coalesce2] is reused in multiple contexts.
 *
 * ## Logical Operations
 *
 * In addition to the usual arithmetic operations, the PatternDSL allows combining patterns with logical operations. In
 * particular, `p ^ q` may be used to combine two [PatternBuilders][PatternBuilder] with identical payload types, yielding
 * a pattern that matches EITHER q OR q, i.e., the XOR operation.
 *
 * To perform a short-circuiting logical or, use the `lor` infix function. `p lor q` will attempt to match `p` and only
 * if it fails to match will it attempt to match `q`
 *
 * ## Advanced patterns (Not yet extensively tested)
 *
 * For totally general matching you can lift an extractor function into a function in the DSL. Any function that takes
 * an [LTACCmd] and an [TACCmd.Simple.AssigningCmd] and returns either a `TACSymbol?` or `Pair<TACSymbol, TACSymbol>?`
 * may be lifted into a pattern builder function via the `lift` function. These functions should examine the assigning cmd,
 * and if the assigning form is acceptable return the operand (resp. operands) which should be sub-matched on. For example,
 * to embed a match on calldataload, one could write:
 *
 * ```
 * { _: LTACCmd, b: TACCmd.Simple.AssigningCmd -> if(b is TACCmd.Simple.AssigningCmd.ByteLoadFromRO && b.base == TACKeyword.CALLDATA.toVar()) { b.loc } else { null } }.lift()
 * ```
 *
 * The result of the above lift operation is a function that expects a [PatternBuilder] which will be used to sub-match on
 * the extracted argument (in this example, the location in the calldata array). Suppose we wanted to match an index that is exactly zero: then
 * we would have:
 *
 * ```
 * { _: LTACCmd, b: TACCmd.Simple.AssigningCmd -> /* as before */ }.lift()(0x0()).withAction { ... }
 * ```
 *
 * In the case where the function returns a pair, the object returned after invoking the function also supports the `commute`
 * operation, to indicate the arguments can be supplied in any order.
 */
 object PatternDSL {
     /**
      * A common interface for objects that are not yet pattern builders, but know how to transform themselves into a [PatternBuilder];
      * in fact, a [PatternBuilder] is itself a [Buildable]. Implementations of [Buildable] that are not pattern builders represent
      * logic to match some fragment of code without any information (yet) about what to do with the result of that match. In particular,
      * Coalesce2 and CommutativeCoalesce2 both represent logic to match a binary operation that does not yet have explicit information
      * on what to do with the payloads of the operand matches. If the user specifies such instructions, the resulting object is now
      * full-fledged [PatternBuilder]. However, the [toBuilder] method specified in this interface means that objects of these types can still
      * be used in contexts where we expect a PatternBuilder (i.e., matching logic and action logic). In those cases, we can use a "default action"
      * which is to simply return the payloads of the binary operation as a pair.
      *
      * Ultimately, this interface allows writing dsl like `((p1 + p2) + p3).withAction ...` without having to explicitly specify the
      * action for the nested plus operation.
      * The type parameter [R] indicates that this [Buildable] knows how to transform itself into a [PatternBuilder] whose payload is of type [R].
      */
    interface Buildable<out R> {
        fun toBuilder(): PatternBuilder<R>

         /**
          * Given the logic to match a piece of code represented by this and the argument [p], construct a new
          * object that represents the logic to match `x + y`, where x and y are symbols that must be defined according
          * to the patterns in this and [p] respectively. The object returned supports commuting, see [CommutativeCoalesce2]
          *
          * Example: the pattern `(Var + 16())` will match the definition of z in:
          * y = 16
          * z = x + y
          *
          */
        operator fun <U> plus(p: Buildable<U>): CommutativeCoalesce2<R, U> = this.toBuilder() + p.toBuilder()

         /**
          * As in [plus], but matches `x - y`
          */
         operator fun <U> minus(p: Buildable<U>): Coalesce2<R, U> = this.toBuilder() - p.toBuilder()

         /**
          * As in [plus], but matches `x * y` (commutative)
          */
        operator fun <U> times(p: Buildable<U>): CommutativeCoalesce2<R, U> = this.toBuilder() * p.toBuilder()

         /**
          * As in [plus], but matches `x / y`
          */
         operator fun <U> div(p: Buildable<U>): Coalesce2<R, U> = this.toBuilder() / p.toBuilder()

         /*
         * As in [div], but matches `x % y`
         */
         infix fun <U> mod(p: Buildable<U>): Coalesce2<R, U> = this.toBuilder() mod p.toBuilder()

         /**
          * As in [plus] but matches x & y NB: bitwise
          */
         infix fun <U> and(p: Buildable<U>): CommutativeCoalesce2<R, U> = this.toBuilder() and p.toBuilder()

         /**
          * As in [plus], but matches x | y, NB: bitwise
          */
         infix fun <U> or(p: Buildable<U>): CommutativeCoalesce2<R, U> = this.toBuilder() or p.toBuilder()

         infix fun <U> xor(p: Buildable<U>): CommutativeCoalesce2<R, U> = this.toBuilder() xor p.toBuilder()


         /**
          * As in [plus], but matches x < y
          *
          * NB: this does *NOT* match y > x and the object returned here does *NOT* support commuting.
          */
         infix fun <U> lt(p: Buildable<U>): Coalesce2<R, U> = this.toBuilder() lt p.toBuilder()

        /** Signed operators */
        infix fun <U> slt(p: Buildable<U>): Coalesce2<R, U> = this.toBuilder() slt p.toBuilder()

        infix fun <U> sle(p: Buildable<U>): Coalesce2<R, U> = this.toBuilder() sle p.toBuilder()

        infix fun <U> sgt(p: Buildable<U>): Coalesce2<R, U> = this.toBuilder() sgt p.toBuilder()

        infix fun <U> sge(p: Buildable<U>): Coalesce2<R, U> = this.toBuilder() sge p.toBuilder()

         /**
          * As in [lt], but matches x > y. As in [lt] it does NOT match y < x
          */
        infix fun <U> gt(p: Buildable<U>): Coalesce2<R, U> = this.toBuilder() gt p.toBuilder()

         /**
          * As in [gt], but matches `x >= y`. Does NOT match `y <= x`
          */
         infix fun <U> ge(p: Buildable<U>): Coalesce2<R, U> = this.toBuilder() ge p.toBuilder()

         /**
          * As in [ge], but matches `x <= y`. Does NOT match `y >= x`.
          */
         infix fun <U> le(p: Buildable<U>): Coalesce2<R, U> = this.toBuilder() le p.toBuilder()

         /**
          * As in [lt], but matches the expression `x == y`.
          */
        infix fun <U> `==`(p: Buildable<U>): CommutativeCoalesce2<R, U> = this.toBuilder() `==` p.toBuilder()

         /**
          * As in [and], but matches x << y`.
          */
         infix fun <U> shl(p: Buildable<U>): Coalesce2<R, U> = this.toBuilder() shl p.toBuilder()

         /**
          * As in [and], but matches x >>> y`.
          */
         infix fun <U> shrLogical(p: Buildable<U>): Coalesce2<R, U> = this.toBuilder() shrLogical p.toBuilder()
    }

    interface CommutativeCombinator {
        operator fun <R, U> invoke(p1: PatternBuilder<R>, p2: PatternBuilder<U>) : CommutativeCoalesce2<R, U>

        companion object {
            val add : CommutativeCombinator = object: CommutativeCombinator {
                override fun <R, U> invoke(p1: PatternBuilder<R>, p2: PatternBuilder<U>): CommutativeCoalesce2<R, U> {
                    return p1 + p2
                }
            }
            val times = object : CommutativeCombinator {
                override fun <R, U> invoke(p1: PatternBuilder<R>, p2: PatternBuilder<U>): CommutativeCoalesce2<R, U> {
                    return p1 * p2
                }
            }

            val and = object : CommutativeCombinator {
                override fun <R, U> invoke(p1: PatternBuilder<R>, p2: PatternBuilder<U>): CommutativeCoalesce2<R, U> {
                    return p1 and p2
                }
            }

            val or = object : CommutativeCombinator {
                override fun <R, U> invoke(p1: PatternBuilder<R>, p2: PatternBuilder<U>): CommutativeCoalesce2<R, U> {
                    return p1 or p2
                }
            }
        }
    }

    fun <T, U, V, R> commuteThree(p1: PatternBuilder<T>, p2: PatternBuilder<U>, p3: PatternBuilder<V>, combinator: CommutativeCombinator, f: (T, U, V) -> R) : PatternBuilder<R> {
        return (combinator(combinator(p1, p2).commute.toBuilder(), p3).commute).withAction { (t, u), v ->
            f(t, u, v)
        } `^` combinator(combinator(p1, p3).commute.toBuilder(), p2).commute.withAction { (t, v), u ->
            f(t, u, v)
        } `^` combinator(combinator(p2, p3).commute.toBuilder(), p1).commute.withAction { (u, v), t ->
            f(t, u, v)
        }
    }

    /**
     * An intermediate representation of applying a (non-commutative) binary operation to two [PatternBuilder]s p1 and p2, which produces
     * payloads of type R and U respectively. Thus, this object represents the pattern `op(p1, p2)` but does not yet have
     * instructions for how to represent the result of the match.
     * However, as it implements the `Buildable` interface, it's "default" action is to simply return the payloads of
     * p1 and p2 as a pair.
     *
     */
    interface Coalesce2<out R, U> : Buildable<Pair<R, U>> {
        /**
         * Produce a pattern builder that matches `op(p1, p2)` and produces a payload of type [T] via the
         * callback [f]. [f] receives the payloads of `p1` and `p2`, the result of the function
         * is used as a result of the pattern represented by the returned pattern Builder.
         */
        fun <T> withAction(f: (R, U) -> T): PatternBuilder<T> = withAction { _, r, u -> f(r, u) }

        /**
         * Produces a pattern buidler that matches `op(p1, p2)` and produces a payload of type [T] via the
         * callback [f]. [f] receives the payloads of `p1` and `p2` as well as the location of the operation being
         * matched. The result of [f] is used as the payload for the pattern represented by the
         * returned [PatternBuilder]
         */
        fun <T> withAction(f: (LTACCmd, R, U) -> T): PatternBuilder<T>

        /**
         * Convenience property which yields a pattern builder which uses
         * the result of p1 as the payload of the overall pattern.
         */
        val first: PatternBuilder<R>
            get() = withAction { f, _ -> f }

        /**
         * Convenience property which yields a pattern builder which uses
         * the result of p2 as the payload of the overall pattern.
         */
        val second: PatternBuilder<U> get() = withAction { _, s -> s }

        /**
         * As with [first], but the payload of the returned [PatternBuilder] is pair of the location of the match and the payload of p1.
         */
        val locFirst: PatternBuilder<Pair<LTACCmd, R>> get() = withAction { where, f, _ -> where to f }
        /**
         * As with [second], but the payload of the returned [PatternBuilder] is a pair of the location of the match and the payload of p2.
         */
        val locSecond: PatternBuilder<Pair<LTACCmd, U>> get() = withAction { where, _, s -> where to s }

        /**
         * Represents the "default" action of this Buildable: simply return the payloads of p1 and p2 as a pair.
         */
        override fun toBuilder(): PatternBuilder<Pair<R, U>> = this.withAction { r, u -> r to u }

        var name: String?
        fun named(s: String): Coalesce2<R, U> {
            name = s; return this
        }
    }

    /**
     * A [Coalesce2] matching `op(p1,p2)`
     * where the operation is known to be commutative The property [commute] returns a
     * `Coalesce2` that matches `op(p1,p2) ^ op(p2,p1)`. Note that the pattern uses the
     * exclusive or, meaning that the resulting pattern will refuse to return a match if
     * both versions match.
     *
     * The behavior of the operations described in [Coalesce2] apply to this interface:
     * NB `p1` and `p2` refer to the patterns before commuting. In other words,
     * `(p1 + p2).commute.first` will use the payload of `p1` as the result of the resulting pattern.
     */
    interface CommutativeCoalesce2<out R, U> : Coalesce2<R, U> {
        val commute: Coalesce2<R, U>
        override fun named(s: String): CommutativeCoalesce2<R, U> {
            return super.named(s).uncheckedAs()
        }
    }

    /**
     * A thin (ish) wrapper around a [Pattern] that represents logic to match a piece of code
     * and produce a payload of type [R]. This is effectively what a [Pattern] is, an indeed a [PatternBuilder]
     * can be freely converted to a pattern via the [toPattern] method.
     * The distinction difference from a [Pattern] is that a [PatternBuilder] is also a [Buildable]
     * and thus can be used in DSL operations.
     *
     * In addition, the careful reader will note that the definition of operations in [Buildable] all rely on infix function definitions
     * defined in this class; this class therefore provides the implementations for the DSL combinators which produce fresh [Buildable] objects
     * based on the operations being used for matching.
     */
    abstract class PatternBuilder<out R> : Buildable<R> {

        open var name: String? = null

        fun named(s: String): PatternBuilder<R> {
            name = s
            return this
        }

        override fun toBuilder(): PatternBuilder<R> = this

        abstract fun toPattern(): Pattern<R>

        operator fun <U> plus(p: PatternBuilder<U>): CommutativeCoalesce2<R, U> =
            commuteBinOpSemantics(TACExpr.Vec.Add::class.java, p)

        /**
         * Given a commutative operation `op` nwhose TAC expression class is [klass] and a pattern builder
         * [p] that produces a payload [U], return a [CommutativeCoalesce2] which matches `this op p`.
         */
        private fun <K : TACExpr.BinExp, U> commuteBinOpSemantics(
            klass: Class<K>,
            p: PatternBuilder<U>
        ): CommutativeCoalesce2<R, U> {
            var nameField: String? = null
            return object : CommutativeCoalesce2<R, U> {
                override var name: String?
                    get() = nameField
                    set(value) {
                        nameField = value
                    }

                override fun <T> withAction(f: (R, U) -> T): PatternBuilder<T> {
                    return RUCoalesce2(this@PatternBuilder, klass, p).withAction(f)
                }

                override fun <T> withAction(f: (LTACCmd, R, U) -> T): PatternBuilder<T> {
                    return RUCoalesce2(this@PatternBuilder, klass, p).withAction(f)
                }

                override val commute: Coalesce2<R, U>
                    get() = object : Coalesce2<R, U> {
                        override fun <T> withAction(f: (LTACCmd, R, U) -> T): PatternBuilder<T> {
                            val p1 = RUCoalesce2(
                                this@PatternBuilder, klass, p
                            ).let {
                                if (nameField != null) {
                                    it.named("orig-$nameField")
                                } else {
                                    it
                                }
                            }.withAction(f).toPattern()
                            val p2 = RUCoalesce2(
                                p, klass, this@PatternBuilder
                            ).let {
                                if (nameField != null) {
                                    it.named("commuted-$nameField")
                                } else {
                                    it
                                }
                            }.withAction { l, u, r ->
                                f(l, r, u)
                            }.toPattern()
                            return object : PatternBuilder<T>() {
                                override fun toPattern(): Pattern<T> = Pattern.XOr(
                                    first = p1,
                                    adapt1 = { it },
                                    second = p2,
                                    adapt2 = { it },
                                    patternName = nameField
                                )
                                override var name: String?
                                    get() = nameField
                                    set(value) {
                                        nameField = value
                                    }
                            }
                        }

                        override var name: String?
                            get() = nameField
                            set(value) {
                                nameField = value
                            }
                    }
                }
            }

        operator fun <U> times(p: PatternBuilder<U>): CommutativeCoalesce2<R, U> =
            commuteBinOpSemantics(TACExpr.Vec.Mul::class.java, p)

        operator fun <U> minus(p: PatternBuilder<U>): Coalesce2<R, U> = binOpSemantics(TACExpr.BinOp.Sub::class.java, p)

        operator fun <U> div(p: PatternBuilder<U>): Coalesce2<R, U> = binOpSemantics(TACExpr.BinOp.Div::class.java, p)

        infix fun <U> mod(p: PatternBuilder<U>): Coalesce2<R, U> = binOpSemantics(TACExpr.BinOp.Mod::class.java, p)

        infix fun <U> sdiv(p: PatternBuilder<U>): Coalesce2<R, U> = binOpSemantics(TACExpr.BinOp.SDiv::class.java, p)

        infix fun <U> lt(p: PatternBuilder<U>): Coalesce2<R, U> = binOpSemantics(TACExpr.BinRel.Lt::class.java, p)

        infix fun <U> slt(p: PatternBuilder<U>): Coalesce2<R, U> = binOpSemantics(TACExpr.BinRel.Slt::class.java, p)

        infix fun <U> sle(p: PatternBuilder<U>): Coalesce2<R, U> = binOpSemantics(TACExpr.BinRel.Sle::class.java, p)

        infix fun <U> sgt(p: PatternBuilder<U>): Coalesce2<R, U> = binOpSemantics(TACExpr.BinRel.Sgt::class.java, p)

        infix fun <U> sge(p: PatternBuilder<U>): Coalesce2<R, U> = binOpSemantics(TACExpr.BinRel.Sge::class.java, p)

        infix fun <U> gt(p: PatternBuilder<U>): Coalesce2<R, U> = binOpSemantics(TACExpr.BinRel.Gt::class.java, p)

        infix fun <U> ge(p: PatternBuilder<U>): Coalesce2<R, U> = binOpSemantics(TACExpr.BinRel.Ge::class.java, p)

        infix fun <U> le(p: PatternBuilder<U>): Coalesce2<R, U> = binOpSemantics(TACExpr.BinRel.Le::class.java, p)

        infix fun <U> `==`(p: PatternBuilder<U>): CommutativeCoalesce2<R, U> =
                commuteBinOpSemantics(TACExpr.BinRel.Eq::class.java, p)

        infix fun <U> shl(p: PatternBuilder<U>): Coalesce2<R, U> = binOpSemantics(TACExpr.BinOp.ShiftLeft::class.java, p)

        infix fun <U> shrLogical(p: PatternBuilder<U>): Coalesce2<R, U> = binOpSemantics(TACExpr.BinOp.ShiftRightLogical::class.java, p)

        infix fun <U> and(p: PatternBuilder<U>): CommutativeCoalesce2<R, U> =
            commuteBinOpSemantics(TACExpr.BinOp.BWAnd::class.java, p)

        infix fun <U> or(p: PatternBuilder<U>): CommutativeCoalesce2<R, U> =
            commuteBinOpSemantics(TACExpr.BinOp.BWOr::class.java, p)

        infix fun <U> xor(p: PatternBuilder<U>): CommutativeCoalesce2<R, U> =
            commuteBinOpSemantics(TACExpr.BinOp.BWXOr::class.java, p)

        /**
         * Given a non-commutative expression whose tac expression representation is `g`, produce a [Coalesce2] builder
         * that matches `this op p`.
         */
        private fun <K : TACExpr.BinExp, U> binOpSemantics(
            g: Class<K>,
            p: PatternBuilder<U>
        ): Coalesce2<R, U> {
            return RUCoalesce2(this, g, p)
        }
    }

    class RUCoalesce2<K : TACExpr.BinExp, U, R>(
        private val left: PatternBuilder<R>,
        private val g: Class<K>,
        private val right: PatternBuilder<U>
    ) : Coalesce2<R, U> {
        override fun <T> withAction(f: (R, U) -> T): PatternBuilder<T> {

            val patt = Pattern.FromBinOp.from(g,
                p1 = left.toPattern(),
                p2 = right.toPattern(),
                comb = { _, r, u -> f(r, u) },
                patternName = name
            )
            return mkBuilder(patt)
        }

        override fun <T> withAction(f: (LTACCmd, R, U) -> T): PatternBuilder<T> = Pattern.FromBinOp.from(
            g,
            left.toPattern(), right.toPattern(), comb = f, patternName = name
        ).let(::mkBuilder)

        private fun <T> mkBuilder(patt: Pattern.FromBinOp<T, *, *>): PatternBuilder<T> {
            return object : PatternBuilder<T>() {
                override fun toPattern(): Pattern<T> = if (name != null) {
                    patt.copy(patternName = name)
                } else {
                    patt
                }
                override var name = this@RUCoalesce2.name
            }
        }

        override var name: String? = null
    }

    /**
     * Combine two DSL patterns according to a logical or. Unlike the XOR counterpart, this logic
     * short-circuits, and only the first pattern that matches is take as the result (i.e. in p1 lor p2, if p1 and p2
     * both match, p1's payload is taken as the result)
     */
    infix fun <R> Buildable<R>.lor(other: Buildable<R>) = object : PatternBuilder<R>() {
        override fun toPattern(): Pattern<R> {
            return Pattern.Or(
                first = this@lor.toBuilder().toPattern(),
                adapt1 = { it },
                second = other.toBuilder().toPattern(),
                adapt2 = { it },
                patternName = name
            )
        }
    }

    /**
     * Combine two DSL patterns according to an exclusive or. If both patterns match, then the entire
     * match fails (representing an ambiguous match).
     */
    @Suppress("unused")
    infix fun <R> PatternBuilder<R>.`^`(other: PatternBuilder<R>): PatternBuilder<R> {
        return object : PatternBuilder<R>() {
            override fun toPattern(): Pattern<R> =
                Pattern.XOr(
                    first = this@`^`.toPattern(),
                    adapt1 = { it },
                    second = other.toPattern(),
                    adapt2 = { it },
                    patternName = name
                )

        }
    }

    /**
     * DSL operation to embed a match against an exact variable into the DSL. For example, to match exactly the scalarized
     * free pointer variable, it suffices to write `!TACKeyword.MEM64.toVar()`.
     */
    operator fun TACSymbol.Var.not(): PatternBuilder<Unit> = Var { it ->
        if (it == this) {
            Match(Unit)
        } else {
            PatternMatcher.VariableMatch.Continue
        }
    }

    val TACSymbol.Var.withLocation get() = Var { it, loc ->
        if(it == this) {
            Match(loc.ptr)
        } else {
            Continue
        }
    }

    /**
     * Try to match a variable according to the predicate [f] which receives the variable and the
     * location. The return type of the function
     * is a [analysis.PatternMatcher.VariableMatch], the meaning of this object is explained in the
     * documentation for that class.
     */
    @Suppress("FunctionName")
    fun <R> Var(f: (TACSymbol.Var, LTACCmd) -> PatternMatcher.VariableMatch<R>): PatternBuilder<R> =
        object : PatternBuilder<R>() {
            override fun toPattern(): Pattern<R> =
                Pattern.FromVar(extractor = { w, v -> f(v, w) }, patternName = name)

        }

    /**
     * Match a variable according to the predicate [f]; the return type of this predicate is a [analysis.PatternMatcher.VariableMatch],
     * the meaning of this type is explained in that class.
     */
    @Suppress("FunctionName")
    fun <R> Var(f: (TACSymbol.Var) -> PatternMatcher.VariableMatch<R>): PatternBuilder<R> =
        Var { sym, _ -> f(sym) }

    /**
     * Match against any constant.
     */
    val Const =
        object : PatternBuilder<BigInteger>() {
            override fun toPattern(): Pattern<BigInteger> = Pattern.FromConstant({ it, _ ->
                it.value
            }, { _, t -> t }, patternName = name)

        }

    /**
     * Match any constant that satisfies the predicate [f]
     */
    @Suppress("FunctionName")
    fun Const(f: (BigInteger) -> Boolean) =
        object : PatternBuilder<BigInteger>() {
            override fun toPattern(): Pattern<BigInteger> = Pattern.FromConstant({ it, _ ->
                if (f(it.value)) {
                    it.value
                } else {
                    null
                }
            }, { _, t -> t }, patternName = name)

        }

    fun Const(f: (BigInteger, LTACCmd) -> Boolean) =
        object : PatternBuilder<BigInteger>() {
            override fun toPattern(): Pattern<BigInteger> {
                return Pattern.FromConstant({ it, where ->
                    it.value.takeIf { _ -> f(it.value, where) }
                }, { _, it -> it }, patternName = name)
            }
        }

    /**
     * Match exactly the constant [f]
     */
    @Suppress("FunctionName")
    fun Const(f: BigInteger) =
        object : PatternBuilder<Unit>() {
            override fun toPattern(): Pattern<Unit> = Pattern.FromConstant({ it, _ ->
                if (it.value == f) {
                    Unit
                } else {
                    null
                }
            }, { _, _ -> }, patternName = name)

        }

    abstract class VarMatcher : PatternBuilder<TACSymbol.Var>()

    /**
     * A "terminal" node in the DSL(), allows matching against any variable.
     */
    val Var = object : VarMatcher() {
        override fun toPattern(): Pattern<TACSymbol.Var> = Pattern.FromVar({ _, v ->
            Match(v)
        }, patternName = name)
    }

    val VarMatcher.withLocation get() = object : PatternBuilder<Pair<CmdPointer, TACSymbol.Var>>() {
        override fun toPattern(): Pattern<Pair<CmdPointer, TACSymbol.Var>> = Pattern.FromVar({ w, v ->
            Match(w.ptr to v)
        }, patternName = name)
    }

    fun <T> build(f: PatternDSL.() -> PatternBuilder<T>): Pattern<T> = this.f().toPattern()

    /**
     * DSL operation to promote a hex-string representation of an unsigned number into a pattern that matches exactly that
     * literal, example:
     *
     * `"1234"()` will match the constant 1234
     */
    operator fun String.invoke() = BigInteger(this, 16)()

    /**
     * DSL operation to match on exactly the constant reprsented by [this].
     * Example: `BigInteger.ONE()` will match exactly the constant 1
     */
    operator fun BigInteger.invoke(): PatternBuilder<Unit> = Const(this)

    /**
     * DSL operation to match the value represented by [this].
     * Example `1()` will match exactly the constant 1
     */
    operator fun Int.invoke(): PatternBuilder<Unit> = Const(this.toBigInteger())

    /**
     * DSL operation to match the value represented by [this].
     * Example: `0x40L()` will match exactly the constant 96
     */
    operator fun Long.invoke(): PatternBuilder<Unit> = Const(this.toBigInteger())

    /**
     * An intermediate step of embedded an arbitrary predicate over [vc.data.TACCmd.Simple.AssigningCmd]
     * into the DSL. This object means that we have logic to match a command, extract an operand of interest, and perform
     * further matching on that operand, but do not yet know what to do with the result of that match. This logic
     * is specified by calling one of the [withAction] functions, which return a full fledged [PatternBuilder] object.
     *
     * FIXME(jtoman): this doesn't implement [Buildable], should it?
     */
    interface FunctionContinuation<R> {
        fun <T> withAction(f: (LTACCmd, R) -> T): PatternBuilder<T>
        fun <T> withAction(f: (R) -> T): PatternBuilder<T> = withAction { _, d ->
            f(d)
        }

        var name: String?
        fun named(s: String): FunctionContinuation<R> {
            name = s
            return this
        }
    }

    /**
     * As with [FunctionContinuation], but the assigning command in question returned two operands of interest, and logic
     * must be specified to produce a payload result.
     */
    interface FunctionContinuation2<R, U> {
        fun <T> withAction(f: (LTACCmd, R, U) -> T): PatternBuilder<T>
        fun <T> withAction(f: (R, U) -> T): PatternBuilder<T> = withAction { _, d, e ->
            f(d, e)
        }

        /**
         * Specifies that the operation in question is actually commutative. In other words, if this object
         * conceptually represents matching `op(x,y)` where x and y are symbols to be further matched against
         * `p1` and `p2`, then the object returned by this property will also try matching `x` and p2 and `y` against
         * `p2`. NB: there is no logic that operations matched by this object is actually commutative, suffice to say,
         * do not call this unless it makes sense.
         */
        val commutes: Coalesce2<R, U>
    }

    /**
     * Supports the embedding of arbitrary "predicates" into the pattern DSL. The receiver of this extension function expects
     * an assigment command and its location. If the assigning command is of the appropriate form, then it may extract
     * a Symbol upon which further matching should be performed. Returning null indicates the assigning command does not match.
     *
     * For example, to allow matching on the location of a calldata
     * load, one could write:
     * `{ _, cmd -> (cmd as? TACCmd.Simple.AssigningCmd.ByteLoad)?.takeIf { it.base == TACKeyword.CALLDATA.toVar() }?.loc }.lift()`
     *
     * The matching to be performed on the symbol returned by a successful match is specified by passing an argument
     * into the function returned here. For example, to specify that the location of the calldata match must be
     * the result of adding 32 to some constant, one could write:
     *
     * `{ _, cmd -> ... }.lift()((0x20() + Var).first)`
     */
    fun <R> ((LTACCmd, TACCmd.Simple.AssigningCmd) -> TACSymbol?).lift(): (PatternBuilder<R>) -> FunctionContinuation<R> =
        { nested ->
            var nameField: String? = null
            object : FunctionContinuation<R> {
                override fun <T> withAction(f: (LTACCmd, R) -> T): PatternBuilder<T> {
                    return object : PatternBuilder<T>() {
                        override fun toPattern(): Pattern<T> = Pattern.AssigningPattern1(
                            extract = this@lift,
                            nested = nested.toPattern(),
                            out = f,
                            patternName = name
                        )

                        override var name: String?
                            get() = nameField
                            set(value) {
                                nameField = value
                            }
                    }
                }

                override var name: String?
                    get() = nameField
                    set(value) {
                        nameField = value
                    }
            }
        }

    /**
     * Supports embedding arbitrary preciates into the DSL logic that return two operands that are subject to further matching.
     * As in the case for [lift] above, if the receiver function here returns null, the assigning command is considered not to match.
     * If it returns a non-null pair, the operands are subject to further matching according to the arguments passed to the
     * continuation returned by lift.
     */
    fun <R, U> ((LTACCmd, TACCmd.Simple.AssigningCmd) -> Pair<TACSymbol, TACSymbol>?).lift(): (PatternBuilder<R>, PatternBuilder<U>) -> FunctionContinuation2<R, U> =
        { n1, n2 ->
            var nameField: String? = null
            object : FunctionContinuation2<R, U> {
                override fun <T> withAction(f: (LTACCmd, R, U) -> T): PatternBuilder<T> {
                    return object : PatternBuilder<T>() {
                        override fun toPattern(): Pattern<T> {
                            return Pattern.AssigningPattern2(
                                extract = this@lift,
                                left = n1.toPattern(),
                                right = n2.toPattern(),
                                patternName = name,
                                out = f
                            )
                        }

                        override var name: String?
                            get() = nameField
                            set(value) {
                                nameField = value
                            }
                    }
                }

                override val commutes: Coalesce2<R, U>
                    get() = object : Coalesce2<R, U> {
                        override fun <T> withAction(f: (LTACCmd, R, U) -> T): PatternBuilder<T> {
                            val origPattern = Pattern.AssigningPattern2(
                                extract = this@lift,
                                left = n1.toPattern(),
                                right = n2.toPattern(),
                                patternName = name?.let { "orig-$it" },
                                out = f
                            )
                            val commuted = Pattern.AssigningPattern2(
                                extract = this@lift,
                                left = n2.toPattern(),
                                right = n1.toPattern(),
                                patternName = name?.let { "commuted-$it" },
                                out = { l, u, r ->
                                    f(l, r, u)
                                }
                            )
                            return object : PatternBuilder<T>() {
                                override fun toPattern(): Pattern<T> {
                                    return Pattern.XOr(
                                        first = origPattern,
                                        adapt1 = { it },
                                        second = commuted,
                                        adapt2 = { it },
                                        patternName = name
                                    )
                                }
                            }
                        }

                        override var name: String?
                            get() = nameField
                            set(value) {
                                nameField = value
                            }
                    }

            }
        }
}

/**
 * A predicate for checking where a symbol at some location either:
 * 1. Fails to meet some criteria
 * 2. De novo meets some matching criteria, or
 * 3. Is a variable that is subject to further matching.
 *
 * These three cases are the different instantions of the [analysis.PatternMatcher.Continuation] class.
 *
 * This type is used as a "pre-filter" on the symbols that are subject to further matching.
 * Every pattern knows how to declare the type of symbol they expect to match on. For example,
 * `a + b` cannot match against the constant 15, but the literal variable `x` could, provided `x` itself
 * is definitely defined as `x := 15`.
 *
 * In other words, this type represents logic to say:
 * "I know this symbol matches the pattern, or "It definitely DOES NOT match the pattern", or
 * "it's a variable, and we'll have to check its definition sites to be sure".
 *
 * It is expected (but not checked) that a pattern's [SymbolPredicate] is "coherent" w.r.t. immediate results
 * on variables. That is, if a pattern P's symbol predicate indicates that a variable V matches at L with payload X,
 * then invoking the compiled P's query function on V at L should also return X.
 *
 * Note that the [PatternMatcher.Continuation.SubQuery] object takes a [TACSymbol.Var]. It is expected
 * (but not currently checked) that this field *must* be the same object (as determined by reference equality)
 * to the input argument, i.e., the symbol predicate must construct the [analysis.PatternMatcher.Continuation.SubQuery]
 * object via a checked (or unchecked) downcast. Ideally we'd somehow encode that returning [analysis.PatternMatcher.Continuation.SubQuery]
 * must prove that the argument is a [TACSymbol.Var], but we can't do that with Kotlin's baby contract system.
 */
typealias SymbolPredicate<T> = (TACSymbol, LTACCmd) -> PatternMatcher.Continuation<T>

/**
 * Utility class for *syntactic* matching over TAC code.
 *
 * ## Patterns
 * The pattern objects are heart of the Pattern matcher. Each [Pattern] represents some logic on how to match the definition
 * of a variable against a particular syntactic pattern, and on a successful match some logic on how to transform that match
 * into a useful result. It bears repeating: every pattern is ultimately a way to check whether a variable v at some point L
 * is definitely defined according to some syntactic pattern.
 *
 * Patterns naturally compose: a pattern that matches an addition operation can specify that its operands must be defined
 * according to some other patterns, see below for what this means.
 *
 * ## On Matching
 * Each [Pattern] is independent of any particular piece of TAC code.
 * rather the pattern must be compiled via the [PatternMatcher.compilePattern]
 * function. The [SymbolQuery] class returned by this [compilePattern] provides methods for
 * determining whether a variable v at some program point l is defined according to the compiled pattern.
 *
 * Note that in the presence of variable redeclaration, a pattern can match for a particular variable at one
 * point in the program and not in another. This should be relatively rare for anything but loop variables and tac globals (keywords).
 *
 * The behavior of nested matching follows from the above. Suppose we have a pattern that specifies that a variable should
 * be defined according to `op(p1, p2)` where `op` is some operation (a word load, a binary expression, etc.) and `p1` and `p2`
 * are themselves patterns.
 *
 * As matching is (assumed) to be performed over TAC code, the composition of `p1` and `p2` with the matching logic for
 * `op` is relatively simple. Suppose we issue a query for the definition of `v` at `L` (see the documentation for [SymbolQuery]
 * for the calling conventions). The matcher returned by [compilePattern] will first find the definitely reaching definition
 * site for `v at `L` if it exists. Call this definition side D. If there is no such D, the match fails. Otherwise, it checks the RHS of the definition to see
 * if it is of the form `op(x, y)` where x and y are TACSymbol. NB that the pattern matcher does not (and probably will not)
 * consider nested expressions, but as stated above our code is almost always in TAC form anyway, so this is not so much of a restriction.
 *
 * By default, most patterns will follow "trivial" def chains, that is, if you specify that x should be defined according to `a + b`
 * and x is defined by:
 * y = a + b
 * x = y
 *
 * The definition of `x := y` is considered to match.
 *
 * To check that the operands of `op` satisfy the patterns `p1` and `p2` the pattern matching process is invoked recursively.
 * That is, a matcher for `p1` will be queried for `x` at `D`, and similarly, a matcher for `p2` will be queried for `y` at `D`.
 * If `p1` and `p2` themselves have nested pattern this process repeats.
 *
 * If `p1` and `p2` both match, their results are combined via some logic specified in the original pattern, and the result is
 * returned as the result of the query. This "all or nothing" approach is not fundamental, indeed, the logical combinators
 * will sometimes expect one or more of their subpatterns to fail.
 */
object PatternMatcher {
    /**
     * Return value from a [SymbolPredicate]. Each pattern
     * exposes a [SymbolPredicate] which indicating whether a TACSymbol is matchable (or is itself a match).
     * These TACSymbols are generally extracted as an operand for some pattern,
     * and the [SymbolPredicate] is used to determine if the operands are a suitable candidates
     * for matching in the sub-patterns.
     */
    sealed class Continuation<out R> {
        /**
         * The operand ([TACSymbol]) is a good candidate, and matches with payload [res].
         */
        data class Result<R>(val res: R) : Continuation<R>()

        /**
         * The operand is a [TACSymbol.Var] (as recorded in the [from] field) and should be
         * passed to the matcher whose [SymbolPredicate] returned this object. It is expected (but not yet
         * enforced) that the value of this field **must** be the same [TACSymbol]
         * object that was passed to the [SymbolPredicate] function that returns it.
         *
         * NB: By making the user add [from] explicitly, we effectively get safety proof that the downcast
         * on the predicate symbol is safe, but also allow the user to return arbitrary variables to further match against.
         */
        data class SubQuery(val from: TACSymbol.Var) : Continuation<Nothing>()

        /**
         * This symbol does not match (e.g., the argument to the [SymbolPredicate] was a Constant where
         * a Variable was expected.
         */
        object NoMatch : Continuation<Nothing>()
    }

    /**
     * Return value for a predicate over variables. As mentioned in the documentation for [PatternMatcher],
     * patterns *usually* follow trivial def chains. However, when matching a pattern where the
     * expected definition is itself just a plain variable, extra control must b allowed to inspect
     * each step of a trivial def chain for a potential match. The actual meaning of the [VariableMatch]
     * type and its instantiations is actually quite similar to those of the [Continuation]. There are
     * three options:
     * 1. Keep matching against the current variable's definition [Continue]
     * 2. Stop here with a match [Match]
     * 3: Stop here with a failed match.
     *
     * Note that unlike the [analysis.PatternMatcher.Continuation.SubQuery] case (the analogue to [Continue] here),
     * we do not return back the variable to do further matching against, as it is always going to be the
     * variable we are inspecting for a potential match. (i.e. in `{ v -> if(...) { Continue } else ... }` the matching
     * will always continue on the variable `v` passed to the predicate.
     */
    sealed class VariableMatch<out R> {
        object Continue : VariableMatch<Nothing>()
        data class Match<R>(val data: R) : VariableMatch<R>()
        object NoMatch : VariableMatch<Nothing>()
    }

    /**
     * The common type for a [Pattern] that, on a successful match, produces a "payload" of type [R].
     *
     * Most patterns follow the same general structure. They have:
     * 1. a name (for debugging purposes),
     * 2. An "extractor": code to check if some code matches a particular syntactic structure, and which returns
     *  a symbol for further matching,
     * 3. A sub-pattern, which is matched against the symbol returned by the extractor above, and
     * 4. an "out", which translates the payload of the subpattern (and information about the currently matched syntactic element)
     * into the type of the payload.
     */
    sealed class Pattern<out R> {

        /**
         * Adds a transformation [map] on top of the pattern [wrapped].
         * Importantly, if [map] run on the payload of the [wrapped] pattern returns null, then this pattern will be
         * considered as a no-match.
         */
        data class MapNotNull<T, R : Any>(
            val wrapped : Pattern<T>,
            val map : (T, LTACCmd?) -> R?,
            override val patternName: String?
        ) : Pattern<R>() {
            private val wrappedSymbolPredicate = wrapped.toSymbolPredicate()
            override fun toSymbolPredicate() = { s: TACSymbol, lcmd: LTACCmd ->
                when (val r = wrappedSymbolPredicate(s, lcmd)) {
                    Continuation.NoMatch -> Continuation.NoMatch
                    is Continuation.SubQuery -> Continuation.SubQuery(r.from)
                    is Continuation.Result -> map(r.res, lcmd)
                        ?.let { Continuation.Result(it) }
                        ?: Continuation.NoMatch
                }
            }
        }

        /**
         * Checks whether a variable `v` is defined by `op(x)`, where the conditions
         * on `op` are established by [extractor]. The unary expression `op` is
         * passed as the second argument to [extractor], and the location of the command containing
         * the operation is passed as the first argument.
         *
         * If `op` satisfies
         * the conditions of the patterns, it should return the symbol of the (single) operand,
         * which is matched against [inner]. The successul result of a match by [inner] is then
         * transformed by [out], where the first argument is the location of the unary operation (i.e., the same
         * location passed to [extractor])
         */
        data class FromUnaryOp<R, T>(
            val extractor: (CmdPointer, TACExpr.UnaryExp) -> TACSymbol?,
            val inner: Pattern<T>,
            val out: (LTACCmd, T) -> R,
            override val patternName: String?
        ) : Pattern<R>() {
            constructor(
                extractor: (CmdPointer, TACExpr.UnaryExp) -> TACSymbol?,
                inner: Pattern<T>,
                out: (LTACCmd, T) -> R
            ) : this(extractor, inner, out, null)

            override fun toSymbolPredicate(): SymbolPredicate<R> = this::expectVariable
        }

        /**
         * Public ONLY for annoying kotlin reasons. Never instantiate yourself
         */
        class Nested<T>(var innerQuery: SymbolQuery<ConstLattice<T>>? = null, var predicate: SymbolPredicate<T>? = null, override val patternName: String?) : Pattern<T>() {
            override fun toSymbolPredicate(): SymbolPredicate<T> = { sym, where ->
                predicate!!.invoke(sym, where)
            }
        }

        /**
         * Build a recursive pattern. [builder] takes a pattern that, after compilation, will match exactly the same pattern
         * returned from [builder]. It is expected that [builder] will use its argument as a "base case" in some complicated pattern.
         *
         * Care should be used when using this class: no attempt is made to protect against infinite recursion: i.e.,
         * RecursivePattern({ it -> it }) will probably loop forever.
         */
        class RecursivePattern<T>(override val patternName: String? = null, val builder: (Pattern<T>) -> Pattern<T>) : Pattern<T>() {
            val nested: Nested<T> = Nested(patternName = null)
            val patt = builder(nested)

            init {
                nested.predicate = patt.toSymbolPredicate()
            }

            override fun toSymbolPredicate(): SymbolPredicate<T> = patt.toSymbolPredicate()
        }

        abstract val patternName: String?

        val name: String get() = patternName!!

        /**
         * If this pattern has an informative name defined, issue a debug log on the pattern logger.
         */
        fun debug(s: () -> String) {
            if (this.patternName != null) {
                logger.debug(s)
            }
        }

        /**
         * As the [FromBinOp] case, except the operation is expected to be a binary operation,
         * and the extractor should return a pair of symbols, one for each operand. The left
         * operand is then matched against [left], and the right operand is matched against [right].
         *
         * On successful sub-matches, the results of [left] and [right] are combined with [out],
         * where the location is again the location of the binary operation that was matched.
         */
        data class FromBinOp<R, T, U>(
            val extractor: (CmdPointer, TACExpr.BinExp) -> Pair<TACSymbol, TACSymbol>?,
            val left: Pattern<T>, val right: Pattern<U>,
            val out: (LTACCmd, T, U) -> R,
            override val patternName: String?
        ) : Pattern<R>() {
            @Suppress("unused")
            constructor(
                extractor: (CmdPointer, TACExpr.BinExp) -> Pair<TACSymbol, TACSymbol>?,
                left: Pattern<T>, right: Pattern<U>,
                out: (LTACCmd, T, U) -> R
            ) : this(extractor, left, right, out, null)

            companion object {
                /**
                 * Convenience function that matches any binary expression op(x, y) where
                 * op <: [t]. The operands are matched according to [p1] and [p2] and combined with the
                 * combiner [comb].
                 */
                fun <T : TACExpr.BinExp, R, Q, U> from(
                    t: Class<T>,
                    p1: Pattern<R>,
                    p2: Pattern<Q>,
                    comb: (LTACCmd, R, Q) -> U,
                    patternName: String? = null
                ): FromBinOp<U, *, *> {
                    return FromBinOp(extractor = { _ /* where */, op ->
                        if (t.isInstance(op)) {
                            val op_: T = t.cast(op)
                            op_.o1AsNullableTACSymbol()?.let { o1_ ->
                                op_.o2AsNullableTACSymbol()?.let { o2_ ->
                                    Pair(o1_, o2_)
                                }
                            }
                        } else {
                            null
                        }
                    },
                        left = p1,
                        right = p2,
                        out = { w, o1, o2 -> comb(w, o1, o2) },
                        patternName = patternName
                    )
                }

                /**
                 * A version of [from] where [t] is expected to be a commutative operation. Actually
                 * creates two patterns:
                 * op(p1, p2) and op(p2, p1) and joins them with an [XOr]. The [comb1to2] and [comb2to1]
                 * are used to lift the results of p1 and p2 into the U type.
                 */
                fun <T : TACExpr.BinExp, R, Q, U> fromCommuted(
                    t: Class<T>,
                    p1: Pattern<R>,
                    p2: Pattern<Q>,
                    comb1to2: (LTACCmd, R, Q) -> U,
                    comb2to1: (LTACCmd, Q, R) -> U
                ) =
                    XOr.orSame(
                        from(t, p1, p2, comb1to2),
                        from(t, p2, p1, comb2to1)
                    )
            }

            override fun toSymbolPredicate(): SymbolPredicate<R> = this::expectVariable
        }

        /**
         * Matches constants. Unlike the other cases, the extractor here does not return a symbol, but rather a nullable
         * type [T]. If null, then the [TACSymbol] is considered not to match. Otherwise the result is passed into [out]
         * to generate the payload for [FromConstant].
         *
         * XXX(jtoman): what a weird level of indirection! Why?
         */
        data class FromConstant<R, T>(
            val extractor: (TACSymbol.Const, LTACCmd) -> T?,
            val out: (LTACCmd, T) -> R,
            override val patternName: String?
        ) : Pattern<R>() {
            constructor(extractor: (TACSymbol.Const) -> T?, out: (LTACCmd, T) -> R) : this({ c, _ -> extractor(c) }, out, null)
            constructor(extractor: (TACSymbol.Const, LTACCmd) -> T?, out: (LTACCmd, T) -> R) : this(extractor, out, null)

            companion object {
                /**
                 * Create a pattern with the trivial payload (Unit) that matches exactly a constant with the value [bigInteger]
                 */
                fun exactly(bigInteger: BigInteger): FromConstant<Unit, Unit> {
                    return FromConstant(extractor = { it ->
                        if (it.value == bigInteger) {
                            Unit
                        } else {
                            null
                        }
                    }, out = { _, _ -> Unit })
                }

                /**
                 * Create a pattern that matches a constant whose value satisfies the predicate [pred]. On a succesful match,
                 * the payload is the [BigInteger] value of the constant that matched.
                 */
                fun withPredicate(pred: (BigInteger) -> Boolean): FromConstant<BigInteger, BigInteger> {
                    return FromConstant(
                        extractor = { it ->
                            if (pred(it.value)) {
                                it.value
                            } else {
                                null
                            }
                        },
                        out = { _, c -> c }
                    )
                }

                /**
                 * 2^k - 1 for some k >= 1
                 */
                @Suppress("unused")
                val powerOfTwoMinusOne = withPredicate {
                    it.bitLength() > 0 && it == (BigInteger.TWO.pow(
                        it.bitLength()
                    ) - BigInteger.ONE)
                }

                /**
                 * Matches any constant.
                 */
                val anyConst = FromConstant({ it -> it.value }, { _, c -> c })
            }

            /*
             * A instructive example of a non-trivial symbol predicate.
             */
            override fun toSymbolPredicate(): SymbolPredicate<R> = { s, where ->
                /**
                 * This is called by some other pattern when we have a symbol [s] that we
                 * want to know satifies the predicate in [extractor].
                 *
                 * If [s] is a Variable, it could be defined as a constant, so indicate that
                 * the matcher for the [FromConstant] should be invoked with [s].
                 */
                if (s is TACSymbol.Var) {
                    Continuation.SubQuery(s)
                /**
                 * This case is actually dead code, but it's here for completeness
                 */
                } else if (s !is TACSymbol.Const) {
                    Continuation.NoMatch
                } else {
                    /**
                     * Then we don't need to do a recursive search for the definition to see
                     * if it is a constant, we are looking at a constant now. Just apply the predicate and
                     * determine match/no-match
                     */
                    extractor(s, where)?.let {
                        out(where, it).let {
                            Continuation.Result(it)
                        }
                    } ?: Continuation.NoMatch
                }
            }

            /**
             * Extend the preciate of this object with [pred].
             */
            fun takeOnlyIf(pred : (BigInteger) -> Boolean) =
                this.copy(extractor = { c, where -> extractor(c, where).takeIf { pred(c.value) } })
        }

        /**
         * Logical (short-circuiting) combiner. Given two patterns [first] and [second], this pattern will match
         * any code that matches [first] or matches [second]. If both would match, the payload of [first] is preferred.
         * Note that [first] and [second] can return different types, [adapt1] and [adapt2] are for transforming them into
         * the (single) payload type of [Or].
         *
         * Note that like other "adapters", these callbacks do *NOT* receive a location, because this logical matching
         * doesn't actually correspond to any point in the code. However, the sub-patterns will receive the locations at
         * the point of their match.
         */
        data class Or<T, U, R>(
            val first: Pattern<T>,
            val adapt1: (T) -> R,
            val second: Pattern<U>,
            val adapt2: (U) -> R,
            override val patternName: String?
        ) : Pattern<R>() {
            constructor(first: Pattern<T>, adapt1: (T) -> R, second: Pattern<U>, adapt2: (U) -> R) : this(
                first,
                adapt1,
                second,
                adapt2,
                null
            )

            override fun toSymbolPredicate(): SymbolPredicate<R> {
                val p1 = first.toSymbolPredicate()
                val p2 = second.toSymbolPredicate()
                return { s, where ->
                    when(val r1 = p1(s, where)) {
                        is Continuation.Result -> Continuation.Result(adapt1(r1.res))
                        is Continuation.SubQuery -> {
                            when(val r2 = p2(s, where)) {
                                Continuation.NoMatch -> r1
                                is Continuation.Result -> {
                                    if(r1.from != s) {
                                        Continuation.NoMatch
                                    } else{
                                        r1
                                    }
                                }
                                is Continuation.SubQuery -> {
                                    if(r1.from != r2.from) {
                                        Continuation.NoMatch
                                    } else {
                                        r1
                                    }
                                }
                            }
                        }
                        is Continuation.NoMatch -> {
                            when(val r2 = p2(s, where)) {
                                Continuation.NoMatch,
                                is Continuation.SubQuery -> r2.uncheckedAs()
                                is Continuation.Result -> Continuation.Result(adapt2(r2.res))
                            }
                        }
                    }
                }
            }
        }

        /**
         * Logical xor. Matches everything that [first] and [second] match. If however, both [first] and [second]
         * match, then the overall match is considered ambiguous and the match will fail.
         * As in [Or], the differing payload types of [first] and [second] are adapted to the single payload type [R]
         * via [adapt1] and [adapt2] respectively.
         */
        data class XOr<T, U, R>(
            val first: Pattern<T>,
            val adapt1: (T) -> R,
            val second: Pattern<U>,
            val adapt2: (U) -> R,
            override val patternName: String?
        ) : Pattern<R>() {
            constructor(
                first: Pattern<T>,
                adapt1: (T) -> R,
                second: Pattern<U>,
                adapt2: (U) -> R
            ) : this(first, adapt1, second, adapt2, null)

            companion object {
                private fun <R> orSameTwo(p1 : Pattern<R>, p2 : Pattern<R>) =
                    XOr(first = p1, adapt1 = { k -> k }, second = p2, adapt2 = { k -> k })

                /**
                 * Combine two patterns known to have the same output type (using trivial adapters).
                 */
                fun <R> orSame(firstP : Pattern<R>, secondP : Pattern<R>, vararg rest: Pattern<R>) =
                    rest.fold(orSameTwo(firstP, secondP), ::orSameTwo)

                /**
                 * As above, but using the debug name [name].
                 */
                fun <R> orSame(name: String, vararg p: Pattern<R>): Pattern<R> =
                    p.reduce { p1, p2 ->
                        XOr(first = p1, adapt1 = { k -> k }, second = p2, adapt2 = { k -> k }, patternName = name)
                    }

                @Suppress("unused")
                fun <R, U> orMap(map: (R) -> U, vararg p: Pattern<R>) : Pattern<U> =
                    p.fold(Fail) { Curr: Pattern<U>, nxt ->
                        XOr(
                            first = Curr,
                            adapt1 = { it },
                            second = nxt,
                            adapt2 = { map(it) }
                        )
                    }
            }

            override fun toSymbolPredicate(): SymbolPredicate<R> {
                val p1 = first.toSymbolPredicate()
                val p2 = second.toSymbolPredicate()
                return out@{ s, w ->
                    val r1 = p1(s, w)
                    val r2 = p2(s, w)
                    if (r1 is Continuation.Result) {
                        return@out if (r2 is Continuation.NoMatch || (r1 == r2)) {
                            Continuation.Result(adapt1(r1.res))
                        } else {
                            Continuation.NoMatch
                        }
                    } else if (r1 is Continuation.SubQuery) {
                        if (r2 is Continuation.NoMatch || (r2 is Continuation.SubQuery && r2.from == r1.from)) {
                            r1
                        } else {
                            Continuation.NoMatch
                        }
                    } else {
                        check(r1 is Continuation.NoMatch)
                        when (r2) {
                            is Continuation.NoMatch -> r2
                            is Continuation.SubQuery -> r2
                            is Continuation.Result -> Continuation.Result(adapt2(r2.res))
                        }
                    }
                }
            }

        }

        /**
         * Matches any symbol. Note that the [out] here serves the purpose of both extractor and "out" function
         * and it is total: use of this pattern means that truly any Symbol will do.
         */
        data class AnySymbol<T>(
                val out: (LTACCmd, TACSymbol) -> T,
                override val patternName: String? = null
        ) : Pattern<T>() {
            override fun toSymbolPredicate(): SymbolPredicate<T> {
                return { tacSymbol: TACSymbol, ltacCmd: LTACCmd ->
                    out(ltacCmd, tacSymbol).let {
                        Continuation.Result(it)
                    }
                }
            }

            companion object {
                /**
                 * Match any symbol and use the matched symbol as the payload.
                 */
                val anySymbol = AnySymbol(out = { _, s -> s })
                val anyLSymbol = AnySymbol(out = { l, s -> LTACSymbol(l.ptr, s) })
            }
        }

        /**
         * Match the tac expression i ? t : e, where
         * i is subject to the pattern [cond], t is subject to [thenCase], and
         * `e` is subject to [elseCase]. The result of these matches is combined with [comb].
         * Note that unlike other patterns, the symbols to extract are not specified by the instantiator,
         * as this always matches against [TACExpr.TernaryExp.Ite], we know to always extract the three fields
          [TACExpr.TernaryExp.Ite.i], [TACExpr.TernaryExp.Ite.t], and [TACExpr.TernaryExp.Ite.e]
         *
         */
        data class Ite<T, U, V, R>(
            val cond: Pattern<T>,
            val thenCase: Pattern<U>,
            val elseCase: Pattern<V>,
            val comb: (LTACCmd, T, U, V) -> R,
            override val patternName: String?
        ) : Pattern<R>() {
            constructor(
                cond: Pattern<T>,
                thenCase: Pattern<U>,
                elseCase: Pattern<V>,
                comb: (LTACCmd, T, U, V) -> R
            ) : this(cond, thenCase, elseCase, comb, null)

            override fun toSymbolPredicate(): SymbolPredicate<R> = this::expectVariable

        }

        /**
         * "Don't care". Will match anything with the trivial payload.
         */
        object Trivial : Pattern<Unit>() {
            override fun toSymbolPredicate(): SymbolPredicate<Unit> = { _, _ ->
                Continuation.Result(Unit)
            }

            override val patternName: String?
                get() = null
        }

        /**
         * A pattern to find a variable v that satisfies the predicate implicit in [extractor].
         * As mentioned in the documentation for [VariableMatch], the matcher for this pattern is a special
         * case, and it does not automatically traverse trivial def chains. Rather, each step of the def chain is
         * fed into the [extractor], looking for a match along the way.
         */
        data class FromVar<R>(
            val extractor: (LTACCmd, TACSymbol.Var) -> VariableMatch<R>,
            override val patternName: String?
        ) : Pattern<R>() {
            constructor(extractor: (LTACCmd, TACSymbol.Var) -> VariableMatch<R>) : this(extractor, null)

            companion object {
                @Suppress("unused")
                fun matchExactly(v: TACSymbol.Var): FromVar<Unit> =
                    FromVar(extractor = { _, v2 ->
                        if (v == v2) {
                            Match(Unit)
                        } else {
                            Continue
                        }
                    })

                val anyVar = FromVar { _, v -> Match(v) }
                val anyLVar = FromVar { l, v -> Match(LTACVar(l.ptr, v)) }
            }

            override fun toSymbolPredicate(): SymbolPredicate<R> = { s, where ->
                if (s !is TACSymbol.Var) {
                    Continuation.NoMatch
                } else {
                    when (val ext = extractor(where, s)) {
                        Continue -> Continuation.SubQuery(s)
                        is Match -> Continuation.Result(ext.data)
                        VariableMatch.NoMatch -> Continuation.NoMatch
                    }
                }
            }

        }

        /**
         * A pattern that matches nothing.
         */
        object Fail : Pattern<Nothing>() {

            override fun toSymbolPredicate(): SymbolPredicate<Nothing> = { _, _ -> Continuation.NoMatch }

            override val patternName: String?
                get() = null
        }

        @Suppress("FunctionName")
        companion object {

            fun <R> asBuildable(patt: Pattern<R>) = object : PatternBuilder<R>() {
                override fun toPattern(): Pattern<R> = patt
            }

            fun <R> Pattern<R>.toBuildable() = asBuildable(this)

            fun <R> Hash(
                extractor: (TACCmd.Simple.AssigningCmd.AssignSha3Cmd) -> R?,
                patternName: String?
            ): Pattern<R> =
                AssigningPattern0(TACCmd.Simple.AssigningCmd.AssignSha3Cmd::class.java, { _, d ->
                    extractor(d)?.let {
                        ConstLattice.Match(it)
                    } ?: ConstLattice.NoMatch
                }, patternName)

            fun <R> Hash(extractor: (TACCmd.Simple.AssigningCmd.AssignSha3Cmd) -> R?): Pattern<R> =
                Hash(extractor, null)

            fun <R> SimpleHash(
                extractor: (TACCmd.Simple.AssigningCmd.AssignSimpleSha3Cmd) -> R?,
                patternName: String?
            ) =
                AssigningPattern0(
                    TACCmd.Simple.AssigningCmd.AssignSimpleSha3Cmd::class.java,
                    { _, d ->
                        extractor(d)?.let { ConstLattice.Match(it) } ?: ConstLattice.NoMatch
                    }, patternName
                )

            fun <R> SimpleHash(extractor: (TACCmd.Simple.AssigningCmd.AssignSimpleSha3Cmd) -> R?) =
                SimpleHash(extractor, null)

            fun <R, T> StorageRead(idxPat: Pattern<T>, out: (LTACCmd, T) -> R, patternName: String?) =
                AssigningPattern1(
                    TACCmd.Simple.AssigningCmd.WordLoad::class.java,
                    extract = { _, d: TACCmd.Simple.AssigningCmd.WordLoad ->
                        if (d.base.meta[STORAGE_KEY] == null) {
                            null
                        } else {
                            d.loc
                        }
                    },
                    out = out,
                    nested = idxPat,
                    patternName = patternName
                )

            @Suppress("FunctionName")
            fun <R, T> StorageRead(idxPat: Pattern<T>, out: (LTACCmd, T) -> R) = StorageRead(idxPat, out, null)
        }

        /**
         * One of the more "general" forms of patterns. This pattern matches a definition of `x` if the definitely reaching
         * definition for `x` is an assigning command and when passed to [f], f returns a [ConstLattice.Match].
         *
         * The 0 in this name indicates the number of sub-patterns that are used, in this case, 0.
         */
        data class AssigningPattern0<R>(
            val f: (LTACCmd, TACCmd.Simple.AssigningCmd) -> ConstLattice<R>,
            override val patternName: String?
        ) : Pattern<R>() {
            constructor(f: (LTACCmd, TACCmd.Simple.AssigningCmd) -> ConstLattice<R>) : this(f, null)

            companion object {
                /**
                 * Convenience function to create an assigning pattern that looks for a specific instance
                 * of [vc.data.TACCmd.Simple.AssigningCmd] of type [klass] and which further filters values of
                 * this type according to [extract] (which has a tigher bound on the type than that expected by [AssigningPattern0]).
                 */
                operator fun <K : TACCmd.Simple.AssigningCmd, R> invoke(
                    klass: Class<K>,
                    extract: (LTACCmd, K) -> ConstLattice<R>,
                    patternName: String?
                ): AssigningPattern0<R> {
                    return AssigningPattern0(f = { where, d ->
                        if (klass.isInstance(d)) {
                            @Suppress("UNCHECKED_CAST")
                            extract(where, d as K)
                        } else {
                            ConstLattice.NoMatch
                        }
                    }, patternName = patternName)
                }

                /**
                 * As above, but without a debugging pattern name.
                 */
                operator fun <K : TACCmd.Simple.AssigningCmd, R> invoke(
                    klass: Class<K>,
                    extract: (LTACCmd, K) -> ConstLattice<R>
                ): AssigningPattern0<R> = invoke(klass, extract, null)

            }

            override fun toSymbolPredicate(): SymbolPredicate<R> = this::expectVariable

        }

        /**
         * A general assignment pattern, where the extractor is passed the [vc.data.TACCmd.Simple.AssigningCmd]
         * without any up-front pre-filtering. Unlike [AssigningPattern0], the extractor can return a (single) operand
         * tacsymbol which is subject to further matching via [nested], the result of which is transformed into
         * this pattern's payload via [out].
         */
        data class AssigningPattern1<R, T>(
            val extract: (LTACCmd, TACCmd.Simple.AssigningCmd) -> TACSymbol?,
            val nested: Pattern<T>,
            val out: (LTACCmd, T) -> R,
            override val patternName: String?
        ) : Pattern<R>() {
            constructor(
                extract: (LTACCmd, TACCmd.Simple.AssigningCmd) -> TACSymbol?,
                nested: Pattern<T>,
                out: (LTACCmd, T) -> R
            ) : this(extract, nested, out, null)

            companion object {
                /**
                 * A convenience function to create an [AssigningPattern1] that automatically
                 * pre-filters only [vc.data.TACCmd.Simple.AssigningCmd] of type [klass]/[K],
                 * and only passes those commands to [extract] (which has a narrower type).
                 */
                operator fun <K : TACCmd.Simple.AssigningCmd, R, T> invoke(
                    klass: Class<K>,
                    extract: (LTACCmd, K) -> TACSymbol?,
                    nested: Pattern<T>,
                    out: (LTACCmd, T) -> R,
                    patternName: String?
                ): AssigningPattern1<R, T> {
                    return AssigningPattern1(extract = { where, d ->
                        if (klass.isInstance(d)) {
                            extract(where, d.uncheckedAs())
                        } else {
                            null
                        }
                    }, nested = nested, out = out, patternName = patternName)
                }

                /**
                 * As above, but without a debugging name.
                 */
                operator fun <K : TACCmd.Simple.AssigningCmd, R, T> invoke(
                    klass: Class<K>,
                    extract: (LTACCmd, K) -> TACSymbol?,
                    nested: Pattern<T>,
                    out: (LTACCmd, T) -> R
                ): AssigningPattern1<R, T> {
                    return AssigningPattern1(klass, extract, nested, out, null)
                }
            }

            override fun toSymbolPredicate(): SymbolPredicate<R> = this::expectVariable

        }

        /**
         * The binary version of [AssigningPattern1]. As elsewhere, the first element of the returned pair
         * is matched against [left], the second element is matched against [right]. The results of both successful
         * matches are combined with [out].
         */
        data class AssigningPattern2<R, T, U>(
            val extract: (LTACCmd, TACCmd.Simple.AssigningCmd) -> Pair<TACSymbol, TACSymbol>?,
            val left: Pattern<T>,
            val right: Pattern<U>,
            val out: (LTACCmd, T, U) -> R,
            override val patternName: String?
        ) : Pattern<R>() {
            @Suppress("unused")
            constructor(
                extract: (LTACCmd, TACCmd.Simple.AssigningCmd) -> Pair<TACSymbol, TACSymbol>?,
                left: Pattern<T>,
                right: Pattern<U>,
                out: (LTACCmd, T, U) -> R
            ) : this(extract, left, right, out, null)

            companion object {
                /**
                 * Convenience function which creates an [AssigningPattern2] which prefilters the [vc.data.TACCmd.Simple.AssigningCmd]
                 * passed to the extractor to be only those of type [klass]/[klass]. The paramters of this
                 * function have the same interpretation as the fields of this class, except [extract] accepts
                 * a more specific type.
                 */
                operator fun <K : TACCmd.Simple.AssigningCmd, T, U, R> invoke(
                    klass: Class<K>,
                    extract: (LTACCmd, K) -> Pair<TACSymbol, TACSymbol>?,
                    left: Pattern<T>,
                    right: Pattern<U>,
                    out: (LTACCmd, T, U) -> R,
                    patternName: String?
                ): AssigningPattern2<R, T, U> {
                    return AssigningPattern2({ where, d ->
                        if (klass.isInstance(d)) {
                            @Suppress("UNCHECKED_CAST")
                            extract(where, d as K)
                        } else {
                            null
                        }
                    }, left, right, out, patternName)
                }

                /**
                 * Nameless version of the convenience function [invoke].
                 */
                operator fun <K : TACCmd.Simple.AssigningCmd, T, U, R> invoke(
                    klass: Class<K>,
                    extract: (LTACCmd, K) -> Pair<TACSymbol, TACSymbol>?,
                    left: Pattern<T>,
                    right: Pattern<U>,
                    out: (LTACCmd, T, U) -> R
                ): AssigningPattern2<R, T, U> {
                    return AssigningPattern2(klass, extract, left, right, out, null as String?)
                }
            }

            override fun toSymbolPredicate(): SymbolPredicate<R> = this::expectVariable

        }

        /**
         * Does what is says on the tin: used by patterns whose symbol predicates always expect to be a subquery
         * on a variable (e.g., a pattern of the form `p1 + p2` can definitely reject a constant, but can't know
         * without a recursive query whether a variable `v` matches the expected form).
         */
        protected fun expectVariable(
            s: TACSymbol,
            @Suppress("UNUSED_PARAMETER") l: LTACCmd
        ) = if (s is TACSymbol.Var) {
            Continuation.SubQuery(s)
        } else {
            Continuation.NoMatch
        }

        /**
         * Return a predicate that indicates whether a [TACSymbol] is a good candidate for matching
         * against this pattern, or whether the symbol is known de novo to match the pattern.
         *
         * The result of this judgment (do or do not attempt to match, or finish with a result) is
         * indicated by the [Continuation] object returned by the [SymbolPredicate]
         */
        abstract fun toSymbolPredicate(): SymbolPredicate<R>
    }


    sealed class ConstLattice<out R> {
        data class Match<R>(val data: R) : ConstLattice<R>()
        object NoMatch : ConstLattice<Nothing>()

        companion object {
            fun <R> join(a: ConstLattice<R>, b: ConstLattice<R>): ConstLattice<R> =
                if (a == b) {
                    a
                } else {
                    NoMatch
                }

        }

        fun toNullableResult(): R? =
            when (this) {
                is Match -> this.data
                is NoMatch -> null
            }
    }

    /**
     * Return a [SymbolQuery] which answers queries about matches against [patt] in [graph].
     *
     * Optionally restrict traversals by the symbol query with [traverseFilter], when traversing
     * definition chains, any pattern that must traverse a variable for which [traverseFilter] returns
     * false is considered an automatic failure.
     */
    fun <R> compilePattern(graph: TACCommandGraph,
                           patt: Pattern<R>,
                           traverseFilter : (TACSymbol.Var) -> Boolean = { _ -> true }
    ): SymbolQuery<ConstLattice<R>> {
        @Suppress("UNCHECKED_CAST")
        return when (patt) {
            is Pattern.FromUnaryOp<R, *> -> compileUnaryOp(graph, patt, traverseFilter)
            is Pattern.FromBinOp<R, *, *> -> compileBinOp(graph, patt, traverseFilter)
            is Pattern.FromConstant<R, *> -> compileConstant(graph, patt, traverseFilter)
            is Pattern.FromVar -> compileFromVar(graph, patt, traverseFilter)
            is Pattern.Fail -> object : SymbolQuery<ConstLattice<R>> {
                override fun query(q: TACSymbol.Var, src: LTACCmd): ConstLattice<R> =
                    ConstLattice.NoMatch

                override fun queryFrom(start: LTACCmdView<TACCmd.Simple.AssigningCmd>): ConstLattice<R> =
                    ConstLattice.NoMatch
            }
            is Pattern.XOr<*, *, R> -> compileXOrPattern(graph, patt, traverseFilter)
            is Pattern.AssigningPattern0<R> -> compileAssigningPattern0(graph, patt, traverseFilter)
            is Pattern.AssigningPattern1<R, *> -> {
                compileAssigningPattern1(graph, patt, traverseFilter)
            }
            is Pattern.AssigningPattern2<R, *, *> -> {
                compileAssigningPattern2(graph, patt, traverseFilter)
            }
            is Pattern.Or<*, *, R> -> compileOrPattern(graph, patt, traverseFilter)
            is Pattern.Ite<*, *, *, R> -> compileItePattern(graph, patt, traverseFilter)
            // the only way this could be typed is if R = Unit
            Pattern.Trivial -> (object : SymbolQuery<ConstLattice<Unit>> {
                override fun query(q: TACSymbol.Var, src: LTACCmd): ConstLattice<Unit> = ConstLattice.Match(Unit)
                override fun queryFrom(start: LTACCmdView<TACCmd.Simple.AssigningCmd>): ConstLattice<Unit> =
                    ConstLattice.Match(Unit)
            }) as SymbolQuery<ConstLattice<R>>
            is Pattern.AnySymbol -> (object : SymbolQuery<ConstLattice<R>> {
                override fun query(q: TACSymbol.Var, src: LTACCmd): ConstLattice<R> {
                    return ConstLattice.Match(patt.out(src, q))
                }

                override fun queryFrom(start: LTACCmdView<TACCmd.Simple.AssigningCmd>): ConstLattice<R> {
                    return ConstLattice.Match(patt.out(start.wrapped, start.cmd.lhs))
                }
            })
            is Pattern.Nested -> {
                return (object : SymbolQuery<ConstLattice<R>> {
                    override fun query(q: TACSymbol.Var, src: LTACCmd): ConstLattice<R> {
                        return patt.innerQuery!!.query(q, src)
                    }

                    override fun queryFrom(start: LTACCmdView<TACCmd.Simple.AssigningCmd>): ConstLattice<R> {
                        return patt.innerQuery!!.queryFrom(start)
                    }
                })
            }
            is Pattern.RecursivePattern -> {
                val toReturn = compilePattern(graph, patt.patt)
                patt.nested.innerQuery = toReturn
                toReturn
            }

            is Pattern.MapNotNull<*, *> ->
                compileMapNotNull(graph, patt).uncheckedAs()
        }
    }


    private fun <T, R : Any> compileMapNotNull(
        graph: TACCommandGraph,
        patt: Pattern.MapNotNull<T, R>,
    ) = object : SymbolQuery<ConstLattice<R>> {
        private val inner = compilePattern(graph, patt.wrapped)

        fun ConstLattice<T>.applyExtra(src: LTACCmd): ConstLattice<R> = when(this) {
            is ConstLattice.Match -> patt.map(this.data, src)
                ?.let { ConstLattice.Match(it) }
                ?: ConstLattice.NoMatch
            ConstLattice.NoMatch -> ConstLattice.NoMatch
        }

        override fun query(q: TACSymbol.Var, src: LTACCmd) =
            inner.query(q, src).applyExtra(src)

        override fun queryFrom(start: LTACCmdView<TACCmd.Simple.AssigningCmd>) =
            inner.queryFrom(start).applyExtra(start.wrapped)
    }


    private fun <R, T, U> compileAssigningPattern2(
        graph: TACCommandGraph,
        patt: Pattern.AssigningPattern2<R, T, U>,
        traverseFilter: (TACSymbol.Var) -> Boolean
    ): SymbolQuery<ConstLattice<R>> {
        val pLeft = compilePattern(graph, patt.left, traverseFilter)
        val pRight = compilePattern(graph, patt.right, traverseFilter)
        val sLeft = patt.left.toSymbolPredicate()
        val sRight = patt.right.toSymbolPredicate()
        return object : LoggingFlowQuery<R>(patt, graph) {
            override fun filterTraversal(l: LTACCmdView<TACCmd.Simple.AssigningCmd>): Boolean {
                return traverseFilter(l.cmd.lhs)
            }

            override fun stepAssignment(
                first: TACCmd.Simple.AssigningCmd,
                where: CmdPointer,
                active: Set<Query>
            ): ConstLattice<R> {
                val lWhere = graph.elab(where)
                if (first is TACCmd.Simple.AssigningCmd.AssignExpCmd && first.rhs is TACExpr.Sym) {
                    patt.taggedDebug {
                        "Reached assignment in terms of symbol at $where"
                    }
                    return if (first.rhs is TACExpr.Sym.Var) {
                        patt.taggedDebug {
                            "Found definition in terms of a variable ${first.rhs.s}, subquery"
                        }
                        query(first.rhs.s, where, active)
                    } else {
                        patt.taggedDebug {
                            "Found definition in terms of constant. Failing"
                        }
                        ConstLattice.NoMatch
                    }
                }
                val extracted = patt.extract(lWhere, first)
                patt.taggedDebug {
                    "Extracting from RHS yielded $extracted"
                }
                return extracted?.let outer@{ (leftSym, rightSym) ->
                    val cLeft = sLeft(leftSym, lWhere)
                    patt.taggedDebug {
                        "Continuation for left symbol $leftSym is $cLeft"
                    }
                    when (cLeft) {
                        is Continuation.NoMatch -> return@outer ConstLattice.NoMatch
                        is Continuation.Result -> cLeft.res
                        is Continuation.SubQuery -> pLeft.query(cLeft.from, lWhere).toNullableResult()
                    }?.let { leftRes ->
                        patt.taggedDebug {
                            "Processing left operand yielded $leftRes"
                        }
                        val cRight = sRight(rightSym, lWhere)
                        patt.taggedDebug {
                            "Continuation for right symbol $rightSym yielded $cRight"
                        }
                        when (cRight) {
                            is Continuation.NoMatch -> return@outer ConstLattice.NoMatch
                            is Continuation.Result -> cRight.res
                            is Continuation.SubQuery -> {
                                pRight.query(cRight.from, lWhere).toNullableResult()
                            }
                        }?.let { rightRes ->
                            patt.taggedDebug {
                                "Processing right operand yielded $rightRes. Combining and succeeding"
                            }
                            ConstLattice.Match(patt.out(lWhere, leftRes, rightRes))
                        }
                    }
                } ?: ConstLattice.NoMatch.also {
                    patt.taggedDebug {
                        "Processing assignment at $lWhere failed"
                    }
                }
            }
        }
    }

    private fun <R, T> compileAssigningPattern1(
        graph: TACCommandGraph,
        patt: Pattern.AssigningPattern1<R, T>,
        traverseFilter: (TACSymbol.Var) -> Boolean
    ): SymbolQuery<ConstLattice<R>> {
        val p1 = compilePattern(graph, patt.nested, traverseFilter)
        val s1 = patt.nested.toSymbolPredicate()
        return object : LoggingFlowQuery<R>(patt, graph) {
            override fun filterTraversal(l: LTACCmdView<TACCmd.Simple.AssigningCmd>): Boolean {
                return traverseFilter(l.cmd.lhs)
            }

            override fun stepAssignment(
                first: TACCmd.Simple.AssigningCmd,
                where: CmdPointer,
                active: Set<Query>
            ): ConstLattice<R> {
                if (first is TACCmd.Simple.AssigningCmd.AssignExpCmd && first.rhs is TACExpr.Sym) {
                    return if (first.rhs is TACExpr.Sym.Var) {
                        patt.taggedDebug {
                            "Found definition in terms of variable ${first.rhs.s} @ ${graph.elab(where)}. Sub-query"
                        }
                        this.query(first.rhs.s, where, active)
                    } else {
                        patt.taggedDebug {
                            "Found definition in terms of constant @ ${graph.elab(where)}. fail"
                        }
                        ConstLattice.NoMatch
                    }
                }
                val lWhere = graph.elab(where)
                patt.taggedDebug {
                    "Found assignment at $lWhere"
                }
                return patt.extract(lWhere, first)?.let {
                    patt.taggedDebug {
                        "Extracted symbol $it from assignment at $lWhere"
                    }
                    s1(it, lWhere)
                }?.let {
                    patt.taggedDebug {
                        "Continuation for symbol is $it"
                    }
                    when (it) {
                        is Continuation.Result -> ConstLattice.Match(patt.out(lWhere, it.res))
                        is Continuation.SubQuery -> p1.query(it.from, lWhere).toNullableResult()?.let {
                            ConstLattice.Match(patt.out(lWhere, it))
                        }
                        Continuation.NoMatch -> ConstLattice.NoMatch
                    }
                } ?: ConstLattice.NoMatch.also {
                    patt.taggedDebug { "Failed to extract or sub-query at $lWhere" }
                }
            }
        }
    }

    private fun <T, U, R> compileOrPattern(
        graph: TACCommandGraph,
        patt: Pattern.Or<T, U, R>,
        traverseFilter: (TACSymbol.Var) -> Boolean
    ): SymbolQuery<ConstLattice<R>> {
        val p1 = compilePattern(graph, patt.first, traverseFilter)
        val p2 = compilePattern(graph, patt.second, traverseFilter)
        return object : SymbolQuery<ConstLattice<R>> {
            /*
              If we are here, then this is a top-level query, or one of our symbol predicates returned subquery.
              The tricky case here is if we had p1 return SubQuery(s) and p2 returned Match(d) from their symbol queries.
              We need to be sure that, if p1 fails, invoking p2's pattern matcher gives back Match(d).

              Because we are here, we know that q must be s, the symbol passed to the symbol predicates, which
              was returned wrapped in the SubQuery. Further, from the constraint that symbolpredicates must be
              coherent w.r.t to the query, we know that if p2 returned Match from its symbol predicate, we can get
              that result back from p2's matcher.

              NB we are so certain about this invariant because the only non-trivial symbol predicates (i.e., is it a variable?
              subquery, otherwise no match) are those which are matching specific constants and variables. For matching on literal
              constants, coherence between the query and the symbolpredicate is moot: you can't query on a constant, so the
              only *real* result on a constant is Match or NoMatch. So what about variables? Well, the public API through which
              these symbolpredicates/queries are constructed just expose a predicate which returns a variable continuation,
              in other words, the results must be coherent because they are using the exact same logic (i.e., predicate)
              under the hood.
             */
            override fun query(q: TACSymbol.Var, src: LTACCmd): ConstLattice<R> {
                patt.taggedDebug {
                    "Beginning query at $src for variable $q"
                }
                return tryPipeline(patt.adapt1) {
                    patt.taggedDebug {
                        "Trying first query for pattern ${patt.first.patternName}"
                    }
                    p1.query(q, src)
                } ?: tryPipeline(patt.adapt2) {
                    patt.taggedDebug {
                        "First pattern didn't find anything, trying second pattern ${patt.second.patternName}"
                    }
                    p2.query(q, src)
                } ?: ConstLattice.NoMatch.also {
                    patt.taggedDebug { "Neither branch found anything for $q @ $src" }
                }
            }

            private fun <K> tryPipeline(adapt: (K) -> R, f: () -> ConstLattice<K>): ConstLattice.Match<R>? {
                return f().toNullableResult()?.let {
                    patt.taggedDebug {
                        "Successful match returned $it, adapting"
                    }
                    adapt(it)
                }?.let {
                    ConstLattice.Match(it)
                }
            }

            override fun queryFrom(start: LTACCmdView<TACCmd.Simple.AssigningCmd>): ConstLattice<R> {
                patt.taggedDebug {
                    "Starting query from assignment $start"
                }
                return tryPipeline(patt.adapt1) {
                    patt.taggedDebug {
                        "Starting query at $start for pattern ${patt.first.patternName}"
                    }
                    p1.queryFrom(start)
                } ?: tryPipeline(patt.adapt2) {
                    patt.taggedDebug {
                        "First pattern found nothing, trying from $start with patterh ${patt.second.patternName}"
                    }
                    p2.queryFrom(start)
                } ?: ConstLattice.NoMatch.also {
                    patt.taggedDebug {
                        "Neither pattern matched starting from $start"
                    }
                }
            }

        }
    }

    private fun <T, U, V, R> compileItePattern(
        graph: TACCommandGraph,
        patt: Pattern.Ite<T, U, V, R>,
        traverseFilter: (TACSymbol.Var) -> Boolean
    ): SymbolQuery<ConstLattice<R>> {
        val pCond = compilePattern(graph, patt.cond, traverseFilter)
        val pThen = compilePattern(graph, patt.thenCase, traverseFilter)
        val pElse = compilePattern(graph, patt.elseCase, traverseFilter)

        return object : LoggingFlowQuery<R>(patt, graph) {
            override fun filterTraversal(l: LTACCmdView<TACCmd.Simple.AssigningCmd>): Boolean {
                return traverseFilter(l.cmd.lhs)
            }

            override fun stepAssignment(
                first: TACCmd.Simple.AssigningCmd,
                where: CmdPointer,
                active: Set<Query>
            ): ConstLattice<R> {
                fun <T> runContinuation(cont: Continuation<T>, analysis: SymbolQuery<ConstLattice<T>>): T? {
                    return when (cont) {
                        is Continuation.SubQuery -> {
                            val res = analysis.query(cont.from, first.at(where))
                            when (res) {
                                is ConstLattice.NoMatch -> null
                                is ConstLattice.Match -> res.data
                            }
                        }
                        is Continuation.Result -> cont.res
                        Continuation.NoMatch -> null
                    }
                }
                patt.taggedDebug {
                    "Found definition as $first @ $where"
                }
                return if (first is TACCmd.Simple.AssigningCmd.AssignExpCmd) {
                    when (first.rhs) {
                        is TACExpr.TernaryExp.Ite -> {
                            patt.taggedDebug {
                                "Definition is ITE"
                            }
                            val ltacWhere = graph.elab(where)
                            first.rhs.e.getAs<TACExpr.Sym>()?.let { eSym ->
                                patt.taggedDebug {
                                    "Extracted $eSym as symbol from else case @ $ltacWhere"
                                }
                                patt.elseCase.toSymbolPredicate().invoke(eSym.s, ltacWhere).also {
                                    patt.taggedDebug {
                                        "Continuation on else branch sym $eSym is $it"
                                    }
                                }
                            }?.let { cont ->
                                runContinuation(cont, pElse)?.let { eRes ->
                                    patt.taggedDebug {
                                        "Processing else branch yielded $eRes"
                                    }
                                    first.rhs.t.getAs<TACExpr.Sym>()?.let { tSym ->
                                        patt.taggedDebug {
                                            "Extracted $tSym as true branch symbol"
                                        }
                                        patt.thenCase.toSymbolPredicate().invoke(tSym.s, ltacWhere).also {
                                            patt.taggedDebug {
                                                "Continuation on $tSym is $it"
                                            }
                                        }
                                    }?.let { tCont ->
                                        runContinuation(tCont, pThen)
                                    }?.let { tRes ->
                                        patt.taggedDebug {
                                            "Processing true branch yielded $tRes"
                                        }
                                        first.rhs.i.getAs<TACExpr.Sym>()?.let { iSym ->
                                            patt.taggedDebug {
                                                "Extracted $iSym as the condition symbol"
                                            }
                                            patt.cond.toSymbolPredicate()(iSym.s, ltacWhere).also {
                                                patt.taggedDebug {
                                                    "Continuation on $iSym is $it"
                                                }
                                            }
                                        }?.let { iCont ->
                                            runContinuation(iCont, pCond)
                                        }?.let { iRes ->
                                            patt.taggedDebug {
                                                "processing conditional yielded $iRes. All branches successful, returning with match"
                                            }
                                            ConstLattice.Match(patt.comb(ltacWhere, iRes, tRes, eRes))
                                        }
                                    }
                                }
                            } ?: ConstLattice.NoMatch
                        }
                        is TACExpr.Sym.Var -> {
                            patt.taggedDebug {
                                "Reached def in terms of variable ${first.rhs.s} @ ${graph.elab(where)}. sub-query"
                            }
                            query(first.rhs.s, where, active)
                        }
                        is TACExpr.Sym.Const -> {
                            patt.taggedDebug {
                                "Reached def in terms of constant @ ${graph.elab(where)}. Fail"
                            }
                            ConstLattice.NoMatch
                        }
                        else -> {
                            patt.taggedDebug {
                                "Unrecognized RHS expression ${first.rhs} @ ${graph.elab(where)}. Fail"
                            }
                            ConstLattice.NoMatch
                        }
                    }
                } else {
                    patt.taggedDebug {
                        "Unrecognized assignment form @ ${graph.elab(where)}"
                    }
                    ConstLattice.NoMatch
                }
            }
        }

    }

    private fun <R, T, U> compileXOrPattern(
        graph: TACCommandGraph,
        patt: Pattern.XOr<T, U, R>,
        traverseFilter: (TACSymbol.Var) -> Boolean
    ): SymbolQuery<ConstLattice<R>> {
        val comp1 = compilePattern(graph, patt.first, traverseFilter)
        val comp2 = compilePattern(graph, patt.second, traverseFilter)
        return object : SymbolQuery<ConstLattice<R>> {
            override fun query(q: TACSymbol.Var, src: LTACCmd): ConstLattice<R> {
                patt.taggedDebug { "Starting at $src for $q. First running ${patt.first.patternName}" }
                val r1 = comp1.query(q, src)
                patt.taggedDebug { "Pattern 1 returned $r1. Now running ${patt.second.patternName}" }
                val r2 = comp2.query(q, src)
                patt.taggedDebug { "Pattern 2 returned $r2. Combining results" }
                return combineAlternatives(
                    r1,
                    r2
                )
            }

            private fun combineAlternatives(r1: ConstLattice<T>, r2: ConstLattice<U>): ConstLattice<R> =
                when {
                    r1 is ConstLattice.Match && r2 is ConstLattice.NoMatch -> {
                        patt.taggedDebug {
                            "First pattern returned a result, using $r1"
                        }
                        ConstLattice.Match(patt.adapt1(r1.data))
                    }
                    r1 is ConstLattice.NoMatch && r2 is ConstLattice.Match -> {
                        patt.taggedDebug {
                            "Second pattern returned exclusive result, using $r2"
                        }
                        ConstLattice.Match(patt.adapt2(r2.data))
                    }
                    else -> {
                        patt.taggedDebug {
                            "Either $r1 and $r2 are no match, or both are matches. Failing"
                        }
                        ConstLattice.NoMatch
                    }
                }

            override fun queryFrom(start: LTACCmdView<TACCmd.Simple.AssigningCmd>): ConstLattice<R> {
                patt.taggedDebug {
                    "Starting query from $start. First sub-query for ${patt.first.patternName}"
                }
                val r1 = comp1.queryFrom(start)
                patt.taggedDebug {
                    "Pattern 1 returned $r1. Starting sub-query for ${patt.second.patternName}"
                }
                val r2 = comp2.queryFrom(start)
                patt.taggedDebug { "Pattern 2 return $r2. Combining results" }
                return combineAlternatives(
                    r1,
                    r2
                )
            }


        }
    }

    private fun <R> compileAssigningPattern0(
        graph: TACCommandGraph,
        patt: Pattern.AssigningPattern0<R>,
        traverseFilter: (TACSymbol.Var) -> Boolean
    ): SymbolQuery<ConstLattice<R>> {
        return object : LoggingFlowQuery<R>(patt, graph) {
            override fun filterTraversal(l: LTACCmdView<TACCmd.Simple.AssigningCmd>): Boolean {
                return traverseFilter(l.cmd.lhs)
            }

            override fun stepAssignment(
                first: TACCmd.Simple.AssigningCmd,
                where: CmdPointer,
                active: Set<Query>
            ): ConstLattice<R> {
                patt.taggedDebug {
                    "Found definition at ${graph.elab(where)}"
                }
                if (first is TACCmd.Simple.AssigningCmd.AssignExpCmd && first.rhs is TACExpr.Sym.Var) {
                    patt.taggedDebug {
                        "Found definition in terms of variable ${first.rhs.s}. Sub-query"
                    }
                    return this.query(first.rhs.s, where, active)
                }
                return patt.f(graph.elab(where), first).also {
                    patt.taggedDebug {
                        "Extractor on $first at $where returned $it"
                    }
                }
            }
        }
    }

    private fun <R> compileFromVar(
        graph: TACCommandGraph,
        patt: Pattern.FromVar<R>,
        traverseFilter: (TACSymbol.Var) -> Boolean
    ): BackwardsFlowQuery<ConstLattice<R>> {
        return object : LoggingFlowQuery<R>(patt, graph) {
            override fun query(q: TACSymbol.Var, src: LTACCmd): ConstLattice<R> {
                val sub = patt.extractor(src, q)
                return when (sub) {
                    is Continue -> super.query(q, src)
                    is VariableMatch.NoMatch -> ConstLattice.NoMatch
                    is Match -> return ConstLattice.Match(sub.data)
                }
            }

            override fun filterTraversal(l: LTACCmdView<TACCmd.Simple.AssigningCmd>): Boolean {
                return traverseFilter(l.cmd.lhs)
            }

            override fun stepAssignment(
                first: TACCmd.Simple.AssigningCmd,
                where: CmdPointer,
                active: Set<Query>
            ): ConstLattice<R> {
                patt.taggedDebug {
                    "Reached assignment at ${graph.elab(where)}"
                }
                return when (first) {
                    is TACCmd.Simple.AssigningCmd.AssignExpCmd -> {
                        when {
                            first.rhs is TACExpr.Sym.Var -> {
                                val ret = patt.extractor(first.at(where), first.rhs.s)
                                patt.taggedDebug {
                                    "Found definition in terms of var ${first.rhs.s}. Extractor returned $ret"
                                }
                                when (ret) {
                                    is Match -> ConstLattice.Match(ret.data)
                                    is Continue -> this.query(first.rhs.s, where, active)
                                    is VariableMatch.NoMatch -> ConstLattice.NoMatch
                                }
                            }
                            else -> {
                                patt.taggedDebug {
                                    "Unrecognized assignment for $first @ $where"
                                }
                                ConstLattice.NoMatch
                            }
                        }
                    }
                    else -> {
                        patt.taggedDebug {
                            "Unrecognized assignment form $first @ $where"
                        }
                        ConstLattice.NoMatch
                    }
                }
            }
        }
    }

    private fun <R, T> compileConstant(
        graph: TACCommandGraph,
        patt: Pattern.FromConstant<R, T>,
        traverseFilter: (TACSymbol.Var) -> Boolean
    ): BackwardsFlowQuery<ConstLattice<R>> {
        return object : LoggingFlowQuery<R>(patt, graph) {
            override fun filterTraversal(l: LTACCmdView<TACCmd.Simple.AssigningCmd>): Boolean {
                return traverseFilter(l.cmd.lhs)
            }

            override fun stepAssignment(
                first: TACCmd.Simple.AssigningCmd,
                where: CmdPointer,
                active: Set<Query>
            ): ConstLattice<R> {
                patt.taggedDebug {
                    "Hit assignment at ${graph.elab(where)}"
                }
                return when {
                    first is TACCmd.Simple.AssigningCmd.AssignExpCmd && first.rhs is TACExpr.Sym -> {
                        when (first.rhs) {
                            is TACExpr.Sym.Var -> {
                                patt.taggedDebug {
                                    "Found def as variable, requery"
                                }
                                query(first.rhs.s, where, active)
                            }
                            is TACExpr.Sym.Const -> {
                                patt.taggedDebug {
                                    "Found def as constant @ ${graph.elab(where)}, checking acceptance"
                                }
                                val extr = patt.extractor(first.rhs.s, graph.elab(where))
                                patt.taggedDebug {
                                    "Extractor for ${first.rhs.s} returned $extr"
                                }
                                if (extr == null) {
                                    ConstLattice.NoMatch
                                } else {
                                    ConstLattice.Match(patt.out(first.at(where), extr))
                                }
                            }
                        }
                    }
                    else -> {
                        patt.taggedDebug {
                            "Unrecognized assignment form ${graph.elab(where)}"
                        }
                        ConstLattice.NoMatch
                    }
                }
            }
        }
    }

    private fun <R, T> compileUnaryOp(
            graph: TACCommandGraph,
            patt: Pattern.FromUnaryOp<R, T>,
            traverseFilter: (TACSymbol.Var) -> Boolean
    ): BackwardsFlowQuery<ConstLattice<R>> {
        val innerMatcher = compilePattern(graph, patt.inner, traverseFilter)
        return object : LoggingFlowQuery<R>(patt, graph) {
            override fun filterTraversal(l: LTACCmdView<TACCmd.Simple.AssigningCmd>): Boolean {
                return traverseFilter(l.cmd.lhs)
            }

            override fun stepAssignment(
                first: TACCmd.Simple.AssigningCmd,
                where: CmdPointer,
                active: Set<Query>
            ): ConstLattice<R> {
                fun <T> runContinuation(cont: Continuation<T>, analysis: SymbolQuery<ConstLattice<T>>): T? {
                    return when (cont) {
                        is Continuation.SubQuery -> {
                            val res = analysis.query(cont.from, first.at(where))
                            when (res) {
                                is ConstLattice.NoMatch -> null
                                is ConstLattice.Match -> res.data
                            }
                        }
                        is Continuation.Result -> cont.res
                        Continuation.NoMatch -> null
                    }
                }
                return if (first is TACCmd.Simple.AssigningCmd.AssignExpCmd) {
                    when (first.rhs) {
                        is TACExpr.UnaryExp -> {
                            patt.taggedDebug {
                                "Reached definition in-terms of ${first.rhs}, at ${graph.elab(where)}"
                            }
                            val ext = patt.extractor(where, first.rhs)
                            patt.taggedDebug {
                                "Extractor returned $ext for ${first.rhs}"
                            }
                            return if (ext == null) {
                                patt.taggedDebug {
                                    "Could not extract symbol"
                                }
                                ConstLattice.NoMatch
                            } else {
                                val cont = patt.inner.toSymbolPredicate().invoke(ext, graph.elab(where))
                                patt.taggedDebug {
                                    "Continuation on $ext is $cont"
                                }
                                val r = runContinuation(cont, innerMatcher)
                                if (r != null) {
                                    patt.taggedDebug {
                                        "Continuation returned non-null result $r, succeeding"
                                    }
                                    ConstLattice.Match(patt.out(first.at(where), r))
                                } else {
                                    patt.taggedDebug {
                                        "Failing, as running $cont did not succeed"
                                    }
                                    ConstLattice.NoMatch
                                }
                            }
                        }
                        is TACExpr.Sym.Var -> {
                            patt.taggedDebug {
                                "Hit definition in terms of ${first.rhs.s} @ ${graph.elab(where)}. Requery"
                            }
                            query(first.rhs.s, where, active)
                        }
                        else -> {
                            patt.taggedDebug {
                                "Unrecognized RHS: ${first.rhs} @ ${graph.elab(where)}. Fail"
                            }
                            ConstLattice.NoMatch
                        }
                    }
                } else {
                    patt.taggedDebug {
                        "Unrecognized assignment form @ ${graph.elab(where)}"
                    }
                    ConstLattice.NoMatch
                }
            }
        }
    }

    private abstract class LoggingFlowQuery<T>(val patt: Pattern<*>, graph: TACCommandGraph) :
        BackwardsFlowQuery<ConstLattice<T>>(graph, ConstLattice.NoMatch, ConstLattice.Companion::join) {
        override fun query(q: TACSymbol.Var, src: LTACCmd): ConstLattice<T> {
            patt.debug {
                "Starting query for ${patt.name} at $src"
            }
            return super.query(q, src)
        }

        override fun queryFrom(start: LTACCmdView<TACCmd.Simple.AssigningCmd>): ConstLattice<T> {
            patt.debug {
                "Starting query for ${patt.name} at $start"
            }
            return super.queryFrom(start)
        }
    }

    private fun Pattern<*>.taggedDebug(s: () -> String) {
        this.debug {
            "${this.name}: ${s()}"
        }
    }

    private fun <R, T, U> compileBinOp(
            graph: TACCommandGraph,
            patt: Pattern.FromBinOp<R, T, U>,
            traverseFilter: (TACSymbol.Var) -> Boolean
    ): BackwardsFlowQuery<ConstLattice<R>> {
        val leftMatcher = compilePattern(graph, patt.left, traverseFilter)
        val rightMatcher = compilePattern(graph, patt.right, traverseFilter)
        val leftPred = patt.left.toSymbolPredicate()
        val rightPred = patt.right.toSymbolPredicate()
        return object : LoggingFlowQuery<R>(patt, graph) {
            override fun filterTraversal(l: LTACCmdView<TACCmd.Simple.AssigningCmd>): Boolean {
                return traverseFilter(l.cmd.lhs)
            }

            override fun stepAssignment(
                first: TACCmd.Simple.AssigningCmd,
                where: CmdPointer,
                active: Set<Query>
            ): ConstLattice<R> {
                patt.taggedDebug {
                    "Reaching non-trivial def at ${graph.elab(where)}"
                }
                fun <T> runContinuation(matcher: SymbolQuery<ConstLattice<T>>, cont: Continuation<T>): T? {
                    return when (cont) {
                        is Continuation.SubQuery -> {
                            val res = matcher.query(cont.from, first.at(where))
                            when (res) {
                                is ConstLattice.NoMatch -> null
                                is ConstLattice.Match -> res.data
                            }
                        }
                        is Continuation.Result -> cont.res
                        is Continuation.NoMatch -> null
                    }
                }
                return if (first is TACCmd.Simple.AssigningCmd.AssignExpCmd) {
                    when (first.rhs) {
                        is TACExpr.BinExp -> {
                            patt.taggedDebug {
                                "Found binary expression RHS"
                            }
                            val ext = patt.extractor(where, first.rhs)
                            return if (ext == null) {
                                patt.taggedDebug {
                                    "Could not extract two symbol operands, returning no match"
                                }
                                ConstLattice.NoMatch
                            } else {
                                val (q1, q2) = ext
                                patt.taggedDebug {
                                    "Extracted $q1 [op] $q2"
                                }
                                val c1 = leftPred.invoke(q1, graph.elab(where))
                                patt.taggedDebug {
                                    "Continuation for $q1: $c1"
                                }
                                val r1 = runContinuation(leftMatcher, c1)
                                val c2 = rightPred.invoke(q2, graph.elab(where))
                                patt.taggedDebug {
                                    "Continuation for $q2: $c2"
                                }
                                val r2 = runContinuation(rightMatcher, c2)
                                patt.taggedDebug {
                                    "Results from continuations: $r1 and $r2"
                                }
                                if (r1 != null && r2 != null) {
                                    patt.taggedDebug {
                                        "Both continuations succeeded, returning result"
                                    }
                                    ConstLattice.Match(patt.out(first.at(where), r1, r2))
                                } else {
                                    patt.taggedDebug {
                                        "Failing due to at least one continuation failing"
                                    }
                                    ConstLattice.NoMatch
                                }
                            }
                        }
                        is TACExpr.Sym.Var -> {
                            patt.taggedDebug {
                                "Reached definition as variable at ${graph.elab(where)}. Re-query"
                            }
                            query(first.rhs.s, where, active)
                        }
                        is TACExpr.Sym.Const -> {
                            patt.taggedDebug {
                                "Hit definition as constant at ${graph.elab(where)}, fail"
                            }
                            ConstLattice.NoMatch
                        }
                        else -> {
                            patt.taggedDebug {
                                "Failing, unrecognized RHS: ${graph.elab(where)}"
                            }
                            ConstLattice.NoMatch
                        }
                    }
                } else {
                    patt.taggedDebug {
                        "Failing, unrecognized assignment for ${graph.elab(where)}"
                    }
                    ConstLattice.NoMatch
                }
            }
        }
    }
}

fun <T> Pattern<T>.asBuildable() = Pattern.asBuildable(this)

infix fun <R> PatternBuilder<R>.lor(p: PatternBuilder<R>): PatternBuilder<R> { // `||` can't be compiled on windows.
    val capture = this
    return object : PatternBuilder<R>() {

        override fun toPattern(): Pattern<R> {
            return Pattern.Or(
                first = capture.toPattern(),
                adapt1 = { it },
                second = p.toPattern(),
                adapt2 = { it },
                patternName = name
            )
        }
    }
}
