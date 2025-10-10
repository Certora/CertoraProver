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

import bridge.Method
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.groups.OptionGroup
import com.github.ajalt.clikt.parameters.groups.defaultByName
import com.github.ajalt.clikt.parameters.groups.groupChoice
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.validate
import com.github.ajalt.clikt.parameters.types.enum
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.int
import parallel.coroutines.onThisThread
import prefixgen.FileUtils.deserialize
import prefixgen.FileUtils.mustDirectory
import prefixgen.FileUtils.serialize
import prefixgen.data.TestCaseFileExt.mutateDataFile
import prefixgen.data.TestCaseFileExt.resumeDataFile
import prefixgen.data.TestCaseFileExt.runDataFile
import prefixgen.fuzzer.FuzzRunner
import prefixgen.selection.*
import prefixgen.selection.WeightedStrategy.Companion.t
import java.nio.file.Path
import kotlin.random.Random
import datastructures.stdcollections.*
import prefixgen.data.TestCaseFileExt.extendDataFile
import prefixgen.data.TestCaseFileExt.reducedDataFile
import prefixgen.fuzzer.RunData
import prefixgen.selection.ICommonRandomOptions.Companion.levelTerminationProbability
import prefixgen.selection.ICommonRandomOptions.Companion.seed

/**
 * The main "loop" around the component tools of [Extender], [Resumer] etc.
 *
 * Tries to intelligently explore the test cases and then extend promising ones.
 *
 * The logic is currently pretty barebones. One important concept is "stagnation", that refers
 * to looking at some test case T which includes method calls m1, m2, ..., mk. Stagnation
 * means that every extension (or resumption) of that test case only gives tests that include
 * some subset of the extant `mi`, that is, the discovery of new method calls has stagnated.
 */
@Suppress("ForbiddenMethodCall") // allow println debugging for now until we figure out better logging
object Explorer {
    /**
     * Wrapper around [SelectorGenerator] which allows
     * controlling whether repeats are allowed and changing the selector strategy
     * based on given test cases.
     */
    interface ContextSensitiveSelectorGenerator {
        /**
         * Get a [SelectorGenerator] based off an existing test case (usually for extending
         * or resuming), or null if there is no context for the generation. This is a total operation.
         */
        fun forTest(testCase: TestCase?) : SelectorGenerator

        /**
         * Return a [ContextSensitiveSelectorGenerator] allowing repeats, or null if this is not supported
         */
        fun allowRepeats(): ContextSensitiveSelectorGenerator?

        /**
         * Whether the [SelectorGenerator] returned by this object allow repeats.
         */
        val allowsRepeats: Boolean

    }

    /**
     * Whether to allow repeats in the sequence.
     */
    enum class RepeatPolicy {
        NEVER,

        /**
         * Always allow repeats
         */
        ALWAYS,

        /**
         * Allow repeats after stagnation; i.e., new test cases have not been found.
         */
        STAGNATION;

        val initialAllow : Boolean get() = this == ALWAYS
    }

    /**
     * `Optional` here doesn't mean "not required" but rather "set via command line options"; the name sucks.
     * See the [OptionalTestParams] implementer for a discsussion of what these fields mean.
     */
    interface ImmutableUserTestParams {
        val stagnationLimit: Int
        val concretizationDepth: Int
        val chunkSize: Int
        val repeatPolicy: RepeatPolicy
        val maxDepth: Int
        val sampleSize: Int
        val reduceEvery: Int
    }

    /**
     * Parameters that come from the command line *arguments*
     */
    interface ImmutableTestParams : ImmutableUserTestParams {
        val workingDirectory: Path
        val targetContract: String
    }

    class OptionalTestParams : OptionGroup("Control params"), ImmutableUserTestParams {
        override val stagnationLimit by option(
            help = "The number of calls that can be performed without seeing a new method appear"
        ).int().default(3).validate { stag ->
            require(stag > 0) {
                "Cannot have stagnation limit of 0"
            }
        }

        override val concretizationDepth by option(
            help = "The total length of sequences after which extensions are done on concrete CEX discovered by the fuzzer. -1 = always symbolic"
        ).int().default(-1)

        override val chunkSize by option(
            help = "The number of operations to generate in each step. Should be a divisor of stagnationLimit and concretizationDepth"
        ).int().default(1)

