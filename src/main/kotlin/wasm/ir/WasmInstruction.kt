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

package wasm.ir

import com.certora.collect.*
import utils.*
import wasm.tokens.WasmTokens
import wasm.tokens.WasmTokens.ALIGN
import wasm.tokens.WasmTokens.BLOCK
import wasm.tokens.WasmTokens.BR
import wasm.tokens.WasmTokens.BR_IF
import wasm.tokens.WasmTokens.BR_TABLE
import wasm.tokens.WasmTokens.CALL
import wasm.tokens.WasmTokens.CALL_INDIRECT
import wasm.tokens.WasmTokens.DROP
import wasm.tokens.WasmTokens.ELSE
import wasm.tokens.WasmTokens.EQUAL
import wasm.tokens.WasmTokens.GLOBALGET
import wasm.tokens.WasmTokens.GLOBALSET
import wasm.tokens.WasmTokens.I32CONST
import wasm.tokens.WasmTokens.I64CONST
import wasm.tokens.WasmTokens.IF
import wasm.tokens.WasmTokens.LOCALGET
import wasm.tokens.WasmTokens.LOCALSET
import wasm.tokens.WasmTokens.LOCALTEE
import wasm.tokens.WasmTokens.LOOP
import wasm.tokens.WasmTokens.MEMORY_FILL
import wasm.tokens.WasmTokens.MEMORY_COPY
import wasm.tokens.WasmTokens.MEMORY_GROW
import wasm.tokens.WasmTokens.MEMORY_SIZE
import wasm.tokens.WasmTokens.NOP
import wasm.tokens.WasmTokens.OFFSET
import wasm.tokens.WasmTokens.RETURN
import wasm.tokens.WasmTokens.SELECT
import wasm.tokens.WasmTokens.UNREACHABLE
import wasm.tokens.WasmTokens.WASMCFG
import wasm.tokens.WasmTokens.WASMCFG_JUMP
import java.math.BigInteger


// TODO: CERT-5543 handle store / load with alignment argument
// TODO: CERT-5543 finish the rest: https://www.w3.org/TR/wasm-core-1/#memory-instructions%E2%91%A0
// NOTE: offset is optional. Default is 0.

sealed interface Op {
    val type: WasmPrimitiveType
}

sealed interface Binop: Op

sealed interface Unop: Op
@JvmInline
@Treapable
@KSerializable
value class WASMAddress(val value: Int): AmbiSerializable
@JvmInline
@Treapable
@KSerializable
value class WASMLocalIdx(val value: Int): AmbiSerializable {
    override fun toString() = value.toString()
}
@JvmInline
@Treapable
@KSerializable
value class WASMMemoryOffset(val value: Int): AmbiSerializable

// TODO: CERT-6019 add other ops from https://www.w3.org/TR/wasm-core-1/#numeric-instructions%E2%91%A0
enum class BinaryNumericOp(val token: String, override val type: WasmPrimitiveType) : Binop {
    I32ADD(WasmTokens.I32ADD, WasmPrimitiveType.I32),
    I64ADD(WasmTokens.I64ADD, WasmPrimitiveType.I64),
    I32SUB(WasmTokens.I32SUB, WasmPrimitiveType.I32),
    I64SUB(WasmTokens.I64SUB, WasmPrimitiveType.I64),
    I32MUL(WasmTokens.I32MUL, WasmPrimitiveType.I32),
    I64MUL(WasmTokens.I64MUL, WasmPrimitiveType.I64),
    I32DIVU(WasmTokens.I32DIVU, WasmPrimitiveType.I32),
    I64DIVU(WasmTokens.I64DIVU, WasmPrimitiveType.I64),
    I32DIVS(WasmTokens.I32DIVS, WasmPrimitiveType.I32),
    I64DIVS(WasmTokens.I64DIVS, WasmPrimitiveType.I64),
    I32REMU(WasmTokens.I32REMU, WasmPrimitiveType.I32),
    I64REMU(WasmTokens.I64REMU, WasmPrimitiveType.I64),
    I32REMS(WasmTokens.I32REMS, WasmPrimitiveType.I32),
    I64REMS(WasmTokens.I64REMS, WasmPrimitiveType.I64),
    I32AND(WasmTokens.I32AND, WasmPrimitiveType.I32),
    I64AND(WasmTokens.I64AND, WasmPrimitiveType.I64),
    I32OR(WasmTokens.I32OR, WasmPrimitiveType.I32),
    I64OR(WasmTokens.I64OR, WasmPrimitiveType.I64),
    I32XOR(WasmTokens.I32XOR, WasmPrimitiveType.I32),
    I64XOR(WasmTokens.I64XOR, WasmPrimitiveType.I64),
    I32SHL(WasmTokens.I32SHL, WasmPrimitiveType.I32),
    I64SHL(WasmTokens.I64SHL, WasmPrimitiveType.I64),
    I32SHRU(WasmTokens.I32SHRU, WasmPrimitiveType.I32),
    I64SHRU(WasmTokens.I64SHRU, WasmPrimitiveType.I64),
    I32SHRS(WasmTokens.I32SHRS, WasmPrimitiveType.I32),
    I64SHRS(WasmTokens.I64SHRS, WasmPrimitiveType.I64);

