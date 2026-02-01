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

package analysis

import datastructures.stdcollections.*
import log.*
import tac.MetaMap
import tac.NBId
import tac.Tag
import tac.isSubtypeOf
import utils.*
import utils.CollectingResult.Companion.asError
import utils.CollectingResult.Companion.bind
import utils.CollectingResult.Companion.flatten
import utils.CollectingResult.Companion.lift
import utils.CollectingResult.Companion.map
import utils.CollectingResult.Companion.ok
import vc.data.*
import vc.data.TACBuiltInFunction.Hash.Companion.skeySort
import vc.data.TACExpr.*
import vc.data.TACMeta.IS_STORAGE_ACCESS
import vc.data.tacexprutil.TACExprFreeVarsCollector
import vc.data.tacexprutil.rebuild


class TACTypeCheckerException(msg: String) :
    CertoraInternalException(CertoraInternalErrorType.INTERNAL_TAC_TYPE_CHECKER, msg)

class TACTypeChecker(private val symbolTable: TACSymbolTable, private val allowTransientTag: Boolean = true) {
    private val errors: MutableList<String> = mutableListOf()
    private val missingDeclarations: MutableSet<TACSymbol.Var> = mutableSetOf()

    private fun addError(error: String) {
        errors += error
    }

    private val expectInt = "${Tag.Bit256} or ${Tag.Int}"
    private val expectBv256 = "${Tag.Bit256}"
    private val expectBits = "${Tag.Bits}"
    private val expectBool = "${Tag.Bool} or ${Tag.Bit256} or ${Tag.Int}"
    private val expectMap = "${Tag.ByteMap} or ${Tag.WordMap} or ${Tag.GhostMap.namePrefix}"
    private val expectMutableMap = "${Tag.ByteMap} or ${Tag.WordMap} or ${Tag.GhostMap.namePrefix}"
    private val expectSkeyOrInt = "$skeySort or $expectInt"

