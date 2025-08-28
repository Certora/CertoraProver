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

import analysis.CommandWithRequiredDecls.Companion.mergeMany
import analysis.SimpleCmdsWithDecls
import datastructures.PersistentStack
import datastructures.persistentStackOf
import datastructures.stdcollections.*
import log.*
import move.ConstantStringPropagator.MESSAGE_VAR
import move.MoveModule.*
import move.analysis.ReferenceAnalysis
import tac.*
import tac.generation.*
import utils.*
import vc.data.*
import java.math.BigInteger

private val loggerSetupHelpers = Logger(LoggerTypes.SETUP_HELPERS)

/**
    Implements the Move memory model in Core TAC.

    Move memory consists of a set of top-level "locations" that can be referenced via "borrow" operations.  Examples are
    local variables, or items in storage.  (Note that stack items are not "locations" in this sense, since they cannot
    be borrowed/referenced.).  These have the following properties:

    - A "location" can contain a primitive value, a struct, a vector, or a variant.
    - Locations are typed; each location contains exactly one type.
    - It is possible to get "interior" references to locations, which are references to the fields of a struct or
      elements of a vector.
    - It is *impossible* for one location to reference another location, because Move types cannot contain references.
    - The contents of a location can be copied to another location.
    - A part of a location (e.g. a field of a struct) can be copied to another location.
    - Vectors can be dynamically resized, by adding or removing elements at the end of the vector.

    Taken together, these properties make it somewhat natural for us to represent each "location" as a separate variable
    in TAC.  Locations with primitive types are represented as Tag.Bit256/Tag.Bool variables. Locations with struct or
    vector types are represented as a map variable, laid out as follows:

    - Structs are laid out as a series of fields, with the entire contents of each field stored in contiguous keys in
      the map.  Primitive fields take up one key, while struct fields might take up multiple keys.

    - Vectors consist of a length field and a digest field, followed by the elements of the vector.  The digest field
      holds the hash of all elements (this is typically havoc'd for dynamic vectors).  The elements are laid out as if
      they were fields of a struct.

    - To accomodate dynamic resizing of vectors, when vectors appear as fields or elements of other vectors, we allocate
      enough space for the maximum size of that vector.  For convenience, we choose a "maximum size" of 2^256 elements,
      which allows us to avoid having to code assumptions about the size field all over the place.

    Since vectors take up so much space in the maps, we must use Tag.Int keys for the struct/vector maps.  This prevents
    us from using "precise_bitwise_ops" mode for Move programs, but this may not be a problem in practice (Move programs
    do not do a lot of bitwise operations).  (If this does become a problem, we will need to add/infer constraints on
    the vector lengths, but let's cross that bridge when we come to it.)

    References are represented as a pair of variables: a location ID, and an offset.  The location ID is an integer
    identifying a referenced location variable, and the offset is the start offset of the referenced item in the map (or
    0 if the referenced variable is a primitive).

    Location IDs are assigned by [move.analysis.ReferenceAnalysis], which also tracks the set of location IDs that might be
    reachable by each reference at each point in the program.  This is used to implement dereferencing: we simply
    "switch" over the possible locations for that reference at the point of the deference.  (Typically we can determine
    statically that only one location is reachable, so this does not really lead to much overhead.)
 */
class MoveMemory(val scene: MoveScene) {
    companion object {
        /** The tag to use for referenceable locations. */
        private val LocTag = Tag.GhostMap(listOf(Tag.Int), Tag.Int)

        private data class RefVars(val locId: TACSymbol.Var, val offset: TACSymbol.Var)

        /**
            Given a MoveTag.Ref-tagged variable, returns the pair of variables that represent the reference in core TAC.
        */
        private fun transformRefVar(ref: TACSymbol.Var): RefVars {
            check(ref.tag is MoveTag.Ref) { "Expected reference variable, got $ref" }
            return RefVars(
                locId = ref.copy(namePrefix = ref.namePrefix + "!iLoc", tag = Tag.Bit256),
                offset = ref.copy(namePrefix = ref.namePrefix + "!offs", tag = Tag.Int)
            )
        }

        /**
            Given a possibly-MoveTag-tagged location variable, returns the variable that represents that location in core
            TAC.
        */
        private fun transformLocVar(v: TACSymbol.Var): TACSymbol.Var {
            return when (val tag = v.tag) {
                is MoveTag -> when (tag) {
                    is MoveTag.Ref -> error("MoveTag.Ref is not a location variable tag: $v")
                    is MoveTag.Struct,
                    is MoveTag.Vec,
                    is MoveTag.GhostArray -> v.copy(namePrefix = v.namePrefix + "!loc", tag = LocTag)
                    is MoveTag.Nondet -> v.copy(namePrefix = v.namePrefix + "!nondet", tag = Tag.Bit256)
                }
                else -> v
            }
        }

        val vecLengthOffset = BigInteger.ZERO
        val vecDigestOffset = BigInteger.ONE
        val vecElemsOffset = BigInteger.TWO
    }

