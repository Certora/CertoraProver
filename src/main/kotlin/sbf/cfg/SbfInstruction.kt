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

package sbf.cfg

import sbf.disassembler.*
import datastructures.stdcollections.*
import dwarf.DebugInfoReader
import sbf.callgraph.*
import utils.*

/**
 * This file defines the instruction set for SBF v1 programs
 * All classes should be immutable.
 **/
sealed class Value {

    data class Imm(val v: ULong): Value() {
        override fun toString(): String {
            return v.toLong().toString()
        }
    }

    data class Reg(val r: SbfRegister): Value(), Comparable<Reg> {
        override fun toString(): String {
            return when (r) {
               SbfRegister.R0_RETURN_VALUE -> "r0"
               SbfRegister.R1_ARG -> "r1"
               SbfRegister.R2_ARG -> "r2"
               SbfRegister.R3_ARG -> "r3"
               SbfRegister.R4_ARG -> "r4"
               SbfRegister.R5_ARG -> "r5"
               SbfRegister.R6 -> "r6"
               SbfRegister.R7 -> "r7"
               SbfRegister.R8 -> "r8"
               SbfRegister.R9 -> "r9"
               SbfRegister.R10_STACK_POINTER -> "r10"
           }
        }

        override fun compareTo(other: Reg) = r.compareTo(other.r)
    }
}

/* Registers that _may_ be written */
interface WriteRegister {
    val writeRegister: Set<Value.Reg>
}

/* Registers that _may_ be read */
interface ReadRegister {
    val readRegisters: Set<Value.Reg>
}

enum class BinOp(val isCommutative: Boolean = false) {
    MOV(false),
    ADD(true),
    SUB(false),
    MUL(true),
    // unsigned division (sbf doesn't have an instruction for signed division)
    DIV(false),
    // unsigned remainder (sbf doesn't have an instruction for signed remainder)
    MOD(false),
    OR(true),
    AND(true),
    XOR(true),
    LSH(false),
    RSH(false),
    ARSH(false)
}

fun toString(op: BinOp): String {
    return when (op) {
        BinOp.ADD -> "+"
        BinOp.SUB -> "-"
        BinOp.MUL -> "*"
        // unsigned division
        BinOp.DIV -> "/"
        // Bitwise or
        BinOp.OR -> "or"
        // Bitwise and
        BinOp.AND -> "and"
        // Bitwise xor
        BinOp.XOR -> "xor"
        // Left shift
        // don't use << because dot doesn't like it
        BinOp.LSH -> "lsh"
        // Logical right shift
        // don't use >> because dot doesn't like it
        BinOp.RSH -> "lrsh"
        // Arithmetic right shif
        BinOp.ARSH -> "arsh"
        // Note that mod and rem are different operators and this one is rem even
        // if the name says MOD.
        // sbfv1 doesn't have an instruction for signed remainder so this is unsigned remainder
        BinOp.MOD -> "%"
        // don't print MOV
        BinOp.MOV -> ""
    }
}

enum class UnOp {
    // SBF is *always* little-endian so only conversion to big-endian are possible.
    // conversion to big-endian
    BE16, // dst = htobe16(dst) swaps the lower 2 bytes and zeroes the upper 6.
    BE32, // dst = htobe32(dst) reverses the order of the lower 4 bytes and zeros the upper 4.
    BE64, // dst = htobe64(dst) reverses the order of all 8 bytes.
    // conversion to little-endian
    LE16, // dst = htole16(dst)
    LE32, // dst = htole32(dst)
    LE64, // dst = htole64(dst)

    NEG   // dst = neg(dst)
}

fun toString(op: UnOp): String {
    return when(op) {
        UnOp.BE16 -> "htobe16"
        UnOp.BE32 -> "htobe32"
        UnOp.BE64 -> "htobe64"
        UnOp.LE16 -> "htole16"
        UnOp.LE32 -> "htole32"
        UnOp.LE64 -> "htole64"
        UnOp.NEG  -> "neg"
    }
}

enum class CondOp(val isUnsigned: Boolean) {
    EQ(false) {
        override fun negate() = NE
        override fun swap() = EQ },
    NE(false) {
        override fun negate() = EQ
        override fun swap() = NE },
    LT(true) {
        override fun negate() = GE
        override fun swap() = GT},
    LE(true) {
        override fun negate() = GT
        override fun swap() = GE},
    GT(true) {
        override fun negate() = LE
        override fun swap() = LT},
    GE(true) {
        override fun negate() = LT
        override fun swap() = LE},
    SLT(false) {
        override fun negate() = SGE
        override fun swap() = SGT},
    SLE(false) {
        override fun negate() = SGT
        override fun swap() = SGE},
    SGT(false) {
        override fun negate() = SLE
        override fun swap() = SLT},
    SGE(false) {
        override fun negate() = SLT
        override fun swap() = SLE
    };

