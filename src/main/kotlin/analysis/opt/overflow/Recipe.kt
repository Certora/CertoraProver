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

package analysis.opt.overflow


import analysis.LTACCmd
import analysis.PatternMatcher
import analysis.TACCommandGraph
import analysis.numeric.MAX_UINT
import analysis.opt.overflow.OverflowKey.*
import analysis.opt.overflow.OverflowKey.Companion.bound
import analysis.opt.overflow.OverflowKey.Companion.extendBit
import analysis.opt.overflow.OverflowKey.Companion.fittingMaxSIntK
import analysis.opt.overflow.OverflowKey.Companion.fittingMaxUintK
import analysis.opt.overflow.OverflowKey.Companion.maxSIntK
import analysis.opt.overflow.OverflowKey.Companion.maxUintK
import analysis.opt.overflow.OverflowKey.Companion.minSIntK
import analysis.opt.overflow.OverflowKey.Companion.shiftBy
import analysis.opt.overflow.OverflowKey.Companion.signedWidth
import analysis.opt.overflow.OverflowKey.Companion.unsignedWidth
import analysis.opt.overflow.OverflowPattern.Companion.neg
import analysis.opt.overflow.OverflowPattern.Companion.pos
import analysis.patterns.Info
import analysis.patterns.Info.Companion.set
import analysis.patterns.InfoKey
import analysis.patterns.PatternHelpers
import analysis.patterns.get
import analysis.split.Ternary.Companion.isPowOf2
import analysis.split.Ternary.Companion.isPowOf2Minus1
import config.Config
import datastructures.stdcollections.*
import evm.*
import utils.*
import utils.ModZm.Companion.from2s
import utils.ModZm.Companion.lowOnes
import utils.SignUtilities.minSignedValueOfBitwidth
import vc.data.TACCmd
import vc.data.TACExpr
import java.math.BigInteger


typealias OverflowInfo<T> = Map<OverflowPattern<T>, Info>
typealias PI = PatternMatcher.Pattern<Info>

@Suppress("ClassName")
sealed class OverflowKey<K> : InfoKey<K>() {
    data object EXTEND_BIT : OverflowKey<Int>()
    data object MAX_UINTK : OverflowKey<BigInteger>()
    data object NEGATED : OverflowKey<Boolean>()
    data object MATCHED_LCMD : OverflowKey<LTACCmd>()
    data object MAX_SINTK : OverflowKey<BigInteger>()
    data object MIN_SINTK : OverflowKey<BigInteger>()
    data object UNSIGNED_WIDTH : OverflowKey<Int>()
    data object SIGNED_WIDTH : OverflowKey<Int>()
    data object BOUND : OverflowKey<BigInteger>()
    data object SHIFT_BY : OverflowKey<Int>()
    data object C1 : OverflowKey<BigInteger>()


