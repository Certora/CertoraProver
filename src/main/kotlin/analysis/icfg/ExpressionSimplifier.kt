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

package analysis.icfg

import algorithms.dominates
import analysis.*
import analysis.dataflow.IDefAnalysis
import analysis.opt.ConstantPropagatorAndSimplifier.Companion.defaultTag
import com.certora.collect.*
import config.Config
import datastructures.stdcollections.*
import kotlinx.coroutines.yield
import log.*
import parallel.coroutines.onThisThread
import tac.Tag
import tac.Tag.Bit256
import tac.commonSuperTag
import utils.*
import vc.data.*
import vc.data.tacexprutil.asConstOrNull
import vc.data.tacexprutil.rebuild
import java.math.BigInteger

private val logger = Logger(LoggerTypes.CONSTANT_PROPAGATION)

private val simplificationDepth get() = Config.SimplificationDepth.get()

/**
 * Gets a graph [g] and able to simplify expressions to constants
 * This class will exclude tacM0x40 from the computations (since it can interfere with other parts of the pipeline).
 * [preservedNames] represents variables' names that should not be optimized out.
 * [preservedVars] represents variables that should not be optimized out
 * [defaultTo256Bits] is a hack to support the use of this class when [g] is not type-checked. This happens in early
 *   stages of the EVM pipeline. Ideally this shouldn't happen there but it does. If this is on, then any
 *   [TACExpr] which we want to call `eval` on, and that `eval` needs to know the bit-width of the expression, yet
 *   the expression has a `null` tag, will be evaluated as is it has the [Tag.Bit256] tag.
 */
