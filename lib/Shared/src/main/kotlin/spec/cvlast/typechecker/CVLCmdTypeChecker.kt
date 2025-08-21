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

@file:Suppress("NAME_SHADOWING") // Shadowing is used purposefully
package spec.cvlast.typechecker

import config.Config
import datastructures.stdcollections.*
import spec.CVLKeywords
import spec.cvlast.*
import spec.cvlast.typedescriptors.*
import spec.isWildcard
import utils.*
import utils.CollectingResult.Companion.asError
import utils.CollectingResult.Companion.bind
import utils.CollectingResult.Companion.flatten
import utils.CollectingResult.Companion.flattenToVoid
import utils.CollectingResult.Companion.lift
import utils.CollectingResult.Companion.map
import utils.CollectingResult.Companion.mapErrors
import utils.CollectingResult.Companion.ok
import utils.CollectingResult.Companion.transpose
import utils.ErrorCollector.Companion.collectingErrors

class CVLCmdTypeChecker(
    private val symbolTable: CVLSymbolTable
) {
    private val expTypeChecker = CVLExpTypeChecker(symbolTable)
    private val typeTypeChecker = CVLTypeTypeChecker(symbolTable)

    fun typeCheckCmds(cmds: List<CVLCmd>): CollectingResult<List<CVLCmd>, CVLError> {
        return cmds.map { cmd -> typeCheckCmd(cmd) }.flatten()
    }

    fun typeCheckCmd(cmd: CVLCmd): CollectingResult<CVLCmd, CVLError> {
        return when (cmd) {
            is CVLCmd.Composite.Block -> cmd.block.map { typeCheckCmd(it) }.flatten()
                .bind { cmd.copy(block = it).lift() }
            is CVLCmd.Composite.If -> typeCheckIfCmd(cmd)
            is CVLCmd.Simple.Apply -> typeCheckApplyCmd(cmd)
            is CVLCmd.Simple.Assert -> typeCheckAssertCmd(cmd)
            is CVLCmd.Simple.Satisfy -> typeCheckSatisfyCmd(cmd)
            is CVLCmd.Simple.AssumeCmd.Assume -> typeCheckAssumeCmd(cmd)
            is CVLCmd.Simple.AssumeCmd.AssumeInvariant -> typeCheckAssumeInvariantCmd(cmd)
            is CVLCmd.Simple.Declaration -> typeCheckDeclarationCmd(cmd)
            is CVLCmd.Simple.Definition -> typeCheckDefinitionCmd(cmd)
            is CVLCmd.Simple.Exp -> typeCheckExpCmd(cmd)
            is CVLCmd.Simple.Havoc -> typeCheckHavocCmd(cmd)
            is CVLCmd.Simple.Label.End -> typeCheckLabelCmd(cmd)
            is CVLCmd.Simple.Label.Start -> typeCheckLabelCmd(cmd)
            is CVLCmd.Simple.ResetStorage -> typeCheckResetStorageCmd(cmd)
            is CVLCmd.Simple.ResetTransientStorage -> typeCheckResetTransientStorageCmd(cmd)
            is CVLCmd.Simple.Return -> typeCheckReturnCmd(cmd)
            is CVLCmd.Simple.Revert -> typeCheckRevertCmd(cmd)
            is CVLCmd.Simple.Nop -> cmd.lift()
        }
    }

    private fun typeCheckReturnCmd(cmd: CVLCmd.Simple.Return): CollectingResult<CVLCmd.Simple.Return, CVLError> {
        return cmd.scope.enclosingCVLFunction()?.let { enclosingFunction ->
            enclosingFunction.returnType().let { expectedReturnType ->
                cmd.exps.map {
                    expTypeChecker.typeCheck(it, cmd.typeEnv)
                }.flatten().bind { returnOperands: List<CVLExp> ->
                    val returnOperandsOK : VoidResult<CVLError> = when(expectedReturnType) {
                        CVLType.PureCVLType.Void -> {
                            if(returnOperands.isNotEmpty()) {
                                CVLError.General(
                                    cmd.range,
                                    "Cannot return values in CVL function ${enclosingFunction.functionName().methodId} that returns void."
                                ).asError()
                            } else {
                                ok
                            }
                        }
                        is CVLType.PureCVLType.TupleType -> {
                            if(returnOperands.size != expectedReturnType.elements.size) {
                                CVLError.General(
                                    cmd.range,
                                    "Incorrect number of values returned for CVL function ${enclosingFunction.functionName().methodId}: expected ${expectedReturnType.elements.size} but saw ${returnOperands.size}."
                                ).asError()
                            } else {
                                returnOperands.zip(expectedReturnType.elements).withIndex().map { (ind, expTypePair) ->
                                    val (returnComponent, expected) = expTypePair
                                    if(returnComponent.getCVLType() isConvertibleTo expected) {
                                        ok
                                    } else {
                                        CVLError.Exp(
                                            exp = returnComponent,
                                            message = "Expected type $expected in return position ${ind + 1} of CVL function ${enclosingFunction.functionName().methodId}, but $returnComponent is of type ${returnComponent.getCVLType()}"
                                        ).asError()
                                    }
                                }.flattenToVoid()
                            }
                        }
                        else -> if(returnOperands.size != 1) {
                            CVLError.General(
                                cmd.range,
                                "Multiple values are returned for function ${enclosingFunction.functionName().methodId} where only one was expected."
                            ).asError()
                        } else if(returnOperands.single().getCVLType() isConvertibleTo expectedReturnType) {
                            ok
                        } else {
                            CVLError.Exp(exp = returnOperands.single(), message = "Expected $expectedReturnType return type but ${returnOperands.single()} is of type ${returnOperands.single().getCVLType()}").asError()
                        }
                    }
                    returnOperandsOK.map { _ ->
                        cmd.copy(exps = returnOperands)
                    }
                }
            }
        } ?: CVLError.General(cmd.range, "Return statement not allowed here").asError()
    }

    private fun typeCheckRevertCmd(cmd: CVLCmd.Simple.Revert): CollectingResult<CVLCmd.Simple.Revert, CVLError> {
        if (Config.CvlFunctionRevert.get()) {
            return cmd.scope.enclosingCVLFunction()?.let {
                cmd.lift()
            } ?: RevertCmdOutsideOfFunction(cmd.range).asError()
        }
        return CVLError.General(cmd.range, "Revert statement is only supported when ${Config.CvlFunctionRevert.userFacingName()} is true.").asError()
    }

    private fun typeCheckResetStorageCmd(cmd: CVLCmd.Simple.ResetStorage): CollectingResult<CVLCmd.Simple.ResetStorage, CVLError> {
        return expTypeChecker.typeCheck(cmd.exp, cmd.typeEnv).bind { exp ->
            if (exp.getCVLType() is CVLType.PureCVLType.Primitive.CodeContract ||
                (exp.getCVLType() is CVLType.PureCVLType.Primitive.AccountIdentifier && exp is CVLExp.VariableExp && exp.id == CVLKeywords.allContracts.keyword)) {
                cmd.copy(exp = exp).lift()
            } else {
                ResetStorageOnNonContract(exp).asError()
            }
        }
    }

    private fun typeCheckResetTransientStorageCmd(cmd: CVLCmd.Simple.ResetTransientStorage): CollectingResult<CVLCmd.Simple.ResetTransientStorage, CVLError> {
        return expTypeChecker.typeCheck(cmd.exp, cmd.typeEnv).bind {
            cmd.copy(exp = it).lift()
        }
    }
    private fun typeCheckHavocTarget(
        target: CVLExp.HavocTarget,
        typeEnv: CVLTypeEnvironment,
        scope: CVLScope,
    ): CollectingResult<WithEnv<CVLExp.HavocTarget>, CVLError> {
        /** first, typecheck as a [CVLExp], which has the side-effect of adding a [StorageAccessMarker] annotation */
        val typeCheckAsExp = expTypeChecker.typeCheck(target, typeEnv)

        /** now do checks specific to havoc targets, under the assumption that any storage access detection has already happened */
        return typeCheckAsExp.bind { target ->
            when (target) {
                is CVLExp.ArrayDerefExp,
                is CVLExp.FieldSelectExp -> typeCheckStorageTarget(target, typeEnv)
                is CVLExp.VariableExp -> typeCheckVariableTarget(target, typeEnv, scope)
            }
        }
    }

    private fun typeCheckVariableTarget(
        variable: CVLExp.VariableExp,
        typeEnv: CVLTypeEnvironment,
        scope: CVLScope,
    ): CollectingResult<WithEnv<CVLExp.HavocTarget>, CVLError> = collectingErrors {
        check(variable.tag.annotation !is StorageAccessMarker) {
            "${variable::class.simpleName} cannot be a storage variable"
        }

        val id = variable.id
        val symbolInfo = symbolTable.lookUpNonFunctionLikeSymbol(id, scope)
            ?: symbolTable.lookUpFunctionLikeSymbol(id, scope)
            ?: error("$id not found in symbol table (should have already been checked)")
        val symbolValue = symbolInfo.symbolValue

        if (symbolValue !is CVLGhostWithAxiomsAndOldCopy) {
            val err = NonGhostVariableAsHavocTarget.fromSymbolValue(variable, symbolValue)
            returnError(err)
        }

        // two state versions of havoc'd variables are pushed onto the type environment stack
        // in order to shadow their original "one state" definition within the "assuming" expression
        val typeEnv = when (val ty = symbolInfo.getCVLTypeOrNull()) {
            is CVLType.PureCVLType.CVLValueType,
            is CVLType.PureCVLType.Sort -> {
                typeEnv.pushTwoStateVariable(variable)
            }
            is CVLType.PureCVLType.Ghost -> typeEnv.pushTwoStateGhost(symbolValue)
            null -> error("The symbol info for $id should be typed at this stage")
            else -> error("The ghost $id can't have type $ty. This should have been caught at an earlier stage")
        }

        variable to typeEnv
    }

    private fun typeCheckStorageTarget(
        target: CVLExp.HavocTarget,
        typeEnv: CVLTypeEnvironment,
    ): CollectingResult<WithEnv<CVLExp.HavocTarget>, CVLError> = collectingErrors {
        val exp = target.asExp

        if (exp.tag.annotation !is StorageAccessMarker) {
            returnError(HavocTargetLiteralMustBeStorageAccess(target))
        }

        val vmTypeDescriptor = exp.getCVLType().tryAs<CVLType.VM>()?.descriptor
        val badInput = when (vmTypeDescriptor) {
            is VMArrayTypeDescriptor -> UnsupportedStorageTarget.Input.EntireArray
            is VMMappingDescriptor -> UnsupportedStorageTarget.Input.EntireMapping
            is VMStructDescriptor -> UnsupportedStorageTarget.Input.Struct
            is EVMTypeDescriptor.EVMEnumDescriptor -> UnsupportedStorageTarget.Input.Enum
            else -> null
        }

        if (badInput != null) {
            returnError(UnsupportedStorageTarget(target, badInput))
        }

        target to typeEnv
    }

    private fun typeCheckHavocCmd(cmd: CVLCmd.Simple.Havoc): CollectingResult<CVLCmd.Simple.Havoc, CVLError> = collectingErrors {
        var typeEnv = cmd.typeEnv

        fun checkTargetAndUpdateEnv(target: CVLExp.HavocTarget): CollectingResult<CVLExp.HavocTarget, CVLError> {
            val typeCheck = typeCheckHavocTarget(target, typeEnv, cmd.scope)

            return typeCheck.map {
                val (target, updatedEnv) = it

                typeEnv = updatedEnv
                target
            }
        }

        val targets = cmd.targets.map(::checkTargetAndUpdateEnv).flatten()

        val assumingExp = cmd
            .assumingExp
            ?.let { exp -> expTypeChecker.typeCheck(exp, typeEnv) }
            ?.bind { exp -> typeCheckBooleanExp(exp, NonBoolExpression.Kind.ASSUMING_EXPR_OF_HAVOC) }
            ?.bind { exp ->
                /**
                 * this relies on the fact that [CVLExp.subExprsOfType] iterates in bottom-up order,
                 * therefore reverse-order iteration would yield the most complex expressions first.
                 *
                 * this matters here because we want the entire storage access as written by the user,
                 * and not any of its subexpressions (which would also be marked as storage accesses)
                 */
                val storageAccess = exp
                    .subExprsOfType<CVLExp>()
                    .asReversed()
                    .find { it.tag.annotation == StorageAccessMarker }

                if (storageAccess != null) {
                    StorageAccessInAssumingExpression(storageAccess).asError()
                } else {
                    exp.lift()
                }
            }
            .transpose()

        map(targets, assumingExp) { checkedTargets, checkedAssumingExp ->
            cmd.copy(targets = checkedTargets, assumingExp = checkedAssumingExp)
        }
    }

    private fun typeCheckExpCmd(cmd: CVLCmd.Simple.Exp): CollectingResult<CVLCmd.Simple.Exp, CVLError> {
        return expTypeChecker.typeCheck(cmd.exp, cmd.typeEnv).bind {
            cmd.copy(exp = it).lift()
        }
    }

    private fun typeCheckDefinitionCmd(cmd: CVLCmd.Simple.Definition): CollectingResult<CVLCmd.Simple.Definition, CVLError> {
        if (cmd.type == null) {
            // because no types are given, this command must be an assignment so all vars on the lhs must already
            // be declared
            for (_id in cmd.idL) {
                val id = _id.getIdLhs().id
                val symbolInfo = symbolTable.lookUpNonFunctionLikeSymbol(id, cmd.scope)
                if (symbolInfo != null && symbolInfo.symbolValue is CVLParam) {
                    return CVLError.General(
                        cmd.range, "assignments to a rule/subroutine parameter (here: $id) are forbidden"
                    ).asError()
                }
            }
        } else {
            if (cmd.idL.single().getIdLhs().isWildCard()) {
                return CVLError.General(cmd.range, "`_` is the wildcard variable. Cannot declare an argument with that name").asError()
            }
        }

        /**
         * Given an "output" list [t] of type [T], and a definition [cmd] which expects length([t])-ary variables,
         * do the pointwise typechecking according to [errorCheck].
         */
        fun <T> typeCheckMultiReturn(
            cmd: CVLCmd.Simple.Definition,
            t: List<T>,
            errorCheck: (CVLType.PureCVLType, T) -> VoidResult<String>
        ) : CollectingResult<CVLCmd.Simple.Definition, CVLError> {
            return if (cmd.idL.size != t.size) {
                CVLError.General(
                    cmd.range,
                    "Cannot assign ${t.size}-ary function to ${cmd.idL.size} variable${cmd.idL.singleOrNull()?.let { "" } ?: "s"}"
                ).asError()
            } else {
                cmd.idL.map { it.tag.getCVLTypeOrNull() as CVLType.PureCVLType }.zip(t)
                    .map { (lhs, rhs) ->
                        if (lhs == CVLType.PureCVLType.Bottom) {
                            ok
                        } else {
                            errorCheck(lhs, rhs)
                        }
                    }.flatten().map {
                        cmd
                    }.mapErrors { e -> CVLError.General(cmd.range, e) }
            }
        }

        // typecheck the rhs
        return expTypeChecker.typeCheck(cmd.exp, CVLTypeEnvironment.empty(cmd.range, cmd.scope)).bind { exp ->
            cmd.copy(exp = exp).lift()
        }.bind { cmd ->
            // typecheck [cmd.type]
            if (cmd.type != null) {
                typeTypeChecker.typeCheck(cmd.type, cmd.range, cmd.scope)
            } else {
                null.lift()
            }.bind { type ->
                if (type is CVLType.PureCVLType.Ghost.Mapping) {
                    CVLError.General(cmd.range, "CVL does not support mappings as local variables").asError()
                } else {
                    cmd.copy(type = type).lift()
                }
            }
        }.bind { cmd ->
            // typecheck the lhs
            cmd.idL.map { lhs ->
                expTypeChecker.typeCheck(lhs, CVLTypeEnvironment.empty(cmd.range, cmd.scope)).bind { idL ->
                    if (idL.getCVLTypeOrNull() == EVMBuiltinTypes.method) {
                        CVLError.General(
                            range = cmd.range, message = "Cannot assign to values of type method"
                        ).asError()
                    } else if(idL is CVLLhs.Array && idL.innerLhs.getCVLType() is CVLType.PureCVLType.CVLArrayType) {
                        CVLError.General(
                            range = cmd.range, message = "Cannot perform '$cmd', cannot assign to array elements. " +
                                "The same effect could be achieved with a 'require' statement: `require $idL == ${cmd.exp}`"
                        ).asError()
                    } else {
                        idL.lift()
                    }
                }
            }.flatten().bind { idL ->
                cmd.copy(idL = idL).lift()
            }
        }.bind { cmd ->
            val rhsType = cmd.exp.getCVLType()
            if (rhsType is CVLType.VM && rhsType.descriptor is VMFunctionReturn) {
                typeCheckMultiReturn(
                    cmd, rhsType.descriptor.returns
                ) { lhs, rhs ->
                    rhs.converterTo(lhs, rhsType.context.getVisitor()).bind { ok }
                }
            } else if(rhsType is CVLType.PureCVLType.TupleType) {
                /**
                 * Why the explicit check for [spec.cvlast.CVLType.PureCVLType.TupleType] here and then
                 * doing pointwise subtype checking ourselves, instead of folding this into the subtyping rule?
                 *
                 * As discussed in the documentation of the [spec.cvlast.CVLType.PureCVLType.TupleType], this choice is to emphasize
                 * that values with [spec.cvlast.CVLType.PureCVLType.TupleType] do not exist, not really. The existence of
                 * this type in the type system is simply to mark a function call as requiring special handling for
                 * assignments, which is exactly what we're doing here.
                 */
                typeCheckMultiReturn(
                    cmd, rhsType.elements
                ) { lhs, rhs ->
                    if(rhs isSubtypeOf lhs) {
                        ok
                    } else {
                        "Cannot assign value of type $rhs to variable of type $lhs".asError()
                    }
                }
            } else if (cmd.idL.isEmpty()) {
                throw CertoraInternalException(CertoraInternalErrorType.CVL_TYPE_CHECKER, "should not get empty lhs in $this")
            } else if (cmd.idL.size == 1) {
                val lhsType = cmd.idL.single().tag.getCVLTypeOrNull() as CVLType.PureCVLType
                if (rhsType is CVLType.PureCVLType.Void) {
                    createAssignmentErrorMessage(cmd, lhsType, rhsType).asError()
                } else if (lhsType != CVLType.PureCVLType.Bottom && !rhsType.isConvertibleTo(lhsType)) {
                    createAssignmentErrorMessage(cmd, lhsType, rhsType).asError()
                } else {
                    cmd.lift()
                }
            } else {
                CVLError.General(cmd.range, "Assignments to multiple lhs only work for calls to functions which return multiple values.").asError()
            }
        }
    }

    private fun createAssignmentErrorMessage(cmd: CVLCmd.Simple.Definition, lhsType: CVLType.PureCVLType, rhsType: CVLType): CVLError {
        return if (lhsType isSubtypeOf CVLType.PureCVLType.Primitive.Mathint && rhsType isConvertibleTo CVLType.PureCVLType.Primitive.Mathint) {
            val (requiredType, currTypeStr) = if (rhsType is CVLType.PureCVLType.Primitive.NumberLiteral) {
                // In case of number literals the type the user could use is the smallest non-literal supertype, so print that
                rhsType.smallestTypeForNumberLit() to ""
            } else {
                rhsType to " (of type $rhsType)"
            }
            val errStr = "assigning '${cmd.exp}'$currTypeStr to type $lhsType may cause overflow. Consider declaring ${cmd.idL} with type ${
                if (requiredType is CVLType.PureCVLType.Primitive.Mathint) {
                    "mathint"
                } else {
                    "$requiredType or type mathint"
                }
            }"
            CVLError.General(cmd.range, errStr)
        } else if (rhsType is CVLType.PureCVLType.Void && cmd.exp is CVLExp.ApplyExp) {
            // This is a parametric/overloading function call
            when (val context = cmd.exp.methodIdWithCallContext) {
                is SymbolicContractMethod -> ParametricReturn(cmd.exp, context)
                is SpecDeclaration -> AssignFromVoid(AssignFromVoid.RhsKind.CVL_FUNC, context.toString(), cmd.range)
                is ConcreteMethod -> AssignFromVoid(AssignFromVoid.RhsKind.EVM_FUNC, context.toString(), cmd.range)
                is UniqueMethod -> `impossible!` // calling any unique method (specifically, the constructor) should fail the apply-expression typecheck
                is CVLBuiltInFunction -> `impossible!` // all built in functions should return a type
            }
        } else if (rhsType is CVLType.PureCVLType.Void && cmd.exp is CVLExp.AddressFunctionCallExp) {
            AssignFromVoid(AssignFromVoid.RhsKind.ADDRESS_FUNC, cmd.exp.toString(), cmd.range)
        } else if (rhsType is CVLType.PureCVLType.Enum && rhsType.isCastableTo(lhsType)) {
            CVLError.General(cmd.range, "Cannot directly assign enum $rhsType to $lhsType. Use an explicit cast (e.g. assert_$lhsType(${cmd.exp})")
        } else {
            NotConvertible(cmd.exp, lhsType)
        }
    }

    private fun typeCheckDeclarationCmd(cmd: CVLCmd.Simple.Declaration): CollectingResult<CVLCmd.Simple.Declaration, CVLError> {
        if (cmd.id.isWildcard()) {
            return CVLError.General(
                cmd.range,
                "`_` is the wildcard variable. Cannot declare a variable with that name"
            ).asError()
        }

        return typeTypeChecker.typeCheck(cmd.cvlType, cmd.range, cmd.scope).bind { type ->
            when (type) {
                is CVLType.PureCVLType.Ghost.Mapping ->
                    CVLError.General(cmd.range, "CVL does not support mappings as local variables").asError()

                is CVLType.PureCVLType.VMInternal.BlockchainState ->
                    CVLError.General(cmd.range, "Storage references must be explicitly initialized").asError()

                else -> cmd.copy(cvlType = type).lift()
            }
        }
    }

    private fun typeCheckAssumeInvariantCmd(cmd: CVLCmd.Simple.AssumeCmd.AssumeInvariant): CollectingResult<CVLCmd.Simple.AssumeCmd.AssumeInvariant, CVLError> {
        val symbol = symbolTable.lookUpNonFunctionLikeSymbol(cmd.id, cmd.scope)?.symbolValue
            ?: return CVLError.General(cmd.range, "Could not find invariant ${cmd.id}").asError()

        val invariant = symbol as? CVLInvariant
            ?: return CVLError.General(cmd.range, "${cmd.id} is not an invariant").asError()

        val params = cmd.params.map {
            expTypeChecker.typeCheck(
                it,
                CVLTypeEnvironment.empty(cmd.range, cmd.scope)
            )
        }.flatten()

        if (cmd.params.size != invariant.params.size) {
            return CVLError.General(cmd.range, "Wrong number of arguments passed to ${cmd.id}: expected ${invariant.params.size}, got ${cmd.params.size}").asError()
        }

        return params.bind() { params ->
            params.zip(invariant.params).map { (givenArg, param) ->
                val currentConvertible = givenArg.getCVLType().isConvertibleTo(param.type)

                if (!currentConvertible) {
                    CVLError.General(
                        cmd.range,
                        "Wrong argument type passed to ${cmd.id}: expected ${param.type}, got ${givenArg.tag.type}"
                    ).asError()
                } else {
                    givenArg.lift()
                }
            }.flatten().bind { params ->
                cmd.copy(params = params).lift()
            }
        }
    }

    private fun typeCheckAssumeCmd(cmd: CVLCmd.Simple.AssumeCmd.Assume): CollectingResult<CVLCmd.Simple.AssumeCmd.Assume, CVLError> {
        if (Config.EnforceRequireReason.get() && cmd.description == null) {
            return RequireWithoutReason(cmd.range, cmd.exp).asError()
        }
        val typeCheckedExp = expTypeChecker
            .typeCheck(cmd.exp, cmd.typeEnv)
            .bind { exp -> typeCheckBooleanExp(exp, NonBoolExpression.Kind.ASSUME_CMD) }

        return typeCheckedExp.map { exp -> cmd.copy(exp = exp) }
    }

    private fun typeCheckApplyCmd(cmd: CVLCmd.Simple.Apply): CollectingResult<CVLCmd.Simple.Apply, CVLError> {
        val exp = cmd.exp
        val resolved = when (exp) {
            is CVLExp.ApplyExp -> if (exp is CVLExp.ApplyExp.Inlinable) {
                expTypeChecker.typeCheckApplyExp(exp, cmd.typeEnv)
            } else {
                exp.lift()
            }
            is CVLExp.UnresolvedApplyExp -> expTypeChecker.typeCheckUnresolved(exp, cmd.typeEnv)
            is CVLExp.AddressFunctionCallExp -> `impossible!`
        }

        return resolved.bind { exp ->
            when (exp) {
                is CVLExp.ApplyExp.Definition ->
                    CVLError.Exp(
                        exp,
                        "Apply of a definition $exp not allowed as a command."
                    ).asError()
                is CVLExp.ApplyExp.CVLBuiltIn ->
                    CVLError.Exp(
                        exp,
                        "Apply of a built-in is not allowed as a command"
                    ).asError()
                is CVLExp.ApplyExp.Ghost ->
                    CVLError.Exp(
                        exp,
                        "Apply of a ghost $exp not allowed as a command."
                    ).asError()
                is CVLExp.ApplyExp.Inlinable -> cmd.copy(exp = exp).lift()
                is CVLExp.UnresolvedApplyExp -> {
                    check(exp.tag.annotation is CVLExp.UnresolvedApplyExp.WhitelistedUnresolvedFunc) {
                        "The expression should only remain in unresolved state if it corresponds to optional methods in the methods block or -listCalls was used"
                    }
                    cmd.copy(exp = exp).lift()
                }
                is CVLExp.AddressFunctionCallExp -> cmd.copy(exp = exp).lift()
            }
        }
    }

    private fun typeCheckLabelCmd(cmd: CVLCmd.Simple.Label): CollectingResult<CVLCmd.Simple.Label, CVLError> {
        // nothing to typecheck for a label
        return cmd.lift()
    }

    private fun typeCheckAssertCmd(cmd: CVLCmd.Simple.Assert): CollectingResult<CVLCmd.Simple.Assert, CVLError> {
        val typeCheckedExp = expTypeChecker
            .typeCheck(cmd.exp, cmd.typeEnv)
            .bind { exp -> typeCheckBooleanExp(exp, NonBoolExpression.Kind.ASSERT_CMD) }

        return typeCheckedExp.map { exp -> cmd.copy(exp = exp) }
    }

    private fun typeCheckSatisfyCmd(cmd: CVLCmd.Simple.Satisfy): CollectingResult<CVLCmd.Simple.Satisfy, CVLError> {
        val typeCheckedExp = expTypeChecker
            .typeCheck(cmd.exp, cmd.typeEnv)
            .bind { exp -> typeCheckBooleanExp(exp, NonBoolExpression.Kind.SATISFY_CMD) }

        return typeCheckedExp.map { exp -> cmd.copy(exp = exp) }
    }

    private fun typeCheckIfCmd(cmd: CVLCmd.Composite.If): CollectingResult<CVLCmd.Composite.If, CVLError> {
        val cond = expTypeChecker.typeCheck(cmd.cond, cmd.typeEnv)
        val thenCmd = typeCheckCmd(cmd.thenCmd)
        val elseCmd = typeCheckCmd(cmd.elseCmd)
        return cond.bind(thenCmd, elseCmd) { cond, thenCmd, elseCmd ->
            fun expIsMethodFieldSelect(exp: CVLExp) =
                exp is CVLExp.FieldSelectExp && exp.structExp.getCVLType() == EVMBuiltinTypes.method

            fun condOnMethodParameter(): Boolean {
                // If the condition is of the sort `if (f.selector == someConstant)` then we don't want to show this
                // branching in the calltrace - it will be a constant branch in each instantiation of the parametric rule.
                return cond is CVLExp.RelopExp &&
                    ((expIsMethodFieldSelect(cond.l) && cond.r.eval() != null) ||
                     (expIsMethodFieldSelect(cond.r) && cond.l.eval() != null))
            }

            if (cond.getCVLType().isNotConvertibleTo(CVLType.PureCVLType.Primitive.Bool)) {
                NonBoolExpression(cond, NonBoolExpression.Kind.IF_COND).asError()
            } else {
                cmd.copy(
                    cond = if (cond.eval() != null || condOnMethodParameter()) {
                        check(cond.tag.annotation == null) { "$cond is already annotated with ${cond.tag.annotation}" }
                        cond.updateTag(cond.tag.copy(annotation = CVLCmd.Composite.If.ConstantIfCond))
                    } else {
                        cond
                    },
                    thenCmd = thenCmd,
                    elseCmd = elseCmd
                ).lift()
            }
        }
    }

}
