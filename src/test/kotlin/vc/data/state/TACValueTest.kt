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

@file:OptIn(ExperimentalSerializationApi::class)

package vc.data.state

import evm.EVM_WORD_SIZE
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import verifier.sampling.TACValueGenerator
import java.math.BigInteger

class TACValueTest {
    @Suppress("JSON_FORMAT_REDUNDANT")
    @Test
    fun testSerializationRoundTripWordMap() {
        val num = BigInteger.valueOf(12344556789)
        val idx = TACValue.PrimitiveValue.Integer(13243)
        val biNum = TACValue.PrimitiveValue.Integer(num)
        val wm0 = TACValue.MappingValue.KVMapping.WordMap()

        val wm1 = wm0.store(idx, biNum) as TACValue.MappingValue.KVMapping.WordMap
        val ser = Json {
            allowStructuredMapKeys = true
        }.encodeToString(wm1)

        val des = Json {
            allowStructuredMapKeys = true
        }.decodeFromString<TACValue.MappingValue.KVMapping.WordMap>(ser)
        Assertions.assertEquals(wm1, des)
    }

    @Test
    fun testSerializationRoundTripByteMap() {
        val num0 = TACValue.PrimitiveValue.Integer(0x11)
        val idx0 = TACValue.PrimitiveValue.Integer(0)

        val bm0 = TACValue.MappingValue.KVMapping.ByteMap()

        val bm1 = bm0.store(idx0, num0) as TACValue.MappingValue.KVMapping.ByteMap
        val ser = Json.encodeToString(bm1)
        println(ser)

        val des = Json.decodeFromString<TACValue.MappingValue.KVMapping.ByteMap>(ser)
        Assertions.assertEquals(bm1, des)
    }

    @Test
    fun testWordMapRoundTrip() {
        val num = BigInteger.valueOf(12344556789)
        val idx = TACValue.PrimitiveValue.Integer(13243)
        val biNum = TACValue.PrimitiveValue.Integer(num)
        val wm0 = TACValue.MappingValue.KVMapping.WordMap()

        val wm1 = wm0.store(idx, biNum) as TACValue.MappingValue.KVMapping.WordMap

        val res = wm1.select(idx, false, MockRandomGenerator)
        Assertions.assertEquals(num, (res.first as TACValue.PrimitiveValue.Integer).value)
        val value = wm1[idx] as TACValue.PrimitiveValue.Integer
        Assertions.assertEquals(num, value.value)
    }

    @Test
    fun testWordMapWithInitialValue() {
        val defValue = TACValue.PrimitiveValue.Integer(1)
        val bm = TACValue.MappingValue.KVMapping.WordMap(initialValue = defValue)

        val res0 = bm.select(TACValue.PrimitiveValue.Integer(0), false, MockRandomGenerator)
        Assertions.assertEquals(defValue, res0.first)
        Assertions.assertNull(res0.second)

        val res1 = bm.select(TACValue.PrimitiveValue.Integer(1), false, MockRandomGenerator)
        Assertions.assertEquals(defValue, res1.first)
        Assertions.assertNull(res1.second)

        val res2 = bm.select(TACValue.PrimitiveValue.Integer(999), false, MockRandomGenerator)
        Assertions.assertEquals(defValue, res2.first)
        Assertions.assertNull(res2.second)
    }

    @Test
    fun testWordMapWithRandomOracle() {
        val bm = TACValue.MappingValue.KVMapping.WordMap()

        val offset = TACValue.PrimitiveValue.Integer(10)

        val res0 = bm.select(offset, false, MockRandomGenerator)
        Assertions.assertEquals(TACValue.Uninitialized, res0.first)
        Assertions.assertNull(res0.second)

        val expectedVal = TACValue.PrimitiveValue.Integer(BigInteger.valueOf(12))
        val mock = mockk<TACValueGenerator>()
        every { mock.getUnsignedRandomBit256(any()) } returns expectedVal
        val res1 = bm.select(offset, true, mock)
        Assertions.assertEquals(expectedVal, res1.first)
        Assertions.assertNotNull(res1.second)

        val res2 = (res1.second as TACValue.MappingValue.KVMapping.WordMap).select(offset, false, MockRandomGenerator)
        Assertions.assertEquals(expectedVal, res2.first)
        Assertions.assertNull(res2.second)
    }

