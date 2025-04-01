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

package vc.data

import allocator.Allocator
import analysis.TACExprWithRequiredCmdsAndDecls
import analysis.opt.intervals.IntervalsRewriter.Companion.NON_ZERO_META
import analysis.storage.StorageAnalysisResult
import analysis.storage.indices
import analysis.storage.toNonIndexed
import com.certora.collect.*
import datastructures.add
import datastructures.buildMultiMap
import datastructures.stdcollections.*
import log.*
import report.BigIntPretty.bigIntPretty
import smt.QuantifierRewriter.Companion.LEXPRESSION_STORAGE_ACCESSES
import smt.QuantifierRewriter.Companion.LExpressionStorageAccesses
import smt.axiomgenerators.TypeBoundsGenerator.Companion.lExpressionBoundsOf
import smt.solverscript.functionsymbols.AxiomatizedFunctionSymbol
import smt.solverscript.functionsymbols.FunctionSymbol
import smt.solverscript.functionsymbols.NonSMTInterpretedFunctionSymbol
import smt.solverscript.functionsymbols.TheoryFunctionSymbol
import spec.QUANTIFIED_VAR_TYPE
import spec.cvlast.CVLStructPathNode
import spec.cvlast.CVLType
import tac.MetaKey
import tac.MetaMap
import tac.Tag
import tac.commonSuperTag
import utils.*
import utils.ModZm.Companion.addMod
import utils.ModZm.Companion.asBigInteger
import utils.ModZm.Companion.evmSignExtend
import utils.ModZm.Companion.highOnes
import utils.ModZm.Companion.isZero
import utils.ModZm.Companion.mulMod
import utils.ModZm.Companion.shr
import vc.data.TACBuiltInFunction.Hash.Companion.skeySort
import vc.data.TACCmd.Simple.AnnotationCmd.Annotation
import vc.data.TACMeta.ACCESS_PATHS
import vc.data.TACMeta.CVL_DISPLAY_NAME
import vc.data.TACMeta.CVL_STRUCT_PATH
import vc.data.TACMeta.CVL_TYPE
import vc.data.TACMeta.DIRECT_STORAGE_ACCESS_TYPE
import vc.data.TACSymbol.Var.Companion.KEYWORD_ENTRY
import vc.data.compilation.storage.InternalCVLCompilationAPI
import vc.data.tacexprutil.TACUnboundedHashingUtils
import vc.data.tacexprutil.asVarOrNull
import vc.data.tacexprutil.evaluators.TACExprInterpreter
import vc.data.tacexprutil.subs
import verifier.PolarityCalculator
import verifier.SKOLEM_VAR_NAME
import java.io.Serializable
import java.math.BigInteger

private val tacToLExprLogger = Logger(LoggerTypes.TAC_TO_LEXPR_CONVERTER)

@KSerializable
@Treapable
sealed class TACExpr : AmbiSerializable, ToLExpression, ToTACExpr {
    companion object {
        val zeroExpr = 0.asTACExpr
    }

    override fun toTACExpr(): TACExpr = this

    abstract val tag: Tag?

    val tagAssumeChecked
        get() = tag ?: throw CertoraInternalException(
            CertoraInternalErrorType.INTERNAL_TAC_TYPE_CHECKER,
            "assumed TAC Expression $this was type checked when it was not."
        )

    abstract fun eval(cs : List<BigInteger>) : BigInteger?

    /**
     * [cb] is a custom handler for [TACSymbol.Var]s, which by default just takes the SMT-rep
     */
    abstract fun toPrintRep(cb: (TACSymbol.Var) -> String): String

    /**
     * Checks if the TAC Expr `uses` the variable `v`.
     */
    fun usesVar(v: TACSymbol.Var) =
        subs.filterIsInstance<Sym.Var>().any { it.s == v }

    // REVIEW: looks redundant with `TACExpr.subs` in `TACExprUtils.kt` --> unify?
    fun nestedSubexprs(): Collection<TACExpr> {
        val ret = mutableSetOf<TACExpr>()
        fun TACExpr.helper() {
            ret.add(this)
            when (this) {
                is Vec -> this.ls.forEach { it.helper() }
                is BinBoolOp.LOr -> this.ls.forEach { it.helper() }
                is BinBoolOp.LAnd -> this.ls.forEach { it.helper() }
                is BinExp -> {
                    this.o1.helper()
                    this.o2.helper()
                }
                is Apply -> this.ops.forEach { it.helper() }
                is LongStore -> {
                    this.dstMap.helper()
                    this.dstOffset.helper()
                    this.length.helper()
                    this.srcMap.helper()
                    this.srcOffset.helper()
                }
                is MapDefinition -> {
                    this.defParams.forEach { it.helper() }
                    this.definition.helper()
                }
                is MultiDimStore -> {
                    this.base.helper()
                    this.value.helper()
                    this.locs.forEach { it.helper() }
                }
                is QuantifiedFormula -> {
                    this.quantifiedVars.forEach{ it.asSym().helper() }
                    this.body.helper()
                }
                is Select -> {
                    this.base.helper()
                    this.loc.helper()
                }
                is Store -> {
                    this.base.helper()
                    this.loc.helper()
                    this.value.helper()
                }
                is StructAccess -> {
                    this.struct.helper()
                }
                is StructConstant -> Unit
                is Sym.Const -> Unit
                is Sym.Var -> Unit
                is TernaryExp.Ite -> {
                    this.i.helper()
                    this.t.helper()
                    this.e.helper()
                }
                is UnaryExp -> this.o.helper()
                is Unconstrained -> Unit
                is TernaryExp.AddMod -> {
                    a.helper()
                    b.helper()
                    n.helper()
                }
                is TernaryExp.MulMod -> {
                    a.helper()
                    b.helper()
                    n.helper()
                }
                is SimpleHash -> {
                    length.helper()
                    args.forEach { it.helper() }
                }
                is AnnotationExp<*> -> {
                    o.helper()
                }
            }
        }
        this.helper()
        return ret
    }


    /**
     * By default, returns [toPrintRep] enclosed in parentheses. Different [TACExpr]s may override this method
     * so that it is identical to [toPrintRep].
     *
     * On the one hand, a [TACExpr] may require that a sub-[TACExpr] thereof would occur enclosed
     * in parentheses by calling this method in its [toPrintRep] implementation. On the other hand, each [TACExpr] may
     * determine its [toPrintRep]'s "parentheses form" using this method.
     *
     * The goal of this method is to facilitate the readability of [toPrintRep].
     */
    open fun toPrintRepWithParentheses(cb: (TACSymbol.Var) -> String): String = "(${this.toPrintRep(cb)})"

    fun evalAsConst(): BigInteger? =
        TACExprInterpreter.evalConstInt(this)

    inline fun <reified T : TACExpr> getAs() = if (this is T) this else null
    fun getAsConst() =
        this.getAs<Sym>()?.s?.takeIf { it is TACSymbol.Const }?.let { it as TACSymbol.Const }?.value

    fun convertToBool() =
        when (val t = tag) {
            is Tag.Bits -> UnaryExp.LNot(BinRel.Eq(this, TACSymbol.zero(t).asSym()))
            Tag.Bool -> this
            else -> error("Cannot convert $this with tag $tag to boolean")
        }

    fun checkBvForAssignment(lhs: TACSymbol.Var): TACExpr =
        when (lhs.tag) {
            Tag.Int, is Tag.Bits -> checkIsInt()
            Tag.Bool, Tag.ByteMap, Tag.WordMap, is Tag.GhostMap, is Tag.UserDefined, is Tag.TransientTag -> this
        }

    /**
     * Checks that `this` expression is tagged as an int or bitvector
     */
    fun checkIsInt(): TACExpr =
        when (tag) {
            is Tag.Bits, Tag.Int -> this
            else ->
                error("$this, with tag $tag must be converted to int/bv but is not")
        }

    /** Quantified formula, allows representation of a series of quantifiers (of the same polarity) */
    @KSerializable
    data class QuantifiedFormula(
        val isForall: Boolean,
        val quantifiedVars: List<TACSymbol.Var>,
        val body: TACExpr,
        override val tag: Tag? = null
    ) : TACExpr() {
        @Suppress("UNUSED_PARAMETER")
        constructor(isForall: Boolean, quantifiedVar: TACSymbol.Var, body: TACExpr, tag: Tag? = null) : this(
            isForall,
            listOf(quantifiedVar),
            body
        )

        override fun eval(cs: List<BigInteger>) = null

        init {
            check(quantifiedVars.isNotEmpty()) {
                "quantified vars may not be empty; formula body is $body"
            }
            val qVarsWithMapType = quantifiedVars.filter { it.tag in setOf(Tag.WordMap, Tag.ByteMap) }
            check(qVarsWithMapType.isEmpty()) {
                "quantifying over map types is not supported at the moment $qVarsWithMapType, for $body"
            }
        }

        override fun toString(): String {
            val quantifier = if (isForall) "Forall" else "Exists"
            val varlist = quantifiedVars.joinToString(" ")
            return "$quantifier( QVars($varlist) $body)"
        }

        override fun toPrintRep(cb: (TACSymbol.Var) -> String): String {
            val quantifier = if (isForall) "forall" else "exists"
            val varlist = quantifiedVars. joinToString(" ") { qvar -> "$qvar ${qvar.tag}" }
            return "$quantifier(QVars($varlist) ${body.toPrintRep(cb)})"
        }

        override fun toLExpression(conv: ToLExpression.Conv, meta: MetaMap?) =
            conv.lxf {
                // The following will probably be broken in CVL2 as it relies on 2s complement representation
                // of the quantified variables.
                val vars = mutableListOf<LExpression.Identifier>()
                val inner = conv(body, meta)
                val antecedent = quantifiedVars.mapNotNull { tacVar ->
                    val p = tacVar.meta[PolarityCalculator.POLARITY]
                        ?: run {
                            Logger.alwaysWarn("couldn't get polarity for ${this@QuantifiedFormula}")
                            PolarityCalculator.Polarity.BOTH
                        }
                    val v = conv(tacVar.asSym(), meta).addMeta(PolarityCalculator.POLARITY, p).let {
                        tacVar.meta[SKOLEM_VAR_NAME]
                            ?.let { name -> it.addMeta(SKOLEM_VAR_NAME, name) }
                            ?: it
                    }
                    vars += v as LExpression.Identifier
                    lExpressionBoundsOf(conv.lxf, v, tacVar.meta[QUANTIFIED_VAR_TYPE], conv.symbolTable.tags[tacVar])
                }.let { l ->
                    when (l.size) {
                        0 -> null
                        1 -> l.first()
                        else -> and(l)
                    }
                }

                if (isForall) {
                    forall(vars) {
                        antecedent?.let {
                            antecedent implies inner
                        } ?: inner
                    }
                } else {
                    exists(vars) {
                        antecedent?.let {
                            antecedent and inner
                        } ?: inner
                    }
                }
            }
    }

    @KSerializable
    sealed class Sym : TACExpr() {
        abstract val s: TACSymbol

        override fun toPrintRepWithParentheses(cb: (TACSymbol.Var) -> String): String = toPrintRep(cb)

        @KSerializable
        @Treapable
        data class Var(override val s: TACSymbol.Var, override val tag: Tag?) : Sym() {
            constructor(s: TACSymbol.Var) : this(s, s.tag)
            override fun toString(): String = s.toString()

            override fun eval(cs: List<BigInteger>) = error("Eval is called on variable $this")

