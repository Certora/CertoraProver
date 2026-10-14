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

package instrumentation.transformers

import analysis.maybeAnnotation
import analysis.pta.InitAnnotation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import vc.data.CoreTACProgram
import vc.data.TACBuilderAuxiliaries
import vc.data.TACCmd
import vc.data.TACProgramBuilder
import vc.data.withDestructiveOptimizations

class AnnotationRemoversTest : TACBuilderAuxiliaries() {

    /**
     * A program with the annotation the points-to analysis leaves behind for every memory write inside an
     * initialization window whose bounds it had to check ([InitAnnotation.ExpectBoundedWrite]). The annotation carries
     * the location variable, so it keeps that variable alive for cone-of-influence optimizations.
     */
    private fun progWithBoundedWriteCheck() = TACProgramBuilder {
        b assign 1
        addCmd(
            TACCmd.Simple.AnnotationCmd(
                InitAnnotation.ExpectBoundedWrite.META_KEY,
                InitAnnotation.ExpectBoundedWrite(b)
            )
        )
        bMap1[b] assign 3
    }.code

    private fun countBoundedWriteChecks(ctp: CoreTACProgram) =
        ctp.ltacStream().filter { it.maybeAnnotation(InitAnnotation.ExpectBoundedWrite.META_KEY) != null }.count()

    @Test
    fun boundedWriteCheckIsErasedInDestructiveMode() {
        val prog = progWithBoundedWriteCheck().withDestructiveOptimizations(true)
        assertEquals(1L, countBoundedWriteChecks(prog))
        assertEquals(0L, countBoundedWriteChecks(AnnotationRemovers.rewrite(prog)))
    }

    @Test
    fun boundedWriteCheckIsKeptOutsideDestructiveMode() {
        val prog = progWithBoundedWriteCheck().withDestructiveOptimizations(false)
        assertEquals(1L, countBoundedWriteChecks(AnnotationRemovers.rewrite(prog)))
    }
}
