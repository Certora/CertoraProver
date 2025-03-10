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

package analysis.loop

import datastructures.stdcollections.*
import analysis.smtblaster.SmtScriptBuilder
import tac.Tag
import vc.data.TACExpr
import vc.data.TACSymbol
import vc.data.asTACSymbol
import vc.data.tacexprutil.DefaultTACExprTransformer
import java.math.BigInteger

open class AbstractArraySummaryExtractor {

    enum class LoopRole(val relational: Boolean = false) {
        ELEM_START,
        OBJ_POINTER,
        LENGTH, // the logical length
        ZERO,
        END,
        ELEM_LENGTH, // the physical representation in bytes
        CORRELATED_ELEM_START(true),
        CORRELATED_ELEM_END(true),
        CONSTANT,
    }

    sealed class Interpolation {
        object Identity : Interpolation()
        data class Constant(val sym: TACExpr.Sym) : Interpolation()
        data class Linear(val n: BigInteger) : Interpolation()
        object MonotoneTransformation : Interpolation()
    }

    protected open fun isConstant(x: TACSymbol.Var) : BigInteger? {
        return null
    }

    protected fun instantiateExpr(
        expr: TACExpr,
        inst: (TACSymbol.Var) -> TACExpr
    ): TACExpr {
        return object : DefaultTACExprTransformer() {
            override fun transform(exp: TACExpr): TACExpr {
                return if (exp is TACExpr.Sym.Var) {
                    inst(exp.s)
                } else {
                    super.transform(exp)
                }
            }
        }.transform(expr)
    }

    protected fun <R> axiomatizeFunctions(
        builder: SmtScriptBuilder<*, R>,
        c: Iterable<Pair<String, TACExpr>>,
        translator: (TACExpr) -> R?
    ) {
        for ((it, start) in c) {
            val init = translator(start) ?: continue
            builder.declareFun(it, 2)
            builder.assert {
                eq(
                    apply(
                        it, listOf(
                            init,
                            const(0)
                        )
                    ),
                    init
                )
            }
            builder.assert {
                forall { a, b ->
                    le(a, b) implies le(
                        apply(
                            it, listOf(init, a)
                        ),
                        apply(
                            it, listOf(init, b)
                        )
                    )
                }
            }
        }
    }

