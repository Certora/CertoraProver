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

package spec.cvlast.transformations

import spec.CastType
import spec.cvlast.*
import spec.cvlast.transformer.CVLAstTransformer
import spec.cvlast.transformer.CVLCmdTransformer
import spec.cvlast.transformer.CVLExpTransformer
import utils.CollectingResult
import utils.CollectingResult.Companion.lift
import utils.CollectingResult.Companion.map
import utils.CollectingResult.Companion.safeForce

class GhostApplicationRewriter(symbolTable: CVLSymbolTable) : CVLAstTransformer<Nothing>(
    object : CVLCmdTransformer<Nothing>(object : CVLExpTransformer<Nothing> {
        override fun arrayderef(exp: CVLExp.ArrayDerefExp): CollectingResult<CVLExp.ArrayDerefExp, Nothing> {
            tailrec fun traverse(
                arrayDeref: CVLExp.ArrayDerefExp,
                cont: (baseExp: CVLExp, ty: CVLType.PureCVLType?) -> CVLExp.ArrayDerefExp
            ) : CVLExp.ArrayDerefExp {
                val baseArray = arrayDeref.array
                val mappedIndex = super.expr(arrayDeref.index).safeForce()
                val rewriteIndex = { baseExp: CVLExp, ty: CVLType.PureCVLType? ->
                    val (instIdx, nxtType) = if(ty != null && ty is CVLType.PureCVLType.Ghost.Mapping) {
                        // do the instrumentation, we have type information
                        val instIdx = if(ty.key is CVLType.PureCVLType.Primitive.HashBlob) {
                            CVLExp.CastExpr(
                                arg = mappedIndex,
                                toCastType = CVLType.PureCVLType.Primitive.HashBlob,
                                castType = CastType.CONVERT,
                                tag = CVLExpTag(
                                    scope = mappedIndex.getScope(),
                                    range = mappedIndex.getRangeOrEmpty(),
                                    type = null
                                )
                            )
                        } else {
                            mappedIndex
                        }
                        instIdx to ty.value
                    } else {
                        mappedIndex to null
                    }
                    cont(arrayDeref.copy(
                        array = baseExp,
                        index = instIdx
                    ), nxtType)
                }
                return if(baseArray is CVLExp.VariableExp) {
                    val ty = symbolTable.lookUpNonFunctionLikeSymbol(baseArray.id, baseArray.getScope())?.let {
                        it as? CVLSymbolTable.SymbolInfo.CVLValueInfo
                    }?.getCVLTypeOrNull()?.let {
                        it as? CVLType.PureCVLType
                    }
                    rewriteIndex(baseArray, ty)
                } else if(baseArray is CVLExp.ArrayDerefExp) {
                    traverse(baseArray, cont = rewriteIndex)
                } else {
                    rewriteIndex(super.expr(baseArray).safeForce(), null)
                }
            }
            return traverse(arrayDeref = exp) { e, _ -> e as CVLExp.ArrayDerefExp }.lift()
        }

        override fun ghost(exp: CVLExp.ApplyExp.Ghost): CollectingResult<CVLExp.ApplyExp.Ghost, Nothing> {
            /* NB: this will not properly detect whether we're in two state or not (no typeenv)
               but that doesn't matter: two state or no, a ghost function will still have a bytes at the same
               argument position
             */
            return super.ghost(exp).map { app ->
                val paramTypes = symbolTable.lookUpFunctionLikeSymbol(app.id, app.getScope()).let {
                    it as? CVLSymbolTable.SymbolInfo.CVLFunctionInfo
                }?.impFuncs?.singleOrNull()?.let {
                    it as? CVLGhostDeclaration.Function
                }?.paramTypes ?: return@map app
                // arity mismatch; not our problem, let the typechecker sort this out
                if(app.args.size != paramTypes.size) {
                    return@map app
                }
                val instrumented = app.args.zip(paramTypes) { arg, ty ->
                    if(ty is CVLType.PureCVLType.Primitive.HashBlob) {
                        CVLExp.CastExpr(
                            tag = CVLExpTag(
                                scope = arg.getScope(),
                                range = arg.getRangeOrEmpty(),
                                type = null
                            ),
                            arg = arg,
                            castType = CastType.CONVERT,
                            toCastType = CVLType.PureCVLType.Primitive.HashBlob
                        )
                    } else {
                        arg
                    }
                }
                app.copy(
                    args = instrumented
                )
            }
        }
    }) {

    }
)
