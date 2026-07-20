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

package analysis.opt

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import vc.data.TACBuilderAuxiliaries
import vc.data.TACProgramBuilder
import vc.data.TACProgramBuilder.Companion.testProgString
import vc.data.asTACExpr

class ConstantPropagatorAndSimplifierTest : TACBuilderAuxiliaries() {

    /**
     * A constant assignment followed by a havoc of the same variable must NOT leave the old constant tracked:
     * folding `a == 5` to `true` after `havoc a` would be unsound. Regression for the fix that invalidates the
     * tracked constant on any non-[vc.data.TACCmd.Simple.AssigningCmd.AssignExpCmd] write to the variable (a
     * havoc is an [vc.data.TACCmd.Simple.AssigningCmd.AssignHavocCmd]).
     */
    @Test
    fun havocInvalidatesTrackedConstant() {
        val prog = TACProgramBuilder {
            a assign 5
            havoc(a)
            x assign Eq(aS, 5.asTACExpr)
            assert(x)
        }
        // `a` is havoced, so `a == 5` cannot be resolved to a constant: the program is unchanged.
        assertEquals(
            testProgString(prog.code),
            testProgString(ConstantPropagatorAndSimplifier(prog.code).rewrite())
        )
    }
}