    override fun toString(): String = token

}

enum class BinaryComparisonOp(val token: String, override val type: WasmPrimitiveType): Binop {
    I32LTU(WasmTokens.I32LTU, WasmPrimitiveType.BOOL),
    I64LTU(WasmTokens.I64LTU, WasmPrimitiveType.BOOL),
    I32GTU(WasmTokens.I32GTU, WasmPrimitiveType.BOOL),
    I64GTU(WasmTokens.I64GTU, WasmPrimitiveType.BOOL),
    I32LTS(WasmTokens.I32LTS, WasmPrimitiveType.BOOL),
    I64LTS(WasmTokens.I64LTS, WasmPrimitiveType.BOOL),
    I32GTS(WasmTokens.I32GTS, WasmPrimitiveType.BOOL),
    I64GTS(WasmTokens.I64GTS, WasmPrimitiveType.BOOL),
    I32LEU(WasmTokens.I32LEU, WasmPrimitiveType.BOOL),
    I64LEU(WasmTokens.I64LEU, WasmPrimitiveType.BOOL),
    I32LES(WasmTokens.I32LES, WasmPrimitiveType.BOOL),
    I64LES(WasmTokens.I64LES, WasmPrimitiveType.BOOL),
    I32GEU(WasmTokens.I32GEU, WasmPrimitiveType.BOOL),
    I64GEU(WasmTokens.I64GEU, WasmPrimitiveType.BOOL),
    I32GES(WasmTokens.I32GES, WasmPrimitiveType.BOOL),
    I64GES(WasmTokens.I64GES, WasmPrimitiveType.BOOL),
    I32EQ(WasmTokens.I32EQ, WasmPrimitiveType.BOOL),
    I64EQ(WasmTokens.I64EQ, WasmPrimitiveType.BOOL),
    I32NE(WasmTokens.I32NE, WasmPrimitiveType.BOOL),
    I64NE(WasmTokens.I64NE, WasmPrimitiveType.BOOL);

    override fun toString(): String = token

}

enum class UnaryNumericOp(val token: String, override val type: WasmPrimitiveType): Unop {
    I32WRAP64(WasmTokens.I32WRAP64, WasmPrimitiveType.I32),
    I32_EXTEND8_S(WasmTokens.I32_EXTEND8_S, WasmPrimitiveType.I32),
    I32_EXTEND16_S(WasmTokens.I32_EXTEND16_S, WasmPrimitiveType.I32),
    I64_EXTEND8_S(WasmTokens.I64_EXTEND8_S, WasmPrimitiveType.I64),
    I64_EXTEND16_S(WasmTokens.I64_EXTEND16_S, WasmPrimitiveType.I64),
    I64_EXTEND32_S(WasmTokens.I64_EXTEND32_S, WasmPrimitiveType.I64),
    I64_EXTENDI32_U(WasmTokens.I64_EXTENDI32_U, WasmPrimitiveType.I64),
    I64_EXTENDI32_S(WasmTokens.I64_EXTENDI32_S, WasmPrimitiveType.I64),
    I32CLZ(WasmTokens.I32CLZ, WasmPrimitiveType.I32),
    I64CLZ(WasmTokens.I64CLZ, WasmPrimitiveType.I64);

    override fun toString(): String = token
}

enum class UnaryComparisonOp(val token: String, override val type: WasmPrimitiveType): Unop {
    I32EQZ(WasmTokens.I32EQZ, WasmPrimitiveType.BOOL),
    I64EQZ(WasmTokens.I64EQZ, WasmPrimitiveType.BOOL);

    override fun toString(): String = token

}

sealed interface MemoryOp: Op {
    override fun toString(): String
    val widthInBytes: Int
}

