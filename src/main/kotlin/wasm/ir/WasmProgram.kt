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

import datastructures.stdcollections.*
import utils.*
import wasm.tokens.DESC
import wasm.tokens.WasmTokens.AT
import wasm.tokens.WasmTokens.DATA
import wasm.tokens.WasmTokens.ELEM
import wasm.tokens.WasmTokens.EQUAL
import wasm.tokens.WasmTokens.EXPORT
import wasm.tokens.WasmTokens.FUNC
import wasm.tokens.WasmTokens.FUNC_REF
import wasm.tokens.WasmTokens.GLOBAL
import wasm.tokens.WasmTokens.IMPORT
import wasm.tokens.WasmTokens.LABEL
import wasm.tokens.WasmTokens.LOCAL
import wasm.tokens.WasmTokens.LPAR
import wasm.tokens.WasmTokens.MEMORY
import wasm.tokens.WasmTokens.MODULE
import wasm.tokens.WasmTokens.PARAM
import wasm.tokens.WasmTokens.RESULT
import wasm.tokens.WasmTokens.RPAR
import wasm.tokens.WasmTokens.SEMICOLON
import wasm.tokens.WasmTokens.TABLE
import wasm.tokens.WasmTokens.TYPE
import java.math.BigInteger

class IdNotFoundException(msg: String): Exception(msg)

/*
TODO: CERT-6022 add support for
    Rec(Rec<'a>),
    Tag(Tag<'a>),
    Custom(Custom<'a>),
*/

const val WASM_PAGE_SIZE: Int = 65536

/**
 * A WasmProgram is a "module".
 * A module has a list of fields that make up the wasm program.
 * NOTE: there's apparently something called component
 * (https://github.com/bytecodealliance/wasm-tools/tree/main/crates/wast/src/component)
 * too but not sure what that exactly is.
 */
data class WasmProgram(val fields: List<WasmModuleField>) {
    override fun toString(): String {
        val fieldsToString = fields.joinToString("\n") { it.toString() }
        return "$LPAR $MODULE $fieldsToString $RPAR"
    }

    private val exports: Map<WasmName, WasmExport<ExportFunc>> =
        fields.mapNotNull {
            (it as? WasmExport<*>)?.takeIf { it.desc is ExportFunc }.uncheckedAs<WasmExport<ExportFunc>?>()
        }.associateBy { WasmName(it.name) }

    fun findExportByName(name: String) = exports.values.find { it.name == name }

    val elemTable by lazy {
        fields.filterIsInstance<WasmElem>().singleOrNull()
    }

    val typeTable by lazy {
        fields.filterIsInstance<WasmType>().associateBy { it.name }
    }

    private fun lookupTypeByTypeUseName(name: WasmName): WasmType {
        return typeTable[name]
            ?: throw IdNotFoundException("$name does not correspond to any type definition.")
    }

    /**
     *  returns a list of all functions defined in this wasm module.
    * */
    val definedFuncs = fields.filterIsInstance<WasmFunction>()

    /*
    * These functions have no locals or body.
    * */
    val importedFuncs: Map<WasmName, WasmImport<ImportFunc>> =
        fields.mapNotNull {
            (it as? WasmImport<*>)?.takeIf { it.desc is ImportFunc }.uncheckedAs<WasmImport<ImportFunc>?>()
        }.associateBy { it.desc.name }

    /** The WasmId of the "assert" function, if there is one */
    val assertId = importedFuncs.values.find { it.what == "CVT_assert_c" }?.desc?.name

    /** The WasmId of the "assume" function, if there is one */
    val assumeId = importedFuncs.values.find { it.what == "CVT_assume_c" }?.desc?.name


    sealed class WasmFuncDesc {
        abstract val fnType: WasmType
        abstract val paramsAndLocals: List<WasmPrimitiveType>?
        data class LocalFn(override val fnType: WasmType, val locals: List<WasmPrimitiveType>) : WasmFuncDesc() {
            override val paramsAndLocals = fnType.params + locals
        }
        data class ExternalFn(override val fnType: WasmType, val from: String, val what: String): WasmFuncDesc() {
            override val paramsAndLocals = fnType.params
        }
    }

    /**
     * For each function, returns the types of the params and return.
     * This also returns the types of the locals declared in the function.
     * */
    val allFuncTypes: Map<WasmName, WasmFuncDesc> = run {
        val ret = mutableMapOf<WasmName, WasmFuncDesc>()
        for (f in definedFuncs) {
            ret[f.name] = WasmFuncDesc.LocalFn(this.lookupTypeByTypeUseName(f.typeuse.name), f.locals)
        }
        // These do not have any locals as they are not defined in this module.
        for ((id, import) in importedFuncs) {
            ret[id] = WasmFuncDesc.ExternalFn(
                this.lookupTypeByTypeUseName(import.desc.typeuse.name),
                import.from,
                import.what,
            )
        }
        ret
    }

