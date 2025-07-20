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

package wasm.host.soroban

import net.jqwik.api.*
import net.jqwik.api.constraints.*
import net.jqwik.kotlin.api.*
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import wasm.*
import wasm.host.soroban.SorobanImport.Buf
import wasm.host.soroban.SorobanImport.IntType
import wasm.host.soroban.Val.Tag.*
import wasm.wat.*
import java.math.BigInteger

class IntModuleTest : SorobanTestFixture() {
    @Target(AnnotationTarget.VALUE_PARAMETER)
    @ForAll
    @BigRange(min = "0", max = "115792089237316195423570985008687907853269984665640564039457584007913129639935")
    annotation class AllInt256

    //AllInt256Signed
    @Target(AnnotationTarget.VALUE_PARAMETER)
    @ForAll
    @BigRange(
        min = "-57896044618658097711785492504343953926634992332820282019728792003956564819968",
        max = "57896044618658097711785492504343953926634992332820282019728792003956564819967"
    )
    annotation class AllInt256Signed

    @Target(AnnotationTarget.VALUE_PARAMETER)
    @ForAll
    @BigRange(min = "0", max = "340282366920938463463374607431768211455")
    annotation class AllInt128

    @Target(AnnotationTarget.VALUE_PARAMETER)
    @ForAll
    @BigRange(min = "0", max = "18446744073709551615")
    annotation class AllInt64

    private val INT256_MIN_SIGNED = BigInteger.TWO.pow(255).negate()
    private val INT256_MAX_SIGNED = BigInteger.TWO.pow(255) - BigInteger.ONE

    private val MASK64 = BigInteger.TWO.pow(64) - BigInteger.ONE
    private val MASK256 = BigInteger.TWO.pow(256) - BigInteger.ONE
    private fun BigInteger.toULong() = toLong().toULong()

    private fun BigInteger.hi_hi() = ((this shr 192) and MASK64).toULong()
    private fun BigInteger.hi_lo() = ((this shr 128) and MASK64).toULong()
    private fun BigInteger.lo_hi() = ((this shr 64) and MASK64).toULong()
    private fun BigInteger.lo_lo() = (this and MASK64).toULong()

    private fun BigInteger.hi_hi_s() = ((this shr 192) and MASK64).toLong()
    private fun BigInteger.lo_hi_s() = ((this shr 64) and MASK64).toLong()

    private fun BigInteger.to32Bytes() = toByteArray().let {
        when {
            it.size <= 32 -> ByteArray(32).also { buf -> it.copyInto(buf, 32 - it.size) }
            it.size == 33 && it[0] == 0.toByte() -> it.drop(1).toByteArray()
            else -> error("Value $this is too large to fit in 32 bytes")
        }
    }

    context(WatBodyBuilder)
    fun BigInteger.toU256Object() = IntType.obj_from_u256_pieces(u64(hi_hi()), u64(hi_lo()), u64(lo_hi()), u64(lo_lo()))
    context(WatBodyBuilder)
    fun BigInteger.toI256Object() = IntType.obj_from_i256_pieces(i64(hi_hi_s()), u64(hi_lo()), u64(lo_hi()), u64(lo_lo()))
    context(WatBodyBuilder)
    fun BigInteger.toU128Object() = IntType.obj_from_u128_pieces(u64(lo_hi()), u64(lo_lo()))
    context(WatBodyBuilder)
    fun BigInteger.toI128Object() = IntType.obj_from_i128_pieces(i64(lo_hi_s()), u64(lo_lo()))
    context(WatBodyBuilder)
    fun BigInteger.toU64Object() = IntType.obj_from_u64(u64(this.toULong()))
    context(WatBodyBuilder)
    fun BigInteger.toI64Object() = IntType.obj_from_i64(i64(this.toLong()))

    context(WatBodyBuilder)
    infix fun WatExpr<i64>.eq_u256(i: BigInteger): WatExpr<i32> {
        val result = funcBuilder.local(i32)
        ite(
            hasTag(U256Small),
            then = {
                result.set(decode(U256Small) eq i64(i.toLong()))
            },
            orElse = {
                result.set(
                    (IntType.obj_to_u256_hi_hi(this@WatExpr) eq u64(i.hi_hi())) and
                    (IntType.obj_to_u256_hi_lo(this@WatExpr) eq u64(i.hi_lo())) and
                    (IntType.obj_to_u256_lo_hi(this@WatExpr) eq u64(i.lo_hi())) and
                    (IntType.obj_to_u256_lo_lo(this@WatExpr) eq u64(i.lo_lo()))
                )
            }
        )
        return result
    }