    companion object {
        protected const val SIZE_NAME = "SIZE"
        @JvmStatic
        protected val scaleName = "CORRELATED_SIZE"
        @JvmStatic
        protected val offsetName = "PTR_OFFSET_AMOUNT"
        fun <S, R> addAxioms(builder: SmtScriptBuilder<S, R>, sz: BigInteger, correlatedSize: BigInteger? = null, elemStartOffset: BigInteger) {
            builder.declare(LoopRole.ELEM_START.name)
            builder.declare(LoopRole.END.name)
            builder.declare(LoopRole.OBJ_POINTER.name)
            builder.declare(LoopRole.LENGTH.name)
            builder.declare(LoopRole.ELEM_LENGTH.name)
            builder.declare(LoopRole.CORRELATED_ELEM_END.name)
            builder.declare(LoopRole.CORRELATED_ELEM_START.name)
            if(correlatedSize == null) {
                builder.declare(scaleName)
            } else {
                builder.define(scaleName) {
                    const(correlatedSize)
                }
            }

            builder.define(offsetName) {
                const(elemStartOffset)
            }

            builder.define(SIZE_NAME) {
                const(sz)
            }

            builder.define(LoopRole.ZERO.name) { const(0) }

            builder.assert {
                ge(builder.toIdent(LoopRole.LENGTH.name), const(0))
            }

            builder.assert {
                le(builder.toIdent(LoopRole.LENGTH.name), const(BigInteger.TWO.pow(64) - BigInteger.ONE))
            }

            builder.assert {
                ge(builder.toIdent(LoopRole.OBJ_POINTER.name), const(0))
            }

            builder.assert {
                eq(
                    toIdent(LoopRole.ELEM_START.name),
                    plus(
                        toIdent(LoopRole.OBJ_POINTER.name),
                        const(elemStartOffset)
                    )
                )
            }
            if (sz == BigInteger.ONE) {
                builder.assert {
                    eq(
                        toIdent(LoopRole.END.name),
                        plus(
                            toIdent(LoopRole.ELEM_START.name),
                            toIdent(LoopRole.LENGTH.name)
                        )
                    )
                }

            } else {
                builder.assert {
                    eq(
                        toIdent(LoopRole.END.name),
                        plus(
                            toIdent(LoopRole.ELEM_START.name),
                            mult(
                                toIdent(LoopRole.LENGTH.name),
                                toIdent(SIZE_NAME)
                            )
                        )
                    )
                }
            }
            builder.assert {
                eq(
                    minus(
                        toIdent(LoopRole.CORRELATED_ELEM_END.name),
                        toIdent(LoopRole.CORRELATED_ELEM_START.name)
                    ),
                    mult(toIdent(scaleName), toIdent(LoopRole.LENGTH.name))
                )
            }
            if (sz == BigInteger.ONE) {
                builder.assert {
                    eq(
                        toIdent(LoopRole.ELEM_LENGTH.name),
                        toIdent(LoopRole.LENGTH.name)
                    )
                }
            } else {
                builder.assert {
                    eq(
                        toIdent(LoopRole.ELEM_LENGTH.name),
                        mult(
                            toIdent(LoopRole.LENGTH.name),
                            toIdent(SIZE_NAME)
                        )
                    )
                }
            }
            builder.assert {
                eq(
                    toIdent(LoopRole.END.name),
                    plus(
                        toIdent(LoopRole.ELEM_START.name),
                        toIdent(LoopRole.ELEM_LENGTH.name)
                    )
                )
            }
        }

        /**
         * XXX(jtoman): I *swear* I have written something like this
         */
        private fun TACExpr.Vec.Add.simplify() : TACExpr.Vec.Add {
            if(this.ls.size != 2) {
                return this
            } else {
                val (c, added) = if(this.o1 is TACExpr.Sym.Const && this.o2 is TACExpr.Vec.Add) {
                    this.o1 to (this.o2 as TACExpr.Vec.Add).simplify()
                } else if(this.o1 is TACExpr.Vec.Add && this.o2 is TACExpr.Sym.Const) {
                    this.o2 to (this.o1 as TACExpr.Vec.Add).simplify()
                } else {
                    return this
                }
                if(!added.operandsAreSyms()) {
                    return this
                }
                val o1 = added.o1AsTACSymbol()
                val o2 = added.o2AsTACSymbol()
                return if(o1 is TACSymbol.Const) {
                    TACExpr.Vec.Add(
                        o2.asSym(), (o1.value + c.getAsConst()!!).asTACSymbol().asSym()
                    )
                } else if(o2 is TACSymbol.Const) {
                    TACExpr.Vec.Add(
                        o1.asSym(), (o2.value + c.getAsConst()!!).asTACSymbol().asSym()
                    )
                } else {
                    this
                }
            }
        }

        fun interpolateLoop(summ: LoopSummarization.LoopIterationSummary): Map<TACSymbol.Var, Interpolation>? =
            interpolateLoopVars(summ) { true }

        fun interpolateLoopVars(
            summ: LoopSummarization.LoopIterationSummary,
            filter: (TACSymbol.Var) -> Boolean
        ): Map<TACSymbol.Var, Interpolation>? {
            val interp = mutableMapOf<TACSymbol.Var, Interpolation>()
            outer@for((k, v) in summ.iterationEffect.filterKeys(filter)) {
                when (v) {
                    is TACExpr.Vec.Add -> {
                        val vSimple = v.simplify()
                        fun d(i: TACExpr.Vec.Add) : Boolean {
                            return LoopSummarization.isMonotoneTransformerFor(
                                expr = i
                            ) { it == k }
                        }
                        if(vSimple.ls.size != 2) {
                            if(d(vSimple)) {
                                interp[k] = Interpolation.MonotoneTransformation
                            }
                            continue@outer
                        }
                        val extracted = extractValue(vSimple.ls, k)
                        if(extracted == null && d(vSimple)) {
                            interp[k] = Interpolation.MonotoneTransformation
                        } else if(extracted != null) {
                            interp[k] = Interpolation.Linear(extracted)
                        }
                    }
                    is TACExpr.Vec.IntAdd -> {
                        if(v.ls.size != 2) {
                            continue@outer
                        }
                        val subExprs = v.ls
                        interp[k] = Interpolation.Linear(extractValue(subExprs, k) ?: continue@outer)
                    }
                    is TACExpr.BinOp.Sub -> {
                        interp[k] = Interpolation.Linear(extractValue(listOf(v.o1, v.o2), k)?.negate() ?: continue@outer)
                    }
                    is TACExpr.Sym -> {
                        if(v.s is TACSymbol.Var) {
                            if(v.s != k) {
                                continue@outer
                            }
                            interp[k] = Interpolation.Identity
                        } else {
                            interp[k] = Interpolation.Constant(v)
                        }
                    }
                    is TACExpr.Apply -> {
                        if(v.f == LoopSummarization.monotoneSummary && v.ops.all {
                                LoopSummarization.isMonotoneTransformerFor(expr = it) {
                                    it == k
                                }
                            }) {
                            interp[k] = Interpolation.MonotoneTransformation
                        } else {
                            continue@outer
                        }
                    }
                    is TACExpr.Vec.Mul -> {
                        if(LoopSummarization.isMonotoneTransformerFor(v) { it == k }) {
                            interp[k] = Interpolation.MonotoneTransformation
                            continue@outer
                        } else {
                            return null
                        }
                    }
                    else ->
                        return null
                }
            }
            return interp
        }

        private fun extractValue(subExprs: List<TACExpr>, preLoopVersion: TACSymbol.Var): BigInteger? {
            val operands = subExprs.filter {
                it !is TACExpr.Sym || it.s != preLoopVersion
            }
            if (operands.size != 1) {
                return null
            }
            val op = operands.first()
            if (op !is TACExpr.Sym || op !is TACExpr.Sym.Const) {
                // temporary solution until work on abi decoding is done
                return op.evalAsConst()
            }
            return op.s.value
        }
    }


