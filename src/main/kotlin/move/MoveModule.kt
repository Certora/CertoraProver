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

package move

import datastructures.stdcollections.*
import java.math.BigInteger
import java.nio.*
import java.nio.file.*
import java.util.*
import log.*
import utils.*

private val logger = Logger(LoggerTypes.MOVE)

/**
    Parses the Move binary module format.

    See https://github.com/move-language/move-sui/tree/main/crates/move-binary-format#readme
 */
class MoveModule(val path: Path) {

    /** The version of the Move VM this module was compiled for */
    val version: MoveVersion
    val moduleName: MoveModuleName

    private val moduleHandles: List<ModuleHandle>?
    private val datatypeHandles: List<DatatypeHandle>?
    private val functionHandles: List<FunctionHandle>?
    private val functionInstantiations: List<FunctionInstantiation>?
    private val signatures: List<Signature>?
    private val constantPool: List<Constant>?
    private val identifiers: List<Identifier>?
    private val addressIdentifiers: List<AccountAddress>?
    private val structDefinitions: List<StructDefinition>?
    private val structDefInstantiations: List<StructDefInstantiation>?
    public val functionDefinitions: List<FunctionDefinition>?
    private val fieldHandles: List<FieldHandle>?
    private val fieldInstantiations: List<FieldInstantiation>?
    private val friendDecls: List<ModuleHandle>?
    public val metadata: List<Metadata>?
    private val enumDefinitions: List<EnumDefinition>?
    private val enumDefInstantiations: List<EnumDefInstantiation>?
    private val variantHandles: List<VariantHandle>?
    private val variantInstantiationHandles: List<VariantInstantiationHandle>?

    init {
        val buf = ByteBuffer.wrap(Files.readAllBytes(path))
        try {
            // Check the magic number and the Move version
            val magic = buf.getBEUInt()
            check(magic == MOVE_MAGIC || magic == UNPUBLISHABLE_MAGIC) {
                "In $path: Invalid Move binary format: unexpected magic number 0x${magic.toString(16)}"
            }

            // Parse the binary version
            version = MoveVersion.parse(buf)

            // Read the table descriptions: (table type, offset, size)
            val tables = mutableMapOf<TableType, IntRange>()
            val tableCount = buf.getLeb128UInt().toInt()
            repeat(tableCount) {
                val type = TableType.parse(buf)
                check(type !in tables) {
                    "In $path: Invalid Move binary format: duplicate table type $type"
                }

                val offset = buf.getLeb128UInt().toInt()
                val size = buf.getLeb128UInt().toInt()

                tables[type] = offset ..< offset + size
            }

            // Check that the tables are contiguous
            check(tables.isNotEmpty()) { "In $path: Invalid Move binary format: no tables found" }
            tables.entries.sortedBy { it.value.start }.zipWithNext().forEach { (a, b) ->
                check(a.value.endInclusive == b.value.start - 1) {
                    "In $path: Invalid Move binary format: tables ${a.key} and ${b.key} are not contiguous"
                }
            }

            val tableDataStart = buf.position()

            // Get the self module handle index, which is the last thing in the file, after the table data
            val tableDataEnd = tableDataStart + tables.values.maxOf { it.endInclusive } + 1
            val selfModuleIndex = buf.slice(tableDataEnd, buf.limit() - tableDataEnd).getLeb128UInt().toInt()

            // Read the tables
            fun <T> readTable(type: TableType, reader: (ByteBuffer) -> T) =
                tables[type]?.let {
                    reader(buf.slice(tableDataStart + it.start, 1 + it.endInclusive - it.start))
                }
            moduleHandles = readTable(TableType.MODULE_HANDLES) { ModuleHandle.parseTable(it) }
            datatypeHandles = readTable(TableType.DATATYPE_HANDLES) { DatatypeHandle.parseTable(it) }
            functionHandles = readTable(TableType.FUNCTION_HANDLES) { FunctionHandle.parseTable(it) }
            functionInstantiations = readTable(TableType.FUNCTION_INST) { FunctionInstantiation.parseTable(it) }
            signatures = readTable(TableType.SIGNATURES) { Signature.parseTable(it) }
            constantPool = readTable(TableType.CONSTANT_POOL) { Constant.parseTable(it) }
            identifiers = readTable(TableType.IDENTIFIERS) { Identifier.parseTable(it) }
            addressIdentifiers = readTable(TableType.ADDRESS_IDENTIFIERS) { AccountAddress.parseTable(it) }
            structDefinitions = readTable(TableType.STRUCT_DEFS) { StructDefinition.parseTable(it) }
            structDefInstantiations = readTable(TableType.STRUCT_DEF_INST) { StructDefInstantiation.parseTable(it) }
            functionDefinitions = readTable(TableType.FUNCTION_DEFS) { FunctionDefinition.parseTable(it) }
            fieldHandles = readTable(TableType.FIELD_HANDLE) { FieldHandle.parseTable(it) }
            fieldInstantiations = readTable(TableType.FIELD_INST) { FieldInstantiation.parseTable(it) }
            friendDecls = readTable(TableType.FRIEND_DECLS) { ModuleHandle.parseTable(it) }
            metadata = readTable(TableType.METADATA) { Metadata.parseTable(it) }
            enumDefinitions = readTable(TableType.ENUM_DEFS) { EnumDefinition.parseTable(it) }
            enumDefInstantiations = readTable(TableType.ENUM_DEF_INST) { EnumDefInstantiation.parseTable(it) }
            variantHandles = readTable(TableType.VARIANT_HANDLES) { VariantHandle.parseTable(it) }
            variantInstantiationHandles = readTable(TableType.VARIANT_INST_HANDLES) { VariantInstantiationHandle.parseTable(it) }

            moduleName = ModuleHandleIndex(selfModuleIndex).deref().name

        } catch (e: BufferUnderflowException) {
            throw IllegalStateException("In $path: Invalid Move binary format: not enough bytes in the file", e)
        }
        logger.trace { "Loaded Move module $moduleName" }
    }

    companion object {
        const val FILE_EXTENSION = ".mv"

        // Every Move binary module starts with one of these magic numbers.
        private const val MOVE_MAGIC = 0xA11CEB0Bu
        private const val UNPUBLISHABLE_MAGIC = 0xDEADC0DEu
    }