enum class LoadMemoryOp(val token: String, override val type: WasmPrimitiveType, override val widthInBytes: Int) : MemoryOp {
    I32LOAD(WasmTokens.I32LOAD, WasmPrimitiveType.I32, 4),
    I64LOAD(WasmTokens.I64LOAD, WasmPrimitiveType.I64, 8),
    F32LOAD(WasmTokens.F32LOAD, WasmPrimitiveType.F32, 4),
    F64LOAD(WasmTokens.F64LOAD, WasmPrimitiveType.F64, 8),
    I32LOAD8_S(WasmTokens.I32LOAD8_S, WasmPrimitiveType.I32, 1),
    I64LOAD8_S(WasmTokens.I64LOAD8_S, WasmPrimitiveType.I64, 1),
    I32LOAD8_U(WasmTokens.I32LOAD8_U, WasmPrimitiveType.I32, 1),
    I64LOAD8_U(WasmTokens.I64LOAD8_U, WasmPrimitiveType.I64, 1),
    I32LOAD16_S(WasmTokens.I32LOAD16_S, WasmPrimitiveType.I32, 2),
    I64LOAD16_S(WasmTokens.I64LOAD16_S, WasmPrimitiveType.I64, 2),
    I32LOAD16_U(WasmTokens.I32LOAD16_U, WasmPrimitiveType.I32, 2),
    I64LOAD16_U(WasmTokens.I64LOAD16_U, WasmPrimitiveType.I64, 2),
    I64LOAD32_S(WasmTokens.I64LOAD32_S, WasmPrimitiveType.I64, 4),
    I64LOAD32_U(WasmTokens.I64LOAD32_U, WasmPrimitiveType.I64, 4);

    override fun toString(): String = token
}

enum class StoreMemoryOp(val token: String, override val type: WasmPrimitiveType, override val widthInBytes: Int): MemoryOp {
    I32STORE(WasmTokens.I32STORE, WasmPrimitiveType.I32, 4),
    I64STORE(WasmTokens.I64STORE, WasmPrimitiveType.I64, 8),
    F32STORE(WasmTokens.F32STORE, WasmPrimitiveType.F32, 4),
    F64STORE(WasmTokens.F64STORE, WasmPrimitiveType.F64, 8),
    I32STORE8(WasmTokens.I32STORE8, WasmPrimitiveType.I32, 1),
    I64STORE8(WasmTokens.I64STORE8, WasmPrimitiveType.I64, 1),
    I32STORE16(WasmTokens.I32STORE16, WasmPrimitiveType.I32, 2),
    I64STORE16(WasmTokens.I64STORE16, WasmPrimitiveType.I64, 2),
    I64STORE32(WasmTokens.I64STORE32, WasmPrimitiveType.I64, 4);

    override fun toString(): String = token

}

/**
 * A [WasmCFGInstruction] is essentially a
 * wasm AST instruction, [WasmInstruction] with an associated successor arity which represents the number of
 * successor the instruction can have.
 * */
sealed interface WasmCFGInstruction : WasmInstruction {
    fun toWasmCfgString(): String {
        return WASMCFG + this.toString()
    }
}

/**
 * The AST representation of Wasm instructions.
 * */
// TODO: CERT-6020 not handling folded instructions right now https://www.w3.org/TR/wasm-core-1/#folded-instructions%E2%91%A0
sealed interface WasmInstruction {

    sealed class Control: WasmInstruction {

        data class Nop(val address: WASMAddress? = null) : Control(), WasmCFGInstruction {
            override fun toString(): String {
                return NOP
            }

            override fun toWasmCfgString(): String {
                return WASMCFG_JUMP
            }
        }

        // https://www.w3.org/TR/wasm-core-1/#valid-unreachable
        data class Unreachable(val address: WASMAddress? = null) : Control(), WasmCFGInstruction {
            override fun toString(): String {
                return UNREACHABLE
            }
        }

        data class Return(val address: WASMAddress? = null) : Control(), WasmCFGInstruction {
            override fun toString(): String {
                return RETURN
            }
        }

        data class Block(val instrs: List<WasmInstruction>, val label: WasmLabelAnnotation, val address: WASMAddress? = null) :
            Control() {

            override fun toString(): String {
                return BLOCK
            }
        }

        data class Loop(val instrs: List<WasmInstruction>, val label: WasmLabelAnnotation, val address: WASMAddress? = null) : Control() {
            override fun toString(): String {
                return LOOP
            }
        }

        data class Br(val label: Int, val address: WASMAddress? = null) : Control(), WasmCFGInstruction {
            override fun toString(): String {
                return "$BR $label"
            }