    companion object {
        val logger = Logger(LoggerTypes.INTERNAL_TYPE_CHECKER)

        /**
         * Example: If `this` was `ghostmap(X*uint->uint)`, and [firstParam] is (a subtype of) `X` this will return
         * `ghostmap(uint->uint)`.
         * If [firstParam] not a subtype of `X` or is null, this will return null.
         *  */
        fun Tag.GhostMap.popFirstParam(firstParam: Tag?) =
            utils.runIf(firstParam?.isSubtypeOf(paramSorts.first()) == true) { popFirstParam() }

        fun annotateExpressions(p: CoreTACProgram) =
            TACTypeChecker(p.symbolTable, allowTransientTag = false).let { checker ->
                // probly don't need with pointer
                val errors = mutableListOf<String>()
                val cmdMapper = object : DefaultTACCmdMapperWithPointer() {
                    override fun mapMeta(t: MetaMap): MetaMap {
                        return t
                    }

                    override fun mapExpr(expr: TACExpr): TACExpr {
                        val checked = checker.typeCheck(expr)
                        return when (checked) {
                            is CollectingResult.Error -> {
                                errors.addAll(checked.messages)
                                expr
                            }
                            is CollectingResult.Result -> checked.result
                        }
                    }
                }

                val newCode = p.code.mapValues { (blk, cmds) ->
                    cmds.mapIndexed { ind, cmd ->
                        cmdMapper.currentPtr = CmdPointer(blk, ind)
                        cmdMapper.map(cmd)
                    }
                }

                if (errors.isEmpty()) {
                    // build generates a new typescope, maybe we should reorganize things so that's clearer
                    p.copy(
                        code = newCode,
                        symbolTable = checker.symbolTable,
                        check = false
                    ).lift()
                } else {
                    errors.asError()
                }

            }

        val PrimitiveChecker = TACTypeChecker(TACSymbolTable.empty())


        fun checkProgram(p: CoreTACProgram): CoreTACProgram {
            @Suppress("NAME_SHADOWING") val result = annotateExpressions(p).bind { p ->
                val checker = TACTypeChecker(p.symbolTable, allowTransientTag = false)
                val cmdMapper = object : DefaultTACCmdMapperWithPointer() {
                    override fun mapExpr(expr: TACExpr): TACExpr {
                        // check all variables are declared
                        TACExprFreeVarsCollector.getFreeVars(expr).forEach {
                            mapVar(it)
                        }
                        return expr
                    }

                    override fun mapVar(t: TACSymbol.Var): TACSymbol.Var {
                        checker.reportErrors(checker.checkTag(t.tag))
                        checker.checkExists(t, currentPtr!!)
                        if (t.namePrefix == "_") {
                            checker.addError("Found a variable with an illegal variable name $t")
                        }
                        return super.mapVar(t)
                    }

                    override fun mapMeta(t: MetaMap): MetaMap {
                        return t
                    }

                    override fun mapLhs(t: TACSymbol.Var): TACSymbol.Var {
                        checker.checkExists(t, currentPtr!!)
                        return super.mapLhs(t)
                    }

                    private fun checkStorageAccess(loc: TACSymbol, metaMap: MetaMap) {
                        if (loc is TACSymbol.Var && !loc.tag.isSubtypeOf(Tag.Int) && metaMap.containsKey(
                                IS_STORAGE_ACCESS
                            )
                        ) {
                            checker.errors += "In storage access $loc @ $currentPtr, location must have Bit256/Int type"
                        }
                    }

                    private fun checkIsBoolean(v: TACSymbol) {
                        if (v.tag != Tag.Bool) {
                            checker.errors += "Expected $v @ $currentPtr to be a boolean"
                        }
                    }

                    override fun mapWordLoad(
                        lhs: TACSymbol.Var,
                        base: TACSymbol.Var,
                        loc: TACSymbol,
                        metaMap: MetaMap
                    ): TACCmd.Simple {
                        checkStorageAccess(loc, metaMap)
                        return super.mapWordLoad(lhs, base, loc, metaMap)
                    }

                    override fun mapWordStore(
                        base: TACSymbol.Var,
                        loc: TACSymbol,
                        value: TACSymbol,
                        metaMap: MetaMap
                    ): TACCmd.Simple {
                        checkStorageAccess(loc, metaMap)
                        return super.mapWordStore(base, loc, value, metaMap)
                    }

                    override fun mapJumpICmd(
                        dst: NBId,
                        cond: TACSymbol,
                        elseDst: NBId,
                        metaMap: MetaMap
                    ): TACCmd.Simple {
                        checkIsBoolean(cond)
                        return super.mapJumpICmd(dst, cond, elseDst, metaMap)
                    }

                    override fun mapAssertCmd(o: TACSymbol, description: String, metaMap: MetaMap): TACCmd.Simple {
                        checkIsBoolean(o)
                        return super.mapAssertCmd(o, description, metaMap)
                    }

                    override fun mapAssumeCmd(cond: TACSymbol, description: String, metaMap: MetaMap): TACCmd.Simple {
                        checkIsBoolean(cond)
                        return super.mapAssumeCmd(cond, description, metaMap)
                    }

                    override fun mapAssumeNotCmd(cond: TACSymbol, metaMap: MetaMap): TACCmd.Simple {
                        checkIsBoolean(cond)
                        return super.mapAssumeNotCmd(cond, metaMap)
                    }

                    override fun mapAnnotationCmd(
                        annotationCmd: TACCmd.Simple.AnnotationCmd,
                        annotationPair: TACCmd.Simple.AnnotationCmd.Annotation<*>,
                        metaMap: MetaMap
                    ): TACCmd.Simple {
                        (annotationPair.v as? WithSupport)?.support?.forEach(this::mapVar)
                        return annotationCmd
                    }

                    override fun map(t: TACCmd.Simple): TACCmd.Simple {
                        val typeCheckedCmd = super.map(t)
                        if (typeCheckedCmd is TACCmd.Simple.AssigningCmd) {
                            val lhs = typeCheckedCmd.lhs
                            typeCheckedCmd.assignedTag?.let { rhsTag ->
                                if (!rhsTag.isSubtypeOf(lhs.tag)) {
                                    checker.errors += "In assignment $typeCheckedCmd, type of rhs $rhsTag should be a subtype of lhs ${lhs.tag}"
                                }
                            } ?: check(checker.errors.isNotEmpty())
                        }
                        return typeCheckedCmd
                    }

                    override fun mapCommand(c: LTACCmd): List<TACCmd.Simple> {
                        currentPtr =
                            c.ptr // (alex:) I don't get why this is done here and not in the super class???
                        return super.mapCommand(c)
                    }
                }

                val newCode = p.code.mapValues { (blk, cmds) ->
                    cmds.mapIndexed { ind, cmd ->
                        val ptr = CmdPointer(blk, ind)
                        cmdMapper.currentPtr = ptr
                        cmdMapper.map(cmd)
                    }
                }

                val checkedInstrumentationTAC = p.instrumentationTAC.copy(
                    ufAxioms = p.instrumentationTAC.ufAxioms.mapTo { underlying ->
                        val newUnderlying = underlying.mapValues { (uf, axioms) ->
                            val ufMap = mapOf(uf.name to mapOf(uf.paramSorts.size to uf))
                            val ufChecker = TACTypeChecker(p.symbolTable.copy(uninterpretedFunctions = ufMap))
                            val result = axioms.map { axiom ->
                                val freeVarsSet =
                                    TACExprFreeVarsCollector.getFreeVars(axiom.exp).filter { it.smtRep != uf.name }
                                if (freeVarsSet.isNotEmpty()) {
                                    ufChecker.errors += "The axiom $axiom contains free variables ($freeVarsSet). Axioms may only contain quantified variables."
                                }
                                ufChecker.typeCheck(axiom.exp).bind { axiomExpTypeChecked ->
                                    if (axiomExpTypeChecked.tagAssumeChecked != Tag.Bool) {
                                        listOf(
                                            "A type error was found in the axiom $axiom. Note, " +
                                                "this axiom must be Boolean and may only contain the function \"$uf\""
                                        ).asError()
                                    } else {
                                        axiom.copy(exp = axiomExpTypeChecked).lift()
                                    }
                                }
                            }.flatten()
                            checker.reportErrors(result)
                            result.resultOrNull() ?: axioms
                        }
                        UfAxioms(newUnderlying)
                    },
                )
                if (checker.errors.isNotEmpty()) {
                    checker.errors.asError()
                } else {
                    logger.info { "No type errors found in ${p.name}" }
                    p.copy(
                        code = newCode,
                        instrumentationTAC = checkedInstrumentationTAC,
                        check = false
                    ).lift()
                }
            }

            return when (result) {
                is CollectingResult.Result -> result.result
                is CollectingResult.Error -> {
                    result.messages.forEach { logger.error(it) }
                    throw TACTypeCheckerException(
                        "An internal type error was found in ${p.name}. Errors:\n ${
                            result.messages.joinToString(
                                "\n"
                            )
                        }"
                    )
                }
            }
        }