    companion object {
        /**
         * A pattern to match a 0xffff type number, which should represent the output width of the operation.
         *
         * Note that the [Info.set] method will cause the match to fail if a key is set to two different values.
         * For example, if a pattern uses [maxUintK] twice, and these two matches are with different constants,
         * the [UNSIGNED_WIDTH] will be set to different values, and the matching will fail.
         */
        val maxUintK = PatternHelpers {
            c(MAX_UINTK) { it.isPowOf2Minus1 }.andDo { set(UNSIGNED_WIDTH, get(MAX_UINTK)!!.bitCount()) }
        }

        /** same as [maxUintK], but also checks that operands are known to be within the same width bound */
        val OverflowContext.Binary.fittingMaxUintK
            get() = PatternHelpers {
                maxUintK.onlyIf { intervals1 isLe get(MAX_UINTK)!! && intervals2 isLe get(MAX_UINTK)!! }
            }

        val OverflowContext.Const.fittingMaxUintK
            get() = PatternHelpers {
                maxUintK.onlyIf { opIntervals isLe get(MAX_UINTK)!! }
            }

        val extendBit = PatternHelpers {
            c(EXTEND_BIT) { it in 0..30 } // 31 is a no-op, and is be removed earlier by normalization.
                .andDo { set(SIGNED_WIDTH, (get(EXTEND_BIT)!! + 1) * 8) }
        }
        val maxSIntK = PatternHelpers {
            c(MAX_SINTK) { it.isPowOf2Minus1 }
                .andDo { set(SIGNED_WIDTH, get(MAX_SINTK)!!.bitCount() + 1) }
        }

        /** same as [maxSIntK], but also checks that operands are known to be within the same width bound */
        val OverflowContext.Binary.fittingMaxSIntK
            get() = PatternHelpers {
                maxSIntK.onlyIf {
                    intervals1.signExtend(get(SIGNED_WIDTH)!!, modZ256.bitwidth) == intervals1 &&
                        intervals2.signExtend(get(SIGNED_WIDTH)!!, modZ256.bitwidth) == intervals2
                }
            }

        val minSIntK = PatternHelpers {
            c(MIN_SINTK) { (MAX_UINT - it).isPowOf2Minus1 }
                .andDo { set(SIGNED_WIDTH, (MAX_UINT - get(MIN_SINTK)!!).bitCount() + 1) }
        }

        val Info.unsignedWidth get() = this[UNSIGNED_WIDTH]
        val Info.signedWidth get() = this[SIGNED_WIDTH]

        val OverflowContext.Const.shiftBy
            get() = PatternHelpers {
                c(SHIFT_BY) { it in 1..255 && BigInteger.TWO.pow(it) == const }
            }

        /**
         * [Info] being the payload of pattern matching some [OverflowPattern], if the jump cond matched the
         * [OverflowPattern.basePattern] and the pattern is [OverflowPattern.pos] then the non-reverting destination
         * is the `dst` of the jumpi command, and otherwise the `elsedst`. This is reversed if tha pattern is has
         * [OverflowPattern.pos] as false.
         *
         * Note that [NEGATED] is not strictly added in the pattern-matching process, but rather added in [Matcher]
         * after a pattern or its negation is matched.
         */
        val Info?.matchedDst
            get() = (this[MATCHED_LCMD]!!.cmd as TACCmd.Simple.JumpiCmd).let {
                if (this[NEGATED]!!) {
                    it.elseDst
                } else {
                    it.dst
                }
            }

        val Info?.revertDst
            get() = (this[MATCHED_LCMD]!!.cmd as TACCmd.Simple.JumpiCmd).let {
                if (this[NEGATED]!!) {
                    it.dst
                } else {
                    it.elseDst
                }
            }

        val Info.bound get() = get(BOUND)!!
    }
}

/**
 * Holds a basic pattern to be matched: a part of a recipe.
 * The [basePattern] should never contain a `LNot` at the top level, but instead use the `isNegated` flag.
 *
 * Each pattern ultimately matches some logical formula (expressed as a tac expression) over the operands of the
 * mathematical operation. [isNegated] indicates whether the formula being false or being true indicates (maybe along
 * with other patterns in a [Recipe]) that the math operation must not overflow. That is, suppose we have a pattern
 * which matches some predicate `p(x, y)` where `r = x * y`. If `isNegated = true`, then `p(x, y)` being `FALSE` means
 * that `x * y` must not overflow. If instead, `isNegated = false`, then `p(x, y)` being `TRUE` means that the `x * y`
 * must not overflow.
 *
 * For example, the following is a negated pattern for proving that unsigned multiplication does not overflow:
 * ```
 *    lAnd(
 *      !(op1 eq zero),
 *      (maxUint / op1) lt op2
 *    )
 * ```
 * Which says that `(op1 != 0) && (maxUint / op1 < op2)`.
 * It's negated, because there is no overflow iff this condition is false. If this pattern appears through an assume
 * command, we should actually be looking for an `LNot` of this pattern, but if it appears as a jump condition, we will
 * see the pattern itself used as the jump condition, and the `elseDst` being the block to jump to if there is no
 * overflow.
 *
 * Keeping the pattern itself without the top level `LNot`, and the information whether it is negated or not separate,
 * lets us match both cases.
 */
class OverflowPattern<T : OverflowContext>(
    private val isNegated: Boolean = false,
    /** can't have a lnot at the top level of */
    private val basePattern: T.() -> PI,
) {
    fun genQuery(g: TACCommandGraph, context: T, negated: Boolean) =
        PatternMatcher.compilePattern(
            g,
            basePattern(context)
                .letIf(isNegated != negated) {
                    PatternHelpers { !it }
                })

    companion object {
        fun <T : OverflowContext> pos(pattern: T.() -> PI) =
            OverflowPattern(false, pattern)

        fun <T : OverflowContext> neg(pattern: T.() -> PI) =
            OverflowPattern(true, pattern)
    }
}


enum class RecipeType {
    ConstMul, BinaryMul, Add, Sub;
}

