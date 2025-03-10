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

package wasm.tokens

typealias DESC = String

object WasmTokens {
    const val ENTRYPOINT = 0

    // Module fields
    const val FUNC_REF = "funcref"
    const val FUNC = "func"
    const val PARAM = "param"
    const val RESULT = "result"
    const val TABLE = "table"
    const val DATA = "data"
    const val EXPORT = "export"
    const val IMPORT = "import"
    const val TYPE = "type"
    const val GLOBAL = "global"
    const val LOCAL = "local"
    const val MEMORY = "memory"
    const val ELEM = "elem"
    const val MODULE = "module"
    const val OFFSET = "offset"
    const val ALIGN = "align"
    const val MUT = "mut"
    const val EQUAL = "="
    const val SEMICOLON = ';'
    const val COLON = ':'
    const val AT = '@'
    const val LPAR = '('
    const val RPAR = ')'
    const val UNDERSCORE = "_"
    const val TAC = "tacified"
    const val WASMASSUME = "assume"
    const val WASMASSERT = "assert"
    const val LABEL = "label"
    const val LOWER = "lo"
    const val UPPER = "up"


    // Numeric ops: https://www.w3.org/TR/wasm-core-1/#numeric-instructions%E2%91%A0
    const val I32 = "i32"
    const val I64 = "i64"
    const val F32 = "f32"
    const val F64 = "f64"
    const val I32CONST = "i32.const"
    const val I64CONST = "i64.const"
    const val F32CONST = "f32.const"
    const val F64CONST = "f64.const"
    const val I32ADD = "i32.add"
    const val I64ADD = "i64.add"
    const val I32SUB = "i32.sub"
    const val I64SUB = "i64.sub"
    const val I32MUL = "i32.mul"
    const val I64MUL = "i64.mul"
    const val I32DIVU = "i32.div_u"
    const val I64DIVU = "i64.div_u"
    const val I32DIVS = "i32.div_s"
    const val I64DIVS = "i64.div_s"
    const val I32REMU = "i32.rem_u"
    const val I64REMU = "i64.rem_u"
    const val I32REMS = "i32.rem_s"
    const val I64REMS = "i64.rem_s"
    const val I32AND = "i32.and"
    const val I64AND = "i64.and"
    const val I32OR = "i32.or"
    const val I64OR = "i64.or"
    const val I32XOR = "i32.xor"
    const val I64XOR = "i64.xor"

    const val I32SHL = "i32.shl"
    const val I64SHL = "i64.shl"
    const val I32SHRU = "i32.shr_u"
    const val I64SHRU = "i64.shr_u"
    const val I32SHRS = "i32.shr_s"
    const val I64SHRS = "i64.shr_s"
    const val I32WRAP64 = "i32.wrap_i64"
    const val I32_EXTEND8_S = "i32.extend8_s"
    const val I32_EXTEND16_S = "i32.extend16_s"
    const val I64_EXTEND8_S = "i64.extend8_s"
    const val I64_EXTEND16_S = "i64.extend16_s"
    const val I64_EXTEND32_S = "i64.extend32_s"
    const val I64_EXTENDI32_U = "i64.extend_i32_u"
    const val I64_EXTENDI32_S = "i64.extend_i32_s"
    const val I32CLZ = "i32.clz"
    const val I64CLZ = "i64.clz"

    const val I32LTU = "i32.lt_u"
    const val I64LTU = "i64.lt_u"
    const val I32GTU = "i32.gt_u"
    const val I64GTU = "i64.gt_u"
    const val I32LTS = "i32.lt_s"
    const val I64LTS = "i64.lt_s"
    const val I32GTS = "i32.gt_s"
    const val I64GTS = "i64.gt_s"
    const val I32LEU = "i32.le_u"
    const val I64LEU = "i64.le_u"
    const val I32LES = "i32.le_s"
    const val I64LES = "i64.le_s"
    const val I32GEU = "i32.ge_u"
    const val I64GEU = "i64.ge_u"
    const val I32GES = "i32.ge_s"
    const val I64GES = "i64.ge_s"
    const val I32EQ = "i32.eq"
    const val I64EQ = "i64.eq"
    const val I32EQZ = "i32.eqz"
    const val I64EQZ = "i64.eqz"
    const val I32NE = "i32.ne"
    const val I64NE = "i64.ne"

    // Variable ops: https://www.w3.org/TR/wasm-core-1/#variable-instructions%E2%91%A0
    const val LOCALGET = "local.get"
    const val LOCALSET = "local.set"
    const val LOCALTEE = "local.tee"
    const val GLOBALGET = "global.get"
    const val GLOBALSET = "global.set"

    // Parametric ops: https://www.w3.org/TR/wasm-core-1/#parametric-instructions%E2%91%A0
    const val DROP = "drop"
    const val SELECT = "select"

    // Memory ops: https://www.w3.org/TR/wasm-core-1/#memory-instructions%E2%91%A0
    const val I32LOAD = "i32.load"
    const val I64LOAD = "i64.load"

    const val I32LOAD8_U = "i32.load8_u"
    const val I32LOAD8_S = "i32.load8_s"
    const val I64LOAD8_U = "i64.load8_u"
    const val I64LOAD8_S = "i64.load8_s"

    const val I32LOAD16_U = "i32.load16_u"
    const val I32LOAD16_S = "i32.load16_s"
    const val I64LOAD16_U = "i64.load16_u"
    const val I64LOAD16_S = "i64.load16_s"

    const val I64LOAD32_U = "i64.load32_u"
    const val I64LOAD32_S = "i64.load32_s"

    const val I32STORE = "i32.store"
    const val I64STORE = "i64.store"

    const val I32STORE8 = "i32.store8"
    const val I64STORE8 = "i64.store8"

    const val I32STORE16 = "i32.store16"
    const val I64STORE16 = "i64.store16"

    const val I64STORE32 = "i64.store32"

    const val F32LOAD = "f32.load"
    const val F64LOAD = "f64.load"
    const val F32STORE = "f32.store"
    const val F64STORE = "f64.store"

    const val MEMORY_GROW = "memory.grow"
    const val MEMORY_SIZE = "memory.size"

    // Control ops: https://www.w3.org/TR/wasm-core-1/#control-instructions%E2%91%A0
    const val NOP = "nop"
    const val UNREACHABLE = "unreachable"
    const val BR = "br"
    const val BR_IF = "br_if"
    const val BR_TABLE = "br_table"
    const val CALL = "call"
    const val CALL_INDIRECT = "call_indirect"
    const val BLOCK = "block"
    const val LOOP = "loop"
    const val RETURN = "return"
    const val END = "end"
    const val ELSE = "else"
    const val IF = "if"

    // WASM CFG constants
    const val BOTTOM = "bottom"
    const val HAVOC = "wasm.havoc"
    const val HAVOC_VAR_NM = "wasm_havoc_var"

    const val WASMCFG = "wasmcfg."
    const val WASMICFG = "wasmicfg."
    const val WASMCFG_JUMP = "wasmcfg.jmp"

    // Wasm ImpCfg constants

    const val WASMICFG_CALL = "wasmicfg.call"
    const val WASMICFG_CALL_INDIRECT = "wasmicfg.call_indirect"
    const val WASMICFG_UNREACH = "wasmicfg.unreach"
    const val WASMICFG_JUMP = "wasmicfg.jmp"
    const val WASMICFG_RET = "wasmicfg.ret"
    const val WASMICFG_IF = "wasmicfg.if"
    const val WASMICFG_SWITCH = "wasmicfg.switch"

    const val TMP = "tmp_pc"
}
