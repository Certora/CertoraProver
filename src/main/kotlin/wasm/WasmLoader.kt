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

package wasm

import com.dylibso.chicory.wasm.Parser
import com.dylibso.chicory.wasm.types.*
import com.dylibso.chicory.wasm.WasmModule
import datastructures.stdcollections.*
import log.*
import wasm.debugsymbols.WasmDebugSymbolLoader
import wasm.ir.*
import wasm.ir.WasmInstruction.Control
import wasm.ir.WasmInstruction.Numeric.*
import wasm.ir.WasmInstruction.Parametric
import wasm.tokens.WasmTokens
import java.io.File
import java.math.BigInteger
import java.util.Optional

private val wasmLogger = Logger(LoggerTypes.WASM)

class WasmLoader(wasmFile: File) {

    val module: WasmModule = Parser.parse(wasmFile)
    val debugSymbols = WasmDebugSymbolLoader.generate(wasmFile, module, true)

    private val I32_BOUND = BigInteger.TWO.pow(32);
    private val I64_BOUND = BigInteger.TWO.pow(64);


    /*+
    * [idx] represents an index into the table section.
    * This index matches elementidx of https://webassembly.github.io/gc/core/syntax/modules.html#indices
    */
    private data class ElementIndex(val idx: Int) {
        override fun toString(): String {
            return "Element_${idx}"
        }
        fun toWasmName() = WasmName("\$e$idx")
    }

    /*+
    * [idx] represents an index into the table section.
    * This index matches dataidx of https://webassembly.github.io/gc/core/syntax/modules.html#indices
    */
    private data class DataIndex(val idx: Int) {
        override fun toString(): String {
            return "Data_${idx}"
        }
        fun toWasmName() = WasmName(this.toString())
    }

    /*+
     * [idx] represents an index into the table section.
     * This index matches tableidx of https://webassembly.github.io/gc/core/syntax/modules.html#indices
     */
    private data class TableIndex(val idx: Int) {
        override fun toString(): String {
            return "Table_${idx}"
        }
        fun toWasmName() = WasmName("\$T$idx")
    }


    /*+
     * [idx] represents an index into the global section.
     * This index matches globalidx of https://webassembly.github.io/gc/core/syntax/modules.html#indices
     */
    private data class GlobalIndex(val idx: Int) {
        override fun toString(): String {
            return "Global_${idx}"
        }
    }

    /*+
     * [idx] represents an index into the types section.
     * This index matches typeidx of https://webassembly.github.io/gc/core/syntax/modules.html#indices
     */
    private data class TypeIndex(val idx: Int) {
        override fun toString(): String {
            return "TypeIndex_${idx}"
        }

        fun toWasmName() = WasmName("\$t$idx")
    }


    /*+
     * [idx] represents the index of the function in the code section.
     * This index matches funcidx of https://webassembly.github.io/gc/core/syntax/modules.html#indices
     */
    private data class FunctionIndex(val idx: Int) {
        override fun toString(): String {
            return "FunctionIndex_${idx}"
        }
    }

    /*+
     * [idx] represents the index of the function in the code section.
     * This index does _not_ have a correspondence in https://webassembly.github.io/gc/core/syntax/modules.html#indices
     *
     * Chicory parses the code section and creates a [FunctionBody] per parsed function in the code section.
     * The index represents the index of the parsed function body.
     *
     * Be aware that the code section does _not_ include function bodies for imported, so the [FunctionIndex] and [FunctionBodyIndex]
     * are not exchangeable. See logic around [toFunctionIndex] below.
     */
    private data class FunctionBodyIndex(val idx: Int) {
        override fun toString(): String {
            return "FunctionBodyIndex_${idx}"

        }
    }

    private fun FunctionBodyIndex.toFunctionIndex() = FunctionIndex(idx + importedFunctionSize)

    /**
     * Every instruction "call funcIdx" will call one of the imported functions when funcIdx < importedFunctionSize.
     * Otherwise, a call invokes the function that is located at codeSection[funcIdx-importedFunctionSize]
     *
     * This integer keeps track of this offset.
     */
    private val importedFunctionSize =
        module.importSection().stream().toList().count { it.importType() == ExternalType.FUNCTION }

    private val uniqueNamer = mutableMapOf<String, Int>()
    private val indexTable = mutableMapOf<FunctionIndex, WasmName>()

    private fun FunctionIndex.toWasmName(): WasmName {
        fun lookUpInNameSection(): String? =
            (module.customSection("name") as? NameCustomSection)?.nameOfFunction(this.idx)

        fun lookUpInExportSection(): String? {
            val exportSection = module.exportSection()
            for (i in 0..<exportSection.exportCount()) {
                val export = exportSection.getExport(i)
                if (export.exportType() == ExternalType.FUNCTION) {
                    if (export.index() == this.idx) {
                        return export.name()
                    }
                }
            }
            return null
        }
        return indexTable.getOrPut(this) {
            val lookupNameInSection = (lookUpInExportSection() ?: lookUpInNameSection())
                ?.let {
                    val ctr = uniqueNamer.getOrPut(it) { 0 }
                    uniqueNamer[it] = ctr + 1
                    WasmName(if (ctr == 0) { "\$$it" } else { "\$$it.$ctr" })
                }

            lookupNameInSection ?: WasmName(this.toString())
        }

    }