    @Test
    fun testByteMapRoundTrip() {
        val num = BigInteger.valueOf(12344556789)
        val idx = TACValue.PrimitiveValue.Integer(13243)
        val biNum = TACValue.PrimitiveValue.Integer(num)
        val bm0 = TACValue.MappingValue.KVMapping.ByteMap()

        val bm1 = bm0.store(idx, biNum)

        val res = bm1.select(idx, false, MockRandomGenerator)
        Assertions.assertEquals(num, (res.first as TACValue.PrimitiveValue.Integer).value)
    }

    @Test
    fun testByteMapOverlapping() {
        val num0 = BigInteger.valueOf(0xbbccdd)
        val idx0 = TACValue.PrimitiveValue.Integer(BigInteger.valueOf(100))
        val biNum0 = TACValue.PrimitiveValue.Integer(num0)
        val num1 = BigInteger.valueOf(0xaa)
        val idx1 = TACValue.PrimitiveValue.Integer(BigInteger.valueOf(97))
        val biNum1 = TACValue.PrimitiveValue.Integer(num1)

        val bm0 = TACValue.MappingValue.KVMapping.ByteMap()
        val bm1 = bm0.store(idx0, biNum0)
        val bm2 = bm1.store(idx1, biNum1)

        val res0 = bm2.select(idx0, false, MockRandomGenerator)
        val res1 = bm2.select(idx1, false, MockRandomGenerator)
        Assertions.assertEquals(BigInteger.valueOf(0xaabbccdd), (res0.first as TACValue.PrimitiveValue.Integer).value)
        Assertions.assertEquals(num1, (res1.first as TACValue.PrimitiveValue.Integer).value)
    }

    @Test
    fun testByteMapLongStore() {
        val num0 = BigInteger.valueOf(0x0011)
        val idx0 = TACValue.PrimitiveValue.Integer(0)
        val biNum0 = TACValue.PrimitiveValue.Integer(num0)
        val bm00 = TACValue.MappingValue.KVMapping.ByteMap()
        val bm01 = bm00.store(idx0, biNum0) as TACValue.MappingValue.KVMapping.ByteMap

        val num1 = BigInteger.valueOf(0x2222)
        val idx1 = TACValue.PrimitiveValue.Integer(BigInteger.valueOf(110))
        val biNum1 = TACValue.PrimitiveValue.Integer(num1)
        val bm10 = TACValue.MappingValue.KVMapping.ByteMap()
        val bm11 = bm10.store(idx1, biNum1) as TACValue.MappingValue.KVMapping.ByteMap

        val bm2 = bm01.longStore(bm11,
            TACValue.PrimitiveValue.Integer(BigInteger.valueOf(140)),
            TACValue.PrimitiveValue.Integer(EVM_WORD_SIZE - BigInteger.valueOf(3)),
            TACValue.PrimitiveValue.Integer(BigInteger.TWO))
        val res = bm2.select(idx0, false, MockRandomGenerator)
        Assertions.assertEquals(BigInteger.valueOf(0x222211), (res.first as TACValue.PrimitiveValue.Integer).value)
    }

