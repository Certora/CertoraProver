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

package sbf.disassembler

import sbf.callgraph.*
import sbf.cfg.*
import sbf.sbfLogger
import org.jetbrains.annotations.TestOnly
import datastructures.stdcollections.*

/**
 * Translate SbfBytecode to SbfInstructions
 * https://www.kernel.org/doc/html/latest/bpf/instruction-set.html
 **/

private fun getMemWidth(inst: SbfBytecode): Short {
    return when (inst.opcode.toInt() and SbfInstructionCodes.INST_SIZE_MASK.opcode) {
        SbfInstructionCodes.INST_SIZE_B.opcode -> 1.toShort()
        SbfInstructionCodes.INST_SIZE_H.opcode -> 2.toShort()
        SbfInstructionCodes.INST_SIZE_W.opcode -> 4.toShort()
        SbfInstructionCodes.INST_SIZE_DW.opcode -> 8.toShort()
        else -> throw DisassemblerError("unexpected size mode")
    }
}

private fun isMemLd(inst: SbfBytecode): Boolean {
    return when (inst.opcode.toInt() and SbfInstructionCodes.INST_CLS_MASK.opcode) {
        SbfInstructionCodes.INST_CLS_LD.opcode -> true
        else -> false
    }
}

private fun isMemLdX(inst: SbfBytecode): Boolean {
    return when (inst.opcode.toInt() and SbfInstructionCodes.INST_CLS_MASK.opcode) {
        SbfInstructionCodes.INST_CLS_LDX.opcode -> true
        else -> false
    }
}

@TestOnly
fun makeMemInst(inst: SbfBytecode): SbfInstruction {
    when ((inst.opcode.toInt() and SbfInstructionCodes.INST_MODE_MASK.opcode).shr(5)) {
        SbfInstructionCodes.INST_ABS.opcode -> {
            throw DisassemblerError("unsupported legacy BPF packet access (absolute)")
        }
        SbfInstructionCodes.INST_IND.opcode -> {
            throw DisassemblerError("unsupported legacy BPF packet access (indirect)")
        }
        SbfInstructionCodes.INST_LEN.opcode -> {
            throw DisassemblerError("unsupported memory mode LEN")
        }
        SbfInstructionCodes.INST_MSH.opcode -> {
            throw DisassemblerError("unsupported memory mode MSH")
        }
        SbfInstructionCodes.INST_XADD.opcode -> {
            throw DisassemblerError("unsupported atomic add")
        }
        SbfInstructionCodes.INST_MEM_UNUSED.opcode -> {
            throw DisassemblerError("unsupported memory mode UNUSED")
        }
        SbfInstructionCodes.INST_MEM.opcode -> {
            if (isMemLd(inst)) {
                throw DisassemblerError("unsupported legacy LD for packet access")
            }
            val width = getMemWidth(inst)
            val isLoad = isMemLdX(inst)
            val isImm:Boolean = (inst.opcode.toInt() and 1) == 0
            check(!(isLoad and isImm))
            val baseReg:Byte = if (isLoad) {inst.src} else {inst.dst}
            return SbfInstruction.Mem(
                    Deref(width, Value.Reg(SbfRegister.getByValue(baseReg)), inst.offset),
                    if (isLoad) {
                            Value.Reg(SbfRegister.getByValue(inst.dst))
                    } else {
                        if (isImm) {
                            Value.Imm(inst.imm.toULong())
                        }
                        else {
                            Value.Reg(SbfRegister.getByValue(inst.src))
                        }
                    },
                    isLoad)
        }
        else -> throw DisassemblerError("unexpected instruction $inst")
    }
}

private fun getBinValue(inst: SbfBytecode): Value {
    if (inst.offset != 0.toShort()) {
        throw DisassemblerError("nonzero offset for register ALU instruction")
    }
    return if (inst.opcode.toInt() and SbfInstructionCodes.INST_SRC_REG.opcode != 0) {
        if (inst.imm != 0) {
            throw DisassemblerError("nonzero imm for register ALU instruction")
        }
        Value.Reg(SbfRegister.getByValue(inst.src))
    }  else {
        if (inst.src.toInt() != 0) {
            throw DisassemblerError("nonzero src for register ALU instruction")
        }
        Value.Imm(inst.imm.toULong())
    }
}


