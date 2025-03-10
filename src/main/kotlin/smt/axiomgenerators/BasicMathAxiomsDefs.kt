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

import config.Config
import datastructures.Memoized
import datastructures.stdcollections.*
import evm.EVM_MOD_GROUP256
import smt.axiomgenerators.fullinstantiation.expnormalizer.ExpNormalizer
import smt.solverscript.LExpressionFactory
import smt.solverscript.functionsymbols.TheoryFunctionSymbol.Vec.IntMul.IntMulDontNormalize
import tac.Tag
import utils.*
import vc.data.LExpression
import vc.data.LExpressionWithComment
import java.math.BigInteger


/**
 * Generates the all the axiom definitions for LIA.
 * Everything happens upon instantiation of this class, yet axiom definitions will be created according to actual
 * function symbols already in use according to [lxf].
 *
 * Note that the operators *,/,% will become uninterpreted in [ExpNormalizer], except for * which will stay in the
 * case of multiplication by constant. However, in the definitions, even if we always apply them to constants,
 * the normalizer won't know it. So we use [IntMulDontNormalize] whenever we don't want the uninterpreted version.
 */
class BasicMathAxiomsDefs(val lxf: LExpressionFactory) {

    private fun bAx(
        name: String, descr: String,
        expr: LExpressionFactory.(LExpression, LExpression) -> LExpression
    ) = BinaryIntAxiomDef(lxf, name, descr, expr)

    private fun uAx(
        name: String, descr: String,
        expr: LExpressionFactory.(LExpression) -> LExpression
    ) = UnaryIntAxiomDef(lxf, name, descr, expr)


    //-------------------------------------------------------------------------
    // mul axioms

    private val mulUnaryAxioms by lazy {
        listOf(
            uAx("mul_zero", "a*0 = 0") { a ->
                and(
                    (a uninterpMul ZERO) eq ZERO,
                    (ZERO uninterpMul a) eq ZERO
                )
            },
            uAx("mul_one", "a*1 = a") { a ->
                and(
                    (a uninterpMul ONE) eq a,
                    (ONE uninterpMul a) eq a
                )
            },
            // TODO: should we add: a*2 = a+a?
        )
    }

    private val mulBinaryNonConstAxioms by lazy {
        listOf(
            bAx("mul_commute", "a*b = b*a") { a, b ->
                (a * b) eq (b * a)
            },
            bAx("mul_signs", "sign logic") { a, b ->
                and(
                    and(a gt ZERO, b gt ZERO) implies (a * b gt ZERO),
                    and(a gt ZERO, b lt ZERO) implies (a * b lt ZERO),
                    and(a lt ZERO, b gt ZERO) implies (a * b lt ZERO),
                    and(a lt ZERO, b lt ZERO) implies (a * b gt ZERO),
                )
            },
        )
    }

    private val mulBinaryTagAxioms = Memoized { tag: Tag ->
        if (Config.SignedMulAxioms.get() && tag is Tag.Bits) {
            val modulus = lxf.litInt(tag.modulus)
            val w = tag.bitwidth
            listOf(
                bAx(
                    "mul_signed_$w",
                    "a * b == (a - 2^$w) * b = ... = (a - 2^$w) * (b - 2^$w) mod 2^$w"
                ) { a, b ->
                    and(
                        uninterpTagMod(a * b, tag) eq uninterpTagMod((a - modulus) * b, tag),
                        uninterpTagMod(a * b, tag) eq uninterpTagMod(a * (b - modulus), tag),
                        uninterpTagMod(a * b, tag) eq uninterpTagMod((a - modulus) * (b - modulus), tag)
                    )
                }
            )
        } else {
            emptyList()
        }
    }


    private val mulBinaryWithConstAxioms by lazy {
        bAx("mul_by_const", "uninterp_mul(a,b) = a*b when a or b are const") { a, b ->
            (a uninterpMul b) eq (a intMulDontNorm b)
        }
    }