    private fun GlobalIndex.toWasmName(): WasmName{
        val res = (module.customSection("name") as? NameCustomSection)?.nameOfGlobal(this.idx)
        return res?.let { WasmName("\$$res") } ?: WasmName(this.toString())
    }

    private fun <T : Section, R> checkSectionNonNull(section: T?, block: (T) -> R): R? {
        fun warn(): R? {
            wasmLogger.debug { "The ${section} of the .wasm file is empty, proceeding without parsing it" }
            return null
        }
        return section?.let {
            return block(it)
        } ?: warn()
        ;
    }

    private fun <T : Section, R> checkSectionPresent(section: Optional<T>, block: (T) -> R): R? =
        checkSectionNonNull(section.orElse(null), block)


    fun convert(): WasmProgram {
        return WasmProgram(
            listOf(
                convertImportSection(),
                convertGlobalSection(),
                convertCodeSection(),
                convertDataSection(),
                convertElementSection(),
                convertExportSection(),
                convertMemorySection(),
                convertTableSection(),
                convertTypeSection(),
                convertEmbeddedRuleSection(),
            ).flatten(),
            debugSymbols
        )
    }

    private fun convertCodeSection(): List<WasmModuleField> {
        val codeSection = module.codeSection()
        val functionSection = module.functionSection()
        return checkSectionNonNull(codeSection) { cs ->
            cs.functionBodies().mapIndexedNotNull { fbIdx, fb ->
                val funcIdx = FunctionBodyIndex(fbIdx).toFunctionIndex()
                WasmFunction(
                    funcIdx.toWasmName(),
                    convertFunctionTypeToWasmType(TypeIndex(functionSection.getFunctionType(fbIdx))).toWasmTypeUse(),
                    fb.localTypes().filterNotNull().map { convertValueType(it) },
                    convertInstructions(0, fb.instructions()).second
                )
            }
        } ?: listOf()
    }

    private fun convertDataSection(): List<WasmModuleField> {
        val res = mutableListOf<WasmData>()
        checkSectionNonNull(module.dataSection()) { ds ->
            res.addAll(ds.dataSegments().mapIndexedNotNull { idx, seg ->
                when(seg){
                    is ActiveDataSegment -> WasmData(DataIndex(idx).toWasmName(), convertNonControlFlowInstruction(seg.offsetInstructions().first()!!),seg.data().map { it.toUByte() })
                    is PassiveDataSegment -> TODO()
                    else -> TODO()
                }
            })
        }
        return res
    }

    private fun convertMemorySection(): List<WasmModuleField> {
        val res = mutableListOf<WasmMemory>()
        val memorySection = module.memorySection()
        checkSectionPresent(memorySection) { ms ->
            for (i in 0..<ms.memoryCount()) {
                ms.getMemory(0)
                    ?.let { m -> res.add(WasmMemory(WasmName(WasmTokens.MEMORY), m.limits().initialPages())) }
            }
        }
        return res
    }


    private fun convertGlobalSection(): List<WasmModuleField> {
        val globalSection = module.globalSection()
        return checkSectionNonNull(globalSection) { gs ->
            gs.globals().mapIndexedNotNull { idx, x ->
                check(x.initInstructions().size == 1)
                WasmGlobal(
                    GlobalIndex(idx).toWasmName(),
                    when (x.mutabilityType()) {
                        MutabilityType.Const -> WasmMutability.MUT
                        MutabilityType.Var -> WasmMutability.MUT //@Chandra, we should add a new type here. What do we use this MUT for?
                        null -> error("Cannot convert mutabilityType null")
                    },
                    convertValueType(x.valueType()),
                    convertNonControlFlowInstruction(
                        x.initInstructions().first()
                    )
                )
            }
        } ?: listOf()
    }

    private fun convertElementSection(): List<WasmModuleField> {
        return checkSectionNonNull(module.elementSection()){es ->
            es.elements().mapIndexedNotNull{ idx, el ->
                when(el){
                    is ActiveElement ->{
                        val name = ElementIndex(idx).toWasmName()
                        val constant = el.offset().firstOrNull()?.let { I32Const(I32Value(BigInteger.valueOf(it.operands()[0])), WASMAddress(it.address())) }
                        val funcNames =  el.initializers().mapNotNull { it.firstOrNull() }.map {
                            check(it.opcode() == OpCode.REF_FUNC)
                            val funcIndex = it.operands()[0].toInt()
                            val functionInfo = FunctionIndex(funcIndex).toWasmName()
                            functionInfo.toString()}
                        constant?.let { c ->
                            WasmElem(name, c, funcNames)
                        }

                    }
                    else -> null
                }
            }
        } ?: listOf()
    }

