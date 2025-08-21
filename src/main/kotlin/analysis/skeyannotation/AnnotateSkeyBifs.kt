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

package analysis.skeyannotation

import analysis.ExpPointer
import analysis.TACCommandGraph
import analysis.skeyannotation.SkeyDetection.Companion.isStorageAccess
import config.Config
import datastructures.stdcollections.*
import evm.MAX_EVM_UINT256
import spec.cvlast.CVLType
import tac.MetaMap
import tac.Tag
import utils.*
import vc.data.*
import vc.data.TACBuiltInFunction.Hash
import vc.data.TACBuiltInFunction.Hash.*
import vc.data.TACBuiltInFunction.Hash.Companion.skeySort
import vc.data.TACMeta.CVL_TYPE
import vc.data.tacexprutil.QuantDefaultTACExprTransformerWithPointer
import vc.data.tacexprutil.TACExprFactBasicSimp.Apply
import vc.data.tacexprutil.rebuild
import verifier.CodeAnalysis
import java.math.BigInteger

/**
 * Uses [SkeyDetectionResult] (which marks some values as "skey"s).
 *  - flips `TACExpr.Vec.SimpleHash` to the analogous [TACBuiltInFunction]
 *  - flips additions with an skey from `Add` to `skey_add`
 *  - introduces `to_skey` and `from_skey` at the `seams`
 *  - flip types of symbols`
 */
object AnnotateSkeyBifs {

    @Suppress("unused")
    private const val debugMode = false // should be `false` on master, since it enables expensive checks

    private fun runSkeyDetection(tacProgram: CoreTACProgram): SkeyDetectionResult {
        return CodeAnalysis(
            "SKeyDetection",
            { it: CoreTACProgram -> SkeyDetection.run(it) },
            { true }
        ).runAnalysis(tacProgram)
    }