    private val mulBinaryWithConstTagAxioms = Memoized { tag: Tag ->
        if (Config.SignedMulAxioms.get() && tag is Tag.Bits) {
            val modulus = lxf.litInt(tag.modulus)
            val w = tag.bitwidth
            // these axioms are needed to prove facts about signed multiplication when the constant is the two's
            // complement representation of a negative number.
            // with these we can show "mathint(a) uninterp_mul mathint(b) == mathint(a) * mathint(b)"
            listOf(
                bAx("mul_signed_const_$w", "mul_by_const axiom for a-2^$w and b-2^$w") { a, b ->
                    val mathB = b - modulus
                    // we apply distributivity in two axioms to make sure the argument to multiplication is a constant.
                    and(
                        ((a - modulus) * b) eq ((a intMulDontNorm b) - (modulus intMulDontNorm b)),
                        (a * mathB) eq (a intMulDontNorm mathB),
                        ((a - modulus) * mathB) eq ((a intMulDontNorm mathB) - (modulus intMulDontNorm mathB))
                    )
                }
            )
        } else {
            emptyList()
        }
    }


    //-------------------------------------------------------------------------
    // div axioms

    private val divUnaryAxioms by lazy {
        listOf(
            uAx("div_of0", "0/b == 0") { a ->
                (ZERO / a eq ZERO)
            },
            uAx("div_by1", "a/1 == a") { a ->
                (a / ONE eq a)
            },
            uAx("div_ofEquals", "a!=0 => a/a=1") { a ->
                (a neq ZERO) implies ((a / a) eq ONE)
            }
        )
    }

    private val divBinaryAxioms by lazy {
        listOf(
            bAx("div_decreases", "a>=0,b>0 => 0 <= a/b <= a") { a, b ->
                and(a ge ZERO, b gt ZERO) implies and(a / b ge ZERO, a ge (a / b))
            },
            bAx("div_rhsGe2", "a>b,b>1 => a/b < a") { a, b ->
                and(a gt b, b gt ONE) implies (a / b lt a)
            },
            bAx("div_rhsIsLarge", "0<a,b => (a<b <=> a/b == 0)") { a, b ->
                and(a gt ZERO, b gt ZERO) implies
                    ((a lt b) eq (a / b eq ZERO))
            },
            bAx("div_exact", "0<a,b => a-b < a/b * b <= a") { a, b ->
                and(a gt ZERO, b gt ZERO) implies
                    ((a / b) * b).let { and(it gt (a - b), it le a) }
            }
        )
    }

    private val divBinaryByConstAxioms by lazy {
        listOf(
            bAx("div_by_const", "uninterp_div(a,b) = a/b when b is a const") { a, b ->
                (a uninterpDiv b) eq (a intDivDontNorm b)
            }
        )
    }


    //----------------------------------------------------------------------------------------
    // mod axioms

    private val modUnaryAxioms by lazy {
        listOf(
            uAx("mod_of0", "0%a = 0") { a ->
                (ZERO % a eq ZERO)
            },
            uAx("mod_by1", "a%1 = 0") { a ->
                (a % ONE eq ZERO)
            },
            uAx("mod_of_eq", "a%a = 0") { a ->
                (a % a eq ZERO)
            }
        )
    }

    private val modBinaryAxioms by lazy {
        listOf(
            bAx("mod_bounds", "a>=0 && b>0 => 0 <= a%b < b") { a, b ->
                and(a ge ZERO, b gt ZERO) implies (a % b).within(ZERO, b)
            },
            bAx("mod_small", "a>=0 && b>0  =>  (a<b <=> a%b=a)") { a, b ->
                and(a ge ZERO, b gt ZERO) implies ((a lt b) eq ((a % b) eq a))
            },
            bAx("mod_exact", "a>=0 && b>0 => [a = (a/b)*b + a%b]") { a, b ->
                and(a ge ZERO, b gt ZERO) implies
                    eq(a, ((a / b) * b) + (a % b))
            }
        )
    }


    private val modBinaryByConstAxioms by lazy {
        listOf(
            bAx("mod_by_const", "uninterp_mod(a,b) = a % b, when b is a const") { a, b ->
                (a uninterpMod b) eq (a intModDontNorm b)
            }
        )
    }


    //----------------------------------------------------------------------------------------
    // axioms for two multiplications

    val twoMulsMonotone by lazy {
        QuadIntAxiomDef(lxf, "mul_monotone2", "0<=a1<=a2, 0<=b1<=b2 => a1*b1<=a2*b2") { a1, b1, a2, b2 ->
            and(ZERO le a1, ZERO le b1, a1 le a2, b1 le b2) implies
                ((a1 * b1) le (a2 * b2))
        }
    }
    val twoMulsDistributivity by lazy {
        QuadIntAxiomDef(lxf, "mul_distributivity", "a == c => [ a*b-c*d == a*(b-d) ]") { a, b, c, d ->
            (a eq c) implies
                (((a * b) - (c * d)) eq (a * (b - d)))
        }
    }
    // TODO: associativity


