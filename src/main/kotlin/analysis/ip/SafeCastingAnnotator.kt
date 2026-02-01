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

package analysis.ip

import analysis.MustBeConstantAnalysis
import analysis.ip.SafeCastingAnnotator.safeCastingUpperBits
import config.Config
import tac.MetaKey
import utils.*
import vc.data.*
import java.math.BigInteger
import java.util.stream.Collectors
import datastructures.stdcollections.*
/**
 * Looks for specific `mload` commands, added in our python instrumentation, which encode a casting operation. This
 * mload is removed and an annotation with the decoded information is added.
 */
object SafeCastingAnnotator {

    private val safeCastingUpperBits = BigInteger("ffffff6e4604afefe123321beef1b03fffffffffffffff", 16)

    @KSerializable
    data class CastType(val isSigned: Boolean, val width: Int) : AmbiSerializable {
        constructor(encoded: Int) : this(encoded % 2 == 1, encoded / 2)
        constructor(encoded: BigInteger) : this(encoded.toInt())

        override fun toString() = "${ite(isSigned, "int", "uint")}$width"
    }

    @KSerializable
    data class SafeCastInfo(
        val from: CastType,
        val to: CastType,
        val value: TACSymbol,
        val range: Range.Range?
    ) : AmbiSerializable, TransformableSymEntity<SafeCastInfo>, WithSupport {
        override fun transformSymbols(f: (TACSymbol) -> TACSymbol) = copy(value = f(value))
        override val support = setOfNotNull(value as? TACSymbol.Var)
    }

    val CastingKey = MetaKey<SafeCastInfo>("tac.typecast.key")

    /**
     * looks for commands of the form `ByteStore(<mem address>, <magic constant>)`
     * that we instrumented in python land; replaces them with an annotation so that they can be used in the safeCasting
     * builtin rule.
     */
    fun annotate(code: CoreTACProgram): CoreTACProgram {
        if (!Config.SafeCastingBuiltin.get()) {
            return code
        }
        val g = code.analysisCache.graph
        val constantAnalysis = MustBeConstantAnalysis(g)
        val patcher = code.toPatchingProgram()
        val casts = code.parallelLtacStream().mapNotNull { (ptr, cmd) ->
            if (cmd is TACCmd.Simple.AssigningCmd.ByteStore && cmd.base == TACKeyword.MEMORY.toVar()) {
                constantAnalysis.mustBeConstantAt(ptr, cmd.loc)
                    ?.let { const ->
                        /**
                         * The encoding has its top bits equal to [safeCastingUpperBits], 16 bits for a unique counter,
                         * 16 encoding the type being casted, and 16 encoding the resulting type.
                         */
                        val (mask, line, column, from, to) = const.parseToParts(256 - 72, 20, 20, 16, 16)
                        runIf(mask == safeCastingUpperBits) {
                            ptr to SafeCastInfo(
                                from = CastType(from),
                                to = CastType(to),
                                value = cmd.value,
                                range = g.toCommand(ptr).sourceRange()?.makeNewRange(line, column)
                            )
                        }
                    }
            } else {
                null
            }
        }.collect(Collectors.toList())

        casts.forEach { (ptr, info) ->
            patcher.update(ptr, TACCmd.Simple.AnnotationCmd(CastingKey, info))
        }
        return patcher.toCodeNoTypeCheck(code)
    }
}
