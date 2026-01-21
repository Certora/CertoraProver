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

package vc.data

import com.certora.collect.*
import spec.cvlast.CVLType
import spec.cvlast.ComparisonBasis
import tac.*
import utils.uncheckedAs
import vc.data.tacexprutil.DefaultTACExprTransformer
import java.io.Serializable
import vc.data.TACCmd.Simple as TS
import vc.data.TACCmd.Simple.AssigningCmd as TSA

abstract class TACCmdMapper<T> {
    open fun map(t: TS): T {
        return when (t) {
            is TSA -> mapAssigningCmd(t)
            is TS.LabelCmd -> mapLabelCmd(t)
            is TS.WordStore -> mapWordStore(t)
            is TS.ByteLongCopy -> mapByteLongCopy(t)
            is TS.JumpCmd -> mapJumpCmd(t)
            is TS.JumpiCmd -> mapJumpICmd(t)
            is TS.JumpdestCmd -> mapJumpDstCmd(t)
            is TS.LogCmd -> mapLogCmd(t)
            is TS.CallCore -> mapCallCore(t)
            is TS.ReturnCmd -> mapReturnCmd(t)
            is TS.ReturnSymCmd -> mapReturnSymCmd(t)
            is TS.RevertCmd -> mapRevertCmd(t)
            is TS.AssumeCmd -> mapAssumeCmd(t)
            is TS.AssumeNotCmd -> mapAssumeNotCmd(t)
            is TS.AssumeExpCmd -> mapAssumeExpCmd(t)
            is TS.AssertCmd -> mapAssertCmd(t)
            TS.NopCmd -> mapNopCmd()
            is TS.AnnotationCmd -> mapAnnotationCmd(t)
            is TACCmd.Simple.SummaryCmd -> mapSummaryCmd(t)
        }
    }

    private fun mapSummaryCmd(t: TACCmd.Simple.SummaryCmd): T =
        mapSummaryCmd(t.summ, t.meta)

    abstract fun mapSummaryCmd(t: TACSummary, meta: MetaMap): T

    open fun mapAnnotationCmd(t: TACCmd.Simple.AnnotationCmd): T = this.mapAnnotationCmd(t, t.annot, t.meta)

    abstract fun mapAnnotationCmd(
        annotationCmd: TACCmd.Simple.AnnotationCmd,
        annotationPair: TACCmd.Simple.AnnotationCmd.Annotation<*>,
        metaMap: MetaMap
    ): T

    open fun mapAssertCmd(t: TS.AssertCmd) = this.mapAssertCmd(t.o, t.description, t.meta)

    open fun mapAssumeNotCmd(t: TS.AssumeNotCmd) = this.mapAssumeNotCmd(t.cond, t.meta)

    open fun mapAssumeCmd(t: TS.AssumeCmd) = this.mapAssumeCmd(t.cond, t.msg, t.meta)

    open fun mapAssumeExpCmd(t: TS.AssumeExpCmd) = this.mapAssumeExpCmd(t.cond, t.meta)

    open fun mapRevertCmd(t: TS.RevertCmd) =
        this.mapRevertCmd(t.o1, t.o2, t.revertType, t.base, t.meta)

    open fun mapReturnCmd(t: TS.ReturnCmd) =
        this.mapReturnCmd(t.o1, t.o2, t.memBaseMap, t.meta)

    open fun mapReturnSymCmd(t: TS.ReturnSymCmd) =
        this.mapReturnSymCmd(t.o, t.meta)

    open fun mapCallCore(t: TS.CallCore) =
        this.mapCallCore(
            t.to,
            t.gas,
            t.inOffset,
            t.inSize,
            t.inBase,
            t.outOffset,
            t.outSize,
            t.outBase,
            t.callType,
            t.value,
            t.meta
        )

    open fun mapLogCmd(t: TS.LogCmd) = this.mapLogCmd(t.args, t.memBaseMap, t.meta)

    open fun mapJumpDstCmd(t: TS.JumpdestCmd) = this.mapJumpDstCmd(t.startPC, t.meta)

    open fun mapJumpICmd(t: TS.JumpiCmd) =
        this.mapJumpICmd(t.dst, t.cond, t.elseDst, t.meta)

    open fun mapJumpCmd(t: TS.JumpCmd) = this.mapJumpCmd(t.dst, t.meta)

    open fun mapByteLongCopy(t: TS.ByteLongCopy) =
        this.mapByteLongCopy(t.dstBase, t.dstOffset, t.length, t.srcBase, t.srcOffset, t.meta)

    open fun mapWordStore(t: TS.WordStore) = this.mapWordStore(t.base, t.loc, t.value, t.meta)

