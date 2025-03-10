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

package smt.axiomgenerators

import datastructures.stdcollections.*
import evm.EVM_BITWIDTH256
import smt.GenerateEnv
import smt.axiomgenerators.fullinstantiation.ArrayGenerator.Companion.arrayGenerator
import smt.axiomgenerators.fullinstantiation.ArrayGenerator.Companion.arraysFromExpr
import smt.newufliatranslator.AxiomGenerator
import smt.solverscript.LExpressionFactory
import smt.solverscript.SmtTheory
import smt.solverscript.functionsymbols.*
import spec.cvlast.CVLType
import tac.Tag
import tac.isMapType
import utils.*
import utils.ModZm.Companion.lowOnes
import utils.SignUtilities.maxSignedValueOfBitwidth
import utils.SignUtilities.minSignedValueOfBitwidth
import utils.SignUtilities.to2sComplement
import vc.data.LExpression
import vc.data.TACBuiltInFunction
import vc.data.TACKeyword.BALANCE
import vc.data.TACKeyword.EXTCODESIZE
import vc.data.TACMeta.DIRECT_STORAGE_ACCESS_TYPE
import vc.data.TACSymbol.Var.Companion.hasKeyword
import vc.gen.LExpVCMetaData

/**
 * Adds axioms to bound the values of ghost variables, ghost functions, and direct storage accesses.
 *
 * If a function/storage is queried within some quantified expression, then one axiom is added:
 *    `forall x. f(x) is within bounds`
 * Otherwise, for every instance `f(term)` in the vc, an axiom is added to bound that specific instance.
 *
 * Every ghost variable gets an axiom to bound its value according to its type.
 *
 * Any variable that has a [Tag.Bit256] is bounded to be in [0, 2^256).
 */
class TypeBoundsGenerator(val lxf: LExpressionFactory, val targetTheory: SmtTheory) : AxiomGenerator() {

    companion object {
        /**
         * Returns the bounding expression for [e] according to [cvlType] and if it's not available, then according to
         * [tag]. If there is no need for a bounding expression, then this returns `null`.
         *
         * If [e] is null then this returns TRUE instead of the actual bounding expression. It's used for checking
         * if the bounding expression is at all necessary.
         */
        fun lExpressionBoundsOf(
            lxf: LExpressionFactory,
            e: LExpression?,
            cvlType: CVLType?,
            tag: Tag?,
            twosComplement: Boolean = false
        ): LExpression? =
            lxf {
                fun ret(exp: LExpressionFactory.(LExpression) -> LExpression) =
                    if (e == null) TRUE else lxf.exp(e)

                cvlType?.let { type ->
                    when (type) {
                        is CVLType.PureCVLType.Primitive.UIntK -> ret {
                            and(it ge ZERO, it le litInt(lowOnes(type.bitWidth)))
                        }

                        is CVLType.PureCVLType.Primitive.AccountIdentifier -> ret {
                            and(it ge ZERO, it le litInt(lowOnes(type.bitWidth)))
                        }

                        is CVLType.PureCVLType.Primitive.BytesK ->
                            if (type.bitWidth == 256) {
                                ret {
                                    and(it ge ZERO, it le MAX_UINT)
                                }
                            } else {
                                ret {
                                    (it % twoTo(EVM_BITWIDTH256 - type.bitWidth)) eq ZERO
                                }
                                // TODO: This expression should actually be given to `visit` of linear math axiom generator
                                //  otherwise it would be wrong in LIA.
                                //  architecturally this is a pain, but the simplest solution is a move to give modulo
                                //  constant operations directly to the solvers.
                            }

                        is CVLType.PureCVLType.Primitive.IntK ->
                            ret {
                                val w = type.bitWidth
                                if (twosComplement) {
                                    or(
                                        it.inclusiveWithin(ZERO, litInt(maxSignedValueOfBitwidth(w))),
                                        it.inclusiveWithin(
                                            litInt(minSignedValueOfBitwidth(w).to2sComplement()),
                                            MAX_UINT
                                        )
                                    )
                                } else {
                                    it.inclusiveWithin(
                                        litInt(minSignedValueOfBitwidth(w)),
                                        litInt(maxSignedValueOfBitwidth(w))
                                    )
                                }
                            }

                        is CVLType.PureCVLType.Primitive.Bool -> null
                        else -> null
                    }
                } ?: when (tag) {
                    is Tag.Bits -> ret { it.within(ZERO, litInt(tag.modulus)) }
                    Tag.Bool -> null // smt will take care of bool types.
                    Tag.Int -> null
                    is Tag.UserDefined.UninterpretedSort -> null
                    null -> null // beats me why this is even possible...
                    else -> error("Shouldn't try to bound variable of tag $tag")
                }
            }

    }


