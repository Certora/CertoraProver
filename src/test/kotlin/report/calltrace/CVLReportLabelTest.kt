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

package report.calltrace

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import report.calltrace.CVLCmdFactory.assertCmd
import report.calltrace.CVLCmdFactory.requireCmd
import spec.allCastFunctions
import spec.cvlast.*
import spec.cvlast.CVLScope.Companion.AstScope
import utils.Range

/**
 * Test things about [CVLReportLabel].
 * Current focus:
 * The formatting of CVL commands and expressions as they show up in the call trace, in particular their parenthetisation.
 *
 * Note that we should try to keep things robust, so we don't have to change too much when some detail about the
 * printing changes ... which is always a challenge when there are string comparisons ..
 */
class CVLReportLabelTest {

    val cmd = CVLCmdFactory
    val cvl = CVLExpFactory

    fun label(cmd: CVLCmd.Simple) = CVLReportLabel.Cmd(cmd)

    // test the formatting method "p(..)"

    /** baseline assert formatting */
    @Test
    fun formatTest01() {
        val cmd = cmd { assertCmd { a and b } }
        assertEquals("assert a && b", label(cmd).toString())
    }

    // some basic tests about when parentheses can/cannot be omitted (and / or are just two examples of
    // different-precedence operators)

    @Test
    fun formatTest02() {
        val cmd = cmd { assertCmd { (a and b) or c } }
        assertEquals("assert a && b || c", label(cmd).toString())
    }

    @Test
    fun formatTest03() {
        val cmd = cmd { assertCmd { a and (b or c) } }
        assertEquals("assert a && (b || c)", label(cmd).toString())
    }

    @Test
    fun formatTest04() {
        val cmd = cmd { assertCmd { a or (b and c) } }
        assertEquals("assert a || b && c", label(cmd).toString())
    }

    @Test
    fun formatTest05() {
        val cmd = cmd { assertCmd { (a or b) and c } }
        assertEquals("assert (a || b) && c", label(cmd).toString())
    }

    /** the formatter omits parentheses when operators are the same and things are left-nested */
    @Test
    fun formatTest06() {
        val cmd = cmd { assertCmd { (a or b) or c } }
        assertEquals("assert a || b || c", label(cmd).toString())
    }

    /** the formatter adds parentheses when operators are the same and things are right-nested */
    @Test
    fun formatTest07() {
        val cmd = cmd { assertCmd { a or (b or c) } }
        assertEquals("assert a || (b || c)", label(cmd).toString())
    }

    /** check how `e.msg.sender` is parenthesized (it shouldn't be) */
    @Test
    fun formatTest08() {
        val cmd = cmd { assertCmd { e dot "msg" dot "sender" eq num(0) } }
        assertEquals("assert e.msg.sender == 0", label(cmd).toString())
    }

    // require to_mathint((Balances.totalSupply())) == ghostSupply()
    /** Inspired by some textual regression that failed originally. */
    @Test
    fun formatTest09() {
        val cmd = cmd { requireCmd {
            cast(
                "to_mathint",
                callContract("Balances", "totalSupply", listOf())
            ) eq callContract(null, "ghostSupply", listOf())
        } }
        assertEquals("require to_mathint(Balances.totalSupply()) == ghostSupply()", label(cmd).toString())
    }

    @Test
    fun formatTest10() {
        val cmd = cmd { requireCmd {
            ite(c, x, ite(d, x, y)) eq z
        } }
        assertEquals("require (c ? x : (d ? x : y)) == z", label(cmd).toString())
    }

    @Test
    fun formatTest11() {
        val cmd = cmd { requireCmd {
            ite(c, a, ite(d, a, b))
        } }
        assertEquals("require c ? a : (d ? a : b)", label(cmd).toString())
    }

    @Test
    fun formatTest12() {
        val cmd = cmd { requireCmd {
            g[x] eq y
        } }
        assertEquals("require g[x] == y", label(cmd).toString())
    }
}

private val emptyRange = Range.Empty()
private val emptyExpTag = CVLExpTag(AstScope, emptyRange)

/**
 * Helper for constructing CVL Asts for testing purposes.
 * Not complete, add constructs on demand.
 */
object CVLCmdFactory {
    operator fun invoke(build: (CVLExpFactory.() -> CVLCmd.Simple)): CVLCmd.Simple = build.invoke(CVLExpFactory)

