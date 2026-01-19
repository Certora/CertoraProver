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

package sbf.analysis

import sbf.cfg.*
import sbf.disassembler.*
import datastructures.stdcollections.*
import sbf.SolanaConfig
import sbf.domains.INumValue
import sbf.domains.IOffset
import sbf.domains.SbfType
import utils.*

/**
 * This class contains the information needed to emit TAC code that initializes the content of the global variable [gv]
 * - [largestOffset] is the maximum byte plus one accessed from [gv].
 *   Note that [gv] might have also a field `size` but this field is only present if the global variable is in the symbol table.
 *   As a result, we do not rely on that and instead infer size from the uses of [gv] in the program.
 * - [stride] is the gcd of all offsets from global reads.
 * - [values] is the list of signed 64-bit values.
 *   Each value represents the content of `*(gv+(i*[stride]))` where `i` is the index of the value in [values]
 * - [locInst] is an instruction where [gv] is accessed. The instruction is needed so that we can ask pointer analysis which
 *   memory region [gv] points to, and from there to extract the corresponding TAC byte map.
 */
data class GlobalVarInitializer(val gv: SbfGlobalVariable,
                                val largestOffset: Short,
                                val stride: Short,
                                val locInst: LocatedSbfInstruction,
                                val values: List<Long>)



/**
 * It extracts information about the initialization of some global variables.
 * This info (`GlobalVarInitializer`) will be used during TAC encoding to emit TAC code.
 *
 * The analysis searches for all global reads and keeps track only of the largest read offset.
 * Then, the TAC lowering of the global initialization will add stores for all words starting from zero offset up to the largest read offset.
 * Some of these stores may be dead, and we rely on TAC optimizations to remove them.
 */
fun <TNum: INumValue<TNum>, TOffset: IOffset<TOffset>> runGlobalInitializationAnalysis(
    cfg: SbfCFG,
    scalarAnalysis: IRegisterTypes<TNum, TOffset>,
    elf: IElfFileView
): List<GlobalVarInitializer>  {

    /**
     * If [reg] at [locInst] contains the address of a global variable then it returns the global variable
     **/
    fun getGlobalVariable(locInst: LocatedSbfInstruction,
                                  reg: Value.Reg,
                                  scalarAnalysis: IRegisterTypes<TNum, TOffset>): SbfGlobalVariable? {
        return when(val type = scalarAnalysis.typeAtInstruction(locInst, reg.r)) {
            is SbfType.PointerType.Global -> type.global
            else -> null
        }
    }

    val globalInit: MutableMap<SbfGlobalVariable, GlobalVarInitializer> = mutableMapOf()
    val globalUses: MutableMap<SbfGlobalVariable, List<LocatedSbfInstruction>> = mutableMapOf()

    for (b in cfg.getBlocks().values) {
        for (locInst in b.getLocatedInstructions()) {
            val inst = locInst.inst
            // 1. check if a memory load
            if (inst is SbfInstruction.Mem && inst.isLoad) {
                val (width, base, offset) = inst.access
                // 2. check that the load accesses a global variable
                val gv = getGlobalVariable(locInst, base.reg, scalarAnalysis) ?: continue
                val curOffset = (offset + width).toShort()
                val maxOffset = globalInit.getOrPut(gv) { GlobalVarInitializer(gv, curOffset, 0, locInst, listOf()) }.largestOffset
                // 3. update maximum accessed offset so far and stride for the global variable
                if (curOffset > maxOffset) {
                    globalInit[gv] = GlobalVarInitializer(gv, curOffset, gcd(maxOffset, curOffset), locInst, listOf())
                }
                // Record all global uses so that we can later check some conditions
                globalUses[gv] = globalUses.getOrDefault(gv, listOf()) + listOf(locInst)
            }
        }
    }

    return globalInit.values
        .filter { gvInit ->
            val uses = globalUses[gvInit.gv]
            check(uses != null)
            val stride = gvInit.stride.toInt()
            val maxByteAccessed = gvInit.largestOffset.toInt()

            // The number of initialized bytes is not too large
            maxByteAccessed <= SolanaConfig.TACMaxGlobalInit.get() &&
            // at least one use of the global must be used in a condition,
            // otherwise we are not interested in the initial value of the global
            uses.any {
                isUsedInCondition(it, cfg)
            } &&
            // all uses must be consistent with the initializer's stride, otherwise the initialization code might be unsound
            (stride == 0 || uses.all {
                    val inst = it.inst
                    check(inst is SbfInstruction.Mem)
                    inst.access.offset % stride == 0
            })
        }
        .map { populateValues(it, elf) }
}

/** Return true if [locInst]  is used in a condition **/
private fun isUsedInCondition(locInst: LocatedSbfInstruction, cfg: SbfCFG): Boolean {
    val b = cfg.getBlock(locInst.label)
    check(b != null)
    val inst = locInst.inst
    check(inst is SbfInstruction.Mem && inst.isLoad)
    val useLocInst = getNextUseInterBlock(b, locInst.pos+1, inst.value as Value.Reg)
    return if (useLocInst != null) {
        val useInst = useLocInst.inst
        (useInst is SbfInstruction.Jump.ConditionalJump ||
            useInst is SbfInstruction.Assume ||
            useInst is SbfInstruction.Select ||
            useInst is SbfInstruction.Assert)
    } else {
        false
    }
}

/**
 * Return a new global initializer with a new list of values that represent the initial content of [gvInit]`.gv`.
 *
 *  These are the steps to get those values:
 *  1. Get from the ELF file the sequence of [gvInit].`size` characters starting from [gvInit].`gv`'s address.
 *  2. Split that sequence in words of [gvInit].`stride` bytes
 *  3. For each word, convert the unsigned value of each byte to a hex value and concat all the hex values
 *  4. Convert each hex word to a signed decimal
 **/
private fun populateValues(
    gvInit: GlobalVarInitializer,
    elf: IElfFileView
): GlobalVarInitializer {
    check(gvInit.values.isEmpty()) {"populateValues expects an empty list of values"}

    val gv = gvInit.gv
    val content = elf.getAsConstantString(gv.address, gvInit.largestOffset.toLong())
    val bytes = content.map { it.code.toUByte() }
    var j = 0
    val word = ArrayList<UByte>()
    val values = ArrayList<Long>()
    for (byte in bytes) {
        word.add(byte)
        j++
        if (j == gvInit.stride.toInt()) {
            // Converts the unsigned value a BigInteger
            val bigInteger = if (elf.isLittleEndian()) {
                word.reversed()
            } else {
                word
            }.toPositiveBigInteger()
            // Converts the positive BigInteger to a signed decimal
            val decimalVal = bigInteger.toLong()
            values.add(decimalVal)
            // reset word
            j = 0
            word.clear()
        }
    }
    return gvInit.copy(values = values)
}

private fun gcd(x: Short, y: Short): Short {
    check(x>=0) {"gcd expects non-negative numbers"}
    check(y>=0) {"gcd expects non-negative numbers"}
    val res = gcd(x.toInt(), y.toInt())
    return res.toShort()
}

/** textbook implementation of gcd. Note that a and b are expected to be small numbers **/
private fun gcd(a: Int, b: Int): Int {
    var (x, y) = a to b
    while (y != 0) {
        val tmp = y
        y = x % y
        x = tmp
    }
    return x
}