            override fun toLExpression(conv: ToLExpression.Conv, meta: MetaMap?): LExpression {
                val symbols = conv.symbolTable.getUninterpretedFunctions(this.s.toSMTRep())
                return if (symbols.isNotEmpty()) {
                    check(symbols.size == 1)
                    {
                        "Expected the type scope to have exactly one uninterpreted function with the " +
                                "name ${this.s.toSMTRep()}, but got $symbols"
                    }
                    val symbol = symbols.first()
                    // sanity check, any non-uf tagged variable should refer to a nullary function
                    check(this.s.tag is Tag.GhostMap || symbol.paramSorts.isEmpty())
                    { "any non-ghostmap tagged variable should refer to a nullary function" }
                    // this is a nullary uninterpreted function, so we already registered it with the UF symbol table and do not
                    // want to _also_ register it with the constant symbol table
                    conv.lxf.getOrConstructGhostIdentifier(this.s.toSMTRep(), symbol, meta)
                } else {
                    // this is NOT a nullary uninterpreted function so register as normal
                    val m = (meta ?: MetaMap()) + s.meta.filter {
                        it.key == DIRECT_STORAGE_ACCESS_TYPE || it.key == KEYWORD_ENTRY
                    }
                    conv.lxf.const(this.s.toSMTRep(), this.s.tag, m)
                }
            }
        }

        @KSerializable
        data class Const(override val s: TACSymbol.Const, override val tag: Tag? = null) : Sym() {
            init {
                check(tag == null || tag == s.tag) {
                    "Inconsistent tags for constant: expr has $tag but underlying symbol has ${s.tag}."
                }
            }
            constructor(s: TACSymbol.Const) : this(s, s.tag)
            override fun toString(): String = s.toString()

            override fun eval(cs: List<BigInteger>): BigInteger {
                require(cs.isEmpty())
                return s.value
            }

            override fun toLExpression(conv: ToLExpression.Conv, meta: MetaMap?): LExpression =
                when (this.tag) {
                    is Tag.Bits -> conv.lxf.lit(s.value, tag)
                    Tag.Bool -> conv.lxf.litBool(when (s.value) {
                        BigInteger.ZERO -> false
                        BigInteger.ONE -> true
                        else -> {
                            tacToLExprLogger.warn { "$this has an unexpected bool value: ${s.value}" }
                            true
                        }
                    })

                    Tag.Int -> conv.lxf.litInt(s.value)
                    else -> throw java.lang.UnsupportedOperationException("Do not know how to translate constant of type ${this.tag}")
                }
        }

        /**
         * If this expression is used as a value for a non bool, but it is a bool, then we should convert it to an int/bv type.
         * [lhs] is the variable that `this` is going to be assigned to.
         */
        fun ensureIntOrBvForAssignmentTo(lhs: TACSymbol.Var): TACExpr {
            if (lhs.tag == Tag.Bool) {
                return this
            }
            return this.convertToBv().checkIsInt()
        }

        private fun convertToBv(): TACExpr {
            return when (this.s.tag) {
                Tag.Bool -> TACExprFactTypeCheckedOnlyPrimitives.Ite(
                    this,
                    TACSymbol.lift(1).asSym(),
                    TACSymbol.lift(0).asSym()
                )
                is Tag.Bits, Tag.Int -> TACExprFactTypeCheckedOnlyPrimitives.Sym(this.s)
                else -> error("Cannot convert $this with tag $tag to int/bv")
            }
        }

        override fun toPrintRep(cb: (TACSymbol.Var) -> String): String {
            return if (s is TACSymbol.Const && s.tag is Tag.Bits) {
                bigIntPretty((s as TACSymbol.Const).value) ?: s.toSMTRep()
            } else if (s is TACSymbol.Var) {
                cb(s as TACSymbol.Var)
            } else {
                s.toSMTRep()
            }
        }

        fun copy(s: TACSymbol = this.s): Sym = when (s) {
            is TACSymbol.Var -> Var(s)
            is TACSymbol.Const -> Const(s)
        }

        companion object {
            operator fun invoke(s: TACSymbol) = when (s) {
                is TACSymbol.Var -> Var(s, s.tag)
                is TACSymbol.Const -> Const(s, s.tag)
            }
        }
    }

    /**
     * Each class derived from BinExpr which has more than 2 operands has its own evalAsConst.
     */
    sealed interface BinExp {
        val tag: Tag?
        val o1: TACExpr
        val o2: TACExpr

        fun operandsAreSyms(): Boolean = o1 is Sym && o2 is Sym
        fun evaluatable(o1: BigInteger, o2: BigInteger): Boolean = true
        fun o1AsNullableTACSymbol(): TACSymbol? = if (o1 is Sym) (o1 as Sym).s else null
        fun o2AsNullableTACSymbol(): TACSymbol? = if (o2 is Sym) (o2 as Sym).s else null
        fun o1AsTACSymbol(): TACSymbol = (o1 as Sym).s
        fun o2AsTACSymbol(): TACSymbol = (o2 as Sym).s
        fun eval(o1: BigInteger, o2: BigInteger): BigInteger

        companion object {
            fun <T : BinExp> build(from: T): (TACExpr, TACExpr) -> TACExpr = { o1: TACExpr, o2: TACExpr ->
                when (from) {
                    is BinRel.Gt -> BinRel.Gt(o1, o2)
                    is BinRel.Ge -> BinRel.Ge(o1, o2)
                    is BinRel.Lt -> BinRel.Lt(o1, o2)
                    is BinRel.Le -> BinRel.Le(o1, o2)
                    is BinRel.Slt -> BinRel.Slt(o1, o2)
                    is BinRel.Sle -> BinRel.Sle(o1, o2)
                    is BinRel.Sgt -> BinRel.Sgt(o1, o2)
                    is BinRel.Sge -> BinRel.Sge(o1, o2)
                    is BinRel.Eq -> {
                        if (o1.tag == Tag.Bool && o2.getAsConst() == BigInteger.ONE) {
                            o1
                        } else {
                            BinRel.Eq(o1, o2)
                        }
                    }
                    is BinOp.Sub -> BinOp.Sub(o1, o2)
                    is BinOp.IntSub -> BinOp.IntSub(o1, o2)
                    is BinOp.Div -> BinOp.Div(o1, o2)
                    is BinOp.SDiv -> BinOp.SDiv(o1, o2)
                    is BinOp.IntDiv -> BinOp.IntDiv(o1, o2)
                    is BinOp.Mod -> BinOp.Mod(o1, o2)
                    is BinOp.SMod -> BinOp.SMod(o1, o2)
                    is BinOp.IntMod -> BinOp.IntMod(o1, o2)
                    is BinOp.Exponent -> BinOp.Exponent(o1, o2)
                    is BinOp.IntExponent -> BinOp.IntExponent(o1, o2)
                    is BinOp.BWAnd -> BinOp.BWAnd(o1, o2)
                    is BinOp.BWOr -> BinOp.BWOr(o1, o2)
                    is BinOp.BWXOr -> BinOp.BWXOr(o1, o2)
                    is BinOp.ShiftLeft -> BinOp.ShiftLeft(o1, o2)
                    is BinOp.ShiftRightLogical -> BinOp.ShiftRightLogical(o1, o2)
                    is BinOp.ShiftRightArithmetical -> BinOp.ShiftRightArithmetical(o1, o2)
                    is BinBoolOp.LAnd -> BinBoolOp.LAnd(o1, o2)
                    is BinBoolOp.LOr -> BinBoolOp.LOr(o1, o2)
                    is BinOp.SignExtend -> BinOp.SignExtend(o1, o2)
                    else -> throw UnsupportedOperationException("$from is not covered by binary expression builder")
                }
            }
        }
    }

    sealed interface IntExp {
        val tag: Tag.Int?
    }

    sealed interface BitvectorExp {
        val tag: Tag.Bits?
    }

    sealed interface BoolExp {
        val tag: Tag.Bool?
    }

    @KSerializable
    sealed class Vec : TACExpr(), BinExp {
        override fun operandsAreSyms(): Boolean = this.ls.size == 2 && super.operandsAreSyms()

        abstract val ls: List<TACExpr>
        abstract val initValue: BigInteger
        override fun eval(cs: List<BigInteger>) = cs.fold(initValue) { a, b -> eval(a, b) }

        override val o1: TACExpr
            get() {
                if (ls.size == 2) {
                    return ls[0]
                }
                error("The Vec expression $this is not a binary expression")
            }
        override val o2: TACExpr
            get() {
                if (ls.size == 2) {
                    return ls[1]
                }
                error("The Vec expression $this is not a binary expression")
            }

        companion object {
            inline fun <reified T : Vec> build(from: T): (List<TACExpr>) -> T = { ls: List<TACExpr> ->
                when (from) {
                    is Mul -> Mul(ls)
                    is IntMul -> IntMul(ls)
                    is Add -> Add(ls)
                    is IntAdd -> IntAdd(ls)
                    else -> throw UnsupportedOperationException("$from is a Vec and not covered")
                } as T
            }
        }

        @KSerializable
        data class Mul(
            override val ls: List<TACExpr>,
            override val tag: Tag.Bits? = null
        ) : Vec(), VecToLExpressionByBinOp, BitvectorExp {

            constructor(op1: TACExpr, op2: TACExpr, tag: Tag.Bits? = null) : this(listOf(op1, op2), tag)

            override fun toString(): String {
                return "Mul(${ls.joinToString(" ")})"
            }

            override fun toPrintRep(cb: (TACSymbol.Var) -> String): String {
                return ls.joinToString("*") {
                    if (it is Mul) {
                        it.toPrintRep(cb)
                    } else {
                        it.toPrintRepWithParentheses(cb)
                    }
                }
            }

            override val smtName: FunctionSymbol
                get() = NonSMTInterpretedFunctionSymbol.Vec.Mul(tag!!)

            override val initValue: BigInteger get() = BigInteger.ONE

            override fun eval(o1: BigInteger, o2: BigInteger): BigInteger =
                (tag ?: Tag.Bit256).mul(o1, o2)
        }

        @KSerializable
        sealed class IntMul : Vec(), VecToLExpressionWithInitBigInt, IntExp {
            companion object {
                operator fun invoke(ls: List<TACExpr>, tag: Tag.Int? = null): IntMul =
                    when {
                        ls.size == 2 -> Binary(ls[0], ls[1], tag)
                        else -> Nary(ls, tag)
                    }
                operator fun invoke(op1: TACExpr, op2: TACExpr, tag: Tag.Int? = null): IntMul =
                    Binary(op1, op2, tag)
            }

            open fun copy(ls: List<TACExpr> = this.ls, tag: Tag.Int? = this.tag) = IntMul(ls, tag)

            override fun equals(other: Any?) =
                other is IntMul && other.ls == this.ls && other.tag == this.tag

            override fun hashCode() = 31 * ls.hashCode() + tag.hashCode()

            override fun toString(): String {
                return "IntMul(${ls.joinToString(" ")})"
            }

            override fun toPrintRep(cb: (TACSymbol.Var) -> String): String {
                return ls.joinToString("*int ") {
                    if (it is IntMul) {
                        it.toPrintRep(cb)
                    } else {
                        it.toPrintRepWithParentheses(cb)
                    }
                }
            }

            override val smtName: FunctionSymbol
                get() = TheoryFunctionSymbol.Vec.IntMul.IntMulAllowNormalize

            override val initValue: BigInteger get() = BigInteger.ONE

            override fun eval(o1: BigInteger, o2: BigInteger): BigInteger =
                o1 * o2

