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

package analysis.opt.inliner

import analysis.CmdPointer
import analysis.opt.ConstantPropagatorAndSimplifier
import analysis.opt.inliner.Inlinee.Term
import config.Config
import log.*
import org.jetbrains.annotations.TestOnly
import tac.Tag
import utils.ConcurrentCounterMap
import utils.ite
import utils.repeatedMap
import vc.data.CoreTACProgram
import vc.data.TACCmd
import vc.data.TACCmd.Simple.AssigningCmd.AssignExpCmd
import vc.data.TACExpr
import vc.data.tacexprutil.rebuild
import java.math.BigInteger


val logger = Logger(LoggerTypes.GLOBAL_INLINER)

/**
 * Tries to find out as much as possible about byte-map writes, so that byte-map reads maybe be inlined, and so skipped.
 */
class GlobalInliner private constructor(val code: CoreTACProgram) {

    companion object {
        fun inlineAll(code: CoreTACProgram) =
            repeatedMap(
                times = Config.globalInliner.get(),
                init = ConstantPropagatorAndSimplifier(code).rewrite(),
                transform = { GlobalInliner(it).go() }
            )

        @TestOnly
        fun inlineStats(code: CoreTACProgram) =
            ConstantPropagatorAndSimplifier(code).rewrite()
                .let { GlobalInliner(it).apply(GlobalInliner::go).stats }

        val Tag.inlinable get() = this is Tag.Bits
    }

    private val calculator = InliningCalculator(code)
    private val patcher = code.toPatchingProgram()
    private val stats = ConcurrentCounterMap<String>()

    private fun rewriteByteLongCopy(ptr: CmdPointer, cmd: TACCmd.Simple.ByteLongCopy) {
        if ((calculator.rhsVal(ptr, cmd.length) as? Term)?.asConstOrNull == BigInteger.ZERO) {
            stats.plusOne("longCopyZeroLength")
            patcher.delete(ptr)
            return
        }
    }


    private fun rewriteByteLoad(ptr: CmdPointer, cmd: TACCmd.Simple.AssigningCmd.ByteLoad) {
        val t = calculator.lhsVal(ptr) as? Term ?: return
        val lhs = cmd.lhs
        if (t != Term(lhs)) {
            val newE = t.toTACExpr() ?: run {
                stats.plusOne("missedByteLoad")
                return
            }
            stats.plusOne("byteLoad")
            patcher.update(ptr, AssignExpCmd(cmd.lhs, newE, cmd.meta))
        }
    }


    private fun rewriteExpr(ptr: CmdPointer, e: TACExpr): TACExpr {
        if (!e.tagAssumeChecked.inlinable) {
            return e.rebuild { rewriteExpr(ptr, it) }
        }
        val t = calculator.rhsVal(ptr, e)
        if (t is Term) {
            if (t.isConst && e !is TACExpr.Sym.Const) {
                stats.plusOne(ite(e is TACExpr.Select, "selectConst", "constant"))
                return t.toTACExpr()!!
            }
            t.asVarOrNull?.takeIf { it.asSym() != e }?.let {
                stats.plusOne(ite(e is TACExpr.Select, "selectVar", "simpleVar"))
                return t.toTACExpr()!!
            }
            if (e is TACExpr.Select && e.base.tag is Tag.ByteMap) {
                val newE = t.toTACExpr()
                if (newE != null) {
                    stats.plusOne("selectComplex")
                    return newE
                } else {
                    stats.plusOne("missedSelect")
                }
            }
        }
        return e.rebuild { rewriteExpr(ptr, it) }
    }

    private fun rewriteRhs(ptr: CmdPointer, cmd: AssignExpCmd) {
        val rhs = cmd.rhs
        val newRhs = rewriteExpr(ptr, rhs)
        if (newRhs != rhs) {
            patcher.update(ptr, cmd.copy(rhs = newRhs))
        }
    }


    /**
     * Entry point
     */
    private fun go(): CoreTACProgram {
        calculator.go()
        logger.trace {
            calculator.debugPrinter().toString(code, "GlobalInliner")
        }
        code.analysisCache.graph.commands.forEach { (ptr, cmd) ->
            when (cmd) {
                is TACCmd.Simple.AssigningCmd.ByteLoad -> rewriteByteLoad(ptr, cmd)
                is TACCmd.Simple.ByteLongCopy -> rewriteByteLongCopy(ptr, cmd)
                is AssignExpCmd -> rewriteRhs(ptr, cmd)
                else -> Unit
            }
        }
        logger.info {
            stats.toString("${javaClass.simpleName} - ${code.name}")
        }
        return patcher.toCode(code)
    }

}