private fun makeBinAluInst(op: BinOp, inst: SbfBytecode, is64: Boolean): SbfInstruction =
    SbfInstruction.Bin(op, Value.Reg(SbfRegister.getByValue(inst.dst)), getBinValue(inst), is64)
private fun makeUnAluInst(op: UnOp, inst: SbfBytecode, is64: Boolean): SbfInstruction =
    SbfInstruction.Un(op, Value.Reg(SbfRegister.getByValue(inst.dst)), is64)

@TestOnly
fun makeAluInst(inst: SbfBytecode): SbfInstruction {
    val dstReg = Value.Reg(SbfRegister.getByValue(inst.dst))
    if (dstReg.r == SbfRegister.R10_STACK_POINTER) {
        throw DisassemblerError("cannot write on r10")
    }
    val is64 = (inst.opcode.toInt() and SbfInstructionCodes.INST_CLS_MASK.opcode) == SbfInstructionCodes.INST_CLS_ALU64.opcode
    when (inst.opcode.toInt().shr(4) and 0xF) {
        0x0 -> return makeBinAluInst(BinOp.ADD, inst, is64)
        0x1 -> return makeBinAluInst(BinOp.SUB, inst, is64)
        0x2 -> return makeBinAluInst(BinOp.MUL, inst, is64)
        0x3 -> return makeBinAluInst(BinOp.DIV, inst, is64)
        0x4 -> return makeBinAluInst(BinOp.OR, inst, is64)
        0x5 -> return makeBinAluInst(BinOp.AND, inst, is64)
        0x6 -> return makeBinAluInst(BinOp.LSH, inst, is64)
        0x7 -> return makeBinAluInst(BinOp.RSH, inst, is64)
        0x8 -> return makeUnAluInst(UnOp.NEG, inst, is64)
        0x9 -> return makeBinAluInst(BinOp.MOD, inst, is64)
        0xa -> return makeBinAluInst(BinOp.XOR, inst, is64)
        0xb -> return makeBinAluInst(BinOp.MOV, inst, is64)
        0xc -> return makeBinAluInst(BinOp.ARSH, inst, is64)
        0xd -> {
            // LE16/LE32/LE64/BE16/BE32/BE64
            sbfLogger.warn{"unsupported LE/BE instruction: generated instead dst := havoc()"}
            return SbfInstruction.Havoc(Value.Reg(SbfRegister.getByValue(inst.dst)))
        }
        else -> throw DisassemblerError("invalid ALU instruction")
    }
}

@TestOnly
fun makeLddw(inst: SbfBytecode, insts: List<SbfBytecode>, pc: Int): SbfInstruction {
    if (pc >= insts.size - 1) {
        throw DisassemblerError("incomplete LDDW")
    }
    if (inst.src != SbfRegister.R0_RETURN_VALUE.value && inst.src != SbfRegister.R1_ARG.value) {
        throw DisassemblerError("LDDW uses reserved registers")
    }
    if (inst.offset != 0.toShort()) {
        throw DisassemblerError("LDDW uses zero offset")
    }
    if (inst.src == SbfRegister.R1_ARG.value) {
        // magic number, meaning we're a per-process file descriptor defining the map.
        // (for details, look for BPF_PSEUDO_MAP_FD in the kernel)
        throw DisassemblerError("translation of LDDW failed because maps are not supported")
    }
    check(pc+1 < insts.size) {"LDDW accessing out-of-bounds: " +
                                    "accessing at index ${pc+1} "
                                    "with bytecode of size=${insts.size}"}
    val nextInst = insts[pc+1]
    if (nextInst.opcode.toInt() != 0 ||
            nextInst.dst != SbfRegister.R0_RETURN_VALUE.value ||
            nextInst.src != SbfRegister.R0_RETURN_VALUE.value ||
            nextInst.offset.toInt() != 0 ) {
        throw DisassemblerError("invalid LDDW")
    }
    return SbfInstruction.Bin(BinOp.MOV,
            dst= Value.Reg(SbfRegister.getByValue(inst.dst)),
            v = Value.Imm(merge(inst.imm, nextInst.imm)),
            is64 = true)
}