    data class MoveVersion(
        val major: Int,
        val flavor: Flavor?
    ) {
        enum class Flavor(val encoding: Int) {
            Sui(0x5),
            ;
            companion object {
                fun decode(encoding: Int) =
                    values().find { it.encoding == encoding } ?: error("Unknown Move flavor $encoding")
            }
        }
        companion object {
            fun parse(buf: ByteBuffer): MoveVersion {
                val encoding = buf.getLEUInt()
                val major = encoding and 0x00FFFFFFu
                val flavor = if (major <= 6u) { null } else { encoding shr 24 }
                return MoveVersion(major.toInt(), flavor?.let { MoveVersion.Flavor.decode(it.toInt()) })
            }
        }
    }

    private enum class TableType(val encoding: Int) {
        MODULE_HANDLES(0x1),
        DATATYPE_HANDLES(0x2),
        FUNCTION_HANDLES(0x3),
        FUNCTION_INST(0x4),
        SIGNATURES(0x5),
        CONSTANT_POOL(0x6),
        IDENTIFIERS(0x7),
        ADDRESS_IDENTIFIERS(0x8),
        STRUCT_DEFS(0xA),
        STRUCT_DEF_INST(0xB),
        FUNCTION_DEFS(0xC),
        FIELD_HANDLE(0xD),
        FIELD_INST(0xE),
        FRIEND_DECLS(0xF),
        METADATA(0x10),
        ENUM_DEFS(0x11),
        ENUM_DEF_INST(0x12),
        VARIANT_HANDLES(0x13),
        VARIANT_INST_HANDLES(0x14),
        ;
        companion object {
            fun parse(buf: ByteBuffer): TableType {
                val encoding = buf.getUByte().toInt()
                return values().find { it.encoding == encoding } ?: error("Unknown Move table type $encoding")
            }
        }
    }

    enum class SerializedType(val encoding: Int) {
        BOOL(0x1),
        U8(0x2),
        U64(0x3),
        U128(0x4),
        ADDRESS(0x5),
        REFERENCE(0x6),
        MUTABLE_REFERENCE(0x7),
        STRUCT(0x8),
        TYPE_PARAMETER(0x9),
        VECTOR(0xA),
        DATATYPE_INST(0xB),
        SIGNER(0xC),
        U16(0xD),
        U32(0xE),
        U256(0xF)
        ;
        companion object {
            fun parse(buf: ByteBuffer): SerializedType {
                val encoding = buf.getUByte().toInt()
                return values().find { it.encoding == encoding } ?: error("Unknown SerializedType $encoding")
            }
        }
    }

    enum class Ability(val encoding: Int) {
        Copy(0x1),
        Drop(0x2),
        Store(0x4),
        Key(0x8),
        ;
        companion object {
            context(MoveModule)
            fun parse(buf: ByteBuffer): Set<Ability> {
                check(version.major >= 2) {
                    "Ability set parsing is only supported for Move version 2 and above"
                }
                val encoding = buf.getUByte().toInt()
                check(encoding < 0x10) {
                    "In ${buf.position()}: Invalid Move ability encoding $encoding"
                }
                return values().filter { (it.encoding and encoding) != 0 }.toSet()
            }
        }
    }

    enum class Visibility(val encoding: Int) {
        Private(0x0),
        Public(0x1),
        Friend(0x3),
        ;
        companion object {
            const val DEPRECATED_SCRIPT = 0x2

            fun fromEncoding(encoding: Int) =
                values().find { it.encoding == encoding } ?: error("Unknown Move visibility $encoding")

            fun parse(buf: ByteBuffer) = fromEncoding(buf.getUByte().toInt())
        }
    }

    private enum class Opcodes(val encoding: Int) {
        POP(0x01),
        RET(0x02),
        BR_TRUE(0x03),
        BR_FALSE(0x04),
        BRANCH(0x05),
        LD_U64(0x06),
        LD_CONST(0x07),
        LD_TRUE(0x08),
        LD_FALSE(0x09),
        COPY_LOC(0x0A),
        MOVE_LOC(0x0B),
        ST_LOC(0x0C),
        MUT_BORROW_LOC(0x0D),
        IMM_BORROW_LOC(0x0E),
        MUT_BORROW_FIELD(0x0F),
        IMM_BORROW_FIELD(0x10),
        CALL(0x11),
        PACK(0x12),
        UNPACK(0x13),
        READ_REF(0x14),
        WRITE_REF(0x15),
        ADD(0x16),
        SUB(0x17),
        MUL(0x18),
        MOD(0x19),
        DIV(0x1A),
        BIT_OR(0x1B),
        BIT_AND(0x1C),
        XOR(0x1D),
        OR(0x1E),
        AND(0x1F),
        NOT(0x20),
        EQ(0x21),
        NEQ(0x22),
        LT(0x23),
        GT(0x24),
        LE(0x25),
        GE(0x26),
        ABORT(0x27),
        NOP(0x28),
        FREEZE_REF(0x2E),
        SHL(0x2F),
        SHR(0x30),
        LD_U8(0x31),
        LD_U128(0x32),
        CAST_U8(0x33),
        CAST_U64(0x34),
        CAST_U128(0x35),
        MUT_BORROW_FIELD_GENERIC(0x36),
        IMM_BORROW_FIELD_GENERIC(0x37),
        CALL_GENERIC(0x38),
        PACK_GENERIC(0x39),
        UNPACK_GENERIC(0x3A),
        VEC_PACK(0x40),
        VEC_LEN(0x41),
        VEC_IMM_BORROW(0x42),
        VEC_MUT_BORROW(0x43),
        VEC_PUSH_BACK(0x44),
        VEC_POP_BACK(0x45),
        VEC_UNPACK(0x46),
        VEC_SWAP(0x47),
        LD_U16(0x48),
        LD_U32(0x49),
        LD_U256(0x4A),
        CAST_U16(0x4B),
        CAST_U32(0x4C),
        CAST_U256(0x4D),
        PACK_VARIANT(0x4E),
        PACK_VARIANT_GENERIC(0x4F),
        UNPACK_VARIANT(0x50),
        UNPACK_VARIANT_IMM_REF(0x51),
        UNPACK_VARIANT_MUT_REF(0x52),
        UNPACK_VARIANT_GENERIC(0x53),
        UNPACK_VARIANT_GENERIC_IMM_REF(0x54),
        UNPACK_VARIANT_GENERIC_MUT_REF(0x55),
        VARIANT_SWITCH(0x56),

