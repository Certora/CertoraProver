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

@file:kotlinx.serialization.UseContextualSerialization(MetaMap::class)
package vc.data

import com.certora.collect.*
import datastructures.stdcollections.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import move.MoveTag
import solver.CounterexampleModel
import utils.Range
import spec.cvlast.CVLType
import spec.cvlast.ComparisonBasis
import tac.*
import utils.*
import vc.data.TACBuiltInFunction.Hash.Companion.skeySort
import vc.data.annotation.HookableOpcode
import vc.data.annotation.OpcodeEnvironmentParam
import vc.data.annotation.OpcodeOutput
import vc.data.annotation.OpcodeParameter
import vc.data.state.TACValue
import vc.data.tacexprutil.*
import java.io.Serializable
import java.math.BigInteger
import java.util.*

fun <T: Serializable> MetaMap.find(k: MetaKey<T>): T? = this[k]

fun <T: Serializable> MetaMap.add(k: Pair<MetaKey<T>, T>) = this.plus(k)

val META_INFO_KEY = MetaKey<TACMetaInfo>("tac.meta", restore = true)

fun TACMetaInfo?.toMap() : MetaMap = if(this == null) { MetaMap() } else { MetaMap(META_INFO_KEY to this) }

/** Groups classes that wrap [TACCmd.Simple] (at the time of writing: [TACCmd.Simple] itself and [analysis.LTACCmd]). */
interface HasTACSimpleCmd { val cmd: TACCmd.Simple }

@Suppress("FunctionName")
interface AssignmentBuilder {
    infix fun TACSymbol.Var.`=`(rhs: TACSymbol) = TACCmd.Simple.AssigningCmd.AssignExpCmd(lhs = this, rhs = rhs.asSym())
    infix fun TACSymbol.Var.`=`(rhs: TACExpr) = TACCmd.Simple.AssigningCmd.AssignExpCmd(lhs = this, rhs = rhs)
    operator fun TACSymbol.plus(other: TACSymbol) = TACExpr.Vec.Add(this.asSym(), other.asSym())
}

fun exp(f: AssignmentBuilder.() -> TACCmd.Simple) = object : AssignmentBuilder { }.f()
fun exp(collector: MutableSet<TACSymbol.Var>, f: AssignmentBuilder.() -> TACCmd.Simple): TACCmd.Simple {
    val delegate = object : AssignmentBuilder { }
    return object : AssignmentBuilder {
        override fun TACSymbol.Var.`=`(rhs: TACSymbol): TACCmd.Simple.AssigningCmd.AssignExpCmd {
            collector.add(this)
            return with(delegate) {
                this@`=` `=` rhs
            }
        }

        override fun TACSymbol.Var.`=`(rhs: TACExpr): TACCmd.Simple.AssigningCmd.AssignExpCmd {
            collector.add(this)
            return with(delegate) {
                this@`=` `=` rhs
            }
        }
    }.f()
}

sealed class TACCmd : Serializable, ITACCmd {
    abstract val meta: MetaMap

    val metaSrcInfo: TACMetaInfo?
        get() = meta.find(META_INFO_KEY)

    fun sourceRange(): Range.Range? = metaSrcInfo?.getSourceDetails()?.range

    val sourceOrCVLRange get() = sourceRange() ?: meta[TACMeta.CVL_RANGE]

    open fun nameString(): String {
        return this.javaClass.simpleName
    }

    fun metaString(): String {
        return metaSrcInfo?.toString() ?: ""
    }

    open fun argString(): String = "" // default is empty

    override fun toString(): String {
        return "${nameString()} ${argString()} ${metaString()}"
    }

    fun toStringNoMeta(): String {
        return "${nameString()} ${argString()}"
    }

    @KSerializable
    sealed class Spec: TACCmd(), HasKSerializable {
        abstract fun withMeta(metaMap: MetaMap) : Spec
    }


    @KSerializable
    sealed class CVL: Spec(), TransformableVarEntity<CVL>  {

        abstract override fun withMeta(metaMap: MetaMap) : CVL
        fun withMeta(f: (MetaMap) -> MetaMap) : CVL = withMeta(f(meta))

        override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var): CVL =
            object : DefaultTACCmdSpecMapper() {
                override fun mapVar(t: TACSymbol.Var, index: Int): TACSymbol.Var =
                    super.mapVar(f(t))
            }.mapCvl(this)

        @KSerializable
        data class AssignBytesBlob(
            val lhs : TACSymbol.Var,
            val rhs : TACSymbol.Var,
            override val meta: MetaMap
        ) : CVL() {
            override fun argString(): String {
                return "$lhs := hash($rhs)"
            }

            init {
                check(Tag.Bit256.isSubtypeOf(lhs.tag))
                check(rhs.tag is Tag.CVLArray)
            }

            override fun withMeta(metaMap: MetaMap) = this.copy(meta = metaMap)
        }

        @KSerializable
        data class ArrayLengthRead(
            val lhs : TACSymbol.Var,
            val rhs : TACSymbol.Var,
            override val meta: MetaMap = MetaMap()
        ) : CVL() {
            override fun argString(): String {
                return "$lhs := $rhs . length"
            }

            init {
                check(Tag.Bit256.isSubtypeOf(lhs.tag))
                check(rhs.tag is Tag.CVLArray) {
                    "expected array, got ${rhs.tag}"
                }
            }

            override fun withMeta(metaMap: MetaMap) = this.copy(meta = metaMap)
        }

        /**
         * Gets the last word of a string, so given a string `s` it returns `s[s.length - (s.length % 32)]`
         * If `s.length` == 0 it returns whatever is in the buffer at `s[0]`, so users should be aware if they are
         * fine with this behavior.
         */
        @KSerializable
        data class StringLastWord(
            val lhs : TACSymbol.Var,
            val rhs : TACSymbol.Var,
            override val meta: MetaMap = MetaMap()
        ) : CVL() {
            override fun argString(): String {
                return "$lhs := $rhs . lastWord"
            }

            init {
                check(rhs.tag is Tag.CVLArray) {
                    "expected array, got ${rhs.tag}"
                }
            }

            override fun withMeta(metaMap: MetaMap) = this.copy(meta = metaMap)
        }

        // TODO(jtoman): init checks
        @KSerializable
        data class SetArrayLength(
            val lhs: TACSymbol.Var,
            val len: TACSymbol,
            override val meta: MetaMap = MetaMap()
        ) : CVL() {
            override fun withMeta(metaMap: MetaMap) = this.copy(meta = metaMap)
        }

        /**
         * Writes [value] in to the index [physicalIndex] with encoding [useEncoding] to the
         * bytemap identified by [target] and [outputPath]
         * @property target holds the basemap
         */
        @KSerializable
        data class WriteElement(
            val target: TACSymbol.Var,
            val outputPath: List<DataField> = listOf(DataField.ArrayData),
            val physicalIndex: TACSymbol,
            val value: TACSymbol,
            val useEncoding: Tag.CVLArray.UserArray.ElementEncoding,
            override val meta: MetaMap = MetaMap()
        ) : CVL() {
            init {
                fun checkEncoding(enc: Tag.CVLArray.UserArray.ElementEncoding) {
                    when (enc) {
                        Tag.CVLArray.UserArray.ElementEncoding.Boolean -> check(value.tag.isSubtypeOf(Tag.Bool))
                        Tag.CVLArray.UserArray.ElementEncoding.Unsigned,
                        Tag.CVLArray.UserArray.ElementEncoding.Signed -> check(value.tag.isSubtypeOf(Tag.Int))
                    }
                }

                val targetTag = target.tag
                check(targetTag is Tag.CVLArray)
                check(physicalIndex.tag is Tag.Bit256)
                when (targetTag) {
                    is Tag.CVLArray.RawArray -> check(value.tag is Tag.Bit256)
                    is Tag.CVLArray.UserArray -> {
                        if(targetTag.elementType is ObjectShape.Primitive) {
                            checkEncoding((targetTag.elementType as ObjectShape.Primitive).enc)
                        } else {
                            checkEncoding(useEncoding)
                        }
                    }
                }
                // TODO: check value tag, we should really have real type checking here
            }
            override fun withMeta(metaMap: MetaMap) = this.copy(meta = metaMap)
        }

        /**
         * Read an element of with encoding [useEncoding] from the bytemap
         * indicated by [base] and [dataPath], at the inddex given by [physicalIndex].
         * The (decoded) result is placed in [lhs]
         */
        @KSerializable
        data class ReadElement(
            val lhs: TACSymbol.Var,
            val base: TACSymbol.Var,
            val physicalIndex: TACSymbol,
            val dataPath: List<DataField> = listOf(DataField.ArrayData),
            val useEncoding: Tag.CVLArray.UserArray.ElementEncoding,
            override val meta: MetaMap = MetaMap()
        ) : CVL() {
            init {
                check(base.tag is Tag.CVLArray)
                check(physicalIndex.tag is Tag.Bit256)
            }

            override fun withMeta(metaMap: MetaMap) = this.copy(meta = metaMap)
        }

        /**
         * "Allocate" a fresh block for the array [arr] of size [amount] and place it
         * into [lhs]. It is guaranteed that for the same variable [arr], the value placed
         * in [lhs] will always larger than `a + prev`, where `prev` is the [lhs] of
         * some prior execution of [LocalAlloc], and `a` is that executions [amount].
         *
         * (It's a bump allocator gang)
         */
        @KSerializable
        data class LocalAlloc(
            val arr: TACSymbol.Var,
            val amount: TACSymbol,
            val lhs: TACSymbol.Var,
            override val meta: MetaMap = MetaMap()
        ) : CVL() {
            override fun withMeta(metaMap: MetaMap): CVL {
                return this.copy(meta = metaMap)
            }

            init {
                check(arr.tag is Tag.CVLArray.UserArray)
            }
        }


        /**
         * A buffer that can be copied to or from.
         * [EVMBuffer] are copied directly, [CVLBuffer] are a base variable
         * and some data path (a list of [DataField].
         */
        @KSerializable
        sealed interface Buffer : AmbiSerializable {
            @KSerializable
            data class EVMBuffer(val t: TACSymbol.Var) : Buffer
            @KSerializable
            data class CVLBuffer(val root: TACSymbol.Var, val dataPath: List<DataField>) : Buffer
        }

        /**
         * A buffer pointer is simply a [Buffer] and some [offset] within that [buffer].
         */
        @KSerializable
        data class BufferPointer(
            val offset: TACSymbol,
            val buffer: Buffer
        ) : AmbiSerializable {
            fun getReferencedSyms(): TreapSet<TACSymbol> = when(this.buffer) {
                is TACCmd.CVL.Buffer.CVLBuffer -> treapSetOf(this.buffer.root)
                is TACCmd.CVL.Buffer.EVMBuffer -> treapSetOf(this.buffer.t)
            } + this.offset

            companion object {
                fun TACSymbol.Var.toBufferPointer(): BufferPointer {
                   check(this.tag is Tag.CVLArray)
                   return BufferPointer(
                       offset = TACSymbol.Zero,
                       buffer = Buffer.CVLBuffer(this, dataPath = listOf(DataField.ArrayData))
                   )
                }
            }
        }

        /**
         * Copy [logicalLength] bytes from [src] to [dst], where each element has size [elementSize].
         * If [destinationSource] is non-null, then the copy operation is done as a [vc.data.TACExpr.LongStore]
         * with [destinationSource] as the source operand.
         */
        @KSerializable
        data class ArrayCopy(
            val src: BufferPointer,
            val dst: BufferPointer,
            val elementSize: Int,
            val logicalLength: TACSymbol,
            val destinationSource: TACSymbol.Var? = null,
            override val meta: MetaMap = MetaMap()
        ) : CVL() {
            init {
                check(destinationSource == null || dst.buffer is Buffer.EVMBuffer)
            }

            override fun withMeta(metaMap: MetaMap): CVL {
                return this.copy(meta = metaMap)
            }
        }

        /**
         * Set storage to the state that is saved in [stateVar]
         */
        @KSerializable
        data class SetBlockchainState(
            val stateVar: TACSymbol.Var,
            override val meta: MetaMap = MetaMap()
        ) : CVL() {
            override fun withMeta(metaMap: MetaMap) = this.copy(meta = metaMap)
        }

        /**
         * Copy current storage state into [lhs]
         */
        @KSerializable
        data class CopyBlockchainState(
            val lhs: TACSymbol.Var,
            override val meta: MetaMap = MetaMap()
        ) : CVL() {
            override fun withMeta(metaMap: MetaMap) = this.copy(meta = metaMap)
        }

        /**
         * A placeholder [TACCmd.CVL] for converting a VM parameter into a variable with CVL type.  The [rhsName]
         * is interpreted differently depending on context.  For example, in a summary application it is the
         * name of the argument from the method declaration.
         *
         * @property lhsVar // Keeps track of the TAC Variable holding the value (handing this job to a
         * [TACSymbolAllocation] does not make sense in cases where we generate a transient cvl parameter
         *
         * @property lhsType the type to convert to
         *
         * @property rhsName the name of the VM Parameter which is used as a key to find the converter during
         * instrumentation
         *
         * @property rhsType the type to convert from
         */
        @KSerializable
        data class AssignVMParam(
            val lhsVar: TACSymbol.Var,
            val lhsType: CVLType.PureCVLType,
            val rhsName: String,
            val rhsType: CVLType.VM,
            override val meta: MetaMap = MetaMap()
        ) : CVL() {
            override fun withMeta(metaMap: MetaMap) = this.copy(meta = metaMap)
        }

        /** A [VMParamConverter] represents a bundle of conversions for replacing [AssignVMParam]s */
        fun interface VMParamConverter {
            /**
             * @return a program to perform the assignment described by [assignment], if possible
             * @throws Exception if there is no conversion defined for the rhs of the assignment
             */
            fun convert(assignment : AssignVMParam) : CVLTACProgram
        }

        /**
         * Compare the storage variables in [storage1] and [storage2] (which must be of transient type
         * [tac.Tag.BlockchainState]) and place the result in [lhsVar]. The basis of comparison is given by
         * [storageBasis]. The comparison is equality or inequality depending on the value of [isEquality].
         * The [useSkolemizaton] flag instructs the compiler that skolemization is safe to be used (and is
         * preferred due to counter example niceties).
         */
        @KSerializable
        data class CompareStorage(
            val lhsVar: TACSymbol.Var,
            val storage1: TACSymbol.Var,
            val storage2: TACSymbol.Var,
            val storageBasis: ComparisonBasis,
            val isEquality: Boolean,
            val useSkolemizaton: Boolean,
            override val meta: MetaMap = MetaMap()
        ) : CVL() {
            init {
                check(storage1.tag is Tag.BlockchainState)
                check(storage2.tag is Tag.BlockchainState)
                check(lhsVar.tag == Tag.Bool)
                check(!useSkolemizaton || isEquality)
            }

            override fun withMeta(metaMap: MetaMap) = this.copy(meta = metaMap)
        }
        /**
         * Compare the two arrays contained in [left] and [right] and
         * place the result in [lhsVar].
         *
         * Supports:
         * - Bytes1Array (bytes/string) - simple length + data hash comparison
         * - Arrays of primitives - same as above
         * - Arrays of structs - compares length and hash of each data path
         */
        @KSerializable
        data class CompareArray(
            val lhsVar: TACSymbol.Var,
            val left: TACSymbol.Var,
            val right: TACSymbol.Var,
            override val meta: MetaMap = MetaMap()
        ) : CVL() {
            init {
                check(left.tag is Tag.CVLArray.UserArray)
                check(right.tag is Tag.CVLArray.UserArray)
                check(lhsVar.tag == Tag.Bool)
            }

            override fun withMeta(metaMap: MetaMap) = this.copy(meta = metaMap)
        }
    }