        override val repeatPolicy by option(
            help = "Whether calls can repeat in sequences"
        ).enum<RepeatPolicy>().default(RepeatPolicy.STAGNATION)

        override val maxDepth by option(
            help = "The absolute maximum length of sequences. Must be a multiple of chunkSize"
        ).int().default(10)

        override val sampleSize by option(
            help = "The maximum number of samples to generate at each level"
        ).int().default(100)

        override val reduceEvery: Int by option(
            help = "Reduce the search space using aliasing heuristics every N rounds. (0 = never reduce)"
        ).int().default(0)
    }

    /**
     * A dummy interface that can be used for yielding partial or completed test cases. Will likely be
     * made more sophisticated as exploration gets smarter.
     */
    interface OutputCollector {
        suspend fun collectPartial(f: TestCase)
        suspend fun complete(f: TestCase)
    }

    /**
     * How many method calls have occured in the sequence since the last time we saw a new method call.
     */
    private fun TestCase.stagnationCount() : Int {
        if(this.parent == null) {
            return 0
        }
        val prevSeq = this.parent.currentSequence()
        val currSeq = this.currentSequence()
        if(currentSequence().toSet() != prevSeq.toSet()) {
            return 0
        }
        return (currSeq.size - prevSeq.size) + this.parent.stagnationCount()
    }

    /**
     * Run [t] using the [selectorGenerator], in the context of the [ImmutableTestParams] and [OutputCollector].
     * Returns `true` if this test or any of its children finds a new test case.
     */
    context(ImmutableTestParams, OutputCollector)
    private suspend fun runTest(selectorGenerator: ContextSensitiveSelectorGenerator, t: TestCase): Boolean {
        println("[Explorer] Starting test execution for: ${t.testName}")
        println("[Explorer] Current sequence length: ${t.currentSequence().size}, Stagnation count: ${t.stagnationCount()}")

        // termination checks
        if(t.currentSequence().size >= maxDepth || t.stagnationCount() >= stagnationLimit) {
            println("[Explorer] Test ${t.testName} reached termination condition (maxDepth: $maxDepth, stagnationLimit: $stagnationLimit)")
            complete(t)
            return true
        } else {
            collectPartial(t)
        }
        val currLength = t.currentSequence().size
        println("[Explorer] Generating extensions for test ${t.testName} (current length: $currLength)")

        /**
         * This means if `concretizationDepth` is -1, this will always be false (Which is what we want)
         */
        val useConcrete = concretizationDepth in 0..currLength

        /**
         * Is this the test case where we switch to concrete mode?
         */
        val isConcreteFrontier = useConcrete && t.parent?.let { p ->
            // same trick as above, if `concretizationDepth` is -1, this is definitely false
            concretizationDepth !in 0 .. p.currentSequence().size
        } == true

        /**
         * If so, resume
         */
        val extension = if(useConcrete) {
            println("[Explorer] Using concrete extension via Resumer (concretizationDepth: $concretizationDepth)")
            Resumer.resume(
                selector = selectorGenerator.forTest(t),
                resumeFile = t.resumeDataFile,
                mutationDataPath = t.mutateDataFile,
                calldata = t.runDataFile.deserialize().calldata,
                depth = chunkSize,
                targetContractType = targetContract
            )
        } else {
            println("[Explorer] Using symbolic extension via Extender")
            val extTarget = if(reduceEvery != 0 && ((t.searchDepth + 1) % reduceEvery) == 0) {
                println("Reducing ${t.testId}")
                t.reducedDataFile.serialize(Reducer.reduce(
                    resFile = t.resumeDataFile,
                    cd = t.runDataFile,
                    workingDir = workingDirectory,
                    generationDir = t.reductionDirectory
                ))
                t.reducedDataFile
            } else {
                t.extendDataFile
            }
            Extender.extend(
                selector = selectorGenerator.forTest(t),
                maxDepth = chunkSize,
                resumeFile = extTarget,
                targetContract = targetContract
            )
        }
        val output = t.extensionsDir.mustDirectory()

        val childTestGeneration = runGeneratedTests(
            selectorGenerator = selectorGenerator,
            output = output,
            parent = t,
            extension = extension
        )
        if(isConcreteFrontier && !childTestGeneration) {
            // try mutants
            return tryMutate(selectorGenerator, t)
        }
        return childTestGeneration
    }

