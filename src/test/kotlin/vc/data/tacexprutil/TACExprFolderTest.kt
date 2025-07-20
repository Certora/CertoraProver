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

package vc.data.tacexprutil

import analysis.storage.StorageAnalysis
import analysis.storage.StorageAnalysisResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import vc.data.TACBuilderAuxiliaries
import vc.data.TACExprFactUntyped
import vc.data.TACMeta
import vc.data.TACSymbol
import java.math.BigInteger

class TACExprFolderTest : TACBuilderAuxiliaries() {
    @Test
    fun test1() {
        val e = TACExprFactUntyped {
            Mul(
                Add(
                    cS,
                    AnnotationExp(
                        bS, TACMeta.ACCESS_PATHS,
                        StorageAnalysisResult.AccessPaths(
                            setOf(
                                StorageAnalysis.AnalysisPath.ArrayAccess(
                                    StorageAnalysis.AnalysisPath.Root(BigInteger.ONE, StorageAnalysis.Base.STORAGE),
                                    c,
                                    d
                                )
                            )
                        )
                    )
                ),
                number(2)
            )
        }

        val folder = object : TACExprFolder<MutableSet<TACSymbol.Var>>() {
            override fun const(acc: MutableSet<TACSymbol.Var>, v: TACSymbol.Const) = acc
            override fun variable(acc: MutableSet<TACSymbol.Var>, v: TACSymbol.Var) =
                acc.apply { add(v) }
        }
        assertEquals(
            setOf(c, b, d),
            folder.expr(mutableSetOf(), e)
        )
    }
}