    fun assertCmd(exp: CVLExpFactory.() -> CVLExp) = assertCmd(exp, "")

    fun requireCmd(exp: CVLExpFactory.() -> CVLExp): CVLCmd.Simple.AssumeCmd.Assume {
        return CVLCmd.Simple.AssumeCmd.Assume(
            emptyRange,
            CVLExpFactory.invoke(exp),
            scope = AstScope
        )
    }

    fun assertCmd(exp: CVLExpFactory.() -> CVLExp, desc: String) =
        CVLCmd.Simple.Assert(
            emptyRange,
            CVLExpFactory.invoke(exp),
            description = desc,
            scope = AstScope
        )
}

/**
 * Helper for constructing CVL Asts for testing purposes.
 * Not complete, add constructs on demand.
 */
object CVLExpFactory {
    val bool = tag(CVLType.PureCVLType.Primitive.Bool)
    val uint = tag(CVLType.PureCVLType.Primitive.UIntK(256))
    val mathint = tag(CVLType.PureCVLType.Primitive.Mathint)
    val ghost =
        tag(CVLType.PureCVLType.Ghost.Mapping(
            CVLType.PureCVLType.Primitive.UIntK(256),
            CVLType.PureCVLType.Primitive.UIntK(256)
        ))

    // predeclared bools
    val a = id("a", bool)
    val b = id("b", bool)
    val c = id("c", bool)
    val d = id("d", bool)
    val b0 = id("b0", bool)
    val b1 = id("b1", bool)
    val b2 = id("b2", bool)

    // predeclared uint
    val x = id("x", uint)
    val y = id("y", uint)
    val z = id("z", uint)

    // predeclared mathint
    val i = id("i", mathint)

    // predeclared env
    val e = id("e", tag(EVMBuiltinTypes.env))

    // predeclared ghost (mapping(uint => uint))
    val g = id("g", ghost)

    operator fun invoke(build: (CVLExpFactory.() -> CVLExp)): CVLExp = build.invoke(this)

    fun id(name: String, tag: CVLExpTag) = CVLExp.VariableExp(name, tag)
    fun num(i: Int, tag: CVLExpTag = uint) = CVLExp.Constant.NumberLit(i.toBigInteger(), tag)
    fun tag(type: CVLType.PureCVLType) = CVLExpTag.transient(type)

    infix fun CVLExp.and(o: CVLExp) = CVLExp.BinaryExp.LandExp(this, o, bool)
    infix fun CVLExp.or(o: CVLExp) = CVLExp.BinaryExp.LorExp(this, o, bool)
    infix fun CVLExp.eq(o: CVLExp) = CVLExp.RelopExp.EqExp(this, o, bool)

    fun cast(keyword: String, o: CVLExp): CVLExp.CastExpr {
        val castFunction = allCastFunctions[keyword] ?: error("not a cast function: $keyword")
        return CVLExp.CastExpr(
            castFunction.targetCVLType,
            o,
            emptyExpTag,
            castFunction.castType,
        )
    }

    fun ite(i: CVLExp, t: CVLExp, e: CVLExp) = CVLExp.CondExp(i, t, e, bool)

    operator fun CVLExp.get(i: CVLExp): CVLExp.ArrayDerefExp {
        val type =
            when (val t = tag.type) {
                is CVLType.PureCVLType.CVLArrayType -> t.elementType
                is CVLType.PureCVLType.Ghost.Mapping -> t.nestedResultType
                else -> error("type error in array deref exp: $this [ $i ]")
            }
        return CVLExp.ArrayDerefExp(this, i, tag(type))
    }

    fun callContract(contract: String?, method: String, ops: List<CVLExp>) =
        CVLExp.UnresolvedApplyExp(
            contract?.let { id(it, tag(CVLType.PureCVLType.Primitive.CodeContract(SolidityContract(it)))) },
            method,
            ops,
            uint,
            invokeStorage = id("storageDummy", uint)
        )

    infix fun CVLExp.dot(fieldName: String): CVLExp.FieldSelectExp {
        val msg = "type error in field select exp: $this . $fieldName"
        val type = when (val t = tag.type) {
            is CVLType.PureCVLType.Struct -> t.fields.find { it.fieldName == fieldName }?.cvlType ?: error(msg)
            else -> error(msg)
        }
        return CVLExp.FieldSelectExp(this, fieldName, tag(type))
    }
}