    open fun mapLabelCmd(t: TS.LabelCmd) = this.mapLabelCmd(t._msg, t.meta)

    abstract fun mapNopCmd(): T
    abstract fun mapAssertCmd(o: TACSymbol, description: String, metaMap: MetaMap): T
    abstract fun mapAssumeNotCmd(cond: TACSymbol, metaMap: MetaMap): T
    abstract fun mapAssumeCmd(cond: TACSymbol, description: String, metaMap: MetaMap): T
    abstract fun mapAssumeExpCmd(cond: TACExpr, metaMap: MetaMap): T
    abstract fun mapRevertCmd(
        o1: TACSymbol,
        o2: TACSymbol,
        revertType: TACRevertType,
        base: TACSymbol.Var,
        metaMap: MetaMap
    ): T

    abstract fun mapReturnCmd(
        o1: TACSymbol,
        o2: TACSymbol,
        memBaseMap: TACSymbol.Var,
        metaMap: MetaMap
    ): T

    abstract fun mapReturnSymCmd(
        o: TACSymbol,
        metaMap: MetaMap
    ): T

    abstract fun mapCallCore(
        to: TACSymbol, gas: TACSymbol, inOffset: TACSymbol, inSize: TACSymbol,
        inBase: TACSymbol.Var,
        outOffset: TACSymbol,
        outSize: TACSymbol,
        outBase: TACSymbol.Var,
        callType: TACCallType,
        value: TACSymbol,
        metaMap: MetaMap
    ): T

    abstract fun mapLogCmd(args: List<TACSymbol>, memBaseMap: TACSymbol.Var, metaMap: MetaMap): T

    abstract fun mapJumpDstCmd(startPC: NBId, metaMap: MetaMap): T

    abstract fun mapJumpICmd(
        dst: NBId,
        cond: TACSymbol,
        elseDst: NBId,
        metaMap: MetaMap
    ): T

    abstract fun mapJumpCmd(dst: NBId, metaMap: MetaMap): T

    abstract fun mapByteLongCopy(
        dstBase: TACSymbol.Var,
        dstOffset: TACSymbol,
        length: TACSymbol,
        srcBase: TACSymbol.Var,
        srcOffset: TACSymbol,
        metaMap: MetaMap
    ): T

    abstract fun mapWordStore(
        base: TACSymbol.Var,
        loc: TACSymbol,
        value: TACSymbol,
        metaMap: MetaMap
    ): T

    abstract fun mapLabelCmd(msg: String, metaMap: MetaMap): T

    open fun mapAssigningCmd(t: TSA): T {
        return when (t) {
            is TSA.AssignExpCmd -> mapAssignExpCmd(t)
            is TSA.AssignSha3Cmd -> mapAssignSha3Cmd(t)
            is TSA.AssignSimpleSha3Cmd -> mapAssignSimpleSha3Cmd(t)
            is TSA.ByteLoad -> mapByteLoad(t)
            is TSA.ByteStore -> mapByteStore(t)
            is TSA.ByteStoreSingle -> mapByteStoreSingle(t)
            is TSA.WordLoad -> mapWordLoad(t)
            is TSA.AssignMsizeCmd -> mapAssignMsizeCmd(t)
            is TSA.AssignGasCmd -> mapAssignGasCmd(t)
            is TSA.AssignHavocCmd -> mapAssignHavocCmd(t)
        }
    }

    open fun mapAssignHavocCmd(t: TSA.AssignHavocCmd) = this.mapAssignHavocCmd(t.lhs, t.meta)

    open fun mapAssignGasCmd(t: TSA.AssignGasCmd) = this.mapAssignGasCmd(t.lhs, t.meta)

    open fun mapAssignMsizeCmd(t: TSA.AssignMsizeCmd) = this.mapAssignMsizeCmd(t.lhs, t.meta)

    open fun mapWordLoad(t: TSA.WordLoad) = this.mapWordLoad(t.lhs, t.base, t.loc, t.meta)

    open fun mapByteStoreSingle(t: TSA.ByteStoreSingle) =
        this.mapByteStoreSingle(t.base, t.loc, t.value, t.meta)

    open fun mapByteStore(t: TSA.ByteStore) =
        this.mapByteStore(t.base, t.loc, t.value, t.meta)

    open fun mapByteLoad(t: TSA.ByteLoad) = this.mapByteLoad(t.lhs, t.base, t.loc, t.meta)

    open fun mapAssignSimpleSha3Cmd(t: TSA.AssignSimpleSha3Cmd) =
        this.mapAssignSimpleSha3Cmd(t.lhs, t.length, t.args, t.meta)