    private fun convertTableSection(): List<WasmModuleField> {
        val res = mutableListOf<WasmModuleField>()
        val tableSection = module.tableSection()
        checkSectionNonNull(tableSection) { ts ->
            for (i in 0..<ts.tableCount()) {
                val table = ts.getTable(i)
                val conv = when (table.elementType()) {
                    ValueType.FuncRef -> WasmTable(
                        TableIndex(i).toWasmName(), table.limits().min().toInt(), table.limits().max().toInt()
                    )
                    //only Function seemed to be supported currently
                    else -> {
                        null
                    }
                }
                conv?.let { res.add(it) }
            }
        }

        return res
    }

    private fun convertExportSection(): List<WasmModuleField> {
        val res = mutableListOf<WasmModuleField>()
        val exportSec = module.exportSection()
        checkSectionNonNull(exportSec) { es ->
            for (i in 0..<es.exportCount()) {
                val export = es.getExport(i)
                res.add(
                    when (export.exportType()) {
                        ExternalType.FUNCTION -> {
                            val funcIdx = FunctionIndex(export.index())
                            val wasmName = funcIdx.toWasmName()
                            WasmExport(
                                export.name(), ExportFunc(wasmName)
                            )
                        }

                        ExternalType.TABLE -> WasmExport(
                            export.name(), ExportTable(WasmName(export.name()))
                        )

                        ExternalType.MEMORY -> WasmExport(
                            export.name(), ExportMemory(WasmName("$"+export.name()))
                        )

                        ExternalType.GLOBAL -> WasmExport(
                            export.name(), ExportGlobal(WasmName(export.name()))
                        )

                        ExternalType.TAG -> error("Cannot convert a tag")
                        null -> error("Cannot convert a tag")
                    }
                )
            }

        }
        return res
    }

    private val CERTORA_RULES_SECTION = "certora_rules"

    private fun convertEmbeddedRuleSection(): List<WasmModuleField> {
        val rules = module.customSection(CERTORA_RULES_SECTION) as? UnknownCustomSection ?: return listOf()

        // We don't expect enough rules or long enough function names
        // for the inefficiency below to matter
        val names = rules.bytes().fold(listOf<String>() to listOf<Byte>()) { (l, bs), nextByte ->
            if (nextByte == 0.toByte()) {
                val str = bs.toByteArray().toString(Charsets.UTF_8)
                (l + str) to listOf()
            } else {
                l to (bs + nextByte)
            }
        }.first

        return listOf(
            WasmCVTRule(names)
        )
    }

    private fun convertImportSection(): List<WasmModuleField> {
        val importSection = module.importSection()
        return checkSectionNonNull(importSection) { importSec ->
            importSec.stream().toList().mapIndexedNotNull { idx, x ->
                when (x.importType()) {
                    ExternalType.FUNCTION -> {
                        WasmImport(
                            x.module(), x.name(), ImportFunc(
                                FunctionIndex(idx).toWasmName(),
                                WasmTypeUse(convertFunctionTypeToWasmType(TypeIndex((x as FunctionImport).typeIndex())).name,
                                    listOf(), null
                                )
                            )
                        )
                    }

                    ExternalType.TABLE -> TODO()
                    ExternalType.MEMORY -> TODO()
                    ExternalType.GLOBAL -> TODO()
                    ExternalType.TAG -> TODO()
                    null -> TODO()
                }
            }
        } ?: listOf()
    }

    private fun convertTypeSection(): List<WasmModuleField> {
        val typeSection = module.typeSection()
        return checkSectionNonNull(typeSection) { ts ->
            ts.types().mapIndexedNotNull { idx, _ -> convertFunctionTypeToWasmType(TypeIndex(idx)) }
        } ?: listOf()
    }

    private fun convertFunctionTypeToWasmType(typeIdx: TypeIndex): WasmType {
        val funcType = module.typeSection().getType(typeIdx.idx);
        return WasmType(typeIdx.toWasmName(),
            funcType.params().mapNotNull { convertValueType(it) },
            funcType.returns().firstOrNull()?.let { convertValueType(it) })
    }

    private fun convertValueType(value: ValueType): WasmPrimitiveType {
        return when (value) {
            ValueType.F32 -> WasmPrimitiveType.F32
            ValueType.F64 -> WasmPrimitiveType.F64
            ValueType.I64 -> WasmPrimitiveType.I64
            ValueType.I32 -> WasmPrimitiveType.I32
            ValueType.UNKNOWN,
            ValueType.V128,
            ValueType.FuncRef,
            ValueType.ExternRef,
            ValueType.ExnRef -> error("Cannot convert ${value}")
        }
    }

    private fun Long.positiveModulus(modulo: BigInteger): BigInteger {
        return if (this < 0) {
            modulo.subtract(BigInteger.valueOf(this).abs())
        } else {
            BigInteger.valueOf(this)
        }
    }

