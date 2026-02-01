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

import analysis.*
import analysis.CommandWithRequiredDecls.Companion.mergeMany
import datastructures.stdcollections.*
import log.*
import move.analysis.*
import move.analysis.ReferenceAnalysis.*
import move.MoveModule.*
import tac.*
import tac.generation.*
import utils.*
import vc.data.*
import vc.data.tacexprutil.*

private val loggerSetupHelpers = Logger(LoggerTypes.SETUP_HELPERS)

/**
    Converts a MoveTACProgram to a CoreTACProgram by expanding all TACCmd.Move commands into Core TAC implementations.
 */
class MoveTACSimplifier(
    val scene: MoveScene,
    val moveCode: MoveTACProgram,
    val trapMode: TrapMode
) : MemoryLayout.VarInitializer {

    fun transform(): CoreTACProgram {
        val transformedBlocks = moveCode.graph.blocks.associate { block ->
            block.id to mergeMany(
                block.commands.map { transformCommand(it) }
            )
        }

        val symbolTable = moveCode.symbolTable.mergeDecls(
            transformedBlocks.values.map { it.varDecls }.unionAll()
        )

        val code = transformedBlocks.mapValues { (blockId, cmdsWithDecls) ->
            if (blockId == moveCode.entryBlock) {
                havocInitializedVars.map { TACCmd.Simple.AssigningCmd.AssignHavocCmd(it) } + cmdsWithDecls.cmds
            } else {
                cmdsWithDecls.cmds
            }
        }

        return CoreTACProgram(
            code,
            moveCode.blockgraph,
            moveCode.name,
            symbolTable,
            InstrumentationTAC(UfAxioms.empty()),
            IProcedural.empty(),
            entryBlock = moveCode.entryBlock,
            check = true
        ).let {
            ITESummary.materialize(it)
        }
    }

    private val havocInitializedVars = mutableSetOf<TACSymbol.Var>()
    override fun TACSymbol.Var.ensureHavocInit() = this.also { havocInitializedVars.add(it) }

    private class CommandContext(val origCmd: MoveTACProgram.LCmd)

    private fun transformCommand(lcmd: MoveTACProgram.LCmd): SimpleCmdsWithDecls {
        with(CommandContext(lcmd)) {
            return when (val cmd = lcmd.cmd) {
                is TACCmd.Simple -> when (cmd) {
                    is TACCmd.Simple.AssigningCmd.AssignExpCmd -> transformAssignExpCmd(cmd)
                    is TACCmd.Simple.AssigningCmd.AssignHavocCmd -> transformAssignHavocCmd(cmd)
                    is TACCmd.Simple.AnnotationCmd -> when (val annot = cmd.annot.v) {
                        is MoveCallTrace.Assume -> annot.resolveMessage()
                        is MoveCallTrace.Assert -> annot.resolveMessage()
                        else -> cmd.withDecls()
                    }
                    else -> cmd.withDecls()
                }
                is TACCmd.Move -> when (cmd) {
                    is TACCmd.Move.BorrowLocCmd -> transformBorrowLocCmd(cmd)
                    is TACCmd.Move.BorrowFieldCmd -> transformBorrowFieldCmd(cmd)
                    is TACCmd.Move.ReadRefCmd -> transformReadRefCmd(cmd)
                    is TACCmd.Move.WriteRefCmd -> transformWriteRefCmd(cmd)
                    is TACCmd.Move.PackStructCmd -> transformPackStructCmd(cmd)
                    is TACCmd.Move.UnpackStructCmd -> transformUnpackStructCmd(cmd)
                    is TACCmd.Move.VecPackCmd -> transformVecPackCmd(cmd)
                    is TACCmd.Move.VecUnpackCmd -> transformVecUnpackCmd(cmd)
                    is TACCmd.Move.VecLenCmd -> transformVecLenCmd(cmd)
                    is TACCmd.Move.VecBorrowCmd -> transformVecBorrowCmd(cmd)
                    is TACCmd.Move.VecPushBackCmd -> transformVecPushBackCmd(cmd)
                    is TACCmd.Move.VecPopBackCmd -> transformVecPopBackCmd(cmd)
                    is TACCmd.Move.PackVariantCmd -> transformPackVariantCmd(cmd)
                    is TACCmd.Move.UnpackVariantCmd -> transformUnpackVariantCmd(cmd)
                    is TACCmd.Move.UnpackVariantRefCmd -> transformUnpackVariantRefCmd(cmd)
                    is TACCmd.Move.VariantIndexCmd -> transformVariantIndexCmd(cmd)
                    is TACCmd.Move.GhostArrayBorrowCmd -> transformGhostArrayBorrowCmd(cmd)
                    is TACCmd.Move.HashCmd -> transformHashCmd(cmd)
                    is TACCmd.Move.EqCmd -> transformEqCmd(cmd)
                }
                else -> error("Bad command in MoveTACProgram: $lcmd")
            }
        }
    }

    context(CommandContext)
    private fun <T> T.resolveMessage(): SimpleCmdsWithDecls
        where T : MoveCallTrace.WithMessageFromVar, T : SnippetCmd.MoveSnippetCmd {
        return this.resolveMessage(resolveString(messageVar)).toAnnotation().withDecls()
    }

    context(CommandContext)
    private fun resolveString(sym: TACSymbol.Var?): String? {
        if (sym == null) { return null }

        // Find the definition of the string variable
        val def = moveCode.graph.cache.def
        val defPtr = def.nontrivialDef(sym, origCmd.ptr).singleOrNull() ?: return null

        // Make sure it's a VecPackCmd of u8 elements
        val cmd = moveCode.graph.toCommand(defPtr)
        val vecPackCmd = cmd as? TACCmd.Move.VecPackCmd ?: return null
        val vecType = cmd.dst.getType<MoveType.Vector>()
        if (vecType.elemType != MoveType.U8) { return null }

        val bytes = vecPackCmd.srcs.map { byte ->
            val constValue = def.mustBeConstantAt(defPtr, byte) ?: return null
            constValue.toInt().toByte()
        }.toByteArray()

        return String(bytes, Charsets.UTF_8)
    }

    context(CommandContext)
    private fun transformAssignExpCmd(cmd: TACCmd.Simple.AssigningCmd.AssignExpCmd): SimpleCmdsWithDecls {
        return when (cmd.lhs.tag) {
            is MoveTag -> {
                when (cmd.rhs) {
                    is TACExpr.Sym.Var -> {
                        MemoryLayout.fromVar(cmd.lhs).assign(MemoryLayout.fromVar(cmd.rhs.s), cmd.meta)
                    }
                    is TACExpr.TernaryExp.Ite -> {
                        val t = cmd.rhs.t as? TACExpr.Sym.Var
                        val e = cmd.rhs.e as? TACExpr.Sym.Var
                        check(t != null && e != null) { "Folded ITE expression in $origCmd" }
                        MemoryLayout.fromVar(cmd.lhs).assignIte(
                            cmd.rhs.i,
                            MemoryLayout.fromVar(t.s),
                            MemoryLayout.fromVar(e.s),
                            cmd.meta
                        )
                    }
                    else -> error("Unexpected Move-typed expression in $origCmd")
                }
            }
            else -> cmd.withDecls()
        }
    }


    context(CommandContext)
    private fun transformAssignHavocCmd(cmd: TACCmd.Simple.AssigningCmd.AssignHavocCmd): SimpleCmdsWithDecls {
        return when (cmd.lhs.tag) {
            is MoveTag -> MemoryLayout.Value.fromVar(cmd.lhs).havoc(cmd.meta)
            else -> cmd.withDecls()
        }
    }

    context(CommandContext)
    private fun transformBorrowLocCmd(cmd: TACCmd.Move.BorrowLocCmd): SimpleCmdsWithDecls {
        val refLayout = MemoryLayout.Reference.fromVar(cmd.ref)
        val targetLayout = moveCode.graph.cache.references.refTargetsAfter(origCmd.ptr, cmd.ref).let { targets ->
            targets.singleOrNull()?.layout() ?: error("Expected single target for borrow loc, got $targets")
        }
        return mergeMany(
            assign(refLayout.layoutId, cmd.meta) { targetLayout.id },
            assign(refLayout.offset, cmd.meta) { 0.asTACExpr }
        )
    }

    context(CommandContext)
    private fun transformBorrowFieldCmd(cmd: TACCmd.Move.BorrowFieldCmd): SimpleCmdsWithDecls {
        val structType = cmd.srcRef.getTargetType<MoveType.Struct>()
        val dstRefLayout = MemoryLayout.Reference.fromVar(cmd.dstRef)
        return deref(cmd.srcRef, cmd.meta) { loc ->
            val fieldLoc = loc.fieldLoc(structType, cmd.fieldIndex)
            mergeMany(
                assign(dstRefLayout.layoutId, cmd.meta) { fieldLoc.layout.id },
                assign(dstRefLayout.offset, cmd.meta) { fieldLoc.offset },
            )
        }
    }

    context(CommandContext)
    private fun transformReadRefCmd(cmd: TACCmd.Move.ReadRefCmd): SimpleCmdsWithDecls {
        val type = cmd.ref.getTargetType<MoveType.Value>()
        val dstLoc = cmd.dst.location()
        return deref(cmd.ref, cmd.meta) { srcLoc -> dstLoc.assign(srcLoc, type, cmd.meta) }
    }

    context(CommandContext)
    private fun transformWriteRefCmd(cmd: TACCmd.Move.WriteRefCmd): SimpleCmdsWithDecls {
        val type = cmd.ref.getTargetType<MoveType.Value>()
        val srcLoc = cmd.src.location()
        return deref(cmd.ref, cmd.meta) { dstLoc ->
            mergeMany(
                dstLoc.assign(srcLoc, type, cmd.meta),
                dstLoc.invalidateVectorDigests(cmd.meta)
            )
        }
    }

    context(CommandContext)
    private fun transformPackStructCmd(cmd: TACCmd.Move.PackStructCmd): SimpleCmdsWithDecls {
        val type = cmd.dst.getType<MoveType.Struct>()
        val dstLoc = cmd.dst.location()
        return mergeMany(
            listOf(
                dstLoc.layout.havoc(cmd.meta)
            ) + type.fields.orEmpty().mapIndexed { fieldIndex, field ->
                val srcFieldLoc = cmd.srcs[fieldIndex].location()
                val dstFieldLoc = dstLoc.fieldLoc(type, fieldIndex)
                dstFieldLoc.assign(srcFieldLoc, field.type, cmd.meta)
            }
        )
    }

    context(CommandContext)
    private fun transformUnpackStructCmd(cmd: TACCmd.Move.UnpackStructCmd): SimpleCmdsWithDecls {
        val type = cmd.src.getType<MoveType.Struct>()
        val srcLoc = cmd.src.location()
        return mergeMany(
            type.fields.orEmpty().mapIndexed { fieldIndex, field ->
                val srcFieldLoc = srcLoc.fieldLoc(type, fieldIndex)
                val dstFieldLoc = cmd.dsts[fieldIndex].location()
                dstFieldLoc.assign(srcFieldLoc, field.type, cmd.meta)
            }
        )
    }

    context(CommandContext)
    private fun transformVecPackCmd(cmd: TACCmd.Move.VecPackCmd): SimpleCmdsWithDecls {
        val vecType = cmd.dst.getType<MoveType.Vector>()
        val vecLoc = cmd.dst.location()
        val digest = TACKeyword.TMP(Tag.Bit256, "digest")
        return mergeMany(
            listOf(
                // Compute the vector's digest
                computeVectorDigest(digest, cmd.srcs, cmd.meta),
                // Initialize the vector location
                vecLoc.layout.havoc(cmd.meta),
            ) + cmd.srcs.mapIndexed { elemIndex, src ->
                // Store each element
                val srcElemLoc = src.location()
                val dstElemLoc = vecLoc.elementLoc(vecType, elemIndex.asTACExpr)
                dstElemLoc.assign(srcElemLoc, vecType.elemType, cmd.meta)
            } + listOf(
                // Store the length and digest last, so that they are the most recent updates to the map.  This helps
                // solver performance.
                vecLoc.setVecLen(cmd.srcs.size.asTACExpr, cmd.meta),
                vecLoc.setVecDigest(digest.asSym(), cmd.meta)
            )
        )
    }

    context(CommandContext)
    private fun computeVectorDigest(
        digest: TACSymbol.Var,
        elems: List<TACSymbol.Var>,
        meta: MetaMap
    ): SimpleCmdsWithDecls {
        val elemHashes = elems.map { src -> TACKeyword.TMP(Tag.Bit256, "${src.namePrefix}!hash") }
        return mergeMany(
            elems.zip(elemHashes).map { (src, elemHash) ->
                // Hash each non-Bits element
                when (src.tag) {
                    is Tag.Bits -> {
                        assign(elemHash, meta) { src.asSym() }
                    }
                    else -> {
                        hash(
                            loc = src,
                            dst = elemHash,
                            hashFamily = HashFamily.MoveVectorElemHash,
                            meta = meta
                        )
                    }
                }
            } + listOf(
                // Hash the element hashes together
                assign(digest, meta) {
                    SimpleHash(
                        length = elemHashes.size.asTACExpr,
                        args = elemHashes.map { it.asSym() },
                        hashFamily = HashFamily.MoveVectorDigest
                    )
                }
            )
        )
    }

    context(CommandContext)
    private fun transformVecUnpackCmd(cmd: TACCmd.Move.VecUnpackCmd): SimpleCmdsWithDecls {
        val vecType = cmd.src.getType<MoveType.Vector>()
        val vecLoc = cmd.src.location()
        val elemType = vecType.elemType
        val elemCount = cmd.dsts.size
        return mergeMany(
            listOf(
                Trap.assert("Vector length mismatch", trapMode, cmd.meta) {
                    vecLoc.vecLen() eq elemCount.asTACExpr
                }
            ) + cmd.dsts.mapIndexed { elemIndex, dst ->
                val dstElemLoc = dst.location()
                val srcElemLoc = vecLoc.elementLoc(vecType, elemIndex.asTACExpr)
                dstElemLoc.assign(srcElemLoc, elemType, cmd.meta)
            }
        )
    }

    context(CommandContext)
    private fun transformVecLenCmd(cmd: TACCmd.Move.VecLenCmd): SimpleCmdsWithDecls {
        return deref(cmd.ref, cmd.meta) { loc ->
            assign(cmd.dst, cmd.meta) { loc.vecLen() }
        }
    }

    context(CommandContext)
    private fun transformVecBorrowCmd(cmd: TACCmd.Move.VecBorrowCmd): SimpleCmdsWithDecls {
        val type = cmd.srcRef.getTargetType<MoveType.Vector>()
        val dstRefLayout = MemoryLayout.Reference.fromVar(cmd.dstRef)
        return deref(cmd.srcRef, cmd.meta) { loc ->
            val elemLoc = loc.elementLoc(type, cmd.index.asSym())
            mergeMany(
                listOfNotNull(
                    runIf(cmd.doBoundsCheck) {
                        Trap.assert("Index out of bounds", trapMode, cmd.meta) {
                            cmd.index lt loc.vecLen()
                        }
                    },
                    assign(dstRefLayout.layoutId, cmd.meta) { elemLoc.layout.id },
                    assign(dstRefLayout.offset, cmd.meta) { elemLoc.offset }
                )
            )
        }
    }

    context(CommandContext)
    private fun transformVecPushBackCmd(cmd: TACCmd.Move.VecPushBackCmd): SimpleCmdsWithDecls {
        val vecType = cmd.ref.getTargetType<MoveType.Vector>()
        val elemType = vecType.elemType
        val srcElemLoc = cmd.src.location()
        val oldVecDigest = TACKeyword.TMP(Tag.Bit256, "oldVecDigest")
        val newElemHash = TACKeyword.TMP(Tag.Bit256, "newElemHash")
        return deref(cmd.ref, cmd.meta) { vecLoc ->
            vecLoc.vecLen().letVar("vecLen", Tag.Int, cmd.meta) { len ->
                TXF {
                    safeMathNarrowAssuming(len intAdd 1, Tag.Bit256, Tag.Bit64.maxUnsigned)
                }.letVar("newLen", Tag.Bit256, cmd.meta) { newLen ->
                    val dstElemLoc = vecLoc.elementLoc(vecType, len)
                    mergeMany(
                        assign(oldVecDigest, cmd.meta) { vecLoc.vecDigest() },
                        vecLoc.setVecLen(newLen, cmd.meta),
                        dstElemLoc.assign(srcElemLoc, elemType, cmd.meta),
                        // havoc the digests for all nested vectors, and add an assume so
                        // that a.push_back(b) == a.push_back(b)
                        dstElemLoc.invalidateVectorDigests(cmd.meta),
                        hash(
                            dst = newElemHash,
                            loc = cmd.src,
                            hashFamily = HashFamily.MoveVectorElemHash,
                            meta = cmd.meta
                        ),
                        assume(cmd.meta) {
                            vecLoc.vecDigest() eq
                            select(TACKeyword.MOVE_VECTOR_PUSH_BACK_DIGEST.toVar().asSym(), oldVecDigest.asSym(), newElemHash.asSym())
                        }.merge(TACKeyword.MOVE_VECTOR_PUSH_BACK_DIGEST.toVar())
                    )
                }
            }
        }
    }

    context(CommandContext)
    private fun transformVecPopBackCmd(cmd: TACCmd.Move.VecPopBackCmd): SimpleCmdsWithDecls {
        val vecType = cmd.ref.getTargetType<MoveType.Vector>()
        val elemType = vecType.elemType
        val dstElemLoc = cmd.dst.location()
        val oldVecDigest = TACKeyword.TMP(Tag.Bit256, "oldVecDigest")
        return deref(cmd.ref, cmd.meta) { vecLoc ->
            vecLoc.vecLen().letVar("oldLen", Tag.Bit256, meta = cmd.meta) { oldLen ->
                TXF { oldLen sub 1 }.letVar("newLen", Tag.Bit256, cmd.meta) { newLen ->
                    val srcElemLoc = vecLoc.elementLoc(vecType, newLen)
                    mergeMany(
                        Trap.assert("Empty vector", trapMode, cmd.meta) { oldLen gt 0.asTACExpr },
                        assign(oldVecDigest, cmd.meta) { vecLoc.vecDigest() },
                        vecLoc.setVecLen(newLen, cmd.meta),
                        dstElemLoc.assign(srcElemLoc, elemType, cmd.meta),
                        // havoc the digests for all nested vectors, and add an assume so
                        // that a.pop_back() == a.pop_back()
                        dstElemLoc.invalidateVectorDigests(cmd.meta),
                        assume(cmd.meta) {
                            vecLoc.vecDigest() eq
                            select(TACKeyword.MOVE_VECTOR_POP_BACK_DIGEST.toVar().asSym(), oldVecDigest.asSym())
                        }.merge(TACKeyword.MOVE_VECTOR_POP_BACK_DIGEST.toVar())
                    )
                }
            }
        }
    }

    context(CommandContext)
    private fun transformPackVariantCmd(cmd: TACCmd.Move.PackVariantCmd): SimpleCmdsWithDecls {
        val enumType = cmd.dst.getType<MoveType.Enum>()
        val dstEnumLoc = cmd.dst.location()
        val variant = enumType.variants[cmd.variant]
        val fields = variant.fields!!
        check(fields.size == cmd.srcs.size) { "Expected ${fields.size} fields, got ${cmd.srcs.size}" }
        return mergeMany(
            listOf(
                dstEnumLoc.layout.havoc(cmd.meta)
            ) + fields.mapIndexed { fieldIndex, field ->
                val srcFieldLoc = cmd.srcs[fieldIndex].location()
                val dstFieldLoc = dstEnumLoc.fieldLoc(variant, fieldIndex)
                dstFieldLoc.assign(srcFieldLoc, field.type, cmd.meta)
            } + listOf(
                dstEnumLoc.setEnumVariant(cmd.variant.asTACExpr, cmd.meta)
            )
        )
    }

    context(CommandContext)
    private fun transformUnpackVariantCmd(cmd: TACCmd.Move.UnpackVariantCmd): SimpleCmdsWithDecls {
        val enumType = cmd.src.getType<MoveType.Enum>()
        val srcEnumLoc = cmd.src.location()
        val variant = enumType.variants[cmd.variant]
        val fields = variant.fields!!
        check(fields.size == cmd.dsts.size) { "Expected ${fields.size} fields, got ${cmd.dsts.size}" }
        return mergeMany(
            listOfNotNull(
                runIf(cmd.doVariantCheck) {
                    Trap.assert("Variant tag mismatch", trapMode, cmd.meta) {
                        srcEnumLoc.enumVariant(enumType) eq cmd.variant.asTACExpr
                    }
                }
            ) + fields.mapIndexed { fieldIndex, field ->
                val srcFieldLoc = srcEnumLoc.fieldLoc(variant, fieldIndex)
                val dstFieldLoc = cmd.dsts[fieldIndex].location()
                dstFieldLoc.assign(srcFieldLoc, field.type, cmd.meta)
            }
        )
    }

    context(CommandContext)
    private fun transformUnpackVariantRefCmd(cmd: TACCmd.Move.UnpackVariantRefCmd): SimpleCmdsWithDecls {
        val enumType = cmd.srcRef.getTargetType<MoveType.Enum>()
        val variant = enumType.variants[cmd.variant]
        val fields = variant.fields!!
        check(fields.size == cmd.dsts.size) { "Expected ${fields.size} fields, got ${cmd.dsts.size}" }
        val dstRefLayouts = cmd.dsts.map { MemoryLayout.Reference.fromVar(it) }
        return deref(cmd.srcRef, cmd.meta) { srcEnumLoc ->
            mergeMany(
                listOfNotNull(
                    runIf(cmd.doVariantCheck) {
                        Trap.assert("Variant tag mismatch", trapMode, cmd.meta) {
                            srcEnumLoc.enumVariant(enumType) eq cmd.variant.asTACExpr
                        }
                    }
                ) + fields.mapIndexed { fieldIndex, _ ->
                    val srcFieldLoc = srcEnumLoc.fieldLoc(variant, fieldIndex)
                    mergeMany(
                        assign(dstRefLayouts[fieldIndex].layoutId, cmd.meta) { srcFieldLoc.layout.id },
                        assign(dstRefLayouts[fieldIndex].offset, cmd.meta) { srcFieldLoc.offset }
                    )
                }
            )
        }
    }

    context(CommandContext)
    private fun transformVariantIndexCmd(cmd: TACCmd.Move.VariantIndexCmd): SimpleCmdsWithDecls {
        val enumType = cmd.ref.getTargetType<MoveType.Enum>()
        val maxVariant = enumType.variants.size - 1
        check(maxVariant >= 0) { "Enum ${enumType.name} has no variants" }
        return deref(cmd.ref, cmd.meta) { enumLoc ->
            assign(cmd.index, cmd.meta) {
                safeMathNarrowAssuming(
                    enumLoc.enumVariant(enumType),
                    Tag.Bit256,
                    maxVariant.toBigInteger()
                )
            }
        }
    }

    context(CommandContext)
    private fun transformGhostArrayBorrowCmd(cmd: TACCmd.Move.GhostArrayBorrowCmd): SimpleCmdsWithDecls {
        val arrayType = cmd.arrayRef.getTargetType<MoveType.GhostArray>()
        val elemType = cmd.dstRef.getTargetType<MoveType.Value>()
        check(elemType in arrayType.elemTypes) {
            "Element type $elemType not in ghost array element types ${arrayType.elemTypes} in $origCmd"
        }
        val dstRefLayout = MemoryLayout.Reference.fromVar(cmd.dstRef)
        return deref(cmd.arrayRef, cmd.meta) { arrayLoc ->
            val elemLoc = arrayLoc.elementLoc(arrayType, cmd.index.asSym(), elemType)
            mergeMany(
                assign(dstRefLayout.layoutId, cmd.meta) { elemLoc.layout.id },
                assign(dstRefLayout.offset, cmd.meta) { elemLoc.offset }
            )
        }
    }

    /**
        [TACCmd.Move.HashCmd] computes the hash of the value at the location `cmd.loc`, storing the result in `cmd.dst`.
     */
    context(CommandContext)
    private fun transformHashCmd(cmd: TACCmd.Move.HashCmd): SimpleCmdsWithDecls {
        return hash(cmd.dst, cmd.loc, cmd.hashFamily, cmd.meta)
    }

    context(CommandContext)
    private fun hash(
        dst: TACSymbol.Var,
        loc: TACSymbol.Var,
        hashFamily: HashFamily,
        meta: MetaMap = MetaMap()
    ): SimpleCmdsWithDecls {
        val tag = loc.tag
        if (tag !is MoveTag) {
            return assign(dst, meta) {
                SimpleHash(
                    length = 1.asTACExpr,
                    args = listOf(loc.asSym()),
                    hashFamily = hashFamily
                )
            }
        }

        fun hashExprs(loc: MemoryLocation, type: MoveType.Value): List<TACExpr> {
            return when (type) {
                is MoveType.Simple -> when (type) {
                    is MoveType.Bits, is MoveType.Nondet, is MoveType.Function -> {
                        listOf(loc.simpleValue(type))
                    }
                    is MoveType.Bool -> {
                        listOf(
                            TXF {
                                ite(loc.simpleValue(type), 1.asTACExpr, 0.asTACExpr)
                            }
                        )
                    }
                    is MoveType.MathInt -> {
                        // TODO CERT-9258 TACExpr.SimpleHash does not support Tag.Int arguments
                        loggerSetupHelpers.warn { "Havocing hash of MathInt in $origCmd" }
                        listOf(TACExpr.Unconstrained(Tag.Bit256))
                    }
                }
                is MoveType.Vector -> listOf(
                    loc.vecLen(),
                    loc.vecDigest()
                )
                is MoveType.Struct -> {
                    val fields = type.fields ?: run {
                        loggerSetupHelpers.warn { "Havocing hash of native struct type $type in $origCmd" }
                        return listOf(TACExpr.Unconstrained(Tag.Bit256))
                    }
                    fields.flatMapIndexed { fieldIndex, field ->
                        hashExprs(loc.fieldLoc(type, fieldIndex), field.type)
                    }
                }
                is MoveType.Enum -> {
                    // To hash an enum, we would need to generate a different list of expressions for each variant.
                    // That doesn't fit into our current hashing scheme, so for now let's just havoc the hash.
                    loggerSetupHelpers.warn { "Havocing hash of enum type in $origCmd" }
                    listOf(TACExpr.Unconstrained(Tag.Bit256))
                }
                is MoveType.GhostArray -> {
                    loggerSetupHelpers.warn { "Havocing hash of ghost array type in $origCmd" }
                    listOf(TACExpr.Unconstrained(Tag.Bit256))
                }
            }
        }

        val exprs = hashExprs(loc.location(), loc.getType<MoveType.Value>())

        return assign(dst, meta) {
            SimpleHash(
                length = exprs.size.asTACExpr,
                args = exprs,
                hashFamily = hashFamily
            )
        }
    }

    /**
        [TACCmd.Move.EqCmd] checks if the values at `cmd.a` and `cmd.b` are equal, storing the result in `cmd.dst`.

        We support precise equality checks for integers, booleans, and enums/structs that do not contain vectors or
        ghost arrays. For vectors, we compare their digest fields, which are more or less precise depending on how the
        vector was constructed.  For ghost arrays, we simply havoc the result.
     */
    context(CommandContext)
    private fun transformEqCmd(cmd: TACCmd.Move.EqCmd): SimpleCmdsWithDecls {
        check(cmd.a.tag == cmd.b.tag) {
            "Cannot compare different types: ${cmd.a.tag} and ${cmd.b.tag}"
        }
        return when (cmd.a.tag) {
            is MoveTag -> cmd.a.getType<MoveType.Value>().compare(cmd.dst, cmd.a.location(), cmd.b.location(), cmd.meta)
            else -> assign(cmd.dst, cmd.meta) { cmd.a.asSym() eq cmd.b.asSym() }
        }
    }

    context(CommandContext)
    private fun MoveType.Composite.compareFields(
        result: TACSymbol.Var,
        aLoc: MemoryLocation,
        bLoc: MemoryLocation,
        meta: MetaMap
    ): SimpleCmdsWithDecls {
        val fields = this.fields ?: run {
            loggerSetupHelpers.warn { "Havocing equality of native struct type $this in $origCmd" }
            return assignHavoc(result, meta)
        }
        val fieldResults = fields.map { TACKeyword.TMP(Tag.Bool, "${it.name}!eq") }
        return mergeMany(
            fields.mapIndexed { fieldIndex, field ->
                field.type.compare(
                    fieldResults[fieldIndex],
                    aLoc.fieldLoc(this, fieldIndex),
                    bLoc.fieldLoc(this, fieldIndex),
                    meta
                )
            } + assign(result, meta) {
                LAnd(fieldResults.map { it.asSym() } )
            }
        )
    }

    context(CommandContext)
    private fun MoveType.Value.compare(
        result: TACSymbol.Var,
        aLoc: MemoryLocation,
        bLoc: MemoryLocation,
        meta: MetaMap
    ): SimpleCmdsWithDecls {
        return when (val type = this) {
            is MoveType.Simple -> {
                assign(result, meta) { aLoc.simpleValue(type) eq bLoc.simpleValue(type) }
            }
            is MoveType.Struct -> {
                type.compareFields(result, aLoc, bLoc, meta)
            }
            is MoveType.Enum -> {
                val variantComparisons = type.variants.mapIndexed { variantIndex, variant ->
                    val variantEq = TACKeyword.TMP(Tag.Bool, "variant$variantIndex!eq")
                    variantEq to variant.compareFields(variantEq, aLoc, bLoc, meta)
                }
                val actualVariantIndex = TACKeyword.TMP(Tag.Int, "actualVariant")
                mergeMany(
                    mergeMany(variantComparisons.map { (_, cmds) -> cmds }),
                    assign(actualVariantIndex, meta) { aLoc.enumVariant(type) },
                    assign(result, meta) {
                        (bLoc.enumVariant(type) eq actualVariantIndex.asSym()) and
                        LOr(
                            variantComparisons.mapIndexed { variantIndex, (variantEq, _) ->
                                (actualVariantIndex eq variantIndex.asTACExpr) and variantEq.asSym()
                            }
                        )
                    }
                )
            }
            is MoveType.Vector -> {
                assign(result, meta) {
                    (aLoc.vecLen() eq bLoc.vecLen()) and (aLoc.vecDigest() eq bLoc.vecDigest())
                }
            }
            is MoveType.GhostArray -> {
                loggerSetupHelpers.warn { "Havocing equality of ghost array type in $origCmd" }
                tac.generation.assignHavoc(result, meta)
            }
        }
    }


    /**
        Dereferences a reference.  Constructs a "switch" over the possible referenced locations, using [action] to
        generate the commands for each case.
     */
    context(CommandContext)
    private fun deref(
        ref: TACSymbol.Var,
        meta: MetaMap,
        action: context(CommandContext)(MemoryLocation) -> SimpleCmdsWithDecls
    ): SimpleCmdsWithDecls {
        val refTargets = moveCode.graph.cache.references.refTargetsBefore(origCmd.ptr, ref)
        check(refTargets.isNotEmpty()) { "No targets for $ref at ${origCmd.ptr}" }
        val refLayout = MemoryLayout.Reference.fromVar(ref)

        // Apply the action to the first (and typically only) target
        val firstCase = action(this@CommandContext, refTargets.first().targetLocation(refLayout))

        // If there are multiple targets, build the "switch" over them
        return refTargets.drop(1).fold(firstCase) { noMatch, refTarget ->
            val loc = refTarget.targetLocation(refLayout)
            DerefCase(
                layoutId = loc.layout.id,
                layoutIdVar = refLayout.layoutId,
                matchCmds = action(this@CommandContext, loc),
                noMatchCmds = noMatch
            ).toCmd(meta)
        }
    }

    @Suppress("MustHaveKSerializable") // We materialize this summary before serialization
    private class DerefCase(
        val layoutId: TACExpr,
        val layoutIdVar: TACSymbol.Var,
        val matchCmds: SimpleCmdsWithDecls,
        val noMatchCmds: SimpleCmdsWithDecls
    ) : ITESummary() {
        override val inputs
            get() = listOf(layoutIdVar) + (matchCmds.cmds + noMatchCmds.cmds).flatMap { it.getFreeVarsOfRhs() }

        override val trueWriteVars get() = matchCmds.cmds.mapNotNullToSet { it.getLhs() }
        override val falseWriteVars get() = noMatchCmds.cmds.mapNotNullToSet { it.getLhs() }

        // We immediately materialize these summaries in `MoveTACSimplifier.transform`, so we don't need to worry about
        // remapping variables.  (CommandWithRequiredDecls doesn't support remapping - and it would break the
        // typechecker anyway.)
        override fun transformSymbols(f: Transformer) = this

        override val cond get() = TXF { layoutIdVar eq layoutId }

        override fun onTrue() = matchCmds
        override fun onFalse() = noMatchCmds
    }

    context(CommandContext)
    private inline fun <reified T : MoveType> TACSymbol.Var.getType(): T {
        return when (val tag = this.tag) {
            is MoveTag -> tag.toMoveType().let {
                (it as? T) ?: error("Expected MoveType ${T::class}, got $it, in $origCmd")
            }
            else -> error("Expected MoveTag, got $tag, in $origCmd")
        }
    }

    context(CommandContext)
    private inline fun <reified T : MoveType.Value> TACSymbol.Var.getTargetType(): T {
        return getType<MoveType.Reference>().refType.let {
            (it as? T) ?: error("Expected target MoveType ${T::class}, got $it, in $origCmd")
        }
    }

    private val layoutIds = mutableMapOf<MemoryLayout.Value, Int>()
    val MemoryLayout.Value.id get() = layoutIds.getOrPut(this) { layoutIds.size }.asTACExpr(Tag.Int)
}
