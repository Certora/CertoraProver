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

import analysis.split.Ternary.Companion.bwNot
import evm.EVM_BITWIDTH256
import evm.MAX_EVM_UINT256
import smt.solverscript.LExpressionFactory
import utils.*
import vc.data.LExpression
import java.math.BigInteger

/**
 * Generates the all the axiom definitions for LIA and NIA for bitwise operations.
 * Everything happens upon instantiation of this class, yet axiom definitions will be created according to actual
 * function symbols already in use according to [lxf].
 */
class BitwiseAxiomsDefs(val lxf: LExpressionFactory, private val precision: Int) {

    // These wrappers are a little trick so that binAxiom and unaryAxiom can take a no args lambda expression that can use a, b, ab, etc.
    private inner class BinWrap(val a: LExpression, val b: LExpression) {
        val aAb get() = lxf { a bitwiseAnd b }
        val aOb get() = lxf { a bitwiseOr b }
        val aXb get() = lxf { a bitwiseXor b }
    }

    private inner class UnaryWrap(val a: LExpression)

    private fun unaryAxiom(name: String, description: String, exp: UnaryWrap.() -> LExpression) =
        UnaryIntAxiomDef(lxf, name, description) { a ->
            UnaryWrap(a).exp()
        }

    private fun binAxiom(name: String, description: String, exp: BinWrap.() -> LExpression) =
        BinaryIntAxiomDef(lxf, name, description) { a, b ->
            BinWrap(a, b).exp()
        }


    //-------------------------------------------------------------------------
    // bitwise-and axioms

    private val bwAndUnaryAxioms by lazy {
        with(lxf) {
            listOf(
                unaryAxiom("bwand_fullmask", "bwand with full mask") {
                    and(
                        (a bitwiseAnd MAX_UINT) eq a,
                        (MAX_UINT bitwiseAnd a) eq a
                    )
                },
                unaryAxiom("bwand_withzero", "bwand with zero") {
                    and(
                        (a bitwiseAnd ZERO) eq ZERO,
                        (ZERO bitwiseAnd a) eq ZERO
                    )
                },
                unaryAxiom("bwand_eq", "a&a=a") {
                    (a bitwiseAnd a) eq a
                },
            )
        }
    }

    private val bwAndBinaryAxioms by lazy {
        with(lxf) {
            listOf(
                binAxiom("bwand_commute", "a&b = b&a") {
                    aAb eq (b bitwiseAnd a)
                },
                binAxiom("bwand_bound", "bwand bounds") {
                    and(
                        (a intGe ZERO) implies (aAb intLe a),
                        (b intGe ZERO) implies (aAb intLe b),
                        aAb intGe ZERO
                    )
                },
            )
        }
    }


    //-------------------------------------------------------------------------
    // bitwise-or axioms

    private val bwOrUnaryAxioms by lazy {
        with(lxf) {
            listOf(
                unaryAxiom("bwor_fullmask", "bwor with full mask") {
                    and(
                        (a bitwiseOr MAX_UINT) eq MAX_UINT,
                        (MAX_UINT bitwiseOr a) eq MAX_UINT
                    )
                },
                unaryAxiom("bwor_withzero", "bwor with zero") {
                    and(
                        (a bitwiseOr ZERO) eq a,
                        (ZERO bitwiseOr a) eq a
                    )
                },
                unaryAxiom("bwor_eq", "a|a=a") {
                    (a bitwiseOr a) eq a
                },
            )
        }
    }

    private val bwOrBinaryAxioms by lazy {
        with(lxf) {
            listOf(
                binAxiom("bwor_commute", "a|b = b|a") {
                    aOb eq (b bitwiseOr a)
                },
                binAxiom("bwor_bound", "bwor bounds") {
                    and(
                        (a intGe ZERO) implies (aOb intGe a),
                        (b intGe ZERO) implies (aOb intGe b),
                        aOb intLe (a + b)
                    )
                },
            )
        }
    }


    //-------------------------------------------------------------------------
    // bitwise-xor axioms

    private val bwXorUnaryAxioms by lazy {
        with(lxf) {
            listOf(
                // MAX_UINT is just 1's, so subtracting a number from it is just like erasing the 1 bits of the number
                // from MAX_UINT, i.e., xor-ing with it.
                unaryAxiom("bwxor_fullmask", "bwxor with full mask") {
                    and(
                        (a bitwiseXor MAX_UINT) eq (MAX_UINT intSub a),
                        (MAX_UINT bitwiseXor a) eq (MAX_UINT intSub a)
                    )
                },
                unaryAxiom("bwxor_withzero", "bwxor with zero") {
                    and(
                        (a bitwiseXor ZERO) eq a,
                        (ZERO bitwiseXor a) eq a
                    )
                },
                unaryAxiom("bwxor_eq", "a^a=0") {
                    (a bitwiseXor a) eq ZERO
                }
            )
        }
    }