            @KSerializable
            private class Nary(
                override val ls: List<TACExpr>,
                override val tag: Tag.Int?
            ) : IntMul()

            @KSerializable
            class Binary(
                override val o1: TACExpr,
                override val o2: TACExpr,
                override val tag: Tag.Int?
            ) : IntMul() {
                override val ls get() = listOf(o1, o2)
            }
        }

        @KSerializable
        sealed class IntAdd : Vec(), VecToLExpressionWithInitBigInt, IntExp {
            companion object {
                operator fun invoke(ls: List<TACExpr>, tag: Tag.Int? = null): IntAdd =
                    when {
                        ls.size == 2 -> Binary(ls[0], ls[1], tag)
                        else -> Nary(ls, tag)
                    }
                operator fun invoke(op1: TACExpr, op2: TACExpr, tag: Tag.Int? = null): IntAdd =
                    Binary(op1, op2, tag)
                operator fun invoke(vararg l: TACExpr, tag: Tag.Int? = null): IntAdd =
                    IntAdd(l.toList(), tag)
            }

            open fun copy(ls: List<TACExpr> = this.ls, tag: Tag.Int? = this.tag) = IntAdd(ls, tag)

            override fun equals(other: Any?) =
                other is IntAdd && other.ls == this.ls && other.tag == this.tag

            override fun hashCode() = 31 * ls.hashCode() + tag.hashCode()

            override fun toString(): String {
                return "IntAdd(${ls.joinToString(" ")})"
            }

            override fun toPrintRep(cb: (TACSymbol.Var) -> String): String {
                return ls.joinToString("+int ") {
                    if (it is IntAdd) {
                        it.toPrintRep(cb)
                    } else {
                        it.toPrintRepWithParentheses(cb)
                    }
                }
            }

            override val smtName: FunctionSymbol
                get() = TheoryFunctionSymbol.Vec.IntAdd

            override val initValue: BigInteger get() = BigInteger.ZERO

            override fun eval(o1: BigInteger, o2: BigInteger): BigInteger =
                o1 + o2

            @KSerializable
            private class Nary(
                override val ls: List<TACExpr>,
                override val tag: Tag.Int?
            ) : IntAdd()

            @KSerializable
            class Binary(
                override val o1: TACExpr,
                override val o2: TACExpr,
                override val tag: Tag.Int?
            ) : IntAdd() {
                override val ls get() = listOf(o1, o2)
            }
        }

        @KSerializable
        sealed class Add : Vec(), VecToLExpressionByBinOp, BitvectorExp {
            companion object {
                operator fun invoke(ls: List<TACExpr>, tag: Tag.Bits? = null): Add =
                    when {
                        ls.size == 2 -> Binary(ls[0], ls[1], tag)
                        else -> Nary(ls, tag)
                    }
                operator fun invoke(op1: TACExpr, op2: TACExpr, tag: Tag.Bits? = null): Add =
                    Binary(op1, op2, tag)
            }

            open fun copy(ls: List<TACExpr> = this.ls, tag: Tag.Bits? = this.tag) = Add(ls, tag)

            override fun equals(other: Any?) =
                other is Add && other.ls == this.ls && other.tag == this.tag

            override fun hashCode() = 31 * ls.hashCode() + tag.hashCode()

            override fun toString(): String {
                return "Add(${ls.joinToString(" ")})"
            }

            override fun toPrintRep(cb: (TACSymbol.Var) -> String): String {
                return ls.joinToString("+") {
                    if (it is Add) {
                        it.toPrintRep(cb)
                    } else {
                        it.toPrintRepWithParentheses(cb)
                    }
                }
            }

            override val smtName: FunctionSymbol
                get() = NonSMTInterpretedFunctionSymbol.Vec.Add(tag!!)

            override val initValue: BigInteger get() = BigInteger.ZERO

            override fun eval(o1: BigInteger, o2: BigInteger): BigInteger =
                (tag ?: Tag.Bit256).add(o1, o2)

            @KSerializable
            private class Nary(
                override val ls: List<TACExpr>,
                override val tag: Tag.Bits?
            ) : Add()

            @KSerializable
            class Binary(
                override val o1: TACExpr,
                override val o2: TACExpr,
                override val tag: Tag.Bits?
            ) : Add() {
                override val ls get() = listOf(o1, o2)
            }
        }


    }

    /**
     *
     */
    @KSerializable
    sealed class TernaryExp : TACExpr() {
        // generic names for the parameters
        abstract val o1: TACExpr
        abstract val o2: TACExpr
        abstract val o3: TACExpr

        abstract fun eval(o1: BigInteger, o2: BigInteger, o3: BigInteger): BigInteger
        override fun eval(cs: List<BigInteger>): BigInteger {
            require(cs.size == 3)
            val (a, b, c) = cs
            return eval(a, b, c)
        }

        fun o1AsNullableTACSymbol(): TACSymbol? = if (o1 is Sym) { (o1 as Sym).s } else { null }
        fun o2AsNullableTACSymbol(): TACSymbol? = if (o2 is Sym) { (o2 as Sym).s } else { null }
        fun o3AsNullableTACSymbol(): TACSymbol? = if (o3 is Sym) { (o3 as Sym).s } else { null }

        @KSerializable
        data class Ite(val i: TACExpr, val t: TACExpr, val e: TACExpr, override val tag: Tag? = null) :
            TernaryExp() {
            override val o1: TACExpr get() = i
            override val o2: TACExpr get() = t
            override val o3: TACExpr get() = e

            override fun toLExpression(conv: ToLExpression.Conv, meta: MetaMap?): LExpression =
                conv.lxf.ite(
                    conv(i, meta),
                    conv(t, meta),
                    conv(e, meta),
                )

            override fun toString(): String {
                return "Ite($i $t $e)"
            }


            override fun toPrintRep(cb: (TACSymbol.Var) -> String): String {
                return "${i.toPrintRep(cb)} ? ${t.toPrintRep(cb)} : ${e.toPrintRep(cb)}"
            }

            override fun eval(o1: BigInteger, o2: BigInteger, o3: BigInteger) =
                if (o1 > BigInteger.ZERO) { o2 } else { o3 }
        }

        @KSerializable
        data class MulMod(val a: TACExpr, val b: TACExpr, val n: TACExpr, override val tag: Tag? = null) :
        TernaryExp() {
            override val o1: TACExpr get() = a
            override val o2: TACExpr get() = b
            override val o3: TACExpr get() = n


            override fun toString(): String {
                return "MulMod($a $b $n)"
            }

            override fun toLExpression(conv: ToLExpression.Conv, meta: MetaMap?): LExpression {
                return conv.lxf.applyExp(
                        NonSMTInterpretedFunctionSymbol.Ternary.MulMod,
                        conv(a, meta),
                        conv(b, meta),
                        conv(n, meta),
                )
            }

            override fun toPrintRep(cb: (TACSymbol.Var) -> String): String {
                return "MULMOD(${a.toPrintRep(cb)}, ${b.toPrintRep(cb)}, ${n.toPrintRep(cb)})"
            }

            override fun eval(o1: BigInteger, o2: BigInteger, o3: BigInteger): BigInteger =
                mulMod(o1, o2, o3)
        }

        @KSerializable
        data class AddMod(val a: TACExpr, val b: TACExpr, val n: TACExpr, override val tag: Tag? = null) :
                TernaryExp() {
            override val o1: TACExpr get() = a
            override val o2: TACExpr get() = b
            override val o3: TACExpr get() = n

            override fun toString(): String {
                return "AddMod($a $b $n)"
            }

            override fun toLExpression(conv: ToLExpression.Conv, meta: MetaMap?): LExpression {
                return conv.lxf.applyExp(
                        NonSMTInterpretedFunctionSymbol.Ternary.AddMod,
                        conv(a, meta),
                        conv(b, meta),
                        conv(n, meta),
                )
            }

            override fun toPrintRep(cb: (TACSymbol.Var) -> String): String {
                return "ADDMOD(${a.toPrintRep(cb)}, ${b.toPrintRep(cb)}, ${n.toPrintRep(cb)})"
            }

            override fun eval(o1: BigInteger, o2: BigInteger, o3: BigInteger): BigInteger =
                addMod(o1, o2, o3)
        }
    }

    @KSerializable
    sealed class UnaryExp : TACExpr(), UnaryToLExpression {
        abstract fun eval(o: BigInteger): BigInteger
        override fun eval(cs: List<BigInteger>): BigInteger {
            require(cs.size == 1)
            return eval(cs[0])
        }
        override fun toPrintRepWithParentheses(cb: (TACSymbol.Var) -> String): String = toPrintRep(cb)

        @KSerializable
        data class BWNot(override val o: TACExpr, override val tag: Tag? = null) : UnaryExp() {

            override fun toString(): String {
                return "BWNot($o)"
            }

            override fun toPrintRep(cb: (TACSymbol.Var) -> String): String {
                return "~${o.toPrintRepWithParentheses(cb)}"
            }

            override val smtName: FunctionSymbol
                get() = NonSMTInterpretedFunctionSymbol.Unary.BitwiseNot(tagAssumeChecked as Tag.Bits)

            override fun eval(o: BigInteger) = (tag as Tag.Bits).bwNot(o)
        }

        @KSerializable
        data class LNot(override val o: TACExpr, override val tag: Tag.Bool? = null) : UnaryExp(), BoolExp {

            override fun toString(): String {
                return "LNot($o)"
            }

            override val smtName: FunctionSymbol
                get() = TheoryFunctionSymbol.Unary.LNot

            override fun toPrintRep(cb: (TACSymbol.Var) -> String): String {
                return "!${o.toPrintRepWithParentheses(cb)}"
            }

            override fun eval(o: BigInteger): BigInteger =
                isZero(o)
        }
    }

    /**
     * Groups the binary expressions (does not include binary relations and binary boolean operations).
     * Note: we might make the operands fields in this sealed class, too, just not doing it now because of 'lazy'...
     */
    @KSerializable
    sealed class BinOp : TACExpr(), BinExp {

        /** Copy method on sealed class level, analogous to the data class's `copy` methods. */
        @JvmName("copy1")
        fun copy(o1: TACExpr = this.o1, o2: TACExpr = this.o2) = when (this) {
            is BWAnd -> BWAnd(o1, o2, this.tag)
            is BWOr -> BWOr(o1, o2, this.tag)
            is BWXOr -> BWXOr(o1, o2, this.tag)
            is Div -> Div(o1, o2, this.tag)
            is Exponent -> Exponent(o1, o2, this.tag)
            is IntDiv -> IntDiv(o1, o2, this.tag)
            is IntSub -> IntSub(o1, o2, this.tag)
            is IntExponent -> IntExponent(o1, o2, this.tag)
            is Mod -> Mod(o1, o2, this.tag)
            is SDiv -> SDiv(o1, o2, this.tag)
            is SMod -> SMod(o1, o2, this.tag)
            is IntMod -> IntMod(o1, o2, this.tag)
            is ShiftLeft -> ShiftLeft(o1, o2, this.tag)
            is ShiftRightArithmetical -> ShiftRightArithmetical(o1, o2, this.tag)
            is ShiftRightLogical -> ShiftRightLogical(o1, o2, this.tag)
            is SignExtend -> SignExtend(o1, o2, this.tag)
            is Sub -> Sub(o1, o2, this.tag)
        }
        /** Copy method on sealed class level, analogous to the data class's `copy` methods. */
        @JvmName("copy1")
        fun copy(tag: Tag? = this.tag) = when (this) {
            is BWAnd -> BWAnd(o1, o2, tag)
            is BWOr -> BWOr(o1, o2, tag)
            is BWXOr -> BWXOr(o1, o2, tag)
            is Div -> Div(o1, o2, tag as Tag.Bits?)
            is Exponent -> Exponent(o1, o2, tag as Tag.Bits?)
            is IntDiv -> IntDiv(o1, o2, tag as Tag.Int?)
            is IntSub -> IntSub(o1, o2, tag as Tag.Int?)
            is IntExponent -> IntExponent(o1, o2, tag as Tag.Int?)
            is Mod -> Mod(o1, o2, tag as Tag.Bits?)
            is SDiv -> SDiv(o1, o2, tag as Tag.Bits?)
            is SMod -> SMod(o1, o2, tag as Tag.Bits?)
            is IntMod -> IntMod(o1, o2, tag as Tag.Int?)
            is ShiftLeft -> ShiftLeft(o1, o2, tag)
            is ShiftRightArithmetical -> ShiftRightArithmetical(o1, o2, tag)
            is ShiftRightLogical -> ShiftRightLogical(o1, o2, tag)
            is SignExtend -> SignExtend(o1, o2, tag)
            is Sub -> Sub(o1, o2, tag as Tag.Bits?)
        }

