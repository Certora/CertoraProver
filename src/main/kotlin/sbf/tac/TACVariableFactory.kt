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

package sbf.tac

import sbf.domains.PTACell
import sbf.domains.PTANode
import sbf.domains.PTAOffset
import tac.Tag
import vc.data.TACSymbol
import datastructures.stdcollections.*
import sbf.domains.IPTANodeFlags
import vc.data.TACKeyword
import kotlin.math.absoluteValue

private typealias ByteMapCache<NodeFlags> = MutableMap<PTANode<NodeFlags>, TACByteMapVariable>
private typealias ByteStackCache = MutableMap<PTAOffset, TACByteStackVariable>
/** Represent the whole memory. Used with memory splitter is disabled **/
private val theMem = TACByteMapVariable(TACSymbol.Var("UntypedMem", Tag.ByteMap))

sealed class TACVariable(open val tacVar: TACSymbol.Var)

/**
 * Represent a physical 32 wide-byte at [offset] on the stack.
 * The choice of 32 bytes is enforced by TAC.
 * **/
data class TACByteStackVariable(override val tacVar: TACSymbol.Var, val offset: PTAOffset):
    TACVariable(tacVar), Comparable<TACByteStackVariable> {
    init {
        check(tacVar.tag == Tag.Bit256) {"TACByteStackVariable must have Bit256 tag"}
    }
    override fun compareTo(other: TACByteStackVariable) = offset.compareTo(other.offset)
}
/** Wrapper for a ByteMap variable **/
data class TACByteMapVariable(override val tacVar: TACSymbol.Var): TACVariable(tacVar) {
    init {
        check(tacVar.tag == Tag.ByteMap) {"TACByteMapVariable must have ByteMap tag"}
    }
}

/** Factory for TAC variables **/
class TACVariableFactory<Flags: IPTANodeFlags<Flags>>(
    private val useDynFrames: Boolean
) {
    private var varId: Int = 0
    private val byteMapCache: ByteMapCache<Flags> = mutableMapOf()
    private val byteStackCache: ByteStackCache = mutableMapOf()
    private val declaredVars: MutableSet<TACSymbol.Var> = mutableSetOf()

    private fun mkByteStackVar(offset: PTAOffset): TACByteStackVariable {
        val offsetL = if (useDynFrames) {
            // all offsets are non-positive
            offset.v.absoluteValue
        } else {
            // all offsets are non-negative
            offset.v
        }

        val name = "Stack_B_$offsetL"
        val scalarVar = TACSymbol.Var(name, Tag.Bit256)
        declaredVars.add(scalarVar)
        return TACByteStackVariable(scalarVar, offset)
    }

    private fun mkFreshVar(prefix: String, tag: Tag): TACSymbol.Var {
        val v = TACSymbol.Var(prefix, tag)
        varId++
        declaredVars.add(v)
        return v
    }

    /// -- Public API

    fun getDeclaredVariables(): Set<TACSymbol.Var> = declaredVars

    /** Create a fresh fixed bitwidth integer TAC variable **/
    fun mkFreshIntVar(prefix: String = "v"): TACSymbol.Var {
        return mkFreshVar("$prefix${varId}", Tag.Bit256)
    }

    /** Create a fresh mathematical integer TAC variable **/
    fun mkFreshMathIntVar(prefix: String = "v"): TACSymbol.Var {
        return mkFreshVar("$prefix${varId}", Tag.Int)
    }

    /** Create a boolean TAC variable **/
    fun mkFreshBoolVar(prefix: String = "v"): TACSymbol.Var {
        return mkFreshVar("$prefix${varId}", Tag.Bool)
    }

    /** Create a TAC variable to represent the SBF register [i] **/
    fun getRegisterVar(i: Int): TACSymbol.Var {
        // Note that we use 32 bytes even if SBF registers are known to be 8 bytes
        val v = TACSymbol.Var("r${i}", Tag.Bit256)
        declaredVars.add(v)
        return v
    }

    /** Map physical stack memory at [offset] to a TAC scalar variable **/
    fun getByteStackVar(offset: PTAOffset): TACByteStackVariable {
        val v = byteStackCache[offset]
        return if (v == null) {
            val newV = mkByteStackVar(offset)
            byteStackCache[offset] = newV
            newV
        } else {
            v
        }
    }

    /** Map a cell [c] to a TAC ByteMap variable **/
    fun getByteMapVar(c: PTACell<Flags>): TACByteMapVariable {
        return byteMapCache.getOrPut(c.getNode()) {
            val byteMapVar = TACSymbol.Var("M_${c.getNode().id}", Tag.ByteMap)
            declaredVars.add(byteMapVar)
            TACByteMapVariable(byteMapVar)
        }
    }

    /** Return a fresh, non-cached ByteMap variable **/
    fun getByteMapVar(suffix: String): TACByteMapVariable {
        val byteMapVar = TACKeyword.TMP(Tag.ByteMap, suffix)
        declaredVars.add(byteMapVar)
        return TACByteMapVariable(byteMapVar)
    }

    /**
     * Return a ByteMap variable that represents the whole memory.
     * Used when memory splitter is disabled
     **/
    fun getWholeMemoryByteMapVar(): TACByteMapVariable {
        declaredVars.add(theMem.tacVar)
        return theMem
    }
}
