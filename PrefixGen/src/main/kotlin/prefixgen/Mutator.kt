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

import bridge.types.PrimitiveType
import bridge.types.SolidityTypeDescription
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.int
import datastructures.stdcollections.*
import prefixgen.FileUtils.deserialize
import prefixgen.FileUtils.deserializeFile
import prefixgen.FileUtils.mustDirectory
import prefixgen.data.*
import prefixgen.data.TaggedFile.Companion.into
import utils.*
import java.io.File
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.deleteIfExists
import kotlin.random.Random

/**
 * Randomly perturb a counter example to try to find other aliasing relationships or boolean options.
 *
 * (Could be extended to try to find different values for other scalar args, but not implemented yet).
 */
object Mutator {
    @KSerializable
    data class RegenerationData(
        val aliasSelectors: Map<String, Int>,
        val selectedBools: Map<String, Boolean>,
    )

    /**
     * Extension of the [Compiler] which randomly adds some constraints to force different values for
     * booleans or aliasing choices. The randomization process is controlled by the given seed.
     */
    private class RandomizingCompiler(
        initialSeed: Int,
        val regen: RegenerationData,
        val resumeData: SequenceGenerator.ResumeData
    ) : Compiler() {
        val r = Random(initialSeed)

        val initialBinding = resumeData.sceneData.initialBinding.deserializeFile<Map<String, InitialBinding>>()
        val setupCode = File(resumeData.sceneData.setupFile).readText()


        val disc = resumeData.ast.mapNotNull { a ->
            when(a) {
                is SimpleSolidityAst.BindMust -> a.ty to a.discriminator
                is SimpleSolidityAst.BindInputChoice -> a.ty `to?` a.aliases
                else -> null
            }
        }

        override fun finalHook(): String {
            /**
             * randomly flip a boolean
             */
            val conjuncts = regen.selectedBools.mapNotNull { (k, v) ->
                if (!r.nextBoolean()) {
                    return@mapNotNull null
                }
                "${SequenceGenerator.inputParamName}.$k != $v"
            }

            /**
             * randomly choose some aliasing decisions and change them.
             */
            val aliasSelection = regen.aliasSelectors.mapNotNull { (dId, chosenAlias) ->
                val (ty, discData) = disc.find { (_, disc) ->
                    disc.discriminator == dId
                }!!
                /**
                 * This is mostly for debugging purposes, but aliasing decisions among addresses (given their
                 * role as "user identities" and "tokens" means this isn't a bad heuristic...
                 */
                if((ty as? SolidityTypeDescription.Primitive)?.primitiveName != PrimitiveType.address) {
                    return@mapNotNull null
                }
                /**
                 * Randomly decide to perturb this.
                 */
                if (!r.nextBoolean()) {
                    return@mapNotNull null
                }
                /**
                 * If we chose zero, force it to be an alias instead
                 */
                if (chosenAlias == 0) {
                    return@mapNotNull "${discData.selectedId} != 0"
                }
                /**
                 * If this is a must binding, pick another alias.
                 */
                if(discData.must) {
                    return@mapNotNull "${discData.selectedId} != $chosenAlias"
                }
                // different alias
                if (r.nextBoolean()) {
                    "${discData.selectedId} != 0 && ${discData.selectedId} != $chosenAlias"
                } else {
                    // fresh value
                    "${discData.selectedId} == 0"
                }
            }
            val assumeBody = (conjuncts + aliasSelection + "true").joinToString(" && ")
            return "vm.assume($assumeBody);"
        }

        fun generate() : String {
            return this.compileFuzzContract(
                s = resumeData,
                setupCode = setupCode,
                initialBindings = initialBinding
            ).soliditySource
        }
    }

    /**
     * Mutates a post-processed counterexample in [mutationData] based off of the a run on [resumeDataFile].
     * Places the mutants in [outputPath], using [startingSeed] for the random mutation process; defaulting to
     * a valud derived from a hash of the input data.
     *
     * [samples] indicates how many mutants to generate, each such mutant has a test case created at a path returned
     * by this function. The test case has the solidity component, and the resume data, which is hard-linked to [resumeDataFile]
     * (to save disk space). It goes without saying this won't work on an FS that doesn't support hardlinks but
     * in that case; here's a quarter kid, buy yourself a real OS.
     */
    fun mutate(
        resumeDataFile: TaggedFile<SequenceGenerator.ResumeData>,
        mutationData: TaggedFile<RegenerationData>,
        outputPath: Path,
        startingSeed: Int?,
        samples: Int
    ) : List<Path> {
        val seed = startingSeed ?: run {
            val mutationDataRaw = mutationData.f.readText()
            val resumeDataRaw = resumeDataFile.f.readText()
            val inputDigest = MessageDigest.getInstance("SHA-256")
            inputDigest.update(mutationDataRaw.toByteArray())
            inputDigest.update(resumeDataRaw.toByteArray())
            val rawHash = inputDigest.digest()
            BigInteger(
                rawHash
            ).toInt()
        }

        val resumeData = resumeDataFile.deserialize()
        val regenData = mutationData.deserialize()

        val randomizingCompiler = RandomizingCompiler(
            initialSeed = seed,
            resumeData = resumeData,
            regen = regenData
        )

        val toRet = mutableListOf<Path>()

        for(i in 0..<samples) {
            val mutant = randomizingCompiler.generate()
            val testName = "harness_mutant$i"
            outputPath.resolve("$testName.sol").toFile().writeText(mutant)
            val resumeOutput = outputPath.resolve("$testName${TestCase.resumeExtension}")
            resumeOutput.deleteIfExists()
            Files.createLink(
                resumeOutput,
                resumeDataFile.f.toPath()
            )
            toRet.add(outputPath.resolve(testName))
        }
        return toRet
    }

    @JvmStatic
    fun main(args: Array<String>) {
        object : CliktCommand("Mutator") {
            override fun help(context: Context): String = "Perturb previously found CEX"

            val samples by option(help = "The number of samples to generate").int().default(100)

            val resumeData by argument(
                help = "The resume data file. Usually has the extension `resume.json`"
            ).file(
                mustExist = true,
                canBeDir = false,
                canBeFile = true
            )

            val mutationData by argument(
                help = "The mutation data file generated by `Resumer`. Usually has the extension `mutate.json`"
            ).file(
                mustExist = true,
                canBeDir = false,
                canBeFile = true
            )

            val outputDir by argument(
                help = "The output directory into which the tests are generated. Created if it does not already exist"
            ).file(mustExist = false, canBeFile = false)

            val seed by option(
                "seed",
                help = "The random seed to use for generation. By default constructed deterministically from input data."
            ).int()

            override fun run() {
                mutate(
                    resumeDataFile = resumeData.into(),
                    outputPath = outputDir.mustDirectory(),
                    mutationData = mutationData.into(),
                    samples = samples,
                    startingSeed = seed
                )
            }
        }.main(args)
    }
}