        override fun eval(cs: List<BigInteger>): BigInteger {
            require(cs.size == 2)
            return eval(cs[0], cs[1])
        }

        /**
         * Represents a signextend of o2 from (o1+1)*8 bits to 256
         *
         * @param o1 denotes one less the assumed size (in bytes) of the signed integer that is to be extended
         *        (i.e. if o1 == 1, we're interpreting [o2] as an `int16`.).
         */
        @KSerializable
        data class SignExtend(
            override val o1: TACExpr, // the number of bits there are currently, minus one.
            override val o2: TACExpr,
            override val tag: Tag? = null
        ) : BinOp() {
            override fun toLExpression(conv: ToLExpression.Conv, meta: MetaMap?) =
                conv.lxf {
                    (conv(o2, meta) signExt conv(o1, meta))
                }

            override fun eval(o1: BigInteger, o2: BigInteger): BigInteger =
                evmSignExtend(o1, o2)

            override fun toPrintRep(cb: (TACSymbol.Var) -> String): String = this.toString()
        }

        @KSerializable
        data class IntSub(override val o1: TACExpr, override val o2: TACExpr, override val tag: Tag.Int? = null) :
            BinOp(), BinaryToLExpression, IntExp {
            override fun toString(): String {
                return "IntSub($o1 $o2)"
            }

            override fun toPrintRep(cb: (TACSymbol.Var) -> String): String {
                return "${o1.toPrintRepWithParentheses(cb)} -int ${o2.toPrintRepWithParentheses(cb)}"
            }

            override val smtName: FunctionSymbol
                get() = TheoryFunctionSymbol.Binary.IntSub

            override fun eval(o1: BigInteger, o2: BigInteger): BigInteger =
                o1.minus(o2)
        }

        @KSerializable
        data class Sub(override val o1: TACExpr, override val o2: TACExpr, override val tag: Tag.Bits? = null) :
            BinOp(), BinaryToLExpression, BitvectorExp {
            override fun toString(): String {
                return "Sub($o1 $o2)"
            }

            override fun toPrintRep(cb: (TACSymbol.Var) -> String): String {
                return "${o1.toPrintRepWithParentheses(cb)}-${o2.toPrintRepWithParentheses(cb)}"
            }

            override val smtName: FunctionSymbol
                get() = NonSMTInterpretedFunctionSymbol.Binary.Sub(tagAssumeChecked as Tag.Bits)

            override fun eval(o1: BigInteger, o2: BigInteger): BigInteger =
                (tag ?: Tag.Bit256).sub(o1, o2)
        }

        /** Division of two [Tag.Bits] operands, assuming both are positive. */
        @KSerializable
        data class Div(override val o1: TACExpr, override val o2: TACExpr, override val tag: Tag.Bits? = null) :
            BinOp(), ToLExpression, BitvectorExp {
            override fun toString(): String {
                return "Div($o1 $o2)"
            }

            override fun toPrintRep(cb: (TACSymbol.Var) -> String): String {
                return "${o1.toPrintRepWithParentheses(cb)}/${o2.toPrintRepWithParentheses(cb)}"
            }

            override fun toLExpression(conv: ToLExpression.Conv, meta: MetaMap?): LExpression = conv.lxf {
                val tag = tagAssumeChecked as Tag.Bits
                ite(
                    eq(conv(o2), ZERO),
                    lit(0, tag),
                    LExpression.ApplyExpr(NonSMTInterpretedFunctionSymbol.Binary.Div(tag), conv(o1), conv(o2))
                )
            }

            override fun eval(o1: BigInteger, o2: BigInteger): BigInteger =
                (tag ?: Tag.Bit256).div(o1, o2)
        }

        /** Division of two [Tag.Bits] operands, assuming both are in 2s-complement. */
        @KSerializable
        data class SDiv(override val o1: TACExpr, override val o2: TACExpr, override val tag: Tag.Bits? = null) :
            BinOp(), BitvectorExp {
            override fun toString(): String {
                return "SDiv($o1 $o2)"
            }

            override fun toPrintRep(cb: (TACSymbol.Var) -> String): String {
                return "${o1.toPrintRepWithParentheses(cb)} /s ${o2.toPrintRepWithParentheses(cb)}"
            }

            override fun eval(o1: BigInteger, o2: BigInteger): BigInteger =
                (tag ?: Tag.Bit256).sdiv(o1, o2)

            override fun toLExpression(
                conv: ToLExpression.Conv,
                meta: MetaMap?,
            ): LExpression {
                tag as Tag.Bits
                fun sign(e: TACExpr): TACExpr = BinRel.Le(e, tag.maxSigned.asTACExpr(tag), Tag.Bool)

                fun neg(e: TACExpr): TACExpr =
                    Sub(
                        tag.maxUnsigned.asTACExpr(tag),
                        Sub(e, BigInteger.ONE.asTACExpr(tag), tag),
                        tag
                    )

                fun abs(e: TACExpr): TACExpr =
                    TernaryExp.Ite(
                        sign(e), // pos
                        e,
                        // neg - take 2's complement- or in modular, maxuint - s
                        neg(e),
                        tag
                    )
                val asTACIte = TernaryExp.Ite(
                    BinBoolOp.LOr( // I don't like this, but that's what the EVM does. Consider moving to simplify
                        BinRel.Eq(o1, BigInteger.ZERO.asTACExpr(tag)),
                        BinRel.Eq(o2, BigInteger.ZERO.asTACExpr(tag))
                    ),
                    BigInteger.ZERO.asTACExpr(tag),
                    TernaryExp.Ite(
                        BinRel.Eq(sign(o1), sign(o2), Tag.Bool),
                        Div(abs(o1), abs(o2), tag),
                        // From the Go code: res.Div(x.Abs(x), y.Abs(y)); res.Neg(res)
                        neg(Div(abs(o1), abs(o2), tag)),
                        tag
                    )
                )
                return conv(asTACIte, meta)
            }
        }

        /** Division of two [Tag.Int] operands. */
        @KSerializable
        data class IntDiv(override val o1: TACExpr, override val o2: TACExpr, override val tag: Tag.Int? = null) :
            BinOp(), ToLExpression, IntExp {
            override fun toString(): String {
                return "IntDiv($o1 $o2)"
            }

            override fun toPrintRep(cb: (TACSymbol.Var) -> String): String {
                return "${o1.toPrintRepWithParentheses(cb)}/${o2.toPrintRepWithParentheses(cb)}"
            }

            override fun toLExpression(conv: ToLExpression.Conv, meta: MetaMap?): LExpression {
                // in the division-by-0 case, we allow an unconstrained value
                // we introduce a fresh variable for this purpose
                // however, if we proved that the value is of the denominator is never 0, we prefer to plug in
                // 0 instead of a skolem, because SMT solvers seem to behave better in that case.
                val skolem = if (o2.asVarOrNull?.meta?.contains(NON_ZERO_META) == true) {
                    conv.lxf.litInt(0)
                } else {
                    conv.lxf.const("div0" + Allocator.getFreshId(Allocator.Id.DIV_0_NONDET), Tag.Int)
                }
                return conv.lxf {
                    ite(
                        eq(conv(o2), ZERO),
                        skolem,
                        conv(o1) / conv(o2)
                    )
                }
            }

            override fun eval(o1: BigInteger, o2: BigInteger): BigInteger =
                if (o2 == BigInteger.ZERO) {
                    BigInteger.ZERO
                } else {
                    o1.divide(o2)
                }
        }

        /** Modulo of two [Tag.Bits] operands, assuming both are positive. */
        @KSerializable
        data class Mod(override val o1: TACExpr, override val o2: TACExpr, override val tag: Tag.Bits? = null) :
            BinOp(), ToLExpression, BitvectorExp {
            override fun toString(): String {
                return "Mod($o1 $o2)"
            }

            override fun toPrintRep(cb: (TACSymbol.Var) -> String): String {
                return "${o1.toPrintRepWithParentheses(cb)}%${o2.toPrintRepWithParentheses(cb)}"
            }

            override fun eval(o1: BigInteger, o2: BigInteger): BigInteger =
                (tag ?: Tag.Bit256).mod(o1, o2)

            override fun toLExpression(conv: ToLExpression.Conv, meta: MetaMap?): LExpression {
                val conv2 = conv(o2)
                val tag = tagAssumeChecked as Tag.Bits
                return conv.lxf {
                    ite(
                        eq(conv2, ZERO),
                        lit(0, tag),
                        LExpression.ApplyExpr(NonSMTInterpretedFunctionSymbol.Binary.Mod(tag), conv(o1), conv(o2))
                    )
                }
            }
        }

        /** Modulo of two [Tag.Bits] operands, assuming both are in 2s-complement. */
        @KSerializable
        data class SMod(override val o1: TACExpr, override val o2: TACExpr, override val tag: Tag.Bits? = null) :
            BinOp(), BitvectorExp {
            override fun toString(): String {
                return "SMod($o1 $o2)"
            }

            override fun toPrintRep(cb: (TACSymbol.Var) -> String): String {
                return "${o1.toPrintRepWithParentheses(cb)}%_s${o2.toPrintRepWithParentheses(cb)}"
            }

            override fun eval(o1: BigInteger, o2: BigInteger): BigInteger =
                (tag ?: Tag.Bit256).smod(o1, o2)

            override fun toLExpression(
                conv: ToLExpression.Conv,
                meta: MetaMap?,
            ): LExpression = conv.lxf {
                tag as Tag.Bits

                // absolute value of denom
                val n = conv(o2, meta).let {
                    ite(
                        it le lit(tag.maxSigned, tag),
                        it,
                        TwoTo256Tagged(tag) - it
                    )
                }

                val k = conv(o1, meta)

                switch(
                    n eq ZERO to ZERO,
                    // positive case
                    k intLe lit(tag.maxSigned, tag) to k % n,
                    // negative case: negate k, take mod, and negate again.
                    elseExpr = TwoTo256Tagged(k.tag) - ((TwoTo256Tagged(k.tag) - k) % n)
                )
            }
        }