    @Test
    fun testByteMapDefaultZeros() {
        val num0 = TACValue.PrimitiveValue.Integer(0x11)
        val idx0 = TACValue.PrimitiveValue.Integer(0)
        val num1 = TACValue.PrimitiveValue.Integer(BigInteger.valueOf(0xdeadbabe0000))
        val idx1 = TACValue.PrimitiveValue.Integer(BigInteger.valueOf(36))

        val bm0 = TACValue.MappingValue.KVMapping.ByteMap()
        val bm1 = bm0.store(idx0, num0)
        val bm2 = bm1.store(idx1, num1)

        val idx2 = TACValue.PrimitiveValue.Integer(BigInteger.valueOf(34))
        val res = bm2.select(idx2, false, MockRandomGenerator)
        Assertions.assertEquals(BigInteger.valueOf(0xdeadbabe), (res.first as TACValue.PrimitiveValue.Integer).value)

        val idx3 = TACValue.PrimitiveValue.Integer(BigInteger.valueOf(37))
        val res1 = bm2.select(idx3, false, MockRandomGenerator)
        Assertions.assertEquals(BigInteger.valueOf(0xdeadbabe000000), (res1.first as TACValue.PrimitiveValue.Integer).value)

        val idx4 = TACValue.PrimitiveValue.Integer(BigInteger.valueOf(100))
        val res2 = bm2.select(idx4, false, MockRandomGenerator)
        Assertions.assertEquals(TACValue.Uninitialized, res2.first)

    }

    @Test
    fun testByteMapWithInitialValue() {
        val defValue = TACValue.PrimitiveValue.Integer(1)
        val bm = TACValue.MappingValue.KVMapping.ByteMap(initialValue = defValue)

        val res0 = bm.select(TACValue.PrimitiveValue.Integer(0), false, MockRandomGenerator)
        Assertions.assertEquals(defValue, res0.first)
        Assertions.assertNull(res0.second)

        val res1 = bm.select(TACValue.PrimitiveValue.Integer(1), false, MockRandomGenerator)
        Assertions.assertEquals(defValue, res1.first)
        Assertions.assertNull(res1.second)

        val res2 = bm.select(TACValue.PrimitiveValue.Integer(999), false, MockRandomGenerator)
        Assertions.assertEquals(defValue, res2.first)
        Assertions.assertNull(res2.second)
    }

    @Test
    fun testByteMapWithRandomOracle() {
        val bm = TACValue.MappingValue.KVMapping.ByteMap()

        val offset = TACValue.PrimitiveValue.Integer(10)

        val res0 = bm.select(offset, false, MockRandomGenerator)
        Assertions.assertEquals(TACValue.Uninitialized, res0.first)
        Assertions.assertNull(res0.second)

        val expectedVal = TACValue.PrimitiveValue.Integer(BigInteger.valueOf(12))
        val mock = mockk<TACValueGenerator>()
        every { mock.getUnsignedRandomBit256(any()) } returns expectedVal
        val res1 = bm.select(offset, true, mock)
        Assertions.assertEquals(expectedVal, res1.first)
        Assertions.assertNotNull(res1.second)

        val res2 = (res1.second as TACValue.MappingValue.KVMapping.ByteMap).select(offset, false, MockRandomGenerator)
        Assertions.assertEquals(expectedVal, res2.first)
        Assertions.assertNull(res2.second)
    }

