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

package com.certora.certoraprover.cvl

import annotation.OpcodeHookType
import spec.TypeResolver
import spec.cvlast.*
import spec.cvlast.CVLExp.Constant.NumberLit
import spec.cvlast.CVLScope.Item.HookScopeItem
import spec.cvlast.SolidityContract.Companion.Current
import spec.cvlast.parser.GeneratedOpcodeParsers
import spec.cvlast.typechecker.CVLError
import utils.CollectingResult
import utils.CollectingResult.Companion.asError
import utils.CollectingResult.Companion.flatten
import utils.CollectingResult.Companion.lift
import utils.CollectingResult.Companion.map
import utils.ErrorCollector.Companion.collectingErrors
import utils.Range
import java.math.BigInteger

// This file contains the "Java" AST nodes for Hooks and their components.  See README.md for information about the Java AST.

// TODO CERT-3751: this whole hierarchy is much more complicated than it needs to be

class Hook(private val range: Range, private val pattern: HookPattern, private val commands: List<Cmd>) : Kotlinizable<CVLHook> {
    override fun kotlinize(resolver: TypeResolver, scope: CVLScope): CollectingResult<CVLHook, CVLError> {
        return scope.extendInCollecting(::HookScopeItem) { hookScope: CVLScope ->
            collectingErrors {
                val pattern_  = pattern.kotlinize(resolver, hookScope)
                val commands_ = commands.map { it.kotlinize(resolver, hookScope) }.flatten()
                map(pattern_, commands_) { pattern, commands -> CVLHook(pattern, commands, range, hookScope) }
            }
        }
    }
}

// TODO CERT-3751: This should have a more refined hierarchy
class HookPattern(
    private val range: Range,
    private val hookType: HookType,
    private val value: NamedVMParam?,
    private val oldValue: NamedVMParam?,
    private val slot: SlotPattern?,
    private val params: List<NamedVMParam>?,
) : Kotlinizable<CVLHookPattern> {

    enum class Base { NONE, STORAGE }

    constructor(range: Range, hookType: HookType, value: NamedVMParam?, slot: SlotPattern?)
        : this(range, hookType, value, null, slot, null)

    constructor(range: Range, hookType: HookType, value: NamedVMParam?, oldValue: NamedVMParam?, slot: SlotPattern?)
        : this(range, hookType, value, oldValue, slot, null)

    /** Constructor for non-storage hooks (e.g., hooks on create) */
    constructor(range: Range, hookType: HookType, value: NamedVMParam?)
        : this(range, hookType, value, null, null, null)

    // Constructors for opcode hooks

    /** hookable opcodes with a return value */
    constructor(range: Range, hookTypeName: String, params: List<NamedVMParam>, value: NamedVMParam?)
        // The valueOf call is guaranteed to succeed as the lexer uses HookType for EVMConfig to define hookable opcodes with 1 return value
        : this(range, HookType.valueOf(hookTypeName), value, null, null, params)

    /** hookable opcodes with no return values */
    constructor(range: Range, hookTypeName: String, params: List<NamedVMParam>)
        // The valueOf call is guaranteed to succeed as the lexer uses HookType for EVMConfig to define hookableOpcodes
        : this(range, HookType.valueOf(hookTypeName), null, null, null, params)

    override fun kotlinize(resolver: TypeResolver, scope: CVLScope): CollectingResult<CVLHookPattern, CVLError> = collectingErrors {
        val kvalue    = bind(value?.kotlinize(resolver, scope) ?: null.lift())
        val slot     = bind(slot?.kotlinize(resolver, scope) ?: null.lift())
        val oldValue = bind(oldValue?.kotlinize(resolver,scope) ?: null.lift())
        return@collectingErrors when(hookType) {
            HookType.SLOAD  -> CVLHookPattern.StoragePattern.Load(kvalue!!, slot!!, base())
            HookType.SSTORE -> CVLHookPattern.StoragePattern.Store(kvalue!!, slot!!, base(), oldValue)
            HookType.CREATE -> CVLHookPattern.Create(kvalue!!)
            else -> {
                check(hookType.lowLevel) { "Did not expect a non-opcode low-level hook type ${hookType.name}" }
                check(GeneratedOpcodeParsers.supportsAutoParse(hookType)) { "Unrecognized hook pattern ${hookType.name}" }
                bind(GeneratedOpcodeParsers.handleParse(resolver,scope,hookType,value,params!!,range))
            }
        }
    }

    private fun base(): CVLHookPattern.StoragePattern.Base {
        // Currently we only support STORAGE base
        return CVLHookPattern.StoragePattern.Base.STORAGE
    }
}