        /** Modulo of two [Tag.Int] operands. Always returns a non-negative number. */
        @KSerializable
        data class IntMod(override val o1: TACExpr, override val o2: TACExpr, override val tag: Tag.Int? = null) :
            BinOp(), ToLExpression, IntExp {
            override fun toString(): String {
                return "IntMod($o1 $o2)"
            }

            override fun toPrintRep(cb: (TACSymbol.Var) -> String): String {
                return "${o1.toPrintRepWithParentheses(cb)}%${o2.toPrintRepWithParentheses(cb)}"
            }

            /** Following evm's smod, always returns a non-negative number from zero to `abs(o2)`. */
            override fun eval(o1: BigInteger, o2: BigInteger): BigInteger =
                o1.mod(o2.abs())

            override fun toLExpression(conv: ToLExpression.Conv, meta: MetaMap?): LExpression {
                // in the division-by-0 case, we allow an unconstrained value
                // we introduce a fresh variable for this purpose
                val skolem = conv.lxf.const("mod0" + Allocator.getFreshId(Allocator.Id.MOD_0_NONDET), Tag.Int)
                return conv.lxf {
                    ite(
                        eq(conv(o2), ZERO),
                        skolem,
                        conv(o1) % conv(o2)
                    )
                }
            }
        }

        @KSerializable
        data class Exponent(override val o1: TACExpr, override val o2: TACExpr, override val tag: Tag.Bits? = null) :
            BinOp() {
            override fun toString(): String {
                return "Exponent($o1 $o2)"
            }

            override fun toPrintRep(cb: (TACSymbol.Var) -> String): String {
                return "${o1.toPrintRepWithParentheses(cb)}^${o2.toPrintRepWithParentheses(cb)}"
            }

            override fun toPrintRepWithParentheses(cb: (TACSymbol.Var) -> String): String = toPrintRep(cb)

            override fun toLExpression(
                conv: ToLExpression.Conv,
                meta: MetaMap?,
            ): LExpression {
                val x = o1.getAsConst()
                val y = o2.getAsConst()
                if (x != null && y != null) {
                    return conv.lxf.lit(this.eval(x, y), tagAssumeChecked)
                }
                // power of two, convert to shift
                return if (x != null && x.bitCount() == 1) {
                    val pow = x.lowestSetBit
                    /*
                      so we have (2^pow)^x aka 1 << pow * x
                     */
                    conv.lxf {
                        (lit(1, o1.tagAssumeChecked) shl (litInt(pow) intMul conv(o2, meta)).addMeta(meta)).addMeta(meta)
                    }
                } else {
                    conv.lxf {
                        applyExp(NonSMTInterpretedFunctionSymbol.Binary.Exp(tag!!), conv(o1, meta), conv(o2, meta))
                            .addMeta(meta)
                    }
                }
            }

            override fun eval(o1: BigInteger, o2: BigInteger): BigInteger =
                (tag as Tag.Bits).exp(o1, o2)
        }

        @KSerializable
        data class IntExponent(override val o1: TACExpr, override val o2: TACExpr, override val tag: Tag.Int? = null) :
            BinOp(), IntExp {
            override fun toString(): String {
                return "IntExponent($o1 $o2)"
            }

            override fun toPrintRep(cb: (TACSymbol.Var) -> String): String {
                return "${o1.toPrintRepWithParentheses(cb)} ^int ${o2.toPrintRepWithParentheses(cb)}"
            }

            override fun toPrintRepWithParentheses(cb: (TACSymbol.Var) -> String): String = toPrintRep(cb)

            override fun toLExpression(
                conv: ToLExpression.Conv,
                meta: MetaMap?,
            ): LExpression {
                val x = o1.getAsConst()
                val y = o2.getAsConst()
                if (x != null && y != null) {
                    return conv.lxf.litInt(this.eval(x, y))
                }
                return conv.lxf { (conv(o1, meta) uninterpExp conv(o2, meta)).addMeta(meta) }
            }

            override fun eval(o1: BigInteger, o2: BigInteger): BigInteger =
                o1.pow(o2.toInt())
        }

        @KSerializable
        data class BWAnd(override val o1: TACExpr, override val o2: TACExpr, override val tag: Tag? = null) :
            BinOp(), BinaryToLExpression {
            override fun toString(): String {
                return "BWAnd($o1 $o2)"
            }

            override fun toPrintRep(cb: (TACSymbol.Var) -> String): String {
                return "${o1.toPrintRepWithParentheses(cb)}&amp;${o2.toPrintRepWithParentheses(cb)}"
            }

            override val smtName: FunctionSymbol
                get() = NonSMTInterpretedFunctionSymbol.Binary.BitwiseAnd(tagAssumeChecked)

            override fun eval(o1: BigInteger, o2: BigInteger): BigInteger =
                o1 and o2
        }

        @KSerializable
        data class BWOr(override val o1: TACExpr, override val o2: TACExpr, override val tag: Tag? = null) :
            BinOp(), BinaryToLExpression {
            override fun toString(): String {
                return "BWOr($o1 $o2)"
            }

            override fun toPrintRep(cb: (TACSymbol.Var) -> String): String {
                return "${o1.toPrintRepWithParentheses(cb)}|${o2.toPrintRepWithParentheses(cb)}"
            }

            override val smtName: FunctionSymbol
                get() = NonSMTInterpretedFunctionSymbol.Binary.BitwiseOr(tagAssumeChecked)

            override fun eval(o1: BigInteger, o2: BigInteger): BigInteger =
                o1 or o2
        }

        @KSerializable
        data class BWXOr(override val o1: TACExpr, override val o2: TACExpr, override val tag: Tag? = null) :
            BinOp(), BinaryToLExpression {
            override fun toString(): String {
                return "BWXOr($o1 $o2)"
            }

            override fun toPrintRep(cb: (TACSymbol.Var) -> String): String {
                return "${o1.toPrintRepWithParentheses(cb)} xor ${o2.toPrintRepWithParentheses(cb)}"
            }

            override val smtName: FunctionSymbol
                get() = NonSMTInterpretedFunctionSymbol.Binary.BitwiseXor(tagAssumeChecked)

            override fun eval(o1: BigInteger, o2: BigInteger): BigInteger =
                o1 xor o2
        }

        /**
         * Left shift. Shift [o1] to the left by [o2] bits.
         */
        @KSerializable
        data class ShiftLeft(override val o1: TACExpr, override val o2: TACExpr, override val tag: Tag? = null) :
            BinOp(), BinaryToLExpression {
            override fun toString(): String {
                return "ShiftLeft($o1 $o2)"
            }

            override fun toPrintRep(cb: (TACSymbol.Var) -> String): String {
                return "${o1.toPrintRepWithParentheses(cb)}&lt;&lt;${o2.toPrintRepWithParentheses(cb)}"
            }

            override val smtName: FunctionSymbol
                get() = NonSMTInterpretedFunctionSymbol.Binary.ShiftLeft(o1.tag as Tag.Bits)

            override fun eval(o1: BigInteger, o2: BigInteger): BigInteger =
                (tag as Tag.Bits).shl(o1, o2)
        }

        /**
         * Logical right shift. Shift [o1] to the right by [o2] bits.
         * (Logical right shift is the naive one that just moves stuff to the right and fills 0s on the left.)
         */
        @KSerializable
        data class ShiftRightLogical(
            override val o1: TACExpr,
            override val o2: TACExpr,
            override val tag: Tag? = null
        ) :
            BinOp(), BinaryToLExpression {
            override fun toString(): String {
                return "ShiftRightLogical($o1 $o2)"
            }

            override fun toPrintRep(cb: (TACSymbol.Var) -> String): String {
                return "${o1.toPrintRepWithParentheses(cb)}&gt;&gt;l${o2.toPrintRepWithParentheses(cb)}"
            }

            override val smtName: FunctionSymbol
                get() = NonSMTInterpretedFunctionSymbol.Binary.ShiftRightLogical(o2.tagAssumeChecked)

            override fun eval(o1: BigInteger, o2: BigInteger): BigInteger =
                shr(o1, o2)
        }

        /**
         * Arithmetical right shift. Shift [o1] to the right by [o2] bits.
         * (Arithmetical right shift views the number as a signed integer and preserves the sign, only shifting the
         * "number part".)
         */
        @KSerializable
        data class ShiftRightArithmetical(
            override val o1: TACExpr,
            override val o2: TACExpr,
            override val tag: Tag? = null
        ) : BinOp() {
            override fun toString(): String {
                return "ShiftRightArithmetical($o1 $o2)"
            }

            override fun toLExpression(
                conv: ToLExpression.Conv,
                meta: MetaMap?,
            ): LExpression = conv.lxf {
                val tag = tagAssumeChecked as Tag.Bits
                o1.getAsConst()?.let { x ->
                    o2.getAsConst()?.let { k ->
                        return@lxf lit(eval(x, k), tag)
                    }
                }
                val x = conv(o1, meta)
                val k = conv(o2, meta).let { kexp: LExpression ->
                    kexp.letIf(kexp.tag == Tag.Int) {
                        conv.lxf { kexp safeMathNarrow tag }
                    }
                }
                val h = o2.getAsConst()
                    ?.toIntOrNull()
                    ?.takeIf { it in 0 until tag.bitwidth }
                    ?.let { lit(highOnes(it, tag), tag) }
                    ?: (lit(tag.modulus, tag) sub (lit(2, tag) uninterpExp (lit(tag.bitwidth, tag) sub k)))
                ite(
                    x gt lit(tag.maxSigned, tag),
                    (x shr k) add h,
                    x shr k
                ).addMeta(meta)
            }

            override fun toPrintRep(cb: (TACSymbol.Var) -> String): String {
                return "${o1.toPrintRepWithParentheses(cb)}&gt;&gt;a${o2.toPrintRepWithParentheses(cb)}"
            }

            override fun eval(o1: BigInteger, o2: BigInteger): BigInteger =
                (tag as Tag.Bits).sar(o1, o2)
        }
    }


    @KSerializable
    sealed class BinRel : TACExpr(), BinExp, BinaryToLExpression, BoolExp {

        /** Copy method on sealed class level, analogous to the data class's `copy` methods. */
        @JvmName("copy1")
        fun copy(o1: TACExpr = this.o1, o2: TACExpr = this.o2) = when (this) {
            is Eq -> Eq(o1, o2, tag)
            is Ge -> Ge(o1, o2, tag)
            is Gt -> Gt(o1, o2, tag)
            is Le -> Le(o1, o2, tag)
            is Lt -> Lt(o1, o2, tag)
            is Sge -> Sge(o1, o2, tag)
            is Sgt -> Sgt(o1, o2, tag)
            is Sle -> Sle(o1, o2, tag)
            is Slt -> Slt(o1, o2, tag)
        }
        /** Copy method on sealed class level, analogous to the data class's `copy` methods. */
        @JvmName("copy1")
        fun copy(tag: Tag.Bool? = this.tag) = when (this) {
            is Eq -> Eq(o1, o2, tag)
            is Ge -> Ge(o1, o2, tag)
            is Gt -> Gt(o1, o2, tag)
            is Le -> Le(o1, o2, tag)
            is Lt -> Lt(o1, o2, tag)
            is Sge -> Sge(o1, o2, tag)
            is Sgt -> Sgt(o1, o2, tag)
            is Sle -> Sle(o1, o2, tag)
            is Slt -> Slt(o1, o2, tag)
        }

        override fun eval(cs: List<BigInteger>): BigInteger {
            require(cs.size == 2)
            return eval(cs[0], cs[1])
        }