    fun annotate(tacProgram: CoreTACProgram): CoreTACProgram {
        val skeyDetection = runSkeyDetection(tacProgram)

        fun isSkey(ptr: ExpPointer) = skeyDetection.isSkey(ptr)

        val txf = TACExprFactUntyped

        /** nb, ptr is relative to the expression [exp] before it was transformed, not [exp] itself
         * (might not matter for anything, just a remark)
         *
         * @return a pair of
         *     - the input expression [exp] or the input expression [exp] wrapped in an application of to_skey
         *     - a ptr that points to [exp] after the transformation (this will differ from [ptr] iff wrapping has
         *       happened)
         */
        fun toSkeyIfNecessary(ptr: ExpPointer, exp: TACExpr): Pair<TACExpr, ExpPointer> =
            if (isSkey(ptr)) {
                exp to ptr
            } else {
                check(exp !is TACExpr.Apply || exp.f != ToSkey.toTACFunctionSym())
                { "double wrapping" }
                toSkey(exp, ptr, txf)
            }

        fun fromSkeyIfNecessary(ptr: ExpPointer, exp: TACExpr): TACExpr =
            if (isSkey(ptr)) {
                fromSkey(exp, txf)
            } else {
                exp
            }

        val oldGraph = TACCommandGraph(tacProgram)

        val usedHashFunctions = mutableSetOf<TACBuiltInFunction>()

        fun simpleHashApp(arity: Int, hashFamily: HashFamily): TACExpr.TACFunctionSym.BuiltIn {
            val fs = SimpleHashApplication(arity, hashFamily)
            usedHashFunctions.add(fs)
            return fs.toTACFunctionSym()
        }

        val wrapSkeys = object : IndexingDefaultTACCmdMapperWithPointer() {
            override val exprMapper = object : QuantDefaultTACExprTransformerWithPointer() {
                fun toSkeyOptRec(acc: QuantVarsAndExpPointer, exp: TACExpr, argIndex: Int) =
                    toSkeyIfNecessary(acc.expPointer.extend(argIndex), transformArg(acc, exp, argIndex)).first

                fun fromSkeyOptRec(acc: QuantVarsAndExpPointer, exp: TACExpr, argIndex: Int) =
                    fromSkeyIfNecessary(acc.expPointer.extend(argIndex), transformArg(acc, exp, argIndex))

                override fun transformSimpleHash(
                    acc: QuantVarsAndExpPointer,
                    length: TACExpr,
                    args: List<TACExpr>,
                    hashFamily: HashFamily,
                    tag: Tag?
                ): TACExpr {
                    val ls = listOf(length) + args
                    return txf {
                        Apply(
                            simpleHashApp(ls.size, hashFamily),
                            ls.mapIndexed { i, e ->
                                if (hashFamily.requiresLargeGaps) {
                                    toSkeyOptRec(acc, e, i)
                                } else {
                                    // in the no-large-gaps case, the signature is bit256^n -> bit256
                                    fromSkeyOptRec(acc, e, i)
                                }
                            }
                        )
                    }
                }

                override fun transformLongStore(acc: QuantVarsAndExpPointer, e: TACExpr.LongStore): TACExpr {
                    if (isStorageAccess(currentPtr!!, oldGraph)) {
                        throw UnsupportedOperationException("there are no longstores for storage rright??")
                    }
                    return super.transformLongStore(acc, e)
                }

                override fun transformStore(
                    acc: QuantVarsAndExpPointer,
                    base: TACExpr,
                    loc: TACExpr,
                    value: TACExpr,
                    tag: Tag?
                ): TACExpr {
                    val baseTransformed = transformArg(acc, base, 0)

                    /*
                     * we're switching the type of the storage arrays to skey -> Bit256
                     * (but not the type of bytemaps and such)
                     * thus a Store might require conversions of the store location
                     *   - if the Store is to storage, we need to make sure that the `loc` argument is an skey
                     *   - if the Store is to a ghostmap, we may need to convert from skey to an int type (currently,
                     *     we never convert ghostmap types to skeys (though it might be a good idea for the "bytesblob"
                     *     use case
                     */
                    val locTransformed =
                        if (isStorageAccess(acc.expPointer, oldGraph)) {
                            toSkeyOptRec(acc, loc, 1)
                        } else if (isGhostMapWithNonSkeyParam(base, 0)) {
                            fromSkeyOptRec(acc, loc, 1)
                        } else {
                            transformArg(acc, loc, 1)
                        }

                    val valueTransformed = fromSkeyOptRec(acc, value, 2)

                    return TACExpr.Store(baseTransformed, locTransformed, valueTransformed)
                }

                override fun transformMultiDimStore(
                    acc: QuantVarsAndExpPointer,
                    base: TACExpr,
                    locs: List<TACExpr>,
                    value: TACExpr,
                    tag: Tag?
                ): TACExpr {
                    val baseTransformed = transformArg(acc, base, 0)

                    val locsTransformed =
                        locs.mapIndexed { i, loc ->
                            if (isStorageAccess(acc.expPointer, oldGraph)) {
                                error("multidim store on a storage map is unexpected")
                            } else if (isGhostMapWithNonSkeyParam(base, i)) {
                                fromSkeyOptRec(acc, loc, 1 + i)
                            } else {
                                transformArg(acc, loc, 1 + i)
                            }
                        }

                    val valueTransformed = fromSkeyOptRec(acc, value, locs.size + 1)

                    return TACExpr.MultiDimStore(baseTransformed, locsTransformed, valueTransformed)
                }

                override fun transformVecAdd(acc: QuantVarsAndExpPointer, e: TACExpr.Vec.Add): TACExpr =
                    handleAdd(acc, e)

                override fun transformVecIntAdd(acc: QuantVarsAndExpPointer, e: TACExpr.Vec.IntAdd): TACExpr =
                    handleAdd(acc, e)


                private fun handleAdd(
                    acc: QuantVarsAndExpPointer,
                    e: TACExpr.Vec,
                ): TACExpr {
                    var skeyArgument: TACExpr? = null
                    val argsTransformed = e.ls.mapIndexedNotNull { i, arg ->
                        val transformed = transformArg(acc, arg, i)
                        if (isSkey(acc.expPointer.extend(i))) {
                            check(skeyArgument == null) { "Trying to add two storage keys -- something is wrong." }
                            skeyArgument = transformed
                            null // leave this one out from the numeric arguments
                        } else {
                            transformed
                        }
                    }
                    val intAdd =
                        when (argsTransformed.size) {
                            0 -> error("no summands left, should not happen")
                            1 -> argsTransformed.first()
                            else -> when (e) {
                                is TACExpr.Vec.Add -> TACExpr.Vec.Add(argsTransformed)
                                is TACExpr.Vec.IntAdd -> TACExpr.Vec.IntAdd(argsTransformed)
                                else -> error("statically unreachable should have Add or IntAdd, got $e")
                            }
                        }
                    val newExp = if (skeyArgument == null) {
                        intAdd
                    } else {
                        wrapSkeyAdd(skeyArgument!!, intAdd)
                    }
                    return newExp
                }

                // these two can't be called because the override of transformBinOp interrupts that delegation
                override fun transformSub(acc: QuantVarsAndExpPointer, e: TACExpr.BinOp.Sub): TACExpr =
                    `impossible!`
                override fun transformIntSub(acc: QuantVarsAndExpPointer, e: TACExpr.BinOp.IntSub): TACExpr =
                    `impossible!`

                private fun handleSub(
                    acc: QuantVarsAndExpPointer,
                    e: TACExpr.BinOp,
                ): TACExpr {
                    require(e is TACExpr.BinOp.Sub || e is TACExpr.BinOp.IntSub)
                    { "call this only on subtractions, got \"$e\"" }
                    val o1transformed = transformArg(acc, e.o1, 0)
                    val o2transformed = transformArg(acc, e.o2, 1)
                    val o1IsSkey = isSkey(acc.expPointer.extend(0))
                    val o2IsSkey = isSkey(acc.expPointer.extend(1))
                    return when {
                        !o1IsSkey && !o2IsSkey -> {
                            e.rebuild(o1transformed, o2transformed)
                        }
                        o1IsSkey && !o2IsSkey -> {
                            val subAsAdd = txf {
                                when (e) {
                                    is TACExpr.BinOp.Sub ->
                                        twosUnwrap(Sub(number(0), o2transformed))

                                    is TACExpr.BinOp.IntSub ->
                                        IntSub(number(0), o2transformed)

                                    else -> `impossible!`
                                }
                            }
                            wrapSkeyAdd(o1transformed, subAsAdd)
                        }
                        !o1IsSkey && o2IsSkey -> {
                            e.rebuild(o1transformed, fromSkey(o2transformed, txf))
                        }
                        else -> {
                            // o1IsSkey && o2IsSkey (kotlin doesn't let me write it that way ..)
                            e.rebuild(fromSkey(o1transformed, txf), fromSkey(o2transformed, txf))
                        }
                    }
                }


                /** Some bifs need their arguments converted by from_skey, others don't.
                 * (Actually, this looks a bit weird ... but looks to be working .. review some time) * */
                override fun transformApply(
                    acc: QuantVarsAndExpPointer,
                    f: TACExpr.TACFunctionSym,
                    ops: List<TACExpr>,
                    tag: Tag?
                ): TACExpr {
                    fun opsTransformed() =
                        TACExpr.Apply(f, ops.mapIndexed { i, arg -> transformArg(acc, arg, i) }, tag)

                    fun opsTransformedFromSkeyOpt() =
                        TACExpr.Apply(f, ops.mapIndexed { i, arg -> fromSkeyOptRec(acc, arg, i) }, tag)
                    return when (f) {
                        is TACExpr.TACFunctionSym.Adhoc -> opsTransformed()
                        is TACExpr.TACFunctionSym.BuiltIn -> {
                            when (f.bif) {
                                TACBuiltInFunction.DisjointSighashes -> {
                                    logger.warn {
                                        "according to it's documentation, this should have been simplified " +
                                            "away at this point: ${f.bif}"
                                    }
                                    opsTransformed()
                                }
                                is TACBuiltInFunction.NondetFunction,
                                is TACBuiltInFunction.TwosComplement.Unwrap,
                                is TACBuiltInFunction.TwosComplement.Wrap,

                                // these two require a numerical (i.e., non-skey) argument
                                is TACBuiltInFunction.SafeMathNarrow,
                                is TACBuiltInFunction.SafeMathPromotion,

                                is TACBuiltInFunction.SignedPromotion,
                                is TACBuiltInFunction.UnsignedPromotion,
                                is TACBuiltInFunction.SafeSignedNarrow,
                                is TACBuiltInFunction.SafeUnsignedNarrow,
                                is TACBuiltInFunction.NoSMulOverAndUnderflowCheck,
                                is TACBuiltInFunction.WithSMTFunctionSymbol,
                                TACBuiltInFunction.PrecompiledECRecover -> opsTransformedFromSkeyOpt()

                                // these are basically identity functions (?) -- nooping
                                is TACBuiltInFunction.OpaqueIdentity -> opsTransformed()

                                TACBuiltInFunction.LinkContractAddress,
                                is Hash -> error(
                                    "input program to AnnotateSkeyBifs contains " +
                                        "a TACBuiltInFunction that is not expected at this point in the pipeline, " +
                                        "namely \"${f.bif}\" "
                                )
                                TACBuiltInFunction.ToStorageKey -> {
                                    TACExpr.Apply(
                                        ToSkey.toTACFunctionSym(),
                                        ops.mapIndexed { i, arg -> transformArg(acc, arg, i) },
                                        skeySort
                                    )
                                }

                                is TACBuiltInFunction.PartitionInitialize,
                                is TACBuiltInFunction.ReadTransientPartition -> `impossible!`
                            }
                        }
                    }
                }

                override fun transformBinOp(acc: QuantVarsAndExpPointer, e: TACExpr.BinOp): TACExpr {
                    // subtractions get special handling ..
                    if (e is TACExpr.BinOp.Sub || e is TACExpr.BinOp.IntSub) {
                        return handleSub(acc, e)
                    }
                    // .. other binops trigger a from_skey conversion
                    val o1Transformed = fromSkeyOptRec(acc, e.o1, 0)
                    val o2Transformed = fromSkeyOptRec(acc, e.o2, 1)
                    /** Make sure that [DropBwNops] has done its job. */
                    check(when (e) {
                        is TACExpr.BinOp.BWOr ->
                            o1Transformed.evalAsConst() != BigInteger.ZERO &&
                                o2Transformed.evalAsConst() != BigInteger.ZERO
                        is TACExpr.BinOp.BWAnd ->
                            o1Transformed.evalAsConst() != MAX_EVM_UINT256 &&
                                o2Transformed.evalAsConst() != MAX_EVM_UINT256
                        is TACExpr.BinOp.ShiftRightLogical,
                        is TACExpr.BinOp.ShiftRightArithmetical,
                        is TACExpr.BinOp.ShiftLeft ->
                            o2Transformed.evalAsConst() != BigInteger.ZERO
                        else -> true
                    }) { "bitwise noops should have been dropped at this point" }
                    return e.rebuild(o1Transformed, o2Transformed)
                }

                override fun transformBinRel(acc: QuantVarsAndExpPointer, e: TACExpr.BinRel): TACExpr {
                    val o1Transformed = fromSkeyOptRec(acc, e.o1, 0)
                    val o2Transformed = fromSkeyOptRec(acc, e.o2, 1)
                    return e.rebuild(o1Transformed, o2Transformed)
                }

                override fun transformSelect(
                    acc: QuantVarsAndExpPointer,
                    base: TACExpr,
                    loc: TACExpr,
                    tag: Tag?
                ): TACExpr =
                    TACExpr.Select(
                        transformArg(acc, base, 0),
                        if (isStorageAccess(acc.expPointer, oldGraph)) {
                            toSkeyOptRec(acc, loc, 1)
                        } else if (isGhostMapWithNonSkeyParam(base, 0)) {
                            fromSkeyOptRec(acc, loc, 1)
                        } else {
                            transformArg(acc, loc, 1)
                        },
                        tag
                    )

                override fun transformIte(
                    acc: QuantVarsAndExpPointer,
                    i: TACExpr,
                    t: TACExpr,
                    e: TACExpr,
                    tag: Tag?
                ): TACExpr =
                    if (isSkey(acc.expPointer)) {
                        TACExpr.TernaryExp.Ite(
                            transformArg(acc, i, 0),
                            toSkeyOptRec(acc, t, 1),
                            toSkeyOptRec(acc, e, 2),
                        )
                    } else {
                        TACExpr.TernaryExp.Ite(
                            transformArg(acc, i, 0),
                            fromSkeyOptRec(acc, t, 1),
                            fromSkeyOptRec(acc, e, 2),
                        )
                    }
            }

            override fun mapAssignExpCmd(lhs: TACSymbol.Var, rhs: TACExpr, metaMap: MetaMap): TACCmd.Simple {
                val lhsTransformed = mapLhs(lhs, index = 0)
                val rhsTransformed = mapExpr(rhs, index = 1)
                val rhsPtr = ExpPointer.rhsOfAssign(currentPtr!!)
                val rhsToSkeyOpt =
                    if (lhs in skeyDetection.skeyVars && !isSkey(rhsPtr)) {
                        // NB: it might be nicer not to have to rely on the skeyVars field, can we just ask isSkey(lhs)?
                        toSkey(rhsTransformed, rhsPtr, txf).first
                    } else {
                        rhsTransformed
                    }
                return TACCmd.Simple.AssigningCmd.AssignExpCmd(lhsTransformed, rhsToSkeyOpt, metaMap)
            }
        }

        val transformOperators = tacProgram.toPatchingProgram()
        tacProgram.analysisCache.graph.commands.forEach { ltacCmd ->
            transformOperators.update(ltacCmd.ptr) { c ->
                check(c == ltacCmd.cmd)
                // updates skeyVars
                val l = wrapSkeys.mapCommand(ltacCmd)
                check(l.size == 1)
                l.first()
            }
        }

        val transformedOperators = transformOperators.toCodeNoTypeCheck(tacProgram)

        val transformVariables = transformedOperators.toPatchingProgram()
        transformedOperators.analysisCache.graph.commands.forEach { ltacCmd ->
            transformVariables.update(ltacCmd.ptr) { c ->
                val l = object : DefaultTACCmdMapperWithPointer() {
                    override fun mapVar(t: TACSymbol.Var): TACSymbol.Var {
                        val r = if (t in skeyDetection.skeyVars) {
                            val switched = switchToSkeySort(t)
                            // if [t] is also registered as a (scalar) ghost, we need to update it in the corresponding
                            // infra accordingly -- see https://certora.atlassian.net/browse/CERT-2320 for background
                            val ufVersionOfT =
                                transformedOperators.symbolTable.getUninterpretedFunctions(t.toSMTRep()).firstOrNull()
                            if (ufVersionOfT != null) {
                                transformVariables.replaceScalarUf(
                                    ufVersionOfT,
                                    ufVersionOfT.copy(returnSort = skeySort),
                                    t.asSym(),
                                    switched.asSym(),
                                )
                            }
                            switched
                        } else {
                            t
                        }
                        return r
                    }
                }.mapCommand(ltacCmd)
                l.firstOrNull() ?: error("not expecting to get more than one command when transforming " +
                    "input command \"$c\", but got output \"$l\"")
            }

        }

        transformVariables.addUninterpretedSort(Hash.skeySort)
        skeyDetection.skeyVars.forEach { v -> transformVariables.replaceVarDecl(v, switchToSkeySort(v)) }
        return transformVariables.toCode(transformedOperators)
    }

