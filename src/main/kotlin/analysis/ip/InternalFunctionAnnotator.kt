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

@file:kotlinx.serialization.UseSerializers(utils.BigIntegerSerializer::class)
package analysis.ip

import allocator.Allocator
import allocator.GenerateRemapper
import allocator.GeneratedBy
import analysis.icfg.Summarization
import analysis.pta.abi.PartitionField
import com.certora.collect.*
import datastructures.stdcollections.*
import evm.MASK_SIZE
import log.Logger
import log.LoggerTypes
import report.calltrace.printer.DebugAdapterPopAction
import report.calltrace.printer.DebugAdapterPushAction
import report.calltrace.printer.StackEntry
import spec.cvlast.QualifiedMethodSignature
import tac.MetaKey
import utils.*
import vc.data.*
import java.io.Serializable
import java.math.BigInteger

private val logger = Logger(LoggerTypes.DECOMPILER)

val internalAnnotationMask = BigInteger("ffffff6e4604afefe123321beef1b01fffffffffffffffffffffffff00000000", 16)
fun BigInteger.isInternalAnnotationConstant() = this == ((this and BigInteger("ffffffff", 16)) or internalAnnotationMask)
val internalAnnotationFlagMask = MASK_SIZE(16)


/**
 * All internal function ids are generated from a strictly increasing sequence starting from f196e50000. So, assuming
 * we never get more than 65k internal functions (lol), then the appearance of a constant with these upper bits
 * indicates an internal function id.
 */
val functionIdMask = BigInteger("f196e50000", 16)

enum class InternalArgSort {
    SCALAR,
    CALLDATA_ARRAY_ELEMS,
    CALLDATA_ARRAY_LENGTH,
    CALLDATA_POINTER
}

@KSerializable
@Treapable
data class InternalFuncArg(
    val s: TACSymbol,
    val offset: Int,
    val logicalPosition: Int,
    val sort: InternalArgSort,
    val location: InternalFuncValueLocation? = null
) : AmbiSerializable, TransformableVarEntityWithSupport<InternalFuncArg> {
    override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var): InternalFuncArg {
        return InternalFuncArg(
            (s as? TACSymbol.Var)?.let(f) ?: s,
            offset,
            logicalPosition,
            sort,
            location?.transformSymbols(f)
        )
    }

    override fun hashCode() = hash { it + s + offset + sort + location + logicalPosition }
    override val support: Set<TACSymbol.Var>
        get() = treapSetOfNotNull(s as? TACSymbol.Var) + location?.support.orEmpty()
}

@KSerializable
@Treapable
data class InternalFuncRet(
    val s: TACSymbol.Var,
    val offset: Int,
    /**
     * The location information is now attached directly to the internal function returns, previous versions
     * stored this information in a side map, and it was a *nightmare*.
     */
    val location: InternalFuncValueLocation? = null
): AmbiSerializable, TransformableVarEntityWithSupport<InternalFuncRet> {
    override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var): InternalFuncRet {
        return InternalFuncRet(
            f(s),
            offset,
            location?.transformSymbols(f)
        )
    }

    override val support: Set<TACSymbol.Var>
        get() = treapSetOf(s) + location?.support.orEmpty()

}

@KSerializable
@Treapable
data class InternalFunctionHint(val id: Int, val flag: Int, val sym: TACSymbol)
: TransformableVarEntityWithSupport<InternalFunctionHint>, HasKSerializable {
    companion object {
        val META_KEY = MetaKey<InternalFunctionHint>("tac.internal.function.hint")
    }

    override val support: Set<TACSymbol.Var>
        get() = setOfNotNull(sym as? TACSymbol.Var)

    override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var): InternalFunctionHint {
        return InternalFunctionHint(
            id,
            flag,
            sym = (sym as? TACSymbol.Var)?.let(f) ?: sym
        )
    }
}

interface InternalFunctionStartInfo {
    val methodSignature : QualifiedMethodSignature
    val args: List<InternalFuncArg>

    /**
     * The source range of the call site that calls the function
     */
    val callSiteSrc: TACMetaInfo?

    /**
     * The source range pointing to the implementation of the function that
     * is being called at the call site.
     */
    val calleeSrc: TACMetaInfo?
}

/**
 * [callSiteSrc] is call-site from the source of the underlined function call
 * of this [InternalFuncStartAnnotation].
 */
@KSerializable
@GenerateRemapper
@Treapable
data class InternalFuncStartAnnotation(
    @GeneratedBy(Allocator.Id.INTERNAL_FUNC, source = true) override val id: Int, val startPc: Int,
    override val args: List<InternalFuncArg>, override val methodSignature: QualifiedMethodSignature,
    val stackOffsetToArgPos: Map<Int, Int>,
    override val callSiteSrc: TACMetaInfo?,
    override val calleeSrc: TACMetaInfo?
) : TransformableVarEntityWithSupport<InternalFuncStartAnnotation>, RemapperEntity<InternalFuncStartAnnotation>, Serializable, InternalFunctionStartInfo, DebugAdapterPushAction, InternalFunctionStartAnnot {
    override val which: QualifiedMethodSignature
        get() = methodSignature

    fun isSummarizable() = args.all { it.sort == InternalArgSort.SCALAR }

    override val stackElement = StackEntry.SolidityFunction(methodSignature.prettyPrintFullyQualifiedName(), calleeSrc?.getSourceDetails()?.range)

    fun getArgPos(argOffset: Int): Int = stackOffsetToArgPos[argOffset] ?: run {
        val msg = "Unexpected requested argument offset $argOffset"
        logger.error(msg)
        throw IllegalArgumentException(msg)
    }

    override val support: Set<TACSymbol.Var>
        get() = args.flatMapToSet { arg -> arg.support }.toSet()

    override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var): InternalFuncStartAnnotation {
        return this.copy(args = args.map {
            it.transformSymbols(f)
        })
    }
}