        @KSerializable
        data class Gt(
            override val o1: TACExpr,
            override val o2: TACExpr,
            override val tag: Tag.Bool? = null
        ) : BinRel() {

            override fun toString(): String {
                return "Gt($o1 $o2)"
            }

            override fun toPrintRep(cb: (TACSymbol.Var) -> String): String {
                return "${o1.toPrintRepWithParentheses(cb)}&gt;${o2.toPrintRepWithParentheses(cb)}"
            }

            override val smtName: FunctionSymbol
                get() = NonSMTInterpretedFunctionSymbol.Binary.Gt

            override fun eval(o1: BigInteger, o2: BigInteger): BigInteger =
                (o1 > o2).asBigInteger
        }

        @KSerializable
        data class Lt(
            override val o1: TACExpr,
            override val o2: TACExpr,
            override val tag: Tag.Bool? = null
        ) : BinRel() {

            override fun toString(): String {
                return "Lt($o1 $o2)"
            }

            override fun toPrintRep(cb: (TACSymbol.Var) -> String): String {
                return "${o1.toPrintRepWithParentheses(cb)}&lt;${o2.toPrintRepWithParentheses(cb)}"
            }

            override val smtName: FunctionSymbol
                get() = NonSMTInterpretedFunctionSymbol.Binary.Lt

            override fun eval(o1: BigInteger, o2: BigInteger): BigInteger =
                (o1 < o2).asBigInteger
        }

        internal val argBitsTag get() = commonSuperTag(o1.tagAssumeChecked, o2.tagAssumeChecked) as Tag.Bits

        @KSerializable
        data class Slt(
            override val o1: TACExpr,
            override val o2: TACExpr,
            override val tag: Tag.Bool? = null
        ) : BinRel() {

            override fun toString(): String {
                return "Slt($o1 $o2)"
            }

            override fun toPrintRep(cb: (TACSymbol.Var) -> String): String {
                return "${o1.toPrintRepWithParentheses(cb)}s&lt;${o2.toPrintRepWithParentheses(cb)}"
            }

            override val smtName: FunctionSymbol
                get() = NonSMTInterpretedFunctionSymbol.Binary.Slt(argBitsTag)

            override fun eval(o1: BigInteger, o2: BigInteger): BigInteger =
                argBitsTag.slt(o1, o2)
        }

        @KSerializable
        data class Sle(
            override val o1: TACExpr,
            override val o2: TACExpr,
            override val tag: Tag.Bool? = null
        ) : BinRel() {

            override fun toString(): String {
                return "Sle($o1 $o2)"
            }

            override fun toPrintRep(cb: (TACSymbol.Var) -> String): String {
                return "${o1.toPrintRepWithParentheses(cb)}s&le;${o2.toPrintRepWithParentheses(cb)}"
            }

            override val smtName: FunctionSymbol
                get() = NonSMTInterpretedFunctionSymbol.Binary.Sle(argBitsTag)

            override fun eval(o1: BigInteger, o2: BigInteger): BigInteger =
                argBitsTag.sle(o1, o2)
        }

        @KSerializable
        data class Sgt(
            override val o1: TACExpr,
            override val o2: TACExpr,
            override val tag: Tag.Bool? = null
        ) : BinRel() {

            override fun toString(): String {
                return "Sgt($o1 $o2)"
            }

            override fun toPrintRep(cb: (TACSymbol.Var) -> String): String {
                return "${o1.toPrintRepWithParentheses(cb)}s&gt;${o2.toPrintRepWithParentheses(cb)}"
            }

            override val smtName: FunctionSymbol
                get() = NonSMTInterpretedFunctionSymbol.Binary.Sgt(argBitsTag)

            override fun eval(o1: BigInteger, o2: BigInteger): BigInteger =
                argBitsTag.sgt(o1, o2)
        }

        @KSerializable
        data class Sge(
            override val o1: TACExpr,
            override val o2: TACExpr,
            override val tag: Tag.Bool? = null
        ) : BinRel() {

            override fun toString(): String {
                return "Sge($o1 $o2)"
            }

            override fun toPrintRep(cb: (TACSymbol.Var) -> String): String {
                return "${o1.toPrintRepWithParentheses(cb)}s&ge;${o2.toPrintRepWithParentheses(cb)}"
            }

            override val smtName: FunctionSymbol
                get() = NonSMTInterpretedFunctionSymbol.Binary.Sge(argBitsTag)

            override fun eval(o1: BigInteger, o2: BigInteger): BigInteger =
                argBitsTag.sge(o1, o2)
        }

        @KSerializable
        data class Ge(
            override val o1: TACExpr,
            override val o2: TACExpr,
            override val tag: Tag.Bool? = null
        ) : BinRel() {

            override fun toString(): String {
                return "Ge($o1 $o2)"
            }

            override fun toPrintRep(cb: (TACSymbol.Var) -> String): String {
                return "${o1.toPrintRepWithParentheses(cb)}&gt;=${o2.toPrintRepWithParentheses(cb)}"
            }

            override val smtName: FunctionSymbol
                get() = NonSMTInterpretedFunctionSymbol.Binary.Ge

            override fun eval(o1: BigInteger, o2: BigInteger): BigInteger =
                (o1 >= o2).asBigInteger
        }

        @KSerializable
        data class Le(
            override val o1: TACExpr,
            override val o2: TACExpr,
            override val tag: Tag.Bool? = null
        ) : BinRel() {

            override fun toString(): String {
                return "Le($o1 $o2)"
            }

            override fun toPrintRep(cb: (TACSymbol.Var) -> String): String {
                return "${o1.toPrintRepWithParentheses(cb)}&lt;=${o2.toPrintRepWithParentheses(cb)}"
            }

            override val smtName: FunctionSymbol
                get() = NonSMTInterpretedFunctionSymbol.Binary.Le

            override fun eval(o1: BigInteger, o2: BigInteger): BigInteger =
                (o1 <= o2).asBigInteger
        }

        @KSerializable
        data class Eq(
            override val o1: TACExpr,
            override val o2: TACExpr,
            override val tag: Tag.Bool? = null
        ) : BinRel() {

            override fun toString(): String {
                return "Eq($o1 $o2)"
            }

            override fun toPrintRep(cb: (TACSymbol.Var) -> String): String {
                return "${o1.toPrintRepWithParentheses(cb)}==${o2.toPrintRepWithParentheses(cb)}"
            }

            override val smtName: FunctionSymbol
                get() = TheoryFunctionSymbol.Binary.Eq(commonSuperTag(o1.tagAssumeChecked, o2.tagAssumeChecked))

            override fun eval(o1: BigInteger, o2: BigInteger): BigInteger =
                (o1 == o2).asBigInteger
        }

    }

    @KSerializable
    @Treapable
    sealed class TACFunctionSym : AmbiSerializable {

        abstract val name: String

        /**
         *  bif: The class corresponding to name.
         *  Avoid using name. Use bif instead.
         */
        @KSerializable
        data class BuiltIn(val bif: TACBuiltInFunction) : TACFunctionSym() { // ignore when collecting

            override val name: String
                get() = bif.name

            override fun toString(): String {
                return "$name:bif"
            }
        }

        @KSerializable
        data class Adhoc(override val name: String) : TACFunctionSym() { // ignore when collecting

            override fun toString(): String {
                return "$name:Adhoc"
            }
        }
    }

    @KSerializable
    sealed class Apply : TACExpr() {
        abstract val f: TACFunctionSym

        companion object {
            operator fun invoke(f: TACFunctionSym, ops: List<TACExpr>, tag: Tag?) =
                when(ops.size) {
                    1 -> Unary(f, ops[0], tag)
                    2 -> Binary(f, ops[0], ops[1], tag)
                    else -> Nary(f, ops, tag)
                }

            operator fun invoke(f: TACBuiltInFunction, ops: List<TACExpr>, tag: Tag?) =
                Apply(TACFunctionSym.BuiltIn(f), ops, tag)
        }

        abstract val ops: List<TACExpr>

        fun copy(f: TACFunctionSym = this.f, ops: List<TACExpr> = this.ops, tag: Tag? = this.tag) =
            Apply(f, ops, tag)

        override fun eval(cs: List<BigInteger>) =
            (f as? TACFunctionSym.BuiltIn)?.bif?.eval(cs)


        override fun equals(other: Any?) =
            other is Apply && other.f == this.f && other.ops == this.ops && other.tag == this.tag

        override fun hashCode(): Int {
            var h = f.hashCode()
            h = 31 * h + ops.hashCode()
            h = 31 * h + tag.hashCode()
            return h
        }

        override fun toString(): String {
            return "Apply($f ${ops.joinToString(" ")})"
        }

        override fun toPrintRep(cb: (TACSymbol.Var) -> String): String {
            return "$f(${ops.joinToString(",") { it.toPrintRep(cb) }})"
        }

        override fun toPrintRepWithParentheses(cb: (TACSymbol.Var) -> String): String = toPrintRep(cb)

        override fun toLExpression(
            conv: ToLExpression.Conv,
            meta: MetaMap?,
        ): LExpression =
            when (val ff = f) {
                is TACFunctionSym.BuiltIn -> ff.bif.getLExpressionBuilder(conv, meta)(ops)
                is TACFunctionSym.Adhoc ->
                    throw UnsupportedOperationException("Cannot convert $f because it is an Adhoc TACFunctionSym")
            }

        @KSerializable
        class Unary(
            override val f: TACFunctionSym,
            val op: TACExpr,
            override val tag: Tag?
        ) : Apply() {
            override val ops get() = listOf(op)
        }

        @KSerializable
        class Binary(
            override val f: TACFunctionSym,
            val op1: TACExpr,
            val op2: TACExpr,
            override val tag: Tag?
        ) : Apply() {
            override val ops get() = listOf(op1, op2)
        }

        @KSerializable
        class Nary(
            override val f: TACFunctionSym,
            override val ops: List<TACExpr>,
            override val tag: Tag?
        ) : Apply()
    }

    @KSerializable
    data class Select(val base: TACExpr, val loc: TACExpr, override val tag: Tag? = null) : TACExpr() {
        override fun eval(cs: List<BigInteger>) = null

        fun baseAsTACSymbolVar(): TACSymbol.Var? =
            if (base is Sym && base.s is TACSymbol.Var) (base.s as TACSymbol.Var) else null

        fun locAsTACSymbol(): TACSymbol? = if (loc is Sym) loc.s else null

        override fun toPrintRepWithParentheses(cb: (TACSymbol.Var) -> String): String = toPrintRep(cb)

        /** a triple (innermost base, indices, map type) */
        data class MultiDimSelectInfo(
            val base: TACExpr,
            val indices: List<TACExpr>,
            val baseTag: Tag,
            val origExp: Select
        ) {
            init {
                check(base is Sym.Var || base is TernaryExp.Ite) {
                    "In MultiDimSelectInfo instance, expected $base to be either a symbol or an ITE expression"
                }
            }
        }

        /**
         * Note: When we have TACExpr.MultiSelect (if we ever introduce it), this pattern matching class can probably go.
         * */
        fun extractMultiDimSelectInfo(): MultiDimSelectInfo {
            val indices = mutableListOf<TACExpr>()
            var curBase: TACExpr = this
            while (curBase is Select) {
                indices.add(curBase.loc)
                curBase = curBase.base
            }
            val baseTag = curBase.tagAssumeChecked
            check(indices.size == 1 || (baseTag is Tag.GhostMap && baseTag.paramSorts.size == indices.size))
            { "wrong ghostmap signature for multiselect: $baseTag" }
            return MultiDimSelectInfo(curBase, indices.asReversed(), baseTag, this)
        }