    abstract fun negate(): CondOp
    abstract fun swap(): CondOp
}

fun toString(op: CondOp): String {
    return when (op) {
        CondOp.EQ -> "=="
        CondOp.NE -> "!="
        CondOp.LT -> "ult"
        CondOp.LE -> "ule"
        CondOp.GT -> "ugt"
        CondOp.GE -> "uge"
        // Don't use <, <=, >, >= because dot don't like them
        CondOp.SLT -> "slt"
        CondOp.SLE -> "sle"
        CondOp.SGT -> "sgt"
        CondOp.SGE -> "sge"
    }
}

private fun toString(v: Value, type:SbfRegisterType?): String {
    var str = v.toString()
    if (type != null) {
        str += ":$type"
    }
    return str
}

data class Condition(val op: CondOp,
                     val left: Value.Reg,
                     val right: Value,
                     private val leftType: SbfRegisterType? = null,
                     private val rightType: SbfRegisterType? = null): ReadRegister {
    override val readRegisters: Set<Value.Reg>
        get() = (right as? Value.Reg)?.let { setOf(it, left) } ?: setOf(left)

    override fun toString(): String {
        return toString(left, leftType) + " " + toString(op) + " " + toString(right, rightType)
    }

    fun negate() = copy(op = op.negate())
}

data class Deref(val width: Short,
                 val baseReg: Value.Reg,
                 val offset: Short,
                 private val baseRegType: SbfRegisterType? = null) {
    override fun toString(): String {
        fun toString(type: SbfRegisterType?) = if (type != null) {":$type"} else {""}

        if (baseRegType != null) {
            if (baseRegType is SbfRegisterType.PointerType.Stack) {
                val baseOffset = baseRegType.offset.toLongOrNull()
                if (baseOffset != null) {
                    val newBaseRegType = baseRegType.copy(offset = baseRegType.offset.add(offset.toLong()))
                    return "*(u${width * 8} *) ($baseReg + $offset)${toString(newBaseRegType)}"
                }
            }
        }
        return "*(u${width * 8} *) ($baseReg${toString(baseRegType)} + $offset)"
    }
}

sealed class SbfInstruction: ReadRegister, WriteRegister  {
    abstract val metaData: MetaData
    // To allow call the copy method of the subclasses
    abstract fun copyInst(metadata: MetaData = metaData): SbfInstruction

    open fun isAbort() = false
    fun isAssertOrSatisfy() = isAssert() || isSatisfy()
    open fun isAssert() = false
    open fun isSatisfy() = false
    open fun isSanity() = false
    open fun isTerminator() = false
    open fun isAllocFn() = false
    open fun isDeallocFn() = false
    open fun isExternalFn() = false

    open fun metadataToString() = toString(metaData)

    data class Bin(val op: BinOp,
                   val dst: Value.Reg,
                   val v: Value,
                   val is64: Boolean,
                   private val preDstType: SbfRegisterType? = null,
                   private val postDstType: SbfRegisterType? = null,
                   private val vType: SbfRegisterType? = null,
                   override val metaData: MetaData = MetaData())
        : SbfInstruction() {

        init {
            // to be lifted in the future
            check(is64) {"only 64-bit binary instructions are supported"}
        }

        override fun copyInst(metadata: MetaData) = copy(metaData = metadata)
        override val writeRegister: Set<Value.Reg>
            get() = setOf(dst)
        override val readRegisters: Set<Value.Reg>
            get() = if (op == BinOp.MOV) {
                (v as? Value.Reg)?.let { setOf(it) } ?: setOf()
            } else {
                (v as? Value.Reg)?.let { setOf(dst, it) } ?: setOf(dst)
            }

        override fun toString(): String {
            var str = toString(dst, postDstType)
            str += if (!is64) {
                " :=32 "
            } else {
                " := "
            }
            str += if (op == BinOp.MOV) {
                "$v"
            } else {
                toString(dst, preDstType) + " " + toString(op) + " " + toString(v, vType)
            }
            str += metadataToString()
            return str
        }
    }

    data class Select(val dst: Value.Reg,
                      val cond: Condition,
                      val trueVal: Value,
                      val falseVal: Value,
                      override val metaData: MetaData = MetaData())
        : SbfInstruction() {

        override fun copyInst(metadata: MetaData) = copy(metaData = metadata)
        override val writeRegister: Set<Value.Reg>
            get() = setOf(dst)
        override val readRegisters: Set<Value.Reg>
            get() = cond.readRegisters + kotlin.collections.setOfNotNull(trueVal as? Value.Reg, falseVal as? Value.Reg)
        override fun toString() = "$dst := select($cond, $trueVal, $falseVal) ${metadataToString()}"
    }