        // Deprecated in Sui
        EXISTS_DEPRECATED(0x29),
        MUT_BORROW_GLOBAL_DEPRECATED(0x2A),
        IMM_BORROW_GLOBAL_DEPRECATED(0x2B),
        MOVE_FROM_DEPRECATED(0x2C),
        MOVE_TO_DEPRECATED(0x2D),
        EXISTS_GENERIC_DEPRECATED(0x3B),
        MUT_BORROW_GLOBAL_GENERIC_DEPRECATED(0x3C),
        IMM_BORROW_GLOBAL_GENERIC_DEPRECATED(0x3D),
        MOVE_FROM_GENERIC_DEPRECATED(0x3E),
        MOVE_TO_GENERIC_DEPRECATED(0x3F)
        ;
        companion object {
            private val byEncoding = values().associateBy { it.encoding }

            fun parse(buf: ByteBuffer): Opcodes {
                val encoding = buf.getUByte().toInt()
                return byEncoding[encoding] ?: error("Unknown Move opcode $encoding")
            }
        }
    }

    //
    // For each table, we define an index type and a getter function.
    //
    inner class ModuleHandleIndex(val index: Int) {
        fun deref() = moduleHandles?.get(index) ?: error("Module handle $index not found")
    }
    private fun parseModuleHandleIndex(buf: ByteBuffer) = ModuleHandleIndex(buf.getLeb128UInt().toInt())

    inner class DatatypeHandleIndex(val index: Int) {
        fun deref() = datatypeHandles?.get(index) ?: error("Datatype handle $index not found")
    }
    private fun parseDatatypeHandleIndex(buf: ByteBuffer) = DatatypeHandleIndex(buf.getLeb128UInt().toInt())

    inner class FunctionHandleIndex(val index: Int) {
        fun deref() = functionHandles?.get(index) ?: error("Function handle $index not found")
    }
    private fun parseFunctionHandleIndex(buf: ByteBuffer) = FunctionHandleIndex(buf.getLeb128UInt().toInt())

    inner class FunctionInstantiationIndex(val index: Int) {
        fun deref() = functionInstantiations?.get(index) ?: error("Function instantiation $index not found")
    }
    private fun parseFunctionInstantiationIndex(buf: ByteBuffer) = FunctionInstantiationIndex(buf.getLeb128UInt().toInt())

    inner class SignatureIndex(val index: Int) {
        fun deref() = signatures?.get(index) ?: error("Signature $index not found")
    }
    private fun parseSignatureIndex(buf: ByteBuffer) = SignatureIndex(buf.getLeb128UInt().toInt())

    inner class ConstantPoolIndex(val index: Int) {
        fun deref() = constantPool?.get(index) ?: error("Constant pool $index not found")
    }
    private fun parseConstantPoolIndex(buf: ByteBuffer) = ConstantPoolIndex(buf.getLeb128UInt().toInt())

    inner class IdentifierIndex(val index: Int) {
        fun deref() = identifiers?.get(index) ?: error("Identifier $index not found")
    }
    private fun parseIdentifierIndex(buf: ByteBuffer) = IdentifierIndex(buf.getLeb128UInt().toInt())

    inner class AddressIdentifierIndex(val index: Int) {
        fun deref() = addressIdentifiers?.get(index) ?: error("Address identifier $index not found")
    }
    private fun parseAddressIdentifierIndex(buf: ByteBuffer) = AddressIdentifierIndex(buf.getLeb128UInt().toInt())

    inner class StructDefinitionIndex(val index: Int) {
        fun deref() = structDefinitions?.get(index) ?: error("Struct definition $index not found")
    }
    private fun parseStructDefinitionIndex(buf: ByteBuffer) = StructDefinitionIndex(buf.getLeb128UInt().toInt())

    inner class StructDefInstantiationIndex(val index: Int) {
        fun deref() = structDefInstantiations?.get(index) ?: error("Struct definition instantiation $index not found")
    }
    private fun parseStructDefInstantiationIndex(buf: ByteBuffer) = StructDefInstantiationIndex(buf.getLeb128UInt().toInt())

    inner class FieldHandleIndex(val index: Int) {
        fun deref() = fieldHandles?.get(index) ?: error("Field handle $index not found")
    }
    private fun parseFieldHandleIndex(buf: ByteBuffer) = FieldHandleIndex(buf.getLeb128UInt().toInt())

    inner class FieldInstantiationIndex(val index: Int) {
        fun deref() = fieldInstantiations?.get(index) ?: error("Field instantiation $index not found")
    }
    private fun parseFieldInstantiationIndex(buf: ByteBuffer) = FieldInstantiationIndex(buf.getLeb128UInt().toInt())

    inner class VariantHandleIndex(val index: Int) {
        fun deref() = variantHandles?.get(index) ?: error("Variant handle $index not found")
    }
    private fun parseVariantHandleIndex(buf: ByteBuffer) = VariantHandleIndex(buf.getLeb128UInt().toInt())

    inner class VariantInstantiationHandleIndex(val index: Int) {
        fun deref() = variantInstantiationHandles?.get(index) ?: error("Variant instantiation handle $index not found")
    }
    private fun parseVariantInstantiationHandleIndex(buf: ByteBuffer) = VariantInstantiationHandleIndex(buf.getLeb128UInt().toInt())

    inner class EnumDefinitionIndex(val index: Int) {
        fun deref() = enumDefinitions?.get(index) ?: error("Enum definition $index not found")
    }
    private fun parseEnumDefinitionIndex(buf: ByteBuffer) = EnumDefinitionIndex(buf.getLeb128UInt().toInt())

    inner class EnumDefInstantiationIndex(val index: Int) {
        fun deref() = enumDefInstantiations?.get(index) ?: error("Enum definition instantiation $index not found")
    }
    private fun parseEnumDefInstantiationIndex(buf: ByteBuffer) = EnumDefInstantiationIndex(buf.getLeb128UInt().toInt())


    @JvmInline value class TypeParameterIndex(val index: Int)
    private fun parseTypeParameterIndex(buf: ByteBuffer) = TypeParameterIndex(buf.getLeb128UInt().toInt())

    @JvmInline value class VariantJumpTableIndex(val index: Int)
    private fun parseVariantJumpTableIndex(buf: ByteBuffer) = VariantJumpTableIndex(buf.getLeb128UInt().toInt())

    @JvmInline value class VariantTag(val index: Int)
    private fun parseVariantTag(buf: ByteBuffer) = VariantTag(buf.getLeb128UInt().toInt())