        companion object {
            fun buildMultiDimSelect(base: TACExpr, locs: List<TACExpr>): TACExpr =
                locs.fold(base) { l, b -> Select(l, b) }
        }

        override fun toString(): String {
            return "Select(${base} ${loc})"
        }

        override fun toLExpression(
            conv: ToLExpression.Conv,
            meta: MetaMap?,
        ): LExpression {
            return when (this.base) {
                is Select -> {
                    val (tacBase, tacIndices, tacBaseTag) = this.extractMultiDimSelectInfo()
                    conv.lxf.applyExp(
                        NonSMTInterpretedFunctionSymbol.MultiDimArray.MultiSelect(tacBaseTag as Tag.GhostMap),
                        (listOf(tacBase) + tacIndices).map { conv(it, meta) }
                    )
                }
                else -> {
                    val baseTag = base.tagAssumeChecked
                    val locLExp = conv(loc, meta)
                    val locTag = loc.tagAssumeChecked
                    val baseLExp = conv(base, meta)
                    val skeySort = if (baseTag == Tag.WordMap) locTag else null
                    conv.lxf.applyExp(
                        TheoryFunctionSymbol.Array.Select(baseTag, skeySort),
                        baseLExp,
                        locLExp
                    )
                }
            }
        }

        override fun toPrintRep(cb: (TACSymbol.Var) -> String): String {
            return "${base.toPrintRep(cb)}[${loc.toPrintRep(cb)}]"
        }
    }

    /**
     * @param defParams the lambda arguments of the definition -- note that [defParams].length equals the dimension of
     *    the defined array
     *     also NB: [defParams] are essentially binders, like in a lambda, or a quantifiers -- that means they're not
     *       subject to transformations etc, but are handled like quantified variables for those purposes
     * @param isWordMap used to distinguish bytemap and wordmap, which have the same signature in terms of parameter
     *    and return types.. -- once we represent that distinction differently, we can drop this parameter
     */
    @KSerializable
    data class MapDefinition(
        val defParams: List<Sym.Var>,
        val definition: TACExpr,
        override val tag: Tag.Map
    ) : TACExpr() {

        // we can actually calculate this...
        override fun eval(cs: List<BigInteger>) = null

        override fun toPrintRep(cb: (TACSymbol.Var) -> String): String =
            "[${defParams.joinToString(", ") { it.toPrintRep(cb) }} -> ${definition.toPrintRep(cb)}]"

        override fun toString(): String = toPrintRep { v -> v.toSMTRep() }

        override fun toLExpression(
            conv: ToLExpression.Conv,
            meta: MetaMap?,
        ): LExpression {
            return conv.lxf.applyExp(
                fs = AxiomatizedFunctionSymbol.MapDefinition(tag),
                // todo: avoid the defParams being declared in the smt script ?
                arguments = defParams.map { conv(it, meta) } + listOf(conv(definition, meta)),
                meta = meta
            )
        }

        companion object {
            operator fun invoke(tag: Tag.Map, gen: (Sym.Var) -> TACExpr) : TACExpr.MapDefinition {
                val tmp = TACKeyword.TMP(Tag.Bit256, "!ind")
                return MapDefinition(
                    defParams = listOf(tmp.asSym()),
                    definition = gen(tmp.asSym()),
                    tag
                )
            }
        }
    }

    /**
     * Represents a value that is unconstrained by the program.
     * Note that for now this is only used in the context of [MapDefinition] to denote uninitialized fields.
     * Note that every instance of this symbol can have a different value, especially also in e.g. the context of a
     * lambda. */
    @KSerializable
    data class Unconstrained(override val tag: Tag) : TACExpr() {
        override fun toPrintRep(cb: (TACSymbol.Var) -> String): String = "*"

        override fun eval(cs: List<BigInteger>) = null

        override fun toLExpression(
            conv: ToLExpression.Conv,
            meta: MetaMap?,
        ): LExpression {
            return conv.lxf {
                applyExp(NonSMTInterpretedFunctionSymbol.Nullary.Nondet(tagAssumeChecked), meta = meta)
            }
        }
    }

    sealed interface StoreExpr {
        val base: TACExpr
        val locs: List<TACExpr>
        val value: TACExpr
    }

    @KSerializable
    data class Store(
        override val base: TACExpr,
        val loc: TACExpr,
        override val value: TACExpr,
        override val tag: Tag? = null
    ) : TACExpr(), StoreExpr {
        override val locs: List<TACExpr> get() = listOf(loc)

        override fun eval(cs: List<BigInteger>) = null

        override fun toString(): String {
            return "Store($base $loc $value)"
        }

        override fun toLExpression(
            conv: ToLExpression.Conv,
            meta: MetaMap?,
        ): LExpression {
            val baseTag = base.tagAssumeChecked
            val locTag = loc.tagAssumeChecked
            val skeySort = if (baseTag == Tag.WordMap) locTag else null
            return conv.lxf.applyExp(
                TheoryFunctionSymbol.Array.Store(baseTag, skeySort),
                conv(base, meta),
                conv(loc, meta),
                conv(value, meta)
            )
        }

        override fun toPrintRep(cb: (TACSymbol.Var) -> String): String =
            "Store(${base.toPrintRep(cb)} ${loc.toPrintRep(cb)} ${value.toPrintRep(cb)})"
    }

    @KSerializable
    data class MultiDimStore(
        override val base: TACExpr,
        override val locs: List<TACExpr>,
        override val value: TACExpr,
        override val tag: Tag? = null
    ) : TACExpr(), StoreExpr {
        init {
            check(locs.size > 1) { "trying to create a MultiDimStore with less than 2 indices ($this)" }
        }

        override fun eval(cs: List<BigInteger>) = null

        override fun toString(): String {
            return "MultiDimStore($base $locs $value)"
        }

        override fun toLExpression(
            conv: ToLExpression.Conv,
            meta: MetaMap?,
        ): LExpression {
            val baseTag = base.tagAssumeChecked
            return conv.lxf.applyExp(
                NonSMTInterpretedFunctionSymbol.MultiDimArray.MultiStore(baseTag as Tag.GhostMap),
                (listOf(base) + locs + value).map { conv(it, meta) }
            )
        }

        override fun toPrintRep(cb: (TACSymbol.Var) -> String): String {
            return "${base.toPrintRep(cb)}[${locs.joinToString(" ") { it.toPrintRep(cb) }}] = ${value.toPrintRep(cb)}"
        }
    }

    @KSerializable
    data class LongStore(
        val dstMap: TACExpr,
        val dstOffset: TACExpr,
        val srcMap: TACExpr,
        val srcOffset: TACExpr,
        val length: TACExpr,
        override val tag: Tag? = null
    ) : TACExpr() {

        override fun eval(cs: List<BigInteger>) = null

        override fun toString(): String {
            return "LongStore($dstMap $dstOffset $srcMap $srcOffset $length)"
        }

        override fun toLExpression(
            conv: ToLExpression.Conv,
            meta: MetaMap?,
        ): LExpression {
            return conv.lxf.applyExp(
                AxiomatizedFunctionSymbol.LongStore,
                conv(dstMap, meta),
                conv(dstOffset, meta),
                conv(srcMap, meta),
                conv(srcOffset, meta),
                conv(length, meta)
            )
        }

        override fun toPrintRep(cb: (TACSymbol.Var) -> String): String {
            return "${dstMap.toPrintRep(cb)}[${dstOffset.toPrintRep(cb)}:${dstOffset.toPrintRep(cb)}+${length.toPrintRepWithParentheses(cb)}] = " +
                    "${srcMap.toPrintRep(cb)}[${srcOffset.toPrintRep(cb)}:${srcOffset.toPrintRep(cb)}+${length.toPrintRepWithParentheses(cb)}]"
        }
    }

    @KSerializable
    sealed class BinBoolOp : TACExpr(), BinExp, VecToLExpressionWithInitBool, BoolExp {

        abstract override fun eval(cs: List<BigInteger>): BigInteger

        /**
         * The errors issued in o1.get(), o2.get() in the case of "BinOp" with more than 2 operands is in order to check
         * we don't ignore cases with more than 2 operands.
         * So o1, o2 should not be used. Instead, use an iterator to a list of the operands.
         */
        override val o1: TACExpr
            get() {
                if (ls.size == 2) {
                    return ls[0]
                }
                error("The BinBoolOp expression $this is not a binary expression")
            }
        override val o2: TACExpr
            get() {
                if (ls.size == 2) {
                    return ls[1]
                }
                error("The BinBoolOp expression $this is not a binary expression")
            }

        companion object {
            inline fun <reified T : BinBoolOp> build(from: T): (List<TACExpr>) -> T = { ls: List<TACExpr> ->
                when (from) {
                    is LAnd -> LAnd(ls)
                    is LOr -> LOr(ls)
                    else -> throw UnsupportedOperationException("$from is a BinBoolOp and not covered")
                } as T
            }
        }

        protected fun BigInteger.asBool() = this.let {
            check(it == BigInteger.ONE || it == BigInteger.ZERO) { "$this is not a boolean" }
            it == BigInteger.ONE
        }

        @KSerializable
        data class LAnd(override val ls: List<TACExpr>, override val tag: Tag.Bool? = null) : BinBoolOp() {

            override val smtName: FunctionSymbol
                get() = TheoryFunctionSymbol.Vec.LAnd

            constructor(op1: TACExpr, op2: TACExpr, tag: Tag.Bool? = null) : this(listOf(op1, op2), tag)

            override fun toString(): String {
                return "LAnd(${ls.joinToString(" ")})"
            }

            override fun toPrintRep(cb: (TACSymbol.Var) -> String): String {
                return ls.joinToString("&&") {
                    if (it is LAnd) {
                        it.toPrintRep(cb)
                    } else {
                        it.toPrintRepWithParentheses(cb)
                    }
                }
            }

            override fun eval(o1: BigInteger, o2: BigInteger): BigInteger =
                (o1.asBool() && o2.asBool()).asBigInteger

            override val initValue: Boolean
                get() = true

            override fun eval(cs: List<BigInteger>): BigInteger =
                cs.fold(BigInteger.ONE) { A: BigInteger, b: BigInteger ->
                    eval(A, b).let {
                        if (it == BigInteger.ZERO) {
                            return@eval BigInteger.ZERO
                        }
                        it
                    }
                }
        }

        @KSerializable
        data class LOr(override val ls: List<TACExpr>, override val tag: Tag.Bool? = null) : BinBoolOp() {
            override val smtName: FunctionSymbol
                get() = TheoryFunctionSymbol.Vec.LOr

            constructor(op1: TACExpr, op2: TACExpr, tag: Tag.Bool? = null) : this(listOf(op1, op2), tag)

            override fun toString(): String {
                return "LOr(${ls.joinToString(" ")})"
            }

            override fun toPrintRep(cb: (TACSymbol.Var) -> String): String {
                return ls.joinToString("||") {
                    if (it is LOr) {
                        it.toPrintRep(cb)
                    } else {
                        it.toPrintRepWithParentheses(cb)
                    }
                }
            }

            override fun eval(o1: BigInteger, o2: BigInteger): BigInteger =
                (o1.asBool() || o2.asBool()).asBigInteger

            override val initValue: Boolean
                get() = false

            override fun eval(cs: List<BigInteger>): BigInteger =
                cs.fold(BigInteger.ZERO) { A: BigInteger, b: BigInteger ->
                    eval(A, b).let {
                        if (it == BigInteger.ONE) {
                            return@eval BigInteger.ONE
                        }
                        it
                    }
                }
        }
    }