//    interface AssigningCmd { val lhs : TACSymbol.Var }
//
//    /** All commands that assign the result of a two-argument function to some lhs, i.e. all commands that are of the
//     * form "lhs := f(o1, o2)", for any operator f*/
//    interface AssigningBinaryOperatorCmd: AssigningCmd { override val lhs : TACSymbol.Var ; val op1 : TACSymbol; val op2 : TACSymbol }


    // Important to know: In kotlin, can call for any TACCmd the method getLhs to get the lhs field or null if it does not exist
    // Also, can use componentN!

    @KSerializable
    sealed class Simple :
        Spec(), AmbiSerializable, HasTACSimpleCmd, TransformableVarEntity<Simple> {

        override val cmd: TACCmd.Simple
            get() = this

        override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var): Simple = object : DefaultTACCmdMapper() {
            override fun mapVar(t: TACSymbol.Var): TACSymbol.Var = super.mapVar(f(t))
        }.map(this)

        /**
         * Checks if the Simple TAC command `uses` the variable `v`.
         */
        fun usesVar(v: TACSymbol.Var): Boolean {
           return when (this) {
                is AssigningCmd.AssignExpCmd -> this.rhs.usesVar(v)
                is AssigningCmd.AssignSha3Cmd -> this.op1 == v || this.op2 == v || this.memBaseMap == v
                is AssigningCmd.AssignSimpleSha3Cmd -> v in this.args
                is AssigningCmd.ByteLoad -> this.loc == v || this.base == v
                is AssigningCmd.ByteStore -> this.base == v || this.loc == v || this.value == v
                is AssigningCmd.ByteStoreSingle -> this.base == v || this.loc == v || this.value == v
                is AssigningCmd.WordLoad -> this.loc == v || this.base == v
                is AssigningCmd.AssignMsizeCmd -> false
                is AssigningCmd.AssignGasCmd -> false
                is AssigningCmd.AssignHavocCmd -> false
                is LabelCmd -> false
                is WordStore -> this.base == v || this.loc == v || this.value == v
                is ByteLongCopy -> this.dstOffset == v
                        || this.srcOffset == v
                        || this.srcBase == v
                        || this.length == v
                is JumpCmd -> false
                is JumpiCmd -> this.cond == v
                is JumpdestCmd -> false
                is LogCmd -> this.memBaseMap == v || v in this.args
                is SummaryCmd -> v in this.summ.variables
                is AnnotationCmd -> v in this.mentionedVariables
                is CallCore -> this.to == v
                        || this.gas == v
                        || this.inOffset == v
                        || this.inSize == v
                        || this.value == v
                        || this.outSize == v
                        || this.outOffset == v
                is ReturnCmd -> this.memBaseMap == v || this.o1 == v || this.o2 == v
                is ReturnSymCmd -> this.o == v
                is RevertCmd -> this.o1 == v || this.o2 == v || this.base == v
                is AssumeCmd -> this.cond == v
                is AssumeExpCmd -> this.cond.usesVar(v)
                is AssumeNotCmd -> this.cond == v
                is AssertCmd -> this.o == v
                NopCmd -> false
            }
        }

        fun subExprs(): List<TACExpr> =
            exprs().flatMap { it.nestedSubexprs() }

        /** Returns the expressions that appear in this command as a list. (Includes symbols, which are wrapped in
         * [TACExpr.Sym].)
         */
        fun exprs(): List<TACExpr> =
            when (this) {
                NopCmd,
                is JumpCmd,
                is JumpdestCmd,
                is AnnotationCmd -> emptyList()
                is AssertCmd -> listOf(o.asSym())
                is AssigningCmd.AssignExpCmd -> listOf(lhs.asSym(), rhs)
                is AssigningCmd.AssignGasCmd -> listOf(lhs.asSym())
                is AssigningCmd.AssignHavocCmd -> listOf(lhs.asSym())
                is AssigningCmd.AssignMsizeCmd -> listOf(lhs.asSym())
                is AssigningCmd.AssignSha3Cmd -> listOf(lhs.asSym(), op1.asSym(), op2.asSym(), memBaseMap.asSym())
                is AssigningCmd.AssignSimpleSha3Cmd -> listOf(lhs.asSym()) + args.map { it.asSym() }
                is AssigningCmd.ByteLoad -> listOf(lhs.asSym(), loc.asSym(), base.asSym())
                is AssigningCmd.ByteStore -> listOf(loc.asSym(), value.asSym(), base.asSym())
                is AssigningCmd.ByteStoreSingle -> listOf(loc.asSym(), value.asSym(), base.asSym())
                is AssigningCmd.WordLoad -> listOf(lhs.asSym(), loc.asSym(), base.asSym())
                is AssumeCmd -> listOf(cond.asSym())
                is AssumeExpCmd -> listOf(cond)
                is AssumeNotCmd -> listOf(cond.asSym())
                is ByteLongCopy -> listOf(dstOffset.asSym(), srcOffset.asSym(), length.asSym(), dstBase.asSym(), srcBase.asSym())
                is CallCore -> listOf(to.asSym(), gas.asSym(), inOffset.asSym(), inSize.asSym(), inBase.asSym())
                is JumpiCmd -> listOf(cond.asSym())
                is LabelCmd -> listOf()
                is LogCmd -> args.map { it.asSym() } + listOf(memBaseMap.asSym())
                is ReturnCmd -> listOf(o1.asSym(), o2.asSym(), memBaseMap.asSym())
                is ReturnSymCmd -> listOf(o.asSym())
                is RevertCmd -> listOf(o1.asSym(), o2.asSym(), base.asSym())
                is SummaryCmd -> summ.variables.map { it.asSym() } // TODO: do we want to treat expressions tooq?
                is WordStore -> listOf(loc.asSym(), value.asSym(), base.asSym())
            }

        companion object {
            fun <T: Serializable> AnnotationCmd(metaKey: MetaKey<T>, d : T, meta: MetaMap = MetaMap()) = AnnotationCmd(AnnotationCmd.Annotation(metaKey, d), meta)
        }

        abstract override fun withMeta(metaMap: MetaMap) : Simple

        /**
         * All commands that, assign, i.e., give a fresh value to a variable, i.e., have a left-hand-side, i.e.,
         * introduce a fresh variable in SSA, should inherit from this class.
         *
         * Note that the [getLhs] method depends on which classes inherit from this -- can lead to subtle bugs if not
         *  done right. (e.g. see git SHA 33cd2371b7967176c61daf7cf3647d269eeee9bb for such a bug fix)
         *
         *  TODO: question: should the *Store commands also inherit from this?
         */
        @KSerializable
        sealed class AssigningCmd : Simple() {
            abstract val lhs: TACSymbol.Var
            // TODO (CERT-8094): move this to the type checker
            abstract val assignedTag: Tag?
            companion object {
                fun AssignSignextendCmd(
                    lhs: TACSymbol.Var,
                    op1: TACSymbol,
                    op2: TACSymbol,
                    meta: MetaMap = MetaMap()
                ) = AssignExpCmd(
                    lhs = lhs,
                    rhs = TACExpr.BinOp.SignExtend(
                        o1 = op1.asSym(),
                        o2 = op2.asSym()
                    ),
                    meta = meta
                )
            }

            @KSerializable
            data class AssignExpCmd(
                override val lhs: TACSymbol.Var,
                val rhs: TACExpr,
                override val meta: MetaMap = MetaMap()
            ) : AssigningCmd() {
                override val assignedTag: Tag?
                    get() = rhs.tag

                override fun argString(): String = "$lhs $rhs"
                override fun toString(): String = super.toString() // opt out of generated toString

                override fun withMeta(metaMap: MetaMap): AssignExpCmd = this.copy(meta = metaMap)

                constructor(lhs: TACSymbol.Var, rhs: TACSymbol, meta: MetaMap = MetaMap()) : this(
                    lhs,
                    rhs.asSym(),
                    meta
                ) //assign Sym

                constructor(
                    lhs: TACSymbol.Var,
                    op: TACExpr,
                    oneOpExpFactory: (TACExpr) -> TACExpr,
                    meta: MetaMap = MetaMap()
                ) : this(lhs, oneOpExpFactory(op), meta) //assign unary expr

                constructor(
                    lhs: TACSymbol.Var,
                    op1: TACSymbol,
                    op2: TACSymbol,
                    twoOpExpFactory: (TACExpr, TACExpr) -> TACExpr,
                    meta: MetaMap = MetaMap()
                ) : this(lhs, twoOpExpFactory(op1.asSym(), op2.asSym()), meta) //assign binary expr

                constructor(
                    lhs: TACSymbol.Var,
                    op1: TACExpr,
                    op2: TACExpr,
                    twoOpExpFactory: (TACExpr, TACExpr) -> TACExpr,
                    meta: MetaMap = MetaMap()
                ) : this(lhs, twoOpExpFactory(op1, op2), meta) //assign binary expr

                constructor(
                    lhs: TACSymbol.Var,
                    op1: TACExpr,
                    op2: TACExpr,
                    op3: TACExpr,
                    threeOpExpFactory: (TACExpr, TACExpr, TACExpr) -> TACExpr,
                    meta: MetaMap = MetaMap()
                ) : this(lhs, threeOpExpFactory(op1, op2, op3), meta)   // assign ternary expr

                constructor(
                    lhs: TACSymbol.Var,
                    opLs: List<TACExpr>,
                    vecExpFactory: (List<TACExpr>) -> TACExpr,
                    meta: MetaMap = MetaMap()
                ) : this(lhs, vecExpFactory(opLs), meta) //assign vec expr

                constructor(lhs: TACSymbol.Var, f: TACExpr.TACFunctionSym, ops: List<TACExpr>,
                            meta: MetaMap = MetaMap()) : this(
                    lhs,
                    TACExprFactUntyped.Apply(f, ops),
                    meta
                ) //assign application expr
            }

            // TODO: Remove all default values for parameters!
            @KSerializable
            data class AssignSha3Cmd(
                override val lhs: TACSymbol.Var,
                val op1: TACSymbol, // start
                val op2: TACSymbol, // len
                val memBaseMap: TACSymbol.Var = TACKeyword.MEMORY.toVar(),
                override val meta: MetaMap = MetaMap()
            ) : AssigningCmd(), SingletonLongRead {
                override val assignedTag: Tag
                    get() = Tag.Bit256

                override fun argString(): String = "$lhs $op1 $op2 $memBaseMap"
                override val sourceOffset: TACSymbol
                    get() = op1
                override val sourceBase: TACSymbol.Var
                    get() = memBaseMap
                override val length: TACSymbol
                    get() = op2

                override fun toString(): String = super.toString() // opt out of generated toString

                override fun withMeta(metaMap: MetaMap): AssignSha3Cmd = this.copy(meta = metaMap)
            }

            /**
             * This command is (almost?) always the result of translating a [AssignSha3Cmd] command. The [length] is
             * a representation of the length parameter of that command, that is, it represents the length of the original
             * buffer being hashed.
             *
             * [length] is the *lower* bound on the logical length of the data in [args]. When can this discrepancy appear?
             * 1. When decomposing an `abi.encodePacked`, the [analysis.hash.DisciplinedHashModel] will insert extra marker
             * arguments to encode sub-word start locations.
             * 2. Similarly, when decomposing an `abi.encodePacked(uint8, uint8)` the logical buffer length will be 16,
             * but we will have two full-width words as arguments in [args].
             *
             * Thus, only interpret [length] as meaningful if you are keep the above caveats in mind.
             */
            @KSerializable
            data class AssignSimpleSha3Cmd(
                override val lhs: TACSymbol.Var,
                val length: TACSymbol.Const,
                val args: List<TACSymbol>,
                override val meta: MetaMap = MetaMap()
            ) : AssigningCmd() {
                override val assignedTag: Tag
                    get() = Tag.Bit256

                override fun argString(): String = "$lhs [length = $length]${args.joinToString(" ")}"
                override fun toString(): String = super.toString() // opt out of generated toString

                override fun withMeta(metaMap: MetaMap) = this.copy(meta = metaMap)
            }

            @KSerializable
            data class ByteLoad(
                override val lhs: TACSymbol.Var,
                override val loc: TACSymbol,
                override val base: TACSymbol.Var,
                override val meta: MetaMap = MetaMap()
            ) : AssigningCmd(), DirectMemoryAccessCmd { //tacM (load with overlapping)
                override val assignedTag: Tag
                    get() = Tag.Bit256

                override fun argString(): String = "$lhs $loc $base"
                override fun toString(): String = super.toString() // opt out of generated toString
                override fun withMeta(metaMap: MetaMap) = this.copy(meta = metaMap)
            }

            @KSerializable
            data class ByteStore(
                override val loc: TACSymbol,
                val value: TACSymbol,
                override val base: TACSymbol.Var,
                override val meta: MetaMap = MetaMap()
            ) : AssigningCmd(), DirectMemoryAccessCmd { //tacM (store with overlapping)
                init {
                    check(loc.tag.isSubtypeOf(Tag.Int) || loc.tag.isSubtypeOf(skeySort)) {
                        "$this requires loc to be an int or skey"
                    }
                    check(value.tag.isSubtypeOf(Tag.Int)) { "$this requires values to be numeric, but got ${value.tag}" }
                }
                override val assignedTag: Tag
                    get() = Tag.ByteMap
                override val lhs: TACSymbol.Var get() = base

                override fun argString(): String = "$loc $value $base"
                override fun toString(): String = super.toString() // opt out of generated toString
                override fun withMeta(metaMap: MetaMap) = this.copy(meta = metaMap)
            }

            @KSerializable
            data class ByteStoreSingle(
                override val loc: TACSymbol,
                val value: TACSymbol,
                override val base: TACSymbol.Var,
                override val meta: MetaMap = MetaMap()
            ) : AssigningCmd(), DirectMemoryAccessCmd {
                override val assignedTag: Tag
                    get() = Tag.ByteMap
                override val lhs: TACSymbol.Var get() = base

                override fun argString(): String = "$loc $value $base"
                override fun toString(): String = super.toString() // opt out of generated toString
                override fun withMeta(metaMap: MetaMap) = this.copy(meta = metaMap)
            }

            @KSerializable
            data class WordLoad(
                override val lhs: TACSymbol.Var,
                override val loc: TACSymbol,
                override val base: TACSymbol.Var,
                override val meta: MetaMap = MetaMap()
            ) : AssigningCmd(), StorageAccessCmd { //tacS (load without overlapping)
                override val assignedTag: Tag
                    get() = Tag.Bit256

                override fun argString(): String = "$lhs $loc $base"
                override fun toString(): String = super.toString() // opt out of generated toString
                override fun withMeta(metaMap: MetaMap) = this.copy(meta = metaMap)
            }

            @KSerializable
            @HookableOpcode("MSIZE")
            data class AssignMsizeCmd(@OpcodeOutput override val lhs: TACSymbol.Var, override val meta: MetaMap = MetaMap()) :
                AssigningCmd() {
                override val assignedTag: Tag
                    get() = Tag.Bit256

                override fun argString(): String = "$lhs"
                override fun toString(): String = super.toString() // opt out of generated toString
                override fun withMeta(metaMap: MetaMap) = this.copy(meta = metaMap)
            }

            @KSerializable
            @HookableOpcode("GAS")
            data class AssignGasCmd(@OpcodeOutput override val lhs: TACSymbol.Var, override val meta: MetaMap = MetaMap()) :
                AssigningCmd() {
                override val assignedTag: Tag
                    get() = Tag.Bit256

                override fun argString(): String = "$lhs"
                override fun toString(): String = super.toString() // opt out of generated toString
                override fun withMeta(metaMap: MetaMap) = this.copy(meta = metaMap)
            }

            /**
             * Note: This is a bit of
             * a special case among the assigning commands since there is no right-hand side.
             *  (No big deal but sometimes exceptions have to be made for this, e.g. in
             *    [ComputeTACSummaryTransFormula.TACSummaryTransFormula.AssignmentSummary])
             */
            @KSerializable
            data class AssignHavocCmd(override val lhs: TACSymbol.Var, override val meta: MetaMap = MetaMap()) :
                AssigningCmd() {
                override val assignedTag: Tag
                    get() = lhs.tag

                override fun argString(): String = "$lhs"
                override fun toString(): String = super.toString() // opt out of generated toString
                override fun withMeta(metaMap: MetaMap) = this.copy(meta = metaMap)
            }
        }

        // marker interface for "commands that don't actually do anything
        sealed interface TransientCmd

        @KSerializable
        data class LabelCmd(val _msg: String, override val meta: MetaMap = MetaMap()) : Simple(), TransientCmd {
            // _msg is original msg without "" and no conversion of hyphens ('-')
            val msg = "\"${_msg}\"" // one for tac files with ''

            override fun argString(): String = msg // one for tac files with ''
            override fun toString(): String = super.toString() // opt out of generated toString
            override fun withMeta(metaMap: MetaMap) = this.copy(meta = metaMap)
        }

        @KSerializable
        data class WordStore(
            override val loc: TACSymbol,
            val value: TACSymbol,
            override val base: TACSymbol.Var,
            override val meta: MetaMap = MetaMap()
        ) : Simple(), StorageAccessCmd { //tacS (store without overlapping) //TODO: Check if Simple(meta) should be replaced with AssigningCmd(base, meta) ?
            init {
                check(loc.tag.isSubtypeOf(Tag.Int) || loc.tag.isSubtypeOf(skeySort)) {
                    "$this requires loc to be an int or skey"
                }
                check(value.tag.isSubtypeOf(Tag.Int)) { "$this requires values to be numeric, but got ${value.tag}" }
            }
            override fun argString(): String = "$loc $value $base"
            override fun toString(): String = super.toString() // opt out of generated toString
            override fun withMeta(metaMap: MetaMap) = this.copy(meta = metaMap)

        }

        /**
         * Indicates that this command reads a range from a byte buffer, aka a "long access". The long accesses
         * performed by the command are encapsulated in the [LongAccess] objects returned from [accesses].
         */
        sealed interface LongAccesses {
            val accesses: List<LongAccess>
        }

        /**
         * Marks commands that hold a single long read.
         */
        sealed interface SingletonLongRead : LongAccesses {
            val sourceOffset: TACSymbol
            val sourceBase: TACSymbol.Var
            val length: TACSymbol

            class AccessHolder(
                override val offset: TACSymbol, override val base: TACSymbol.Var, override val length: TACSymbol
            ) : LongAccess {
                override val isWrite: Boolean
                    get() = false
            }

            override val accesses: List<LongAccess>
                get() = listOf(AccessHolder(sourceOffset, sourceBase, length))
        }

        /**
         * Indicates that this command long copies from one buffer to another. This (necessarily) extends the [LongAccesses]
         * interface, as the input and outputs of the long copy are themselves a [LongAccess] into the input/output buffers.
         *
         * Note that TAC currently only has commands that do at most one [LongCopy], so this interface doesn't use a list
         * (unlike [LongAccesses]).
         *
         * It is an invariant that the [LongAccess] objects returned by this command's [LongAccesses.accesses] must
         * be consistent with this [LongCopy]. That is, for `copy.source`, there must be a [LongAccess] `l` such that:
         * 1. l.offset = copy.source.offset
         * 2. l.base = copy.source.base
         * 3. l.length = copy.length
         * 4. l.isWrite = false
         *
         * And similarly for `copy.dest`. This is made easy by simply using the [LongCopy.toLongAccesses] function.
         */
        sealed interface WithLongCopy : LongAccesses {
            val copy: LongCopy
        }

        /**
         * Describes the input/output of a long copy operation: an [offset] at some location in the buffer [base].
         *
         * The length of the copy, and whether this is a read or a write, is given by the context in which this [CopyOperand]
         * is accessed (see [LongCopy] below).
         */
        interface CopyOperand {
            val offset: TACSymbol
            val base: TACSymbol.Var
        }

        /**
         * An extension of a [CopyOperand] to describe a long access in isolation, i.e., it includes the the [length]
         * of the access, along with the whether the access is reading or writing ([isWrite]).
         */
        interface LongAccess : CopyOperand {
            val length: TACSymbol
            val isWrite: Boolean
        }

        /**
         * Describes long copy performed by a command. Effectively, this pairs together two
         * [LongAccess] objects, and indicates that one is being copied into the other. That is,
         * we are copying [length] bytes from the location in some buffer indicated by [source] to the
         * location indicated by [dest].
         */
        interface LongCopy {
            val source: CopyOperand
            val dest: CopyOperand

            val length: TACSymbol

            /**
             * Retrieve information about the long accesses represented by this long copy, effectively
             * "unpairing" them information.
             */
            fun toLongAccesses() = listOf(
                inputLongAccess,
                outputLongAccess
            )

            /**
             * Retrieve information about the read part of this long copy as a [LongAccess]
             */
            val inputLongAccess : LongAccess get() = object : LongAccess {
                override val length: TACSymbol
                    get() = this@LongCopy.length
                override val isWrite: Boolean
                    get() = false
                override val offset: TACSymbol
                    get() = source.offset
                override val base: TACSymbol.Var
                    get() = source.base
            }

            /**
             * Retrieve information about the write part of this long copy as a [LongAccess]
             */
            val outputLongAccess : LongAccess get() = object : LongAccess {
                override val length: TACSymbol
                    get() = this@LongCopy.length
                override val isWrite: Boolean
                    get() = true
                override val offset: TACSymbol
                    get() = dest.offset
                override val base: TACSymbol.Var
                    get() = dest.base
            }
        }

        @KSerializable
        data class ByteLongCopy(
            val dstOffset: TACSymbol,
            val srcOffset: TACSymbol,
            val length: TACSymbol,
            val dstBase: TACSymbol.Var,
            val srcBase: TACSymbol.Var,
            override val meta: MetaMap = MetaMap()
        ) : Simple(), WithLongCopy {
            override fun argString(): String = "$dstOffset $srcOffset $length $dstBase $srcBase"
            override fun toString(): String = super.toString() // opt out of generated toString
            override fun withMeta(metaMap: MetaMap) = this.copy(meta = metaMap)

            override val accesses: List<LongAccess>
                get() = copy.toLongAccesses()

            override val copy get() = object : LongCopy {
                override val source: CopyOperand
                    get() = object : CopyOperand {
                        override val offset: TACSymbol
                            get() = srcOffset
                        override val base: TACSymbol.Var
                            get() = srcBase
                    }
                override val dest: CopyOperand
                    get() = object : CopyOperand {
                        override val offset: TACSymbol
                            get() = dstOffset
                        override val base: TACSymbol.Var
                            get() = dstBase
                    }
                override val length: TACSymbol
                    get() = this@ByteLongCopy.length

            }
        }

        @KSerializable
        data class JumpCmd(val dst: NBId, override val meta: MetaMap = MetaMap()) : Simple() {
            override fun argString(): String = "$dst"
            fun withDst(dst: NBId) = JumpCmd(dst, meta)

            override fun withMeta(metaMap: MetaMap) = this.copy(meta = metaMap)
        }

        @KSerializable
        data class JumpiCmd(
            val dst: NBId,
            val elseDst: NBId,
            val cond: TACSymbol,
            override val meta: MetaMap = MetaMap()
        ) : Simple() {
            override fun argString(): String = "$dst $elseDst $cond"
            override fun toString(): String = super.toString() // opt out of generated toString
            override fun withMeta(metaMap: MetaMap) = this.copy(meta = metaMap)
        }

        @KSerializable
        data class JumpdestCmd(val startPC: NBId, override val meta: MetaMap = MetaMap()) : Simple(), TransientCmd {
            override fun argString(): String = "$startPC"
            override fun toString(): String = super.toString() // opt out of generated toString
            override fun withMeta(metaMap: MetaMap) = this.copy(meta = metaMap)
        }

        @KSerializable
        data class LogCmd(
            val args: List<TACSymbol>, val memBaseMap: TACSymbol.Var = TACKeyword.MEMORY.toVar(),
            override val meta: MetaMap = MetaMap()
        ) : Simple(), SingletonLongRead {
            override fun argString(): String = args.joinToString(" ") + " " + memBaseMap // TODO
            override val sourceOffset: TACSymbol
                get() = args[0]
            override val sourceBase: TACSymbol.Var
                get() = memBaseMap
            override val length: TACSymbol
                get() = args[1]

            override fun toString(): String = super.toString() // opt out of generated toString
            override fun withMeta(metaMap: MetaMap) = this.copy(meta = metaMap)
        }

        @KSerializable
        data class SummaryCmd(
            val summ: TACSummary,
            override val meta : MetaMap = MetaMap()
        ) : Simple() {
            override fun toString(): String = super.toString() // opt out of generated toString
            override fun withMeta(metaMap: MetaMap): SummaryCmd {
                return this.copy(meta = metaMap)
            }
        }

        @KSerializable
        data class AnnotationCmd(
                val annot: Annotation<*>, override val meta: MetaMap = MetaMap()
        ) : Simple(), TransientCmd {

            constructor(metaKey: MetaKey<Nothing>) : this(annot = Annotation(metaKey))

            @KSerializable(with = Annotation.Serializer::class)
            data class Annotation<T: Serializable>(val k: MetaKey<T>, val v: T) : Serializable {
                @OptIn(ExperimentalSerializationApi::class)
                object Serializer : KSerializer<Annotation<*>> {
                    private val entrySerializer = MetaMap.EntrySerializationSurrogate.serializer()
                    override val descriptor: SerialDescriptor = SerialDescriptor("AnnotationCmd.Annotation", entrySerializer.descriptor)

                    override fun serialize(encoder: Encoder, value: Annotation<*>) =
                        encoder.encodeSerializableValue(
                            entrySerializer,
                            MetaMap.EntrySerializationSurrogate(value.k, value.v)
                        )

                    @Suppress("Treapability")
                    override fun deserialize(decoder: Decoder): Annotation<*> =
                        decoder.decodeSerializableValue(entrySerializer)
                            .let { (k, v) ->
                                check(k.typ.isInstance(v)) { "type of $v does not match $k" }
                                Annotation(k.uncheckedAs(), v)
                            }
                }

                val mentionedVariables get() = if (v is WithSupport) {
                    v.support
                } else {
                    setOf()
                }
            }

            companion object {
                fun Annotation(k: MetaKey<Nothing>) = Annotation(k.uncheckedAs<MetaKey<Serializable>>(), MetaMap.nothing)

                val ELLIDED = MetaKey.Nothing("tac.cmd.filtered")

                fun <T: Serializable> Pair<MetaKey<T>, T>.toAnnotation(b: Boolean) = if(b) {
                    AnnotationCmd(this.first, this.second)
                } else {
                    AnnotationCmd(ELLIDED)
                }
            }

            val mentionedVariables get() = annot.mentionedVariables

            override fun withMeta(metaMap: MetaMap) = this.copy(meta = metaMap)

            fun <T: Serializable> check(k: MetaKey<T>, p: (T) -> Boolean): Boolean = this.bind(k, p) == true

            override fun toString(): String {
                return "${super.toString()} ${annot.toString()}"
            }

        }

        fun <T: Serializable, R> bind(metaKey: MetaKey<T>, f: (T) -> R) : R? =
            if(this is AnnotationCmd && this.annot.k == metaKey) {
                f(metaKey.typ.cast(this.annot.v))
            } else {
                null
            }

        fun <R> bind(metaKey: MetaKey<Nothing>, f: () -> R) : R? =
            if(this is AnnotationCmd && this.annot.k == metaKey) {
                f()
            } else {
                null
            }

        fun <T : Serializable> maybeAnnotation(metaKey: MetaKey<T>): T? = bind(metaKey) { it }

        fun maybeAnnotation(metaKey: MetaKey<Nothing>): Boolean = (this as? AnnotationCmd)?.annot?.k == metaKey

        @KSerializable
        data class CallCore(
            val to: TACSymbol,
            val gas: TACSymbol,
            val inOffset: TACSymbol,
            val inSize: TACSymbol,
            val inBase: TACSymbol.Var = TACKeyword.MEMORY.toVar(),
            val outOffset: TACSymbol,
            val outSize: TACSymbol,
            val outBase: TACSymbol.Var = TACKeyword.MEMORY.toVar(),
            val callType: TACCallType,
            val value: TACSymbol,
            override val meta: MetaMap = MetaMap()
        ) : Simple(), WithLongCopy {
            override fun argString(): String =
                "$to $gas $inOffset $inSize $inBase $outOffset $outSize $outBase $callType $value"

            override fun toString(): String = super.toString() // opt out of generated toString
            override fun withMeta(metaMap: MetaMap) = this.copy(meta = metaMap)

            /**
             * Returns an expression for the whole 32 bytes word containing the sigHash in the first 4 bytes
             */
            fun getSigHashExpr() : TACExpr {
                return TACExpr.Select(inBase.asSym(), inOffset.asSym())
            }

            private data class CallCoreAccess(
                override val offset: TACSymbol,
                override val base: TACSymbol.Var,
                override val length: TACSymbol,
                override val isWrite: Boolean
            ) : LongAccess

            private val calldataInput : LongAccess get() = CallCoreAccess(
                base = inBase,
                offset = inOffset,
                length = inSize,
                isWrite = false
            )

            override val copy: LongCopy
                get() = object : LongCopy {
                    override val source: CopyOperand
                        get() = object : CopyOperand {
                            override val offset: TACSymbol
                                get() = TACSymbol.Zero
                            override val base: TACSymbol.Var
                                get() = TACKeyword.RETURNDATA.toVar()
                        }
                    override val dest: CopyOperand
                        get() = object : CopyOperand {
                            override val offset: TACSymbol
                                get() = outOffset
                            override val base: TACSymbol.Var
                                get() = outBase
                        }
                    override val length: TACSymbol
                        get() = outSize

                }

            /*
             * Technically we're ignoring the output of the calldata input, when we write into the
             * calldata of the callee. Unlike with returndata however, we don't (statically) know the
             * base map (due to call indexing) and there is little need for this information, so we just ignore it.
             */
            override val accesses: List<LongAccess>
                get() = copy.toLongAccesses() + calldataInput
        }

        @KSerializable
        data class ReturnCmd(
            val o1: TACSymbol, // offset
            val o2: TACSymbol, // size
            val memBaseMap: TACSymbol.Var = TACKeyword.MEMORY.toVar(),
            override val meta: MetaMap = MetaMap()
        ) : Simple(), SingletonLongRead {
            override fun argString(): String = "$o1 $o2 $memBaseMap"
            override val sourceOffset: TACSymbol
                get() = o1
            override val sourceBase: TACSymbol.Var
                get() = memBaseMap
            override val length: TACSymbol
                get() = o2

            override fun toString(): String = super.toString() // opt out of generated toString
            override fun withMeta(metaMap: MetaMap) = this.copy(meta = metaMap)
        }

        @KSerializable
        data class ReturnSymCmd(
            val o: TACSymbol,
            override val meta: MetaMap = MetaMap()
        ) : Simple() {
            override fun argString(): String = "$o"
            override fun toString(): String = super.toString() // opt out of generated toString
            override fun withMeta(metaMap: MetaMap) = this.copy(meta = metaMap)
        }

        @KSerializable
        data class RevertCmd(
            val o1: TACSymbol,
            val o2: TACSymbol,
            val revertType: TACRevertType = TACRevertType.BENIGN,
            val base: TACSymbol.Var,
            override val meta: MetaMap = MetaMap()
        ) : Simple(), SingletonLongRead {
            override val sourceOffset: TACSymbol
                get() = o1
            override val sourceBase: TACSymbol.Var
                get() = base
            override val length: TACSymbol
                get() = o2

            override fun argString(): String = "$o1 $o2 $revertType $base"
            override fun toString(): String = super.toString() // opt out of generated toString
            override fun withMeta(metaMap: MetaMap) = this.copy(meta = metaMap)
        }

        sealed interface Assume {
            val condExpr : TACExpr
        }
        // Helper commands, not 'real' commands
        //TODO: change cond type to TACExpr
        @KSerializable
        data class AssumeCmd(val cond: TACSymbol, val msg: String, override val meta: MetaMap = MetaMap()) : Assume, Simple() {
            override fun argString(): String = "$cond \"${msg.escapeQuotes()}\""
            override fun toString(): String = super.toString() // opt out of generated toString
            override fun withMeta(metaMap: MetaMap) = this.copy(meta = metaMap)
            override val condExpr get() = cond.asSym()
            //constructor(cond : TACSymbol, meta: TACMetaInfo? = null ): this(cond.asSym(), meta)
        }

        @KSerializable
        data class AssumeExpCmd(val cond: TACExpr, override val meta: MetaMap = MetaMap()) : Assume, Simple() {
            override fun argString(): String = "$cond"
            override fun toString(): String = super.toString() // opt out of generated toString
            override fun withMeta(metaMap: MetaMap) = this.copy(meta = metaMap)
            override val condExpr get() = cond
        }

        //TODO: remove this subclass (can be encoded via AssumeCmd)
        @KSerializable
        data class AssumeNotCmd(val cond: TACSymbol, override val meta: MetaMap = MetaMap()) : Assume, Simple() {
            override fun argString(): String = "$cond"
            override fun toString(): String = super.toString() // opt out of generated toString
            override fun withMeta(metaMap: MetaMap) = this.copy(meta = metaMap)
            override val condExpr get() = TACExpr.UnaryExp.LNot(cond.asSym(), Tag.Bool)
        }

        @KSerializable
        data class AssertCmd(
            val o: TACSymbol,
            internal val description: String,
            override val meta: MetaMap = MetaMap()
        ) : Simple() {
            override fun argString(): String = "$o \"${description.escapeQuotes()}\""
            override fun toString(): String = super.toString() // opt out of generated toString
            override fun withMeta(metaMap: MetaMap) = this.copy(meta = metaMap)

            fun isViolated(model: CounterexampleModel): Boolean {
                return when (val tv = model.valueAsTACValue(this.o)) {
                    TACValue.False -> true
                    TACValue.True -> false
                    else -> {
                        throw CertoraException(
                            type = CertoraErrorType.COUNTEREXAMPLE,
                            msg = "Assert command with unexpected model assignment (`$tv`): `$this`"
                        )
                    }
                }
            }

            val msg: String get() {
                return if (meta.containsKey(TACMeta.CVL_USER_DEFINED_ASSERT)) {
                    trim(description)
                } else {
                    description
                }
            }

            companion object {
                private fun trim(description: String) = buildString {
                    /**
                     * this double quote removal should eventually be done by the compiler
                     * (by simply not adding them to the assert comment)
                     */
                    val trimmed = description.removeSurrounding("\"").trim()
                    this.append(trimmed)

                    /** mind the ordering here - we want to truncate the assert message, not the entire string */
                    if (this.length > MAX_ASSERT_LENGTH) {
                        this.setLength(MAX_ASSERT_LENGTH)
                        this.append("...")
                    }
                }

                private const val MAX_ASSERT_LENGTH = 40
            }
        }

        interface DirectMemoryAccessCmd {
            val loc: TACSymbol
            val base: TACSymbol.Var
        }

        sealed interface StorageAccessCmd {
            val loc: TACSymbol
            val base: TACSymbol.Var
        }

        @KSerializable
        object NopCmd : Simple(), TransientCmd {
            override fun hashCode() = hashObject(this)

            override fun withMeta(metaMap: MetaMap): Simple {
                if (metaMap.isEmpty()) {
                    return this
                } else {
                    throw UnsupportedOperationException("Cannot annotate NOP")
                }
            }

            override val meta: MetaMap
                get() = MetaMap()

            override fun toString(): String = "NopCmd"
            override fun equals(other: Any?): Boolean = other is NopCmd

            fun readResolve(): Any {
                return Simple.NopCmd
            }
        }

    }

    sealed class EVM : TACCmd() {

        sealed class AssigningCmd : EVM() {
            abstract val lhs: TACSymbol.Var

            sealed class WithExprBuilder<T>(open val exprBuilder: T) : AssigningCmd() {

                abstract fun rhsAsExpr(): TACExpr

                data class AssignSymCmd(
                    override val lhs: TACSymbol.Var,
                    val rhs: TACSymbol,
                    override val meta: MetaMap
                ) : WithExprBuilder<(TACSymbol) -> TACExpr>(TACExprFactUntyped::Sym) {

                    override fun argString(): String = "$lhs $rhs"
                    override fun toString(): String = super.toString() // opt out of generated toString
                    override fun rhsAsExpr(): TACExpr = exprBuilder(rhs)
                }

                /** All commands that assign the result of a two-argument function to some lhs, i.e. all commands that are of the
                 * form "lhs := f(o1, o2)", for any operator f */
                sealed class AssigningBinaryOpCmd(
                    override val exprBuilder: (TACExpr, TACExpr) -> TACExpr,
                    override val lhs: TACSymbol.Var,
                    open val op1: TACSymbol,
                    open val op2: TACSymbol
                ) : WithExprBuilder<(TACExpr, TACExpr) -> TACExpr>(exprBuilder) {

                    override fun argString(): String = "$lhs $op1 $op2"

                    override fun rhsAsExpr(): TACExpr = exprBuilder(op1.asSym(), op2.asSym())

                    data class AssignAddCmd(
                        override val lhs: TACSymbol.Var,
                        override val op1: TACSymbol,
                        override val op2: TACSymbol,
                        override val meta: MetaMap
                    ) : AssigningBinaryOpCmd(TACExprFactUntyped::Add, lhs, op1, op2) {
                        override fun toString(): String = super.toString() // opt out of generated toString
                    }

                    data class AssignMulCmd(
                        override val lhs: TACSymbol.Var,
                        override val op1: TACSymbol,
                        override val op2: TACSymbol,
                        override val meta: MetaMap
                    ) : AssigningBinaryOpCmd(TACExprFactUntyped::Mul, lhs, op1, op2) {
                        override fun toString(): String = super.toString() // opt out of generated toString
                    }

                    data class AssignSubCmd(
                        override val lhs: TACSymbol.Var,
                        override val op1: TACSymbol,
                        override val op2: TACSymbol,
                        override val meta: MetaMap
                    ) : AssigningBinaryOpCmd(TACExprFactUntyped::Sub, lhs, op1, op2) {
                        override fun toString(): String = super.toString() // opt out of generated toString
                    }

                    data class AssignDivCmd(
                        override val lhs: TACSymbol.Var,
                        override val op1: TACSymbol,
                        override val op2: TACSymbol,
                        override val meta: MetaMap
                    ) : AssigningBinaryOpCmd(TACExprFactUntyped::Div, lhs, op1, op2) {
                        override fun toString(): String = super.toString() // opt out of generated toString
                    }

                    data class AssignSdivCmd(
                        override val lhs: TACSymbol.Var,
                        override val op1: TACSymbol,
                        override val op2: TACSymbol,
                        override val meta: MetaMap
                    ) : AssigningBinaryOpCmd(TACExprFactUntyped::SDiv, lhs, op1, op2) {
                        override fun toString(): String = super.toString() // opt out of generated toString
                    }

                    data class AssignModCmd(
                        override val lhs: TACSymbol.Var,
                        override val op1: TACSymbol,
                        override val op2: TACSymbol,
                        override val meta: MetaMap
                    ) : AssigningBinaryOpCmd(TACExprFactUntyped::Mod, lhs, op1, op2) {
                        override fun toString(): String = super.toString() // opt out of generated toString
                    }

                    data class AssignSModCmd(
                        override val lhs: TACSymbol.Var,
                        override val op1: TACSymbol,
                        override val op2: TACSymbol,
                        override val meta: MetaMap
                    ) : AssigningBinaryOpCmd(TACExprFactUntyped::SMod, lhs, op1, op2) {
                        override fun toString(): String = super.toString() // opt out of generated toString
                    }

                    data class AssignExponentCmd(
                        override val lhs: TACSymbol.Var,
                        override val op1: TACSymbol,
                        override val op2: TACSymbol,
                        override val meta: MetaMap
                    ) : AssigningBinaryOpCmd(TACExprFactUntyped::Exponent, lhs, op1, op2) {
                        override fun toString(): String = super.toString() // opt out of generated toString
                    }

                    data class AssignLtCmd(
                        override val lhs: TACSymbol.Var,
                        override val op1: TACSymbol,
                        override val op2: TACSymbol,
                        override val meta: MetaMap
                    ) : AssigningBinaryOpCmd(TACExprFactUntyped::Lt, lhs, op1, op2) {
                        override fun toString(): String = super.toString() // opt out of generated toString
                    }

                    data class AssignGtCmd(
                        override val lhs: TACSymbol.Var,
                        override val op1: TACSymbol,
                        override val op2: TACSymbol,
                        override val meta: MetaMap
                    ) : AssigningBinaryOpCmd(TACExprFactUntyped::Gt, lhs, op1, op2) {
                        override fun toString(): String = super.toString() // opt out of generated toString
                    }

                    data class AssignSltCmd(
                        override val lhs: TACSymbol.Var,
                        override val op1: TACSymbol,
                        override val op2: TACSymbol,
                        override val meta: MetaMap
                    ) : AssigningBinaryOpCmd(TACExprFactUntyped::Slt, lhs, op1, op2) {
                        override fun toString(): String = super.toString() // opt out of generated toString
                    }

                    data class AssignSgtCmd(
                        override val lhs: TACSymbol.Var,
                        override val op1: TACSymbol,
                        override val op2: TACSymbol,
                        override val meta: MetaMap = MetaMap()
                    ) : AssigningBinaryOpCmd({ e1, e2 -> TACExprFactUntyped.Slt(e2, e1) }, lhs, op1, op2) {
                        override fun toString(): String = super.toString() // opt out of generated toString
                    }

                    data class AssignEqCmd(
                        override val lhs: TACSymbol.Var,
                        override val op1: TACSymbol,
                        override val op2: TACSymbol,
                        override val meta: MetaMap
                    ) : AssigningBinaryOpCmd(TACExprFactUntyped::Eq, lhs, op1, op2) {
                        override fun toString(): String = super.toString() // opt out of generated toString
                    }

                    data class AssignAndCmd( //bitwise and
                        override val lhs: TACSymbol.Var,
                        override val op1: TACSymbol,
                        override val op2: TACSymbol,
                        override val meta: MetaMap
                    ) : AssigningBinaryOpCmd(TACExprFactUntyped::BWAnd, lhs, op1, op2) {
                        override fun toString(): String = super.toString() // opt out of generated toString
                    }

                    data class AssignOrCmd( //bitwise or
                        override val lhs: TACSymbol.Var,
                        override val op1: TACSymbol,
                        override val op2: TACSymbol,
                        override val meta: MetaMap
                    ) : AssigningBinaryOpCmd(TACExprFactUntyped::BWOr, lhs, op1, op2) {
                        override fun toString(): String = super.toString() // opt out of generated toString
                    }

                    data class AssignXOrCmd( //bitwise xor
                        override val lhs: TACSymbol.Var,
                        override val op1: TACSymbol,
                        override val op2: TACSymbol,
                        override val meta: MetaMap
                    ) : AssigningBinaryOpCmd(TACExprFactUntyped::BWXOr, lhs, op1, op2) {
                        override fun toString(): String = super.toString() // opt out of generated toString
                    }

                    data class AssignSignextendCmd(
                        override val lhs: TACSymbol.Var,
                        override val op1: TACSymbol,
                        override val op2: TACSymbol,
                        override val meta: MetaMap
                    ) : AssigningBinaryOpCmd(TACExprFactUntyped::SignExtend, lhs, op1, op2) {
                        override fun toString(): String = super.toString() // opt out of generated toString
                    }

                    /*
                When we defined these, it is op1 << op2.
                In EVM, it is op2 << op1, so decompiler must switch the order of the operands
                   */

                    data class AssignShiftLeft(
                        override val lhs: TACSymbol.Var,
                        override val op1: TACSymbol,
                        override val op2: TACSymbol, override val meta: MetaMap
                    ) : AssigningBinaryOpCmd(TACExprFactUntyped::ShiftLeft, lhs, op1, op2) {
                        override fun argString(): String = "$lhs $op1 $op2"
                        override fun toString(): String = super.toString() // opt out of generated toString
                    }

                    data class AssignShiftRightLogical(
                        override val lhs: TACSymbol.Var,
                        override val op1: TACSymbol,
                        override val op2: TACSymbol, override val meta: MetaMap
                    ) : AssigningBinaryOpCmd(TACExprFactUntyped::ShiftRightLogical, lhs, op1, op2) {
                        override fun argString(): String = "$lhs $op1 $op2"
                        override fun toString(): String = super.toString() // opt out of generated toString
                    }

                    data class AssignShiftRightArithmetical(
                        override val lhs: TACSymbol.Var,
                        override val op1: TACSymbol,
                        override val op2: TACSymbol, override val meta: MetaMap
                    ) : AssigningBinaryOpCmd(TACExprFactUntyped::ShiftRightArithmetical, lhs, op1, op2) {
                        override fun argString(): String = "$lhs $op1 $op2"
                        override fun toString(): String = super.toString() // opt out of generated toString
                    }
                }

                /** All commands that assign the result of a three-argument function to some lhs, i.e. all commands that are of the
                 * form "lhs := f(o1, o2, o3)", for any operator f */
                sealed class AssigningTernaryOpCmd(
                    override val exprBuilder: (TACExpr, TACExpr, TACExpr) -> TACExpr,
                    override val lhs: TACSymbol.Var, open val op1: TACSymbol,
                    open val op2: TACSymbol, open val op3: TACSymbol, override val meta: MetaMap
                ) : WithExprBuilder<(TACExpr, TACExpr, TACExpr) -> TACExpr>(exprBuilder) {

                    override fun argString(): String = "$lhs $op1 $op2 $op3"

                    override fun rhsAsExpr(): TACExpr = exprBuilder(op1.asSym(), op2.asSym(), op3.asSym())

                    data class AssignAddModCmd(
                        override val lhs: TACSymbol.Var,
                        override val op1: TACSymbol,
                        override val op2: TACSymbol,
                        override val op3: TACSymbol,
                        override val meta: MetaMap
                    ) : AssigningTernaryOpCmd(
                            TACExprFactUntyped::AddMod,
                        lhs,
                        op1,
                        op2,
                        op3,
                        meta
                    ) {
                        override fun toString(): String = super.toString() // opt out of generated toString
                    }

                    data class AssignMulModCmd(
                        override val lhs: TACSymbol.Var,
                        override val op1: TACSymbol,
                        override val op2: TACSymbol,
                        override val op3: TACSymbol,
                        override val meta: MetaMap
                    ) : AssigningTernaryOpCmd(
                        TACExprFactUntyped::MulMod,
                        lhs,
                        op1,
                        op2,
                        op3,
                        meta
                    ) {
                        override fun toString(): String = super.toString() // opt out of generated toString
                    }
                }

                /** All commands that assign the result of a one-argument function to some lhs, i.e. all commands that are of the
                 * form "lhs := f(o1)", for any operator f */
                sealed class AssigningUnaryOpCmd(
                    override val exprBuilder: (TACExpr) -> TACExpr,
                    override val lhs: TACSymbol.Var,
                    open val op1: TACSymbol
                ) : WithExprBuilder<(TACExpr) -> TACExpr>(exprBuilder) {

                    override fun argString(): String = "$lhs $op1"

                    override fun rhsAsExpr(): TACExpr = exprBuilder(op1.asSym())

                    data class AssignNotCmd(
                        override val lhs: TACSymbol.Var,
                        override val op1: TACSymbol,
                        override val meta: MetaMap
                    ) : AssigningUnaryOpCmd(TACExprFactUntyped::BWNot, lhs, op1) {
                        override fun toString(): String = super.toString() // opt out of generated toString
                    }
                }
            }

            data class AssignByteCmd(
                // y = (x >> (248 - i * 8)) & 0xFF
                // thanks ethervm.io

                // the result, denoted y
                override val lhs: TACSymbol.Var,
                // the byte index (using bytes 0-31), denoted i
                val op1: TACSymbol,
                // the operand, denoted x
                val op2: TACSymbol,
                override val meta: MetaMap
            ) : AssigningCmd() {
                override fun argString(): String = "$lhs $op1 $op2"
                override fun toString(): String = super.toString() // opt out of generated toString
            }

            data class AssignLibraryAddressCmd(override val lhs: TACSymbol.Var, val libraryContractId: BigInteger, override val meta: MetaMap) : AssigningCmd() {
                override fun argString(): String {
                    return "$libraryContractId"
                }

                override fun toString(): String {
                    return super.toString()
                }
            }
        }

        /**
         * A mark of all LOG commands.
         */
        sealed interface LogCmd {
            val numTopics: Int
        }
        /**
         * A mark of all CALL and CREATE commands. Those may trigger a new context.
         */
        sealed interface CallOrCreateCmd {
            val lhs: TACSymbol.Var
            val argsOffset: TACSymbol
            val argsSize: TACSymbol
            val memBaseMap: TACSymbol.Var
            val meta: MetaMap
        }

        /**
         *  A create command: CREATE (aka CREATE1) or CREATE2
         */
        sealed interface AnyCreateCmd: CallOrCreateCmd {
            fun updateLhs(newLhs: TACSymbol.Var): TACCmd.EVM
            val value: TACSymbol
        }

        /**
         * External EVM call command.
         */
        sealed interface ExtCallCmd: CallOrCreateCmd {
            val gas: TACSymbol
            val addr: TACSymbol
            val retOffset: TACSymbol
            val retSize: TACSymbol
        }

        sealed interface ExtCallCmdWithValue: ExtCallCmd {
            val value: TACSymbol
        }

        sealed interface CallOpcodeSummary

        // CALLS
        @HookableOpcode("CALL", additionalInterfaces = [CallOpcodeSummary::class])
        @OpcodeEnvironmentParam(
            paramName = "sighash",
            generator = SighashBinder::class
        )
        @OpcodeEnvironmentParam(
            paramName = "pc",
            generator = PCBinder::class
        )
        data class CallCmd(
            @OpcodeOutput override val lhs: TACSymbol.Var,
            @OpcodeParameter("gas") val o1: TACSymbol, // gas
            @OpcodeParameter("callee") val o2: TACSymbol, // callee
            @OpcodeParameter("value") val o3: TACSymbol, // value
            @OpcodeParameter("argsOffset") val o4: TACSymbol, // args offset
            @OpcodeParameter("argsSize") val o5: TACSymbol, // args size
            @OpcodeParameter("retOffset") val o6: TACSymbol, // ret offset
            @OpcodeParameter("retSize") val o7: TACSymbol, // ret size
            override val memBaseMap: TACSymbol.Var = TACKeyword.MEMORY.toVar(),
            override val meta: MetaMap
        ) : EVM(), ExtCallCmdWithValue {
            override fun argString(): String = "$lhs $gas $addr $value $argsOffset $argsSize $retOffset $retSize $memBaseMap"
            override fun toString(): String = super.toString() // opt out of generated toString

            override val gas: TACSymbol
                get() = o1
            override val addr: TACSymbol
                get() = o2
            override val value: TACSymbol
                get() = o3
            override val argsOffset: TACSymbol
                get() = o4
            override val argsSize: TACSymbol
                get() = o5
            override val retOffset: TACSymbol
                get() = o6
            override val retSize: TACSymbol
                get() = o7
        }

        @OpcodeEnvironmentParam(
            paramName = "sighash",
            generator = SighashBinder::class
        )
        @OpcodeEnvironmentParam(
            paramName = "pc",
            generator = PCBinder::class
        )
        @HookableOpcode("CALLCODE", additionalInterfaces = [CallOpcodeSummary::class])
        data class CallcodeCmd(
            @OpcodeOutput override val lhs: TACSymbol.Var,
            @OpcodeParameter("gas") val o1: TACSymbol, // gas
            @OpcodeParameter("addr") val o2: TACSymbol, // addr
            @OpcodeParameter("value") val o3: TACSymbol, // value
            @OpcodeParameter("argsOffset") val o4: TACSymbol, // argsOffset
            @OpcodeParameter("argsLength") val o5: TACSymbol, // argsLength
            @OpcodeParameter("retOffset") val o6: TACSymbol, // retOffset
            @OpcodeParameter("retLength") val o7: TACSymbol, // retLength
            override val memBaseMap: TACSymbol.Var = TACKeyword.MEMORY.toVar(),
            override val meta: MetaMap
        ) : EVM(), ExtCallCmdWithValue {

            override val gas: TACSymbol
                get() = o1
            override val addr: TACSymbol
                get() = o2
            override val value: TACSymbol
                get() = o3
            override val argsOffset: TACSymbol
                get() = o4
            override val argsSize: TACSymbol
                get() = o5
            override val retOffset: TACSymbol
                get() = o6
            override val retSize: TACSymbol
                get() = o7

            override fun argString(): String = "$lhs $gas $addr $value $argsOffset $argsSize $retOffset $retSize"
            override fun toString(): String = super.toString() // opt out of generated toString
        }

        @OpcodeEnvironmentParam(
            paramName = "sighash",
            generator = SighashBinder::class
        )
        @OpcodeEnvironmentParam(
            paramName = "pc",
            generator = PCBinder::class
        )
        @HookableOpcode("DELEGATECALL", additionalInterfaces = [CallOpcodeSummary::class])
        data class DelegatecallCmd(
            @OpcodeOutput override val lhs: TACSymbol.Var,
            @OpcodeParameter("gas") val o1: TACSymbol, // gas
            @OpcodeParameter("addr") val o2: TACSymbol, // addr
            @OpcodeParameter("argsOffset") val o3: TACSymbol, // argsOffset
            @OpcodeParameter("argsLength") val o4: TACSymbol, // argsLength
            @OpcodeParameter("retOffset") val o5: TACSymbol, // retOffset
            @OpcodeParameter("retLength") val o6: TACSymbol, // retLength
            override val memBaseMap: TACSymbol.Var = TACKeyword.MEMORY.toVar(),
            override val meta: MetaMap
        ) : EVM(), ExtCallCmd {

            override val gas: TACSymbol
                get() = o1
            override val addr: TACSymbol
                get() = o2
            override val argsOffset: TACSymbol
                get() = o3
            override val argsSize: TACSymbol
                get() = o4
            override val retOffset: TACSymbol
                get() = o5
            override val retSize: TACSymbol
                get() = o6

            override fun argString(): String = "$lhs $gas $addr $argsOffset $argsSize $retOffset $retSize"
            override fun toString(): String = super.toString() // opt out of generated toString
        }

        @OpcodeEnvironmentParam(
            paramName = "sighash",
            generator = SighashBinder::class
        )
        @OpcodeEnvironmentParam(
            paramName = "pc",
            generator = PCBinder::class
        )
        @HookableOpcode("STATICCALL", additionalInterfaces = [CallOpcodeSummary::class])
        data class StaticcallCmd(
            @OpcodeOutput override val lhs: TACSymbol.Var,
            @OpcodeParameter("gas") val o1: TACSymbol, // gas
            @OpcodeParameter("addr") val o2: TACSymbol, // addr
            @OpcodeParameter("argsOffset") val o3: TACSymbol, // argsOffset
            @OpcodeParameter("argsLength") val o4: TACSymbol, // argsLength
            @OpcodeParameter("retOffset") val o5: TACSymbol, // retOffset
            @OpcodeParameter("retLength") val o6: TACSymbol, // retLength
            override val memBaseMap: TACSymbol.Var = TACKeyword.MEMORY.toVar(),
            override val meta: MetaMap
        ) : EVM(), ExtCallCmd {

            override val gas: TACSymbol
                get() = o1
            override val addr: TACSymbol
                get() = o2
            override val argsOffset: TACSymbol
                get() = o3
            override val argsSize: TACSymbol
                get() = o4
            override val retOffset: TACSymbol
                get() = o5
            override val retSize: TACSymbol
                get() = o6

            override fun argString(): String = "$lhs $gas $addr $argsOffset $argsSize $retOffset $retSize $memBaseMap"
            override fun toString(): String = super.toString() // opt out of generated toString
        }

        @HookableOpcode("CREATE1")
        @OpcodeEnvironmentParam(
            paramName = "pc",
            generator = PCBinder::class
        )
        data class CreateCmd(
            @OpcodeOutput override val lhs: TACSymbol.Var,
            @OpcodeParameter override val value: TACSymbol,
            @OpcodeParameter val offset: TACSymbol,
            @OpcodeParameter val length: TACSymbol,
            override val memBaseMap: TACSymbol.Var = TACKeyword.MEMORY.toVar(),
            override val meta: MetaMap
        ) : EVM(), AnyCreateCmd {
            override fun argString(): String = "$lhs $value $offset $length $memBaseMap"
            override fun toString(): String = super.toString() // opt out of generated toString
            override fun updateLhs(newLhs: TACSymbol.Var) = this.copy(lhs = newLhs)
            override val argsOffset: TACSymbol
                get() = offset
            override val argsSize: TACSymbol
                get() = length

        }

        @HookableOpcode("CREATE2")
        @OpcodeEnvironmentParam(
            paramName = "pc",
            generator = PCBinder::class
        )
        data class Create2Cmd(
            @OpcodeOutput override val lhs: TACSymbol.Var,
            @OpcodeParameter override val value: TACSymbol,
            @OpcodeParameter val offset: TACSymbol,
            @OpcodeParameter val length: TACSymbol,
            @OpcodeParameter val salt: TACSymbol,
            override val memBaseMap: TACSymbol.Var = TACKeyword.MEMORY.toVar(),
            override val meta: MetaMap
        ) : EVM(), AnyCreateCmd {
            override fun argString(): String = "$lhs $value $offset $length $salt $memBaseMap"
            override fun toString(): String = super.toString() // opt out of generated toString
            override fun updateLhs(newLhs: TACSymbol.Var) = this.copy(lhs = newLhs)

            override val argsOffset: TACSymbol
                get() = offset
            override val argsSize: TACSymbol
                get() = length
        }

        sealed interface LogCmdSummary : TACSummary

        // Log commands.
        @HookableOpcode("LOG0", additionalInterfaces = [LogCmdSummary::class])
        data class EVMLog0Cmd(
            @OpcodeParameter("offset") val o1: TACSymbol,
            @OpcodeParameter("size") val o2: TACSymbol,
            override val meta: MetaMap = MetaMap()
        ) : EVM(),LogCmd {
            override fun argString(): String = "$o1 $o2"
            override fun toString(): String = super.toString() // opt out of generated toString
            override val numTopics = 0
        }
        @HookableOpcode("LOG1", additionalInterfaces = [LogCmdSummary::class])
        data class EVMLog1Cmd(
            @OpcodeParameter("offset") val o1: TACSymbol,
            @OpcodeParameter("size") val o2: TACSymbol,
            @OpcodeParameter("topic1") val t1: TACSymbol,
            override val meta: MetaMap = MetaMap()
        ) : EVM(), LogCmd {
            override fun argString(): String = "$o1 $o2 $t1"
            override fun toString(): String = super.toString() // opt out of generated toString
            override val numTopics = 1
        }
        @HookableOpcode("LOG2", additionalInterfaces = [LogCmdSummary::class])
        data class EVMLog2Cmd(
            @OpcodeParameter("offset") val o1: TACSymbol,
            @OpcodeParameter("size") val o2: TACSymbol,
            @OpcodeParameter("topic1") val t1: TACSymbol,
            @OpcodeParameter("topic2") val t2: TACSymbol,
            override val meta: MetaMap = MetaMap()
        ) : EVM(), LogCmd {
            override fun argString(): String = "$o1 $o2 $t1 $t2"
            override fun toString(): String = super.toString() // opt out of generated toString
            override val numTopics = 2
        }
        @HookableOpcode("LOG3", additionalInterfaces = [LogCmdSummary::class])
        data class EVMLog3Cmd(
            @OpcodeParameter("offset") val o1: TACSymbol,
            @OpcodeParameter("size") val o2: TACSymbol,
            @OpcodeParameter("topic1") val t1: TACSymbol,
            @OpcodeParameter("topic2") val t2: TACSymbol,
            @OpcodeParameter("topic3") val t3: TACSymbol,
            override val meta: MetaMap = MetaMap()
        ) : EVM(), LogCmd {
            override fun argString(): String = "$o1 $o2 $t1 $t2 $t3"
            override fun toString(): String = super.toString() // opt out of generated toString
            override val numTopics = 3
        }
        @HookableOpcode("LOG4", additionalInterfaces = [LogCmdSummary::class])
        data class EVMLog4Cmd(
            @OpcodeParameter("offset") val o1: TACSymbol,
            @OpcodeParameter("size") val o2: TACSymbol,
            @OpcodeParameter("topic1") val t1: TACSymbol,
            @OpcodeParameter("topic2") val t2: TACSymbol,
            @OpcodeParameter("topic3") val t3: TACSymbol,
            @OpcodeParameter("topic4") val t4: TACSymbol,
            override val meta: MetaMap = MetaMap()
        ) : EVM(), LogCmd {
            override fun argString(): String = "$o1 $o2 $t1 $t2 $t3 $t4"
            override fun toString(): String = super.toString() // opt out of generated toString
            override val numTopics = 4
        }

        // InvalidCmd, EVMRevert and EVMReturn

        object InvalidCmd : EVM() {
            override val meta: MetaMap = MetaMap()
            private fun readResolve(): Any = InvalidCmd
        }

        @HookableOpcode("REVERT")
        data class EVMRevertCmd(
            @OpcodeParameter("offset") val o1: TACSymbol,
            @OpcodeParameter("size") val o2: TACSymbol,
            val revertType: TACRevertType = TACRevertType.BENIGN,
            val base: TACSymbol.Var,
            override val meta: MetaMap = MetaMap()
        ) : EVM() {
            override fun argString(): String = "$o1 $o2 $revertType $base"
            override fun toString(): String = super.toString() // opt out of generated toString
        }

        data class EVMReturnCmd(
            val o1: TACSymbol, // offset
            val o2: TACSymbol, // size
            val memBaseMap: TACSymbol.Var = TACKeyword.MEMORY.toVar(),
            override val meta: MetaMap = MetaMap()
        ) : EVM() {
            override fun argString(): String = "$o1 $o2 $memBaseMap"
            override fun toString(): String = super.toString() // opt out of generated toString
        }

        // CALLDATA
        data class AssignCalldatasizeCmd(
            val lhs: TACSymbol.Var,
            val buffer: String = TACKeyword.CALLDATA.getName(),
            override val meta: MetaMap = MetaMap()
        ) : EVM() {
            override fun argString(): String = "$lhs" // TODO:
            override fun toString(): String = super.toString() // opt out of generated toString
        }

        data class AssignCalldataloadCmd(
            val lhs: TACSymbol.Var,
            val op1: TACSymbol,
            val buffer: String = TACKeyword.CALLDATA.getName(),
            override val meta: MetaMap = MetaMap()
        ) : EVM() {
            override fun argString(): String = "$lhs $op1 $buffer"
            override fun toString(): String = super.toString() // opt out of generated toString
        }

        data class CalldatacopyCmd(
            val op1: TACSymbol, // destOffset
            val op2: TACSymbol, // srcOffset
            val op3: TACSymbol, // length
            val memBaseMap: TACSymbol.Var = TACKeyword.MEMORY.toVar(),
            val buffer: String = TACKeyword.CALLDATA.getName(),
            override val meta: MetaMap = MetaMap()
        ) : EVM() // TODO: Check if need to change
        {
            override fun argString(): String = "$op1 $op2 $op3 $memBaseMap $buffer"
            override fun toString(): String = super.toString() // opt out of generated toString
        }

        // MEMORY
        data class MloadCmd(
            val lhs: TACSymbol.Var,
            val loc: TACSymbol,
            val isAligned32: Boolean = false,
            val memBaseMap: TACSymbol = TACKeyword.MEMORY.toVar(),
            override val meta: MetaMap = MetaMap()
        ) : EVM() {
            override fun argString(): String = "$lhs $loc $memBaseMap"
            override fun toString(): String = super.toString() // opt out of generated toString
        }

        data class MstoreCmd(
            val loc: TACSymbol,
            val rhs: TACSymbol,
            val isAligned32: Boolean = false,
            val isAligned23Val: Boolean = false,
            val memBaseMap: TACSymbol.Var = TACKeyword.MEMORY.toVar(),
            override val meta: MetaMap = MetaMap()
        ) : EVM() {
            override fun argString(): String = "$loc $rhs $memBaseMap"
            override fun toString(): String = super.toString() // opt out of generated toString
        }

        data class MbytestoreCmd(
            val loc: TACSymbol,
            val rhs: TACSymbol,
            val isAligned32: Boolean = false,
            val isAligned23Val: Boolean = false,
            val memBaseMap: TACSymbol.Var = TACKeyword.MEMORY.toVar(),
            override val meta: MetaMap = MetaMap()
        ) : EVM() {
            override fun argString(): String = "$loc $rhs $memBaseMap"
            override fun toString(): String = super.toString() // opt out of generated toString
        }

        data class McopyCmd(
            val dst: TACSymbol,
            val src: TACSymbol,
            val len: TACSymbol,
            val memBaseMap: TACSymbol.Var = TACKeyword.MEMORY.toVar(),
            override val meta: MetaMap = MetaMap()
        ) : EVM() {
            override fun argString(): String = "$dst $src $len $memBaseMap"
            override fun toString(): String = super.toString() // opt out of generated toString
        }

        // STORAGE
        @HookableOpcode("ALL_SLOAD", "AllSload", true)
        data class SloadCmd(
            @OpcodeOutput val lhs: TACSymbol.Var,
            @OpcodeParameter("loc") val loc: TACSymbol,
            val storageBaseMap: TACSymbol,
            override val meta: MetaMap = MetaMap()
        ) : EVM() {
            override fun argString(): String = "$lhs $loc $storageBaseMap"
            override fun toString(): String = super.toString() // opt out of generated toString
        }

        @HookableOpcode("ALL_SSTORE", "AllSstore", true)
        data class SstoreCmd(
            @OpcodeParameter("loc") val loc: TACSymbol,
            @OpcodeParameter("rhs") val rhs: TACSymbol,
            val storageBaseMap: TACSymbol.Var,
            override val meta: MetaMap = MetaMap()
        ) : EVM() {
            override fun argString(): String = "$loc $rhs $storageBaseMap"
            override fun toString(): String = super.toString() // opt out of generated toString
        }

        // TRANSIENT STORAGE
        @HookableOpcode("ALL_TLOAD", "AllTload", true)
        data class TloadCmd(
            @OpcodeOutput val lhs: TACSymbol.Var,
            @OpcodeParameter("loc") val loc: TACSymbol,
            val transientStorageBaseMap: TACSymbol,
            override val meta: MetaMap = MetaMap()
        ) : EVM() {
            override fun argString(): String = "$lhs $loc $transientStorageBaseMap"
            override fun toString(): String = super.toString() // opt out of generated toString
        }

        @HookableOpcode("ALL_TSTORE", "AllTstore", true)
        data class TstoreCmd(
            @OpcodeParameter("loc") val loc: TACSymbol,
            @OpcodeParameter("rhs") val rhs: TACSymbol,
            val transientStorageBaseMap: TACSymbol.Var,
            override val meta: MetaMap = MetaMap()
        ) : EVM() {
            override fun argString(): String = "$loc $rhs $transientStorageBaseMap"
            override fun toString(): String = super.toString() // opt out of generated toString
        }

        // RETURNDATA
        @HookableOpcode("RETURNDATASIZE", "Returndatasize", false)
        data class ReturndatasizeCmd(
            @OpcodeParameter("lhs") val lhs: TACSymbol.Var,
            override val meta: MetaMap = MetaMap()
        ) : EVM() {
            override fun argString(): String = "$lhs"
            override fun toString(): String = super.toString() // opt out of generated toString
        }

        data class ReturndatacopyCmd(
            val o1: TACSymbol, // destOffset
            val o2: TACSymbol, // offset
            val o3: TACSymbol, // length
            val memBaseMap: TACSymbol.Var = TACKeyword.MEMORY.toVar(),
            val buffer: String = TACKeyword.RETURNDATA.getName(),
            override val meta: MetaMap = MetaMap()
        ) : EVM() {
            override fun argString(): String = "$o1 $o2 $o3 $memBaseMap $buffer"
            override fun toString(): String = super.toString() // opt out of generated toString
        }

        // Utilities
        object StopCmd : EVM() {
            override val meta: MetaMap = MetaMap()
            private fun readResolve(): Any = StopCmd
        }

        data class AssignIszeroCmd(val lhs: TACSymbol.Var, val op1: TACSymbol, override val meta: MetaMap = MetaMap()) :
            EVM() {
            override fun argString(): String = "$lhs $op1"
            override fun toString(): String = super.toString() // opt out of generated toString
        }

        // BLOCKCHAIN
        @HookableOpcode("ADDRESS", "Address")
        data class AssignAddressCmd(@OpcodeOutput val lhs: TACSymbol.Var, override val meta: MetaMap = MetaMap()) : EVM() {
            override fun argString(): String = "$lhs"
            override fun toString(): String = super.toString() // opt out of generated toString
        }

        @HookableOpcode("BALANCE", "Balance")
        data class AssignBalanceCmd(
            @OpcodeOutput val lhs: TACSymbol.Var,
            @OpcodeParameter("addr") val op1: TACSymbol,
            override val meta: MetaMap = MetaMap()
        ) : EVM() {
            override fun argString(): String = "$lhs $op1"
            override fun toString(): String = super.toString() // opt out of generated toString
        }

        @HookableOpcode("ORIGIN", "Origin")
        data class AssignOriginCmd(@OpcodeOutput val lhs: TACSymbol.Var, override val meta: MetaMap = MetaMap()) : EVM() {
            override fun argString(): String = "$lhs"
            override fun toString(): String = super.toString() // opt out of generated toString
        }

        @HookableOpcode("CALLER")
        data class AssignCallerCmd(@OpcodeOutput val lhs: TACSymbol.Var, override val meta: MetaMap = MetaMap()) : EVM() {
            override fun argString(): String = "$lhs"
            override fun toString(): String = super.toString() // opt out of generated toString
        }

        @HookableOpcode("CALLVALUE")
        data class AssignCallvalueCmd(@OpcodeOutput val lhs: TACSymbol.Var, override val meta: MetaMap = MetaMap()) : EVM() {
            override fun argString(): String = "$lhs"
            override fun toString(): String = super.toString() // opt out of generated toString
        }

        @HookableOpcode("GASPRICE", "Gasprice")
        data class AssignGaspriceCmd(@OpcodeOutput val lhs: TACSymbol.Var, override val meta: MetaMap = MetaMap()) : EVM() {
            override fun argString(): String = "$lhs"
            override fun toString(): String = super.toString() // opt out of generated toString
        }

        @HookableOpcode("BLOCKHASH", "Blockhash")
        data class AssignBlockhashCmd(
            @OpcodeOutput val lhs: TACSymbol.Var,
            @OpcodeParameter("blockNum") val op: TACSymbol,
            override val meta: MetaMap = MetaMap()
        ) : EVM() {
            override fun argString(): String = "$lhs $op"
            override fun toString(): String = super.toString() // opt out of generated toString
        }

        @HookableOpcode("COINBASE")
        data class AssignCoinbaseCmd(@OpcodeOutput val lhs: TACSymbol.Var, override val meta: MetaMap = MetaMap()) : EVM() {
            override fun argString(): String = "$lhs"
            override fun toString(): String = super.toString() // opt out of generated toString
        }

        @HookableOpcode("TIMESTAMP")
        data class AssignTimestampCmd(@OpcodeOutput val lhs: TACSymbol.Var, override val meta: MetaMap = MetaMap()) : EVM() {
            override fun argString(): String = "$lhs"
            override fun toString(): String = super.toString() // opt out of generated toString
        }

        @HookableOpcode("NUMBER", "Number")
        data class AssignNumberCmd(@OpcodeOutput val lhs: TACSymbol.Var, override val meta: MetaMap = MetaMap()) : EVM() {
            override fun argString(): String = "$lhs"
            override fun toString(): String = super.toString() // opt out of generated toString
        }

        @HookableOpcode("DIFFICULTY", "Difficulty")
        data class AssignDifficultyCmd(@OpcodeOutput val lhs: TACSymbol.Var, override val meta: MetaMap = MetaMap()) : EVM() {
            override fun argString(): String = "$lhs"
            override fun toString(): String = super.toString() // opt out of generated toString
        }

        @HookableOpcode("GASLIMIT")
        data class AssignGaslimitCmd(@OpcodeOutput val lhs: TACSymbol.Var, override val meta: MetaMap = MetaMap()) : EVM() {
            override fun argString(): String = "$lhs"
            override fun toString(): String = super.toString() // opt out of generated toString
        }

        @HookableOpcode("CHAINID")
        data class AssignChainidCmd(@OpcodeOutput val lhs: TACSymbol.Var, override val meta: MetaMap = MetaMap()) : EVM() {
            override fun argString(): String = "$lhs"
            override fun toString(): String = super.toString() // opt out of generated toString
        }

        @HookableOpcode("SELFBALANCE", "Selfbalance")
        data class AssignSelfBalanceCmd(@OpcodeOutput val lhs: TACSymbol.Var, override val meta: MetaMap = MetaMap()) : EVM() {
            override fun argString(): String = "$lhs"
            override fun toString(): String = super.toString() // opt out of generated toString
        }

        @HookableOpcode("BASEFEE")
        data class AssignBasefeeCmd(@OpcodeOutput val lhs: TACSymbol.Var, override val meta: MetaMap = MetaMap()) : EVM() {
            override fun argString(): String = "$lhs"
            override fun toString(): String = super.toString() // opt out of generated toString
        }

        @HookableOpcode("BLOBHASH")
        data class AssignBlobhashCmd(
            @OpcodeOutput val lhs: TACSymbol.Var,
            @OpcodeParameter("index") val index: TACSymbol,
            override val meta: MetaMap = MetaMap()
        ) : EVM() {
            override fun argString(): String = "$lhs $index"
            override fun toString(): String = super.toString() // opt out of generated toString
        }

        @HookableOpcode("BLOBBASEFEE")
        data class AssignBlobbasefeeCmd(@OpcodeOutput val lhs: TACSymbol.Var, override val meta: MetaMap = MetaMap()) : EVM() {
            override fun argString(): String = "$lhs"
            override fun toString(): String = super.toString() // opt out of generated toString
        }

        @HookableOpcode("SELFDESTRUCT")
        data class SelfdestructCmd(@OpcodeParameter("addr") val o: TACSymbol, override val meta: MetaMap = MetaMap()) : EVM() {
            override fun argString(): String = "$o"
            override fun toString(): String = super.toString() // opt out of generated toString
        }

        // CODE
        @HookableOpcode("CODESIZE", "Codesize")
        data class AssignCodesizeCmd(@OpcodeOutput val lhs: TACSymbol.Var, val sz: Int, override val meta: MetaMap = MetaMap()) : EVM() {
            override fun argString(): String = "$lhs"
            override fun toString(): String = super.toString() // opt out of generated toString
        }

        @HookableOpcode("CODECOPY", "Codecopy")
        data class CodecopyCmd(
            @OpcodeParameter("destOffset")
            val op1: TACSymbol, // destOffset
            @OpcodeParameter("offset") val op2: TACSymbol, // srcOffset
            @OpcodeParameter("length") val op3: TACSymbol, // length
            val memBaseMap: TACSymbol.Var = TACKeyword.MEMORY.toVar(),
            val buffer: String = TACKeyword.CODEDATA.getName(),
            override val meta: MetaMap = MetaMap()
        ) : EVM() // TODO: Check if need to change
        {
            override fun argString(): String = "$op1 $op2 $op3 $memBaseMap $buffer"
            override fun toString(): String = super.toString() // opt out of generated toString
        }

        @HookableOpcode("EXTCODESIZE", "Extcodesize")
        data class AssignExtcodesizeCmd(
            @OpcodeOutput
            val lhs: TACSymbol.Var,
            @OpcodeParameter("addr") val op1: TACSymbol, override val meta: MetaMap = MetaMap()
        ) : EVM() {
            override fun argString(): String = "$lhs $op1"
            override fun toString(): String = super.toString() // opt out of generated toString
        }

        @HookableOpcode("EXTCODEHASH", "Extcodehash")
        data class AssignExtcodehashCmd(
            @OpcodeOutput val lhs: TACSymbol.Var,
            @OpcodeParameter("addr") val op1: TACSymbol, override val meta: MetaMap = MetaMap()
        ) : EVM() {
            override fun argString(): String = "$lhs $op1"
            override fun toString(): String = super.toString() // opt out of generated toString
        }

        @HookableOpcode("EXTCODECOPY")
        data class ExtcodecopyCmd(
            @OpcodeParameter("addr") val op1: TACSymbol, // addr
            @OpcodeParameter("destOffset") val op2: TACSymbol, // destOffset
            @OpcodeParameter("srcOffset") val op3: TACSymbol, // srcOffset
            @OpcodeParameter("length") val op4: TACSymbol, // length
            val memBaseMap: TACSymbol.Var = TACKeyword.MEMORY.toVar(),
            val buffer: String = TACKeyword.EXTCODEDATA.getName(),
            override val meta: MetaMap = MetaMap()
        ) : EVM() // TODO: CHECK IF NEED TO CHANGE
        {
            override fun argString(): String = "$op1 $op2 $op3 $op4 $memBaseMap $buffer"
            override fun toString(): String = super.toString() // opt out of generated toString
        }
    }

    sealed class Move : TACCmd() {
        sealed interface Borrow
        abstract val modifiedVars: Collection<TACSymbol.Var>

        companion object {
            protected fun refTag(ref: TACSymbol.Var): Tag {
                val refTag = ref.tag as? MoveTag.Ref
                require(refTag != null)
                return refTag.refType.toTag()
            }
        }

        data class BorrowLocCmd(
            val ref: TACSymbol.Var,
            val loc: TACSymbol.Var,
            override val meta: MetaMap = MetaMap()
        ) : Move(), Borrow {
            override fun argString(): String = "$ref $loc"
            override fun toString(): String = super.toString() // opt out of generated toString
            override val modifiedVars get() = setOf(ref)
            init {
                require(loc.tag == refTag(ref))
            }
        }

        data class ReadRefCmd(
            val dst: TACSymbol.Var,
            val ref: TACSymbol.Var,
            override val meta: MetaMap = MetaMap()
        ) : Move() {
            override fun argString(): String = "$dst $ref"
            override fun toString(): String = super.toString() // opt out of generated toString
            override val modifiedVars get() = setOf(dst)
            init {
                require(dst.tag == refTag(ref))
            }
        }

        data class WriteRefCmd(
            val ref: TACSymbol.Var,
            val src: TACSymbol.Var,
            override val meta: MetaMap = MetaMap()
        ) : Move() {
            override fun argString(): String = "$ref $src"
            override fun toString(): String = super.toString() // opt out of generated toString
            override val modifiedVars get() = setOf<TACSymbol.Var>()
            init {
                require(src.tag == refTag(ref))
            }
        }

        data class PackStructCmd(
            val dst: TACSymbol.Var,
            val srcs: List<TACSymbol.Var>,
            override val meta: MetaMap = MetaMap()
        ) : Move() {
            override fun argString(): String = "$dst $srcs"
            override fun toString(): String = super.toString() // opt out of generated toString
            override val modifiedVars get() = setOf(dst)
            init {
                val structTag = dst.tag as? MoveTag.Struct
                require(structTag != null)
                structTag.type.fields!!.forEachIndexed { i, field ->
                    require(srcs[i].tag == field.type.toTag())
                }
            }
        }

        data class UnpackStructCmd(
            val dsts: List<TACSymbol.Var>,
            val src: TACSymbol.Var,
            override val meta: MetaMap = MetaMap()
        ) : Move() {
            override fun argString(): String = "$dsts $src"
            override fun toString(): String = super.toString() // opt out of generated toString
            override val modifiedVars get() = dsts
            init {
                val structTag = src.tag as? MoveTag.Struct
                require(structTag != null)
                structTag.type.fields!!.forEachIndexed { i, field ->
                    require(dsts[i].tag == field.type.toTag())
                }
            }
        }

        data class BorrowFieldCmd(
            val dstRef: TACSymbol.Var,
            val srcRef: TACSymbol.Var,
            val fieldIndex: Int,
            override val meta: MetaMap = MetaMap()
        ) : Move(), Borrow {
            override fun argString(): String = "$dstRef $srcRef $fieldIndex"
            override fun toString(): String = super.toString() // opt out of generated toString
            override val modifiedVars get() = setOf(dstRef)
            init {
                val structTag = refTag(srcRef)
                require(structTag is MoveTag.Struct)
                require(dstRef.tag == MoveTag.Ref(structTag.type.fields!![fieldIndex].type))
            }
        }

        data class VecPackCmd(
            val dst: TACSymbol.Var,
            val srcs: List<TACSymbol.Var>,
            override val meta: MetaMap = MetaMap()
        ) : Move() {
            override fun argString(): String = "$dst $srcs"
            override fun toString(): String = super.toString() // opt out of generated toString
            override val modifiedVars get() = setOf(dst)
            init {
                val vecTag = dst.tag as? MoveTag.Vec
                require(vecTag != null)
                val elemTag = vecTag.elemType.toTag()
                srcs.forEach { src ->
                    require(src.tag == elemTag)
                }
            }
        }

        data class VecUnpackCmd(
            val dsts: List<TACSymbol.Var>,
            val src: TACSymbol.Var,
            override val meta: MetaMap = MetaMap()
        ) : Move() {
            override fun argString(): String = "$dsts $src"
            override fun toString(): String = super.toString() // opt out of generated toString
            override val modifiedVars get() = dsts
            init {
                val vecTag = src.tag as? MoveTag.Vec
                require(vecTag != null)
                val elemTag = vecTag.elemType.toTag()
                dsts.forEach { dst ->
                    require(dst.tag == elemTag)
                }
            }
        }

        data class VecLenCmd(
            val dst: TACSymbol.Var,
            val ref: TACSymbol.Var,
            override val meta: MetaMap = MetaMap()
        ) : Move() {
            override fun argString(): String = "$dst $ref"
            override fun toString(): String = super.toString() // opt out of generated toString
            override val modifiedVars get() = setOf(dst)
            init {
                require(refTag(ref) is MoveTag.Vec)
                require(dst.tag == Tag.Bit256)
            }
        }

        data class VecBorrowCmd(
            val dstRef: TACSymbol.Var,
            val srcRef: TACSymbol.Var,
            val index: TACSymbol,
            val doBoundsCheck: Boolean = true,
            override val meta: MetaMap = MetaMap()
        ) : Move(), Borrow {
            override fun argString(): String = "$dstRef $srcRef $index $doBoundsCheck"
            override fun toString(): String = super.toString() // opt out of generated toString
            override val modifiedVars get() = setOf(dstRef)
            init {
                val vecTag = refTag(srcRef)
                require(vecTag is MoveTag.Vec)
                require(dstRef.tag == MoveTag.Ref(vecTag.elemType))
                require(index.tag == Tag.Bit256)
            }
        }

        data class VecPushBackCmd(
            val ref: TACSymbol.Var,
            val src: TACSymbol.Var,
            override val meta: MetaMap = MetaMap()
        ) : Move() {
            override fun argString(): String = "$ref $src"
            override fun toString(): String = super.toString() // opt out of generated toString
            override val modifiedVars get() = setOf<TACSymbol.Var>()
            init {
                val vecTag = refTag(ref)
                require(vecTag is MoveTag.Vec)
                require(src.tag == vecTag.elemType.toTag())
            }
        }

        data class VecPopBackCmd(
            val dst: TACSymbol.Var,
            val ref: TACSymbol.Var,
            override val meta: MetaMap = MetaMap()
        ) : Move() {
            override fun argString(): String = "$dst $ref"
            override fun toString(): String = super.toString() // opt out of generated toString
            override val modifiedVars get() = setOf(dst)
            init {
                val vecTag = refTag(ref)
                require(vecTag is MoveTag.Vec)
                require(dst.tag == vecTag.elemType.toTag())
            }
        }

        data class PackVariantCmd(
            val dst: TACSymbol.Var,
            val srcs: List<TACSymbol.Var>,
            val variant: Int,
            override val meta: MetaMap = MetaMap()
        ) : Move() {
            override fun argString(): String = "$dst $srcs $variant"
            override fun toString(): String = super.toString() // opt out of generated toString
            override val modifiedVars get() = setOf(dst)
            init {
                val enumTag = dst.tag as? MoveTag.Enum
                require(enumTag != null)
                enumTag.type.variants[variant].fields!!.forEachIndexed { i, field ->
                    require(srcs[i].tag == field.type.toTag())
                }
            }
        }

        data class UnpackVariantCmd(
            val dsts: List<TACSymbol.Var>,
            val src: TACSymbol.Var,
            val variant: Int,
            val doVariantCheck: Boolean = true,
            override val meta: MetaMap = MetaMap()
        ) : Move() {
            override fun argString(): String = "$dsts $src $variant"
            override fun toString(): String = super.toString() // opt out of generated toString
            override val modifiedVars get() = dsts
            init {
                val enumTag = src.tag as? MoveTag.Enum
                require(enumTag != null)
                enumTag.type.variants[variant].fields.orEmpty().forEachIndexed { i, field ->
                    require(dsts[i].tag == field.type.toTag())
                }
            }
        }

        data class UnpackVariantRefCmd(
            val dsts: List<TACSymbol.Var>,
            val srcRef: TACSymbol.Var,
            val variant: Int,
            val doVariantCheck: Boolean = true,
            override val meta: MetaMap = MetaMap()
        ) : Move(), Borrow {
            override fun argString(): String = "$dsts $srcRef $variant"
            override fun toString(): String = super.toString() // opt out of generated toString
            override val modifiedVars get() = dsts
            init {
                val enumTag = refTag(srcRef)
                require(enumTag is MoveTag.Enum)
                enumTag.type.variants[variant].fields.orEmpty().forEachIndexed { i, field ->
                    require(refTag(dsts[i]) == field.type.toTag())
                }
            }
        }

        data class VariantIndexCmd(
            val index: TACSymbol.Var,
            val ref: TACSymbol.Var,
            override val meta: MetaMap = MetaMap()
        ) : Move() {
            override fun argString(): String = "$index $ref"
            override fun toString(): String = super.toString() // opt out of generated toString
            override val modifiedVars get() = setOf(index)
            init {
                require(index.tag == Tag.Bit256)
                val enumTag = refTag(ref)
                require(enumTag is MoveTag.Enum)
            }
        }

        data class GhostArrayBorrowCmd(
            val dstRef: TACSymbol.Var,
            val arrayRef: TACSymbol.Var,
            val index: TACSymbol.Var,
            override val meta: MetaMap = MetaMap()
        ) : Move(), Borrow {
            override fun argString(): String = "$dstRef $arrayRef $index"
            override fun toString(): String = super.toString() // opt out of generated toString
            override val modifiedVars get() = setOf(dstRef)
            init {
                val arrayTag = refTag(arrayRef)
                require(arrayTag is MoveTag.GhostArray)
                val dstRefTag = dstRef.tag as MoveTag.Ref
                require(dstRefTag.refType in arrayTag.elemTypes)
                require(index.tag == Tag.Bit256)
            }
        }

        data class HashCmd(
            val dst: TACSymbol.Var,
            val loc: TACSymbol.Var,
            val hashFamily: HashFamily,
            override val meta: MetaMap = MetaMap()
        ) : Move() {
            override fun argString(): String = "$dst $loc $hashFamily"
            override fun toString(): String = super.toString() // opt out of generated toString
            override val modifiedVars get() = setOf(dst)
            init {
                require(dst.tag == Tag.Bit256)
            }
        }

        data class EqCmd(
            val dst: TACSymbol.Var,
            val a: TACSymbol.Var,
            val b: TACSymbol.Var,
            override val meta: MetaMap = MetaMap()
        ) : Move() {
            override fun argString(): String = "$dst $a $b"
            override fun toString(): String = super.toString() // opt out of generated toString
            override val modifiedVars get() = setOf(dst)
            init {
                require(dst.tag == Tag.Bool)
                require(a.tag == b.tag)
            }
        }
    }

    @JvmName("getLhs1")
    fun getLhs(): TACSymbol.Var? {
        return when (this) {
            is Simple -> when (this) {
                is Simple.AssigningCmd -> this.lhs
                else -> null
            }
            is CVL -> when(this) {
                is CVL.ArrayLengthRead -> this.lhs
                is CVL.StringLastWord -> this.lhs
                is CVL.CopyBlockchainState -> this.lhs
                is CVL.ReadElement -> this.lhs
                is CVL.SetArrayLength -> null
                is CVL.SetBlockchainState -> null
                is CVL.WriteElement -> null // not repeating the f---ing mistakes of the bytestore...
                is CVL.AssignVMParam -> lhsVar
                is CVL.AssignBytesBlob -> this.lhs
                is CVL.CompareStorage -> lhsVar
                is CVL.ArrayCopy -> null
                is CVL.LocalAlloc -> this.lhs
                is CVL.CompareArray -> lhsVar
            }
            is EVM -> when (this) {
                is EVM.AssigningCmd -> this.lhs
                is EVM.AssignIszeroCmd -> this.lhs
                is EVM.AssignAddressCmd -> this.lhs
                is EVM.AssignBalanceCmd -> this.lhs
                is EVM.AssignOriginCmd -> this.lhs
                is EVM.AssignCallerCmd -> this.lhs
                is EVM.AssignCallvalueCmd -> this.lhs
                is EVM.AssignCalldataloadCmd -> this.lhs
                is EVM.AssignCalldatasizeCmd -> this.lhs
                is EVM.AssignCodesizeCmd -> this.lhs
                is EVM.AssignGaspriceCmd -> this.lhs
                is EVM.AssignExtcodesizeCmd -> this.lhs
                is EVM.AssignExtcodehashCmd -> this.lhs
                is EVM.AssignBlockhashCmd -> this.lhs
                is EVM.AssignCoinbaseCmd -> this.lhs
                is EVM.AssignTimestampCmd -> this.lhs
                is EVM.AssignNumberCmd -> this.lhs
                is EVM.AssignDifficultyCmd -> this.lhs
                is EVM.AssignGaslimitCmd -> this.lhs
                is EVM.AssignChainidCmd -> this.lhs
                is EVM.AssignSelfBalanceCmd -> this.lhs
                is EVM.AssignBasefeeCmd -> this.lhs
                is EVM.AssignBlobhashCmd -> this.lhs
                is EVM.AssignBlobbasefeeCmd -> this.lhs
                is EVM.MloadCmd -> this.lhs
                is EVM.SloadCmd -> this.lhs
                is EVM.TloadCmd -> this.lhs
                is EVM.CallCmd -> this.lhs
                is EVM.CallcodeCmd -> this.lhs
                is EVM.DelegatecallCmd -> this.lhs
                is EVM.StaticcallCmd -> this.lhs
                is EVM.CreateCmd -> this.lhs
                is EVM.Create2Cmd -> this.lhs
                is EVM.SelfdestructCmd -> null
                EVM.InvalidCmd -> null
                is EVM.EVMRevertCmd -> null
                is EVM.EVMReturnCmd -> null
                is EVM.CalldatacopyCmd -> null
                is EVM.MstoreCmd -> null
                is EVM.MbytestoreCmd -> null
                is EVM.McopyCmd -> null
                is EVM.SstoreCmd -> null
                is EVM.TstoreCmd -> null
                is EVM.ReturndatasizeCmd -> this.lhs
                is EVM.ReturndatacopyCmd -> null
                EVM.StopCmd -> null
                is EVM.CodecopyCmd -> null
                is EVM.ExtcodecopyCmd -> null
                is EVM.EVMLog0Cmd -> null
                is EVM.EVMLog1Cmd -> null
                is EVM.EVMLog2Cmd -> null
                is EVM.EVMLog3Cmd -> null
                is EVM.EVMLog4Cmd -> null
            }
            is Move -> error("Move commands not yet simplified")
        }
    }

    /**
     * Similar to [getLhs], but returns a variable being changed, and not just assigned to.
     */
    fun getModifiedVar(): TACSymbol.Var? {
        return when(this) {
            is Simple -> when (this) {
                is Simple.ByteLongCopy -> dstBase
                is Simple.WordStore -> base
                else -> getLhs()
            }
            is CVL -> when(this) {
                is CVL.ArrayCopy -> {
                    when(this.dst.buffer) {
                        is CVL.Buffer.CVLBuffer -> this.dst.buffer.root
                        is CVL.Buffer.EVMBuffer -> this.dst.buffer.t
                    }
                }
                is CVL.WriteElement -> target
                else -> getLhs()
            }
            is EVM -> when (this) {
                is EVM.CalldatacopyCmd -> memBaseMap
                is EVM.MstoreCmd -> memBaseMap
                is EVM.MbytestoreCmd -> memBaseMap
                is EVM.SstoreCmd -> storageBaseMap
                is EVM.TstoreCmd -> transientStorageBaseMap
                else -> getLhs()
            }
            is Move -> error("Move commands not yet simplified")
        }
    }

    /**
     * As the name says, returns all free variables on the right hand side of the given command.
     * TODO: what about commands that don't have a "right hand side"??
     *
     * Not to be confused with the similar method [getRhs].
     */
    fun getFreeVarsOfRhs(): Set<TACSymbol.Var> {
        val rhsSymbols: TreapSet<TACSymbol> = when (this) {
            is Simple.LabelCmd -> treapSetOf()
            is Simple.NopCmd -> treapSetOf()
            is Simple.AssigningCmd.AssignExpCmd -> TACExprFreeVarsCollector.getFreeVars(this.rhs)
            is Simple.AssumeExpCmd -> TACExprFreeVarsCollector.getFreeVars(this.cond)
            is EVM.AssigningCmd.AssignByteCmd -> treapSetOf(this.op1, this.op2)
            is EVM.AssigningCmd.WithExprBuilder.AssignSymCmd -> treapSetOf(this.rhs)
            is EVM.AssigningCmd.WithExprBuilder.AssigningBinaryOpCmd -> treapSetOf(this.op1, this.op2)
            is EVM.AssigningCmd.WithExprBuilder.AssigningTernaryOpCmd -> treapSetOf(this.op1, this.op2, this.op3)
            is EVM.AssigningCmd.WithExprBuilder.AssigningUnaryOpCmd.AssignNotCmd -> treapSetOf(this.op1)
            is Simple.AssigningCmd.AssignSha3Cmd -> treapSetOf(this.op1, this.op2, this.memBaseMap)
            is Simple.AssigningCmd.AssignSimpleSha3Cmd -> this.args.toTreapSet()
            is Simple.AssigningCmd.AssignMsizeCmd -> treapSetOf()
            is Simple.AssigningCmd.AssignGasCmd -> treapSetOf()
            is Simple.CallCore -> treapSetOf(this.to, this.gas, this.inOffset, this.inSize, this.value, this.outSize, this.outOffset)
            is Simple.AssigningCmd.AssignHavocCmd -> treapSetOf()
            is Simple.JumpCmd -> treapSetOf() // After jimple, can be empty
            is Simple.JumpiCmd -> treapSetOf(this.cond) // after jimple, can omit pos dst sym
            is Simple.JumpdestCmd -> treapSetOf()
            is Simple.LogCmd -> args.toTreapSet() + treapSetOf(this.memBaseMap)
            is Simple.ReturnCmd -> treapSetOf(this.o1, this.o2,this.memBaseMap)
            is Simple.ReturnSymCmd -> treapSetOf(this.o)
            is EVM.SelfdestructCmd -> treapSetOf(this.o)
            is Simple.RevertCmd -> treapSetOf(this.o1, this.o2, this.base)
            is Simple.AssumeCmd -> treapSetOf(this.cond)
            is Simple.AssumeNotCmd -> treapSetOf(this.cond)
            is Simple.AssertCmd -> treapSetOf(this.o)
            is Simple.AssigningCmd.ByteLoad -> treapSetOf(this.loc, this.base)
            is Simple.AssigningCmd.ByteStore -> treapSetOf(this.base, this.loc, this.value)
            is Simple.AssigningCmd.ByteStoreSingle -> treapSetOf(this.base, this.loc, this.value)
            is Simple.AssigningCmd.WordLoad -> treapSetOf(this.loc, this.base)
            is Simple.WordStore -> treapSetOf(this.base, this.loc, this.value)
            is Simple.ByteLongCopy -> treapSetOf(this.dstOffset, this.srcOffset, this.srcBase, this.length, this.dstBase)
            EVM.StopCmd -> treapSetOf()
            EVM.InvalidCmd -> treapSetOf()
            is EVM.EVMRevertCmd -> treapSetOf(this.o1, this.o2)
            is EVM.EVMReturnCmd -> treapSetOf(this.o1, this.o2)
            is EVM.AssignIszeroCmd -> treapSetOf(this.op1)
            is EVM.AssignAddressCmd -> treapSetOf()
            is EVM.AssignBalanceCmd -> treapSetOf(this.op1)
            is EVM.AssignOriginCmd -> treapSetOf()
            is EVM.AssignCallerCmd -> treapSetOf()
            is EVM.AssignCallvalueCmd -> treapSetOf()
            is EVM.AssignCalldataloadCmd -> treapSetOf(this.op1)
            is EVM.AssignCalldatasizeCmd -> treapSetOf()
            is EVM.AssignCodesizeCmd -> treapSetOf()
            is EVM.AssignGaspriceCmd -> treapSetOf()
            is EVM.AssignExtcodesizeCmd -> treapSetOf(this.op1)
            is EVM.AssignExtcodehashCmd -> treapSetOf(this.op1)
            is EVM.AssignBlockhashCmd -> treapSetOf(this.op)
            is EVM.AssignCoinbaseCmd -> treapSetOf()
            is EVM.AssignTimestampCmd -> treapSetOf()
            is EVM.AssignNumberCmd -> treapSetOf()
            is EVM.AssignDifficultyCmd -> treapSetOf()
            is EVM.AssignGaslimitCmd -> treapSetOf()
            is EVM.AssignChainidCmd -> treapSetOf()
            is EVM.AssignSelfBalanceCmd -> treapSetOf()
            is EVM.AssignBasefeeCmd -> treapSetOf()
            is EVM.AssignBlobhashCmd -> treapSetOf(this.index)
            is EVM.AssignBlobbasefeeCmd -> treapSetOf()
            is EVM.MloadCmd -> treapSetOf(this.loc, this.memBaseMap)
            is EVM.SloadCmd -> treapSetOf(this.loc, this.storageBaseMap)
            is EVM.TloadCmd -> treapSetOf(this.loc, this.transientStorageBaseMap)
            is EVM.CallCmd -> treapSetOf(this.o1, this.o2, this.o3, this.o4, this.o5, this.o6, this.o7, this.memBaseMap)
            is EVM.CallcodeCmd -> treapSetOf(
                this.o1,
                this.o2,
                this.o3,
                this.o4,
                this.o5,
                this.o6,
                this.o7/*,this.memBaseMap*/
            )
            is EVM.DelegatecallCmd -> treapSetOf(this.o1, this.o2, this.o3, this.o4, this.o5, this.o6/*,this.memBaseMap*/)
            is EVM.CalldatacopyCmd -> treapSetOf(this.op1, this.op2, this.op3, this.memBaseMap)
            is EVM.CodecopyCmd -> treapSetOf(this.op1, this.op2, this.op3, this.memBaseMap)
            is EVM.ExtcodecopyCmd -> treapSetOf(this.op1, this.op2, this.op3, this.op4)
            is EVM.MstoreCmd -> treapSetOf(this.loc, this.rhs, this.memBaseMap)
            is EVM.MbytestoreCmd -> treapSetOf(this.loc, this.rhs, this.memBaseMap)
            is EVM.McopyCmd -> treapSetOf(this.dst, this.src, this.len, this.memBaseMap)
            is EVM.SstoreCmd -> treapSetOf(this.loc, this.rhs, this.storageBaseMap)
            is EVM.TstoreCmd -> treapSetOf(this.loc, this.rhs, this.transientStorageBaseMap)
            is EVM.ReturndatasizeCmd -> treapSetOf()
            is EVM.ReturndatacopyCmd -> treapSetOf(this.o1, this.o2, this.o3)
            is EVM.StaticcallCmd -> treapSetOf(this.o1, this.o2, this.o3, this.o4, this.o5, this.o6, this.memBaseMap)
            is EVM.CreateCmd -> treapSetOf(this.value, this.offset, this.length)
            is EVM.Create2Cmd -> treapSetOf(this.value, this.offset, this.length, this.salt)
            is EVM.EVMLog0Cmd -> treapSetOf(this.o1,this.o2)
            is EVM.EVMLog1Cmd -> treapSetOf(this.o1,this.o2,this.t1)
            is EVM.EVMLog2Cmd -> treapSetOf(this.o1,this.o2,this.t1,this.t2)
            is EVM.EVMLog3Cmd -> treapSetOf(this.o1,this.o2,this.t1,this.t2,this.t3,)
            is EVM.EVMLog4Cmd -> treapSetOf(this.o1,this.o2,this.t1,this.t2,this.t3,this.t4)
            is Simple.AnnotationCmd -> this.mentionedVariables.toTreapSet()
            is Simple.SummaryCmd -> this.summ.variables.toTreapSet()
            is EVM.AssigningCmd.AssignLibraryAddressCmd -> treapSetOf()
            is CVL.ArrayLengthRead -> treapSetOf(this.rhs)
            is CVL.StringLastWord -> treapSetOf(this.rhs)
            is CVL.CopyBlockchainState -> treapSetOf()
            is CVL.ReadElement -> treapSetOf(this.base, this.physicalIndex)
            is CVL.SetArrayLength -> treapSetOf(this.len)
            is CVL.SetBlockchainState -> treapSetOf(this.stateVar)
            is CVL.WriteElement -> treapSetOf(this.physicalIndex, this.value)
            is CVL.AssignVMParam -> treapSetOf()
            is CVL.AssignBytesBlob -> treapSetOf(this.rhs)
            is CVL.CompareStorage -> treapSetOf(this.storage1, this.storage2)
            is CVL.ArrayCopy -> this.dst.getReferencedSyms() + this.src.getReferencedSyms()
            is CVL.LocalAlloc -> treapSetOf(this.arr)
            is CVL.CompareArray -> treapSetOf(this.left, this.right)
            is Move.BorrowFieldCmd -> treapSetOf(this.srcRef)
            is Move.BorrowLocCmd -> treapSetOf(this.loc)
            is Move.EqCmd -> treapSetOf(this.a, this.b)
            is Move.GhostArrayBorrowCmd -> treapSetOf(this.index, this.arrayRef)
            is Move.HashCmd -> treapSetOf(this.loc)
            is Move.PackStructCmd -> this.srcs.toTreapSet()
            is Move.ReadRefCmd -> treapSetOf(this.ref)
            is Move.UnpackStructCmd -> treapSetOf(this.src)
            is Move.VecBorrowCmd -> treapSetOf(this.index, this.srcRef)
            is Move.VecLenCmd -> treapSetOf(this.ref)
            is Move.VecPackCmd -> this.srcs.toTreapSet()
            is Move.VecPopBackCmd -> treapSetOf(this.ref)
            is Move.VecPushBackCmd -> treapSetOf(this.ref, this.src)
            is Move.VecUnpackCmd -> treapSetOf(this.src)
            is Move.PackVariantCmd -> this.srcs.toTreapSet()
            is Move.UnpackVariantCmd -> treapSetOf(this.src)
            is Move.UnpackVariantRefCmd -> treapSetOf(this.srcRef)
            is Move.VariantIndexCmd -> treapSetOf(this.ref)
            is Move.WriteRefCmd -> treapSetOf(this.ref, this.src)
        }
        return rhsSymbols.filterIsInstance<TACSymbol.Var>()
    }

    fun getFreeVarsOfRhsExtended() = when(this) {
            is Simple.AssigningCmd.AssignExpCmd -> rhs.getFreeVarsExtended()
            is Simple.AssigningCmd.WordLoad -> getFreeVarsOfRhs() +
                (loc as? TACSymbol.Var)?.meta?.get(TACMeta.ACCESS_PATHS)?.support.orEmpty()
            is Simple.WordStore -> getFreeVarsOfRhs() +
                (loc as? TACSymbol.Var)?.meta?.get(TACMeta.ACCESS_PATHS)?.support.orEmpty()
            else -> getFreeVarsOfRhs()
        }

    /**
     * Note this is different from [getFreeVarsOfRhs]:
     *  - also returns constants, not only variables
     *  - the "*Store*" commands, (like [TACCmd.Simple.WordStore]) cases don't return the "base" as part of the rhs
     */
    fun getRhs(): TreapSet<TACSymbol> {
        return when (this) {
            is Simple.LabelCmd -> treapSetOf()
            Simple.NopCmd -> treapSetOf()
            is Simple.AssigningCmd.AssignExpCmd -> this.rhs.subsFull.toSymSet()
            is Simple.AssumeExpCmd -> this.cond.subsFull.toSymSet()
            is Simple.AssigningCmd.AssignSha3Cmd -> treapSetOf(this.op1, this.op2, this.memBaseMap)
            is Simple.AssigningCmd.AssignSimpleSha3Cmd -> this.args.toTreapSet()
            is Simple.AssigningCmd.AssignMsizeCmd -> treapSetOf()
            is Simple.AssigningCmd.AssignGasCmd -> treapSetOf()
            is Simple.CallCore -> treapSetOf(
                this.to,
                this.gas,
                this.inOffset,
                this.inSize,
                this.outOffset,
                this.outSize,
                this.value
            )
            is Simple.AssigningCmd.AssignHavocCmd -> treapSetOf()
            is Simple.JumpCmd -> treapSetOf() // After jimple, can be empty
            is Simple.JumpiCmd -> treapSetOf(this.cond) // after jimple, can omit pos dst sym
            is Simple.JumpdestCmd -> treapSetOf()
            is Simple.LogCmd -> args.toTreapSet() + this.memBaseMap
            is Simple.ReturnCmd -> treapSetOf(this.o1, this.o2)
            is Simple.ReturnSymCmd -> treapSetOf(this.o)
            is EVM.SelfdestructCmd -> treapSetOf(this.o)
            is Simple.RevertCmd -> treapSetOf(this.o1, this.o2)
            is Simple.AssumeCmd -> treapSetOf(this.cond) //TODO: change to TACExprConstCollector.getConstantsAndVars(this.cond) if this.cond becomes TACExpr
            is Simple.AssumeNotCmd -> treapSetOf(this.cond)
            is Simple.AssertCmd -> treapSetOf(this.o)
            is Simple.AssigningCmd.ByteLoad -> treapSetOf(this.loc, this.base)
            is Simple.AssigningCmd.ByteStore -> treapSetOf(
                this.loc,
                this.value
            ) // TODO: isn't this.base part of rhs as well??
            is Simple.AssigningCmd.ByteStoreSingle -> treapSetOf(this.loc, this.value)
            is Simple.AssigningCmd.WordLoad -> treapSetOf(this.loc, this.base)
            is Simple.WordStore -> treapSetOf(this.loc, this.value)
            is Simple.ByteLongCopy -> treapSetOf(this.dstOffset, this.srcOffset, this.srcBase, this.length)
            is EVM.AssigningCmd.AssignByteCmd -> treapSetOf(this.op1, this.op2)
            is EVM.AssigningCmd.WithExprBuilder.AssignSymCmd -> treapSetOf(this.rhs)
            is EVM.AssigningCmd.WithExprBuilder.AssigningBinaryOpCmd -> treapSetOf(this.op1, this.op2)
            is EVM.AssigningCmd.WithExprBuilder.AssigningTernaryOpCmd -> treapSetOf(this.op1, this.op2, this.op3)
            is EVM.AssigningCmd.WithExprBuilder.AssigningUnaryOpCmd -> treapSetOf(this.op1)
            EVM.StopCmd -> treapSetOf()
            EVM.InvalidCmd -> treapSetOf()
            is EVM.EVMRevertCmd -> treapSetOf(this.o1, this.o2)
            is EVM.EVMReturnCmd -> treapSetOf(this.o1, this.o2)
            is EVM.AssignIszeroCmd -> treapSetOf(this.op1)
            is EVM.AssignAddressCmd -> treapSetOf()
            is EVM.AssignBalanceCmd -> treapSetOf(this.op1)
            is EVM.AssignOriginCmd -> treapSetOf()
            is EVM.AssignCallerCmd -> treapSetOf()
            is EVM.AssignCallvalueCmd -> treapSetOf()
            is EVM.AssignCalldataloadCmd -> treapSetOf(this.op1)
            is EVM.AssignCalldatasizeCmd -> treapSetOf()
            is EVM.AssignCodesizeCmd -> treapSetOf()
            is EVM.AssignGaspriceCmd -> treapSetOf()
            is EVM.AssignExtcodesizeCmd -> treapSetOf(this.op1)
            is EVM.AssignExtcodehashCmd -> treapSetOf(this.op1)
            is EVM.AssignBlockhashCmd -> treapSetOf(this.op)
            is EVM.AssignCoinbaseCmd -> treapSetOf()
            is EVM.AssignTimestampCmd -> treapSetOf()
            is EVM.AssignNumberCmd -> treapSetOf()
            is EVM.AssignDifficultyCmd -> treapSetOf()
            is EVM.AssignGaslimitCmd -> treapSetOf()
            is EVM.AssignChainidCmd -> treapSetOf()
            is EVM.AssignSelfBalanceCmd -> treapSetOf()
            is EVM.AssignBasefeeCmd -> treapSetOf()
            is EVM.AssignBlobhashCmd -> treapSetOf(this.index)
            is EVM.AssignBlobbasefeeCmd -> treapSetOf()
            is EVM.MloadCmd -> treapSetOf(this.loc, this.memBaseMap)
            is EVM.SloadCmd -> treapSetOf(this.loc, this.storageBaseMap)
            is EVM.TloadCmd -> treapSetOf(this.loc, this.transientStorageBaseMap)
            is EVM.CallCmd -> treapSetOf(this.o1, this.o2, this.o3, this.o4, this.o5, this.o6, this.o7, this.memBaseMap)
            is EVM.CallcodeCmd -> treapSetOf(
                this.o1,
                this.o2,
                this.o3,
                this.o4,
                this.o5,
                this.o6,
                this.o7/*,this.memBaseMap*/
            )
            is EVM.DelegatecallCmd -> treapSetOf(this.o1, this.o2, this.o3, this.o4, this.o5, this.o6/*,this.memBaseMap*/)
            is EVM.CalldatacopyCmd -> treapSetOf(this.op1, this.op2, this.op3, this.memBaseMap)
            is EVM.CodecopyCmd -> treapSetOf(this.op1, this.op2, this.op3, this.memBaseMap)
            is EVM.ExtcodecopyCmd -> treapSetOf(this.op1, this.op2, this.op3, this.op4)
            is EVM.MstoreCmd -> treapSetOf(this.loc, this.rhs, this.memBaseMap)
            is EVM.MbytestoreCmd -> treapSetOf(this.loc, this.rhs, this.memBaseMap)
            is EVM.McopyCmd -> treapSetOf(this.dst, this.src, this.len, this.memBaseMap)
            is EVM.SstoreCmd -> treapSetOf(this.loc, this.rhs, this.storageBaseMap)
            is EVM.TstoreCmd -> treapSetOf(this.loc, this.rhs, this.transientStorageBaseMap)
            is EVM.ReturndatasizeCmd -> treapSetOf()
            is EVM.ReturndatacopyCmd -> treapSetOf(this.o1, this.o2, this.o3)
            is EVM.StaticcallCmd -> treapSetOf(this.o1, this.o2, this.o3, this.o4, this.o5, this.o6, this.memBaseMap)
            is EVM.CreateCmd -> treapSetOf(this.value, this.offset, this.length)
            is EVM.Create2Cmd -> treapSetOf(this.value, this.offset, this.length, this.salt)
            is EVM.EVMLog0Cmd -> treapSetOf(this.o1,this.o2)
            is EVM.EVMLog1Cmd -> treapSetOf(this.o1,this.o2,this.t1)
            is EVM.EVMLog2Cmd -> treapSetOf(this.o1,this.o2,this.t1,this.t2)
            is EVM.EVMLog3Cmd -> treapSetOf(this.o1,this.o2,this.t1,this.t2,this.t3,)
            is EVM.EVMLog4Cmd -> treapSetOf(this.o1,this.o2,this.t1,this.t2,this.t3,this.t4)
            is Simple.NopCmd -> treapSetOf()
            is Simple.AnnotationCmd -> this.mentionedVariables.toTreapSet()
            is Simple.SummaryCmd -> this.summ.variables.toTreapSet()
            is EVM.AssigningCmd.AssignLibraryAddressCmd -> treapSetOf()
            is CVL.ArrayLengthRead -> treapSetOf(this.rhs)
            is CVL.StringLastWord -> treapSetOf(this.rhs)
            is CVL.CopyBlockchainState -> treapSetOf()
            is CVL.ReadElement -> treapSetOf(this.physicalIndex, this.base)
            is CVL.SetArrayLength -> treapSetOf(this.len)
            is CVL.SetBlockchainState -> treapSetOf(this.stateVar)
            is CVL.WriteElement -> treapSetOf(this.value, this.physicalIndex)
            is CVL.AssignVMParam -> treapSetOf()
            is CVL.AssignBytesBlob -> treapSetOf(this.rhs)
            is CVL.CompareStorage -> treapSetOf(this.storage1, this.storage2)
            is CVL.ArrayCopy -> this.src.getReferencedSyms() + this.dst.getReferencedSyms()
            is CVL.LocalAlloc -> treapSetOf(this.arr)
            is CVL.CompareArray ->  treapSetOf(this.left, this.right)
            is Move -> error("Move commands not yet simplified")
        }
    }
}