    open fun mapAssignSha3Cmd(t: TSA.AssignSha3Cmd) =
        this.mapAssignSha3Cmd(t.lhs, t.op1, t.op2, t.memBaseMap, t.meta)

    open fun mapAssignExpCmd(t: TSA.AssignExpCmd) = this.mapAssignExpCmd(t.lhs, t.rhs, t.meta)

    abstract fun mapByteStoreSingle(
        base: TACSymbol.Var,
        loc: TACSymbol,
        value: TACSymbol,
        metaMap: MetaMap
    ): T

    abstract fun mapAssignExpCmd(lhs: TACSymbol.Var, rhs: TACExpr, metaMap: MetaMap): T

    abstract fun mapAssignSha3Cmd(
        lhs: TACSymbol.Var,
        op1: TACSymbol,
        op2: TACSymbol,
        memBaseMap: TACSymbol.Var,
        metaMap: MetaMap
    ): T

    abstract fun mapAssignSimpleSha3Cmd(
        lhs: TACSymbol.Var,
        length: TACSymbol.Const,
        args: List<TACSymbol>,
        metaMap: MetaMap
    ): T

    abstract fun mapByteLoad(
        lhs: TACSymbol.Var,
        base: TACSymbol.Var,
        loc: TACSymbol,
        metaMap: MetaMap
    ): T

    abstract fun mapByteStore(
        base: TACSymbol.Var,
        loc: TACSymbol,
        value: TACSymbol,
        metaMap: MetaMap
    ): T

    abstract fun mapWordLoad(
        lhs: TACSymbol.Var,
        base: TACSymbol.Var,
        loc: TACSymbol,
        metaMap: MetaMap
    ): T

    abstract fun mapAssignMsizeCmd(lhs: TACSymbol.Var, metaMap: MetaMap): T

    abstract fun mapAssignHavocCmd(lhs: TACSymbol.Var, metaMap: MetaMap): T

    abstract fun mapAssignGasCmd(lhs: TACSymbol.Var, metaMap: MetaMap): T
}



abstract class AbstractDefaultTACCmdMapper : TACCmdMapper<TACCmd.Simple>() {

    /* expression mapping */

    abstract fun mapExpr(expr: TACExpr, index: Int): TACExpr

    /* symbol mapping */

    abstract fun mapSymbol(t: TACSymbol, index: Int): TACSymbol

    abstract fun mapVar(t: TACSymbol.Var, index: Int): TACSymbol.Var

    abstract fun mapLhs(t: TACSymbol.Var, index: Int): TACSymbol.Var

    abstract fun mapConstant(t: TACSymbol.Const, index: Int): TACSymbol.Const

    protected fun TACSymbol.map(index: Int): TACSymbol = this@AbstractDefaultTACCmdMapper.mapSymbol(this, index)

    protected fun TACSymbol.Var.map(index: Int): TACSymbol.Var = this@AbstractDefaultTACCmdMapper.mapVar(this, index)

    protected fun TACSymbol.Var.mapSymbol(index: Int): TACSymbol =
        this@AbstractDefaultTACCmdMapper.mapSymbol(this, index)


    /* meta mapping */

    open fun mapMeta(t: MetaMap): MetaMap = t

    private fun TS.mapThisMeta(t: MetaMap): TS =
        this.mapMeta { this@AbstractDefaultTACCmdMapper.mapMeta(t) }

    /* command mapping */

    override fun mapNopCmd(): TS =
        TS.NopCmd

    override fun mapAssertCmd(o: TACSymbol, description: String, metaMap: MetaMap): TS =
        TS.AssertCmd(
            o = o.map(0),
            description
        ).mapThisMeta(metaMap)

    override fun mapAssumeNotCmd(cond: TACSymbol, metaMap: MetaMap): TS =
        TS.AssumeNotCmd(
            cond = cond.map(0)
        ).mapThisMeta(metaMap)

    override fun mapAssumeCmd(cond: TACSymbol, description: String, metaMap: MetaMap): TS =
        TS.AssumeCmd(
            cond = cond.map(0),
            msg = description
        ).mapThisMeta(metaMap)

    override fun mapAssumeExpCmd(cond: TACExpr, metaMap: MetaMap): TS =
        TS.AssumeExpCmd(
            cond = mapExpr(cond, 0)
        ).mapThisMeta(metaMap)

    override fun mapRevertCmd(
        o1: TACSymbol,
        o2: TACSymbol,
        revertType: TACRevertType,
        base: TACSymbol.Var,
        metaMap: MetaMap
    ): TS {
        return TS.RevertCmd(
            o1 = o1.map(0),
            o2 = o2.map(1),
            revertType = revertType,
            base = base.map(2)
        ).mapThisMeta(metaMap)
    }