// Make a call instruction to an internal function (i.e., its code is available)
fun makeCallInst(@Suppress("UNUSED_PARAMETER") inst: SbfBytecode,
                 function: SbfFunction): SbfInstruction =
    SbfInstruction.Call(name = function.name, entryPoint = function.entryPoint)


// Make a call instruction to a syscall.
fun makeCallInst(@Suppress("UNUSED_PARAMETER") inst: SbfBytecode,
                 syscall: ExternalFunction): SbfInstruction =
    SbfInstruction.Call(name = syscall.name)


fun makeCallRegInst(inst: SbfBytecode): SbfInstruction {
    return SbfInstruction.CallReg(Value.Reg(SbfRegister.getByValue(inst.src)))
}

fun getJumpOp(inst: SbfBytecode): CondOp {
    // Remember that inst.opcode occupies 1 byte
    // Here we extract the highest four bits which tells us which kind of jump
    when (inst.opcode.toInt().shr(4) and 0xf ) {
        0x0 -> throw DisassemblerError("unexpected JMP op 0x0")
        0x1 -> return CondOp.EQ
        0x2 -> return CondOp.GT
        0x3 -> return CondOp.GE
        0x4 -> throw DisassemblerError("unsupported JMP SET op")
        0x5 -> return CondOp.NE
        0x6 -> return CondOp.SGT
        0x7 -> return CondOp.SGE
        0x8 -> throw DisassemblerError("unexpected JMP op 0x8")
        0x9 -> throw DisassemblerError("unexpected JMP op 0x9")
        0xa -> return CondOp.LT
        0xb -> return CondOp.LE
        0xc -> return CondOp.SLT
        0xd -> return CondOp.SLE
        else -> throw DisassemblerError("invalid JMP op")
    }
}

// A jump instruction can be call, exit or jump
fun makeJumpInst(inst: SbfBytecode, isRelocatedCall: Boolean,
                 insts: List<SbfBytecode>, pc:Int,
                 functionMan: MutableSbfFunctionManager): SbfInstruction {
    // Remember that inst.opcode occupies 1 byte
    // Here we extract the highest four bits which tells us whether the operation is a jump, call or exit
    when (inst.opcode.toInt().shr(4) and 0xF) {
        0x8 -> {
            if (!isRelocatedCall) {
                if (inst.opcode.toInt() and 0x0F == 0xd) {
                    /**
                     *  callx instruction: the callee is stored in a register
                     **/
                    return makeCallRegInst(inst)
                } else {
                    /**
                     * The called function is an internal call (sbf to sbf call).
                     * We know that the called function starts at ${target}.
                     * We add to the function manager a new function that starts at ${target}
                     **/
                    val target = pc + inst.imm + 1
                    val function = functionMan.getFunction(functionMan.addFunction(target.toLong()))
                    check(function != null) {"Cannot find a function that was just added"}
                    return makeCallInst(inst, function)
                }
            } else {
                /**
                 * The called function is a relocation symbol which
                 * was already identified by the disassembler.
                 * We distinguish between a user-defined function or a Solana syscall.
                **/
                // Order matters: check first whether user-defined function
                val function = functionMan.getFunction(inst.imm)
                return if (function != null) {
                    makeCallInst(inst, function)
                } else {
                    val solFunction = SolanaFunction.from(inst.imm)
                    check(solFunction != null) {"The disassembler ensures that it cannot be null"}
                    makeCallInst(inst, solFunction.syscall)
                }
            }
        }
        0x9 -> return SbfInstruction.Exit()
        else -> {
            val newPC = pc + 1 + inst.offset
            if (newPC >= insts.size) {
                throw DisassemblerError("jump out of bounds")
            } else if (insts[newPC].opcode.toInt() == 0) {
                throw DisassemblerError("jump to the middle of lddw")
            }
            val newPCLabel = Label.Address(newPC.toLong())
            return if (inst.opcode.toInt() == SbfInstructionCodes.INST_OP_JA.opcode) {
                // unconditional jump
                SbfInstruction.Jump.UnconditionalJump(newPCLabel)
            } else {
                // conditional jump
                val cond = Condition(op = getJumpOp(inst),
                        left = Value.Reg(SbfRegister.getByValue(inst.dst)),
                        right = if (inst.opcode.toInt() and SbfInstructionCodes.INST_SRC_REG.opcode != 0) {
                            Value.Reg(SbfRegister.getByValue(inst.src))
                        } else {
                            Value.Imm(inst.imm.toULong())
                        })
                SbfInstruction.Jump.ConditionalJump(cond, newPCLabel)

            }
        }
    }
}