    //---------------------------------------------------------------------------------------
    // These don't need an axiom def

    val noArgsAxioms by lazy {
        with(lxf) {
            listOf(
                LExpressionWithComment(
                    // Funny useless axioms for multiret_struct t4 test
                    and(
                        litInt(3) uninterpMul litInt(3) eq litInt(9),
                        litInt(9) uninterpMul litInt(3) eq litInt(27),
                        litInt(27) uninterpMul litInt(3) eq litInt(81),
                        litInt(9) uninterpMul litInt(9) eq litInt(81)
                    ), "multiples of 3"
                ),
                // TODO: add basic multiplication table?
            )
        }
    }

    /**
     * For a constant [base] axiomatize uninterpExp, e.g., for [base]=3, we get: `3^0 = 1, 3^1 = 3 3^2 = 9, ...`
     */
    fun powAxiom(base: BigInteger) =
        LExpressionWithComment(
            lxf {
                check(base > BigInteger.ONE) { "Ask your nearest SMT-dev to axiomatize powers of 1 and 0" }
                val list = mutableListOf<LExpression>()
                var pow = 0
                var result = BigInteger.ONE
                while (result <= EVM_MOD_GROUP256) {
                    list += (litInt(base) uninterpExp litInt(pow)) eq litInt(result)
                    pow += 1
                    result *= base
                }
                and(list)
            },
            "powers of $base"
        )

    /**
     * Cache to avoid double declaration complaints from [UnaryAxiomDef].
     * (Doesn't need to be a concurrent map because we instantiate [BasicMathAxiomsDefs] per axiom generator instance.)*/
    private val constantPowAxiomCache = mutableMapOf<Int, UnaryAxiomDef>()

    /*
     * [e] is an expression of the form x^c, where `c` is a constant (i.e. a literal). We axiomatize this by "unrolling"
     * the exponentiation into multiplications.
     */
    fun constantPowAxiom(pow: Int) =
        constantPowAxiomCache.getOrPut(pow) {
            uAx("monom$pow", "x^k = x * x * ... x") { a ->
                (a uninterpExp litInt(pow)) eq (List(pow) { a }.reduce { acc, it -> acc intMul it })
            }
        }


    //----------------------------------------------------------------------------------------
    // axioms that are the conjunction of other axioms

    val combinedUnaryMul by lazy {
        UnaryIntAxiomDef(lxf, "combinedMulArg", "axioms for mul arguments", mulUnaryAxioms)
    }

    val combinedBinaryMul = Memoized { tag: Tag ->
        BinaryIntAxiomDef(
            lxf,
            "combinedMul_$tag",
            "mul axioms",
            mulBinaryTagAxioms(tag) + mulBinaryNonConstAxioms
        )
    }

    val combinedBinaryMulWithConst = Memoized { tag: Tag ->
        BinaryIntAxiomDef(
            lxf,
            "combinedMulConst_$tag ",
            "mul by const axioms",
            mulBinaryWithConstTagAxioms(tag) + mulBinaryWithConstAxioms
        )
    }

    val combinedUnaryDiv by lazy {
        UnaryIntAxiomDef(lxf, "combinedDivArg", "axioms for div arguments", divUnaryAxioms)
    }
    val combinedBinaryDiv by lazy {
        BinaryIntAxiomDef(lxf, "combinedDiv", "div axioms", divBinaryAxioms)
    }
    val combinedBinaryDivByConst by lazy {
        BinaryIntAxiomDef(lxf, "combinedDivByConst", "div by const axioms", divBinaryByConstAxioms)
    }

    val combinedUnaryMod by lazy {
        UnaryIntAxiomDef(lxf, "combinedModArg", "axioms for mod arguments", modUnaryAxioms)
    }
    val combinedBinaryMod by lazy {
        BinaryIntAxiomDef(lxf, "combinedMod", "mod axioms", modBinaryAxioms)
    }
    val combinedBinaryModByConst by lazy {
        BinaryIntAxiomDef(lxf, "combinedModByConst", "mod by const axioms", modBinaryByConstAxioms)
    }
}