    context(WatBodyBuilder)
    infix fun WatExpr<i64>.eq_i256(i: BigInteger): WatExpr<i32> {
        val result = funcBuilder.local(i32)
        ite(
            hasTag(I256Small),
            then = {
                result.set(decode(I256Small) eq i64(i.toLong()))
            },
            orElse = {
                result.set(
                    (IntType.obj_to_i256_hi_hi(this@WatExpr) eq i64(i.hi_hi_s())) and
                    (IntType.obj_to_i256_hi_lo(this@WatExpr) eq u64(i.hi_lo())) and
                    (IntType.obj_to_i256_lo_hi(this@WatExpr) eq u64(i.lo_hi())) and
                    (IntType.obj_to_i256_lo_lo(this@WatExpr) eq u64(i.lo_lo()))
                )
            }
        )
        return result
    }

    context(WatBodyBuilder)
    infix fun WatExpr<i64>.eq_u128(i: BigInteger): WatExpr<i32> {
        val result = funcBuilder.local(i32)
        ite(
            hasTag(U128Small),
            then = {
                result.set(decode(U128Small) eq i64(i.toLong()))
            },
            orElse = {
                result.set(
                    (IntType.obj_to_u128_hi64(this@WatExpr) eq u64(i.lo_hi())) and
                    (IntType.obj_to_u128_lo64(this@WatExpr) eq u64(i.lo_lo()))
                )
            }
        )
        return result
    }

    context(WatBodyBuilder)
    infix fun WatExpr<i64>.eq_i128(i: BigInteger): WatExpr<i32> {
        val result = funcBuilder.local(i32)
        ite(
            hasTag(I128Small),
            then = {
                result.set(decode(I128Small) eq i64(i.toLong()))
            },
            orElse = {
                result.set(
                    (IntType.obj_to_i128_hi64(this@WatExpr) eq i64(i.lo_hi_s())) and
                    (IntType.obj_to_i128_lo64(this@WatExpr) eq u64(i.lo_lo()))
                )
            }
        )
        return result
    }

    context(WatBodyBuilder)
    infix fun WatExpr<i64>.eq_u64(i: BigInteger): WatExpr<i32> {
        val result = funcBuilder.local(i32)
        ite(
            hasTag(U64Small),
            then = {
                result.set(decode(U64Small) eq i64(i.toLong()))
            },
            orElse = {
                result.set(IntType.obj_to_u64(this@WatExpr) eq u64(i.toULong()))
            }
        )
        return result
    }

    context(WatBodyBuilder)
    infix fun WatExpr<i64>.eq_i64(i: BigInteger): WatExpr<i32> {
        val result = funcBuilder.local(i32)
        ite(
            hasTag(I64Small),
            then = {
                result.set(decode(I64Small) eq i64(i.toLong()))
            },
            orElse = {
                result.set(IntType.obj_to_i64(this@WatExpr) eq i64(i.toLong()))
            }
        )
        return result
    }

    @Property(tries = 32)
    fun `obj_from_u256_pieces`(@AllInt256 a: BigInteger) {
        assertTrue(verifyWasm {
            certoraAssert(a.toU256Object() eq_u256 a)
        })
    }

    @Property(tries = 32)
    fun `obj_from_i256_pieces`(@AllInt256 a: BigInteger) {
        assertTrue(verifyWasm {
            certoraAssert(a.toI256Object() eq_i256 a)
        })
    }

    @Property(tries = 32)
    fun `obj_from_u128_pieces`(@AllInt128 a: BigInteger) {
        assertTrue(verifyWasm {
            certoraAssert(a.toU128Object() eq_u128 a)
        })
    }

    @Property(tries = 32)
    fun `obj_from_i128_pieces`(@AllInt128 a: BigInteger) {
        assertTrue(verifyWasm {
            certoraAssert(a.toI128Object() eq_i128 a)
        })
    }

    @Property(tries = 32)
    fun `obj_from_u64`(@AllInt64 a: BigInteger) {
        assertTrue(verifyWasm {
            certoraAssert(a.toU64Object() eq_u64 a)
        })
    }