data class Recipe<T : OverflowContext>(
    /** For debugging purposes */
    val name: String,
    /** Signed operation or unsigned */
    val signed: Boolean,
    /** See above */
    val type: RecipeType,
    /** Some recipes are known to have no chance given the specific [OverflowContext] */
    val shouldActivate: (T) -> Boolean,
    /** all of these pattern need to match (e.g., consecutive jump conditions) on a context, for a full match */
    val patterns: List<OverflowPattern<T>>
) {
    override fun toString() = "<$type, $signed, $name>"

    fun activation(p: T.() -> Boolean) = this@Recipe.copy(shouldActivate = p)

    fun width(infos: OverflowInfo<T>): Int? =
        infos.values.mapNotNull {
            if (signed) {
                it.signedWidth
            } else {
                it.unsignedWidth
            }
        }.uniqueOrNull()


    /*
       To prove the recipes that follow, we use an SMT forumla. To make life easier for the solvers, 256 is 8 here.
       This is a sample proof with auxiliaries that make it simple to plug in other proofs. The proofs in the
       comments that follow only show the `pattern` decalre-fun, and sometimes the `preconditions` one, if necessary.
       ```
        (set-logic QF_BV)

        ; choose a width of the operation (at most 8)
        (define-fun width () (_ BitVec 8) #x04)

        (declare-fun op1 () (_ BitVec 8))
        (declare-fun op2 () (_ BitVec 8))
        (define-fun res () (_ BitVec 8) (bvmul op1 op2))

        (define-fun zero () (_ BitVec 8) #x00)
        (define-fun one () (_ BitVec 8) #x01)
        (define-fun lowOnes ((x (_ BitVec 8))) (_ BitVec 8) (bvsub (bvshl one x) one))
        (define-fun maxUInt () (_ BitVec 8) (lowOnes width))
        (define-fun maxInt () (_ BitVec 8) (bvsub (bvshl one (bvsub width one)) one))
        (define-fun minInt () (_ BitVec 8) (bvsub (bvsub zero one) maxInt))

        ; comment one of the following two:
        (define-fun inBounds ((x (_ BitVec 8))) Bool (or (bvule x maxInt) (bvuge x minInt))) ;signed
        (define-fun inBounds ((x (_ BitVec 8))) Bool (bvule x maxUInt)) ;unsigned

        ; put in the correct smtlib overflow operator, here its bvsmulo, standing for bv-signed-mul-overflow
        (define-fun noOverflow () Bool (and (not (bvsmulo op1 op2)) (inBounds res)))

        ; an auxiliary function for patterns that use sign-extend.
        (define-fun signExtend ((x (_ BitVec 8)) (fromBit (_ BitVec 8))) (_ BitVec 8)
            (ite
              (= (bvand x (bvshl one fromBit)) zero) ; true if unsigned
              (bvand x (lowOnes fromBit))
              (bvor x (bvsub (lowOnes #x08) (lowOnes fromBit)))
            )
          )

        (define-fun extendFromWidth ((x (_ BitVec 8))) (_ BitVec 8) (signExtend x (bvsub width one)))

        ; if there are checked conditions that don't appear in the pattern, add them here instead of `true`.
        (define-fun preconditions () Bool
           true)
           ;(and (inBounds op1) (inBounds op2)))

        ; this is where the pattern should appear:
        (define-fun pattern () Bool
            (or
              (= op1 zero)
              (= op2 (bvsdiv (extendFromWidth res) op1))
            )
        )

        (assert (not (=> preconditions (= pattern noOverflow))))
        (check-sat)
        ```
     */
    companion object {

        private val <T : OverflowContext> Recipe<T>.simple: Recipe<T>?
            get() = takeIf { Config.SimpleOverflowPattern.get() }

        /** This is the 2^256-1, but with a special name to mark its usage as -1 in 2s complement computations */
        private val MINUS_ONE = MAX_UINT

        private fun uAdd(name: String, vararg patterns: OverflowPattern<OverflowContext.Binary>) =
            Recipe(name, signed = false, RecipeType.Add, { true }, patterns.toList())

        private val unsignedBinaryAdd = with(PatternHelpers) {
            listOfNotNull(
                uAdd(
                    "post_full",
                    pos {
                        op1 le res
                    }).simple,
                uAdd(
                    "post_mid",
                    pos {
                        res le fittingMaxUintK
                    }).simple,
                uAdd(
                    "pre_mid",
                    pos {
                        op1 le (fittingMaxUintK - op2)
                    }),
                uAdd(
                    "pre_full",
                    pos {
                        op1 le bwNot(op2)
                    }),
            )
        }

        private fun sAdd(name: String, vararg patterns: OverflowPattern<OverflowContext.Binary>) =
            Recipe(name, signed = true, RecipeType.Add, { true }, patterns.toList())

        private val signedBinaryAdd = with(PatternHelpers) {
            listOf(
//                  (and
//                    (not (and
//                      (bvsle zero op1)
//                      (bvslt (bvsub maxInt op1) op2)
//                    ))
//                    (not (and
//                      (bvslt op1 zero)
//                      (bvslt op2 (bvsub minInt op1))
//                    ))
//                  ))
//                preconditons = (and (inBounds op1) (inBounds op2))
                sAdd(
                    "pre",
                    neg {
                        lAnd(
                            zero sLe op1,
                            (fittingMaxSIntK - op1) sLt op2
                        )
                    },
                    neg {
                        lAnd(
                            op1 sLt zero,
                            op2 sLt (minSIntK - op1)
                        )
                    }
                ),
                sAdd(
                    "post_full",
                    neg {
                        lOr(
                            lAnd(
                                zero sLe op1,
                                res sLt op2
                            ),
                            lAnd(
                                op1 sLt zero,
                                op2 sLe res
                            )
                        )
                    }
                ),
                sAdd(
                    "post_narrow",
                    neg {
                        lOr(
                            maxSIntK sLt res,
                            res sLt minSIntK
                        )
                    }
                ).activation { surelyNo256SignedAddOverflow },

//                  (=
//                    (bvslt res op1)
//                    (bvslt op2 zero)
//                  ))
                sAdd(
                    "vyper_full",
                    pos {
                        (res sLt op1) eq (op2 sLt zero)
                    }
                ),
                sAdd(
                    "vyper_narrow",
                    pos {
                        res eq (extendBit signExtend res)
                    }
                ).activation { surelyNo256SignedAddOverflow },
            )
        }


        private fun uSub(name: String, vararg patterns: OverflowPattern<OverflowContext.Binary>) =
            Recipe(name, signed = false, RecipeType.Sub, { true }, patterns.toList())

        private val unsignedBinarySub = with(PatternHelpers) {
            listOfNotNull(
                uSub(
                    "pre_full",
                    pos {
                        (op2 le op1).andDo {
                            val width = maxOf(8, maxOf(intervals1.max.n, intervals2.max.n).bitLength().divRoundUp(8) * 8)
                            letIf(width < EVM_BITWIDTH256) {
                                set(UNSIGNED_WIDTH, width)
                            }
                        }
                    }).simple,
                uSub(
                    "post_full",
                    pos {
                        res le op1
                    }).simple,
                uSub(
                    "post",
                    pos {
                        res le fittingMaxUintK
                    }).simple
            )
        }


        private fun sSub(name: String, vararg patterns: OverflowPattern<OverflowContext.Binary>) =
            Recipe(name, signed = true, RecipeType.Sub, { true }, patterns.toList())

        private val signedBinarySub = with(PatternHelpers) {
            listOf(
//                    (and
//                        (not (and
//                        (bvslt op1 (bvadd minInt op2))
//                        (bvsle zero op2)
//                    ))
//                        (not (and
//                        (bvslt (bvadd maxInt op2) op1)
//                        (bvslt op2 zero)
//                    )))
//                preconditions = (and (inBounds op1) (inBounds op2)))
                sSub(
                    "pre",
                    neg {
                        lAnd(
                            op1 sLt (minSIntK + op2),
                            zero sLe op2
                        )
                    },
                    neg {
                        lAnd(
                            (fittingMaxSIntK + op2) sLt op1,
                            op2 sLt zero
                        )
                    }
                ),
                sSub(
                    // almost the same as the previous. relies on `minSIntK = -2^(width-1)`
                    // So C1 may be 128 and the width is then 8.
                    "pre2",
                    neg {
                        lAnd(
                            op1 sLt (op2 - c(C1) { it.isPowOf2 }
                                .andDo {
                                    set(SIGNED_WIDTH, get(C1)!!.bitLength())
                                }),
                            zero sLe op2
                        )
                    },
                    neg {
                        lAnd(
                            (fittingMaxSIntK + op2) sLt op1,
                            op2 sLt zero
                        )
                    }
                ),
                sSub(
                    "post_full",
                    neg {
                        lOr(
                            lAnd(
                                zero sLe op2,
                                op1 sLt res
                            ),
                            lAnd(
                                op2 sLt zero,
                                res sLt op1
                            )
                        )
                    }
                ),
                sSub(
                    "post_narrow",
                    neg {
                        lOr(
                            res sLt minSIntK,
                            maxSIntK sLt res
                        )
                    }).activation { surelyNo256SignedSubOverflow },

//                  (=
//                    (bvslt op2 zero)
//                    (bvslt op1 res)
//                  ))
                sSub(
                    "vyper_full",
                    pos {
                        (op2 sLt zero) eq (op1 sLt res)
                    }),
                sSub(
                    "vyper_narrow",
                    pos {
                        res eq (extendBit signExtend res)
                    }
                ).activation { surelyNo256SignedSubOverflow }
            )
        }


        private fun ubMul(name: String, vararg patterns: OverflowPattern<OverflowContext.Binary>) =
            Recipe(name, signed = false, RecipeType.BinaryMul, { true }, patterns.toList())

        private val unsignedBinaryMul = with(PatternHelpers) {
            listOf(
                ubMul(
                    "post_full",
                    pos {
                        lOr(
                            op1 eq zero,
                            op2 eq (res / op1)
                        )
                    }),

//                  (or
//                    (= op1 zero)
//                    (= op2 (bvudiv (bvand res maxUInt) op1))
//                  ))
                ubMul(
                    "post_mid",
                    pos {
                        lOr(
                            op1 eq zero,
                            op2 eq ((res bwAnd maxUintK) / op1)
                        )
                    }),

//                 (and
//                    (or
//                      (= op1 zero)
//                      (= op2 (bvudiv res op1))
//                    )
//                    (bvule res maxUInt))
                ubMul(
                    "post_mid_vyper",
                    pos {
                        lOr(
                            op1 eq zero,
                            op2 eq (res / op1)
                        )
                    },
                    pos {
                        res le maxUintK
                    }),
                ubMul(
                    "post_narrow",
                    pos {
                        res le maxUintK
                    }).activation { surelyNo256UnsignedMulOverflow },

//                (not
//                    (and
//                      (not (= op1 zero))
//                      (bvult (bvudiv maxUInt op1) op2)
//                    ))
                ubMul(
                    "pre",
                    neg {
                        lAnd(
                            !(op1 eq zero),
                            (maxUintK / op1) lt op2
                        )
                    }),
            )
        }


        private fun sbMul(name: String, vararg patterns: OverflowPattern<OverflowContext.Binary>) =
            Recipe(name, signed = true, RecipeType.BinaryMul, { true }, patterns.toList())

        private val signedBinaryMul = with(PatternHelpers) {
            listOf(
//                 (and
//                    (or
//                      (= op1 zero)
//                      (= op2 (bvsdiv res op1))
//                    )
//                    (not (and
//                      (= op2 minInt)
//                      (bvslt op1 zero)
//                    )))
                sbMul(
                    "post_full",
                    pos {
                        lOr(
                            op1 eq zero,
                            op2 eq (res sDiv op1)
                        )
                    },
                    neg {
                        lAnd(
                            op2 eq c(MIN_EVM_INT256_2S_COMPLEMENT),
                            // note that the problematic case is actually just -1, and not all negative `op1`s.
                            // this is what the following pattern does.
                            op1 sLt zero
                        )
                    }
                ),
                sbMul( // this one is a subcase of the previous one
                    "post_full_vyper",
                    pos {
                        lAnd(
                            lOr(
                                op1 eq zero,
                                op2 eq (res sDiv op1)
                            ),
                            !(lAnd(
                                op2 eq c(MIN_EVM_INT256_2S_COMPLEMENT),
                                op1 eq c(MINUS_ONE)
                            ))
                        )
                    }
                ),
                // (or
                //   (= op1 zero)
                //   (= op2 (bvsdiv (extendFromWidth res) op1))
                // )
                sbMul(
                    "post_mid",
                    pos {
                        lOr(
                            op1 eq zero,
                            op2 eq ((extendBit signExtend res) sDiv op1)
                        )
                    }),

                // (and
                //    (or
                //      (= op1 zero)
                //      (= op2 (bvsdiv res op1))
                //    )
                //    (= res (extendFromWidth res)))
                sbMul(
                    "post_mid_vyper",
                    pos {
                        lOr(
                            op1 eq zero,
                            op2 eq (res sDiv op1)
                        )
                    },
                    pos {
                        res eq (extendBit signExtend res)
                    }
                ),
                sbMul(
                    "post_narrow",
                    pos {
                        res eq (extendBit signExtend res)
                    }
                ).activation { surelyNo256SignedMulOverflow },

                //  (and
                //      (not (and
                //        (and (bvslt zero op1) (bvslt zero op2))
                //        (bvult (bvudiv maxInt op2) op1)
                //      ))
                //      (not (and
                //        (and (bvslt zero op1) (bvslt op2 zero))
                //        (bvslt op2 (bvsdiv minInt op1))
                //      ))
                //      (not (and
                //        (and (bvslt op1 zero) (bvslt zero op2))
                //        (bvslt op1 (bvsdiv minInt op2))
                //      ))
                //      (not (and
                //        (and (bvslt op1 zero) (bvslt op2 zero))
                //        (bvslt op1 (bvsdiv maxInt op2))
                //      ))
                //    )
                sbMul(
                    "pre",
                    neg {
                        lAnd(
                            (zero sLt op1) lAnd (zero sLt op2),
                            (maxSIntK / op2) lt op1
                        )
                    },
                    neg {
                        lAnd(
                            (zero sLt op1) lAnd (op2 sLt zero),
                            op2 sLt (minSIntK sDiv op1)
                        )
                    },
                    neg {
                        lAnd(
                            (op1 sLt zero) lAnd (zero sLt op2),
                            op1 sLt (minSIntK sDiv op2)
                        )
                    },
                    neg {
                        lAnd(
                            (op1 sLt zero) lAnd (op2 sLt zero),
                            op1 sLt (maxSIntK sDiv op2)
                        )
                    }
                ),
            )
        }


        private fun ucMul(name: String, vararg patterns: OverflowPattern<OverflowContext.Const>) =
            Recipe(name, signed = false, RecipeType.ConstMul, { true }, patterns.toList())

        // The proofs of these follow from their var by var counterparts.
        private val unsignedConstMul = with(PatternHelpers) {
            listOf(
                ucMul(
                    "post_full1",
                    pos {
                        lOr(
                            op eq zero,
                            cc eq (res / op)
                        )
                    }),
                ucMul(
                    "post_full2",
                    pos {
                        op eq (res / cc)
                    }),
                ucMul(
                    "post_mid",
                    pos {
                        op eq (res / cc)
                    },
                    pos {
                        res le maxUintK
                    }),
                ucMul(
                    "post_mid1",
                    pos {
                        op eq ((res bwAnd maxUintK) / cc)
                    }),
                ucMul(
                    "post_mid2",
                    pos {
                        lOr(
                            op eq zero,
                            cc eq ((res bwAnd maxUintK) / op)
                        )
                    }),
                ucMul(
                    // This one appears with optimize+viaIR in solc8.9 when multiplication is by a power of 2.
                    // Using intervals-calculator beforehand could have removed the bw-and.
                    "post_mid3",
                    pos {
                        val opop = (op bwAnd fittingMaxUintK)
                        lOr(
                            opop eq zero,
                            cc eq ((res bwAnd maxUintK) / opop)
                        )
                    }),
                ucMul(
                    "post_mid_vyper",
                    pos {
                        OR(
                            lOr(
                                op eq zero,
                                cc eq (res / op)
                            ),
                            op eq (res / cc),
                        )
                    },
                    pos {
                        res le maxUintK
                    }),
                ucMul(
                    "post_pow2_vyper_full",
                    pos {
                        (res shr shiftBy) eq op
                    }).activation { const.isPowOf2 },
                ucMul(
                    "post_pow2_vyper",
                    pos {
                        (res shr shiftBy) eq op
                    },
                    pos {
                        res le maxUintK
                    }).activation { const.isPowOf2 },
                ucMul(
                    "post_narrow",
                    pos {
                        res le maxUintK
                    }).activation { surelyNo256UnsignedMulOverflow },
                ucMul(
                    "pre1",
                    neg {
                        val opop = op OR (op bwAnd maxUintK)
                        lAnd(
                            !(opop eq zero),
                            (maxUintK / opop) lt cc
                        )
                    }),

                // To explain what's going on, let's say we want to see that `x * 10 <= 255`. This pattern appears as:
                // `x <= 25`, because `25 = 255 / 10` rounded down. In this story, `BOUND` will be 25, and what follows
                // is trying to figure out the width (`8` in this case).
                ucMul(
                    "pre2",
                    pos {
                        (op OR (op bwAnd maxUintK)) le c(BOUND).andDo {
                            val m = bound * const // in the example, 250
                            val width = m.bitLength() // which will be 8
                            // check that the bound is tight, i.e., (26 * 10 >= 256)
                            if (width % 8 == 0 && (bound + 1) * const >= twoToThe(width)) {
                                set(UNSIGNED_WIDTH, width)
                            } else {
                                null
                            }
                        }
                    }),

                // `res & 0xff == res & 0x1ff`, proves that there is no overflow multiplying by 2, as long as `op <= 0xff`
                ucMul(
                    "optimized_pow2",
                    pos {
                        eq(
                            res bwAnd fittingMaxUintK,
                            res bwAnd c(C1) { it.isPowOf2Minus1 }.andDo {
                                val bigMask = get(C1)!!
                                set(
                                    MAX_UINTK,
                                    lowOnes(bigMask.bitLength() - const.bitLength() + 1)
                                )
                            }
                        )
                    }).activation { const.isPowOf2 },

                // `op & 0x7f == op & 0xff`, proves that there is no overflow multiplying by 2, as long as `op <= 0xff`
                ucMul(
                    "optimized_pow2_arg",
                    pos {
                        eq(
                            op bwAnd fittingMaxUintK,
                            op bwAnd c(C1) { it.isPowOf2Minus1 }.andDo {
                                set(
                                    MAX_UINTK,
                                    lowOnes(get(C1)!!.bitLength() + const.bitLength() - 1)
                                )
                            }
                        )
                    }).activation { const.isPowOf2 }
            )
        }


        private fun scMul(name: String, vararg patterns: OverflowPattern<OverflowContext.Const>) =
            Recipe(name, signed = true, RecipeType.ConstMul, { true }, patterns.toList())

        // The proofs of these follow from their var by var counterparts.
        private val signedConstMul = with(PatternHelpers) {
            listOf(
                // this is a specific pattern for `op * minInt`.
                scMul(
                    "post_full_minInt",
                    pos {
                        lOr(
                            op eq zero,
                            cc eq (res sDiv op)
                        )
                    },
                    pos {
                        zero sLe op
                    }).activation { const == MIN_EVM_INT256_2S_COMPLEMENT },
                scMul(
                    "post_full1",
                    pos {
                        lOr(
                            op eq zero,
                            cc eq (res sDiv op)
                        )
                    }).activation { const != MIN_EVM_INT256_2S_COMPLEMENT },
                scMul(
                    "post_full2",
                    pos {
                        op eq (res sDiv cc)
                    },
                    neg {
                        op eq c(MIN_EVM_INT256_2S_COMPLEMENT)
                    }).activation { const.from2s(modZ256) < BigInteger.ZERO },
                scMul(
                    "post_full_simple",
                    pos {
                        (op eq (res sDiv cc))
                    }).activation { const != MINUS_ONE },
                scMul(
                    "post_full_vyper1",
                    pos {
                        lOr(
                            op eq zero,
                            cc eq (res sDiv op)
                        ).letIf(const == MIN_EVM_INT256_2S_COMPLEMENT) {
                            lAnd(
                                it,
                                !(op eq c(MINUS_ONE))
                            )
                        }
                    }),
                scMul(
                    "full_minus1",
                    neg {
                        op eq c(MIN_EVM_INT256_2S_COMPLEMENT)
                    }).activation { const == MINUS_ONE },
                scMul(
                    "post_mid1",
                    pos {
                        lOr(
                            op eq zero,
                            cc eq ((extendBit signExtend res) sDiv op)
                        )
                    }),
                scMul(
                    "post_mid2",
                    pos {
                        op eq ((extendBit signExtend res) sDiv cc)
                    }),
                scMul(
                    "post_mid3",
                    pos {
                        val opop = extendBit signExtend op
                        lOr(
                            opop eq zero,
                            cc eq ((extendBit signExtend res) sDiv opop)
                        )
                    }),
                scMul(
                    "post_mid_vyper",
                    pos {
                        OR(
                            op eq (res sDiv cc),
                            lOr(
                                op eq zero,
                                cc eq (res sDiv op)
                            )
                        )
                    },
                    pos {
                        res eq (extendBit signExtend res)
                    }),
                scMul(
                    "post_narrow",
                    pos {
                        res eq (extendBit signExtend res)
                    }).activation { surelyNo256SignedMulOverflow },

                scMul(
                    "pre1_pos",
                    // const is positive here
                    neg {
                        lAnd(
                            zero sLt op,
                            // A signed overflow check `op * const > 2^{width-1}-1`, can be written (as here) as:
                            //   `op > (2^{width-1}-1) / const`
                            // where `bound` is the constant equal to the rhs.
                            // so `bound * const` rounded up to a power of two should give `2^{width-1}`. Extracting
                            // `width` we can check that `bound` is indeed as expected.
                            (c(BOUND) { it.from2s() > BigInteger.ZERO } lt op).andDo {
                                val width = (bound * const).bitLength() + 1
                                if (width % 8 == 0 && bound == (twoToThe(width - 1) - 1) / const) {
                                    set(SIGNED_WIDTH, width)
                                } else {
                                    null
                                }
                            }
                        )
                    },
                    neg {
                        lAnd(
                            op sLt zero,
                            // this is an underflow check, because `const` is positive and `op` is negative.
                            // Thought of in mathint, there is underflow if:
                            //    `op * const < -2^{width-1}`
                            // Equivalently:
                            //    `op < -2^{width-1} / const
                            // where the rhs is the `bound` we see in this pattern.
                            // so `-bound * const` rounded up to a power of two should give `2^{width - 1}`, and the
                            // check here is to see that indeed this `width` matches the `bound`.
                            op sLt c(BOUND) { it.from2s() < BigInteger.ZERO }.andDo {
                                val m = -bound.from2s() * const
                                val width = m.bitLength().letIf(!m.isPowOf2) { it + 1 }
                                if (width % 8 == 0 && bound.from2s() == -twoToThe(width - 1) / const) {
                                    set(SIGNED_WIDTH, width)
                                } else {
                                    null
                                }
                            }
                        )
                    }).activation { const.from2s() > BigInteger.ZERO },

                scMul(
                    "pre1_neg",
                    // const is negative here
                    neg {
                        lAnd(
                            zero sLt op,
                            cc sLt (minSIntK sDiv op)
                        )
                    },
                    neg {
                        lAnd(
                            op sLt zero,
                            OR(
                                // this is an overflow check, because both `const` and `op` are negative.
                                // Thought of in mathint, there is overflow if:
                                //    `op * const > 2^{width-1}-1`
                                // Equivalently:
                                //    `op < (2^{width-1}-1) / const
                                // where the rhs is the `bound` we see in this pattern.
                                // so `bound * const` rounded up to a power of two should give `2^{width - 1}`.
                                op sLt c(BOUND) { it.from2s() < BigInteger.ZERO }.andDo {
                                    val width = (bound.from2s() * const.from2s()).bitLength() + 1
                                    if (width % 8 == 0 && bound.from2s() == (twoToThe(width - 1) - 1) / const.from2s()) {
                                        set(SIGNED_WIDTH, width)
                                    } else {
                                        null
                                    }
                                },
                                // the very special case of multiplying by the minInt of this width. In this case
                                // any negative `op` will cause an overflow.
                                op sLt zero.andDo {
                                    val m = -const.from2s()
                                    runIf(m.isPowOf2) {
                                        set(SIGNED_WIDTH, m.bitLength())
                                    }
                                }
                            )
                        )
                    }).activation { const.from2s() < BigInteger.ZERO },

                scMul(
                    "pre2_pos",
                    // const is positive
                    neg {
                        lAnd(
                            zero sLt op,
                            (maxSIntK / op) lt cc
                        )
                    },
                    neg {
                        lAnd(
                            op sLt zero,
                            // this is an underflow check, because `const` is positive and `op` is negative.
                            // Thought of in mathint, there is underflow if:
                            //    `op * const < -2^{width-1}`
                            // Equivalently:
                            //    `op < 2^{width-1} / -const
                            // where the rhs is the `bound` we see in this pattern.
                            // so `bound * -const` rounded up to a power of two should give `2^{width-1}`.
                            op sLt c(BOUND).onlyIf { bound.from2s() < BigInteger.ZERO }
                                .andDo {
                                    val m = bound.from2s() * -const
                                    val width = m.bitLength().letIf(!m.isPowOf2) { it + 1 }
                                    if (width % 8 == 0 && bound.from2s() == twoToThe(width - 1) / -const) {
                                        set(SIGNED_WIDTH, width)
                                    } else {
                                        null
                                    }
                                }
                        )
                    }).activation { const.from2s() > BigInteger.ZERO },

                scMul(
                    "pre2_neg",
                    neg {
                        lAnd(
                            zero sLt op,
                            cc sLt (minSIntK sDiv op)
                        )
                    },
                    neg {
                        lAnd(
                            op sLt zero,
                            cc sLt (maxSIntK sDiv op)
                        )
                    }).activation { const.from2s() < BigInteger.ZERO },
                scMul(
                    "pre_minInt",
                    neg {
                        lAnd(
                            zero sLt op,
                            minSIntK.onlyIf { const.from2s() == minSignedValueOfBitwidth(signedWidth!!) } sLt
                                (minSIntK sDiv op)
                        )
                    },
                    pos {
                        zero sLe op
                    }),
            )
        }


        private val binaryMul = unsignedBinaryMul + signedBinaryMul
        private val binaryAdd = unsignedBinaryAdd + signedBinaryAdd
        private val binarySub = unsignedBinarySub + signedBinarySub
        private val constMul = unsignedConstMul + signedConstMul

        fun <T : OverflowContext> recipesOf(context: T): List<Recipe<T>> =
            when (context) {
                is OverflowContext.Const -> constMul
                is OverflowContext.Binary ->
                    when (context.expr) {
                        is TACExpr.Vec.Mul -> binaryMul
                        is TACExpr.Vec.Add -> binaryAdd
                        is TACExpr.BinOp.Sub -> binarySub
                        else -> `impossible!`
                    }

                else -> `impossible!`
            }.uncheckedAs<List<Recipe<T>>>()
                .filter { it.shouldActivate(context) }
    }
}