/** Translate a bytecode instruction to an SbfInstruction **/
private fun bytecodeToInstruction(pc: Int, inst: SbfBytecode, bytecode: BytecodeProgram):
    Pair<SbfInstruction, Boolean> {
    var isLddwInst = false
    val newInst = when (inst.opcode.toInt() and SbfInstructionCodes.INST_CLS_MASK.opcode) {
        SbfInstructionCodes.INST_CLS_LD.opcode -> {
            if (inst.opcode.toInt() == SbfInstructionCodes.INST_OP_LDDW_IMM.opcode) {
                isLddwInst = true
                makeLddw(inst, bytecode.program, pc)
            } else {
                makeMemInst(inst)
            }
        }
        SbfInstructionCodes.INST_CLS_LDX.opcode, SbfInstructionCodes.INST_CLS_ST.opcode,
        SbfInstructionCodes.INST_CLS_STX.opcode -> {
            makeMemInst(inst)
        }
        SbfInstructionCodes.INST_CLS_ALU.opcode, SbfInstructionCodes.INST_CLS_ALU64.opcode -> {
            makeAluInst(inst)
        }
        SbfInstructionCodes.INST_CLS_JMP32.opcode, SbfInstructionCodes.INST_CLS_JMP.opcode -> {
            val isRelocatedCall = (bytecode.relocatedCalls.contains(pc))
            makeJumpInst(inst, isRelocatedCall, bytecode.program, pc, bytecode.functionMan)
        }
        else -> null
    } ?: throw DisassemblerError("Unrecognized SBF instruction $inst in the ELF file")
    val newMetadata = newInst.metaData.plus(Pair(SbfMeta.SBF_ADDRESS, inst.address.toULong()))
    return Pair(newInst.copyInst(newMetadata), isLddwInst)
}

/**
 * Translate a program consisting of bytecode instructions into a sequence of
 * labeled SbfInstructions
 **/
fun bytecodeToSbfProgram(bytecode: BytecodeProgram): SbfProgram {
    if (bytecode.program.isEmpty()) {
        throw DisassemblerError("zero-length programs are not allowed")
    }
    val newInsts = ArrayList<SbfLabeledInstruction>()
    var pc = 0
    var exitCount = 0 // number of exit instructions
    while (pc < bytecode.program.size) {
        val inst = bytecode.program[pc]
        val (newInst, isLddwInst) = bytecodeToInstruction(pc, inst, bytecode)
        if (newInst is SbfInstruction.Exit) {
            exitCount++
        }
        newInsts.add(Pair(Label.Address(pc.toLong()), newInst))
        pc++
        if (isLddwInst) {
            // skip the immediate value that has been already processed
            pc++
        }
    } // end while
    if (exitCount == 0) {
        throw DisassemblerError("program does not exit")
    }
    bytecode.functionMan.warnDuplicateSymbols()
    return SbfProgram(bytecode.entriesMap, bytecode.functionMan, bytecode.globals, newInsts)
}