    private val bwXorBinaryAxioms by lazy {
        with(lxf) {
            listOf(
                binAxiom("bwxor_commute", "a^b = b^a") {
                    aXb eq (b bitwiseXor a)
                },
                binAxiom("bwxor_bound", "bwxor bounds") {
                    and(
                        aXb intGe ZERO,
                        aXb intLe (a + b),
                    )
                },
            )
        }
    }


    //-------------------------------------------------------------------------
    // bitwise-and axioms with a constant


    private fun bwAndMaskAxioms(mask: BigInteger) = lxf {
        if (mask == BigInteger.ZERO || mask == MAX_EVM_UINT256) {
            return@lxf null
        }
        val ones = ones(mask)
        val name = "bwand_${mask.toString(16)}"
        val lMask = litInt(mask)
        when {
            // lower bits only mask, e.g., 0x00000fffff
            ones.size == 1 && ones[0].right == 0 && precision >= 2 -> {
                val left = ones[0].left
                unaryAxiom("${name}_via_mod", "a & 0xff = a % 256") {
                    (a bitwiseAnd lMask) eq (a intModDontNorm twoTo(left))
                }
            }

            // mask with only one 1s-interval, e.g., 0xfff000, but f's go all the way up.
            ones.size == 1 && ones[0].left == EVM_BITWIDTH256 && precision >= 2-> {
                val right = ones[0].right
                unaryAxiom("${name}_via_mod", "a & 0xfff...f0 = a - (a % 16)") {
                    eq(
                        a bitwiseAnd lMask,
                        a - (a intModDontNorm twoTo(right)),
                    )
                }
            }

            // mask with only one 1s-interval, e.g., 0xfff000
            ones.size == 1 && precision >= 3 -> {
                val left = ones[0].left
                val right = ones[0].right
                unaryAxiom("${name}_via_mod", "a & 0xf0 = (a % 256) - (a % 16)") {
                    eq(
                        a bitwiseAnd lMask,
                        (a intModDontNorm twoTo(left)) - (a intModDontNorm twoTo(right))
                    )
                }
            }

            // mask with only one 0s-interval, e.g., 0xf..f00fff.
            ones.size == 2 && ones[0].right == 0 && ones[1].left == EVM_BITWIDTH256 && precision >= 4 -> {
                unaryAxiom("${name}_via_mod", "a & 0xf...f0f = a - a & 0xf0") {
                    eq(
                        a bitwiseAnd lMask,
                        a - (a bitwiseAnd litInt(bwNot(mask)))
                    )
                }
            }

            // mask is of the form 000111111 yet precision is low
            ones.size == 1 && ones[0].right == 0 -> {
                val pow = twoTo(ones[0].left)
                unaryAxiom("${name}_simple_mask", "low precision simple mask axioms") {
                    and(
                        eq(
                            a.within(ZERO, pow),
                            (lMask bitwiseAnd a) eq a
                        ),
                        implies(
                            a.within(pow, pow + pow),
                            (lMask bitwiseAnd a) eq (a - pow)
                        )
                    )
                }
            }

            // simple axioms in case precision is low or mask is weird.

            ones[0].right == 0 ->
                // mask has 1's as lower order bits
                unaryAxiom("${name}_lower1", "bwand with small value is identity") {
                    a.within(ZERO, twoTo(ones[0].left)) implies ((lMask bitwiseAnd a) eq a)
                }

            else ->
                // mask has 0's as lower order bits.
                unaryAxiom("${name}_lower0", "bwand with small value is zero") {
                    a.within(ZERO, twoTo(ones[0].right)) implies ((lMask bitwiseAnd a) eq ZERO)
                }

        }
    }


    //-------------------------------------------------------------------------
    // bitwise-or axioms with a constant

    /**
     * Similar to [bwAndMaskAxioms]
     */
    private fun bwOrMaskAxioms(mask: BigInteger): UnaryIntAxiomDef? = lxf {
        if (mask == BigInteger.ZERO || mask == MAX_EVM_UINT256) {
            return@lxf null
        }

        val ones = ones(mask)
        val name = "bwor_${mask.toString(16)}"
        val lMask = litInt(mask)

        when {
            // mask is of the form 000111111
            ones.size == 1 && ones[0].right == 0 && precision >= 2 -> {
                val power = twoTo(ones[0].left)
                unaryAxiom("${name}_via_mod", "precise bwor") {
                    eq(
                        a bitwiseOr lMask,
                        a - (a intModDontNorm power) + lMask
                    )
                }
            }

            // mask with only one 1s-interval, e.g., 0xfff000
            ones.size == 1 && precision >= 3 -> {
                unaryAxiom("${name}_via_bwAnd", "a | 0xf0 = a - (a & 0xf0) + 0xf0") {
                    eq(
                        a bitwiseOr lMask,
                        (a - (a bitwiseAnd lMask)) + lMask
                    )
                }
            }

            // mask has 1's as lower order bits
            ones[0].right == 0 ->
                unaryAxiom("${name}_lower1", "bwor with small value is just the mask") {
                    a.within(ZERO, twoTo(ones[0].left)) implies (lMask bitwiseOr a eq lMask)
                }

            // mask has 0's as lower order bits.
            else ->
                unaryAxiom("${name}_lower0", "bwor with small value is plus") {
                    a.within(ZERO, twoTo(ones[0].left)) implies (lMask bitwiseOr a eq (a + lMask))
                }
        }
    }