        fun Tag.isByteMapOrWordMap(): Boolean =
            when (this) {
                is Tag.Bits,
                Tag.BlockchainState,
                Tag.Bool,
                Tag.CVLArray.RawArray,
                is Tag.CVLArray.UserArray,
                Tag.Int,
                is Tag.GhostMap,
                is Tag.UserDefined.Struct,
                is Tag.UserDefined.UninterpretedSort,
                is Tag.Move -> false
                Tag.ByteMap,
                Tag.WordMap -> true
            }


        fun Tag.isMapType() =
            this == Tag.ByteMap || this == Tag.WordMap ||
                this is Tag.GhostMap

        // expects both tag1 and tag2 are primitive tags (in {bv256, int, bool})
        fun weakestPrimitiveTag(tag1: Tag, tag2: Tag) = when {
            tag1 == Tag.Bool || tag2 == Tag.Bool -> Tag.Bool
            tag1 == Tag.Int || tag2 == Tag.Int -> Tag.Int
            tag1 == Tag.Bit256 || tag2 == Tag.Bit256 -> Tag.Bit256
            else -> throw TACTypeCheckerException("Should only get a primitive here, not $tag1 or $tag2")
        }
    }

    fun reportErrors(result: CollectingResult<*, String>) {
        if (result is CollectingResult.Error) {
            this.errors += result.messages
        }
    }

    fun checkTag(tag: Tag): CollectingResult<Tag, String> = if (!allowTransientTag && tag is Tag.TransientTag) {
        "The tag $tag should only exist in a CVLTACProgram not a CoreTACProgram".asError()
    } else if (symbolTable.contains(tag)) {
        tag.lift()
    } else {
        ("The tag $tag does not exist in the scope of this program").asError()
    }

    fun checkExists(v: TACSymbol.Var, ptr: CmdPointer) {
        if (v !in symbolTable) {
            if (v !in missingDeclarations) {
                missingDeclarations.add(v)
                // reporting only 1 time
                addError("The variable $v @$ptr is not declared in this program")
                Thread.dumpStack()
            }
        }
    }

    fun checkSimpleHash(expr: SimpleHash): CollectingResult<TACExpr, String> =
        (listOf(expr.length) + expr.args).map { typeCheck(it) }.flatten().bind { elements ->
            elements.map { e ->
                if (!e.tagAssumeChecked.isSubtypeOf(Tag.Int) &&
                    !e.tagAssumeChecked.isSubtypeOf(Tag.Bool)
                ) {
                    ("Argument $e to the expression $expr is expected to be of type " +
                        "$expectBool not ${e.tagAssumeChecked}").asError()
                } else {
                    e.lift()
                }

            }.flatten()
        }.bind { argsTyped ->
            SimpleHash(length = argsTyped.first(), args = argsTyped.drop(1), expr.hashFamily).lift()
        }

    /**
     * @author Thomas Bernardi
     * @return A Tag that represents the type of the expression e or Tag.Error if there was some
     *      type error in the expression tree
     * @effects The lists warnings and errors are appended to at each instance of a type error in the expression
     *      tree. Note: these errors do not cascade (an error will not be added because a subexpression had type
     *      Tag.Error)
     */
    fun typeCheck(e: TACExpr): CollectingResult<TACExpr, String> {
        fun uniqueBitsTag(exprs: List<TACExpr>): CollectingResult<Tag.Bits, String> =
            exprs.map {
                (it.tagAssumeChecked as? Tag.Bits)?.lift()
                    ?: ("Argument $it of $e has tag ${it.tagAssumeChecked}, but should have $expectBits").asError()
            }.reduceIndexed { i, e1: Tag.Bits, e2: Tag.Bits ->
                runIf(e1 == e2) { e1.lift() }
                    ?: ("Argument tags $e1 and $e2 at index $i of $e should be the same").asError()
            }

        fun uniqueBitsTag(vararg expr: TACExpr) = uniqueBitsTag(expr.toList())
        fun checkVec(e: Vec): CollectingResult<TACExpr, String> {
            val operandsTypeChecked = e.ls.map { typeCheck(it) }.flatten()
            return when (e) {
                is Vec.Mul,
                is Vec.Add -> {
                    operandsTypeChecked.bind { elements ->
                        uniqueBitsTag(elements).bind { it: Tag.Bits ->
                            when (e) {
                                is Vec.Mul -> e.copy(ls = elements, tag = it).lift()
                                is Vec.Add -> e.copy(ls = elements, tag = it).lift()
                                else -> `impossible!`
                            }
                        }
                    }
                }
                is Vec.IntMul,
                is Vec.IntAdd -> {
                    operandsTypeChecked.bind { elements ->
                        elements.map { expr ->
                            if (!expr.tagAssumeChecked.isSubtypeOf(Tag.Int)) {
                                ("Argument $expr to the expression $e is expected to be of type $expectInt " +
                                    "not ${expr.tag}").asError()
                            } else {
                                expr.lift()
                            }
                        }.flatten()
                    }.bind { vecTypes ->
                        when (e) {
                            is Vec.IntMul -> e.copy(ls = vecTypes, tag = Tag.Int).lift()
                            is Vec.IntAdd -> e.copy(ls = vecTypes, tag = Tag.Int).lift()
                            else -> `impossible!`
                        }
                    }
                }
            }
        }

        fun checkTernaryExp(expr: TernaryExp): CollectingResult<TACExpr, String> =
            when (expr) {
                is TernaryExp.Ite -> {
                    val ifCond = typeCheck(expr.i)
                    val thenCase = typeCheck(expr.t)
                    val elseCase = typeCheck(expr.e)
                    ifCond.bind(thenCase, elseCase) { cond, thenBranch, elseBranch ->
                        var hasErrors = false
                        val msgs = mutableListOf<String>()
                        val thenTag = thenBranch.tagAssumeChecked
                        val elseTag = elseBranch.tagAssumeChecked
                        val tag =
                            if (thenTag.isByteMapOrWordMap() && elseTag.isByteMapOrWordMap()) {
                                thenTag
                            } else if (thenTag.isSubtypeOf(elseTag)) {
                                elseTag
                            } else if (elseTag.isSubtypeOf(thenTag)) {
                                thenTag
                            } else {
                                hasErrors = true
                                msgs.add(
                                    "In $elseCase, the then expression's tag is expected to be a subtype of the else expression's tag (or vice versa) " +
                                        "but instead have tags $thenTag and $elseTag respectively"
                                )
                                null
                            }

                        if (!cond.tagAssumeChecked.isSubtypeOf(Tag.Bool)) {
                            hasErrors = true
                            msgs.add(
                                "The condition of $elseCase is expected to have type ${Tag.Bool} but found to have " +
                                    "type ${cond.tagAssumeChecked}"
                            )
                        }

                        if (hasErrors) {
                            msgs.asError()
                        } else {
                            expr.copy(i = cond, t = thenBranch, e = elseBranch, tag = tag!!).lift()
                        }
                    }
                }
                is TernaryExp.AddMod -> {
                    val aT = typeCheck(expr.a)
                    val bT = typeCheck(expr.b)
                    val nT = typeCheck(expr.n)
                    aT.bind(bT, nT) { a, b, n ->
                        uniqueBitsTag(a, b, n).bind {
                            expr.copy(a, b, n, it).lift()
                        }
                    }
                }
                is TernaryExp.MulMod -> {
                    val aT = typeCheck(expr.a)
                    val bT = typeCheck(expr.b)
                    val nT = typeCheck(expr.n)
                    aT.bind(bT, nT) { a, b, n ->
                        uniqueBitsTag(a, b, n).bind {
                            expr.copy(a, b, n, it).lift()
                        }
                    }
                }
            }

        fun checkUnaryExp(e: UnaryExp): CollectingResult<TACExpr, String> {
            val res = typeCheck(e.o)
            return when (e) {
                is UnaryExp.BWNot -> {
                    res.bind { oTypeChecked ->
                        uniqueBitsTag(oTypeChecked).bind {
                            e.copy(o = oTypeChecked, tag = it).lift()
                        }
                    }
                }
                is UnaryExp.LNot -> {
                    res.bind { oTypeChecked ->
                        if (oTypeChecked.tagAssumeChecked.isSubtypeOf(Tag.Bool)) {
                            e.copy(o = oTypeChecked, tag = Tag.Bool).lift()
                        } else {
                            ("The argument to the expression $e expected " +
                                "to be $expectBool not ${oTypeChecked.tagAssumeChecked}").asError()
                        }
                    }
                }
            }
        }

        fun checkBinOp(e: BinOp): CollectingResult<TACExpr, String> {
            val o1Res = typeCheck(e.o1)
            val o2Res = typeCheck(e.o2)
            return when (e) {
                // Bits * Bits -> Bits
                is BinOp.SignExtend,
                is BinOp.BWAnd,
                is BinOp.BWOr,
                is BinOp.BWXOr,
                is BinOp.Sub,
                is BinOp.Div,
                is BinOp.SDiv,
                is BinOp.Mod,
                is BinOp.SMod -> {
                    o1Res.bind(o2Res) { o1TypeChecked, o2TypeChecked ->
                        uniqueBitsTag(o1TypeChecked, o2TypeChecked).bind { tag ->
                            e.rebuild(o1TypeChecked, o2TypeChecked).copy(tag = tag).lift()
                        }
                    }
                }
                // Int * Int -> Int
                is BinOp.Exponent,
                is BinOp.IntSub,
                is BinOp.IntDiv,
                is BinOp.IntMod,
                is BinOp.IntExponent -> {
                    val o1 = o1Res.bind { o1 ->
                        if (!o1.tagAssumeChecked.isSubtypeOf(Tag.Int)) {
                            ("The first argument to the expression $e expected to be $expectInt not ${o1.tagAssumeChecked}").asError()
                        } else {
                            o1.lift()
                        }
                    }
                    val o2 = o2Res.bind { o2 ->
                        if (!o2.tagAssumeChecked.isSubtypeOf(Tag.Int)) {
                            ("The second argument to the expression $e expected to be $expectInt not ${o2.tagAssumeChecked}").asError()
                        } else {
                            o2.lift()
                        }
                    }

                    o1.bind(o2) { o1TypeChecked, o2TypeChecked ->
                        when (e) {
                            is BinOp.Exponent ->
                                e.rebuild(o1TypeChecked, o2TypeChecked)
                                    .copy(tag =
                                        weakestPrimitiveTag(
                                            o1TypeChecked.tagAssumeChecked,
                                            o2TypeChecked.tagAssumeChecked
                                        )
                                    )
                            is BinOp.IntExponent,
                            is BinOp.IntSub,
                            is BinOp.IntDiv,
                            is BinOp.IntMod -> e.rebuild(o1TypeChecked, o2TypeChecked).copy(tag = Tag.Int)
                            else -> `impossible!`
                        }.lift()
                    }
                }
                is BinOp.ShiftLeft,
                is BinOp.ShiftRightLogical,
                is BinOp.ShiftRightArithmetical -> {
                    val o2 = o2Res.bind { o2 ->
                        if (!o2.tagAssumeChecked.isSubtypeOf(Tag.Int)) {
                            ("The second argument to the expression $e expected to be $expectInt not ${o2.tagAssumeChecked}").asError()
                        } else {
                            o2.lift()
                        }
                    }

                    o1Res.bind(o2) { o1TypeChecked, o2TypeChecked ->
                        uniqueBitsTag(o1TypeChecked).bind { tag ->
                            e.rebuild(o1TypeChecked, o2TypeChecked).copy(tag = tag).lift()
                        }
                    }
                }
            }
        }

        fun checkBinRel(e: BinRel): CollectingResult<TACExpr, String> {
            val o1Res = typeCheck(e.o1)
            val o2Res = typeCheck(e.o2)
            return when (e) {
                is BinRel.Eq -> {
                    o1Res.bind(o2Res) { o1, o2 ->
                        if (!o1.tagAssumeChecked.isSubtypeOf(o2.tagAssumeChecked) && !o2.tagAssumeChecked.isSubtypeOf(o1.tagAssumeChecked)) {
                            ("The first argument to the expression $e expected to be $expectBool or both be " +
                                "equal, not ${o1.tagAssumeChecked} and ${o2.tagAssumeChecked}").asError()
                        } else {
                            e.copy(o1 = o1, o2 = o2, tag = Tag.Bool).lift()
                        }

                    }
                }
                is BinRel.Ge,
                is BinRel.Gt,
                is BinRel.Le,
                is BinRel.Lt -> {
                    val o1 = o1Res.bind { o1 ->
                        if (!o1.tagAssumeChecked.isSubtypeOf(Tag.Int)) {
                            ("The first argument to the expression $e expected to be $expectInt not ${o1.tagAssumeChecked}").asError()
                        } else {
                            o1.lift()
                        }
                    }
                    val o2 = o2Res.bind { o2 ->
                        if (!o2.tagAssumeChecked.isSubtypeOf(Tag.Int)) {
                            ("The second argument to the expression $e expected to be $expectInt not ${o2.tagAssumeChecked}").asError()
                        } else {
                            o2.lift()
                        }
                    }
                    o1.bind(o2) { o1TypeChecked, o2TypeChecked ->
                        e.rebuild(o1TypeChecked, o2TypeChecked).copy(tag = Tag.Bool).lift()
                    }
                }
                // both operands must be of type bv256
                is BinRel.Slt,
                is BinRel.Sle,
                is BinRel.Sgt,
                is BinRel.Sge -> {
                    o1Res.bind(o2Res) { o1TypeChecked, o2TypeChecked ->
                        uniqueBitsTag(o1TypeChecked, o2TypeChecked).bind { _ ->
                            e.rebuild(o1TypeChecked, o2TypeChecked).copy(tag = Tag.Bool).lift()
                        }
                    }
                }
            }
        }

        fun checkApplyExpr(e: Apply): CollectingResult<TACExpr, String> {
            val opResults = e.ops.map { arg ->
                typeCheck(arg)
            }.flatten()
            return opResults.bind { argsTypeChecked ->
                val argTags = argsTypeChecked.map { arg -> arg.tagAssumeChecked }
                when (val f = e.f) {
                    is TACFunctionSym.BuiltIn -> {
                        if (argTags.size != f.bif.paramSorts.size) {
                            ("Arity-mismatch for application to ${f.bif.name} (expected ${f.bif.paramSorts.size}, got ${argTags.size})").asError()
                        } else if (f.bif.paramSorts.zip(argTags).all { (formal, passed) ->
                                passed.isSubtypeOf(formal)
                            }) {
                            e.copy(ops = argsTypeChecked, tag = f.bif.returnSort).lift()
                        } else {
                            ("Type argument mismatch for ${f.bif.name} in $e, params are ${f.bif.paramSorts}, got $argTags").asError()
                        }
                    }
                    is TACFunctionSym.Adhoc -> {
                        ("Found adhoc function ${f.name}, these should never appear in programs").asError()
                    }
                }
            }
        }

        fun checkSymbol(e: Sym.Const): CollectingResult<Sym.Const, String> =
            checkTag(e.s.tag).bind { tag ->
                if (!tag.isSubtypeOf(Tag.Bool) && !tag.isSubtypeOf(Tag.Int)) {
                    ("The constant $e must be of type $expectBool or $expectInt").asError()
                } else {
                    e.copy(s = e.s.copy(tag = tag), tag = tag).lift()
                }
            }

        fun checkSymbol(e: Sym.Var): CollectingResult<Sym.Var, String> =
            checkTag(e.s.tag).bind { tag ->
                e.copy(tag = tag).lift()
            }

        return when (e) {
            is QuantifiedFormula -> {
                typeCheck(e.body).bind { bodyTypeChecked ->
                    val bodyTag = bodyTypeChecked.tagAssumeChecked
                    e.quantifiedVars.map { v ->
                        checkTag(v.tag).bind {
                            v.lift()
                        }
                    }
                        .flatten().bind { argsTypeChecked ->
                            if (!bodyTag.isSubtypeOf(Tag.Bool)) {
                                listOf("The body of $e should be of type ${Tag.Bool} not $bodyTag").asError()
                            } else {
                                e.copy(
                                    body = bodyTypeChecked,
                                    quantifiedVars = argsTypeChecked,
                                    tag = Tag.Bool
                                ).lift()
                            }
                        }
                }
            }
            is Sym.Var -> checkSymbol(e)
            is Sym.Const -> checkSymbol(e)
            is Vec -> checkVec(e)
            is SimpleHash -> checkSimpleHash(e)
            is TernaryExp -> checkTernaryExp(e)
            is UnaryExp -> checkUnaryExp(e)
            is BinOp -> checkBinOp(e)
            is BinRel -> checkBinRel(e)
            is Apply -> checkApplyExpr(e)
            is Select -> {
                typeCheck(e.base).bind(typeCheck(e.loc)) { baseTypeChecked, locTypeChecked ->
                    val baseTag = baseTypeChecked.tagAssumeChecked
                    val locTag = locTypeChecked.tagAssumeChecked
                    val errors = mutableListOf<String>()
                    if (!baseTag.isMapType()) {
                        errors += "The base of $e should have type $expectMap, not $baseTag"
                    }
                    if (baseTag.isSubtypeOf(Tag.ByteMap) &&
                        !locTag.isSubtypeOf(skeySort) &&
                        !(locTag is Tag.Bits)
                    ) {
                        errors += "Because the base of $e has type $baseTag, loc should have type " +
                                "${Tag.Bit256}, not $locTag"
                    }
                    if (baseTag.isSubtypeOf(Tag.WordMap) &&
                        !locTag.isSubtypeOf(skeySort) &&
                        !locTag.isSubtypeOf(Tag.Int)
                    ) {
                        errors += "Because the base of $e has type $baseTag, loc should have type " +
                                "$expectSkeyOrInt, not $locTag"
                    }
                    if (baseTag is Tag.GhostMap &&
                        !locTag.isSubtypeOf(baseTag.paramSorts.first()))
                    {
                        errors += "Because the base of $e has type $baseTag, loc should have type " +
                                "${baseTag.paramSorts.first()}, not $locTag"
                    }

                    if (errors.isNotEmpty()) {
                        errors.asError()
                    } else {
                        val retType = when {
                            baseTag.isSubtypeOf(Tag.ByteMap) || baseTag.isSubtypeOf(Tag.WordMap) -> Tag.Bit256
                            baseTag is Tag.GhostMap -> baseTag.popFirstParam(locTag)!!
                            else -> `impossible!`
                        }
                        e.copy(base = baseTypeChecked, loc = locTypeChecked, tag = retType).lift()
                    }
                }
            }
            is MapDefinition -> e.defParams.map { param ->
                checkSymbol(param)
            }.flatten().bind(typeCheck(e.definition)) { params, definition ->
                val paramTags = params.map { param -> param.tagAssumeChecked }
                val definitionTag = definition.tagAssumeChecked
                if (paramTags != e.tag.paramSorts) {
                    "MapDefinitition parameter type mismatch: $paramTags != ${e.tag.paramSorts}".asError()
                } else if (definitionTag != e.tag.resultSort) {
                    "MapDefinition result type mismatch: $definitionTag != ${e.tag.resultSort}".asError()
                } else {
                    MapDefinition(
                        params,
                        definition,
                        e.tag
                    ).lift()
                }
            }
            is Unconstrained -> checkTag(e.tag).bind { eTypeChecked ->
                Unconstrained(eTypeChecked).lift()
            }
            is Store ->
                typeCheck(e.base).bind(
                    typeCheck(e.loc),
                    typeCheck(e.value)
                ) { baseTypeChecked, locTypeChecked, valueTypeChecked ->
                    val baseTag = baseTypeChecked.tagAssumeChecked
                    val locTag = locTypeChecked.tagAssumeChecked
                    val valueTag = valueTypeChecked.tagAssumeChecked
                    val baseMapOk = if (!baseTag.isSubtypeOf(Tag.ByteMap) &&
                        !baseTag.isSubtypeOf(Tag.WordMap) &&
                        !(baseTag is Tag.GhostMap)
                    ) {
                        "The base of $e should have type $expectMutableMap not $baseTag".asError()
                    } else {
                        ok
                    }
                    @Suppress("DEPRECATION")
                    val bytemapKeyOk = if (baseTag.isSubtypeOf(Tag.ByteMap)
                        && !locTag.isSubtypeOf(Tag.Bit256) && !locTag.isSubtypeOf(
                            skeySort
                        ))
                    {
                        ("Because the base of $e has type $baseTag, loc should have type " +
                            "${Tag.Bit256} or $skeySort not $locTag").asError()
                    } else {
                        ok
                    }
                    @Suppress("DEPRECATION")
                    val wordmapKeyOk = if (baseTag.isSubtypeOf(Tag.WordMap) &&
                        !locTag.isSubtypeOf(Tag.Int) && !locTag.isSubtypeOf(
                            skeySort
                        )
                    ) {
                        ("Because the base of $e has type $baseTag, loc should have type " +
                            "$expectSkeyOrInt not $locTag").asError()
                    } else {
                        ok
                    }
                    val valueTagOk = when {
                        valueTag.isMapType() -> "The value of $e is expected to have type $expectBool, not $valueTag".asError()
                        baseTag.isSubtypeOf(Tag.ByteMap) || baseTag.isSubtypeOf(Tag.WordMap) -> when {
                            valueTag.isSubtypeOf(Tag.Int) -> ok
                            else -> "The value of $e is expected to have type $expectBv256, not $valueTag".asError()
                        }
                        else -> ok
                    }
                    baseMapOk.map(bytemapKeyOk, wordmapKeyOk, valueTagOk) { _, _, _, _ ->
                        e.copy(base = baseTypeChecked, loc = locTypeChecked, value = valueTypeChecked, tag = baseTag)
                    }
                }
            is MultiDimStore -> {
                typeCheck(e.base).bind { baseTypeChecked: TACExpr ->
                    e.locs.map { typeCheck(it) }.flatten().bind { locsTypechecked: List<TACExpr> ->
                        typeCheck(e.value).bind { valueTypeChecked ->
                            val baseTag = baseTypeChecked.tagAssumeChecked
                            val locsTags = locsTypechecked.map { it.tagAssumeChecked }
                            val valueTag = valueTypeChecked.tagAssumeChecked

                            val errors = mutableListOf<String>()
                            if (baseTag !is Tag.GhostMap) {
                                errors += "The base of $e should have type ghostmap, not $baseTag"
                            }
                            locsTags.zip((baseTag as Tag.GhostMap).paramSorts)
                                .forEachIndexed { index, (locType, mapParamType) ->
                                    if (!locType.isSubtypeOf(mapParamType)) {
                                        errors += "Loc ${e.locs[index]} in $e should have type $mapParamType."
                                    }
                                }
                            if (!valueTag.isSubtypeOf(baseTag.resultSort)) {
                                errors += "The expression ${e.value}, appearing in $e, is expected to have type ${baseTag.resultSort} not $valueTag"
                            }
                            if (errors.isNotEmpty()) {
                                errors.asError()
                            } else {
                                e.copy(
                                    base = baseTypeChecked,
                                    locs = locsTypechecked,
                                    value = valueTypeChecked,
                                    tag = baseTag
                                ).lift()
                            }
                        }
                    }
                }
            }
            is LongStore -> {
                typeCheck(e.dstMap).bind(typeCheck(e.srcMap)) { dstMapTypeChecked, srcMapTypeChecked ->
                    typeCheck(e.dstOffset).bind(
                        typeCheck(e.srcOffset),
                        typeCheck(e.length)
                    ) { dstOffsetTypeChecked, srcOffsetTypeChecked, lengthTypeChecked ->
                        val dstMapTag = dstMapTypeChecked.tagAssumeChecked
                        val dstOffsetTag = dstOffsetTypeChecked.tagAssumeChecked
                        val srcMapTag = srcMapTypeChecked.tagAssumeChecked
                        val srcOffsetTag = srcOffsetTypeChecked.tagAssumeChecked
                        val lengthTag = lengthTypeChecked.tagAssumeChecked
                        val errors = mutableListOf<String>()
                        if (!dstMapTag.isSubtypeOf(Tag.ByteMap)) {
                            errors += "The dstMap of $e should have type ${Tag.ByteMap} not $dstMapTag"
                        }
                        if (!srcMapTag.isSubtypeOf(Tag.ByteMap)) {
                            errors += "The srcMap of $e should have type ${Tag.ByteMap} not $srcMapTag"
                        }
                        if (!dstOffsetTag.isSubtypeOf(Tag.Bit256)) {
                            errors += "The dstOffset of $e should have type ${Tag.Bit256} not $dstOffsetTag"
                        }
                        if (!srcOffsetTag.isSubtypeOf(Tag.Bit256)) {
                            errors += "The srcOffset of $e should have type ${Tag.Bit256} not $srcOffsetTag"
                        }
                        if (!lengthTag.isSubtypeOf(Tag.Bit256)) {
                            errors += "The length of $e should have type ${Tag.Bit256} not $lengthTag"
                        }
                        if (errors.isNotEmpty()) {
                            errors.asError()
                        } else {
                            e.copy(
                                dstMap = dstMapTypeChecked,
                                dstOffset = dstOffsetTypeChecked,
                                srcMap = srcMapTypeChecked,
                                srcOffset = srcOffsetTypeChecked,
                                length = lengthTypeChecked,
                                tag = Tag.ByteMap
                            ).lift()
                        }
                    }
                }
            }
            is BinBoolOp -> {
                e.ls.map { l ->
                    typeCheck(l).bind { lTypeChecked ->
                        if (!lTypeChecked.tagAssumeChecked.isSubtypeOf(Tag.Bool)) {
                            ("Argument $lTypeChecked to the expression $e is expected to be of type $expectBool " +
                                "not ${lTypeChecked.tagAssumeChecked}").asError()
                        } else {
                            lTypeChecked.lift()
                        }
                    }
                }.flatten().bind { lsTypeChecked ->
                    when (e) {
                        is BinBoolOp.LAnd ->
                            e.copy(
                                ls = lsTypeChecked,
                                tag = Tag.Bool
                            ).lift()
                        is BinBoolOp.LOr ->
                            e.copy(
                                ls = lsTypeChecked,
                                tag = Tag.Bool
                            ).lift()
                    }
                }
            }
            is StructAccess -> {
                typeCheck(e.struct).bind { structTypeChecked ->
                    val tag = structTypeChecked.tagAssumeChecked
                    if (tag !is Tag.UserDefined.Struct) {
                        ("In $e, ${e.struct} expected to be a struct type not $tag").asError()
                    } else {
                        structTypeChecked.lift()
                    }
                }.bind { structTypeChecked ->
                    val structType =
                        structTypeChecked.tagAssumeChecked as Tag.UserDefined.Struct
                    if (structType.containsField(e.fieldName)) {
                        e.copy(
                            struct = structTypeChecked,
                            tag = structType.getField(e.fieldName)!!.type
                        ).lift()
                    } else {
                        ("A field of name ${e.fieldName} not found in ${e.struct}").asError()
                    }
                }
            }
            is StructConstant -> {
                e.fieldNameToValue
                    .map { (name, expr) ->
                        if (expr !is Sym) {
                            // note this is enforcing an assumption made during struct flattening and may be removed
                            // if struct flattening is modified not to assmue struct constants only contain symbols
                            // as members
                            ("A struct constant may only contain symbols, not $expr").asError()
                        } else {
                            typeCheck(expr).bind {
                                (name to it).lift()
                            }
                        }
                    }.flatten().bind { fieldNameToValueTypeChecked ->
                        val fieldNameToValue = fieldNameToValueTypeChecked.associate { it }
                        val fields = fieldNameToValue.entries
                            .map { (name, expr) ->
                                Tag.UserDefined.Struct.Field(
                                    name,
                                    expr.tagAssumeChecked
                                )
                            }
                        val tag = Tag.UserDefined.Struct(fields)
                        e.copy(
                            fieldNameToValue = fieldNameToValue,
                            tag = tag
                        ).lift()
                    }
            }

            is AnnotationExp<*> ->
                typeCheck(e.o).bind {
                    e.copy(o = it).lift()
                }

        }
    }
}