    /**
     * Try mutating the aliasing relationships to see if that gets new test cases.
     * This selection should probably be *way* more intelligent than it currently is.
     */
    context(ImmutableTestParams, OutputCollector)
    private suspend fun tryMutate(selectorGenerator: ContextSensitiveSelectorGenerator, t: TestCase): Boolean {
        println("[Explorer] Starting mutation attempt for test case: ${t.testName}")
        println("[Explorer] Generating mutants with sample count: 50")


        val mutants = Mutator.mutate(
            resumeDataFile = t.resumeDataFile,
            startingSeed = null,
            samples = 20,
            mutationData = t.mutateDataFile,
            outputPath = t.mutantsPath.mustDirectory()
        )

        println("[Explorer] Generated ${mutants.size} mutant paths")

        val testCaseMutants = mutants.map {
            TestCase(it, t.parent).also {
                check(it.exists())
            }
        }

        println("[Explorer] Created ${testCaseMutants.size} mutant test cases")
        println("[Explorer] Executing ${testCaseMutants.size} mutant test cases with FuzzRunner")
        val res = FuzzRunner(workingDirectory).fuzzTests(
            testCaseMutants.map { m -> m.solSourcePath }.asSequence()
        ).onFailure {
            println("[Explorer] Error running mutant tests: $it")
        }.getOrNull() ?: run {
            println("[Explorer] Failed to execute mutant tests")
            return false
        }
        println("[Explorer] Processing ${testCaseMutants.size} mutant execution results")
        var processedCount = 0
        for(mut in testCaseMutants) {
            val calldataResult = res[mut.solSourcePath]
            if(calldataResult == null) {
                println("[Explorer] No calldata result for mutant: ${mut.testName}")
                continue
            }

            println("[Explorer] Processing mutant ${mut.testName} - serializing run data")
            mut.runDataFile.serialize(RunData(calldataResult))

            println("[Explorer] Generating extensions for mutant ${mut.testName} (depth: $chunkSize)")
            val mutantExtensions = Resumer.resume(
                calldata = calldataResult,
                depth = chunkSize,
                resumeFile = mut.resumeDataFile,
                targetContractType = targetContract,
                mutationDataPath = mut.mutateDataFile,
                selector = selectorGenerator.forTest(mut)
            )

            println("[Explorer] Post-processing extensions for mutant ${mut.testName}")
            if(runGeneratedTests(mutantExtensions, mut, selectorGenerator, mut.extensionsDir.mustDirectory())) {
                println("[Explorer] Mutant ${mut.testName} produced successful results")
                return true
            }

            processedCount++
            println("[Explorer] Completed processing mutant ${mut.testName} ($processedCount/${testCaseMutants.size})")
        }

        println("[Explorer] All $processedCount mutants processed, no successful results found")
        return false
    }

    /**
     * Actually runs the tests in [extension], and if it yields a counterexample, continuing exploration via [runTest].
     * If [parent] is non-null, all of the test cases in [extension] were generated based off of [parent], either
     * via symbolic or concrete extension. [output] is the output directory into which the test cases should be materialized
     * onto disk, and [selectorGenerator] is the selector to use in future calls to [runTest].
     */
    context(ImmutableTestParams, OutputCollector)
    private suspend fun runGeneratedTests(
        extension: Sequence<SequenceGenerator.ResumableArtifact>,
        parent: TestCase?,
        selectorGenerator: ContextSensitiveSelectorGenerator,
        output: Path
    ) : Boolean {
        println("[Explorer] Post-processing tests for parent: ${parent?.testName ?: "root"}")
        val toRun = mutableListOf<TestCase>()
        println("[Explorer] Processing extensions (max sample size: $sampleSize)")
        for((i, res) in extension.withIndex().take(sampleSize)) {
            val childOutput = output.resolve("harness$i")
            res.saveToPath(childOutput.toString())
            toRun.add(TestCase(childOutput, parent))
        }
        println("[Explorer] Created ${toRun.size} test cases")
        if(toRun.isEmpty() && !selectorGenerator.allowsRepeats && repeatPolicy == RepeatPolicy.STAGNATION) {
            if(parent == null) {
                println("[Explorer] No test cases generated at root level")
                return false
            }
            println("[Explorer] No test cases generated, switching to repeat-allowing strategy for parent")
            return runTest(selectorGenerator.allowRepeats() ?: return false, parent)
        } else if(toRun.isEmpty()) {
            println("[Explorer] No test cases generated and no fallback available")
            return false
        }
        println("[Explorer] Executing ${toRun.size} test cases with FuzzRunner")
        val runner = FuzzRunner(
            workingDirectory
        )
        val runResult = runner.fuzzTests(
            toRun.map { it.solSourcePath }.asSequence()
        ).onFailure {
            println("[Explorer] Error running test ${parent?.testName}: $it")
            if(it is FuzzRunner.InvalidSolidityTestException) {
                println(it.errorStream)
            }
        }.getOrNull() ?: return false

        val childTasks = mutableListOf<TestCase>()
        println("[Explorer] Processing ${toRun.size} execution results")

        for(ran in toRun) {
            val outputData = runResult[ran.solSourcePath] ?: continue
            ran.runDataFile.serialize(
                RunData(
                    calldata = outputData
                )
            )
            childTasks.add(ran)
        }

        println("[Explorer] Starting recursive execution of ${childTasks.size} child test cases")
        var succ = false
        for(next in childTasks) {
            val r = runTest(selectorGenerator, next)
            succ = r || succ
        }
        return succ
    }