class ArrayAccessSlotPattern(private val base: SlotPattern, private val key: NamedVMParam) : SlotPattern(base.range) {
    override fun kotlinize(resolver: TypeResolver, scope: CVLScope): CollectingResult<CVLSlotPattern, CVLError> = collectingErrors {
        val base_ = base.kotlinize(resolver, scope)
        val key_  = key.kotlinize(resolver, scope)
        map(base_, key_) { base, key -> CVLSlotPattern.ArrayAccess(base, key) }
    }
}


enum class HookType(val lowLevel: Boolean, @Suppress("Unused") val numInputs: Int, @Suppress("Unused") val numOutputs: Int) {
    // these are the high level hooks that take actual storage patterns
    SLOAD(false, 0, 0),
    SSTORE(false, 0, 0),
    CREATE(false, 0, 0),
    TLOAD(false, 0, 0),
    TSTORE(false, 0, 0),

    // these are low level hooks for storage load/store
    @OpcodeHookType(withOutput = true, valueType = "uint256", params = ["uint256 loc"], onlyNoStorageSplitting = true)
    ALL_SLOAD(true, 1, 1),
    @OpcodeHookType(withOutput = false, params = ["uint256 loc", "uint256 v"], onlyNoStorageSplitting = true)
    ALL_SSTORE(true, 2, 0),
    @OpcodeHookType(withOutput = true, valueType = "uint256", params = ["uint256 loc"], onlyNoStorageSplitting = false)
    ALL_TLOAD(true, 1, 1),
    @OpcodeHookType(withOutput = false, params = ["uint256 loc", "uint256 v"], onlyNoStorageSplitting = false)
    ALL_TSTORE(true, 2, 0),
    @OpcodeHookType(withOutput = true, valueType = "address")
    ADDRESS(true, 0, 1),
    @OpcodeHookType(withOutput = true, params = ["address addr"])
    BALANCE(true, 1, 1),
    @OpcodeHookType(withOutput = true, valueType = "address")
    ORIGIN(true, 0, 1),
    @OpcodeHookType(withOutput = true, valueType = "address")
    CALLER(true, 0, 1),
    @OpcodeHookType(withOutput = true)
    CALLVALUE(true, 0, 1),
    @OpcodeHookType(withOutput = true)
    CODESIZE(true, 0, 1),
    @OpcodeHookType(withOutput = false, params = ["uint256 destOffset", "uint256 offset", "uint256 length"])
    CODECOPY(true, 3, 0),
    @OpcodeHookType(withOutput = true)
    GASPRICE(true, 0, 1),
    @OpcodeHookType(withOutput = true, params = ["address addr"])
    EXTCODESIZE(true, 1, 1),
    @OpcodeHookType(withOutput = false, params = ["address addr", "uint256 destOffset", "uint256 offset", "uint256 length"])
    EXTCODECOPY(true, 4, 0),
    @OpcodeHookType(withOutput = true, params = ["address addr"], valueType = "bytes32")
    EXTCODEHASH(true, 1, 1),
    @OpcodeHookType(withOutput = true, params = ["uint256 blockNum"], valueType = "bytes32")
    BLOCKHASH(true, 1, 1),
    @OpcodeHookType(withOutput = true, valueType = "address")
    COINBASE(true, 0, 1),
    @OpcodeHookType(withOutput = true)
    TIMESTAMP(true, 0, 1),
    @OpcodeHookType(withOutput = true)
    NUMBER(true, 0, 1),
    @OpcodeHookType(withOutput = true)
    DIFFICULTY(true, 0, 1),
    @OpcodeHookType(withOutput = true)
    GASLIMIT(true, 0, 1),
    @OpcodeHookType(withOutput = true)
    CHAINID(true, 0, 1),
    @OpcodeHookType(withOutput = true)
    SELFBALANCE(true, 0, 1),
    @OpcodeHookType(withOutput = true)
    BASEFEE(true, 0, 1),
    @OpcodeHookType(withOutput = true, params = ["uint256 index"], valueType = "bytes32")
    BLOBHASH(true, 1, 1),
    @OpcodeHookType(withOutput = true)
    BLOBBASEFEE(true, 0, 1),
    @OpcodeHookType(withOutput = true)
    MSIZE(true, 0, 1),
    @OpcodeHookType(withOutput = true)
    GAS(true, 0, 1),
    @OpcodeHookType(withOutput = false, params = ["uint256 offset", "uint256 size"], extraInterfaces = [CVLHookPattern.LogHookPattern::class])
    LOG0(true, 2, 0),
    @OpcodeHookType(withOutput = false, params = ["uint256 offset", "uint256 size", "bytes32 topic1"], extraInterfaces = [CVLHookPattern.LogHookPattern::class])
    LOG1(true, 3, 0),
    @OpcodeHookType(withOutput = false, params = ["uint256 offset", "uint256 size", "bytes32 topic1", "bytes32 topic2"], extraInterfaces = [CVLHookPattern.LogHookPattern::class])
    LOG2(true, 4, 0),
    @OpcodeHookType(withOutput = false, params = ["uint256 offset", "uint256 size", "bytes32 topic1", "bytes32 topic2", "bytes32 topic3"], extraInterfaces = [CVLHookPattern.LogHookPattern::class])
    LOG3(true, 5, 0),
    @OpcodeHookType(withOutput = false, params = ["uint256 offset", "uint256 size", "bytes32 topic1", "bytes32 topic2", "bytes32 topic3", "bytes32 topic4"], extraInterfaces = [CVLHookPattern.LogHookPattern::class])
    LOG4(true, 6, 0),
    @OpcodeHookType(withOutput = true, params = ["uint256 callValue", "uint256 offset", "uint256 len"], valueType = "address")
    CREATE1(true, 3, 1),