    private fun isGhostMapWithNonSkeyParam(base: TACExpr, paramIndex: Int): Boolean =
        base.tagAssumeChecked.let { baseType ->
                baseType is Tag.GhostMap &&
                baseType.paramSorts[paramIndex] != Hash.skeySort
        }

    private fun switchToSkeySort(t: TACSymbol.Var) = t.copy(tag = Hash.skeySort)

    private fun toSkey(exp: TACExpr, ptr: ExpPointer, txf: TACExprFact): Pair<TACExpr, ExpPointer> {
        return if (exp is TACExpr.Sym.Const && exp.s.value < Config.MaxBaseStorageSlot.get()) {
            txf.Apply(
                TACExpr.TACFunctionSym.BuiltIn(Basic),
                listOf(exp.convertBoolToInt())
            ) to ptr.extend(0)
        } else if (exp is TACExpr.Sym.Var && exp.s.meta[CVL_TYPE]?.let { t ->
                (t.isSubtypeOf(CVLType.PureCVLType.Primitive.UIntK(256)) ||
                t.isSubtypeOf(CVLType.PureCVLType.Primitive.BytesK(32))) ||
                    t.isSubtypeOf(CVLType.PureCVLType.Primitive.AccountIdentifier)
                    && t !is CVLType.PureCVLType.Primitive.IntK
            } == true && exp.s.tag is Tag.Int) {
            // int variables that are coming from uint256/bytes32 or subtypes (but not signed)
            // need to be converted to bit256 with a safe narrow
            txf.Apply(
                TACExpr.TACFunctionSym.BuiltIn(ToSkey),
                listOf(
                    Apply(
                        TACBuiltInFunction.SafeMathNarrow(Tag.Bit256).toTACFunctionSym(),
                        listOf(exp),
                        Tag.Bit256
                    )
                )
            ) to ptr.extend(0)
        } else {
            txf.Apply(
                TACExpr.TACFunctionSym.BuiltIn(ToSkey),
                listOf(exp.convertBoolToInt()),
            ) to ptr.extend(0)
        }
    }

    private fun fromSkey(exp: TACExpr, txf: TACExprFact): TACExpr {
        return txf.Apply(
            TACExpr.TACFunctionSym.BuiltIn(FromSkey),
            listOf(exp)
        )
    }

    private fun wrapSkeyAdd(skeyExp: TACExpr, nonSkeyArg: TACExpr): TACExpr {
        return TACExpr.Apply(
            TACExpr.TACFunctionSym.BuiltIn(Addition),
            listOf(skeyExp, nonSkeyArg).map { it.convertBoolToInt() },
            Hash.skeySort
        )
    }

    private fun TACExpr.convertBoolToInt(): TACExpr =
        when (this.tag) {
            Tag.Bool -> TACExpr.TernaryExp.Ite(
                this,
                TACSymbol.Const(BigInteger.ONE).asSym(),
                TACSymbol.Const(BigInteger.ZERO).asSym(),
            )
            else -> this
        }
}