    protected fun instantiateExpressionAt(
        which: TACSymbol.Var,
        interp: Interpolation,
        loopIteration: TACExpr,
        functionSymbols: MutableMap<TACSymbol.Var, Pair<String, TACExpr>>,
        start: TACExpr
    ): TACExpr =
        when (interp) {
            is Interpolation.Linear -> {
                val c = interp.n
                if (c >= BigInteger.ZERO) {
                    TACExpr.Vec.Add(
                        listOf(
                            start,
                            TACExpr.Vec.Mul(
                                listOf(
                                    loopIteration,
                                    TACExpr.Sym(TACSymbol.lift(c))
                                )
                            )
                        )
                    )
                } else {
                    TACExpr.BinOp.Sub(
                        start,
                        TACExpr.Vec.Mul(
                            listOf(
                                loopIteration,
                                TACExpr.Sym(TACSymbol.lift(c.abs()))
                            )
                        )
                    )
                }
            }
            is Interpolation.Identity -> {
                start
            }
            is Interpolation.Constant -> interp.sym
            Interpolation.MonotoneTransformation -> {
                val sym = functionSymbols.computeIfAbsent(which) {
                    "monotone-function-${counter++}" to start
                }.first
                TACExpr.Apply(
                    f = TACExpr.TACFunctionSym.Adhoc(sym),
                    ops = listOf(start, loopIteration),
                    tag = Tag.Bit256
                )
            }
        }

    protected fun generateLoopIteration(
        interp: Map<TACSymbol.Var, Interpolation>,
        loopIteration: TACSymbol.Var,
        functionSymbols: MutableMap<TACSymbol.Var, Pair<String, TACExpr>>,
        initValuation: (TACSymbol.Var) -> TACExpr
    ): Map<TACSymbol.Var, TACExpr> {
        return interp.mapValues { (k, v) ->
            val start = initValuation(k)
            instantiateExpressionAt(
                k,
                loopIteration = TACExpr.Sym(loopIteration),
                interp = v,
                functionSymbols = functionSymbols,
                start = start
            )
        }
    }

    private var counter = 0
}