    private class CommonRandomOpts : OptionGroup("Common Random Generation Options") {
        val seed by seed()

        val levelTerminationProb by levelTerminationProbability()

    }

    /**
     * A generator for the [ContextSensitiveSelectorGenerator]
     * which initializes with an initial stagnation policy.
     *
     * I'm not *super* happy with this design tbh, but I can't think of a better one at the moment.
     */
    sealed class StrategyInitializer(name: String) : OptionGroup(name) {
        abstract fun initialize(
            repeats: RepeatPolicy
        ) : ContextSensitiveSelectorGenerator
    }

    private class WeightedStrategyInit(val commonRandomOpts: CommonRandomOpts) : StrategyInitializer("Weighted strategy") {

        /**
         * the [t] function here is an extension function which adds a command line option for controlling `t`
         * to the current option group, which here is the weighted strategy config.
         */
        val t by t()

        inner class Strategy(
            override val allowsRepeats: Boolean
        ) : ContextSensitiveSelectorGenerator {
            override fun allowRepeats(): ContextSensitiveSelectorGenerator? {
                if(allowsRepeats) {
                    return null
                }
                return Strategy(true)
            }

            override fun forTest(testCase: TestCase?): SelectorGenerator {
                val present = testCase?.currentSequence()?.toSet()
                /**
                 * Gives assigns to methods that haven't appeared in [testCase] yet
                 * level L, where L is the current depth of the search.
                 */
                return object : SelectorGenerator {
                    override fun selectorFor(universe: List<Method>): Selector {
                        val newLevels = if(present != null) {
                            universe.associate {
                                val k = it.sighash!!
                                if (k in present) {
                                    k to 0
                                } else {
                                    k to testCase.searchDepth
                                }
                            }
                        } else {
                            mapOf()
                        }
                        return object : WeightedStrategy {
                            override val t: Int
                                get() = this@WeightedStrategyInit.t
                            override val levels: Map<String, Int>
                                get() = newLevels
                            override val levelTerminationProbability: Double?
                                get() = commonRandomOpts.levelTerminationProb
                            override val random: Random = Random(commonRandomOpts.seed)
                            override val allowsRepeats: Boolean
                                get() = this@Strategy.allowsRepeats
                            override val mustInclude: Set<String>
                                get() = setOf()
                        }.selectorFor(universe)
                    }
                }
            }
        }

        override fun initialize(repeats: RepeatPolicy): ContextSensitiveSelectorGenerator {
            return Strategy(repeats.initialAllow)
        }
    }

    private class Uniform(val commonRandomOpts: CommonRandomOpts) : StrategyInitializer("Uniform sampling") {
        /**
         * A [ContextSensitiveSelectorGenerator] that is also itself the [SelectorGenerator] (the selection strategy doesn't
         * depend on a particular test case).
         */
        inner class Strategy(override val allowsRepeats: Boolean) : UniformSelector, ContextSensitiveSelectorGenerator {
            override val levelTerminationProbability: Double?
                get() = commonRandomOpts.levelTerminationProb
            override val random: Random = Random(commonRandomOpts.seed)
            override val mustInclude: Set<String>
                get() = setOf()

            override fun forTest(testCase: TestCase?): SelectorGenerator {
                return this
            }

            override fun allowRepeats(): ContextSensitiveSelectorGenerator? {
                if(allowsRepeats) {
                    return null
                }
                return Strategy(true)
            }
        }