    /**
     * TODO: not sure how well this works in the current state, probably only as a RHS (right-hand-side ... of an
     * assignment) -- for LHSs we need more substantial changes to [TACCmd]s
     * @param struct - Sym/Sym.Var/StructConstant/StructAccess.
     * @param tag - field's tag. Should be non-null after type checking.
     */
    @KSerializable
    data class StructAccess(val struct: TACExpr, val fieldName: String, override val tag: Tag? = null) :
        TACExpr() {

        companion object {
            fun constructVarName(structString: String, fieldName: String) = "$structString.$fieldName"
        }

        override fun eval(cs: List<BigInteger>) = null

        override fun toString(): String {
            return "StructAccess($struct  $fieldName)"
        }

        override fun toPrintRep(cb: (TACSymbol.Var) -> String): String {
            return constructVarName(struct.toPrintRep(cb), fieldName)
        }

        override fun toLExpression(
            conv: ToLExpression.Conv,
            meta: MetaMap?,
        ): LExpression {
            throw UnsupportedOperationException("Structs should be flattened before conversion to LExpression ($this)")
        }

        private fun toSymbolInner(): TACSymbol {
            return when (val r = struct) {
                is Sym.Var -> {
                    val t = r.tag
                    check(t is Tag.UserDefined.Struct)
                    check(t.getField(this.fieldName) != null)
                    var ret = r.s
                    (ret.meta.find(CVL_TYPE) as? CVLType.PureCVLType.Struct)?.let { structType ->
                        ret = ret.withMeta(CVL_STRUCT_PATH, CVLStructPathNode(structType, listOf()))
                    }
                    derefStructSym(t, ret)
                }
                is StructConstant -> {
                    r.fieldNameToValue.get(fieldName).let {
                        when(it) {
                            is Sym -> it.s
                            is StructAccess -> it.toSymbol()
                            else -> throw java.lang.UnsupportedOperationException("Cannot flatten constant field $fieldName of complex form in $struct (definition is: ${r.fieldNameToValue[fieldName]})")
                        }
                    }
                }
                is StructAccess -> {
                    val sym = r.toSymbolInner()
                    check(sym is TACSymbol.Var)
                    val tag = sym.tag
                    check(tag is Tag.UserDefined.Struct)
                    check(tag.containsField(fieldName))
                    derefStructSym(tag, sym)
                }
                else -> throw java.lang.UnsupportedOperationException("Cannot dereference struct of form $struct")
            }
        }

        private fun derefStructSym(tag: Tag.UserDefined.Struct, sym: TACSymbol.Var): TACSymbol.Var {
            val resultTag = tag.getField(fieldName)!!.type
            val theVariable = TACSymbol.Var("${sym.namePrefix}.$fieldName", resultTag).withMeta(sym.meta)
            var ret = theVariable
            sym.meta.find(CVL_DISPLAY_NAME)?.let { display ->
                ret = ret.withMeta(CVL_DISPLAY_NAME, "$display.$fieldName")
            }
            sym.meta.find(CVL_TYPE)?.let { t ->
                (t as? CVLType.PureCVLType.Struct)?.getEntryOrNull(fieldName)?.cvlType?.let { newType ->
                    ret = ret.withMeta(CVL_TYPE, newType)
                    sym.meta.find(CVL_STRUCT_PATH)?.let { path ->
                        ret = ret.withMeta(CVL_STRUCT_PATH, CVLStructPathNode(path.rootStructType, path.fields.plus(CVLType.PureCVLType.Struct.StructEntry(fieldName, newType))))
                    }
                }
            }
            return ret
        }

        /** @return a symbol corresponding to the field.  It will be a [Var] unless the struct is a constant with a constant field. */
        fun toSymbol(): TACSymbol {
            val sym = this.toSymbolInner()
            if (sym.tag is Tag.UserDefined.Struct) {
                throw java.lang.UnsupportedOperationException("Could not flatten incomplete struct application")
            }
            return sym
        }
    }

    @KSerializable
    data class StructConstant(val fieldNameToValue: Map<String, TACExpr>, override val tag: Tag? = null) :
        TACExpr() {

        override fun toString(): String {
//            return "StructConstant(${fieldNameToValue.entries.joinToString(" ")})"
            return toPrintRep { v -> v.toSMTRep() }
        }

        override fun eval(cs: List<BigInteger>) = null

        override fun toPrintRep(cb: (TACSymbol.Var) -> String): String {
            val entriesList =
                fieldNameToValue.entries.map { (fieldName, fieldValue) -> "$fieldName=${fieldValue.toPrintRep(cb)}" }
            return "StructConstant( ${entriesList.joinToString(" ")})"
        }

        override fun toLExpression(
            conv: ToLExpression.Conv,
            meta: MetaMap?,
        ): LExpression {
            throw UnsupportedOperationException("We can't do this yet")
        }
    }

    /**
     * @param length the length of what's being hashed in bytes (for the purposes of injectivity, this works like
     *      another hash argument, since hashes with different length are guaranteed to be different). A similar
     *      caveat applies to this value as applies to the [vc.data.TACCmd.Simple.AssignSimpleSha3Cmd.length] field.
     * @param args the hashed arguments
     * @param hashFamily the original hash function that was used (e.g. whether it was a keccak or a sha3, etc.)
     *
     * Note: It is the caller's responsibility to mask argument values in case they need it
     *  (e.g. to truncate the last argument if `length` is not word-aligned), i.e., there is no later pipeline step
     *  that does this.
     */
    @KSerializable
    data class SimpleHash(
        val length: TACExpr,
        val args: List<TACExpr>,
        val hashFamily: HashFamily,
    ) : TACExpr() {
        val ls: List<TACExpr>
            get() = listOf(length) + args

        override val tag: Tag
            get() = Tag.Bit256

        override fun eval(cs: List<BigInteger>) = null

        /**
         * Note that this function is only called for non-keccak (non "large gaps" hashing) instances of hashes.
         * The other instances of this class have been transformed to TAC builtin functions by [AnnotateSkeyBifs].
         */
        override fun toLExpression(conv: ToLExpression.Conv, meta: MetaMap?): LExpression =
            conv.lxf.applyExp(
                AxiomatizedFunctionSymbol.Hash.SimpleHashN(Tag.Bit256, ls.size, hashFamily),
                ls.map { conv(it, meta) }
            )

        override fun toString(): String {
            return "SimpleHash(${ls.joinToString(" ")}  $hashFamily)"
        }

        override fun toPrintRep(cb: (TACSymbol.Var) -> String): String {
            return "Hash(${ls.joinToString(" ") { it.toPrintRep(cb) }}  $hashFamily)"
        }

        companion object {

            @InternalCVLCompilationAPI
            fun fromByteMapRange(
                hashFamily: HashFamily,
                map: TACSymbol.Var,
                start: TACExpr = zeroExpr,
            ): TACExprWithRequiredCmdsAndDecls<TACCmd.Simple> {
                check(map.tag is Tag.CVLArray)
                return TACUnboundedHashingUtils.fromByteMapRange(
                    hashFamily, map, start, CVLToSimpleCompiler.exposeArrayLengthVar(map).asSym()
                )
            }
        }
    }

    @KSerializable
    data class AnnotationExp<@Treapable T : Serializable>(val o : TACExpr, val annot: Annotation<T>) : TACExpr() {

        constructor(o : TACExpr, k : MetaKey<T>, v : T) : this(o, Annotation(k, v))

        override fun eval(cs: List<BigInteger>) : BigInteger {
            require(cs.size == 1)
            return cs[0]
        }

        override val tag = o.tag

        override fun toPrintRep(cb: (TACSymbol.Var) -> String) =
            "Annotate(${annot.k} := ${annot.v}, ${o.toPrintRepWithParentheses(cb)})"

        override fun toString() =
            "Annotate(${annot.k} := ${annot.v}, $o)"

        override fun toLExpression(conv: ToLExpression.Conv, meta: MetaMap?) =
            o.toLExpression(conv, meta).letIf((o is Select || o is Store) && annot.k == ACCESS_PATHS) { lExp ->
                conv.lxf {
                    val map = buildMultiMap {
                        (annot.v as StorageAnalysisResult.AccessPaths).paths.forEach { path ->
                            val indices = path.indices()?.monadicMap {
                                it.takeIf { it.tag in validAccessPathIndexTags }?.asSym()?.toLExpression(conv)
                            } ?: return@forEach
                            val n = path.toNonIndexed()
                            add(n, indices)
                        }
                    }
                    lExp.addMeta(LEXPRESSION_STORAGE_ACCESSES, LExpressionStorageAccesses(map))
                }
            }

        val mentionedVariables get() = annot.mentionedVariables

        companion object {
            // this is here to prevent the transformation of storage access indices that are actually arrays
            // to the lexpression world. Specifically it does not like the `UserArray` tag.
            // See the test `Test/CVLCompilation/DirectStorageAccess/Default.conf` - `test_bytes_keys`.
            // If we do want to support quantification over bytes keys, we'll need to solve this and some more.
            private val validAccessPathIndexTags = setOf(Tag.Bool, Tag.Bit256, Tag.Int, skeySort)
        }
    }

}

/**
 * Note: This must be in sync with `DefaultAccumulatingTACExprTransformer` in order for `ExpPointer`s to be built
 * correctly.
 */
fun TACExpr.getOperands(): List<TACExpr> =
    when (this) {
        is TACExpr.Apply -> ops
        is TACExpr.BinOp -> listOf(o1, o2)
        is TACExpr.BinRel -> listOf(o1, o2)
        is TACExpr.MultiDimStore -> listOf(base) + locs + listOf(value)
        is TACExpr.LongStore -> listOf(dstMap, dstOffset, srcMap, srcOffset, length)
        is TACExpr.QuantifiedFormula -> listOf(body)
        is TACExpr.MapDefinition -> listOf(definition)
        is TACExpr.Unconstrained -> listOf()
        is TACExpr.Select -> listOf(base, loc)
        is TACExpr.Store -> listOf(base, loc, value)
        is TACExpr.StructAccess -> listOf(struct)
        is TACExpr.StructConstant -> listOf()
        is TACExpr.Sym.Const -> listOf()
        is TACExpr.Sym.Var -> listOf()
        is TACExpr.TernaryExp.Ite -> listOf(i, t, e)
        is TACExpr.TernaryExp.MulMod -> listOf(a, b, n)
        is TACExpr.TernaryExp.AddMod -> listOf(a, b, n)
        is TACExpr.UnaryExp -> listOf(o)
        is TACExpr.Vec -> ls
        is TACExpr.SimpleHash -> ls
        is TACExpr.BinBoolOp -> ls
        is TACExpr.AnnotationExp<*> -> listOf(o)
    }

/**
 * Is this TAC expression equivalent to the symbol [sym]
 */
fun TACExpr?.equivSym(sym: TACSymbol) = (this as? TACExpr.Sym)?.s == sym

fun TACExpr?.equivSym(sym: TACKeyword) = (this as? TACExpr.Sym.Var)?.s?.let {
     sym.isName { name -> name == it.namePrefix } && it.callIndex == TACSymbol.Var.DEFAULT_INDEX && it.tag == sym.type
} == true

fun Collection<TACExpr>.toSymbols(): Set<TACSymbol> = this.mapNotNullTo(mutableSetOf()) {
    (it as? TACExpr.Sym)?.s
}
