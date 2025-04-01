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

import com.certora.certoraprover.cvl.HookType
import com.certora.certoraprover.cvl.LocatedToken
import evm.*
import scene.ICVLScene
import spec.cvlast.MethodQualifiers
import utils.Range
import spec.cvlast.EVMTypeResolver
import spec.cvlast.Visibility
import spec.cvlast.typechecker.CVLError
import tac.Tag
import utils.CollectingResult
import utils.CollectingResult.Companion.asError
import utils.CollectingResult.Companion.flatten
import utils.CollectingResult.Companion.map
import utils.CollectingResult.Companion.ok
import utils.mapToSet
import java.math.BigInteger

object EVMConfig: VMConfig {
    const val CALLDATA = "calldata"
    const val STORAGE = "storage"
    const val MEMORY = "memory"
    const val EXTERNAL = "external"
    const val INTERNAL = "internal"
    const val OPTIONAL = "optional"
    const val ENVFREE = "envfree"

    override val memoryLocations: Set<String> = setOf(CALLDATA, STORAGE, MEMORY)
    override val hookableOpcodes: Set<String> = HookType.values().toList().mapToSet { it.name }
    override val preReturnMethodQualifiers: Set<String> = setOf(EXTERNAL, INTERNAL)

    // Note: parser errors are coupled to this list; if you change it, see `postReturnCatchAll` in `cvl.cup`
    override val postReturnMethodQualifiers: Set<String> = setOf(OPTIONAL, ENVFREE)

    override val maxAddress: BigInteger = MASK_SIZE(EVM_ADDRESS_SIZE)
    override val registerBitwidth: Int = EVM_BITWIDTH256
    override val registerByteWidth: Int = EVM_WORD_SIZE_INT
    override val maxArraySizeFactor: Int
        get() = 32
    override val registerTag: Tag = Tag.Bit256
    override val maxUint: BigInteger = MAX_EVM_UINT256

    override fun getTypeResolver(s: ICVLScene, mainContract: String): TypeResolver =
        EVMTypeResolver.getResolver(s, mainContract)

    override fun getMethodQualifierAnnotations(preReturnAnnotations: List<LocatedToken>, postReturnAnnotations: List<LocatedToken>, range: Range): CollectingResult<MethodQualifiers, CVLError> =
        listOf(
            // yes internal and external are the only pre return annotations but that may not always be the case so
            // unless no one objects, I will leave this filter here
            preReturnAnnotations.filter { annot -> annot.value == INTERNAL || annot.value == EXTERNAL }.let { visibilityAnnotations ->
                if (visibilityAnnotations.isEmpty()) {
                    CVLError.General(range, "method must contain visibility annotation").asError()
                } else if (visibilityAnnotations.size > 1) {
                    CVLError.General(
                        visibilityAnnotations.last().range,
                        "method may only contain a single visibility annotation"
                    ).asError()
                } else {
                    ok
                }
            },
            postReturnAnnotations.filter { annot -> annot.value == OPTIONAL }.let { optionalAnnotations ->
                if (optionalAnnotations.size <= 1) {
                    ok
                } else {
                    CVLError.General(
                        optionalAnnotations.last().range,
                        "method may only contain a single optional modifier"
                    ).asError()
                }
            },
            postReturnAnnotations.filter { annot -> annot.value == ENVFREE }.let { envfreeAnnotations ->
                if (envfreeAnnotations.size <= 1) {
                    ok
                } else {
                    CVLError.General(
                        envfreeAnnotations.last().range,
                        "method may only contain a single envfree modifier"
                    ).asError()
                }
            }
        ).flatten().map { _ ->
            MethodQualifiers(
                virtual = postReturnAnnotations.any { locatedToken -> locatedToken.value == OPTIONAL },
                visibility = if (preReturnAnnotations.any { locatedToken -> locatedToken.value == INTERNAL }) {
                    Visibility.INTERNAL
                } else {
                    Visibility.EXTERNAL
                },
                envFree = postReturnAnnotations.any { locatedToken -> locatedToken.value == ENVFREE },
                library = false
            )
        }






}