    @Property(tries = 32)
    fun `obj_from_i64`(@AllInt64 a: BigInteger) {
        assertTrue(verifyWasm {
            certoraAssert(a.toI64Object() eq_i64 a)
        })
    }


    @Property(tries = 32)
    fun `u256 equality`(@AllInt256 a: BigInteger, @AllInt256 b: BigInteger) {
        assertTrue(verifyWasm {
            val oa = a.toU256Object()
            val ob = b.toU256Object()

            if (a == b) {
                certoraAssert(oa valEq ob)
            } else {
                certoraAssert(not(oa valEq ob))
            }
        })
    }

    @Property(tries = 32)
    fun `i256 equality`(@AllInt256 a: BigInteger, @AllInt256 b: BigInteger) {
        assertTrue(verifyWasm {
            val oa = a.toI256Object()
            val ob = b.toI256Object()

            if (a == b) {
                certoraAssert(oa valEq ob)
            } else {
                certoraAssert(not(oa valEq ob))
            }
        })
    }

    @Property(tries = 32)
    fun `u128 equality`(@AllInt128 a: BigInteger, @AllInt128 b: BigInteger) {
        assertTrue(verifyWasm {
            val oa = a.toU128Object()
            val ob = b.toU128Object()

            if (a == b) {
                certoraAssert(oa valEq ob)
            } else {
                certoraAssert(not(oa valEq ob))
            }
        })
    }

    @Property(tries = 32)
    fun `i128 equality`(@AllInt128 a: BigInteger, @AllInt128 b: BigInteger) {
        assertTrue(verifyWasm {
            val oa = a.toI128Object()
            val ob = b.toI128Object()

            if (a == b) {
                certoraAssert(oa valEq ob)
            } else {
                certoraAssert(not(oa valEq ob))
            }
        })
    }

    @Property(tries = 32)
    fun `u64 equality`(@AllInt64 a: BigInteger, @AllInt64 b: BigInteger) {
        assertTrue(verifyWasm {
            val oa = a.toU64Object()
            val ob = b.toU64Object()

            if (a == b) {
                certoraAssert(oa valEq ob)
            } else {
                certoraAssert(not(oa valEq ob))
            }
        })
    }

    @Property(tries = 32)
    fun `i64 equality`(@AllInt64 a: BigInteger, @AllInt64 b: BigInteger) {
        assertTrue(verifyWasm {
            val oa = a.toI64Object()
            val ob = b.toI64Object()

            if (a == b) {
                certoraAssert(oa valEq ob)
            } else {
                certoraAssert(not(oa valEq ob))
            }
        })
    }

    @Test
    fun u256_val_from_be_bytes() {
        val a = BigInteger("1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef", 16)
        assertTrue(verifyWasm {
            val buf = a.to32Bytes().fold(Buf.new()) { acc, b -> Buf.push(acc, U32Val(b)) }
            val v = IntType.u256_val_from_be_bytes(buf)
            certoraAssert(v eq_u256 a)
        })
    }

    @Test
    fun u256_val_to_be_bytes() {
        val a = BigInteger("1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef", 16)
        assertTrue(verifyWasm {
            val buf = IntType.u256_val_to_be_bytes(a.toU256Object())
            a.to32Bytes().forEachIndexed { i, b ->
                certoraAssert(Buf.get(buf, U32Val(i)) eq U32Val(b))
            }
        })
    }

    @Property(tries = 32)
    fun `u256_add`(@AllInt256 a: BigInteger, @AllInt256 b: BigInteger) {
        val ab = a + b
        if (ab <= MASK256) {
            assertTrue(verifyWasm {
                val v = IntType.u256_add(a.toU256Object(), b.toU256Object())
                certoraAssert(v eq_u256 ab)
            })
        } else {
            // should overflow
            assertFalse(verifyWasm {
                IntType.u256_add(a.toU256Object(), b.toU256Object())
            })
        }
    }

    @Property(tries = 32)
    fun `i256_add`(@AllInt256Signed a: BigInteger, @AllInt256Signed b: BigInteger) {
        val ab = a + b
        if (ab in INT256_MIN_SIGNED..INT256_MAX_SIGNED) {
            assertTrue(verifyWasm {
                val v = IntType.i256_add(a.toI256Object(), b.toI256Object())
                certoraAssert(v eq_i256 (ab and MASK256))
            })
        } else {
            // should overflow
            assertFalse(verifyWasm {
                IntType.i256_add(a.toI256Object(), b.toI256Object())
            })
        }
    }

