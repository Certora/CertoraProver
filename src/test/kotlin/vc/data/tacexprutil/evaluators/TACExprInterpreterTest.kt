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

package vc.data.tacexprutil.evaluators

import com.certora.collect.*
import datastructures.stdcollections.*
import evm.MAX_EVM_UINT256
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import vc.data.TACBuilderAuxiliaries
import vc.data.TACExpr
import vc.data.TACSymbol
import vc.data.state.EmptyTACState
import vc.data.state.SimpleTACState
import vc.data.state.TACValue
import java.math.BigInteger

class TACExprInterpreterTest : TACBuilderAuxiliaries() {

    private val x1 = intVar("x1")
    private val x2 = intVar("x2")
    private val x3 = intVar("x3")

    private val bv1 = bv256Var("bv1")
    private val bv2 = bv256Var("bv2")
    private val bv3 = bv256Var("bv3")

    private val bTrue = boolVar("b1")
    private val bFalse = boolVar("b2")

    private val bm1 = mkBmVar("bm1")

    private val wm1 = wordMapVar("wm1")

    private val Int.asTACVal get() = TACValue.valueOf(this)
    private val Boolean.asTACVal get() = TACValue.valueOf(this)
    private val String.asTACValBinary get() = TACValue.valueOf(BigInteger(this, 2))
    private val String.asTACValHex get() = TACValue.valueOf(BigInteger(this, 16))
    private val String.asTACExpBinary get() = TACSymbol.Const(BigInteger(this, 2)).asSym()

    private val TACValue.toBoolTACVal get() = when (this) {
        is TACValue.PrimitiveValue.Integer -> TACValue.valueOf(this.value != BigInteger.ZERO)
        is TACValue.PrimitiveValue.Bool -> this
        else -> error("can't convert $this to a Bool TACValue")
    }

    private val state1 = SimpleTACState(
        treapMapOf(
            x1 to TACValue.valueOf(1),
            x2 to TACValue.valueOf(2),
            x3 to TACValue.valueOf(3),

            bv1 to TACValue.valueOf(1),
            bv2 to TACValue.valueOf(2),
            bv3 to TACValue.valueOf(3),

            bTrue to TACValue.valueOf(true),
            bFalse to TACValue.valueOf(false),

            // using word-spacing so the stores don't interfere (in order to do a simple case for the moment)
            bm1 to TACValue.MappingValue.KVMapping.ByteMap()
                .store(0.asTACVal, 0.asTACVal)
                .store(32.asTACVal, 1.asTACVal)
                .store(64.asTACVal, 2.asTACVal),

            // defining this as the very partial identity
            wm1 to TACValue.MappingValue.KVMapping.WordMap()
                .store(0.asTACVal, 0.asTACVal)
                .store(1.asTACVal, 1.asTACVal)
                .store(2.asTACVal, 2.asTACVal)

        )
    )

    @Test
    fun eval01() {
        fun evalEmptyState(e: TACExpr) = TACExprInterpreter.transform(TACExprInterpreter.Acc(EmptyTACState), e)
        fun evalState1(e: TACExpr) = TACExprInterpreter.transform(TACExprInterpreter.Acc(state1), e)

        // Var
        assertEquals(1.asTACVal, evalState1(x1.asSym()))
        assertEquals(true.asTACVal, evalState1(bTrue.asSym()))

        // Arithmetic
        assertEquals(13.asTACVal, evalState1(txf {
            Add( // 13
                Mul( // 8
                    number(2),
                    number(2),
                    number(2)
                ),
                Sub( // 5
                    number(10),
                    Div( // 5
                        number(20),
                        number(4)
                    )
                )
            )
        }))
        assertEquals(13.asTACVal, evalState1(txf {
            IntAdd( // 13
                IntMul( // 8
                    number(2),
                    number(2),
                    number(2)
                ),
                IntSub( // 5
                    number(10),
                    IntDiv( // 5
                        number(20),
                        number(4)
                    )
                )
            )
        }))
        assertEquals(
            evalEmptyState(txf { Sub(number(MAX_EVM_UINT256), number(1)) }),
            evalState1(txf { Mul(number(MAX_EVM_UINT256), bv2.asSym()) })
        )
        assertEquals(
            evalEmptyState(txf { number(1) }),
            evalState1(txf { Add(number(MAX_EVM_UINT256), bv2.asSym()) })
        )


        // Select
        assertEquals(0.asTACVal, evalState1(txf { Select(bm1.asSym(), number(0)) }))
        assertEquals(1.asTACVal, evalState1(txf { Select(bm1.asSym(), number(32)) }))
        assertEquals(2.asTACVal, evalState1(txf { Select(bm1.asSym(), Add(number(32), number(32))) }))

        // this will require implementing the skey bif cases in the interpreter
        // assertEquals(0.asTACVal, evalState1(txf { Select(bm1.asSym(), ..) }))
        // assertEquals(1.asTACVal, evalState1(txf { Select(bm1.asSym(), ..) }))
        // assertEquals(2.asTACVal, evalState1(txf { Select(bm1.asSym(), ..) }))

        // BinBoolOp
        assertEquals(true.asTACVal, evalState1(txf {
            LAnd(True, LOr(False, bFalse.asSym(), bTrue.asSym()))
        }).toBoolTACVal)
        assertEquals(false.asTACVal, evalState1(txf {
            LAnd(True, LOr(False, bFalse.asSym(), bTrue.asSym()), bFalse.asSym())
        }).toBoolTACVal)


        // Bitwise
        assertEquals("1".asTACValBinary, evalState1(txf {
            BWAnd("1001".asTACExpBinary, "0101".asTACExpBinary)
        }))
        assertEquals("1101".asTACValBinary, evalState1(txf {
            BWOr("1001".asTACExpBinary, "0101".asTACExpBinary)
        }))
        assertEquals("1100".asTACValBinary, evalState1(txf {
            BWXOr("1001".asTACExpBinary, "0101".asTACExpBinary)
        }))
        assertEquals("fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff6".asTACValHex,
            evalState1(txf { BWNot("1001".asTACExpBinary) })
        )
        assertEquals("100100".asTACValBinary, evalState1(txf {
            ShiftLeft("1001".asTACExpBinary, "10".asTACExpBinary)
        }))
        assertEquals("10".asTACValBinary, evalState1(txf {
            ShiftRightArithmetical("1001".asTACExpBinary, "10".asTACExpBinary)
        }))
        assertEquals("10".asTACValBinary, evalState1(txf {
            ShiftRightLogical("1001".asTACExpBinary, "10".asTACExpBinary)
        }))



    }

}