    override fun mapReturnCmd(
        o1: TACSymbol,
        o2: TACSymbol,
        memBaseMap: TACSymbol.Var,
        metaMap: MetaMap
    ): TS {
        return TS.ReturnCmd(
            o1 = o1.map(0),
            o2 = o2.map(1),
            memBaseMap = memBaseMap.map(2)
        ).mapThisMeta(metaMap)
    }

    override fun mapReturnSymCmd(
        o: TACSymbol,
        metaMap: MetaMap
    ): TS {
        return TS.ReturnSymCmd(
            o = o.map(0)
        ).mapThisMeta(metaMap)
    }

    override fun mapCallCore(
        to: TACSymbol,
        gas: TACSymbol,
        inOffset: TACSymbol,
        inSize: TACSymbol,
        inBase: TACSymbol.Var,
        outOffset: TACSymbol,
        outSize: TACSymbol,
        outBase: TACSymbol.Var,
        callType: TACCallType,
        value: TACSymbol,
        metaMap: MetaMap
    ): TS {
        return TS.CallCore(
            to = to.map(0),
            gas = gas.map(1),
            inOffset = inOffset.map(2),
            inSize = inSize.map(3),
            inBase = inBase.map(4),
            outOffset = outOffset.map(5),
            outSize = outSize.map(6),
            outBase = outBase.map(7),
            callType = callType,
            value = value.map(8)
        ).mapThisMeta(metaMap)
    }

    override fun mapLogCmd(args: List<TACSymbol>, memBaseMap: TACSymbol.Var, metaMap: MetaMap): TS {
        return TS.LogCmd(
            args = args.mapIndexed { index, tacSymbol -> mapSymbol(tacSymbol, index) },
            memBaseMap = memBaseMap.map(args.size)
        ).mapThisMeta(metaMap)
    }

    override fun mapJumpDstCmd(startPC: NBId, metaMap: MetaMap): TS {
        return TS.JumpdestCmd(
            startPC = startPC
        ).mapThisMeta(metaMap)
    }

    override fun mapJumpICmd(
        dst: NBId,
        cond: TACSymbol,
        elseDst: NBId,
        metaMap: MetaMap
    ): TS {
        return TS.JumpiCmd(
            dst = dst,
            cond = cond.map(0),
            elseDst = elseDst
        ).mapThisMeta(metaMap)
    }

    override fun mapJumpCmd(dst: NBId, metaMap: MetaMap): TS {
        return TS.JumpCmd(
            dst = dst
        ).mapThisMeta(metaMap)
    }

    override fun mapByteLongCopy(
        dstBase: TACSymbol.Var,
        dstOffset: TACSymbol,
        length: TACSymbol,
        srcBase: TACSymbol.Var,
        srcOffset: TACSymbol,
        metaMap: MetaMap
    ): TS {
        return TS.ByteLongCopy(
            dstBase = dstBase.map(0),
            dstOffset = dstOffset.map(1),
            length = length.map(2),
            srcBase = srcBase.map(3),
            srcOffset = srcOffset.map(4)
        ).mapThisMeta(metaMap)
    }

    override fun mapWordStore(
        base: TACSymbol.Var,
        loc: TACSymbol,
        value: TACSymbol,
        metaMap: MetaMap
    ): TS {
        return TS.WordStore(
            loc = loc.map(0),
            value = value.map(1),
            base = base.map(2)
        ).mapThisMeta(metaMap)
    }

    override fun mapLabelCmd(msg: String, metaMap: MetaMap): TS {
        return TS.LabelCmd(msg).mapThisMeta(metaMap)
    }

    override fun mapAssignExpCmd(lhs: TACSymbol.Var, rhs: TACExpr, metaMap: MetaMap): TS {
        return TSA.AssignExpCmd(
            lhs = mapLhs(lhs, 0),
            rhs = mapExpr(rhs, 1)
        ).mapThisMeta(metaMap)
    }

    override fun mapAssignSha3Cmd(
        lhs: TACSymbol.Var,
        op1: TACSymbol,
        op2: TACSymbol,
        memBaseMap: TACSymbol.Var,
        metaMap: MetaMap
    ): TS {
        return TSA.AssignSha3Cmd(
            lhs = mapLhs(lhs, 0),
            op1 = op1.map(1),
            op2 = op2.map(2),
            memBaseMap = memBaseMap.map(3)
        ).mapThisMeta(metaMap)
    }