            override fun toWasmCfgString(): String {
                return WASMCFG_JUMP
            }
        }

        data class BrIf(val label: Int, val address: WASMAddress? = null) : Control(), WasmCFGInstruction {
            override fun toString(): String {
                return "$BR_IF $label"
            }

            override fun toWasmCfgString(): String {
                return "$WASMCFG$BR_IF $label"
            }
        }

        /**
         * Support for this wasm construct:
         *    `br_table 0 (;@6;) 1 (;@5;) 2 (;@4;) 3 (;@3;) 4 (;@2;) 0 (;@6;)`
         */
        data class BrTable(val labels: List<Int>, val address: WASMAddress? = null) : Control(), WasmCFGInstruction {
            override fun toString(): String {
                return "$BR_TABLE $labels"
            }

            override fun toWasmCfgString(): String {
                return "$WASMCFG$BR_TABLE $labels"
            }
        }

        data class Call(val funcId: WasmName, val address: WASMAddress? = null) : Control(), WasmCFGInstruction {
            override fun toString(): String {
                return "$CALL $funcId"
            }
        }

        /**
         * Here is a great tutorial on call_indirect:
         * https://developer.mozilla.org/en-US/docs/WebAssembly/Understanding_the_text_format#webassembly_tables
         * call_indirect $T0 (type $t0)
         * */
        data class CallIndirect(val tableId: WasmName, val type: WasmTypeUse, val address: WASMAddress? = null) : Control(), WasmCFGInstruction {
            override fun toString(): String {
                return "$CALL_INDIRECT $tableId $type"
            }
        }

        data class IfElse(
            val ifInstrs: List<WasmInstruction>,
            val elseInstrs: List<WasmInstruction>?,
            val label: WasmLabelAnnotation,
            val address: WASMAddress? = null
        ) : Control(), WasmCFGInstruction {
            override fun toString(): String {
                return "$IF/$ELSE"
            }
        }
    }

    sealed class Memory: WasmInstruction, WasmCFGInstruction {

        data class Load(val op: LoadMemoryOp, val offset: WASMMemoryOffset = WASMMemoryOffset(0), val align: Int = 0, val address: WASMAddress? = null) : Memory() {
            constructor(op: LoadMemoryOp, address: WASMAddress? = null) : this(op, WASMMemoryOffset(0), 0, address)

            override fun toString(): String {
                return "$op $OFFSET$EQUAL$offset $ALIGN$EQUAL$align"
            }
        }

        data class Store(val op: StoreMemoryOp, val offset: WASMMemoryOffset = WASMMemoryOffset(0), val align: Int = 0, val address: WASMAddress? = null) : Memory() {

            constructor(op: StoreMemoryOp, address: WASMAddress? = null) : this(op,  WASMMemoryOffset(0), 0, address)

            override fun toString(): String {
                return "$op $OFFSET$EQUAL$offset $ALIGN$EQUAL$align"
            }
        }

        /**
         *
         * https://developer.mozilla.org/en-US/docs/WebAssembly/Reference/Memory/Copy
         * The copy memory instruction copies data from one region of a memory to another.
         * The instruction does not return a value. If either the source or destination range is out of bounds, the instruction traps.
         *
         * NOTE: we are not supporting wasm's multi-memory but the syntax still enables this:
         * https://webassembly.github.io/spec/core/syntax/instructions.html#syntax-instr-memory
         * memory.copy is more like memmove than memcpy in how overlap is handled.
         *
         * Another useful doc: https://webassembly.github.io/bulk-memory-operations/core/_download/WebAssembly.pdf
         * */
        data class Copy(val toMemIdx: Int, val fromMemIdx: Int, val address: WASMAddress? = null) : Memory() {
            override fun toString(): String {
                return "$MEMORY_COPY $toMemIdx $fromMemIdx"
            }
        }

        /**
         *
         * https://developer.mozilla.org/en-US/docs/WebAssembly/Reference/Memory/Fill
         * The fill memory instruction sets all bytes in a memory region to a given byte.
         * The instruction returns no value. It traps (exception) if the indicated memory region is out of bounds.
         *
         * NOTE: we are not supporting wasm's multi-memory but the syntax is general and can allow this:
         * https://webassembly.github.io/spec/core/syntax/instructions.html#syntax-instr-memory
         * */
        data class Fill(val memIdx: Int, val address: WASMAddress? = null) : Memory() {
            override fun toString(): String {
                return "$MEMORY_FILL $memIdx"
            }
        }


