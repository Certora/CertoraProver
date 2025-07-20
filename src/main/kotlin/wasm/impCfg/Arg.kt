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

package wasm.impCfg

import tac.Tag
import vc.data.TACExpr
import vc.data.TACSymbol
import wasm.cfg.PC
import wasm.ir.*
import wasm.tokens.WasmTokens.HAVOC


class CannotConvertToTacException(msg: String): Exception(msg)

/**
 * An [Arg] is a register or a constant or Top (Havoc).
 * When we go from stack to register machine, we use these args to
 * track the symbolic stack state at each pc.
 * Each Arg also can be converted to a TACSymbol and TACExpr.
 * NOTE: For now, we tag them all as 256 bit ints by default which is not right.
 * */
sealed interface Arg {
    override fun toString(): String

    val type: WasmPrimitiveType

    fun toTacExpr(): TACExpr {
        return this.toTacSymbol().asSym()
    }

    // These are all tagged as Tag.Bit256 for now by default.
    fun toTacSymbol(): TACSymbol

    fun isHavoc(): Boolean

    fun getTmpPC(): PC?

    fun getTmpName(): String?

    fun join(b: Arg): Arg {
        return if (this == b) {
            this
        } else {
            check(this.type == b.type) { "Wasm stack type mismatch: $this and $b" }
            Havoc(type)
        }
    }

    /**
     * Given a register, [arg], and a mapping of old PCs to new PCs, this
     * function creates a new register by looking up
     * the new PC that [arg]'s PC component corresponds to.
     * */
    fun transformTmps(rename: (Tmp) -> Tmp): Arg {
        return this
    }
}

data class ArgRegister(val register: Tmp) : Arg {
    override val type get() = register.type

    override fun toString(): String {
        return register.toString()
    }

    override fun toTacSymbol(): TACSymbol.Var {
        return TACSymbol.Var(register.toString(), Tag.Bit256)
    }

    override fun isHavoc(): Boolean {
        return false
    }

    override fun getTmpPC(): PC {
        return register.pc
    }

    override fun getTmpName(): String {
        return register.name
    }

    override fun transformTmps(rename: (Tmp) -> Tmp): ArgRegister {
        return copy(register = rename(register))
    }
}

data class ArgConst32(val value: I32Value) : Arg {
    override val type get() = WasmPrimitiveType.I32

    override fun toString(): String {
        return value.toString()
    }

    override fun toTacSymbol(): TACSymbol {
        return TACSymbol.Const(value.v, Tag.Bit256)
    }

    override fun isHavoc(): Boolean {
        return false
    }

    override fun getTmpPC(): PC? {
        return null
    }

    override fun getTmpName(): String? {
        return null
    }
}

data class ArgConst64(val value: I64Value) : Arg {
    override val type get() = WasmPrimitiveType.I64

    override fun toString(): String {
        return value.toString()
    }

    override fun toTacSymbol(): TACSymbol {
        return TACSymbol.Const(value.v, Tag.Bit256)
    }

    override fun isHavoc(): Boolean {
        return false
    }

    override fun getTmpPC(): PC? {
        return null
    }

    override fun getTmpName(): String? {
        return null
    }
}

/**
 * Havoc here means "Top".
  */
data class Havoc(override val type: WasmPrimitiveType) : Arg {
    override fun toString(): String {
        return HAVOC
    }

    override fun toTacExpr(): TACExpr {
        throw CannotConvertToTacException("The `havocify` pass should convert havoc exprs to havoc cmds before trying to generate tac.")
    }

    override fun toTacSymbol(): TACSymbol {
        throw CannotConvertToTacException("The `havocify` pass should convert havoc exprs to havoc cmds before trying to generate tac.")
    }

    override fun isHavoc(): Boolean {
        return true
    }

    override fun getTmpPC(): PC? {
        return null
    }

    override fun getTmpName(): String? {
        return null
    }
}

object ArgUtils {

    /**
     * Note: this one also returns a reference (an ArgRegister) to that register!
     * */
    fun mkTmpRegister(type: WasmPrimitiveType, pc: PC, name: String, nonce: Int = 0): Pair<Tmp, ArgRegister> {
        return Tmp(type, pc, name, nonce).let { it to ArgRegister(it) }
    }
}