    class ModuleHandle(
        val addressIndex: AddressIdentifierIndex,
        val nameIndex: IdentifierIndex
    ) {
        val name get() = MoveModuleName(addressIndex.deref().value, nameIndex.deref().value)

        companion object {
            context(MoveModule)
            fun parseTable(buf: ByteBuffer) = buf.parseAll {
                ModuleHandle(
                    addressIndex = parseAddressIdentifierIndex(buf),
                    nameIndex = parseIdentifierIndex(buf)
                )
            }
        }
    }

    data class DatatypeId(
        private val module: MoveModuleName,
        private val simpleName: String,
        private val abilities: Set<Ability>,
        private val typeParameters: List<DatatypeTyParameter>,
    ) {
        val qualifiedName get() = "$module::$simpleName"
    }

    class DatatypeHandle(
        val module: ModuleHandleIndex,
        val nameIndex: IdentifierIndex,
        val abilities: Set<Ability>,
        val typeParameters: List<DatatypeTyParameter>
    ) {
        val definingModule get() = module.deref().name
        val simpleName get() = nameIndex.deref().value
        val qualifiedName get() = "$definingModule::$simpleName"

        val id get() = DatatypeId(
            module = module.deref().name,
            simpleName = simpleName,
            abilities = abilities,
            typeParameters = typeParameters
        )

        override fun equals(other: Any?) = other is DatatypeHandle && id == other.id
        override fun hashCode() = id.hashCode()

        companion object {
            context(MoveModule)
            fun parseTable(buf: ByteBuffer) = buf.parseAll {
                DatatypeHandle(
                    module = parseModuleHandleIndex(buf),
                    nameIndex = parseIdentifierIndex(buf),
                    abilities = Ability.parse(buf),
                    typeParameters = buf.parseList { DatatypeTyParameter.parse(buf) }
                )
            }
        }
    }

    data class DatatypeTyParameter(
        val constraints: Set<Ability>,
        val isPhantom: Boolean
    ) {
        companion object {
            context(MoveModule)
            fun parse(buf: ByteBuffer) = DatatypeTyParameter(
                constraints = Ability.parse(buf),
                isPhantom = if (version.major < 3) { false } else { buf.getLeb128UInt() != 0u }
            )
        }
    }

    data class FunctionId(
        private val name: MoveFunctionName,
        private val params: Signature,
        private val returns: Signature,
        private val typeParameters: List<Set<Ability>>
    )

    class FunctionHandle(
        private val module: ModuleHandleIndex,
        private val nameIndex: IdentifierIndex,
        private val paramsIndex: SignatureIndex,
        private val returnsIndex: SignatureIndex,
        val typeParameters: List<Set<Ability>>
    ) {
        val name get() = MoveFunctionName(module.deref().name, nameIndex.deref().value)
        val qualifiedName get() = "$name"

        val id get() = FunctionId(
            name = name,
            params = paramsIndex.deref(),
            returns = returnsIndex.deref(),
            typeParameters = typeParameters
        )

        override fun equals(other: Any?) = other is FunctionHandle && id == other.id
        override fun hashCode() = id.hashCode()

        val params get() = paramsIndex.deref().tokens
        val returns get() = returnsIndex.deref().tokens

        companion object {
            context(MoveModule)
            fun parseTable(buf: ByteBuffer) = buf.parseAll {
                FunctionHandle(
                    module = parseModuleHandleIndex(buf),
                    nameIndex = parseIdentifierIndex(buf),
                    paramsIndex = parseSignatureIndex(buf),
                    returnsIndex = parseSignatureIndex(buf),
                    typeParameters = buf.parseList { Ability.parse(buf) }
                )
            }
        }
    }

    class FunctionInstantiation(
        private val functionIndex: FunctionHandleIndex,
        val typeParameters: SignatureIndex
    ) {
        val function get() = functionIndex.deref()

        companion object {
            context(MoveModule)
            fun parseTable(buf: ByteBuffer) = buf.parseAll {
                FunctionInstantiation(
                    functionIndex = parseFunctionHandleIndex(buf),
                    typeParameters = parseSignatureIndex(buf)
                )
            }
        }
    }

    data class Signature(
        val tokens: List<SignatureToken>
    ) {
        companion object {
            context(MoveModule)
            fun parseTable(buf: ByteBuffer) = buf.parseAll {
                Signature(
                    tokens = buf.parseList { SignatureToken.parse(buf) }
                )
            }
        }
    }

    sealed class SignatureToken {
        sealed class Value : SignatureToken()
        sealed class DatatypeValue : Value() {
            abstract val handle: DatatypeHandle
            abstract val typeArguments: List<SignatureToken>
        }
        sealed class Reference : SignatureToken() {
            abstract val type: SignatureToken
        }

        sealed class Primitive : Value() { override fun toString() = this::class.simpleName ?: super.toString() }
        sealed class Bits(val size: Int) : Primitive()

        object Bool : Primitive()
        object U8 : Bits(8)
        object U16 : Bits(16)
        object U32 : Bits(32)
        object U64 : Bits(64)
        object U128 : Bits(128)
        object U256 : Bits(256)
        object Address : Bits(256)
        object Signer : Bits(256)
        data class Vector(val type: SignatureToken) : Value()
        data class Datatype(val handleIndex: DatatypeHandleIndex) : DatatypeValue() {
            override val handle get() = handleIndex.deref()
            override val typeArguments = listOf<SignatureToken>()
            override fun hashCode() = handle.hashCode()
            override fun equals(other: Any?) = other is Datatype && handle == other.handle
        }
        data class DatatypeInstantiation(private val handleIndex: DatatypeHandleIndex, override val typeArguments: List<SignatureToken>) : DatatypeValue() {
            override val handle get() = handleIndex.deref()
            override fun hashCode() = Objects.hash(handle, typeArguments)
            override fun equals(other: Any?) = other is DatatypeInstantiation && handle == other.handle && typeArguments == other.typeArguments
        }
        data class ImmutableReference(override val type: SignatureToken) : Reference()
        data class MutableReference(override val type: SignatureToken) : Reference()
        data class TypeParameter(val index: TypeParameterIndex) : Value()

