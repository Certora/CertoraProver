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
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package prefixgen

import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.int
import prefixgen.FileUtils.deserialize
import prefixgen.FileUtils.mustDirectory
import prefixgen.data.TaggedFile
import prefixgen.data.TaggedFile.Companion.into
import prefixgen.selection.SelectorGenerator

/**
 * Extends a symbolic sequence, via [SequenceGenerator.extend]
 */
object Extender {
    fun extend(
        targetContract: String,
        resumeFile: TaggedFile<SequenceGenerator.Extendable>,
        selector: SelectorGenerator,
        maxDepth: Int
    ): Sequence<SequenceGenerator.ResumableArtifact> {
        val resume = resumeFile.deserialize<SequenceGenerator.Extendable>()
        val r = SequenceGenerator(
            resume.sceneData,
            generator = selector,
            maxDepth = maxDepth,
            targetContractType = targetContract
        )
        return r.extend(resume)
    }

    @JvmStatic
    fun main(s: Array<String>) {
        object : PrefixGenCommand("Extender", "Extend (symbolically) previous sequences") {
            val resumeFile by argument(
                help = "The resume data file, usually ends in `${TestCase.resumeExtension}`"
            ).file(mustExist = true, canBeFile = true, canBeDir = false)

            val outputDir by argument(
                help = "The output directory into which the extensions will be generated"
            ).file(mustExist = false, canBeFile = false, canBeDir = true)

            val depth by option(
                help = "The length of sequences to generate"
            ).int().default(1)

            override fun run() {
                val path = outputDir.mustDirectory()
                for((i, ext) in extend(
                    targetContract,
                    resumeFile = resumeFile.into(),
                    maxDepth = depth,
                    selector = samplingStrategy
                ).withIndex().take(samples)) {
                    ext.saveToPath(
                        path.resolve("harness$i").toString()
                    )
                }
            }
        }.main(s)
    }
}