    private val bound2to256Axiom = UnaryIntAxiomDef(lxf, "evm_bound_2to256", "EVM 256 bit word bounds") { a ->
        a.within(ZERO, TwoTo256)
    }

    // A simple cache for these axioms, an axiom for each pair of <type, twosComplement>
    private val boundCVLType = mutableMapOf<Pair<CVLType, Boolean>, UnaryIntAxiomDef>()

    /** [twosComplement] being true means that signed ints get an EVM-style representation, i.e. twos complement; note
     * that the type might still be unsigned when this is true. */
    private fun boundAxiom(type: CVLType, twosComplement: Boolean): UnaryIntAxiomDef? =
        if (lExpressionBoundsOf(lxf, null, type, null, twosComplement) != lxf.TRUE) {
            null
        } else {
            boundCVLType.getOrPut(type to twosComplement) {
                val name = (type as? CVLType.PureCVLType)?.toString() ?: error("Not a simple type? ${type.javaClass}")
                val twosStr = ite(twosComplement, "_2s", "")
                UnaryIntAxiomDef(
                    lxf,
                    "bound_$name$twosStr",
                    "CVL type bounds for $name$twosStr"
                ) { lExpressionBoundsOf(lxf, it, type, null, twosComplement)!! }
            }
        }

    private fun boundGhostAxiom(id: String) =
        ghostInfos[id]?.resultType?.let { type ->
            boundAxiom(type, twosComplement = false)
        }

    private fun directStorageBoundAxiom(type: CVLType) =
        boundAxiom(type, twosComplement = true)


    private class GhostInfo(val resultType: CVLType, val paramsType: List<CVLType>)

    private lateinit var ghostInfos: Map<String, GhostInfo>

    override fun visitVCMetaData(lExpVc: LExpVCMetaData) {
        ghostInfos = lExpVc.tacProgram.symbolTable.uninterpretedFunctions()
            .filter { it.returnSort != TACBuiltInFunction.Hash.skeySort } // no type-based bounds for skeys
            .associate { it.name to GhostInfo(it.cvlResultType, it.cvlParamTypes) }
    }


    /** For every array that is queried in a quantified way, holds the expression: `forall x. a(x) is in bounds` */
    private val quantifiedAxioms = mutableMapOf<LExpression.Identifier, LExpression>()

    private val nonQuantifiedSelects = mutableSetOf<LExpression>()

    private val primitiveStorageAccesses = mutableMapOf<String, CVLType>()

    private val constantSymbols = mutableSetOf<ConstantSymbol>()

    override fun visit(e: LExpression, env: GenerateEnv) {
        when {
            e.isApplyOf<ConstantSymbol>() -> constantSymbols.add(e.f as ConstantSymbol)
            e is LExpression.Identifier -> constantSymbols.add(ConstantSymbol(e.id, e.tag))
        }
        when {
            e is LExpression.Identifier ->
                // registers the axiom
                e.meta[DIRECT_STORAGE_ACCESS_TYPE]
                    ?.let {
                        directStorageBoundAxiom(it)
                        if (e.tag == Tag.Bit256) {
                            // and store any direct access to primitive storage
                            primitiveStorageAccesses[e.id] = it
                        }
                    }
                    ?: boundGhostAxiom(e.id)

            e.isSelect ->
                if (env.isEmpty()) {
                    nonQuantifiedSelects += e
                } else {
                    val qVars = env.quantifiedVariables
                    if (e.locs.any { sub ->
                            sub.contains { it is LExpression.Identifier && it in qVars }
                        }) {
                        arraysFromExpr(e.map)
                            .filterNot { it in quantifiedAxioms }
                            .forEach { a ->
                                val axiom =
                                    if (DIRECT_STORAGE_ACCESS_TYPE in a.meta) {
                                        quantifiedDirectStorageAxiom(a)
                                    } else {
                                        quantifiedGhostAxiom(a)
                                    } ?: return@forEach
                                quantifiedAxioms[a] = axiom
                                axiomGeneratorContainer.arrayGenerator?.visit(axiom, GenerateEnv())
                            }
                    } else {
                        nonQuantifiedSelects += e
                    }
                }
        }
    }