    override fun mapAssignSimpleSha3Cmd(
        lhs: TACSymbol.Var,
        length: TACSymbol.Const,
        args: List<TACSymbol>,
        metaMap: MetaMap
    ): TS {
        return TSA.AssignSimpleSha3Cmd(
            lhs = mapLhs(lhs, 0),
            length = mapConstant(length, 1),
            args = args.mapIndexed { index, tacSymbol -> this.mapSymbol(tacSymbol, index + 2) }
        ).mapThisMeta(metaMap)
    }

    override fun mapByteLoad(
        lhs: TACSymbol.Var,
        base: TACSymbol.Var,
        loc: TACSymbol,
        metaMap: MetaMap
    ): TS {
        return TSA.ByteLoad(
            lhs = mapLhs(lhs, 0),
            base = base.map(1),
            loc = loc.map(2)
        ).mapThisMeta(metaMap)
    }

    override fun mapByteStore(
        base: TACSymbol.Var,
        loc: TACSymbol,
        value: TACSymbol,
        metaMap: MetaMap
    ): TS {
        return TSA.ByteStore(
            base = base.map(0),
            loc = loc.map(1),
            value = value.map(2)
        ).mapThisMeta(metaMap)
    }

    override fun mapWordLoad(
        lhs: TACSymbol.Var,
        base: TACSymbol.Var,
        loc: TACSymbol,
        metaMap: MetaMap
    ): TS {
        return TSA.WordLoad(
            lhs = mapLhs(lhs, 0),
            base = base.map(1),
            loc = loc.map(2)
        ).mapThisMeta(metaMap)
    }

    override fun mapAssignMsizeCmd(lhs: TACSymbol.Var, metaMap: MetaMap): TS {
        return TSA.AssignMsizeCmd(
            lhs = mapLhs(lhs, 0)
        ).mapThisMeta(metaMap)
    }

    override fun mapAssignHavocCmd(lhs: TACSymbol.Var, metaMap: MetaMap): TS {
        return TSA.AssignHavocCmd(
            lhs = mapLhs(lhs, 0)
        ).mapThisMeta(metaMap)
    }

    override fun mapAssignGasCmd(lhs: TACSymbol.Var, metaMap: MetaMap): TS {
        return TSA.AssignGasCmd(
            lhs = mapLhs(lhs, 0)
        ).mapThisMeta(metaMap)
    }

    override fun mapByteStoreSingle(
        base: TACSymbol.Var,
        loc: TACSymbol,
        value: TACSymbol,
        metaMap: MetaMap
    ): TS {
        return TSA.ByteStoreSingle(
            base = base.map(0),
            loc = loc.map(1),
            value = value.map(2)
        ).mapThisMeta(metaMap)
    }

    private fun <@Treapable T : Serializable> mapAnnotationPair(
        annotation: TACCmd.Simple.AnnotationCmd.Annotation<T>
    ) = mapMetaPair(annotation.k, annotation.v).let { TACCmd.Simple.AnnotationCmd.Annotation(annotation.k, it) }

    fun <T : Serializable> mapMetaPair(k: MetaKey<T>, v: T): T =
        applyTransEntityMappers(v, k,
            mapSymbol = { mapSymbol(it, -1) },
            mapVar = { mapVar(it, -1) }
        ).uncheckedAs()

    // TODO: transform ghost
    override fun mapAnnotationCmd(
        annotationCmd: TACCmd.Simple.AnnotationCmd,
        annotationPair: TACCmd.Simple.AnnotationCmd.Annotation<*>,
        metaMap: MetaMap
    ): TACCmd.Simple = annotationCmd.copy(annot = mapAnnotationPair(annotationPair)).mapThisMeta(metaMap)

    override fun mapSummaryCmd(t: TACSummary, meta: MetaMap): TACCmd.Simple =
        TS.SummaryCmd(mapSummary(t), mapMeta(meta))

    open fun mapSummary(t: TACSummary): TACSummary {
        return t.transformSymbols { mapVar(t = it, -1) }.withMeta(::mapMeta)
    }
}

open class DefaultTACCmdMapper : AbstractDefaultTACCmdMapper() {
    protected open val exprMapper = object : DefaultTACExprTransformer() {
        override fun transform(exp: TACExpr): TACExpr {
            return when (exp) {
                is TACExpr.Sym -> TACExpr.Sym(this@DefaultTACCmdMapper.mapSymbol(exp.s))
                else -> super.transform(exp)
            }
        }

        override fun transformVar(exp: TACExpr.Sym.Var): TACExpr {
            return TACExpr.Sym.Var(this@DefaultTACCmdMapper.mapVar(exp.s))
        }

        override fun <@Treapable T : Serializable> transformAnnotationExp(
            o: TACExpr, k: MetaKey<T>, v: T
        ) = TACExpr.AnnotationExp(transform(o), k, mapMetaPair(k, v))
    }