        data class Size(val address: WASMAddress? = null) : Memory() {
            override fun toString(): String {
                return MEMORY_SIZE
            }
        }

        data class Grow(val address: WASMAddress? = null) : Memory() {
            override fun toString(): String {
                return MEMORY_GROW
            }
        }
    }

    sealed class Numeric: WasmInstruction, WasmCFGInstruction {

        sealed interface ConstIntInstruction {
            val constVal: BigInteger
        }


        data class I32Const(val num: I32Value, val address: WASMAddress? = null) : Numeric(), ConstIntInstruction {
            override fun toString(): String {
                return "$I32CONST $num"
            }

            override fun toWasmCfgString(): String {
                return WASMCFG + I32CONST + "$num"
            }

            override val constVal: BigInteger
                get() = num.v
        }

        data class I64Const(val num: I64Value, val address: WASMAddress? = null) : Numeric(), ConstIntInstruction {
            override fun toString(): String {
                return "$I64CONST $num"
            }

            override fun toWasmCfgString(): String {
                return WASMCFG + I64CONST + "$num"
            }

            override val constVal: BigInteger
                get() = num.v
        }

        // TODO: CERT-6021 may do the float ops later
        data class F32Const(val num: F32Value, val address: WASMAddress? = null) : Numeric() {
            override fun toString(): String {
                TODO("operator not supported")
            }

            override fun toWasmCfgString(): String {
                throw UnsupportedOperationException("operator not supported")
            }
        }

        data class F64Const(val num: F64Value, val address: WASMAddress? = null) : Numeric() {
            override fun toString(): String {
                TODO("operator not supported")
            }

            override fun toWasmCfgString(): String {
                throw UnsupportedOperationException("operator not supported")
            }
        }

        data class NumericUnary(val op: UnaryNumericOp, val address: WASMAddress? = null) : Numeric() {
            override fun toString(): String {
                return op.toString()
            }
        }

        data class NumericBinary(val op: BinaryNumericOp, val address: WASMAddress? = null) : Numeric() {
            override fun toString(): String {
                return op.toString()
            }
        }


        data class ComparisonUnary(val op: UnaryComparisonOp, val address: WASMAddress? = null) : Numeric() {
            override fun toString(): String {
                return op.toString()
            }
        }

        data class ComparisonBinary(val op: BinaryComparisonOp, val address: WASMAddress? = null) : Numeric() {
            override fun toString(): String {
                return op.toString()
            }
        }
    }

    /*
* This corresponds to Wasm's variable instructions as defined here:
* https://webassembly.github.io/spec/core/syntax/instructions.html#syntax-instr-variable
* */
    sealed class Variable: WasmInstruction, WasmCFGInstruction {

        /**
         * In the following data classes `x` parameter represents the index of the local or global as it appears
         * in the WAT file. For example, for this:
         *      `(global (;1;) i32 (i32.const 1048872))`
         *      `x` would be 1.
         * Similarly, in a function, that defines
         *      ```
         *      (func (;69;) (type 5) (param i32 i64) (result i64)
         *        (local i32 i64)
         *        ...
         *       ```,
         *       `x` would be 2 for the local i32 and 3 for the local i64 since counting
         *       of locals starts from 0 where 0 is the first `param`.
         * */

        data class LocalGet(val x: WASMLocalIdx, val address: WASMAddress? = null) : Variable() {
            override fun toString(): String {
                return "$LOCALGET $x"
            }
        }

        data class LocalSet(val x: WASMLocalIdx, val address: WASMAddress? = null) : Variable() {
            override fun toString(): String {
                return "$LOCALSET $x"
            }
        }

        data class LocalTee(val x: WASMLocalIdx, val address: WASMAddress? = null) : Variable() {
            override fun toString(): String {
                return "$LOCALTEE $x"
            }
        }

        data class GlobalGet(val x: String, val address: WASMAddress? = null) : Variable() {
            override fun toString(): String {
                return "$GLOBALGET $x"
            }
        }

        data class GlobalSet(val x: String, val address: WASMAddress? = null) : Variable() {
            override fun toString(): String {
                return "$GLOBALSET $x"
            }
        }
    }

    sealed class Parametric: WasmInstruction, WasmCFGInstruction {

        data class Drop(val address: WASMAddress? = null) : Parametric() {
            override fun toString(): String {
                return DROP
            }
        }

        data class Select(val address: WASMAddress? = null) : Parametric() {
            override fun toString(): String {
                return SELECT
            }
        }
    }
}