    @Test
    fun testByteMapWithBound() {
        val bm = TACValue.MappingValue.KVMapping.ByteMap(
            bound = TACValue.MappingValue.KVMapping.Bound(
                TACValue.PrimitiveValue.Integer(BigInteger.valueOf(100)),
                TACValue.PrimitiveValue.Integer(10)
            )
        )

        val res0 = bm.select(TACValue.PrimitiveValue.Integer(BigInteger.valueOf(150)), false, MockRandomGenerator)
        Assertions.assertEquals(TACValue.PrimitiveValue.Integer(10), res0.first)
        Assertions.assertNull(res0.second)

        val bm1 = bm.store(TACValue.PrimitiveValue.Integer(BigInteger.valueOf(50)),
            TACValue.PrimitiveValue.Integer(BigInteger.TWO)) as TACValue.MappingValue.KVMapping.ByteMap
        val res1 = bm1.select(TACValue.PrimitiveValue.Integer(BigInteger.valueOf(50)), false, MockRandomGenerator)
        Assertions.assertEquals(TACValue.PrimitiveValue.Integer(BigInteger.TWO), res1.first)

        Assertions.assertThrowsExactly(IllegalStateException::class.java) {
            bm1.store(TACValue.PrimitiveValue.Integer(BigInteger.valueOf(120)), TACValue.PrimitiveValue.Integer(BigInteger.TWO))
        }

        Assertions.assertThrowsExactly(IllegalStateException::class.java) {
            bm1.store(TACValue.PrimitiveValue.Integer(BigInteger.valueOf(99)), TACValue.PrimitiveValue.Integer(BigInteger.TWO))
        }

        Assertions.assertThrowsExactly(IllegalStateException::class.java) {
            bm1.longStore(
                bm, TACValue.PrimitiveValue.Integer(BigInteger.valueOf(150)),
                TACValue.PrimitiveValue.Integer(BigInteger.valueOf(99)), TACValue.PrimitiveValue.Integer(BigInteger.valueOf(50)))
        }
    }

    @Test
    fun testByteMapGetEntries() {
        val num0 = TACValue.PrimitiveValue.Integer(0x11)
        val num1 = TACValue.PrimitiveValue.Integer(BigInteger.valueOf(0x1))
        val idx0 = TACValue.PrimitiveValue.Integer(0)
        val idx1 = TACValue.PrimitiveValue.Integer(BigInteger.valueOf(36))
        val idx2 = TACValue.PrimitiveValue.Integer(BigInteger.valueOf(78))


        val bm0 = TACValue.MappingValue.KVMapping.ByteMap()
        bm0.select(idx0, false, MockRandomGenerator)
        Assertions.assertEquals(mapOf<TACValue.PrimitiveValue.Integer, TACValue>(), bm0.getEntries().toMap())

        val bm1 = bm0.store(idx0, num0) as TACValue.MappingValue.KVMapping.ByteMap
        bm1.select(idx0, false, MockRandomGenerator)
        bm1.select(idx1, false, MockRandomGenerator)
        val bm2 = bm1.store(idx1, num1) as TACValue.MappingValue.KVMapping.ByteMap
        Assertions.assertEquals(mapOf<TACValue.PrimitiveValue.Integer, TACValue>(idx0 to num0), bm1.getEntries().toMap())
        Assertions.assertEquals(mapOf<TACValue.PrimitiveValue.Integer, TACValue>(idx0 to num0, idx1 to num1), bm2.getEntries().toMap())

        val res = bm2.select(idx2, true, MockRandomGenerator)
        val bm3 = res.second!!
        Assertions.assertEquals(mapOf<TACValue.PrimitiveValue.Integer, TACValue>(idx0 to num0, idx1 to num1), bm2.getEntries().toMap())
        Assertions.assertEquals(mapOf<TACValue.PrimitiveValue.Integer, TACValue>(idx0 to num0, idx1 to num1, idx2 to res.first), bm3.getEntries().toMap())

        val bm4 = bm0.store(idx1, num0) as TACValue.MappingValue.KVMapping.ByteMap
        val bm5 = bm3.longStore(bm4, TACValue.PrimitiveValue.Integer(BigInteger.valueOf(36)), idx2, TACValue.PrimitiveValue.Integer(BigInteger.valueOf(32)))
        Assertions.assertEquals(mapOf<TACValue.PrimitiveValue.Integer, TACValue>(idx0 to num0, idx1 to num1, idx2 to num0), bm5.getEntries().toMap())
    }

    companion object {
        val MockRandomGenerator = mockk<TACValueGenerator>()

        @JvmStatic
        @BeforeAll
        fun setUp() {
            val ret = TACValue.PrimitiveValue.Integer(1)
            every { MockRandomGenerator.getUnsignedRandomBit256(any()) } returns ret
        }
    }

}