    val allGlobals = fields.filterIsInstance<WasmGlobal>().associateBy { it.name }
    val dataFields = fields.filterIsInstance<WasmData>()
}


sealed interface WasmModuleField {
    override fun toString(): String
}


sealed interface WasmExportable: WasmModuleField {
    override fun toString(): String
}

sealed interface WasmImportable: WasmModuleField {
    override fun toString(): String
}

/**
 * We are using WasmId to refer to all IDs and annotations
 * that look like (;NUM;)
 * */
@com.certora.collect.Treapable
@KSerializable
data class WasmId(private val id: Int) : WasmModuleField, AmbiSerializable {
    override fun toString() = "$LPAR$SEMICOLON$id$SEMICOLON$RPAR"
    fun asString() = id.toString()
}

/**
 * We are using WasmName to refer to function names and import/export names
 * that look like $_ZN4core9panicking9panic_fmt17h5c7ce52813e94bcdE
 * */
@com.certora.collect.Treapable
@KSerializable
data class WasmName(private val id: String) : WasmModuleField, AmbiSerializable {
    override fun toString() = id
}
/**  We are using WasmLabelAnnotation to block and loop annotations
 * that look like `label = @1`.
 * */
data class WasmLabelAnnotation(val annotation: Int) : WasmModuleField {
    override fun toString() = "$LABEL $EQUAL $AT$annotation"
}

/**
 * Currently only handles function types as mentioned
 * in wasm manual: https://www.w3.org/TR/wasm-core-1/#types%E2%91%A2
 * (type $t1 (func (param i64 i64) (result i64)))
 */
data class WasmType(
    val name: WasmName,
    val params: List<WasmPrimitiveType>,
    val result: WasmPrimitiveType?
) : WasmModuleField {
    override fun toString(): String {
        val paramsToString = if (params.isNotEmpty()) {
            "$LPAR $PARAM ${params.joinToString(" ") { it.toString() }} $RPAR"
        } else {
            ""
        }
        return if (result == null) {
            "$LPAR $TYPE $name $LPAR $FUNC $paramsToString $RPAR $RPAR"
        } else {
            val resultToString = "$LPAR $RESULT $result $RPAR"
            "$LPAR $TYPE $name $LPAR $FUNC $paramsToString $resultToString $RPAR $RPAR"
        }
    }

    fun toWasmTypeUse(): WasmTypeUse{
        return WasmTypeUse(name, params, result)
    }
}

// This is to be used with imports and functions, NOT the same thing as a type definition.
// This is a reference to a type definition.
data class WasmTypeUse(
    val name: WasmName, // unclear if this is ever optional? I think no: https://webassembly.github.io/spec/core/text/modules.html#text-typeuse
    val params: List<WasmPrimitiveType>,
    val result: WasmPrimitiveType?
) : WasmModuleField {
    override fun toString(): String {
        val paramsToString = if (params.isNotEmpty()) {
            "$LPAR $PARAM ${params.joinToString(" ") { it.toString() }} $RPAR"
        } else {
            ""
        }
        return if (result == null) {
            "$LPAR $TYPE ${name} $RPAR $paramsToString"
        } else {
            val resultToString = "$LPAR $RESULT $result $RPAR"
            "$LPAR $TYPE ${name} $RPAR $paramsToString $resultToString"
        }
    }
}


/*
  (func$_ZN11soroban_sdk6symbol6Symbol3new17h80e1a62fabe9d6b6E (type 1) (param i64) (result i64)
    (local i32 i32)
    global.get 0
    i32.const 32
    i32.sub
    ...
   )
* */
data class WasmFunction(
    val name: WasmName,
    val typeuse: WasmTypeUse,
    val locals: List<WasmPrimitiveType>,
    val body: List<WasmInstruction>
) : WasmModuleField {
    override fun toString(): String {
        val typeuseToString = typeuse.toString()
        val sig = "$FUNC $name $typeuseToString"
        val localsToString = if (locals.isNotEmpty()) {
            "$LPAR $LOCAL ${locals.joinToString(" ") { it.toString() }} $RPAR"
        } else {
            ""
        }
        val bodyToString = body.joinToString("\n") { it.toString() }
        return "$LPAR $sig $localsToString  \n $bodyToString $RPAR"
    }

    fun numParamsAndLocals(): Int {
        return locals.size + this.typeuse.params.size
    }

    fun getDesc(): DESC {
        return "$LPAR $FUNC ${name} $RPAR"
    }
}


/**
 * This represents the `memory` section in a wasm module.
 * The limit field represents the minimum size of the memory
 * in terms of page size.
 * https://webassembly.github.io/spec/core/syntax/modules.html#syntax-mem
 * NOTE: it is possible that the memory block has more information.
 * I have not seen evidence of this so far in the programs I have inspected
 * so this representation may prove to be insufficient
 * in the future, but we can easily extend it.
 * */