    fun transform(moveCode: MoveTACProgram): CoreTACProgram {
        val refs = moveCode.graph.cache.references
        val transformedBlocks = moveCode.graph.blocks.associate { block ->
            block.id to mergeMany(
                block.commands.map { transformCommand(it, refs) }
            )
        }

        val symbolTable = moveCode.symbolTable.mergeDecls(
            transformedBlocks.values.map { it.varDecls }.unionAll()
        )

        return CoreTACProgram(
            transformedBlocks.mapValues { it.value.cmds },
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

    private class CommandContext(val refs: ReferenceAnalysis, val origCmd: MoveTACProgram.LCmd)

    private fun transformCommand(lcmd: MoveTACProgram.LCmd, refs: ReferenceAnalysis): SimpleCmdsWithDecls {
        with(CommandContext(refs, lcmd)) {
            return when (val cmd = lcmd.cmd) {
                is TACCmd.Simple -> {
                    // If the command has a MESSAGE_VAR meta, we need to transform the referenced variable.
                    val messageVar = cmd.meta[MESSAGE_VAR]
                    @Suppress("NAME_SHADOWING") // Intentionally shadow the un-transformed `cmd`
                    val cmd = if (messageVar != null) {
                        cmd.withMeta(cmd.meta + (MESSAGE_VAR to transformLocVar(messageVar)))
                    } else {
                        cmd
                    }
                    when (cmd) {
                        is TACCmd.Simple.AssigningCmd.AssignExpCmd -> transformAssignExpCmd(cmd)
                        is TACCmd.Simple.AssigningCmd.AssignHavocCmd -> transformAssignHavocCmd(cmd)
                        else -> cmd.withDecls()
                    }
                }
                is TACCmd.Move -> {
                    check(MESSAGE_VAR !in cmd.meta) { "Unexpected MESSAGE_VAR in Move command: $lcmd" }
                    when (cmd) {
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
                        is TACCmd.Move.GhostArrayBorrowCmd -> transformGhostArrayBorrowCmd(cmd)
                        is TACCmd.Move.HashCmd -> transformHashCmd(cmd)
                        is TACCmd.Move.EqCmd -> transformEqCmd(cmd)
                    }
                }
                else -> error("Bad command in MoveTACProgram: $lcmd")
            }
        }
    }

    context(CommandContext)
    private fun transformAssignExpCmd(cmd: TACCmd.Simple.AssigningCmd.AssignExpCmd): SimpleCmdsWithDecls {
        return when (val tag = cmd.lhs.tag) {
            is MoveTag -> {
                val rhs = (cmd.rhs as? TACExpr.Sym.Var)?.s ?: error("Expected variable, got ${cmd.rhs}")
                check(cmd.lhs.tag == rhs.tag) { "Cannot copy between different types: ${cmd.lhs}, $rhs" }
                when (tag) {
                    is MoveTag.Ref -> {
                        val dstVars = transformRefVar(cmd.lhs)
                        val srcVars = transformRefVar(rhs)
                        mergeMany(
                            assign(dstVars.locId, cmd.meta) { srcVars.locId.asSym() },
                            assign(dstVars.offset, cmd.meta) { srcVars.offset.asSym() }
                        )
                    }
                    is MoveTag.Struct,
                    is MoveTag.Vec,
                    is MoveTag.GhostArray,
                    is MoveTag.Nondet -> assign(transformLocVar(cmd.lhs), cmd.meta) { transformLocVar(rhs).asSym() }
                }
            }
            else -> cmd.withDecls()
        }
    }


    context(CommandContext)
    private fun transformAssignHavocCmd(cmd: TACCmd.Simple.AssigningCmd.AssignHavocCmd): SimpleCmdsWithDecls {
        return when (val tag = cmd.lhs.tag) {
            is MoveTag -> when (tag) {
                is MoveTag.Ref -> error("Cannot havoc a reference")
                is MoveTag.Struct,
                is MoveTag.Vec,
                is MoveTag.GhostArray,
                is MoveTag.Nondet -> assignHavoc(transformLocVar(cmd.lhs), cmd.meta)
            }
            else -> cmd.withDecls()
        }
    }

    context(CommandContext)
    private fun transformBorrowLocCmd(cmd: TACCmd.Move.BorrowLocCmd): SimpleCmdsWithDecls {
        val transformRefVar = transformRefVar(cmd.ref)
        val locId = refs.varToId[cmd.loc] ?: error("No id for ${cmd.loc}")
        return mergeMany(
            assign(transformRefVar.locId, cmd.meta) { locId.asTACExpr },
            assign(transformRefVar.offset, cmd.meta) { 0.asTACExpr }
        )
    }

    context(CommandContext)
    private fun transformBorrowFieldCmd(cmd: TACCmd.Move.BorrowFieldCmd): SimpleCmdsWithDecls {
        val srcRefTag = cmd.srcRef.tag as? MoveTag.Ref ?: error("Expected ref type, got ${cmd.srcRef.tag}")
        val type = srcRefTag.refType as? MoveType.Struct
            ?: error("Expected struct type, got ${srcRefTag.refType}")

        val fields = structLayout(type).fieldOffsets
        check(fields.size > cmd.fieldIndex) { "Invalid field index ${cmd.fieldIndex} for ${type.name}" }

        val (_, fieldOffset) = fields[cmd.fieldIndex]

        val srcRefVars = transformRefVar(cmd.srcRef)
        val dstRefVars = transformRefVar(cmd.dstRef)
        return mergeMany(
            assign(dstRefVars.locId, cmd.meta) { srcRefVars.locId.asSym() },
            assign(dstRefVars.offset, cmd.meta) { srcRefVars.offset.asSym() intAdd fieldOffset.asTACExpr },
        )
    }

    context(CommandContext)
    private fun transformReadRefCmd(cmd: TACCmd.Move.ReadRefCmd): SimpleCmdsWithDecls {
        val refTag = cmd.ref.tag as? MoveTag.Ref ?: error("Expected reference type, got ${cmd.ref.tag}")
        return deref(cmd.ref, cmd.meta) { deref ->
            readLoc(
                type = refTag.refType,
                dst = cmd.dst,
                ref = deref,
                meta = cmd.meta
            )
        }
    }

    context(CommandContext)
    private fun transformWriteRefCmd(cmd: TACCmd.Move.WriteRefCmd): SimpleCmdsWithDecls {
        val refTag = cmd.ref.tag as? MoveTag.Ref ?: error("Expected reference type, got ${cmd.ref.tag}")
        return deref(cmd.ref, cmd.meta) { deref ->
            writeLoc(
                type = refTag.refType,
                ref = deref,
                src = cmd.src,
                meta = cmd.meta
            )
        }
    }

    context(CommandContext)
    private fun transformPackStructCmd(cmd: TACCmd.Move.PackStructCmd): SimpleCmdsWithDecls {
        val structTag = cmd.dst.tag as? MoveTag.Struct ?: error("Expected struct type, got ${cmd.dst.tag}")
        val fields = structLayout(structTag.type).fieldOffsets
        check(fields.size == cmd.srcs.size) { "Expected ${fields.size} fields, got ${cmd.srcs.size}" }
        return mergeMany(
            listOf(
                assignHavoc(transformLocVar(cmd.dst), cmd.meta),
            ) + fields.mapIndexed { fieldIndex, (field, offset) ->
                writeLoc(
                    type = field.type,
                    ref = DereferencedRef(cmd.dst, offset),
                    src = cmd.srcs[fieldIndex],
                    meta = cmd.meta
                )
            }
        )
    }

    context(CommandContext)
    private fun transformUnpackStructCmd(cmd: TACCmd.Move.UnpackStructCmd): SimpleCmdsWithDecls {
        val structTag = cmd.src.tag as? MoveTag.Struct ?: error("Expected struct type, got ${cmd.src.tag}")
        val fields = structLayout(structTag.type).fieldOffsets
        check(fields.size == cmd.dsts.size) { "Expected ${fields.size} fields, got ${cmd.dsts.size}" }
        return mergeMany(
            fields.mapIndexed { fieldIndex, (field, offset) ->
                readLoc(
                    type = field.type,
                    dst = cmd.dsts[fieldIndex],
                    ref = DereferencedRef(cmd.src, offset),
                    meta = cmd.meta
                )
            }
        )
    }

    context(CommandContext)
    private fun transformVecPackCmd(cmd: TACCmd.Move.VecPackCmd): SimpleCmdsWithDecls {
        val vecTag = cmd.dst.tag as? MoveTag.Vec ?: error("Expected vector type, got ${cmd.dst.tag}")
        val elemType = vecTag.elemType
        val elemCount = cmd.srcs.size
        val elemSize = fieldSize(elemType)
        val transformedLoc = transformLocVar(cmd.dst)
        val elemHashes = cmd.srcs.map { src -> TACKeyword.TMP(Tag.Bit256, "${src.namePrefix}!hash") }
        val digest = TACKeyword.TMP(Tag.Bit256, "digest")
        var elemOffset = vecElemsOffset
        return mergeMany(
            cmd.srcs.zip(elemHashes).map { (src, elemHash) ->
                // Hash each non-Bits element
                when (src.tag) {
                    is Tag.Bits -> {
                        assign(elemHash, cmd.meta) { src.asSym() }
                    }
                    else -> {
                        hash(
                            loc = src,
                            dst = elemHash,
                            hashFamily = HashFamily.MoveVectorElemHash,
                            meta = cmd.meta
                        )
                    }
                }
            } + listOf(
                // Compute the vector's digest
                assign(digest, cmd.meta) {
                    SimpleHash(
                        length = elemHashes.size.asTACExpr,
                        args = elemHashes.map { it.asSym() },
                        hashFamily = HashFamily.MoveVectorDigest
                    )
                },
                // Initialize the vector location
                assignHavoc(transformedLoc, cmd.meta),
                // Store the length and digest
                assign(transformedLoc, cmd.meta) { Store(transformedLoc.asSym(), listOf(vecLengthOffset.asTACExpr), elemCount.asTACExpr) },
                assign(transformedLoc, cmd.meta) { Store(transformedLoc.asSym(), listOf(vecDigestOffset.asTACExpr), digest.asSym()) },
            ) + cmd.srcs.map { src ->
                // Store each element
                writeLoc(
                    type = elemType,
                    ref = DereferencedRef(cmd.dst, elemOffset),
                    src = src,
                    meta = cmd.meta
                ).also {
                    elemOffset += elemSize
                }
            }
        )
    }

    context(CommandContext)
    private fun transformVecUnpackCmd(cmd: TACCmd.Move.VecUnpackCmd): SimpleCmdsWithDecls {
        val vecTag = cmd.src.tag as? MoveTag.Vec ?: error("Expected vector type, got ${cmd.src.tag}")
        val elemType = vecTag.elemType
        val elemCount = cmd.dsts.size
        val elemSize = fieldSize(elemType)
        val transformedLoc = transformLocVar(cmd.src)
        var elemOffset = vecElemsOffset
        return mergeMany(
            listOf(
                Trap.assert("Vector length mismatch", cmd.meta) {
                    select(transformedLoc.asSym(), vecLengthOffset.asTACExpr) eq elemCount.asTACExpr
                }
            ) + cmd.dsts.map { dst ->
                readLoc(
                    type = elemType,
                    dst = dst,
                    ref = DereferencedRef(cmd.src, elemOffset),
                    meta = cmd.meta
                ).also {
                    elemOffset += elemSize
                }
            }
        )
    }

    context(CommandContext)
    private fun transformVecLenCmd(cmd: TACCmd.Move.VecLenCmd): SimpleCmdsWithDecls {
        return getVecLen(cmd.dst, cmd.ref, cmd.meta)
    }

    context(CommandContext)
    private fun transformVecBorrowCmd(cmd: TACCmd.Move.VecBorrowCmd): SimpleCmdsWithDecls {
        val srcRefTag = cmd.srcRef.tag as? MoveTag.Ref ?: error("Expected reference type, got ${cmd.srcRef.tag}")
        val type = srcRefTag.refType as? MoveType.Vector
            ?: error("Expected vector type, got ${srcRefTag.refType}")

        val elemType = type.elemType
        val elemSize = fieldSize(elemType)

        val srcRefVars = transformRefVar(cmd.srcRef)
        val dstRefVars = transformRefVar(cmd.dstRef)
        return mergeMany(
            listOfNotNull(
                runIf(cmd.doBoundsCheck) {
                    val len = TACKeyword.TMP(Tag.Bit256, "!len")
                    mergeMany(
                        getVecLen(len, cmd.srcRef, cmd.meta),
                        Trap.assert("Index out of bounds", cmd.meta) { cmd.index lt len.asSym() }
                    )
                },
                assign(dstRefVars.locId, cmd.meta) { srcRefVars.locId.asSym() },
                assign(dstRefVars.offset, cmd.meta) {
                    srcRefVars.offset.asSym() intAdd vecElemsOffset.asTACExpr intAdd (cmd.index.asSym() intMul elemSize.asTACExpr)
                },
            )
        )
    }

    context(CommandContext)
    private fun transformVecPushBackCmd(cmd: TACCmd.Move.VecPushBackCmd): SimpleCmdsWithDecls {
        val refTag = cmd.ref.tag as? MoveTag.Ref ?: error("Expected reference type, got ${cmd.ref.tag}")
        val vecType = refTag.refType as? MoveType.Vector
            ?: error("Expected vector type, got ${refTag.refType}")
        val elemType = vecType.elemType
        val elemSize = fieldSize(elemType)
        val oldVecDigest = TACKeyword.TMP(Tag.Bit256, "oldVecDigest")
        val newElemHash = TACKeyword.TMP(Tag.Bit256, "newElemHash")
        return deref(cmd.ref, cmd.meta) { deref ->
            TXF {
                select(deref.loc.asSym(), deref.offset)
            }.letVar("vecLen", Tag.Int, cmd.meta) { len ->
                TXF {
                    deref.offset intAdd vecElemsOffset intAdd (len intMul elemSize)
                }.letVar("elemOffset", Tag.Int, cmd.meta) { elemOffset ->
                    TXF { len intAdd 1.asTACExpr }.letVar("newLen", Tag.Int, cmd.meta) { newLen ->
                        val elemRef = deref.copy(
                            offset = elemOffset,
                            path = deref.path.push(ReferenceAnalysis.PathComponent.VecElem)
                        )
                        mergeMany(
                            assign(oldVecDigest, cmd.meta) {
                                safeMathNarrow(
                                    select(deref.loc.asSym(), deref.offset intAdd vecDigestOffset.asTACExpr),
                                    Tag.Bit256,
                                    unconditionallySafe = true // The vector digest is always 256 bits
                                )
                            },
                            MoveType.U64.assumeBounds(newLen.s, cmd.meta),
                            assign(deref.loc, cmd.meta) {
                                Store(deref.loc.asSym(), listOf(deref.offset), newLen)
                            },
                            writeLoc(elemType, elemRef, cmd.src, cmd.meta),
                            // writeLoc will have havoc'd the digest for this vector; let's add an assume so that
                            // a.push_back(b) == a.push_back(b)
                            hash(
                                dst = newElemHash,
                                loc = cmd.src,
                                hashFamily = HashFamily.MoveVectorElemHash,
                                meta = cmd.meta
                            ),
                            assume(cmd.meta) {
                                select(deref.loc.asSym(), deref.offset intAdd vecDigestOffset.asTACExpr) eq
                                select(TACKeyword.MOVE_VECTOR_PUSH_BACK_DIGEST.toVar().asSym(), oldVecDigest.asSym(), newElemHash.asSym())
                            }.merge(TACKeyword.MOVE_VECTOR_PUSH_BACK_DIGEST.toVar())
                        )
                    }
                }
            }
        }
    }

    context(CommandContext)
    private fun transformVecPopBackCmd(cmd: TACCmd.Move.VecPopBackCmd): SimpleCmdsWithDecls {
        val refTag = cmd.ref.tag as? MoveTag.Ref ?: error("Expected reference type, got ${cmd.ref.tag}")
        val vecType = refTag.refType as? MoveType.Vector
            ?: error("Expected vector type, got ${refTag.refType}")
        val elemType = vecType.elemType
        val elemSize = fieldSize(elemType)
        val oldVecDigest = TACKeyword.TMP(Tag.Bit256, "oldVecDigest")
        return deref(cmd.ref, cmd.meta) { deref ->
            TXF {
                select(deref.loc.asSym(), deref.offset)
            }.letVar("vecLen", Tag.Int, meta = cmd.meta) { len ->
                TXF {
                    deref.offset intAdd vecElemsOffset intAdd ((len intSub 1) intMul elemSize)
                }.letVar("elemOffset", Tag.Int, cmd.meta) { elemOffset ->
                    val elemRef = deref.copy(
                        offset = elemOffset,
                        path = deref.path.push(ReferenceAnalysis.PathComponent.VecElem)
                    )
                    mergeMany(
                        Trap.assert("Empty vector", cmd.meta) { len gt 0.asTACExpr },
                        assign(oldVecDigest, cmd.meta) {
                            safeMathNarrow(
                                select(deref.loc.asSym(), deref.offset intAdd vecDigestOffset.asTACExpr),
                                Tag.Bit256,
                                unconditionallySafe = true // The vector digest is always 256 bits
                            )
                        },
                        assign(deref.loc, cmd.meta) {
                            Store(deref.loc.asSym(), listOf(deref.offset), len intSub 1.asTACExpr)
                        },
                        readLoc(elemType, cmd.dst, elemRef, cmd.meta),
                        // This is a modification that does not use `writeLoc`, so we have to manually invalidate
                        // digests here.
                        invalidateVectorDigests(elemRef, cmd.meta),
                        // Add an assume so that a.pop_back() == a.pop_back()
                        assume(cmd.meta) {
                            select(deref.loc.asSym(), deref.offset intAdd vecDigestOffset.asTACExpr) eq
                            select(TACKeyword.MOVE_VECTOR_POP_BACK_DIGEST.toVar().asSym(), oldVecDigest.asSym())
                        }.merge(TACKeyword.MOVE_VECTOR_POP_BACK_DIGEST.toVar())
                    )
                }
            }
        }
    }

    context(CommandContext)
    private fun transformGhostArrayBorrowCmd(cmd: TACCmd.Move.GhostArrayBorrowCmd): SimpleCmdsWithDecls {
        val arrayRefTag = cmd.arrayRef.tag as? MoveTag.Ref
            ?: error("Expected reference type, got ${cmd.arrayRef.tag}")
        val arrayTag = arrayRefTag.refType as? MoveType.GhostArray
            ?: error("Expected ghost array type, got ${cmd.arrayRef.tag}")

        val arrayRefVars = transformRefVar(cmd.arrayRef)
        val elemSize = fieldSize(arrayTag.elemType)
        val dstRefVars = transformRefVar(cmd.dstRef)
        return mergeMany(
            assign(dstRefVars.locId, cmd.meta) { arrayRefVars.locId.asSym() },
            assign(dstRefVars.offset, cmd.meta) { arrayRefVars.offset intAdd (cmd.index.asSym() intMul elemSize.asTACExpr) },
        )
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

        check(tag !is MoveTag.Ref) { "References should have been unwrapped: $origCmd" }

        @Suppress("NAME_SHADOWING")
        val loc = transformLocVar(loc)

        fun hashExprs(offset: BigInteger, type: MoveType.Value): List<TACExpr> {
            return when (type) {
                is MoveType.Bits, is MoveType.Nondet -> listOf(
                    TXF {
                        safeMathNarrow(
                            select(loc.asSym(), offset.asTACExpr),
                            Tag.Bit256,
                            unconditionallySafe = true // This cannot be larger than 256 bits
                        )
                    }
                )
                is MoveType.Bool -> listOf(
                    TXF { ite(select(loc.asSym(), offset.asTACExpr) eq 0.asTACExpr, 0.asTACExpr, 1.asTACExpr) }
                )
                is MoveType.MathInt -> {
                    // TODO CERT-9258 TACExpr.SimpleHash does not support Tag.Int arguments
                    loggerSetupHelpers.warn { "Havocing hash of MathInt in $origCmd" }
                    listOf(TACExpr.Unconstrained(Tag.Bit256))
                }
                is MoveType.Vector -> listOf(
                    TXF {
                        safeMathNarrow(
                            select(loc.asSym(), (offset + vecLengthOffset).asTACExpr),
                            Tag.Bit256,
                            unconditionallySafe = true // The vector length is always 256 bits
                        )
                    },
                    TXF {
                        safeMathNarrow(
                            select(loc.asSym(),
                            (offset + vecDigestOffset).asTACExpr),
                            Tag.Bit256,
                            unconditionallySafe = true // The vector digest is always 256 bits
                        )
                    }
                )
                is MoveType.Struct -> {
                    val fields = type.fields ?: run {
                        loggerSetupHelpers.warn { "Havocing hash of native struct type $type in $origCmd" }
                        return listOf(TACExpr.Unconstrained(Tag.Bit256))
                    }
                    var fieldOffset = offset
                    fields.flatMap { field ->
                        hashExprs(fieldOffset, field.type).also {
                            fieldOffset += fieldSize(field.type)
                        }
                    }
                }
                is MoveType.GhostArray -> {
                    loggerSetupHelpers.warn { "Havocing hash of ghost array type in $origCmd" }
                    listOf(TACExpr.Unconstrained(Tag.Bit256))
                }
            }
        }

        val exprs = hashExprs(BigInteger.ZERO, tag.toMoveValueType())

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

        We support precise equality checks for integers, booleans, and structs that do not contain vectors or ghost
        arrays. For other types, we simply havoc the result.
     */
    context(CommandContext)
    private fun transformEqCmd(cmd: TACCmd.Move.EqCmd): SimpleCmdsWithDecls {
        check(cmd.a.tag == cmd.b.tag) {
            "Cannot compare different types: ${cmd.a.tag} and ${cmd.b.tag}"
        }
        val tag = cmd.a.tag
        if (tag !is MoveTag) {
            return assign(cmd.dst, cmd.meta) { cmd.a.asSym() eq cmd.b.asSym() }
        }

        check(tag !is MoveTag.Ref) { "References should have been unwrapped: $origCmd" }

        val aLoc = transformLocVar(cmd.a)
        val bLoc = transformLocVar(cmd.b)

        fun compare(dest: TACSymbol.Var, offset: BigInteger, type: MoveType.Value): SimpleCmdsWithDecls {
            return when(type) {
                is MoveType.Bits, is MoveType.MathInt, is MoveType.Nondet -> {
                    assign(dest, cmd.meta) {
                        select(aLoc.asSym(), offset.asTACExpr) eq select(bLoc.asSym(), offset.asTACExpr)
                    }
                }
                is MoveType.Bool -> {
                    assign(dest, cmd.meta) {
                        (select(aLoc.asSym(), offset.asTACExpr) neq 0.asTACExpr) eq
                        (select(bLoc.asSym(), offset.asTACExpr) neq 0.asTACExpr)
                    }
                }
                is MoveType.Vector -> {
                    // Compare length and digest
                    val lengthOffset = (offset + vecLengthOffset).asTACExpr
                    val digestOffset = (offset + vecDigestOffset).asTACExpr
                    assign(dest, cmd.meta) {
                        (select(aLoc.asSym(), lengthOffset) eq select(bLoc.asSym(), lengthOffset)) and
                        (select(aLoc.asSym(), digestOffset) eq select(bLoc.asSym(), digestOffset))
                    }
                }
                is MoveType.Struct -> {
                    val fields = type.fields ?: run {
                        loggerSetupHelpers.warn { "Havocing equality of native struct type $type in $origCmd" }
                        return assignHavoc(dest, cmd.meta)
                    }
                    val fieldEqs = fields.map {
                        TACKeyword.TMP(Tag.Bool, "${it.name}!eq")
                    }
                    var fieldOffset = offset
                    mergeMany(
                        fields.zip(fieldEqs).map { (field, fieldEq) ->
                            compare(fieldEq, fieldOffset, field.type).also {
                                fieldOffset += fieldSize(field.type)
                            }
                        } + listOf(
                            assign(dest, cmd.meta) {
                                fieldEqs.fold(true.asTACExpr as TACExpr) { acc, fieldEq -> acc and fieldEq }
                            }
                        )
                    )
                }
                is MoveType.GhostArray -> {
                    loggerSetupHelpers.warn { "Havocing equality of ghost array type in $origCmd" }
                    assignHavoc(dest, cmd.meta)
                }
            }
        }

        return compare(cmd.dst, BigInteger.ZERO, tag.toMoveValueType())
    }

    private data class DereferencedRef(
        /** The original MoveTACProgram representation of the referenced location */
        val origLoc: TACSymbol.Var,
        /** The transformed Core TAC representation of the referenced location */
        val loc: TACSymbol.Var,
        /** The offset within the referenced location */
        val offset: TACExpr,
        /** The path to the referenced location (which fields/vectors did we traverse to get here) */
        val path: PersistentStack<ReferenceAnalysis.PathComponent>
    ) {
        constructor(origLoc: TACSymbol.Var, offset: BigInteger) :
            this(origLoc, transformLocVar(origLoc), offset.asTACExpr, persistentStackOf())
    }

    /**
        Dereferences a reference.  Constructs a "switch" over the possible referenced locations, using [action] to
        generate the commands for each case.
     */
    context(CommandContext)
    private fun deref(
        ref: TACSymbol.Var,
        meta: MetaMap,
        action: context(CommandContext)(DereferencedRef) -> SimpleCmdsWithDecls
    ): SimpleCmdsWithDecls {
        val refTargets = refs.cmdIn[origCmd.ptr]!![ref]
        check(refTargets != null && refTargets.isNotEmpty()) { "No targets for $ref at ${origCmd.ptr}" }

        val refVars = transformRefVar(ref)

        fun ReferenceAnalysis.RefTarget.toDeref(): DereferencedRef {
            val origLoc = refs.idToVar[locId]!!
            return DereferencedRef(
                origLoc = origLoc,
                loc = transformLocVar(origLoc),
                offset = refVars.offset.asSym(),
                path = path
            )
        }

        // Special-case: there's only one possible location, so we don't need to branch
        refTargets.singleOrNull()?.let { refTarget ->
            return action(this@CommandContext, refTarget.toDeref())
        }

        // Otherwise, we need to "switch" over the location ID
        val noneMatched = assert("Corrupt reference") { false.asTACExpr }
        return refTargets.fold(noneMatched) { noMatch, refTarget ->
            DerefCase(
                locId = refTarget.locId,
                locIdVar = refVars.locId,
                matchCmds = action(this@CommandContext, refTarget.toDeref()),
                noMatchCmds = noMatch
            ).toCmd(meta)
        }
    }

    @Suppress("MustHaveKSerializable") // We transform this class away before serialization
    private class DerefCase(
        val locId: Long,
        val locIdVar: TACSymbol.Var,
        val matchCmds: SimpleCmdsWithDecls,
        val noMatchCmds: SimpleCmdsWithDecls
    ) : ITESummary() {
        override val inputs
            get() = listOf(locIdVar) + (matchCmds.cmds + noMatchCmds.cmds).flatMap { it.getFreeVarsOfRhs() }

        override val trueWriteVars get() = matchCmds.cmds.mapNotNullToSet { it.getLhs() }
        override val falseWriteVars get() = noMatchCmds.cmds.mapNotNullToSet { it.getLhs() }

        // We immediately materialize these summaries in `MoveMemory.transform`, so we don't need to worry about
        // remapping variables.  (CommandWithRequiredDecls doesn't support remapping - and it would break the
        // typechecker anyway.)
        override fun transformSymbols(f: Transformer) = this

        override val cond get() = TXF { locIdVar eq locId.asTACExpr }

        override fun onTrue() = matchCmds
        override fun onFalse() = noMatchCmds
    }

    /**
        Reads a value of type [type] from the location [loc] at the given [offset], putting the result in [dst].
     */
    context(CommandContext)
    private fun readLoc(
        type: MoveType.Value,
        dst: TACSymbol.Var,
        ref: DereferencedRef,
        meta: MetaMap
    ): SimpleCmdsWithDecls {
        check(dst.tag == type.toTag()) { "Expected ${type.toTag()} destination, got ${dst.tag}" }
        return when (type) {
            is MoveType.Simple -> {
                when (ref.loc.tag) {
                    LocTag -> {
                        type.assignFromIntInBounds(dst, meta) { select(ref.loc.asSym(), ref.offset) }
                    }
                    type.toTag() -> {
                        mergeMany(
                            assert("Corrupt reference", meta) { ref.offset eq 0.asTACExpr },
                            assign(dst, meta) { ref.loc.asSym() }
                        )
                    }
                    else -> error("Expected valid location for $type, got ${ref.loc.tag}")
                }
            }
            is MoveType.Struct, is MoveType.Vector, is MoveType.GhostArray -> {
                readAggregate(transformLocVar(dst), ref, meta)
            }
        }
    }

    /**
        Reads a vector or struct from the location [loc] at the given [offset], putting the result in [dst].
     */
    context(CommandContext)
    private fun readAggregate(
        dst: TACSymbol.Var,
        ref: DereferencedRef,
        meta: MetaMap
    ): SimpleCmdsWithDecls {
        return defineMap(dst, meta) { (pos) ->
            select(ref.loc.asSym(), pos intAdd ref.offset)
        }
    }

    /**
        Writes the value in [src], of type [type], to the location [loc] at the given [offset].
     */
    context(CommandContext)
    private fun writeLoc(
        type: MoveType.Value,
        ref: DereferencedRef,
        src: TACSymbol.Var,
        meta: MetaMap
    ): SimpleCmdsWithDecls {
        check(src.tag == type.toTag()) { "Expected ${type.toTag()} source, got ${src.tag}" }
        val write = when (type) {
            MoveType.Bool -> {
                when (ref.loc.tag) {
                    Tag.Bool -> mergeMany(
                        assert("Corrupt reference", meta) { ref.offset eq 0.asTACExpr },
                        assign(ref.loc, meta) { src.asSym() }
                    )
                    LocTag -> {
                        assign(ref.loc, meta) {
                            Store(
                                ref.loc.asSym(),
                                listOf(ref.offset),
                                ite(src.asSym(), 1.asTACExpr, 0.asTACExpr)
                            )
                        }
                    }
                    else -> error("Expected valid boolean location, got ${ref.loc.tag}")
                }
            }
            is MoveType.Bits, is MoveType.MathInt, is MoveType.Nondet -> {
                when (ref.loc.tag) {
                    is Tag.Bits, is Tag.Int -> mergeMany(
                        assert("Corrupt reference", meta) { ref.offset eq 0.asTACExpr },
                        assign(ref.loc, meta) { src.asSym() }
                    )
                    LocTag -> {
                        assign(ref.loc, meta) {
                            Store(
                                ref.loc.asSym(),
                                listOf(ref.offset),
                                src.asSym()
                            )
                        }
                    }
                    else -> error("Expected valid bits location, got ${ref.loc.tag}")
                }
            }
            is MoveType.Struct, is MoveType.Vector, is MoveType.GhostArray -> {
                writeAggregate(ref, transformLocVar(src), fieldSize(type).asTACExpr, meta)
            }
        }
        return mergeMany(
            write,
            invalidateVectorDigests(ref, meta)
        )
    }

    /**
        Writes the vector or struct in [src], of overall size [size], to the location [loc] at the given [offset].
     */
    context(CommandContext)
    private fun writeAggregate(
        ref: DereferencedRef,
        src: TACSymbol.Var,
        size: TACExpr,
        meta: MetaMap
    ): SimpleCmdsWithDecls {
        return defineMap(ref.loc, meta) { (pos) ->
            ite(
                (ref.offset le pos) and (pos lt (ref.offset intAdd size)),
                select(src.asSym(), pos intSub ref.offset),
                select(ref.loc.asSym(), pos)
            )
        }
    }

    /**
        Gets the length of the vector referenced by [ref].
     */
    context(CommandContext)
    private fun getVecLen(dst: TACSymbol.Var, ref: TACSymbol.Var, meta: MetaMap): SimpleCmdsWithDecls {
        return deref(ref, meta) { deref ->
            MoveType.U64.assignFromIntInBounds(dst, meta) { select(deref.loc.asSym(), deref.offset) }
        }
    }

    /**
        When modifying part of a location (e.g., a field of a struct or element of a vector), we may affect the value
        of one or more vectors that contain the modified value.  (It can be more than one if the location contains
        nested vectors).  We need to invalidate/havoc the digests of these vectors, so that they will no longer compare
        equal to their old values.

        We do this by traversing the path to the modified location, as computed by [ReferenceAnalysis], and havocing
        the digest of each vector that we encounter along the way.
     */
    context(CommandContext)
    private fun invalidateVectorDigests(
        ref: DereferencedRef,
        meta: MetaMap
    ): SimpleCmdsWithDecls {
        if (ref.path.none { it is ReferenceAnalysis.PathComponent.VecElem }) {
            // No vector elements in the path, so no need to invalidate any vector digests
            return SimpleCmdsWithDecls()
        }

        val cmds = mutableListOf<SimpleCmdsWithDecls>()

        var currentType = when (val tag = ref.origLoc.tag) {
            is MoveTag -> tag.toMoveValueType()
            else -> error("Expected MoveTag, got $tag in $origCmd")
        }
        val currentOffset = TACKeyword.TMP(Tag.Int, "currentOffset")
        cmds += assign(currentOffset, meta) { 0.asTACExpr }

        fun advanceToTargetElement(elemType: MoveType.Value) {
            // On input, `currentOffset` is the offset of the first element of a vector/ghost array.
            // Advance `currentOffset` to the element that contains our target offset.
            // Basically we round the target offset down to the nearest element boundary.
            val elemSize = fieldSize(elemType).asTACExpr
            cmds += assign(currentOffset, meta) {
                currentOffset intAdd (
                    ((ref.offset intSub currentOffset.asSym()) intDiv elemSize) intMul elemSize
                )
            }
        }

        ref.path.toList().asReversed().forEach { pathComp ->
            when (pathComp) {
                is ReferenceAnalysis.PathComponent.Field -> {
                    val structType = currentType as? MoveType.Struct
                        ?: error("Expected struct type, got $currentType in $origCmd")
                    val (field, fieldOffset) = structLayout(structType).fieldOffsets[pathComp.fieldIndex]

                    // Advance to the target field and continue the traversal
                    cmds += assign(currentOffset, meta) { currentOffset.asSym() intAdd fieldOffset.asTACExpr }
                    currentType = field.type
                }
                ReferenceAnalysis.PathComponent.VecElem -> {
                    val vecType = currentType as? MoveType.Vector
                        ?: error("Expected vector type, got $currentType in $origCmd")
                    // Invalidate this vector's digest
                    cmds += assign(ref.loc, meta) {
                        Store(
                            ref.loc.asSym(),
                            listOf(currentOffset.asSym() intAdd vecDigestOffset.asTACExpr),
                            unconstrained(Tag.Bit256)
                        )
                    }
                    // Advance the current offset to the elements of the vector
                    cmds += assign(currentOffset, meta) { currentOffset.asSym() intAdd vecElemsOffset.asTACExpr }

                    // Advance to the target element and continue the traversal
                    advanceToTargetElement(vecType.elemType)
                    currentType = vecType.elemType
                }
                ReferenceAnalysis.PathComponent.GhostArrayElem -> {
                    val ghostArrayType = currentType as? MoveType.GhostArray
                        ?: error("Expected ghost array type, got $currentType in $origCmd")

                    // Advance to the target element and continue the traversal
                    advanceToTargetElement(ghostArrayType.elemType)
                    currentType = ghostArrayType.elemType
                }
            }
        }

        return mergeMany(cmds)
    }

    /**
        Gets the size of the given type, when used as a field in a struct (or element of a vector).
     */
    private fun fieldSize(type: MoveType.Value): BigInteger {
        return when (type) {
            is MoveType.Simple -> BigInteger.ONE
            is MoveType.Struct -> structLayout(type).size
            is MoveType.Vector -> vecElemsOffset + (BigInteger.TWO.pow(256) * fieldSize(type.elemType))
            is MoveType.GhostArray -> BigInteger.TWO.pow(256) * fieldSize(type.elemType)
        }
    }

    private data class StructLayout(
        val size: BigInteger,
        val fieldOffsets: List<Pair<MoveType.Struct.Field, BigInteger>>
    )

    private fun structLayout(struct: MoveType.Struct): StructLayout {
        var offset = BigInteger.ZERO
        val fields = struct.fields ?: error("Cannot compute layout of native struct $struct")
        val fieldOffsets = fields.map { field ->
            (field to offset).also {
                offset += fieldSize(field.type)
            }
        }
        return StructLayout(size = offset, fieldOffsets = fieldOffsets)
    }

}
