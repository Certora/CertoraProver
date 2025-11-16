/*
 *     The Certora Prover
 *     Copyright (C) 2025  Certora Ltd.
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, version 3 of the License.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY, without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR a PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package verifier.equivalence.tracing

import analysis.TACExprWithRequiredCmdsAndDecls
import vc.data.TACCmd
import vc.data.TACSymbol

/**
 * Public information about what kind of write is being done into a buffer.
 */
internal sealed interface IWriteSource {
    /**
     * A basic ByteStore: the written value is [writeSymbol]
     */
    sealed interface ByteStore : IWriteSource {
        val writeSymbol: TACSymbol
    }

    /**
     * The buffer write is long copy from another buffer in memory: the long read
     * for that long copy is [sourceBuffer]
     */
    sealed interface LongMemCopy : IWriteSource {
        val sourceBuffer: ILongReadInstrumentation
        fun getBufferIdentity(): TACExprWithRequiredCmdsAndDecls<TACCmd.Simple>
    }

    /**
     * The buffer write is a long copy from some environment map [baseMap]
     * starting from index [sourceLoc]
     */
    sealed interface EnvCopy : IWriteSource {
        val baseMap: TACSymbol.Var
        val sourceLoc: TACSymbol

        val extraContext: TACSymbol?

        val sortRepr: Int
    }

    sealed interface ConditionalReturnCopy : IWriteSource {
        val conditionalOn: TACExprWithRequiredCmdsAndDecls<TACCmd.Simple>

        val translatedReturnCopy: LongMemCopy
        val fallbackCopy: LongMemCopy

    }

    /**
     * Some other type of write
     */
    sealed interface Other : IWriteSource
}