    /**
     * Conversion of all instructions below
     */
    private fun convertNonControlFlowInstruction(ins: Instruction): WasmInstruction {
        val unimplemented: WasmInstruction by utils.lazy<WasmInstruction> { TODO("Unimplemented: $ins") }

        return when (ins.opcode()) {
            OpCode.LOCAL_GET -> WasmInstruction.Variable.LocalGet(WASMLocalIdx(ins.operands().first().toInt()), WASMAddress(ins.address()))
            OpCode.LOCAL_SET -> WasmInstruction.Variable.LocalSet(WASMLocalIdx(ins.operands().first().toInt()), WASMAddress(ins.address()))
            OpCode.LOCAL_TEE -> WasmInstruction.Variable.LocalTee(WASMLocalIdx(ins.operands().first().toInt()), WASMAddress(ins.address()))

            OpCode.GLOBAL_GET -> WasmInstruction.Variable.GlobalGet(
                (GlobalIndex(ins.operands().first().toInt()).toWasmName()).toString(), WASMAddress(ins.address())
            )

            OpCode.GLOBAL_SET -> WasmInstruction.Variable.GlobalSet(
                (GlobalIndex(ins.operands().first().toInt()).toWasmName()).toString(), WASMAddress(ins.address())
            )

            OpCode.DROP -> Parametric.Drop(WASMAddress(ins.address()))
            OpCode.SELECT -> Parametric.Select(WASMAddress(ins.address()))

            OpCode.CALL -> {
                val idx = ins.operands()[0].toInt()
                val funcId = FunctionIndex(idx)
                val functionInfo = funcId.toWasmName()
                Control.Call(functionInfo, WASMAddress(ins.address()));
            }

            OpCode.CALL_INDIRECT -> Control.CallIndirect(
                //The first operand is an offset into the table section.
                TableIndex(ins.operands()[1].toInt()).toWasmName(),
                //The zero operand is an offset into the type index section.
                WasmTypeUse(TypeIndex(ins.operands()[0].toInt()).toWasmName(), listOf(), null),
                WASMAddress(ins.address())
            );
            OpCode.CALL_REF -> unimplemented
            OpCode.SELECT_T -> unimplemented

            OpCode.TABLE_GET -> unimplemented
            OpCode.TABLE_SET -> unimplemented

            OpCode.MEMORY_SIZE -> WasmInstruction.Memory.Size(WASMAddress(ins.address()))
            OpCode.MEMORY_GROW -> WasmInstruction.Memory.Grow(WASMAddress(ins.address()))
            OpCode.MEMORY_COPY -> WasmInstruction.Memory.Copy(ins.operands()[1].toInt(), ins.operands()[0].toInt(), WASMAddress(ins.address()))
            OpCode.MEMORY_FILL -> WasmInstruction.Memory.Fill(ins.operands()[0].toInt(), WASMAddress(ins.address()))

            OpCode.I32_LOAD -> WasmInstruction.Memory.Load(LoadMemoryOp.I32LOAD, WASMMemoryOffset(ins.operands()[1].toInt()), ins.operands()[0].toInt(), WASMAddress(ins.address()))
            OpCode.I64_LOAD -> WasmInstruction.Memory.Load(LoadMemoryOp.I64LOAD, WASMMemoryOffset(ins.operands()[1].toInt()), ins.operands()[0].toInt(), WASMAddress(ins.address()))
            OpCode.F32_LOAD -> WasmInstruction.Memory.Load(LoadMemoryOp.F32LOAD, WASMMemoryOffset(ins.operands()[1].toInt()), ins.operands()[0].toInt(), WASMAddress(ins.address()))
            OpCode.F64_LOAD -> WasmInstruction.Memory.Load(LoadMemoryOp.F64LOAD, WASMMemoryOffset(ins.operands()[1].toInt()), ins.operands()[0].toInt(), WASMAddress(ins.address()))

            OpCode.I32_LOAD8_S -> WasmInstruction.Memory.Load(LoadMemoryOp.I32LOAD8_S, WASMMemoryOffset(ins.operands()[1].toInt()), ins.operands()[0].toInt(), WASMAddress(ins.address()))
            OpCode.I32_LOAD8_U -> WasmInstruction.Memory.Load(LoadMemoryOp.I32LOAD8_U, WASMMemoryOffset(ins.operands()[1].toInt()), ins.operands()[0].toInt(), WASMAddress(ins.address()))
            OpCode.I32_LOAD16_S -> WasmInstruction.Memory.Load(LoadMemoryOp.I32LOAD16_S, WASMMemoryOffset(ins.operands()[1].toInt()), ins.operands()[0].toInt(), WASMAddress(ins.address()))
            OpCode.I32_LOAD16_U -> WasmInstruction.Memory.Load(LoadMemoryOp.I32LOAD16_U, WASMMemoryOffset(ins.operands()[1].toInt()), ins.operands()[0].toInt(), WASMAddress(ins.address()))
            OpCode.I64_LOAD8_S -> WasmInstruction.Memory.Load(LoadMemoryOp.I64LOAD8_S, WASMMemoryOffset(ins.operands()[1].toInt()), ins.operands()[0].toInt(), WASMAddress(ins.address()))
            OpCode.I64_LOAD8_U -> WasmInstruction.Memory.Load(LoadMemoryOp.I64LOAD8_U, WASMMemoryOffset(ins.operands()[1].toInt()), ins.operands()[0].toInt(), WASMAddress(ins.address()))
            OpCode.I64_LOAD16_S -> WasmInstruction.Memory.Load(LoadMemoryOp.I64LOAD16_S, WASMMemoryOffset(ins.operands()[1].toInt()), ins.operands()[0].toInt(), WASMAddress(ins.address()))
            OpCode.I64_LOAD16_U -> WasmInstruction.Memory.Load(LoadMemoryOp.I64LOAD16_U, WASMMemoryOffset(ins.operands()[1].toInt()), ins.operands()[0].toInt(), WASMAddress(ins.address()))
            OpCode.I64_LOAD32_S -> WasmInstruction.Memory.Load(LoadMemoryOp.I64LOAD32_S, WASMMemoryOffset(ins.operands()[1].toInt()), ins.operands()[0].toInt(), WASMAddress(ins.address()))
            OpCode.I64_LOAD32_U -> WasmInstruction.Memory.Load(LoadMemoryOp.I64LOAD32_U, WASMMemoryOffset(ins.operands()[1].toInt()), ins.operands()[0].toInt(), WASMAddress(ins.address()))

            OpCode.I32_STORE -> WasmInstruction.Memory.Store(StoreMemoryOp.I32STORE, WASMMemoryOffset(ins.operands()[1].toInt()), ins.operands()[0].toInt(), WASMAddress(ins.address()))
            OpCode.I64_STORE -> WasmInstruction.Memory.Store(StoreMemoryOp.I64STORE, WASMMemoryOffset(ins.operands()[1].toInt()), ins.operands()[0].toInt(), WASMAddress(ins.address()))
            OpCode.F32_STORE -> WasmInstruction.Memory.Store(StoreMemoryOp.F32STORE, WASMMemoryOffset(ins.operands()[1].toInt()), ins.operands()[0].toInt(), WASMAddress(ins.address()))
            OpCode.F64_STORE -> WasmInstruction.Memory.Store(StoreMemoryOp.F64STORE, WASMMemoryOffset(ins.operands()[1].toInt()), ins.operands()[0].toInt(), WASMAddress(ins.address()))
            OpCode.I32_STORE8 -> WasmInstruction.Memory.Store(StoreMemoryOp.I32STORE8, WASMMemoryOffset(ins.operands()[1].toInt()), ins.operands()[0].toInt(), WASMAddress(ins.address()))
            OpCode.I32_STORE16 -> WasmInstruction.Memory.Store(StoreMemoryOp.I32STORE16, WASMMemoryOffset(ins.operands()[1].toInt()), ins.operands()[0].toInt(), WASMAddress(ins.address()))
            OpCode.I64_STORE8 -> WasmInstruction.Memory.Store(StoreMemoryOp.I64STORE8, WASMMemoryOffset(ins.operands()[1].toInt()), ins.operands()[0].toInt(), WASMAddress(ins.address()))
            OpCode.I64_STORE16 -> WasmInstruction.Memory.Store(StoreMemoryOp.I64STORE16, WASMMemoryOffset(ins.operands()[1].toInt()), ins.operands()[0].toInt(), WASMAddress(ins.address()))
            OpCode.I64_STORE32 -> WasmInstruction.Memory.Store(StoreMemoryOp.I64STORE32, WASMMemoryOffset(ins.operands()[1].toInt()), ins.operands()[0].toInt(), WASMAddress(ins.address()))

            OpCode.I32_WRAP_I64 -> NumericUnary(UnaryNumericOp.I32WRAP64, WASMAddress(ins.address()))
            OpCode.I32_EXTEND_8_S -> NumericUnary(UnaryNumericOp.I32_EXTEND8_S, WASMAddress(ins.address()))
            OpCode.I32_EXTEND_16_S -> NumericUnary(UnaryNumericOp.I32_EXTEND16_S, WASMAddress(ins.address()))
            OpCode.I64_EXTEND_8_S -> NumericUnary(UnaryNumericOp.I64_EXTEND8_S, WASMAddress(ins.address()))
            OpCode.I64_EXTEND_16_S -> NumericUnary(UnaryNumericOp.I64_EXTEND16_S, WASMAddress(ins.address()))
            OpCode.I64_EXTEND_32_S -> NumericUnary(UnaryNumericOp.I64_EXTEND32_S, WASMAddress(ins.address()))
            OpCode.I64_EXTEND_I32_U -> NumericUnary(UnaryNumericOp.I64_EXTENDI32_U, WASMAddress(ins.address()))
            OpCode.I64_EXTEND_I32_S -> NumericUnary(UnaryNumericOp.I64_EXTENDI32_S, WASMAddress(ins.address()))
            OpCode.I32_CLZ -> NumericUnary(UnaryNumericOp.I32CLZ, WASMAddress(ins.address()))
            OpCode.I64_CLZ -> NumericUnary(UnaryNumericOp.I64CLZ, WASMAddress(ins.address()))

            OpCode.I32_ADD -> NumericBinary(BinaryNumericOp.I32ADD, WASMAddress(ins.address()))
            OpCode.I32_SUB -> NumericBinary(BinaryNumericOp.I32SUB, WASMAddress(ins.address()))
            OpCode.I32_MUL -> NumericBinary(BinaryNumericOp.I32MUL, WASMAddress(ins.address()))
            OpCode.I32_AND -> NumericBinary(BinaryNumericOp.I32AND, WASMAddress(ins.address()))
            OpCode.I32_OR -> NumericBinary(BinaryNumericOp.I32OR, WASMAddress(ins.address()))
            OpCode.I32_XOR -> NumericBinary(BinaryNumericOp.I32XOR, WASMAddress(ins.address()))
            OpCode.I32_SHL -> NumericBinary(BinaryNumericOp.I32SHL, WASMAddress(ins.address()))
            OpCode.I32_SHR_S -> NumericBinary(BinaryNumericOp.I32SHRS, WASMAddress(ins.address()))
            OpCode.I32_SHR_U -> NumericBinary(BinaryNumericOp.I32SHRU, WASMAddress(ins.address()))
            OpCode.I32_DIV_S -> NumericBinary(BinaryNumericOp.I32DIVS, WASMAddress(ins.address()))
            OpCode.I32_DIV_U -> NumericBinary(BinaryNumericOp.I32DIVU, WASMAddress(ins.address()))
            OpCode.I32_REM_S -> NumericBinary(BinaryNumericOp.I32REMS, WASMAddress(ins.address()))
            OpCode.I32_REM_U -> NumericBinary(BinaryNumericOp.I32REMU, WASMAddress(ins.address()))
            OpCode.I64_ADD -> NumericBinary(BinaryNumericOp.I64ADD, WASMAddress(ins.address()))
            OpCode.I64_SUB -> NumericBinary(BinaryNumericOp.I64SUB, WASMAddress(ins.address()))
            OpCode.I64_MUL -> NumericBinary(BinaryNumericOp.I64MUL, WASMAddress(ins.address()))
            OpCode.I64_AND -> NumericBinary(BinaryNumericOp.I64AND, WASMAddress(ins.address()))
            OpCode.I64_OR -> NumericBinary(BinaryNumericOp.I64OR, WASMAddress(ins.address()))
            OpCode.I64_XOR -> NumericBinary(BinaryNumericOp.I64XOR, WASMAddress(ins.address()))
            OpCode.I64_SHL -> NumericBinary(BinaryNumericOp.I64SHL, WASMAddress(ins.address()))
            OpCode.I64_SHR_S -> NumericBinary(BinaryNumericOp.I64SHRS, WASMAddress(ins.address()))
            OpCode.I64_SHR_U -> NumericBinary(BinaryNumericOp.I64SHRU, WASMAddress(ins.address()))
            OpCode.I64_DIV_S -> NumericBinary(BinaryNumericOp.I64DIVS, WASMAddress(ins.address()))
            OpCode.I64_DIV_U -> NumericBinary(BinaryNumericOp.I64DIVU, WASMAddress(ins.address()))
            OpCode.I64_REM_S -> NumericBinary(BinaryNumericOp.I64REMS, WASMAddress(ins.address()))
            OpCode.I64_REM_U -> NumericBinary(BinaryNumericOp.I64REMU, WASMAddress(ins.address()))

            OpCode.I32_EQZ -> ComparisonUnary(UnaryComparisonOp.I32EQZ, WASMAddress(ins.address()))
            OpCode.I64_EQZ -> ComparisonUnary(UnaryComparisonOp.I64EQZ, WASMAddress(ins.address()))

            OpCode.I32_LT_S -> ComparisonBinary(BinaryComparisonOp.I32LTS, WASMAddress(ins.address()))
            OpCode.I32_LT_U -> ComparisonBinary(BinaryComparisonOp.I32LTU, WASMAddress(ins.address()))
            OpCode.I32_GT_S -> ComparisonBinary(BinaryComparisonOp.I32GTS, WASMAddress(ins.address()))
            OpCode.I32_GT_U -> ComparisonBinary(BinaryComparisonOp.I32GTU, WASMAddress(ins.address()))
            OpCode.I32_LE_S -> ComparisonBinary(BinaryComparisonOp.I32LES, WASMAddress(ins.address()))
            OpCode.I32_LE_U -> ComparisonBinary(BinaryComparisonOp.I32LEU, WASMAddress(ins.address()))
            OpCode.I32_GE_S -> ComparisonBinary(BinaryComparisonOp.I32GES, WASMAddress(ins.address()))
            OpCode.I32_GE_U -> ComparisonBinary(BinaryComparisonOp.I32GEU, WASMAddress(ins.address()))
            OpCode.I64_LT_S -> ComparisonBinary(BinaryComparisonOp.I64LTS, WASMAddress(ins.address()))
            OpCode.I64_LT_U -> ComparisonBinary(BinaryComparisonOp.I64LTU, WASMAddress(ins.address()))
            OpCode.I64_GT_S -> ComparisonBinary(BinaryComparisonOp.I64GTS, WASMAddress(ins.address()))
            OpCode.I64_GT_U -> ComparisonBinary(BinaryComparisonOp.I64GTU, WASMAddress(ins.address()))
            OpCode.I64_LE_S -> ComparisonBinary(BinaryComparisonOp.I64LES, WASMAddress(ins.address()))
            OpCode.I64_LE_U -> ComparisonBinary(BinaryComparisonOp.I64LEU, WASMAddress(ins.address()))
            OpCode.I64_GE_S -> ComparisonBinary(BinaryComparisonOp.I64GES, WASMAddress(ins.address()))
            OpCode.I64_GE_U -> ComparisonBinary(BinaryComparisonOp.I64GEU, WASMAddress(ins.address()))
            OpCode.I32_EQ -> ComparisonBinary(BinaryComparisonOp.I32EQ, WASMAddress(ins.address()))
            OpCode.I32_NE -> ComparisonBinary(BinaryComparisonOp.I32NE, WASMAddress(ins.address()))
            OpCode.I64_EQ -> ComparisonBinary(BinaryComparisonOp.I64EQ, WASMAddress(ins.address()))
            OpCode.I64_NE -> ComparisonBinary(BinaryComparisonOp.I64NE, WASMAddress(ins.address()))

            OpCode.I32_CONST -> I32Const(I32Value(ins.operands()[0].positiveModulus(I32_BOUND)), WASMAddress(ins.address()))

            OpCode.I64_CONST -> I64Const(I64Value((ins.operands()[0]).positiveModulus(I64_BOUND)), WASMAddress(ins.address()))

            OpCode.F32_CONST -> F32Const(F32Value(ins.operands()[0].toFloat()), WASMAddress(ins.address()))
            OpCode.F64_CONST -> F64Const(F64Value(ins.operands()[0].toDouble()), WASMAddress(ins.address()))

            OpCode.F32_EQ -> unimplemented
            OpCode.F32_NE -> unimplemented
            OpCode.F32_LT -> unimplemented
            OpCode.F32_GT -> unimplemented
            OpCode.F32_LE -> unimplemented
            OpCode.F32_GE -> unimplemented
            OpCode.F64_EQ -> WasmInstruction.NondetStub.RelF64(ins.opcode())
            OpCode.F64_NE -> WasmInstruction.NondetStub.RelF64(ins.opcode())
            OpCode.F64_LT -> unimplemented
            OpCode.F64_GT -> unimplemented
            OpCode.F64_LE -> unimplemented
            OpCode.F64_GE -> unimplemented
            OpCode.I32_CTZ -> NumericUnary(UnaryNumericOp.I32CTZ, WASMAddress(ins.address()))
            OpCode.I32_POPCNT -> unimplemented
            OpCode.I32_ROTL -> unimplemented
            OpCode.I32_ROTR -> unimplemented
            OpCode.I64_CTZ -> NumericUnary(UnaryNumericOp.I64CTZ, WASMAddress(ins.address()))
            OpCode.I64_POPCNT -> unimplemented
            OpCode.I64_ROTL -> WasmInstruction.NondetStub.BinI64(ins.opcode())
            OpCode.I64_ROTR -> unimplemented
            OpCode.F32_ABS -> unimplemented
            OpCode.F32_NEG -> unimplemented
            OpCode.F32_CEIL -> unimplemented
            OpCode.F32_FLOOR -> unimplemented
            OpCode.F32_TRUNC -> unimplemented
            OpCode.F32_NEAREST -> unimplemented
            OpCode.F32_SQRT -> unimplemented
            OpCode.F32_ADD -> unimplemented
            OpCode.F32_SUB -> unimplemented
            OpCode.F32_MUL -> unimplemented
            OpCode.F32_DIV -> unimplemented
            OpCode.F32_MIN -> unimplemented
            OpCode.F32_MAX -> unimplemented
            OpCode.F32_COPYSIGN -> unimplemented
            OpCode.F64_ABS -> WasmInstruction.NondetStub.UnF64(ins.opcode())
            OpCode.F64_NEG -> WasmInstruction.NondetStub.UnF64(ins.opcode())
            OpCode.F64_CEIL -> unimplemented
            OpCode.F64_FLOOR -> unimplemented
            OpCode.F64_TRUNC -> unimplemented
            OpCode.F64_NEAREST -> unimplemented
            OpCode.F64_SQRT -> unimplemented
            OpCode.F64_ADD -> unimplemented
            OpCode.F64_SUB -> unimplemented
            OpCode.F64_MUL -> WasmInstruction.NondetStub.BinF64(ins.opcode())
            OpCode.F64_DIV -> WasmInstruction.NondetStub.BinF64(ins.opcode())
            OpCode.F64_MIN -> unimplemented
            OpCode.F64_MAX -> unimplemented
            OpCode.F64_COPYSIGN -> unimplemented
            OpCode.I32_TRUNC_F32_S -> unimplemented
            OpCode.I32_TRUNC_F32_U -> unimplemented
            OpCode.I32_TRUNC_F64_S -> unimplemented
            OpCode.I32_TRUNC_F64_U -> unimplemented
            OpCode.I64_TRUNC_F32_S -> unimplemented
            OpCode.I64_TRUNC_F32_U -> unimplemented
            OpCode.I64_TRUNC_F64_S -> unimplemented
            OpCode.I64_TRUNC_F64_U -> unimplemented
            OpCode.F32_CONVERT_I32_S -> unimplemented
            OpCode.F32_CONVERT_I32_U -> unimplemented
            OpCode.F32_CONVERT_I64_S -> unimplemented
            OpCode.F32_CONVERT_I64_U -> unimplemented
            OpCode.F32_DEMOTE_F64 -> unimplemented
            OpCode.F64_CONVERT_I32_S -> unimplemented
            OpCode.F64_CONVERT_I32_U -> unimplemented
            OpCode.F64_CONVERT_I64_S -> unimplemented
            OpCode.F64_CONVERT_I64_U -> WasmInstruction.NondetStub(
                ins.opcode(),
                WasmPrimitiveType.I64,
                WasmPrimitiveType.F64
            )
            OpCode.F64_PROMOTE_F32 -> WasmInstruction.NondetStub(
                ins.opcode(),
                WasmPrimitiveType.F32,
                WasmPrimitiveType.F64
            )

            OpCode.I32_REINTERPRET_F32 -> WasmInstruction.NondetStub(
                ins.opcode(),
                WasmPrimitiveType.F32,
                WasmPrimitiveType.I32
            )

            OpCode.I64_REINTERPRET_F64 -> WasmInstruction.NondetStub(
                ins.opcode(),
                WasmPrimitiveType.F64,
                WasmPrimitiveType.I64
            )
            OpCode.F32_REINTERPRET_I32 -> unimplemented
            OpCode.F64_REINTERPRET_I64 -> unimplemented
            OpCode.REF_NULL -> unimplemented
            OpCode.REF_IS_NULL -> unimplemented
            OpCode.REF_FUNC -> unimplemented
            OpCode.I32_TRUNC_SAT_F32_S -> unimplemented
            OpCode.I32_TRUNC_SAT_F32_U -> unimplemented
            OpCode.I32_TRUNC_SAT_F64_S -> unimplemented
            OpCode.I32_TRUNC_SAT_F64_U -> unimplemented
            OpCode.I64_TRUNC_SAT_F32_S -> unimplemented
            OpCode.I64_TRUNC_SAT_F32_U -> unimplemented
            OpCode.I64_TRUNC_SAT_F64_S -> unimplemented
            OpCode.I64_TRUNC_SAT_F64_U -> unimplemented
            OpCode.MEMORY_INIT -> unimplemented
            OpCode.DATA_DROP -> unimplemented
            OpCode.TABLE_INIT -> unimplemented
            OpCode.ELEM_DROP -> unimplemented
            OpCode.TABLE_COPY -> unimplemented
            OpCode.TABLE_GROW -> unimplemented
            OpCode.TABLE_SIZE -> unimplemented
            OpCode.TABLE_FILL -> unimplemented
            else -> error("Weird")
        }
    }