        override fun initialize(repeats: RepeatPolicy): ContextSensitiveSelectorGenerator {
            return Strategy(repeats.initialAllow)
        }
    }

    class Enumerate : StrategyInitializer("Exhaustive sampling") {
        /**
         * Ibid with [Uniform]
         */
        inner class Strategy(override val allowsRepeats: Boolean) : ContextSensitiveSelectorGenerator, EnumerationStrategy {
            override fun forTest(testCase: TestCase?): SelectorGenerator {
                return this
            }

            override fun allowRepeats(): ContextSensitiveSelectorGenerator? {
                if(allowsRepeats) {
                    return null
                }
                return Strategy(true)
            }

            override val mustInclude: Set<String>
                get() = setOf()
        }

        override fun initialize(repeats: RepeatPolicy): ContextSensitiveSelectorGenerator {
            return Strategy(repeats.initialAllow)
        }
    }

    @JvmStatic
    fun main(l: Array<String>) {
        object : CliktCommand("Explorer"), Start.StartupMixin {
            val sdcFile by sdcFile()
            val binding by binding()
            val setupFile by setupFile()

            val generationParams by OptionalTestParams()

            val randomOpts by CommonRandomOpts()

            val samplingStrategy by option(
                help = "The sampling strategy to use"
            ).groupChoice(
                "enum" to Enumerate(),
                "weighted" to WeightedStrategyInit(randomOpts),
                "uniform" to Uniform(randomOpts)
            ).defaultByName("uniform")

            val targetContract by argument(
                help = "Target contract name"
            )

            val targetDirectory by argument(
                help = "The top-level directory to generate files in"
            ).file(
                mustExist = false,
                canBeFile = false,
                canBeDir = true
            )

            val workingDirectory by argument(
                help = "The top-level directory of the foundry project under test"
            ).file(
                mustExist = true,
                canBeFile = false,
                canBeDir = true
            )

            override fun run() {
                val strategy = samplingStrategy.initialize(
                    generationParams.repeatPolicy
                )
                val dirPath = targetDirectory.mustDirectory()
                val workingDir = workingDirectory
                val target = targetContract
                val immutable = object : ImmutableTestParams, ImmutableUserTestParams by generationParams {
                    override val workingDirectory: Path
                        get() = workingDir.toPath()
                    override val targetContract: String
                        get() = target
                }
                println("[Explorer] Initializing exploration for contract: $targetContract")
                println("[Explorer] Working directory: ${workingDir.absolutePath}")
                println("[Explorer] Target directory: ${targetDirectory.absolutePath}")
                println("[Explorer] Chunk size: ${immutable.chunkSize}, Max depth: ${immutable.maxDepth}")
                println("[Explorer] Sample size: ${immutable.sampleSize}, Stagnation limit: ${immutable.stagnationLimit}")
                println("[Explorer] Repeat policy: ${immutable.repeatPolicy}, Concretization depth: ${immutable.concretizationDepth}")

                val toProcess = Start.start(
                    targetContract = targetContract,
                    depth = immutable.chunkSize,
                    sdcFile = sdcFile,
                    setupFile = setupFile,
                    bindingFile = binding,
                    samplingStrategy = strategy.forTest(null)
                )

                println("[Explorer] Initial sequence generation completed")
                val output = object : OutputCollector {
                    override suspend fun collectPartial(f: TestCase) { }

                    override suspend fun complete(f: TestCase) {
                        println("Have concrete test case:")
                        println(f.currentSequence())
                    }

                }
                println("[Explorer] Starting main exploration loop")
                onThisThread {
                    immutable.run {
                        output.run {
                            runGeneratedTests(
                                toProcess,
                                parent = null,
                                output = dirPath,
                                selectorGenerator = strategy
                            )
                        }
                    }
                }
                println("[Explorer] Exploration completed")
            }
        }.main(l)
    }
}