open class ExpressionSimplifier(
    val g: TACCommandGraph,
    customDefAnalysis: IDefAnalysis = g.cache.def,
    private val preservedNames: Set<String> = setOf(TACKeyword.MEM64.getName()),
    private val preservedVars: Set<TACSymbol.Var> = emptySet(),
    private val trySimplifyConfluence: Boolean = false,
    private val defaultTo256Bits: Boolean = false
) {
    val nonTrivialDefAnalysis = object : NonTrivialDefAnalysis(g, customDefAnalysis) {
        override fun transition(from: CmdPointer, to: CmdPointer): Boolean {
            return domination.dominates(to, from) // to should dominate from
        }

        override val isSingleDefSites: Boolean = true
    }

    val standardNontrivialDef = NonTrivialDefAnalysis(g, customDefAnalysis)

    private fun TACExpr.defaultEvalAsExpr(args: List<BigInteger>): TACExpr.Sym.Const? =
        letIf(defaultTo256Bits) {
            fun opsTag() = commonSuperTag(getOperands().map { it.tag ?: Bit256 }) as Tag.Bits
            when (this) {
                // these needs their operands to have tags if we want to call eval. But instead of calling eval,
                // we do the evaluation here.
                is TACExpr.BinRel.Sge -> return opsTag().sge(args[0], args[1]).asTACExpr(Tag.Bool)
                is TACExpr.BinRel.Sgt -> return opsTag().sgt(args[0], args[1]).asTACExpr(Tag.Bool)
                is TACExpr.BinRel.Sle -> return opsTag().sle(args[0], args[1]).asTACExpr(Tag.Bool)
                is TACExpr.BinRel.Slt -> return opsTag().slt(args[0], args[1]).asTACExpr(Tag.Bool)

                else ->
                    if (tag == null) {
                        when (this) {
                            // the eval of the following need the expression to have a tag, so we temporarily
                            // rewrite with them with the default tag.
                            is TACExpr.BinOp.Exponent -> copy(tag = defaultTag)
                            is TACExpr.BinOp.SDiv -> copy(tag = defaultTag)
                            is TACExpr.BinOp.SMod -> copy(tag = defaultTag)
                            is TACExpr.BinOp.ShiftLeft -> copy(tag = defaultTag)
                            is TACExpr.BinOp.ShiftRightArithmetical -> copy(tag = defaultTag)
                            is TACExpr.BinOp.SignExtend -> copy(tag = defaultTag)
                            is TACExpr.BinOp.Sub -> copy(tag = defaultTag)
                            is TACExpr.UnaryExp.BWNot -> copy(tag = defaultTag)
                            is TACExpr.Vec.Add -> copy(tag = defaultTag as Tag.Bits)
                            is TACExpr.Vec.Mul -> copy(tag = defaultTag as Tag.Bits)
                            else -> this
                        }
                    } else {
                        this
                    }
            }
        }.eval(args)?.asTACExpr(tag ?: defaultTag)

    private fun TACExpr.defaultEvalAsExpr(vararg args : BigInteger) =
        defaultEvalAsExpr(args.toList())

    val TACExpr.tagOrDefault
        get() = tag ?: defaultTag.takeIf { defaultTo256Bits }!!

    protected class SimplificationContext(
        val inPrestate: Boolean,
        val stopAt: ((TACSymbol.Var) -> Boolean)?,
        val visiting: TreapSet<Pair<CmdPointer, TACExpr>> = treapSetOf(),
        val depth: Int = 0,
        val simplified: MutableMap<Pair<TACExpr, CmdPointer>, TACExpr> = mutableMapOf()
    ) {
        inline fun <T> withRecursion(ptr: CmdPointer, e: TACExpr, action: context(SimplificationContext) () -> T) =
            action(SimplificationContext(inPrestate, stopAt, visiting + (ptr to e), depth + 1, simplified))

        inline fun <T> inPrestate(action: context(SimplificationContext) () -> T) =
            action(SimplificationContext(true, stopAt, visiting, depth, simplified))
    }

    private fun isPreserved(v: TACSymbol.Var) = v in preservedVars || v.namePrefix in preservedNames

    context(SimplificationContext)
    protected open suspend fun simplifyBinExp(
        e: TACExpr.BinExp,
        ptr: CmdPointer,
    ): TACExpr {
        val (o1, o2) = inPrestate { simplify(e.o1, ptr) to simplify(e.o2, ptr) }
        val (o1c, o2c) = o1.getAsConst() to o2.getAsConst()

        return if (o1c != null && o2c != null) {
            (e as TACExpr).defaultEvalAsExpr(o1c, o2c)!!
        } else {
            (e as TACExpr).rebuild(o1, o2)
        }
    }

    // requires special handling because loops try to reduce the power, and if they are unrolled we may get invalid constants
    context(SimplificationContext)
    protected open suspend fun simplifyExponentExpr(
        e: TACExpr.BinOp.Exponent,
        ptr: CmdPointer,
    ): TACExpr {
        val (base, pow) = inPrestate { simplify(e.o1, ptr) to simplify(e.o2, ptr) }
        val powAsConst = pow.getAsConst()
        val baseAsConst = base.getAsConst()
        return if (baseAsConst != null && powAsConst != null) {
            if (powAsConst.toInt().toBigInteger() != powAsConst || powAsConst < BigInteger.ZERO) {
                // got invalid power, keeping the crazy exp (it's probably unreachable anyway)
                logger.warn { "Invalid power $powAsConst in $e in $ptr, from $base and $pow" }
                e.rebuild(base, pow)
            } else {
                e.defaultEvalAsExpr(baseAsConst, powAsConst)!!
            }
        } else {
            e.rebuild(base, pow)
        }
    }

    context(SimplificationContext)
    protected open suspend fun simplifySubtractExpr(
        sub: TACExpr.BinOp.Sub,
        ptr: CmdPointer,
    ): TACExpr {
        val o1 = sub.o1AsNullableTACSymbol()
        val o2 = sub.o2AsNullableTACSymbol()
        if (o1 == null || o2 == null) {
            return sub
        }

        val simplifiedO1AsConst = inPrestate { simplify(sub.o1, ptr).getAsConst() }
        val simplifiedO2AsConst = inPrestate { simplify(sub.o2, ptr).getAsConst() }
        if (simplifiedO1AsConst != null && simplifiedO2AsConst != null) {
            return sub.defaultEvalAsExpr(simplifiedO1AsConst, simplifiedO2AsConst)!!
        }

        // o1 will be c1 + (c2 + ... (cn + V)) and V will be m0x40. o2 will be m0x40. let's detect that...
        // check o2
        val expectM0x40 = if (o2 is TACSymbol.Var) {
            nonTrivialDefAnalysis.getDefAsExprIgnoreM0x40<TACExpr.Sym>(
                o2,
                ptr,
                ptr.block.getCallId(),
                stopAt
            )?.exp
        } else {
            null
        }
        // check o1
        val (expectAdd, expectAddPtr) = if (o1 is TACSymbol.Var) {
            val def = nonTrivialDefAnalysis.getDefAsExpr<TACExpr>(
                o1,
                ptr,
                stopAt
            )
            when (def?.exp) {
                is TACExpr.Sym -> {
                    // short-circuit here - got V-V == 0
                    if (def.exp == expectM0x40) {
                        return 0.asTACExpr(sub.tagOrDefault)
                    }
                    null to null
                }

                is TACExpr.Vec.Add -> {
                    def.exp as TACExpr.Vec.Add to def.ptr
                }

                else -> null to null
            }
        } else {
            null to null
        }

        if (expectAddPtr == null || expectAdd == null) {
            return sub
        }
        // right now expectAdd is c1 + (c2 + .... (cn + V))
        var constAggregator = BigInteger.ZERO
        var add : TACExpr? = expectAdd
        var addPtr : CmdPointer? = expectAddPtr
        // there is an issue here - if expectedM0x40 is an expression and not a symbol, especially if it's an AddExp,
        // we may miss an opportunity to optimize. This can be a later mop-up
        while (add is TACExpr.Vec.Add && addPtr != null) {
            if (add.ls.size != 2) {
                return sub
            }
            // here we assume c1 is const and c2 is a variable. but it could also be the other way around
            val res = if (add.o1.getAsConst() != null && add.o2AsNullableTACSymbol() is TACSymbol.Var) {
                val ck = add.o1.getAsConst()!!
                constAggregator = constAggregator.add(ck)
                val res = nonTrivialDefAnalysis.getDefAsExprIgnoreM0x40<TACExpr>(add.o2AsNullableTACSymbol()!! as TACSymbol.Var, addPtr, ptr.block.getCallId(), stopAt)
                res
            } else if (add.o2.getAsConst() != null && add.o1AsNullableTACSymbol() is TACSymbol.Var) {
                val ck = add.o2.getAsConst()!!
                constAggregator = constAggregator.add(ck)
                val res =  nonTrivialDefAnalysis.getDefAsExprIgnoreM0x40<TACExpr>(add.o1AsNullableTACSymbol()!! as TACSymbol.Var, addPtr, ptr.block.getCallId(), stopAt)
                res
            } else {
                return sub
            }
            if (res == null) {
                return sub
            }
            add = res.exp
            addPtr = res.ptr
        }
        val finalSym = add

        if (finalSym == expectM0x40) {
            return constAggregator.asTACExpr(sub.tagOrDefault)
        }

        val expectAddSecondArgAsM0x40 =
            if (finalSym is TACExpr.Sym && finalSym.s is TACSymbol.Var) {
                nonTrivialDefAnalysis.getDefAsExprIgnoreM0x40<TACExpr.Sym>(
                    finalSym.s as TACSymbol.Var,
                    expectAddPtr,
                    ptr.block.getCallId(),
                    stopAt
                )?.exp
            } else if (finalSym is TACExpr.Sym && finalSym.s is TACSymbol.Const) {
                finalSym
            } else {
                null
            }

        if (expectAddSecondArgAsM0x40 != null && expectM0x40 != null && expectAddSecondArgAsM0x40 == expectM0x40) {
            return constAggregator.asTACExpr(sub.tagOrDefault)
        } else {
            return sub
        }
    }

    context(SimplificationContext)
    private suspend fun TACExpr.simplifyAndRebuild(ptr: CmdPointer): TACExpr =
        getOperands().map { simplify(it, ptr) }.let { simplified ->
            simplified.monadicMap { it.getAsConst() }
                ?.let { defaultEvalAsExpr(it) }
                ?: rebuild(simplified)
        }

    context(SimplificationContext)
    protected open fun simplifyStructAccess(
        e: TACExpr.StructAccess,
        ptr: CmdPointer,
    ): TACExpr {
        // if resolved to non-const, don't simplify
        return when (e.struct) {
            is TACExpr.StructConstant-> {
                if (e.fieldName in e.struct.fieldNameToValue) {
                    e.struct.fieldNameToValue[e.fieldName]!!.let { rhs ->
                        if (rhs is TACExpr.Sym.Const) {
                            val tag = (e.struct.tagAssumeChecked as Tag.UserDefined.Struct).getField(e.fieldName)!!.type
                            rhs.s.copy(tag = tag).asSym()
                        } else {
                            e
                        }
                    }
                } else {
                    e
                }
            }
            is TACExpr.Sym.Var -> {
                val c = nonTrivialDefAnalysis.getDefAsExpr<TACExpr>(e.struct.s, ptr, stopAt)?.exp

                if (c is TACExpr.StructConstant) {
                    (c.fieldNameToValue[e.fieldName]?: error("field name ${e.fieldName} does not exist in $c"))
                        .also {
                            Logger.regression {
                                val name =
                                    e.struct.s.meta[TACMeta.CVL_DISPLAY_NAME] ?: e.struct.s.namePrefix
                                "Simplified struct access ${e.copy(struct = e.struct.copy(s = e.struct.s.copy(namePrefix = name)))} to $it"
                            }
                        }
                } else {
                    e
                }
            }
            else -> e
        }
    }

    context(SimplificationContext)
    protected open suspend fun simplifyIte(
        ite: TACExpr.TernaryExp.Ite,
        ptr: CmdPointer,
    ): TACExpr {
        // Note that while we _could_ simplify both branches even if
        // the condition does not simplify to a constant,
        // at the top level we're going to discard this result if [ite]
        // can't be simplified to a constant anyway.
        val cond = simplify(ite.i, ptr).evalAsConst() ?: return ite

        return if (cond == BigInteger.ZERO) {
            simplify(ite.e, ptr)
        } else {
            simplify(ite.t, ptr)
        }
    }

    context(SimplificationContext)
    protected open suspend fun simplifyApply(
        e: TACExpr.Apply,
        ptr: CmdPointer,
    ): TACExpr {
        val simplifiedOps = e.ops.map { simplify(it, ptr) }
        return simplifiedOps.monadicMap { it.asConstOrNull }
            ?.let { e.defaultEvalAsExpr(it) }
            ?: e.rebuild(simplifiedOps)
    }

    context(SimplificationContext)
    protected suspend fun simplify(
        e: TACExpr,
        ptr: CmdPointer,
    ): TACExpr {
        val oldExpressionStack = visiting
        withRecursion(ptr, e) {
            if (visiting === oldExpressionStack) {
                Logger.alwaysWarn("Expression simplification cycle at $ptr ($e)")
                return e
            }
            if (depth == simplificationDepth) {
                Logger.alwaysWarn("Reached maximum expression simplification depth $depth")
                return e
            }
            if (depth > 0 && depth % 100 == 0) {
                logger.info { "Folding the stack at simplification depth $depth" }
                yield() // avoid stack overflow
            }
            return simplified.getOrPut(e to ptr) {
                when (e) {
                    is TACExpr.Sym -> simplify(e.s, ptr)
                    is TACExpr.Vec -> e.simplifyAndRebuild(ptr)
                    is TACExpr.UnaryExp -> e.simplifyAndRebuild(ptr)
                    is TACExpr.BinBoolOp -> e.simplifyAndRebuild(ptr)
                    is TACExpr.BinOp.Sub -> simplifySubtractExpr(e, ptr)
                    is TACExpr.BinOp.Exponent -> simplifyExponentExpr(e, ptr)
                    is TACExpr.BinExp -> simplifyBinExp(e, ptr)
                    is TACExpr.StructAccess -> simplifyStructAccess(e, ptr)
                    is TACExpr.Apply -> simplifyApply(e, ptr)
                    is TACExpr.TernaryExp.Ite -> simplifyIte(e, ptr)
                    else -> e
                }
            }
        }
    }

    fun simplify(e: TACExpr, ptr: CmdPointer, inPrestate: Boolean, stopAt: ((TACSymbol.Var) -> Boolean)? = null) =
        recursionGuard(this) { // All recusion should be done via coroutine calls
            with (SimplificationContext(inPrestate, stopAt)) {
                onThisThread { simplify(e, ptr) }
            }
        }

    fun simplify(v: TACSymbol, ptr: CmdPointer, inPrestate: Boolean, stopAt: ((TACSymbol.Var) -> Boolean)? = null) =
        recursionGuard(this) { // All recusion should be done via coroutine calls
            with (SimplificationContext(inPrestate, stopAt)) {
                onThisThread { simplify(v, ptr) }
            }
        }

    private val domination = g.cache.domination

    // by default, we do not want to handle the codesize, as we rely on it being symbolic in various places.
    open fun handleCodesize(v: TACSymbol, sz: Int): TACExpr {
        unused(sz)
        return v.asSym()
    }

    context(SimplificationContext)
    private suspend fun simplify(v: TACSymbol, ptr: CmdPointer): TACExpr {
        if (v is TACSymbol.Const) {
            return v.asSym()
        }
        check(v is TACSymbol.Var) { "impossible to get $v non-Var here" }
        if (isPreserved(v) || stopAt?.let { it(v) } == true) {
            return v.asSym()
        } else if (v.meta.containsKey(TACMeta.CODESIZE_VALUE)) {
            return handleCodesize(v, v.meta[TACMeta.CODESIZE_VALUE]!!)
        }
        val definingCmd =
            // either the current ptr defines v and are not looking in the prestate, so we can take current ptr
            if (!inPrestate && g.elab(ptr).cmd.getLhs() == v) {
                g.elab(ptr)
            } else {
                // or either the current one does not match, or we have to look backwards
                nonTrivialDefAnalysis.getDefCmd<TACCmd.Simple.AssigningCmd>(v, ptr, stopAt) ?.wrapped
            }

        // defining command must dominate our current pointer
        if (definingCmd == null || !domination.dominates(definingCmd.ptr, ptr)) {
            if(!trySimplifyConfluence) {
                return v.asSym()
            }
            val defs = standardNontrivialDef.nontrivialDef(v, ptr)
            val defForm = defs.monadicMap {
                g.elab(it).maybeNarrow<TACCmd.Simple.AssigningCmd.AssignExpCmd>()?.cmd?.rhs
            }?.uniqueOrNull()
            return if(defForm == null) {
                v.asSym()
            } else {
                defs.map { prevPointer ->
                    simplify(
                        defForm, prevPointer
                    )
                }.uniqueOrNull()?.takeIf { it.getAsConst() != null } ?: v.asSym()
            }
        }
        val (ptr_, cmd) = definingCmd
        return when (cmd) {
            is TACCmd.Simple.AssigningCmd.AssignExpCmd -> {
                // we must not resolve tacM0x40
                if (isPreserved(cmd.lhs)) {
                    v.asSym()
                } else {
                    inPrestate {
                        simplify(cmd.rhs, ptr_)
                            // the simplified value must be a constant
                            .takeIf { it.getAsConst() != null } ?: v.asSym()
                    }
                }
            }

            is TACCmd.Simple.AssigningCmd -> interp(definingCmd.narrow()) ?: v.asSym()
            else -> v.asSym()
        }
    }

    open protected fun interp(c: LTACCmdView<TACCmd.Simple.AssigningCmd>) : TACExpr? {
        return null
    }
}
