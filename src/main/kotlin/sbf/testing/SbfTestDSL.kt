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

package sbf.testing

import sbf.cfg.*
import sbf.disassembler.SbfRegister
import sbf.disassembler.Label
import datastructures.stdcollections.*
import sbf.callgraph.CVTCore
import kotlin.reflect.KProperty

/**
 * A DSL for constructing SBF CFGSs.
 *
 * The usage is best illustrated through the tests for this module.
 */
object SbfTestDSL {

    /**
     * The entrypoint for creating a CFG.
     * Notes:
     * - Either the entry label is provided OR the _first_ block constructed by
     *   [builder] will be used as the entry block
     *
     * - Either a block ends in a jump OR if one is missing, an unconditional jump is added
     *   to the next block constructed by [builder]
     *
     */
    fun makeCFG(
        name: String,
        entry: Long? = null,
        normalize: Boolean = true,
        builder: CFGBuilderScope.() -> Unit
    ): MutableSbfCFG {
        val cfg = MutableSbfCFG(name)
        CFGBuilderScope().apply {
            builder()
            val blocks = blocks()

            for ((l, instrs) in blocks) {
                val label = Label.Address(l)
                val bb = cfg.getOrInsertBlock(label)
                bb.addAll(instrs)
            }

            // Make sure each block exists in the graph
            // Now walk backward so that we can add an implicit successor
            var lastBlock: MutableSbfBasicBlock? = null
            for ((l, instrs) in blocks.reversed()) {
                val label = Label.Address(l)
                val bb = cfg.getOrInsertBlock(label)
                instrs.succs()?.forEach { s ->
                    val succBlock = cfg.getOrInsertBlock(s)
                    bb.addSucc(succBlock)
                } ?: lastBlock?.let {
                    bb.add(SbfInstruction.Jump.UnconditionalJump(it.getLabel()))
                    bb.addSucc(it)
                }
                lastBlock = bb
            }

            val entryBlock = entry?.let {
                cfg.getMutableBlock(Label.Address(it))
                    ?: throw IllegalArgumentException("Entry label $it does not exist in constructed CFG")
            } ?: cfg.getMutableBlock(Label.Address(blocks.first().first))!!

            cfg.setEntry(entryBlock)
        }

        return cfg.apply {
            if (normalize) {
                normalize()
            }
        }
    }

    private fun List<SbfInstruction>.succs(): Set<Label>? {
        return when (val i = this.last()) {
            is SbfInstruction.Exit -> setOf()
            is SbfInstruction.Jump.UnconditionalJump -> setOf(i.target)
            is SbfInstruction.Jump.ConditionalJump -> i.falseTarget?.let {
                setOf(i.target, it)
            } ?: setOf(i.target)
            else -> null
        }
    }

    class CFGBuilderScope {
        private val blocks: MutableList<Pair<Long, List<SbfInstruction>>> = mutableListOf()

        fun blocks(): List<Pair<Long, List<SbfInstruction>>> = blocks

        fun bb(l: Long, build: BlockBuilderScope.() -> Unit) {
            check (blocks.none { it.first == l })
            BlockBuilderScope().apply {
                build()
                blocks.add(l to instructions())
            }
        }
    }

    class BlockBuilderScope {
        var r0 by RegDelegate(BuilderValue.Reg(SbfRegister.R0_RETURN_VALUE))
        var r1 by RegDelegate(BuilderValue.Reg(SbfRegister.R1_ARG))
        var r2 by RegDelegate(BuilderValue.Reg(SbfRegister.R2_ARG))
        var r3 by RegDelegate(BuilderValue.Reg(SbfRegister.R3_ARG))
        var r4 by RegDelegate(BuilderValue.Reg(SbfRegister.R4_ARG))
        var r5 by RegDelegate(BuilderValue.Reg(SbfRegister.R5_ARG))
        var r6 by RegDelegate(BuilderValue.Reg(SbfRegister.R6))
        var r7 by RegDelegate(BuilderValue.Reg(SbfRegister.R7))
        var r8 by RegDelegate(BuilderValue.Reg(SbfRegister.R8))
        var r9 by RegDelegate(BuilderValue.Reg(SbfRegister.R9))
        var r10 by RegDelegate(BuilderValue.Reg(SbfRegister.R10_STACK_POINTER))

        sealed class BuilderValue {
            abstract val v: Value
            data class Reg(val reg: SbfRegister): BuilderValue() {
                override val v: Value.Reg
                    get() = Value.Reg(reg)
            }
            data class Imm(val i: ULong): BuilderValue() {
                override val v: Value.Imm
                    get() = Value.Imm(i)
            }
        }

        inner class RegDelegate(val r: BuilderValue.Reg) {
            operator fun getValue(where: BlockBuilderScope, property: KProperty<*>) : Any {
                return r
            }
            operator fun setValue(where: BlockBuilderScope, property: KProperty<*>, what: Any) {
                where.apply {
                    r.`=`(what)
                }
            }

        }