    override fun mapMeta(t: MetaMap): MetaMap {
        return super.mapMeta(t).updateValues { (k, v) ->
            mapMetaPair(k, v)
        }
    }

    /* variants of the methods that don't care about the index */

    open fun mapSymbol(t: TACSymbol): TACSymbol =
        when (t) {
            is TACSymbol.Const -> this.mapConstant(t)
            is TACSymbol.Var -> this.mapVar(t)
        }

    open fun mapVar(t: TACSymbol.Var): TACSymbol.Var = t.withMeta { meta -> this.mapMeta(meta) }

    open fun mapConstant(t: TACSymbol.Const): TACSymbol.Const = t

    open fun mapLhs(t: TACSymbol.Var) = this.mapVar(t)

    open fun mapExpr(expr: TACExpr): TACExpr = exprMapper.transform(expr)

    /* overriding the indexing methods to just drop the index */

    override fun mapVar(t: TACSymbol.Var, index: Int): TACSymbol.Var = mapVar(t)

    override fun mapLhs(t: TACSymbol.Var, index: Int): TACSymbol.Var = mapLhs(t)

    override fun mapConstant(t: TACSymbol.Const, index: Int): TACSymbol.Const = t

    override fun mapExpr(expr: TACExpr, index: Int): TACExpr = mapExpr(expr)

    override fun mapSymbol(t: TACSymbol, index: Int): TACSymbol = mapSymbol(t)

}

/**
 * "index" here refers the position at which an operand occurs. E.g. consider the expression `f(x, y, z)`;
 * the index we pass when visiting `z` will be `2`, since `z` occurs at the third position and we count
 * 0-based.
 */
abstract class IndexingDefaultTACCmdMapper : AbstractDefaultTACCmdMapper() {

    override fun mapVar(t: TACSymbol.Var, index: Int): TACSymbol.Var = t

    override fun mapConstant(t: TACSymbol.Const, index: Int): TACSymbol.Const = t

    override fun mapSymbol(t: TACSymbol, index: Int): TACSymbol = when (t) {
        is TACSymbol.Const -> mapConstant(t, index)
        is TACSymbol.Var -> mapVar(t, index)
    }
}

/**
 * A mapper that supports mapping [TACCmd.Spec] too,
 * i.e. [TACCmd.Simple] and [TACCmd.CVL]
 * */
open class DefaultTACCmdSpecMapper: DefaultTACCmdMapper() {
    open fun mapSpec(t: TACCmd.Spec): TACCmd.Spec = when(t) {
        is TACCmd.CVL -> mapCvl(t)
        is TACCmd.Simple -> map(t)
    }

    open fun mapCvl(t: TACCmd.CVL): TACCmd.CVL = when (t) {
        is TACCmd.CVL.AssignBytesBlob -> mapAssignBytesBlob(t)
        is TACCmd.CVL.ArrayLengthRead -> mapArrayLengthRead(t)
        is TACCmd.CVL.StringLastWord -> mapStringLastWord(t)
        is TACCmd.CVL.SetArrayLength -> mapSetArrayLength(t)
        is TACCmd.CVL.WriteElement -> mapWriteElement(t)
        is TACCmd.CVL.ReadElement -> mapReadElement(t)
        is TACCmd.CVL.SetBlockchainState -> mapSetBlockchainState(t)
        is TACCmd.CVL.CopyBlockchainState -> mapCopyBlockchainState(t)
        is TACCmd.CVL.AssignVMParam -> mapAssignVMParam(t)
        is TACCmd.CVL.CompareStorage -> mapCompareStorage(t)
        is TACCmd.CVL.ArrayCopy -> mapArrayCopy(t)
        is TACCmd.CVL.LocalAlloc -> mapLocalAlloc(t)
        is TACCmd.CVL.CompareArray -> mapCompareArray(t)
    }

    open fun mapArrayCopy(t: TACCmd.CVL.ArrayCopy): TACCmd.CVL.ArrayCopy {
        return mapArrayCopy(
            t.src,
            t.dst,
            t.logicalLength,
            t.elementSize,
            t.meta
        )
    }