/**
 * Describes where the values that flow out of and into an internal function are stored.
 */
@KSerializable
@Treapable
sealed class InternalFuncValueLocation : AmbiSerializable, TransformableVarEntityWithSupport<InternalFuncValueLocation> {
    /**
     * A scalar value, only on the stack
     */
    @KSerializable
    object Scalar : InternalFuncValueLocation() {
        override fun hashCode() = hashObject(this)
        fun readResolve() : Any = Scalar
        override fun toString(): String = "InternalFuncValueLocation.Scalar"
        override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var): InternalFuncValueLocation {
            return this
        }

        override val support: Set<TACSymbol.Var>
            get() = setOf()
    }

    /**
     * Indicates where the data for a particular field is stored and, if the field itself is a reference type,
     * the layout info for its fields.
     */
    @KSerializable
    @Treapable
    sealed interface PointerLayoutInfo : TransformableVarEntityWithSupport<PointerLayoutInfo>, HasKSerializable {
        val partitionVariable: TACSymbol.Var
    }

    /**
     * A field that only holds a primitive value, whose contents are in the bytemap [partitionVariable]
     */
    @KSerializable
    data class PrimitiveField(
        override val partitionVariable: TACSymbol.Var
    ) : PointerLayoutInfo, TransformableVarEntityWithSupport<PointerLayoutInfo> {
        override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var): PrimitiveField {
            return PrimitiveField(f(partitionVariable))
        }

        override val support: Set<TACSymbol.Var>
            get() = setOf(partitionVariable)
    }

    /**
     * Represents reference typed fields which are stored in the ByteMap [partitionVariable].
     * The fields of the reference types are themselves stored according to the information [fields].
     */
    @KSerializable
    data class ReferenceField(
        override val partitionVariable: TACSymbol.Var,
        val fields: Map<PartitionField, PointerLayoutInfo>
    ) : PointerLayoutInfo {
        override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var): PointerLayoutInfo {
            return ReferenceField(
                partitionVariable = f(partitionVariable),
                fields = fields.mapValues {
                    it.value.transformSymbols(f)
                }
            )
        }

        override val support: Set<TACSymbol.Var>
            get() = treapSetOf(partitionVariable) + fields.flatMapTo(treapSetBuilderOf()) {
                it.value.support
            }.build()

    }

    /**
     * Indicates that a "scalar" value exists on the stack, but is associated with several abstract fields, the basemaps
     * for which can be found according to [layoutForFields].
     */
    @KSerializable
    data class PointerWithFields(
        val layoutForFields: Map<PartitionField, PointerLayoutInfo>
    ) : InternalFuncValueLocation() {
        override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var): InternalFuncValueLocation {
            return PointerWithFields(
                layoutForFields.mapValues {
                    it.value.transformSymbols(f)
                }
            )
        }

        override val support: Set<TACSymbol.Var>
            get() = layoutForFields.entries.flatMapTo(mutableSetOf()) {
                it.value.support
            }
    }

    @KSerializable
    data class UnsplitPointer(
        val monolithicArray: TACSymbol.Var
    ) : InternalFuncValueLocation() {
        override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var): InternalFuncValueLocation {
            return f(monolithicArray).let(InternalFuncValueLocation::UnsplitPointer)
        }

        override val support: Set<TACSymbol.Var>
            get() = setOf(monolithicArray)

    }

    /**
     * A pointer without field information
     */
    @KSerializable
    object Pointer : InternalFuncValueLocation() {
        override fun hashCode() = hashObject(this)
        fun readResolve() : Any = Pointer
        override fun toString(): String = "InternalFuncValueLocation.Pointer"

        override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var): InternalFuncValueLocation {
            return this
        }

        override val support: Set<TACSymbol.Var>
            get() = setOf()
    }
}

@KSerializable
@GenerateRemapper
@Treapable
data class InternalFuncExitAnnotation(
    @GeneratedBy(Allocator.Id.INTERNAL_FUNC)
    override val id: Int,
    val rets: List<InternalFuncRet>,
    val methodSignature: QualifiedMethodSignature
) : TransformableVarEntityWithSupport<InternalFuncExitAnnotation>, RemapperEntity<InternalFuncExitAnnotation>, HasKSerializable, DebugAdapterPopAction, InternalFunctionExitAnnot {

    override val support: Set<TACSymbol.Var>
        get() = rets.flatMapToSet { it.support }

    override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var): InternalFuncExitAnnotation {
        return copy(rets = rets.map { ret ->
            ret.transformSymbols(f)
        })
    }
}

val JUMP_SYM = MetaKey<TACSymbol.Var.Annotation>("jump.sym")
val INTERNAL_FUNC_START = MetaKey<InternalFuncStartAnnotation>("internal.func.start", restore = true)
val INTERNAL_FUNC_EXIT = MetaKey<InternalFuncExitAnnotation>("internal.func.end")

class InternalFunctionExitFinder(code: CoreTACProgram) : Summarization.ExitFinder(code) {
    override fun calleeStarted(cmd: TACCmd.Simple.AnnotationCmd) =
        (cmd.annot.v as? InternalFuncStartAnnotation)?.id
    override fun calleeExited(cmd: TACCmd.Simple.AnnotationCmd) =
        (cmd.annot.v as? InternalFuncExitAnnotation)?.id
}