    data class Havoc(val dst: Value.Reg,
                     private val dstType: SbfRegisterType? = null,
                     override val metaData: MetaData = MetaData())
        : SbfInstruction() {
        override fun copyInst(metadata: MetaData) = copy(metaData = metadata)

        override val writeRegister: Set<Value.Reg>
            get() = setOf(dst)
        override val readRegisters: Set<Value.Reg>
            get() = setOf()

        override fun toString() = toString(dst, dstType) + " := havoc() ${metadataToString()}"
    }

    data class Un(val op: UnOp,
                  val dst: Value.Reg,
                  val is64: Boolean,
                  private val preDstType: SbfRegisterType? = null,
                  private val postDstType: SbfRegisterType? = null,
                  override val metaData: MetaData = MetaData())
        : SbfInstruction() {

        init {
            // to be lifted in the future
            check(is64) {"only 64-bit unary instructions are supported"}
        }

        override fun copyInst(metadata: MetaData) = copy(metaData = metadata)
        override val writeRegister: Set<Value.Reg>
            get() = setOf(dst)
        override val readRegisters: Set<Value.Reg>
            get() = setOf(dst)

        override fun toString(): String {
            var str = toString(dst, postDstType)
            str += if (!is64) {
                " :=32 "
            } else {
                " := "
            }
            str += toString(op) + "(" + toString(dst, preDstType) + ")"
            str += metadataToString()
            return str
        }
    }

    data class Assume(val cond: Condition,
                      override val metaData: MetaData = MetaData())
        : SbfInstruction() {
        override fun copyInst(metadata: MetaData) = copy(metaData = metadata)

        override val writeRegister: Set<Value.Reg>
            get() = setOf()
        override val readRegisters: Set<Value.Reg>
            get() = cond.readRegisters

        override fun toString() = "assume($cond) ${metadataToString()}"
    }

    data class Assert(val cond: Condition,
                      override val metaData: MetaData = MetaData())
        : SbfInstruction() {

        override val writeRegister: Set<Value.Reg>
            get() = setOf()
        override val readRegisters: Set<Value.Reg>
            get() = cond.readRegisters

        override fun copyInst(metadata: MetaData) = copy(metaData = metadata)
        override fun isAssert() = true
        override fun toString() = "assert($cond) ${metadataToString()}"
    }

    sealed class Jump(override val metaData: MetaData = MetaData()) : SbfInstruction() {
        abstract val target : Label
        override fun isTerminator() = true

        override val writeRegister: Set<Value.Reg>
            get() = setOf()

        data class ConditionalJump(val cond: Condition,
                                   override val target: Label,
                                   val falseTarget: Label? = null,
                                   override val metaData: MetaData = MetaData())
        : Jump(), ReadRegister {
            override val readRegisters: Set<Value.Reg>
                get() = cond.readRegisters

            override fun copyInst(metadata: MetaData) = copy(metaData = metadata)
            override fun toString(): String {
                var str = "if ($cond) then goto $target"
                if (falseTarget != null) {
                    str += " else goto $falseTarget"
                }
                str += metadataToString()
                return str
            }
        }

        data class UnconditionalJump(override val target: Label,
                                     override val metaData: MetaData = MetaData())
            : Jump() {
            override val readRegisters: Set<Value.Reg>
                get() = setOf()
            override fun copyInst(metadata: MetaData) = copy(metaData = metadata)
            override fun toString() = "goto $target ${metadataToString()}"
        }
    }

    /**
     *  This class represents both memory loads and stores.
     *  - If isLoad is true
     *    value := *access
     *  - else
     *    *access := value
     */
    data class Mem(val access: Deref,
                   val value: Value,
                   val isLoad: Boolean,
                   private val valueType: SbfRegisterType? = null,
                   override val metaData: MetaData = MetaData())
        : SbfInstruction() {

        init {
            check(!isLoad || value is Value.Reg) {"the lhs of a load must be a register"}
        }

        override fun copyInst(metadata: MetaData) = copy(metaData = metadata)

        override val writeRegister: Set<Value.Reg>
            get() = if (isLoad) {
                setOf(value as Value.Reg)
            } else {
                setOf()
            }

        override val readRegisters: Set<Value.Reg>
            get() = if (isLoad) {
                setOf(access.baseReg)
            } else {
                (value as? Value.Reg)?.let { setOf(it, access.baseReg) } ?: setOf(access.baseReg)
            }

        override fun toString(): String {
            val strB = StringBuilder()
            if (isLoad) {
                strB.append(toString(value, valueType))
                strB.append(" := ")
                strB.append(access.toString())
            } else {
                strB.append(access.toString())
                strB.append(" := ")
                strB.append(toString(value, valueType))
            }
            strB.append(metadataToString())
            return strB.toString()
        }
    }