    //TODO: Xor


    //----------------------------------------------------------------------------------------
    // other axioms

    private val createdSignExtend = mutableMapOf<Int, UnaryIntAxiomDef>()
    fun signExtend(i: Int) =
        createdSignExtend.getOrPut(i) {
            with(lxf) {
                val bits = (i + 1) * 8
                val lowOnes = BigInteger.TWO.pow(bits) - BigInteger.ONE
                unaryAxiom("signextend_$i", "signextend axiom") {
                    val onlyLows = a bitwiseAnd litInt(lowOnes)
                    a.signExt(i) eq ite(
                        onlyLows lt twoTo(bits - 1), // this means highest bit (sign bit) is 0.
                        onlyLows,
                        onlyLows intAdd litInt(MAX_EVM_UINT256 - lowOnes)
                    )
                }
            }
        }


    //----------------------------------------------------------------------------------------
    // axioms that are the and of other axioms

    val combinedBwAndUnary by lazy {
        UnaryIntAxiomDef(lxf, "combinedBwandArg", "all axioms on bwand arguments", bwAndUnaryAxioms)
    }
    val combinedBwAndBinary by lazy {
        BinaryIntAxiomDef(lxf, "combinedBwandApp", "all axioms on bwand operations", bwAndBinaryAxioms)
    }
    val combinedBwOrUnary by lazy {
        UnaryIntAxiomDef(lxf, "combinedBworArg", "all axioms on bwor arguments", bwOrUnaryAxioms)
    }
    val combinedBwOrBinary by lazy {
        BinaryIntAxiomDef(lxf, "combinedBworApp", "all axioms on bwor operations", bwOrBinaryAxioms)
    }
    val combinedBwXorUnary by lazy {
        UnaryIntAxiomDef(lxf, "combinedBwxorArg", "all axioms on bwxor arguments", bwXorUnaryAxioms)
    }
    val combinedBwXorBinary by lazy {
        BinaryIntAxiomDef(lxf, "combinedBwxorApp", "all axioms on bwxor operations", bwXorBinaryAxioms)
    }

    private val createdMaskAndAxioms = mutableMapOf<BigInteger, UnaryIntAxiomDef>()

    fun combinedBwAndWithMask(mask: BigInteger) =
        createdMaskAndAxioms.getOrPut(mask) {
            combinedBwAndBinary // create and register this axiom before the next.
            val maskAxioms = bwAndMaskAxioms(mask)
            unaryAxiom("combinedBwand_${mask.toString(16)}", "all mask and non mask related axioms") {
                lxf {
                    val generalAxioms = combinedBwAndBinary.applyDef(a, litInt(mask))
                    maskAxioms?.let {
                        it.applyDef(a) and generalAxioms
                    } ?: generalAxioms
                }
            }
        }

    private val createdMaskOrAxioms = mutableMapOf<BigInteger, UnaryIntAxiomDef>()

    fun combinedBwOrWithMask(mask: BigInteger) =
        createdMaskOrAxioms.getOrPut(mask) {
            combinedBwOrBinary // create and register this axiom before the next.
            val maskAxioms = bwOrMaskAxioms(mask)
            unaryAxiom("combinedBwOr_${mask.toString(16)}", "all mask and non mask related axioms") {
                lxf {
                    val generalAxioms = combinedBwOrBinary.applyDef(a, litInt(mask))
                    maskAxioms?.let {
                        it.applyDef(a) and generalAxioms
                    } ?: generalAxioms
                }
            }
        }
}


//----------------------------------------------------------------------------------------
// analyzing the bit structure of a BigInteger

/**
 * Used here to represent a consecutive sequence of 1-bits within a BigInteger.
 * The first 1 is at the bit [right], and the last is at [left] - 1.
 */
private data class Interval(val right: Int, val left: Int)

/**
 * Returns the list of intervals of 1 bits a [BigInteger]. For example, for 111000110111, it will return:
 * [(0,3), (4,6), (9,12)]
 */
private fun ones(n: BigInteger): List<Interval> {
    val result: MutableList<Interval> = mutableListOf()

    var lastWasOne = false
    var start = 0
    for (i in 0..n.bitLength()) {
        val currentBitIsOne = n.testBit(i)
        when {
            lastWasOne and !currentBitIsOne -> result.add(Interval(start, i))
            !lastWasOne and currentBitIsOne -> start = i
        }
        lastWasOne = currentBitIsOne
    }
    return result
}