    // not to be confused with legacy CREATE
    @OpcodeHookType(withOutput = true, params = ["uint256 callValue", "uint256 offset", "uint256 len", "bytes32 salt"], valueType = "address")
    CREATE2(true, 4, 1),
    @OpcodeHookType(withOutput = true, params = ["uint256 gas", "address addr", "uint256 callValue", "uint256 argsOffset", "uint256 argsLength", "uint256 retOffset", "uint256 retLength"], envParams = ["uint32 selector"], extraInterfaces = [CVLHookPattern.CallHookPattern::class])
    CALL(true, 7, 1),
    @OpcodeHookType(withOutput = true, params = ["uint256 gas", "address addr", "uint256 callValue", "uint256 argsOffset", "uint256 argsLength", "uint256 retOffset", "uint256 retLength"], envParams = ["uint32 selector"], extraInterfaces = [CVLHookPattern.CallHookPattern::class])
    CALLCODE(true, 7, 1),
    @OpcodeHookType(withOutput = true, params = ["uint256 gas", "address addr", "uint256 argsOffset", "uint256 argsLength", "uint256 retOffset", "uint256 retLength"], envParams = ["uint32 selector"], extraInterfaces = [CVLHookPattern.CallHookPattern::class])
    DELEGATECALL(true, 6, 1),
    @OpcodeHookType(withOutput = true, params = ["uint256 gas", "address addr", "uint256 argsOffset", "uint256 argsLength", "uint256 retOffset", "uint256 retLength"], envParams = ["uint32 selector"], extraInterfaces = [CVLHookPattern.CallHookPattern::class])
    STATICCALL(true, 6, 1),
    @OpcodeHookType(withOutput = false, params = ["uint256 offset", "uint256 size"])
    REVERT(true, 2, 0),
    @OpcodeHookType(withOutput = false, params = ["address addr"])
    SELFDESTRUCT(true, 1, 0),
    @OpcodeHookType(withOutput = true)
    RETURNDATASIZE(true, 0, 1)
}

sealed class SlotPattern(val range: Range) : Kotlinizable<CVLSlotPattern>

class SlotPatternError(override val error : CVLError) : SlotPattern(error.location), ErrorASTNode<CVLSlotPattern>