    override fun beforeScriptFreeze() {
        constantSymbols.forEach { sym ->
            boundGhostAxiom(sym.name)
        }
    }


    override fun yield(axiomContainer: AxiomContainer) {
        constantSymbols.forEach { sym ->
            // constrain primitive variables
            val axiom = boundGhostAxiom(sym.name)
                ?.takeIf { sym.tag !is Tag.GhostMap } // ghost variable
                ?: runIf(sym.tag == Tag.Bit256) {
                    primitiveStorageAccesses[sym.name]
                        ?.let(::directStorageBoundAxiom) // direct access of primitive storage variable
                        ?: bound2to256Axiom // otherwise, just the standard EVM bound
                }
            axiom?.addAllToContainer(
                axiomContainer,
                listOf(lxf.const(sym.name, sym.tag))
            )
        }

        for (e in nonQuantifiedSelects) {
            val arrays = arraysFromExpr(e.map)
            if (arrays.any { it !in quantifiedAxioms }) {
                // doesn't matter which array we take, they all have the same type.
                val a = arraysFromExpr(e.map).first()
                val axiom = a.meta[DIRECT_STORAGE_ACCESS_TYPE]
                    ?.let(::directStorageBoundAxiom)
                    ?: if (a.meta.hasKeyword(BALANCE) || a.meta.hasKeyword(EXTCODESIZE)) {
                        bound2to256Axiom
                    } else {
                        boundGhostAxiom(a.id)
                    }
                axiom?.addAllToContainer(axiomContainer, listOf(e))
            }
        }

        quantifiedAxioms.values
            .forEach(axiomContainer::addAxiom)
    }


    private fun quantifiedGhostAxiom(a: LExpression.Identifier): LExpression? {
        check(a.tag.isMapType())
        val ghostInfo = ghostInfos[a.id]
            ?: return null
        val resultBound = boundGhostAxiom(a.id)
            ?: return null
        return lxf {
            val (qVars, boundExprs) = ghostInfo.paramsType
                .mapIndexed { i, type ->
                    val tag = when (val t = a.tag) {
                        is Tag.GhostMap -> t.paramSorts[i]
                        Tag.ByteMap -> Tag.Int
                        Tag.WordMap -> axiomGeneratorContainer.sortOfStorageKeys
                        else -> error("Don't know what is the parameter type of quantified map $a with tag $t")
                    }
                    val qVar = LExpression.Identifier("x!$i", tag)
                    val bound = lExpressionBoundsOf(lxf, qVar, type, null, twosComplement = false) ?: lxf.TRUE
                    qVar to bound
                }
                .unzip()

            val selectExpr = when (qVars.size) {
                0 -> error("no indices for array $a of sort ${a.tag}; should not happen")
                1 -> lxf.select(a, qVars.single(), a.tag, null)
                else -> lxf.multiSelect(a, qVars, a.tag)
            }

            forall(qVars) {
                and(boundExprs) implies resultBound.applyDef(selectExpr)
            }
        }
    }

    /**
     * Note that this generates somewhat of an anomaly:
     *    `forall x. storage-access[x] is in bounds`
     * Normally, we will see storage accesses indexed using an skey-expression, and not directly. However, in this
     * case, since we know storage was split correctly (otherwise we can't have direct-storage-access), we can just
     * quantify over the whole array/mapping for this specific part of storage.
     */
    private fun quantifiedDirectStorageAxiom(base: LExpression.Identifier): LExpression? {
        val type = base.meta[DIRECT_STORAGE_ACCESS_TYPE]!!
        val resultBound = directStorageBoundAxiom(type) ?: return null
        val paramTag = axiomGeneratorContainer.sortOfStorageKeys
        return lxf {
            val qVar = freshVariable("d", paramTag)
            val bound = lExpressionBoundsOf(lxf, qVar, null, paramTag, twosComplement = true)
            val selectExpr = select(base, qVar, Tag.WordMap, paramTag)
            forall(qVar) {
                if (bound == null) {
                    resultBound.applyDef(selectExpr)
                } else {
                    bound implies resultBound.applyDef(selectExpr)
                }
            }
        }
    }

}