    private fun convertInstructions(
        pointer: Int, instructions: List<AnnotatedInstruction>
    ): Pair<Int, List<WasmInstruction>> {
        val ins = instructions[pointer]
        val res = when (ins.opcode()) {
            OpCode.UNREACHABLE -> Control.Unreachable(WASMAddress(ins.address()))
            OpCode.RETURN -> Control.Return(WASMAddress(ins.address()))
            OpCode.BLOCK -> {
                val blockInstructions = convertInstructions(pointer + 1, instructions)
                val block = Control.Block(blockInstructions.second, WasmLabelAnnotation(ins.depth()), WASMAddress(ins.address()))
                val res = convertInstructions(blockInstructions.first, instructions)
                return res.first to (listOf(block) + res.second)
            }

            OpCode.LOOP -> {
                val blockInstructions = convertInstructions(pointer + 1, instructions)
                val loop = Control.Loop(blockInstructions.second, WasmLabelAnnotation(ins.depth()), WASMAddress(ins.address()))
                val res = convertInstructions(blockInstructions.first, instructions)
                return res.first to (listOf(loop) + res.second)
            }

            OpCode.IF -> {
                val ifInstructions = convertInstructions(ins.labelTrue(), instructions)
                val elseInstructions = convertInstructions(ins.labelFalse(), instructions)
                val ifAndElse =
                    Control.IfElse(ifInstructions.second, elseInstructions.second, WasmLabelAnnotation(ins.depth()), WASMAddress(ins.address()))
                //All instructions in the if and else branches have been transformed, keep converting at the maximum of both pointers.
                val nextPointer = maxOf(ifInstructions.first, elseInstructions.first)
                val res = convertInstructions(nextPointer, instructions)
                return res.first to (listOf(ifAndElse) + res.second)
            }

            OpCode.ELSE -> return (pointer + 1 to listOf())
            OpCode.END -> return (pointer + 1 to listOf())
            OpCode.NOP -> TODO()
            OpCode.BR -> Control.Br(
                ins.operands().first().toInt(), WASMAddress(ins.address())
            );
            OpCode.BR_IF -> Control.BrIf(
                ins.operands().first().toInt(), WASMAddress(ins.address())
            )

            OpCode.BR_TABLE -> Control.BrTable(ins.operands().map { it.toInt() }, WASMAddress(ins.address()))

            else -> convertNonControlFlowInstruction(ins)
        }

        val nextPointer = pointer + 1;
        val (endPointer, remainingIns) = convertInstructions(nextPointer, instructions)
        return endPointer to (listOf(res) + remainingIns)
    }
}