/**
 * This class represents an arbitrary sequence of '(id number)', '(id number, id number)' or id each separated by a '.'
 * from the parser. Only a subset of this language is valid spec, but could not be easily parsed by cup. Valid
 * sequences include:
 *
 * (slot X)
 * (slot X, offset Y)
 * variable
 *
 * each of which can be prepended by a single id 'contract_name.' or followed by an arbitrary sequence of
 * .(offset X)
 *
 * [elements] contains the sequence of elements as described above
 */
class StaticSlotPattern(_range: Range) : SlotPattern(_range) {
    private val elements: MutableList<StaticSlotPatternElement> = mutableListOf()

    fun add(e: StaticSlotPatternElement) {
        elements.add(e)
    }

    // TODO CERT-3751: Could be refactored
    @Suppress("Deprecation") // TODO CERT-3752
    override fun kotlinize(resolver: TypeResolver, scope: CVLScope): CollectingResult<CVLSlotPattern, CVLError> {
        check(elements.size != 0) { "Missed case at $range" }
        return if (elements.size == 1) {
            when (val first = elements[0]) {
                is StaticSlotPatternNamed -> /* my_slot */ CVLSlotPattern.Static.Named(getSolidityContract(resolver), first.name).lift()

                is StaticSlotPatternNumber -> {
                    // (slot X)
                    val _slot = first.number.kotlinize(resolver, scope)
                    _slot.map { CVLSlotPattern.Static.Indexed(getSolidityContract(resolver), (it as NumberLit).n, BigInteger.ZERO) }
                }

                is StaticSlotPatternTwoNumbers -> {
                    // (slot X, offset Y)
                    val _slot   = first.number1.kotlinize(resolver, scope)
                    val _offset = first.number2.kotlinize(resolver, scope)
                    _slot.map(_offset) { slot, offset -> CVLSlotPattern.Static.Indexed(getSolidityContract(resolver), (slot as NumberLit).n, (offset as NumberLit).n) }
                }
            }
        } else {
            val (first,second) = elements
            var curr = when (first) {
                is StaticSlotPatternNamed -> {
                    val contract = SolidityContract(resolver.resolveContractName(first.name))
                    when (second) {
                        is StaticSlotPatternNamed -> /* MyContract.MyVariable */ CVLSlotPattern.Static.Named(contract, second.name).lift()
                        is StaticSlotPatternNumber -> when(second.prefix) {
                            SLOT   -> {
                                // MyContract.(slot X)
                                second.number.kotlinize(resolver, scope).map { slot ->
                                    CVLSlotPattern.Static.Indexed(contract, (slot as NumberLit).n, BigInteger.ZERO)
                                }
                            }

                            OFFSET -> {
                                // MyVariable.(offset X)
                                second.number.kotlinize(resolver, scope).map { offset ->
                                    CVLSlotPattern.StructAccess(CVLSlotPattern.Static.Named(getSolidityContract(resolver), first.name), (offset as NumberLit).n)
                                }
                            }
                            else   -> CVLError.General(range, "keyword should either be 'slot' or 'offset' at $range").asError()
                        }

                        is StaticSlotPatternTwoNumbers -> {
                            if (second.prefix1 != SLOT || second.prefix2 != OFFSET) {
                                CVLError.General(range, "Static slot must be of the form '(slot X, offset Y)' at $range").asError()
                            } else {
                                val _slot   = second.number1.kotlinize(resolver, scope)
                                val _offset = second.number2.kotlinize(resolver, scope)
                                _slot.map(_offset) { slot, offset -> CVLSlotPattern.Static.Indexed(contract, (slot as NumberLit).n, (offset as NumberLit).n) }
                            }
                        }
                    }
                }

                is StaticSlotPatternNumber -> {
                    if(first.prefix != SLOT)                    { CVLError.General(range, "static slot must have the form '(slot X)' at $range").asError() }
                    else if(second !is StaticSlotPatternNumber) { CVLError.General(range, "static slot must be followed by struct offset or array/map dereference at $range").asError() }
                    else if (second.prefix != OFFSET)           { CVLError.General(range, "static slot must be followed by struct offset or array/map dereference at $range").asError() }
                    else {
                        // (slot X).(offset Y)
                        val _slot = first.number.kotlinize(resolver, scope)
                        val _offset = second.number.kotlinize(resolver, scope)
                        _slot.map(_offset) { slot, offset ->
                            CVLSlotPattern.StructAccess(
                                CVLSlotPattern.Static.Indexed(getSolidityContract(resolver), (slot as NumberLit).n, BigInteger.ZERO),
                                (offset as NumberLit).n
                            )
                        }
                    }
                }

                is StaticSlotPatternTwoNumbers -> {
                    // (slot X, offset Y).(offset Z)
                    if (first.prefix1 != SLOT || first.prefix2 != OFFSET)                   { CVLError.General(range,"Static slot must be of the form '(slot X, offset Y)' at $range").asError() }
                    else if (second !is StaticSlotPatternNumber || second.prefix != OFFSET) { CVLError.General(range, "static slot must be followed by struct offset or array/map dereference at $range").asError() }
                    else {
                        val _slot = first.number1.kotlinize(resolver, scope)
                        val _offset1 = first.number2.kotlinize(resolver, scope)
                        val _offset2 = second.number.kotlinize(resolver, scope)

                        _slot.map(_offset1, _offset2) { slot, offset1, offset2 ->
                            CVLSlotPattern.StructAccess(
                                CVLSlotPattern.Static.Indexed(getSolidityContract(resolver), (slot as NumberLit).n, (offset1 as NumberLit).n),
                                (offset2 as NumberLit).n
                            )
                        }
                    }
                }
            }

            // some sequence of (offset X) following first two elements
            for (i in 2 until elements.size) {
                val el = elements[i]
                val msg = "pattern $curr must be followed by a sequence of (offset X) or field names $range"
                curr = when (el) {
                    is StaticSlotPatternNumber -> {
                        if (el.prefix != OFFSET) { CVLError.General(range, msg).asError() }
                        else { curr.map(el.number.kotlinize(resolver, scope)) { base, offset -> CVLSlotPattern.StructAccess(base, (offset as NumberLit).n) } }
                    }

                    is StaticSlotPatternNamed -> curr.map { CVLSlotPattern.FieldAccess(it, el.name) }

                    else -> CVLError.General(range, msg).asError()
                }
            }
            curr
        }
    }

