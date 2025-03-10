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

package smt.axiomgenerators

import evm.MAX_EVM_INT256
import evm.MAX_EVM_UINT256
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import smt.HashingScheme
import smt.axiomgenerators.fullinstantiation.expnormalizer.ExpNormalizerIA
import smt.solverscript.LExpressionFactory
import smt.solverscript.SmtTheory
import tac.Tag
import vc.data.LExpression
import vc.data.TACBuiltInFunction
import vc.data.TACSymbolTable
import vc.data.asTACExpr
import vc.data.asTACSymbol
import java.math.BigInteger

class ExpNormalizerIATest {
    private val lxf = LExpressionFactory()
    private val expNormalizer = ExpNormalizerIA(lxf, SmtTheory.fromString("UFNIA"), HashingScheme.DefaultInt) { false }

    private fun handleBooleanLexp(lexp: LExpression): Boolean {
        val res = expNormalizer.normalizeRec(lexp)
        if (res == lxf.TRUE) {
            return true
        } else if (res == lxf.FALSE) {
            return false
        } else {
            Assertions.fail<Unit>("Expected normalized overflow predicate expression to be a boolean, got $res")
            return false // unreachable really, so it's ok
        }
    }


    private fun mulOverflowPredicate(o1: BigInteger, o2: BigInteger): Boolean {
        val lexp = TACBuiltInFunction.NoMulOverflowCheck(Tag.Bit256).getLExpressionBuilder(
            lxf,
            TACSymbolTable.empty(),
            null
        )(listOf(o1.asTACSymbol().asSym(), o2.asTACSymbol().asSym()))
        return handleBooleanLexp(lexp)
    }

    private fun smulOverflowPredicate(o1: BigInteger, o2: BigInteger): Boolean {
        val lexp = TACBuiltInFunction.NoSMulOverAndUnderflowCheck(Tag.Bit256).getLExpressionBuilder(
            lxf,
            TACSymbolTable.empty(),
            null
        )(listOf(o1.asTACExpr(Tag.Bit256), o2.asTACExpr(Tag.Bit256)))
        return handleBooleanLexp(lexp)
    }

    private fun compareToMod(o1: BigInteger, o2: BigInteger): Boolean {
        val lexp = lxf {
            (litInt(o1) * litInt(o2)) eq ((litInt(o1) * litInt(o2)) % TwoTo256)
        }
        return handleBooleanLexp(lexp)
    }

    private fun checkMulResIsSignedInt256(o1: BigInteger, o2: BigInteger): Boolean {
        val lexp = lxf {
            litInt(o1) * litInt(o2)
        }
        val res = expNormalizer.normalizeRec(lexp)
        return if (res is LExpression.Literal) {
            res.i.let {
                // 2s complement for bitwidth n is -2^(n-1) to 2^(n-1)-1
                -BigInteger.TWO.pow(256 - 1) <= it &&
                        it <= BigInteger.TWO.pow(256 - 1) - BigInteger.ONE
            }
        } else {
            Assertions.fail<Unit>("Expected a big int literal, got $res")
            false // unreachable anyway
        }
    }


    private fun testOneMul(o1: BigInteger, o2: BigInteger) {
        Assertions.assertEquals(mulOverflowPredicate(o1, o2), compareToMod(o1, o2))
    }

    private fun testOneSMul(o1: BigInteger, o2: BigInteger) {
        Assertions.assertEquals(smulOverflowPredicate(o1, o2), checkMulResIsSignedInt256(o1, o2))
    }

    @Test
    fun testSMulOverflow() {
        // if mod 2^256 has no effect on the result then the overflow predicate should be true
        //testOneMul(BigInteger.ONE, BigInteger.ONE)
        testOneSMul(BigInteger.ONE, BigInteger.ONE)

        testOneMul(BigInteger.TWO, MAX_EVM_INT256)
        testOneSMul(BigInteger.TWO, MAX_EVM_INT256)

        testOneMul(BigInteger.TEN, MAX_EVM_INT256)
        testOneSMul(BigInteger.TEN, MAX_EVM_INT256)

        testOneMul(BigInteger.ONE, MAX_EVM_INT256)
        testOneSMul(BigInteger.ONE, MAX_EVM_INT256)

        testOneMul(BigInteger.ONE, MAX_EVM_UINT256)
        // testOneSMul(BigInteger.ONE, MAX_EVM_UINT256) // 2nd arg is not a valid signed int256

        testOneMul(BigInteger.ZERO, MAX_EVM_UINT256)
        testOneSMul(BigInteger.ZERO, MAX_EVM_UINT256)

        testOneMul(MAX_EVM_INT256, MAX_EVM_INT256)
        testOneSMul(MAX_EVM_INT256, MAX_EVM_INT256)
    }

}