        companion object {
            context(MoveModule)
            fun parse(buf: ByteBuffer): SignatureToken {
                val type = SerializedType.parse(buf)
                return when (type) {
                    SerializedType.BOOL -> Bool
                    SerializedType.U8 -> U8
                    SerializedType.U16 -> U16
                    SerializedType.U32 -> U32
                    SerializedType.U64 -> U64
                    SerializedType.U128 -> U128
                    SerializedType.U256 -> U256
                    SerializedType.ADDRESS -> Address
                    SerializedType.REFERENCE -> ImmutableReference(SignatureToken.parse(buf))
                    SerializedType.MUTABLE_REFERENCE -> MutableReference(parse(buf))
                    SerializedType.STRUCT -> Datatype(parseDatatypeHandleIndex(buf))
                    SerializedType.TYPE_PARAMETER -> TypeParameter(parseTypeParameterIndex(buf))
                    SerializedType.VECTOR -> Vector(SignatureToken.parse(buf))
                    SerializedType.DATATYPE_INST -> DatatypeInstantiation(
                        parseDatatypeHandleIndex(buf),
                        buf.parseList { SignatureToken.parse(buf) }
                    )
                    SerializedType.SIGNER -> Signer
                }
            }
        }
    }

    class Constant(
        val type: SignatureToken,
        val data: List<UByte>
    ) {
        companion object {
            context(MoveModule)
            fun parseTable(buf: ByteBuffer) = buf.parseAll {
                Constant(
                    type = SignatureToken.parse(buf),
                    data = buf.parseList { buf.getUByte() }
                )
            }
        }
    }

    class Identifier(
        val value: String
    ) {
        override fun toString() = value

        companion object {
            fun parseTable(buf: ByteBuffer) = buf.parseAll {
                Identifier(buf.getUtf8String())
            }
        }
    }

    class AccountAddress(
        val value: BigInteger
    ) {
        override fun toString() = value.toString(16).padStart(64, '0')
        companion object {
            fun parseTable(buf: ByteBuffer) = buf.parseAll { AccountAddress(buf.getBEUInt256()) }
        }
    }

    class StructDefinition(
        val structHandleIndex: DatatypeHandleIndex,
        val fieldInformation: StructFieldInformation
    ) {
        val structHandle get() = structHandleIndex.deref()
        val name = MoveStructName(structHandle.definingModule, structHandle.simpleName)
        val fields get() = fieldInformation.fields

        companion object {
            context(MoveModule)
            fun parseTable(buf: ByteBuffer) = buf.parseAll {
                StructDefinition(
                    structHandleIndex = parseDatatypeHandleIndex(buf),
                    fieldInformation = StructFieldInformation.parse(buf)
                )
            }
        }
    }

    sealed class StructFieldInformation {
        abstract val fields: List<FieldDefinition>?

        object Native : StructFieldInformation() {
            override val fields get() = null
        }

        data class Declared(override val fields: List<FieldDefinition>) : StructFieldInformation()

        companion object {
            context(MoveModule)
            fun parse(buf: ByteBuffer) = when (buf.getUByte().toInt()) {
                0x1 -> Native
                0x2 -> Declared(buf.parseList { FieldDefinition.parse(buf) })
                else -> error("Invalid field information encoding")
            }
        }
    }

    class FieldDefinition(
        private val nameIndex: IdentifierIndex,
        val signature: SignatureToken
    ) {
        val name get() = nameIndex.deref().value

        companion object {
            context(MoveModule)
            fun parse(buf: ByteBuffer) = FieldDefinition(
                nameIndex = parseIdentifierIndex(buf),
                signature = SignatureToken.parse(buf)
            )
        }
    }

    class StructDefInstantiation(
        val def: StructDefinitionIndex,
        val typeParameters: SignatureIndex
    ) {
        companion object {
            context(MoveModule)
            fun parseTable(buf: ByteBuffer) = buf.parseAll {
                StructDefInstantiation(
                    def = parseStructDefinitionIndex(buf),
                    typeParameters = parseSignatureIndex(buf)
                )
            }
        }
    }

    class FunctionDefinition(
        val definitionIndex: Int,
        val functionIndex: FunctionHandleIndex,
        val visibility: Visibility,
        val isEntry: Boolean,
        val acquiresGlobalResources: List<StructDefinitionIndex>,
        val code: CodeUnit?
    ) {
        val function get() = functionIndex.deref()
        val qualifiedName get() = function.qualifiedName

        companion object {
            private const val DEPRECATED_PUBLIC_BIT = 0b01
            private const val NATIVE = 0b10
            private const val ENTRY = 0b100

            context(MoveModule)
            fun parseTable(buf: ByteBuffer) = buf.parseAll { definitionIndex ->
                val function = parseFunctionHandleIndex(buf)
                val flags = buf.getUByte().toInt()
                val (visibility, isEntry, isNative) = when {
                    // In version 1, flags is a byte encoding the visibility and whether the function is native
                    version.major <= 1 -> Triple(
                        if (flags and DEPRECATED_PUBLIC_BIT != 0) { Visibility.Public } else { Visibility.Private },
                        false,
                        flags and NATIVE != 0
                    )
                    // Starting with version 2, the first byte only represents the visibility, and we need to read an
                    // additional byte to get additional flags.
                    version.major in 2..4 -> {
                        val (visibility, isEntry) = if (flags == Visibility.DEPRECATED_SCRIPT) {
                            Visibility.Public to true
                        } else {
                            Visibility.fromEncoding(flags) to false
                        }
                        val extraFlags = buf.getUByte().toInt()
                        Triple(
                            visibility,
                            isEntry,
                            extraFlags and NATIVE != 0
                        )
                    }
                    // Starting with version 5, script visibility is moved to the second byte
                    version.major >= 5 -> {
                        val visibility = Visibility.fromEncoding(flags)
                        val extraFlags = buf.getUByte().toInt()
                        Triple(
                            visibility,
                            extraFlags and ENTRY != 0,
                            extraFlags and NATIVE != 0
                        )
                    }
                    else -> error("Invalid Move version $version")
                }
                val acquiresGlobalResources = buf.parseList { parseStructDefinitionIndex(buf) }
                val code = runIf(!isNative) { CodeUnit.parse(buf) }

                FunctionDefinition(
                    definitionIndex = definitionIndex,
                    functionIndex = function,
                    visibility = visibility,
                    isEntry = isEntry,
                    acquiresGlobalResources = acquiresGlobalResources,
                    code = code
                )
            }
        }
    }