    /**
     * For a call we know that the input parameters are always registers r1-r5 and
     * the return value (if any) is stored in r0.
     *
     * @name is the function name. The name should be unique.
     * @entryPoint is the start address of the function code (null if function is an external symbol).
     **/
    data class Call(val name: String,
                    val entryPoint: ElfAddress? = null,
                    override val metaData: MetaData = MetaData())
        : SbfInstruction() {

        override fun copyInst(metadata: MetaData) = copy(metaData = metadata)
        override fun isAbort() =
                SolanaFunction.from(name) == SolanaFunction.ABORT || name in AbortFunctions
        override fun isTerminator() = isAbort()
        override fun isAssert() = CVTFunction.from(name) == CVTFunction.Core(CVTCore.ASSERT)
        override fun isSatisfy() = CVTFunction.from(name) == CVTFunction.Core(CVTCore.SATISFY)
        override fun isSanity() = CVTFunction.from(name) == CVTFunction.Core(CVTCore.SANITY)
        override fun isAllocFn(): Boolean {
                return ((name == "__rust_alloc" || name == "__rust_alloc_zeroed") || /* Rust alloc*/
                        (name == "malloc" || name == "calloc" ))                     /* C alloc */
        }
        override fun isDeallocFn(): Boolean {
            return (name == "__rust_dealloc" || /* Rust dealloc */
                    name == "free")              /* C dealloc */
        }
        override fun isExternalFn(): Boolean {
            return (SolanaFunction.from(name) != null ||
                    CVTFunction.from(name) != null ||
                    CompilerRtFunction.from(name) != null)
        }

        override val writeRegister: Set<Value.Reg>
            get() {
                val cvtFunction = CVTFunction.from(name)
                val solFunction = SolanaFunction.from(name)
                val rtFunction = CompilerRtFunction.from(name)
                return cvtFunction?.function?.writeRegisters
                    ?: (solFunction?.syscall?.writeRegisters
                        ?: (rtFunction?.function?.writeRegisters
                            ?: setOf(Value.Reg(SbfRegister.R0_RETURN_VALUE))))
            }

        override val readRegisters: Set<Value.Reg>
            get() {
                val cvtFunction = CVTFunction.from(name)
                val solFunction = SolanaFunction.from(name)
                val rtFunction = CompilerRtFunction.from(name)
                return cvtFunction?.function?.readRegisters
                    ?: (solFunction?.syscall?.readRegisters
                        ?: (rtFunction?.function?.readRegisters
                            ?: SbfRegister.funArgRegisters.mapToSet { Value.Reg(it) }))
            }


        override fun toString() = "call $name ${metadataToString()}"
    }

    data class CallReg(val callee: Value.Reg,
                       override val metaData: MetaData = MetaData())
        : SbfInstruction() {

        override fun copyInst(metadata: MetaData) = copy(metaData = metadata)

        override val writeRegister: Set<Value.Reg>
            get() = setOf(Value.Reg(SbfRegister.R0_RETURN_VALUE))
        override val readRegisters: Set<Value.Reg>
            get() = SbfRegister.funArgRegisters.mapToSet { Value.Reg(it) } + setOf(callee)

        override fun toString(): String {
           return "callx $callee ${metadataToString()}"
        }
    }

    data class Exit(override val metaData: MetaData = MetaData()): SbfInstruction() {
        override val writeRegister: Set<Value.Reg>
            get() = setOf()
        override val readRegisters: Set<Value.Reg>
            get() = setOf()
        override fun copyInst(metadata: MetaData) = copy(metaData = metadata)
        override fun isTerminator() = true
        override fun toString(): String {
            return "exit ${metadataToString()}"
        }
    }
}

/**
 * Useful for when we want a pointer back to the containing block for an instruction
 * @param label the label of the block containing [inst]
 * @param pos is the index of [inst] in block [label]
 **/
data class LocatedSbfInstruction(val label: Label, val pos: Int, val inst: SbfInstruction) {
    override fun toString() = "$label-$pos: $inst"

    /**
     * For the given [LocatedSbfInstruction] retrieves the [Range.Range] in
     * source code that is associated with it. This method returns the first location
     * on the stack that is also in [config.ConfigKt.SOURCES_SUBDIR], which are the files
     * that are present in the rule report.
     *
     * I.e., if the [LocatedSbfInstruction] is in a system file, this method returns a range
     * to the next source code location in user code that lead to the instruction in the system file.
     */
    fun getSourceLocationInSourcesDir(): Range.Range? {
        val address = this.inst.metaData.getVal(SbfMeta.SBF_ADDRESS)
        if (address != null) {
            val frames = DebugInfoReader.getInlinedFramesInSourcesDir(listOf(address))
            return frames[address]?.firstOrNull()
        }
        return null
    }
}