    @Property(tries = 32)
    fun `u256_sub`(@AllInt256 a: BigInteger, @AllInt256 b: BigInteger) {
        val ab = a - b
        if (ab >= BigInteger.ZERO) {
            assertTrue(verifyWasm {
                val v = IntType.u256_sub(a.toU256Object(), b.toU256Object())
                certoraAssert(v eq_u256 ab)
            })
        } else {
            // should underflow
            assertFalse(verifyWasm {
                IntType.u256_sub(a.toU256Object(), b.toU256Object())
            })
        }
    }

    @Property(tries = 32)
    fun `i256_sub`(@AllInt256Signed a: BigInteger, @AllInt256Signed b: BigInteger) {
        val ab = a - b
        if (ab in INT256_MIN_SIGNED..INT256_MAX_SIGNED) {
            assertTrue(verifyWasm {
                val v = IntType.i256_sub(a.toI256Object(), b.toI256Object())
                certoraAssert(v eq_i256 (ab and MASK256))
            })
        } else {
            // should underflow
            assertFalse(verifyWasm {
                IntType.i256_sub(a.toI256Object(), b.toI256Object())
            })
        }
    }

    @Property(tries = 32)
    fun `u256_mul`(@AllInt256 a: BigInteger, @AllInt256 b: BigInteger) {
        val ab = a * b
        if (ab <= MASK256) {
            assertTrue(verifyWasm {
                val v = IntType.u256_mul(a.toU256Object(), b.toU256Object())
                certoraAssert(v eq_u256 ab)
            })
        } else {
            // should overflow
            assertFalse(verifyWasm {
                IntType.u256_mul(a.toU256Object(), b.toU256Object())
            })
        }
    }

    @Property(tries = 32)
    fun `i256_mul`(@AllInt256Signed a: BigInteger, @AllInt256Signed b: BigInteger) {
        val ab = a * b
        if (ab in INT256_MIN_SIGNED..INT256_MAX_SIGNED) {
            assertTrue(verifyWasm {
                val v = IntType.i256_mul(a.toI256Object(), b.toI256Object())
                certoraAssert(v eq_i256 (ab and MASK256))
            })
        } else {
            // should overflow
            assertFalse(verifyWasm {
                IntType.i256_mul(a.toI256Object(), b.toI256Object())
            })
        }
    }

    @Property(tries = 32)
    fun `u256_div`(@AllInt256 a: BigInteger, @AllInt256 b: BigInteger) {
        if (b != BigInteger.ZERO) {
            val ab = a / b
            assertTrue(verifyWasm {
                val v = IntType.u256_div(a.toU256Object(), b.toU256Object())
                certoraAssert(v eq_u256 ab)
            })
        } else {
            // should trap
            assertFalse(verifyWasm {
                IntType.u256_div(a.toU256Object(), b.toU256Object())
            })
        }
    }

    @Property(tries = 32)
    fun `i256_div`(@AllInt256Signed a: BigInteger, @AllInt256Signed b: BigInteger) {
        if (b != BigInteger.ZERO) {
            val ab = a / b
            if (ab in INT256_MIN_SIGNED..INT256_MAX_SIGNED) {
                assertTrue(verifyWasm {
                    val v = IntType.i256_div(a.toI256Object(), b.toI256Object())
                    certoraAssert(v eq_i256 (ab and MASK256))
                })
            } else {
                // should overflow
                assertFalse(verifyWasm {
                    IntType.i256_div(a.toI256Object(), b.toI256Object())
                })
            }
        } else {
            // should trap
            assertFalse(verifyWasm {
                IntType.i256_div(a.toI256Object(), b.toI256Object())
            })
        }
    }

    @Property(tries = 32)
    fun `u256_rem_euclid`(@AllInt256 a: BigInteger, @AllInt256 b: BigInteger) {
        if (b != BigInteger.ZERO) {
            val ab = a % b
            assertTrue(verifyWasm {
                val v = IntType.u256_rem_euclid(a.toU256Object(), b.toU256Object())
                certoraAssert(v eq_u256 ab)
            })
        } else {
            // should trap
            assertFalse(verifyWasm {
                IntType.u256_rem_euclid(a.toU256Object(), b.toU256Object())
            })
        }
    }