/**
 * @return true if this [TACCmd] stops the EVM's execution (and pops up the EVM stack)
 */
fun TACCmd.isHalting() =
    this is TACCmd.EVM.SelfdestructCmd ||
            this is TACCmd.EVM.StopCmd ||
            this is TACCmd.EVM.EVMReturnCmd ||
            this is TACCmd.EVM.EVMRevertCmd ||
            this is TACCmd.EVM.InvalidCmd ||
            this is TACCmd.Simple.ReturnSymCmd ||
            this is TACCmd.Simple.ReturnCmd ||
            this is TACCmd.Simple.RevertCmd

fun TACCmd.Simple.isReturn() : Boolean =
    this is TACCmd.Simple.ReturnSymCmd ||
            this is TACCmd.Simple.ReturnCmd ||
            (this is TACCmd.Simple.SummaryCmd && this.summ is ReturnSummary && this.summ.ret.isReturn())

/** Returns the result of getLhs + getFreeVarsOfRhs */
fun TACCmd.freeVars(): Set<TACSymbol.Var> {
    val lhs = getLhs()
    return if (lhs != null) {
        getFreeVarsOfRhs() + lhs
    } else {
        getFreeVarsOfRhs()
    }
}

inline fun <reified T: TACCmd.Spec> T.mapMeta(f: (MetaMap) -> MetaMap) : T = withMeta(f(meta)) as T
inline fun <reified T: TACCmd.Spec, reified K: Serializable> T.plusMeta(key: MetaKey<K>, v: K) =
    mapMeta { it + (key to v) }
inline fun <reified T: TACCmd.Spec> T.plusMeta(key: MetaKey<Nothing>) = mapMeta { it + key }

fun isNonTrivialAssert(cmd: TACCmd) =
    cmd is TACCmd.Simple.AssertCmd && cmd.o != TACSymbol.True