        operator fun Any.get(offset: Short): BuilderLVal {
            check (this@get is BuilderValue.Reg)
            return BuilderLVal(this@get, offset)
        }

        operator fun Any.set(offset: Short, x: Any) {
            check (this@set is BuilderValue.Reg)
            SbfInstruction.Mem(
                Deref(8, this@set.v, offset), x.builderValue.v, isLoad = false
            ).push()
        }

        private val instructions: MutableList<SbfInstruction> = mutableListOf()

        fun instructions(): List<SbfInstruction> = instructions

        private fun SbfInstruction.push() = instructions.add(this)

        private val Any.builderValue: BuilderValue
            get() = when(this@builderValue) {
                is BuilderValue.Reg -> this@builderValue
                is ULong -> BuilderValue.Imm(this@builderValue)
                is UInt -> BuilderValue.Imm(this@builderValue.toULong())
                is Long -> BuilderValue.Imm(this@builderValue.toULong())
                is Int -> BuilderValue.Imm(this@builderValue.toULong())
                is RegDelegate -> this@builderValue.r
                else -> throw UnsupportedOperationException("Unsupported builder value: ${this@builderValue}")
            }

        operator fun CondOp.invoke(arg: Any, arg1: Any): Condition {
            check (arg is BuilderValue.Reg)
            return Condition(
                this,
                arg.v,
                arg1.builderValue.v
            )
        }

        operator fun BinOp.invoke(arg: Any, arg1: Any) {
            check (arg is BuilderValue.Reg)
            SbfInstruction.Bin(
            this,
                arg.v,
                arg1.builderValue.v,
                true
            ).push()
        }

        data class BuilderLVal(val v: BuilderValue.Reg, val offset: Short) {
            operator fun plus(off: Short) = BuilderLVal(v, (this.offset + off).toShort())
        }

        private fun binOp(op: BinOp, lhs: BuilderValue.Reg, rhs: Any, is64:Boolean = true) {
            SbfInstruction.Bin(op, lhs.v, rhs.builderValue.v, is64).push()
        }

        @Suppress("UNUSED")
        infix fun BuilderValue.Reg.`=`(x: Any) {
            when (x) {
                is BuilderLVal ->
                    SbfInstruction.Mem(
                        Deref(
                            8,
                            x.v.v,
                            x.offset
                        ),
                        this.v,
                        isLoad = true
                    ).push()
                else ->
                    binOp(BinOp.MOV, this, x)
            }
        }

        operator fun BuilderValue.Reg.plusAssign(x: Any) = binOp(BinOp.ADD, this, x)
        operator fun BuilderValue.Reg.timesAssign(x: Any) = binOp(BinOp.MUL, this, x)

        @Suppress("UNUSED")
        infix fun BuilderLVal.`=`(x: Any) {
            SbfInstruction.Mem(
                Deref(
                    8,
                    Value.Reg(this.v.reg),
                    this.offset,
                ),
                x.builderValue.v,
                isLoad = false
            ).push()
        }

        fun select(lhs: Any, c: Condition, tt: Any, ff: Any) {
            check (lhs is BuilderValue.Reg)
            SbfInstruction.Select(
                lhs.v,
                c,
                tt.builderValue.v,
                ff.builderValue.v
            ).push()
        }

        fun goto(l: Long) {
            SbfInstruction.Jump.UnconditionalJump(
                Label.Address(l)
            ).push()
        }

        fun br(c: Condition, l1: Long, l2: Long) {
            SbfInstruction.Jump.ConditionalJump(
                c,
                Label.Address(l1),
                Label.Address(l2),
            ).push()
        }

        fun assume(c: Condition) {
            SbfInstruction.Assume(c).push()
        }

        fun assert(c: Condition) {
            SbfInstruction.Assert(c).push()
        }

        fun push(callName: String, callId: ULong) {
            val metaData = MetaData().plus(
                SbfMeta.CALL_ID to callId
            ).plus(
                SbfMeta.INLINED_FUNCTION_NAME to callName
            )
            SbfInstruction.Call(name = CVTCore.SAVE_SCRATCH_REGISTERS.function.name, metaData = metaData).push()
        }

        fun pop(callName: String, callId: ULong) {
            val metaData = MetaData().plus(
                SbfMeta.CALL_ID to callId
            ).plus(
                SbfMeta.INLINED_FUNCTION_NAME to callName
            )
            SbfInstruction.Call(name = CVTCore.RESTORE_SCRATCH_REGISTERS.function.name, metaData = metaData).push()
        }

        fun exit() {
            SbfInstruction.Exit().push()
        }

        operator fun String.invoke() {
            SbfInstruction.Call(name = this).push()
        }
    }
}
