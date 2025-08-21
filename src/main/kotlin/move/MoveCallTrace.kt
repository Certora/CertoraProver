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
import com.certora.collect.*
import config.*
import datastructures.stdcollections.*
import move.ConstantStringPropagator.MESSAGE_VAR
import tac.*
import tac.generation.*
import utils.*
import vc.data.*

/**
    Functions for annotating Move programs with call trace information.
 */
object MoveCallTrace {
    /** The number of vector elements to retrieve in the CEX model, so that we can display them in the calltrace. */
    private val maxElemCount = Config.MoveCallTraceVecElemCount.get()

    /** Represents a Move value (primitive, struct, vector, etc.) for display in the call trace. */
    @KSerializable
    sealed class Value : AmbiSerializable, TransformableVarEntityWithSupport<Value> {
        @KSerializable
        data class Primitive(val type: MoveType.Primitive, val sym: TACSymbol.Var) : Value() {
            override val support get() = treapSetOf(sym)
            override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var) = copy(
                sym = f(sym)
            )
        }

        @KSerializable
        data class Struct(val fields: List<Pair<String, Value>>) : Value() {
            override val support get() = fields.map { it.second.support }.unionAll()
            override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var) = copy(
                fields = fields.map { (name, value) -> name to value.transformSymbols(f) }
            )
        }

        @KSerializable
        data class Vector(
            val length: TACSymbol.Var,
            /**
                The first [maxElemCount] slots of the memory location allocated for this vector.  This may be more
                elements than the vector actually contains; we will only display the first [length] elements in the
                call trace, but we won't know how many elements the vector actually contains until we get the CEX.
             */
            val elements: List<Value>
        ) : Value() {
            override val support get() = elements.map { it.support }.unionAll() + length
            override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var) = copy(
                length = f(length),
                elements = elements.map { it.transformSymbols(f) }
            )
        }

        @KSerializable
        data class Reference(val value: Value) : Value() {
            override val support get() = value.support
            override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var) = copy(
                value = value.transformSymbols(f)
            )
        }

        @KSerializable
        data object GhostArray : Value() {
            override val support get() = treapSetOf<TACSymbol.Var>()
            override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var) = this
            private fun readResolve(): Any = GhostArray
        }
    }

    /**
        Snippet holding the function start information.  We also put the return types here, because we need those when
        initially constructing the call node in the trace.
     */
    @KSerializable
    data class FuncStart(
        val name: MoveFunctionName,
        val params: List<MoveFunction.DisplayParam>,
        val returnTypes: List<MoveType>,
        val args: List<Value>,
        override val range: Range.Range?
    ) : SnippetCmd.MoveSnippetCmd(), TransformableVarEntityWithSupport<FuncStart> {
        override val support: Set<TACSymbol.Var> get() = args.map { it.support }.unionAll()
        override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var) = copy(
            args = args.map { it.transformSymbols(f) }
        )
    }

    @KSerializable
    data class FuncEnd(
        val name: MoveFunctionName,
        val returns: List<Value>
    ) : SnippetCmd.MoveSnippetCmd(), TransformableVarEntityWithSupport<FuncEnd> {
        override val range: Range.Range? get() = null // calltrace will get the range from the meta
        override val support: Set<TACSymbol.Var> get() = returns.map { it.support }.unionAll()
        override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var) = copy(
            returns = returns.map { it.transformSymbols(f) }
        )
    }

    /** Snippet for a user-defined assume */
    @KSerializable
    data class Assume(
        val message: String?,
        override val range: Range.Range?
    ) : SnippetCmd.MoveSnippetCmd()

    /** Snippet for a user-defined assert */
    @KSerializable
    data class Assert(
        val isSatisfy: Boolean,
        val condition: TACSymbol,
        val message: String?,
        override val range: Range.Range?
    ) : SnippetCmd.MoveSnippetCmd(), TransformableVarEntityWithSupport<Assert> {
        override val support: Set<TACSymbol.Var> get() = setOfNotNull(condition as? TACSymbol.Var)
        override fun transformSymbols(f: (TACSymbol.Var) -> TACSymbol.Var) = copy(
            condition = (condition as? TACSymbol.Var)?.let(f) ?: condition
        )
    }

    /**
        Emits code to create a value from a variable, so we can get its value in the CEX.  Unpacks the contents of the
        variable into individual scalar variables, depending on the type, so that all scalar values will be present in
        the CEX model.
     */
    private fun makeValue(
        cmds: MutableList<MoveCmdsWithDecls>,
        type: MoveType,
        sym: TACSymbol.Var
    ): Value = when (type) {
        is MoveType.Reference -> Value.Reference(makeDerefValue(cmds, type.refType, sym))
        is MoveType.Value -> when (type) {
            is MoveType.Primitive -> Value.Primitive(type, sym)
            is MoveType.Struct, is MoveType.Vector, is MoveType.GhostArray -> {
                val refSym = TACKeyword.TMP(MoveType.Reference(type).toTag())
                cmds += TACCmd.Move.BorrowLocCmd(refSym, sym).withDecls(refSym)
                makeDerefValue(cmds, type, refSym)
            }
        }
    }

    /**
        Emits code to unpack a reference into scalar variables, so we can get the referenced scalar values in the CEX.
     */
    private fun makeDerefValue(
        cmds: MutableList<MoveCmdsWithDecls>,
        type: MoveType.Value,
        refSym: TACSymbol.Var
    ): Value = when (type) {
        is MoveType.Primitive -> {
            val valSym = TACKeyword.TMP(type.toTag())
            cmds += TACCmd.Move.ReadRefCmd(valSym, refSym).withDecls(valSym)
            Value.Primitive(type, valSym)
        }
        is MoveType.Struct -> makeStructValue(cmds, type, refSym)
        is MoveType.Vector -> makeVectorValue(cmds, type, refSym)
        is MoveType.GhostArray -> makeGhostArrayValue(cmds, type, refSym)
    }

    /**
        Emits code to unpack all fields of a struct into individual scalar variables so we can get their values in the
        CEX.
     */
    private fun makeStructValue(
        cmds: MutableList<MoveCmdsWithDecls>,
        type: MoveType.Struct,
        structRefSym: TACSymbol.Var
    ): Value.Struct {
        val fields = type.fields.orEmpty().mapIndexed { fieldIndex, (fieldName, fieldType) ->
            val fieldRefSym = TACSymbol.Var(fieldName, MoveType.Reference(fieldType).toTag()).toUnique("!")
            cmds += TACCmd.Move.BorrowFieldCmd(fieldRefSym, structRefSym, fieldIndex).withDecls(fieldRefSym)
            fieldName to makeDerefValue(cmds, fieldType, fieldRefSym)
        }
        return Value.Struct(fields)
    }

    /**
        Emits code to unpack the first [maxElemCount] slots of a vector's memory location into individual scalar
        variables so we can get their values in the CEX.
     */
    private fun makeVectorValue(
        cmds: MutableList<MoveCmdsWithDecls>,
        type: MoveType.Vector,
        vectorRefSym: TACSymbol.Var
    ): Value.Vector {
        val lenSym = TACSymbol.Var("length", Tag.Bit256).toUnique("!")
        cmds += TACCmd.Move.VecLenCmd(lenSym, vectorRefSym).withDecls(lenSym)
        val elems = (0 until maxElemCount).map { elemIndex ->
            val elemRefSym = TACSymbol.Var("elem_$elemIndex", MoveType.Reference(type.elemType).toTag()).toUnique("!")
            cmds += TACCmd.Move.VecBorrowCmd(
                dstRef = elemRefSym,
                srcRef = vectorRefSym,
                index = TACSymbol.lift(elemIndex),
                doBoundsCheck = false // we'll do the bounds check when generating the call trace from the CEX
            ).withDecls(elemRefSym)
            makeDerefValue(cmds, type.elemType, elemRefSym)
        }
        return Value.Vector(lenSym, elems)
    }

    private fun makeGhostArrayValue(
        cmds: MutableList<MoveCmdsWithDecls>,
        type: MoveType.GhostArray,
        arrayRefSym: TACSymbol.Var
    ): Value.GhostArray {
        // Ghost arrays are often sparse, so it's not clear how to retrieve a representative set of elements.  For now
        // we just return a placeholder object.
        unused(cmds)
        unused(type)
        unused(arrayRefSym)
        return Value.GhostArray
    }


    /**
        Generates a function start annotation, along with the code to extract all scalar values from the function's
        arguments.
     */
    fun annotateFuncStart(func: MoveFunction, args: List<TACSymbol.Var>): MoveCmdsWithDecls {
        val cmds = mutableListOf<MoveCmdsWithDecls>()
        val argVals = func.params.zip(args).map { (argType, argVal) -> makeValue(cmds, argType, argVal) }
        cmds += FuncStart(
            name = func.name,
            params = func.displayParams,
            returnTypes = func.returns,
            args = argVals,
            range = func.range
        ).toAnnotation().withDecls()
        return mergeMany(cmds)
    }

    /**
        Generates a function end annotation, along with the code to extract all scalar values from the function's
        return value(s).
     */
    fun annotateFuncEnd(func: MoveFunction, returns: List<TACSymbol.Var>): MoveCmdsWithDecls {
        val cmds = mutableListOf<MoveCmdsWithDecls>()
        val returnVals = func.returns.zip(returns).map { (retType, retVal) -> makeValue(cmds, retType, retVal) }
        cmds += FuncEnd(
            name = func.name,
            returns = returnVals
        ).toAnnotation().withDecls()
        return mergeMany(cmds)
    }

    private const val UNRESOLVED_MESSAGE = "could not resolve message"

    /**
        Generates an annotation for a user-defined assume command, with an optional message variable and text message.
     */
    fun annotateUserAssume(
        range: Range.Range?,
        messageVar: TACSymbol.Var? = null,
        messageText: String? = UNRESOLVED_MESSAGE
    ): SimpleCmdsWithDecls {
        return if (messageVar == null) {
            Assume(messageText, range).toAnnotation()
        } else {
            Assume(messageText, range).toAnnotation().withMeta(MetaMap(MESSAGE_VAR to messageVar))
        }.withDecls()
    }

    /**
        Generates an annotation for a user-defined assert command, with an optional message variable and text message.
     */
    fun annotateUserAssert(
        isSatisfy: Boolean,
        condition: TACSymbol,
        range: Range.Range? = null,
        messageVar: TACSymbol.Var? = null,
        messageText: String? = UNRESOLVED_MESSAGE
    ): SimpleCmdsWithDecls {
        return if (messageVar == null) {
            Assert(isSatisfy, condition, messageText, range).toAnnotation()
        } else {
            Assert(isSatisfy, condition, messageText, range).toAnnotation().withMeta(MetaMap(MESSAGE_VAR to messageVar))
        }.withDecls()
    }
}
