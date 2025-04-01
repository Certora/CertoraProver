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

package spec

import com.certora.certoraprover.cvl.LocatedToken
import scene.ICVLScene
import utils.Range
import spec.cvlast.MethodQualifiers
import spec.cvlast.typechecker.CVLError
import tac.Tag
import utils.CollectingResult
import java.math.BigInteger

interface VMConfig {
    val memoryLocations: Set<String>
    val hookableOpcodes: Set<String>
    val preReturnMethodQualifiers: Set<String>
    val postReturnMethodQualifiers: Set<String>
    val maxUint: BigInteger
    val maxAddress: BigInteger
    val registerBitwidth: Int
    val registerByteWidth: Int
    val registerTag: Tag
    // arrays on this VM are assumed to be < 2^maxArraySizeFactor
    val maxArraySizeFactor: Int

    fun getTypeResolver(s: ICVLScene, mainContract: String): TypeResolver

    // TODO CERT-2962: there's not a clean separation between VM- and non-VM-specific features (e.g., this
    // returns an AnnotationQualifiers that contains an `envfree` flag, which is VM-specific)
    fun getMethodQualifierAnnotations(preReturnAnnotations: List<LocatedToken>, postReturnAnnotations: List<LocatedToken>, range: Range): CollectingResult<MethodQualifiers, CVLError>
}