    class CodeUnit private constructor(
        val localsIndex: SignatureIndex,
        val instructions: List<Instruction>,
        private val jumpTables: List<VariantJumpTable>?,
    ) {
        val locals get() = localsIndex.deref().tokens

        companion object {
            context(MoveModule)
            fun parse(buf: ByteBuffer) = CodeUnit(
                localsIndex = parseSignatureIndex(buf),
                instructions = buf.parseList { Instruction.parse(buf) },
                jumpTables = runIf(version.major >= 7) { buf.parseList { VariantJumpTable.parse(buf) } }
            )
        }
    }

    @JvmInline value class CodeOffset private constructor(val index: Int) {
        companion object {
            fun parse(buf: ByteBuffer) = CodeOffset(buf.getLeb128UInt().toInt())
        }
    }
    @JvmInline value class LocalIndex private constructor(val index: Int) {
        companion object {
            fun parse(buf: ByteBuffer) = LocalIndex(buf.getLeb128UInt().toInt())
        }
    }

    sealed class Instruction {
        object Pop : Instruction()
        object Ret : Instruction()
        data class BrTrue(val branchTarget: CodeOffset) : Instruction()
        data class BrFalse(val branchTarget: CodeOffset) : Instruction()
        data class Branch(val branchTarget: CodeOffset) : Instruction()
        data class LdU8(val value: UByte) : Instruction()
        data class LdU64(val value: ULong) : Instruction()
        data class LdU128(val value: BigInteger) : Instruction()
        object CastU8 : Instruction()
        object CastU64 : Instruction()
        object CastU128 : Instruction()
        data class LdConst(val index: ConstantPoolIndex) : Instruction()
        object LdTrue : Instruction()
        object LdFalse : Instruction()
        data class CopyLoc(val index: LocalIndex) : Instruction()
        data class MoveLoc(val index: LocalIndex) : Instruction()
        data class StLoc(val index: LocalIndex) : Instruction()
        data class Call(val index: FunctionHandleIndex) : Instruction()
        data class CallGeneric(val index: FunctionInstantiationIndex) : Instruction()
        data class Pack(val index: StructDefinitionIndex) : Instruction()
        data class PackGeneric(val index: StructDefInstantiationIndex) : Instruction()
        data class Unpack(val index: StructDefinitionIndex) : Instruction()
        data class UnpackGeneric(val index: StructDefInstantiationIndex) : Instruction()
        object ReadRef : Instruction()
        object WriteRef : Instruction()
        object FreezeRef : Instruction()
        data class MutBorrowLoc(val index: LocalIndex) : Instruction()
        data class ImmBorrowLoc(val index: LocalIndex) : Instruction()
        data class MutBorrowField(val index: FieldHandleIndex) : Instruction()
        data class MutBorrowFieldGeneric(val index: FieldInstantiationIndex) : Instruction()
        data class ImmBorrowField(val index: FieldHandleIndex) : Instruction()
        data class ImmBorrowFieldGeneric(val index: FieldInstantiationIndex) : Instruction()
        object Add : Instruction()
        object Sub : Instruction()
        object Mul : Instruction()
        object Mod : Instruction()
        object Div : Instruction()
        object BitOr : Instruction()
        object BitAnd : Instruction()
        object Xor : Instruction()
        object Or : Instruction()
        object And : Instruction()
        object Not : Instruction()
        object Eq : Instruction()
        object Neq : Instruction()
        object Lt : Instruction()
        object Gt : Instruction()
        object Le : Instruction()
        object Ge : Instruction()
        object Abort : Instruction()
        object Nop : Instruction()
        object Shl : Instruction()
        object Shr : Instruction()
        data class VecPack(val index: SignatureIndex, val count: ULong) : Instruction()
        data class VecLen(val index: SignatureIndex) : Instruction()
        data class VecImmBorrow(val index: SignatureIndex) : Instruction()
        data class VecMutBorrow(val index: SignatureIndex) : Instruction()
        data class VecPushBack(val index: SignatureIndex) : Instruction()
        data class VecPopBack(val index: SignatureIndex) : Instruction()
        data class VecUnpack(val index: SignatureIndex, val count: ULong) : Instruction()
        data class VecSwap(val index: SignatureIndex) : Instruction()
        data class LdU16(val value: UShort) : Instruction()
        data class LdU32(val value: UInt) : Instruction()
        data class LdU256(val value: BigInteger) : Instruction()
        object CastU16 : Instruction()
        object CastU32 : Instruction()
        object CastU256 : Instruction()
        data class PackVariant(val index: VariantHandleIndex) : Instruction()
        data class PackVariantGeneric(val index: VariantInstantiationHandleIndex) : Instruction()
        data class UnpackVariant(val index: VariantHandleIndex) : Instruction()
        data class UnpackVariantImmRef(val index: VariantHandleIndex) : Instruction()
        data class UnpackVariantMutRef(val index: VariantHandleIndex) : Instruction()
        data class UnpackVariantGeneric(val index: VariantInstantiationHandleIndex) : Instruction()
        data class UnpackVariantGenericImmRef(val index: VariantInstantiationHandleIndex) : Instruction()
        data class UnpackVariantGenericMutRef(val index: VariantInstantiationHandleIndex) : Instruction()
        data class VariantSwitch(val index: VariantJumpTableIndex) : Instruction()

        // Deprecated in Sui
        data class ExistsDeprecated(val index: StructDefinitionIndex) : Instruction()
        data class ExistsGenericDeprecated(val index: StructDefInstantiationIndex) : Instruction()
        data class MoveFromDeprecated(val index: StructDefinitionIndex) : Instruction()
        data class MoveFromGenericDeprecated(val index: StructDefInstantiationIndex) : Instruction()
        data class MoveToDeprecated(val index: StructDefinitionIndex) : Instruction()
        data class MoveToGenericDeprecated(val index: StructDefInstantiationIndex) : Instruction()
        data class MutBorrowGlobalDeprecated(val index: StructDefinitionIndex) : Instruction()
        data class MutBorrowGlobalGenericDeprecated(val index: StructDefInstantiationIndex) : Instruction()
        data class ImmBorrowGlobalDeprecated(val index: StructDefinitionIndex) : Instruction()
        data class ImmBorrowGlobalGenericDeprecated(val index: StructDefInstantiationIndex) : Instruction()