    open fun mapArrayCopy(
        src: TACCmd.CVL.BufferPointer,
        dst: TACCmd.CVL.BufferPointer,
        logicalLength: TACSymbol,
        elementSize: Int,
        meta: MetaMap
    ): TACCmd.CVL.ArrayCopy {
        fun TACCmd.CVL.Buffer.remap(i: Int) = when(this) {
            is TACCmd.CVL.Buffer.CVLBuffer -> TACCmd.CVL.Buffer.CVLBuffer(
                dataPath = dataPath,
                root = mapVar(root, i)
            )
            is TACCmd.CVL.Buffer.EVMBuffer -> TACCmd.CVL.Buffer.EVMBuffer(
                t = mapVar(t, i)
            )
        }
        return TACCmd.CVL.ArrayCopy(
            src = TACCmd.CVL.BufferPointer(
                offset = mapSymbol(src.offset, 0),
                buffer = src.buffer.remap(1)
            ),
            dst = TACCmd.CVL.BufferPointer(
                offset = mapSymbol(dst.offset, 2),
                buffer = dst.buffer.remap(3)
            ),
            elementSize = elementSize,
            logicalLength = mapSymbol(logicalLength, 4),
            meta = mapMeta(meta)
        )
    }

    open fun mapLocalAlloc(t: TACCmd.CVL.LocalAlloc): TACCmd.CVL.LocalAlloc {
        return mapLocalAlloc(t.lhs, t.arr, t.amount, t.meta)
    }

    open fun mapLocalAlloc(
        lhs: TACSymbol.Var,
        arr: TACSymbol.Var,
        amount: TACSymbol,
        meta: MetaMap
    ): TACCmd.CVL.LocalAlloc {
        return TACCmd.CVL.LocalAlloc(
            lhs = mapLhs(lhs, 0),
            arr = mapVar(arr, 1),
            amount = mapSymbol(amount, 2),
            meta = mapMeta(meta)
        )
    }

    open fun mapCompareStorage(t: TACCmd.CVL.CompareStorage): TACCmd.CVL.CompareStorage {
        return this.mapCompareStorage(
            lhsVar = t.lhsVar,
            storage1 = t.storage1,
            storage2 = t.storage2,
            storageBasis = t.storageBasis,
            isEquality = t.isEquality,
            useSkolemizaton = t.useSkolemizaton,
            meta = t.meta
        )
    }

    open fun mapCompareStorage(
        lhsVar: TACSymbol.Var,
        storage1: TACSymbol.Var,
        storage2: TACSymbol.Var,
        storageBasis: ComparisonBasis,
        isEquality: Boolean,
        useSkolemizaton: Boolean,
        meta: MetaMap
    ): TACCmd.CVL.CompareStorage {
        return TACCmd.CVL.CompareStorage(
            lhsVar = mapLhs(lhsVar, 0),
            storage1 = mapVar(storage1,1),
            storage2 = mapVar(storage2, 2),
            isEquality = isEquality,
            storageBasis = storageBasis,
            useSkolemizaton = useSkolemizaton,
            meta = mapMeta(meta)
        )
    }
    open fun mapCompareArray(t: TACCmd.CVL.CompareArray): TACCmd.CVL.CompareArray {
        return this.mapCompareArray(
            lhsVar = t.lhsVar,
            left = t.left,
            right = t.right,
            meta = t.meta
        )
    }

    open fun mapCompareArray(
        lhsVar: TACSymbol.Var,
        left: TACSymbol.Var,
        right: TACSymbol.Var,
        meta: MetaMap
    ): TACCmd.CVL.CompareArray {
        return TACCmd.CVL.CompareArray(
            lhsVar = mapLhs(lhsVar, 0),
            left = mapVar(left,1),
            right = mapVar(right, 2),
            meta = mapMeta(meta)
        )
    }
    override fun mapLhs(t: TACSymbol.Var, index: Int): TACSymbol.Var = mapVar(t, index)

    open fun mapAssignBytesBlob(t: TACCmd.CVL.AssignBytesBlob): TACCmd.CVL.AssignBytesBlob =
        mapAssignBytesBlob(lhs = t.lhs, rhs = t.rhs, t.meta)
    open fun mapAssignBytesBlob(
        lhs : TACSymbol.Var,
        rhs : TACSymbol.Var,
        meta: MetaMap
    ): TACCmd.CVL.AssignBytesBlob =
        TACCmd.CVL.AssignBytesBlob(lhs = mapLhs(lhs, 0), rhs = mapVar(rhs, 1), mapMeta(meta))
    open fun mapArrayLengthRead(t: TACCmd.CVL.ArrayLengthRead): TACCmd.CVL.ArrayLengthRead =
        mapArrayLengthRead(lhs = t.lhs, rhs = t.rhs, t.meta)
    open fun mapStringLastWord(t: TACCmd.CVL.StringLastWord): TACCmd.CVL.StringLastWord =
        mapStringLastWord(lhs = t.lhs, rhs = t.rhs, t.meta)