data class WasmMemory(
    val name: WasmName,
    val limit: Int
) : WasmModuleField {
    override fun toString(): String {
        return "$LPAR $MEMORY $name $limit $RPAR"
    }

    fun getDesc(): DESC {
        return "$LPAR $MEMORY ${name} $RPAR"
    }
}

/**
 * Wasm can declare global variables that
 * are intended to be accessible from code that uses the wasm module like for example
 * some javascript function.
 * https://developer.mozilla.org/en-US/docs/WebAssembly/Understanding_the_text_format
 * Example `(global (;0;) (mut i32) (i32.const 1048576))` is declaring
 * a mutable i32 global whose initial value is 1048576.
 * It can also be (global $__stack_pointer (mut i32) (i32.const 1048576))
 * */
data class WasmGlobal(
    val name: WasmName,
    val typeQualifier: WasmMutability?,
    val type: WasmPrimitiveType,
    val init: WasmInstruction
) : WasmModuleField {
    init {
        // for now these are the only two options. Either both are i32s or both are i64s.
        check((type == WasmPrimitiveType.I32 && init is WasmInstruction.Numeric.I32Const) ||
              (type == WasmPrimitiveType.I64 && init is WasmInstruction.Numeric.I64Const))
    }

    override fun toString(): String {
        if (typeQualifier != null) {
            return "$LPAR $GLOBAL $name $LPAR $typeQualifier $type $RPAR $init $RPAR"
        }
        return "$LPAR $GLOBAL $name $type $init $RPAR"
    }

    fun getDesc(): DESC {
        return "$LPAR $GLOBAL $name $RPAR"
    }

    fun isMutable(): Boolean {
        return (this.typeQualifier != null)
    }
}

data class WasmExport<T: WasmExportable>(val name: String, val desc: T) : WasmModuleField {
    override fun toString(): String {
        return "$LPAR $EXPORT $name $desc $RPAR"
    }
}

data class ExportFunc(val id: WasmName): WasmExportable
data class ExportMemory(val name: WasmName): WasmExportable
data class ExportTable(val name: WasmName): WasmExportable
data class ExportGlobal(val name: WasmName): WasmExportable

data class WasmImport<T : WasmImportable>(val from: String, val what: String, val desc: T) : WasmModuleField {
    override fun toString(): String {
        return "$LPAR $IMPORT $from $what $desc $RPAR"
    }
}

data class ImportFunc(val name: WasmName, val typeuse: WasmTypeUse): WasmImportable
data class ImportMemory(val name: WasmName): WasmImportable // TODO: CERT-6023 not supported right now
data class ImportTable(val name: WasmName): WasmImportable // TODO: CERT-6023 not supported right now
data class ImportGlobal(val name: WasmName): WasmImportable // TODO: CERT-6023 not supported right now


/**
 * Ignoring the memidx which is always 0:
 * https://www.w3.org/TR/wasm-core-1/#element-segments%E2%91%A0
 * */
data class WasmElem(
    val name: WasmName,
    val offset: WasmInstruction.Numeric.I32Const,
    val funcNames: List<String>
) : WasmModuleField {
    override fun toString(): String {
        return "$LPAR $ELEM $name $offset $FUNC ${stringOfFuncIds()} $RPAR"
    }

    fun stringOfFuncIds(): String {
        return "[${funcNames.joinToString(",") { it }}]"
    }

    fun getOffset(): Int {
        return offset.num.v.safeAsInt()
    }
}

/**
 * Ignoring the memidx which is always 0:
 * https://www.w3.org/TR/wasm-core-1/#data-segments%E2%91%A0
 * */
data class WasmData(val name: WasmName, val offset: WasmInstruction, val content: List<UByte>) :
    WasmModuleField {
        constructor(id: WasmName, offset: WasmInstruction, content: String): this(id, offset, toByteData(content))
    override fun toString(): String {
        return "$LPAR $DATA $name $offset $content $RPAR"
    }

    val offsetConstVal: BigInteger?
        get() = offset.tryAs<WasmInstruction.Numeric.I32Const>()?.constVal

    companion object {
        fun toByteData(stringData: String): List<UByte> =
            stringData.map { it.code.toUByte() }
    }
}

/* only allows function refs for now: https://www.w3.org/TR/wasm-core-1/#tables%E2%91%A0
* maybe we need to add another field if/when that happens.
* */
data class WasmTable(val name: WasmName, val min: Int, val max: Int?) : WasmModuleField {
    override fun toString(): String {
        return max?.let { "$LPAR $TABLE $name $min $it $FUNC_REF $RPAR" } ?: "$LPAR $TABLE $name $min $FUNC_REF $RPAR"
    }
}

data class WasmStart(val startFuncName: WasmName) : WasmModuleField {
    override fun toString(): String {
        TODO("Not yet implemented")
    }
}

data class WasmCVTRule(val names: List<String>) : WasmModuleField