        companion object {
            context(MoveModule)
            fun parse(buf: ByteBuffer): Instruction = when (Opcodes.parse(buf)) {
                Opcodes.POP -> Pop
                Opcodes.RET -> Ret
                Opcodes.BR_TRUE -> BrTrue(CodeOffset.parse(buf))
                Opcodes.BR_FALSE -> BrFalse(CodeOffset.parse(buf))
                Opcodes.BRANCH -> Branch(CodeOffset.parse(buf))
                Opcodes.LD_U8 -> LdU8(buf.getUByte())
                Opcodes.LD_U64 -> LdU64(buf.getLEULong())
                Opcodes.LD_U128 -> LdU128(buf.getLEUInt128())
                Opcodes.CAST_U8 -> CastU8
                Opcodes.CAST_U64 -> CastU64
                Opcodes.CAST_U128 -> CastU128
                Opcodes.LD_CONST -> LdConst(parseConstantPoolIndex(buf))
                Opcodes.LD_TRUE -> LdTrue
                Opcodes.LD_FALSE -> LdFalse
                Opcodes.COPY_LOC -> CopyLoc(LocalIndex.parse(buf))
                Opcodes.MOVE_LOC -> MoveLoc(LocalIndex.parse(buf))
                Opcodes.ST_LOC -> StLoc(LocalIndex.parse(buf))
                Opcodes.MUT_BORROW_LOC -> MutBorrowLoc(LocalIndex.parse(buf))
                Opcodes.IMM_BORROW_LOC -> ImmBorrowLoc(LocalIndex.parse(buf))
                Opcodes.MUT_BORROW_FIELD -> MutBorrowField(parseFieldHandleIndex(buf))
                Opcodes.MUT_BORROW_FIELD_GENERIC -> MutBorrowFieldGeneric(parseFieldInstantiationIndex(buf))
                Opcodes.IMM_BORROW_FIELD -> ImmBorrowField(parseFieldHandleIndex(buf))
                Opcodes.IMM_BORROW_FIELD_GENERIC -> ImmBorrowFieldGeneric(parseFieldInstantiationIndex(buf))
                Opcodes.CALL -> Call(parseFunctionHandleIndex(buf))
                Opcodes.CALL_GENERIC -> CallGeneric(parseFunctionInstantiationIndex(buf))
                Opcodes.PACK -> Pack(parseStructDefinitionIndex(buf))
                Opcodes.PACK_GENERIC -> PackGeneric(parseStructDefInstantiationIndex(buf))
                Opcodes.UNPACK -> Unpack(parseStructDefinitionIndex(buf))
                Opcodes.UNPACK_GENERIC -> UnpackGeneric(parseStructDefInstantiationIndex(buf))
                Opcodes.READ_REF -> ReadRef
                Opcodes.WRITE_REF -> WriteRef
                Opcodes.ADD -> Add
                Opcodes.SUB -> Sub
                Opcodes.MUL -> Mul
                Opcodes.MOD -> Mod
                Opcodes.DIV -> Div
                Opcodes.BIT_OR -> BitOr
                Opcodes.BIT_AND -> BitAnd
                Opcodes.XOR -> Xor
                Opcodes.SHL -> Shl
                Opcodes.SHR -> Shr
                Opcodes.OR -> Or
                Opcodes.AND -> And
                Opcodes.NOT -> Not
                Opcodes.EQ -> Eq
                Opcodes.NEQ -> Neq
                Opcodes.LT -> Lt
                Opcodes.GT -> Gt
                Opcodes.LE -> Le
                Opcodes.GE -> Ge
                Opcodes.ABORT -> Abort
                Opcodes.NOP -> Nop
                Opcodes.FREEZE_REF -> FreezeRef
                Opcodes.VEC_PACK -> VecPack(parseSignatureIndex(buf), buf.getLEULong())
                Opcodes.VEC_LEN -> VecLen(parseSignatureIndex(buf))
                Opcodes.VEC_IMM_BORROW -> VecImmBorrow(parseSignatureIndex(buf))
                Opcodes.VEC_MUT_BORROW -> VecMutBorrow(parseSignatureIndex(buf))
                Opcodes.VEC_PUSH_BACK -> VecPushBack(parseSignatureIndex(buf))
                Opcodes.VEC_POP_BACK -> VecPopBack(parseSignatureIndex(buf))
                Opcodes.VEC_UNPACK -> VecUnpack(parseSignatureIndex(buf), buf.getLEULong())
                Opcodes.VEC_SWAP -> VecSwap(parseSignatureIndex(buf))
                Opcodes.LD_U16 -> LdU16(buf.getLEUShort())
                Opcodes.LD_U32 -> LdU32(buf.getLEUInt())
                Opcodes.LD_U256 -> LdU256(buf.getLEUInt256())
                Opcodes.CAST_U16 -> CastU16
                Opcodes.CAST_U32 -> CastU32
                Opcodes.CAST_U256 -> CastU256
                Opcodes.PACK_VARIANT -> PackVariant(parseVariantHandleIndex(buf))
                Opcodes.PACK_VARIANT_GENERIC -> PackVariantGeneric(parseVariantInstantiationHandleIndex(buf))
                Opcodes.UNPACK_VARIANT -> UnpackVariant(parseVariantHandleIndex(buf))
                Opcodes.UNPACK_VARIANT_IMM_REF -> UnpackVariantImmRef(parseVariantHandleIndex(buf))
                Opcodes.UNPACK_VARIANT_MUT_REF -> UnpackVariantMutRef(parseVariantHandleIndex(buf))
                Opcodes.UNPACK_VARIANT_GENERIC -> UnpackVariantGeneric(parseVariantInstantiationHandleIndex(buf))
                Opcodes.UNPACK_VARIANT_GENERIC_IMM_REF -> UnpackVariantGenericImmRef(parseVariantInstantiationHandleIndex(buf))
                Opcodes.UNPACK_VARIANT_GENERIC_MUT_REF -> UnpackVariantGenericMutRef(parseVariantInstantiationHandleIndex(buf))
                Opcodes.VARIANT_SWITCH -> VariantSwitch(parseVariantJumpTableIndex(buf))
                Opcodes.EXISTS_DEPRECATED -> ExistsDeprecated(parseStructDefinitionIndex(buf))
                Opcodes.EXISTS_GENERIC_DEPRECATED -> ExistsGenericDeprecated(parseStructDefInstantiationIndex(buf))
                Opcodes.MUT_BORROW_GLOBAL_DEPRECATED -> MutBorrowGlobalDeprecated(parseStructDefinitionIndex(buf))
                Opcodes.MUT_BORROW_GLOBAL_GENERIC_DEPRECATED -> MutBorrowGlobalGenericDeprecated(parseStructDefInstantiationIndex(buf))
                Opcodes.IMM_BORROW_GLOBAL_DEPRECATED -> ImmBorrowGlobalDeprecated(parseStructDefinitionIndex(buf))
                Opcodes.IMM_BORROW_GLOBAL_GENERIC_DEPRECATED -> ImmBorrowGlobalGenericDeprecated(parseStructDefInstantiationIndex(buf))
                Opcodes.MOVE_FROM_DEPRECATED -> MoveFromDeprecated(parseStructDefinitionIndex(buf))
                Opcodes.MOVE_FROM_GENERIC_DEPRECATED -> MoveFromGenericDeprecated(parseStructDefInstantiationIndex(buf))
                Opcodes.MOVE_TO_DEPRECATED -> MoveToDeprecated(parseStructDefinitionIndex(buf))
                Opcodes.MOVE_TO_GENERIC_DEPRECATED -> MoveToGenericDeprecated(parseStructDefInstantiationIndex(buf))
            }
        }
    }