    open fun mapArrayLengthRead(
        lhs: TACSymbol.Var,
        rhs: TACSymbol.Var,
        meta: MetaMap
    ): TACCmd.CVL.ArrayLengthRead =
        TACCmd.CVL.ArrayLengthRead(lhs = mapLhs(lhs, 0), rhs = mapVar(rhs, 1), mapMeta(meta))
    open fun mapStringLastWord(
        lhs: TACSymbol.Var,
        rhs: TACSymbol.Var,
        meta: MetaMap
    ): TACCmd.CVL.StringLastWord =
        TACCmd.CVL.StringLastWord(lhs = mapLhs(lhs, 0), rhs = mapVar(rhs, 1), mapMeta(meta))

    open fun mapSetArrayLength(t: TACCmd.CVL.SetArrayLength): TACCmd.CVL.SetArrayLength =
        mapSetArrayLength(lhs = t.lhs, len = t.len, t.meta)
    open fun mapSetArrayLength(
        lhs: TACSymbol.Var,
        len: TACSymbol,
        meta: MetaMap
    ): TACCmd.CVL.SetArrayLength =
        mapSetArrayLength(lhs = mapLhs(lhs, 0), len = mapSymbol(len, 1), mapMeta(meta))

    open fun mapWriteElement(t: TACCmd.CVL.WriteElement): TACCmd.CVL.WriteElement =
        mapWriteElement(target = t.target, physicalIndex = t.physicalIndex, value = t.value, t.useEncoding, t.outputPath, t.meta)
    open fun mapWriteElement(
        target: TACSymbol.Var,
        physicalIndex: TACSymbol,
        value: TACSymbol,
        useEncoding: Tag.CVLArray.UserArray.ElementEncoding,
        outputPath: List<DataField>,
        meta: MetaMap = MetaMap()
    ): TACCmd.CVL.WriteElement = TACCmd.CVL.WriteElement(
        target = mapVar(target, 0),
        physicalIndex = mapSymbol(physicalIndex, 1),
        value = mapSymbol(value, 2),
        useEncoding = useEncoding,
        outputPath = outputPath,
        meta = mapMeta(meta)
    )

    open fun mapReadElement(t: TACCmd.CVL.ReadElement): TACCmd.CVL.ReadElement =
        mapReadElement(lhs = t.lhs, base = t.base, physicalIndex = t.physicalIndex, useEncoding = t.useEncoding, t.dataPath, t.meta)
    open fun mapReadElement(
        lhs: TACSymbol.Var,
        base: TACSymbol.Var,
        physicalIndex: TACSymbol,
        useEncoding: Tag.CVLArray.UserArray.ElementEncoding,
        dataPath: List<DataField>,
        meta: MetaMap,
    ): TACCmd.CVL.ReadElement =
        TACCmd.CVL.ReadElement(
            lhs = mapLhs(lhs, 0),
            base = mapVar(base, 1),
            physicalIndex = mapSymbol(physicalIndex, 2),
            useEncoding = useEncoding,
            dataPath = dataPath,
            meta = mapMeta(meta)
        )


    open fun mapSetBlockchainState(t: TACCmd.CVL.SetBlockchainState): TACCmd.CVL.SetBlockchainState = mapSetBlockchainState(stateVar = t.stateVar, t.meta)
    open fun mapSetBlockchainState(
        stateVar: TACSymbol.Var,
        meta: MetaMap
    ): TACCmd.CVL.SetBlockchainState =
        TACCmd.CVL.SetBlockchainState(stateVar = mapVar(stateVar, 0), mapMeta(meta))

    open fun mapCopyBlockchainState(t: TACCmd.CVL.CopyBlockchainState): TACCmd.CVL.CopyBlockchainState =
        mapCopyBlockchainState(lhs = t.lhs, t.meta)

    open fun mapCopyBlockchainState(
        lhs: TACSymbol.Var,
        meta: MetaMap
    ): TACCmd.CVL.CopyBlockchainState =
        TACCmd.CVL.CopyBlockchainState(lhs = mapLhs(lhs, 0), mapMeta(meta))
    open fun mapAssignVMParam(t: TACCmd.CVL.AssignVMParam): TACCmd.CVL.AssignVMParam = mapAssignVMParam(
        lhsVar = t.lhsVar,
        lhsType = t.lhsType,
        rhsName = t.rhsName,
        rhsType = t.rhsType,
        t.meta
    )
    open fun mapAssignVMParam(
        lhsVar: TACSymbol.Var,
        lhsType: CVLType.PureCVLType,
        rhsName: String,
        rhsType: CVLType.VM,
        meta: MetaMap
    ): TACCmd.CVL.AssignVMParam =
        TACCmd.CVL.AssignVMParam(mapLhs(lhsVar, 0), lhsType, rhsName, rhsType, mapMeta(meta))

}