    @Property(tries = 32)
    fun `i256_rem_euclid`(@AllInt256Signed a: BigInteger, @AllInt256Signed b: BigInteger) {
        if (b != BigInteger.ZERO) {
            val ab = a % b
            assertTrue(verifyWasm {
                val v = IntType.i256_rem_euclid(a.toI256Object(), b.toI256Object())
                certoraAssert(v eq_i256 ab)
            })
        } else {
            // should trap
            assertFalse(verifyWasm {
                IntType.i256_rem_euclid(a.toI256Object(), b.toI256Object())
            })
        }
    }

    @Property(tries = 32)
    fun `u256_shl`(@AllInt256 a: BigInteger, @ForAll @JqwikIntRange(min = 0, max = 257) b: Int) {
        if (b <= 256) {
            val ab = a shl b.toInt()
            assertTrue(verifyWasm {
                val v = IntType.u256_shl(a.toU256Object(), U32Val(b))
                certoraAssert(v eq_u256 ab)
            })
        } else {
            // should trap
            assertFalse(verifyWasm {
                IntType.u256_shl(a.toU256Object(), U32Val(b))
            })
        }
    }

    @Property(tries = 32)
    fun `i256_shl`(@AllInt256Signed a: BigInteger, @ForAll @JqwikIntRange(min = 0, max = 257) b: Int) {
        if (b <= 256) {
            val ab = a shl b.toInt()
            assertTrue(verifyWasm {
                val v = IntType.i256_shl(a.toI256Object(), U32Val(b))
                certoraAssert(v eq_i256 ab)
            })
        } else {
            // should trap
            assertFalse(verifyWasm {
                IntType.i256_shl(a.toI256Object(), U32Val(b))
            })
        }
    }

    @Property(tries = 32)
    fun `u256_shr`(@AllInt256 a: BigInteger, @ForAll @JqwikIntRange(min = 0, max = 257) b: Int) {
        if (b <= 256) {
            val ab = a shr b.toInt()
            assertTrue(verifyWasm {
                val v = IntType.u256_shr(a.toU256Object(), U32Val(b))
                certoraAssert(v eq_u256 ab)
            })
        } else {
            // should trap
            assertFalse(verifyWasm {
                IntType.u256_shr(a.toU256Object(), U32Val(b))
            })
        }
    }

    @Property(tries = 32)
    fun `i256_shr`(@AllInt256Signed a: BigInteger, @ForAll @JqwikIntRange(min = 0, max = 257) b: Int) {
        if (b <= 255) {
            val ab = a shr b.toInt()
            assertTrue(verifyWasm {
                val v = IntType.i256_shr(a.toI256Object(), U32Val(b))
                certoraAssert(v eq_i256 ab)
            })
        } else {
            // should trap
            assertFalse(verifyWasm {
                IntType.i256_shr(a.toI256Object(), U32Val(b))
            })
        }
    }

    @Test
    fun `nondet int not affected by allocated int`() {
        assertTrue(verifyWasm(
            watFunc("entry", i64) { nondetObj ->
                val nondetObjValueBefore = IntType.obj_to_i64(nondetObj)
                IntType.obj_from_i64(i64(42))
                val nondetObjValueAfter = IntType.obj_to_i64(nondetObj)
                certoraAssert(nondetObjValueBefore eq nondetObjValueAfter)
            },
            "entry",
            assumeNoTraps = true
        ))
    }

    @Test
    fun `nondet u64 is in bounds`() {
        assertTrue(verifyWasm(
            watFunc("entry", i64) { nondetObj ->
                val v = IntType.obj_to_u64(nondetObj)
                certoraAssert(v le u64(ULong.MAX_VALUE))
            },
            "entry",
            assumeNoTraps = true
        ))
    }

    @Test
    fun `nondet i64 is in bounds`() {
        assertTrue(verifyWasm(
            watFunc("entry", i64) { nondetObj ->
                val v = IntType.obj_to_i64(nondetObj)
                certoraAssert(v.to(u64) le u64(ULong.MAX_VALUE))
            },
            "entry",
            assumeNoTraps = true
        ))
    }
}