    private fun getSolidityContract(resolver: TypeResolver): SolidityContract {
        return SolidityContract(resolver.resolveContractName(Current.name))
    }

    companion object {
        private const val SLOT = "slot"
        private const val OFFSET = "offset"
    }
}


class FieldAccessSlotPattern(private val base: SlotPattern, private val fieldName: String) : SlotPattern(base.range) {
    override fun kotlinize(resolver: TypeResolver, scope: CVLScope): CollectingResult<CVLSlotPattern, CVLError>
        = base.kotlinize(resolver, scope).map { base -> CVLSlotPattern.FieldAccess(base, fieldName) }
}


class MapAccessSlotPattern(private val base: SlotPattern, private val key: NamedVMParam) : SlotPattern(base.range) {
    override fun kotlinize(resolver: TypeResolver, scope: CVLScope): CollectingResult<CVLSlotPattern, CVLError> = collectingErrors {
        map(base.kotlinize(resolver, scope), key.kotlinize(resolver, scope))
            { base, key -> CVLSlotPattern.MapAccess(base, key) }
    }
}


sealed interface StaticSlotPatternElement
class StaticSlotPatternNamed(val name: String) : StaticSlotPatternElement
class StaticSlotPatternNumber(val prefix: String, val number: NumberExp) : StaticSlotPatternElement
class StaticSlotPatternTwoNumbers(val prefix1: String, val number1: NumberExp, val prefix2: String, val number2: NumberExp) : StaticSlotPatternElement

class StructAccessSlotPattern(val base: SlotPattern, val offset: NumberExp) : SlotPattern(base.range) {
    override fun kotlinize(resolver: TypeResolver, scope: CVLScope): CollectingResult<CVLSlotPattern, CVLError> = collectingErrors {
        map(base.kotlinize(resolver, scope), offset.kotlinize(resolver, scope))
            { base, offset -> CVLSlotPattern.StructAccess(base, (offset as NumberLit).n) }
    }
}