    class VariantJumpTable(
        val headEnum: EnumDefinitionIndex,
        private val jumpTable: List<CodeOffset>
    ) {
        companion object {
            context(MoveModule)
            fun parse(buf: ByteBuffer): VariantJumpTable {
                val headEnum = parseEnumDefinitionIndex(buf)
                val count = buf.getLeb128UInt().toInt()
                val jumpTableType = buf.getUByte().toInt()
                val jumpTable: List<CodeOffset> = when (jumpTableType) {
                    0x1 -> buildList { repeat(count) { add(CodeOffset.parse(buf)) } }
                    else -> error("Invalid variant jump table type $jumpTableType")
                }
                return VariantJumpTable(headEnum, jumpTable)
            }
        }
    }

    class FieldHandle(
        private val ownerIndex: StructDefinitionIndex,
        val fieldIndex: Int
    ) {
        val owner get() = ownerIndex.deref()
        val definition get() = owner.fields!![fieldIndex]

        companion object {
            context(MoveModule)
            fun parseTable(buf: ByteBuffer) = buf.parseAll {
                FieldHandle(
                    ownerIndex = parseStructDefinitionIndex(buf),
                    fieldIndex = buf.getLeb128UInt().toInt()
                )
            }
        }
    }

    class FieldInstantiation(
        val handle: FieldHandleIndex,
        val typeParameters: SignatureIndex
    ) {
        companion object {
            context(MoveModule)
            fun parseTable(buf: ByteBuffer) = buf.parseAll {
                FieldInstantiation(
                    handle = parseFieldHandleIndex(buf),
                    typeParameters = parseSignatureIndex(buf)
                )
            }
        }
    }

    class Metadata(
        val key: List<UByte>,
        val value: List<UByte>
    ) {
        companion object {
            fun parseTable(buf: ByteBuffer) = buf.parseAll {
                Metadata(
                    key = buf.parseList { buf.getUByte() },
                    value = buf.parseList { buf.getUByte() }
                )
            }
        }
    }

    class EnumDefinition(
        val enumHandle: DatatypeHandleIndex,
        private val variants: List<VariantDefinition>
    ) {
        companion object {
            context(MoveModule)
            fun parseTable(buf: ByteBuffer) = buf.parseAll {
                EnumDefinition(
                    enumHandle = parseDatatypeHandleIndex(buf),
                    variants = buf.parseList { VariantDefinition.parse(buf) }
                )
            }
        }
    }

    class VariantDefinition(
        val variantName: IdentifierIndex,
        private val fields: List<FieldDefinition>
    ) {
        companion object {
            context(MoveModule)
            fun parse(buf: ByteBuffer) = VariantDefinition(
                variantName = parseIdentifierIndex(buf),
                fields = buf.parseList { FieldDefinition.parse(buf) }
            )
        }
    }

    class EnumDefInstantiation(
        val def: EnumDefinitionIndex,
        val typeParameters: SignatureIndex
    ) {
        companion object {
            context(MoveModule)
            fun parseTable(buf: ByteBuffer) = buf.parseAll {
                EnumDefInstantiation(
                    def = parseEnumDefinitionIndex(buf),
                    typeParameters = parseSignatureIndex(buf)
                )
            }
        }
    }

    class VariantHandle(
        val enumDef: EnumDefinitionIndex,
        val variant: VariantTag
    ) {
        companion object {
            context(MoveModule)
            fun parseTable(buf: ByteBuffer) = buf.parseAll {
                VariantHandle(
                    enumDef = parseEnumDefinitionIndex(buf),
                    variant = parseVariantTag(buf)
                )
            }
        }
    }

    class VariantInstantiationHandle(
        val enumDef: EnumDefInstantiationIndex,
        val variant: VariantTag
    ) {
        companion object {
            context(MoveModule)
            fun parseTable(buf: ByteBuffer) = buf.parseAll {
                VariantInstantiationHandle(
                    enumDef = parseEnumDefInstantiationIndex(buf),
                    variant = parseVariantTag(buf)
                )
            }
        }
    }

    private val functionDefinitionsById = functionDefinitions.orEmpty().associateBy { it.function.id }

    fun definition(handle: FunctionHandle): FunctionDefinition {
        check(handle.name.module == this.moduleName) {
            "Function handle $handle does not belong to module $moduleName"
        }
        return functionDefinitionsById[handle.id]
            ?: error("Function definition not found for ${handle.id} in module $moduleName")
    }

    private val functionDefinitionsByName = functionDefinitions.orEmpty().associateBy { it.function.name }
    fun maybeDefinition(name: MoveFunctionName): FunctionDefinition? {
        check(name.module == this.moduleName) {
            "Function name $name does not belong to module $moduleName"
        }
        return functionDefinitionsByName[name]
    }

    val publicFunctionDefinitions get() = functionDefinitions.orEmpty().filter { it.visibility == Visibility.Public }

    private val structDefinitionsById = structDefinitions.orEmpty().associateBy { it.structHandle.id }

    fun definition(handle: DatatypeHandle): StructDefinition {
        check(handle.definingModule == this.moduleName) {
            "Datatype handle $handle does not belong to module $moduleName"
        }
        return structDefinitionsById[handle.id]
            ?: error("Struct definition not found for handle $handle")
    }

    private val structDefinitionsByName = structDefinitions.orEmpty().associateBy { it.name }

    fun maybeDefinition(name: MoveStructName): StructDefinition? {
        check(name.module == this.moduleName) {
            "Struct name $name does not belong to module $moduleName"
        }
        return structDefinitionsByName[name]
    }
}
