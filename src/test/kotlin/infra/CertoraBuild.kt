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

package infra

import bridge.CertoraConf
import bridge.NamedContractIdentifier
import cli.Ecosystem
import config.*
import datastructures.stdcollections.*
import kotlinx.serialization.json.*
import sbf.SolanaConfig
import scene.*
import scene.source.CertoraBuilderContractSource
import spec.cvlast.typechecker.CVLError
import utils.*
import utils.CertoraFileCache.certoraSourcesDir
import utils.CollectingResult.Companion.map
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.lazy

/** An exception that is thrown when an error occurs during the Certora build process. */
class CertoraBuildException(message: String) : Exception(message)

/** A build for a particular type of verification task. Currently, supports EVM and Solana-based builds. */
sealed interface CertoraBuildKind {

    /** Build for verifying EVM-based smart contracts. */
    class EVMBuild(val buildOptions: List<String> = emptyList()) : CertoraBuildKind

    /** Build for verifying Solana-based smart contracts. Also specifies the entrypoint for the verification task. */
    class SolanaBuild(val solanaEntrypoint: HashSet<String>) : CertoraBuildKind

    /** The command-line tool that has to be called to start the building process. */
    fun certoraProverScript(): Path {
        return when (this) {
            is EVMBuild -> Path("certoraRun.py")
            is SolanaBuild -> Path("certoraSolanaProver.py")
        }
    }

    /** Addtional build options to pass to the certora build command. */
    fun buildOptions(): List<String> {
        return when (this) {
            is EVMBuild -> this.buildOptions
            is SolanaBuild -> listOf()
        }
    }
}


/**
 * contains [path], a directory containing the artifacts created by `CertoraRun`,
 * after running it with this directory as the target.
 * [command] is the exact command used to execute `CertoraRun` and [conf] is the conf file used in this execution.
 */
class CertoraBuild internal constructor(
    val source: CertoraBuildSource,
    val command: List<String>,
    private val buildKind: CertoraBuildKind
) : Closeable {
    private var closed = false

    val contractSource: CertoraBuilderContractSource by lazy {
        val buildConfFile = this.source.buildDir.resolve(CERTORA_BUILD_FILE_PATH)
        val buildConf = CertoraConf.loadBuildConf(buildConfFile)
        CertoraBuilderContractSource(buildConf)
    }

    /** load verification query conf, patch it up to fix paths */
    private fun verificationQuerySource(): CertoraBuilderSpecSource {
        val originalVerificationQueryFile = this.source.buildDir.resolve(CERTORA_VERIFY_FILE_PATH)

        val sources = this.source.buildDir.resolve(SOURCES_SUBDIR)

        val remappedVerificationQuery = CertoraConf
            .loadVerificationConf(originalVerificationQueryFile)
            .remapPaths(sources)
        return CertoraBuilderSpecSource(remappedVerificationQuery)
    }

    fun getScene(query: ProverQuery.CVLQuery?): IScene {
        return SceneFactory.getScene(
            this.contractSource,
            SceneType.PROVER,
            (query as? ProverQuery.CVLQuery.Single)?.cvl
        )
    }

    fun getProverQueryFromVerificationQuery(): CollectingResult<ProverQuery, CVLError> {
        val specSource = this.verificationQuerySource()
        val certoraBuilderContractSource = this.contractSource
        val scene = this.getScene(query = null)
        return specSource.getQuery(certoraBuilderContractSource.instances(), scene)
    }

    fun getProverQueryWithSceneFromText(cvlText: String, primaryContractName: String): CollectingResult<Pair<IScene, ProverQuery>, CVLError> {
        val specSource = CVLSpecTextSource(
            cvlText,
            NamedContractIdentifier(primaryContractName)
        )

        return this.withBuildDir { build ->
            val certoraBuilderContractSource = build.contractSource

            specSource.getQuery(
                certoraBuilderContractSource.instances(),
                SceneFactory.getCVLScene(certoraBuilderContractSource)
            ).map { query ->
                build.getScene(query) to query
            }
        }
    }

    /** executes [action] in an environment where [Config.CertoraBuildDirectory] is [path], then closes this object */
    inline fun <T> useWithBuildDir(action: (CertoraBuild) -> T): T {
        return this.use {
            this.withBuildDir(action)
        }
    }

    /** executes [action] in an environment where [Config.CertoraBuildDirectory] is [path] */
    inline fun <T> withBuildDir(action: (CertoraBuild) -> T): T {
        getConfigScope().use {
                return action(this)
            }
    }

    fun getConfigScope(): ConfigScope {
        // set the build directory, which is shared among all build kinds
        val configScope = ConfigScope(Config.CertoraBuildDirectory, source.buildDir.toString())
        return when (buildKind) {
            is CertoraBuildKind.EVMBuild -> {
                configScope
            }

            is CertoraBuildKind.SolanaBuild -> {
                // solana-based verification tasks needs the Solana entrypoint to be specified
                configScope
                    .extend(Config.SolanaEntrypoint, buildKind.solanaEntrypoint)
                    .extend(Config.ActiveEcosystem, Ecosystem.SOLANA)
                    // Never elide functions — this is used for testing purposes.
                    // Note that reading the config file does *not* apply any of the configurations specified within it.
                    // This is temporary. Once the options in the config file are correctly applied, this can be removed.
                    .extend(SolanaConfig.TACMinSizeForCalltrace, 0)
            }
        }
    }

    override fun close() {
        if (!this.closed) {
            this.closed = true
            this.source.cleanup()
        }
    }

    companion object {
        fun inTempDir(buildKind: CertoraBuildKind, conf: Path): CertoraBuild {
            val source = CertoraBuildSource.TempDir(buildDir = createTempDir(), conf = conf)
            return source.build(buildKind)
        }

        sealed class EVMCompiler(val cmd: String, val suffix: String, val jsonKeyword: String) {
            class Solidity(cmd: String) : EVMCompiler(cmd, ".sol", "solc")
            class Vyper(cmd: String) : EVMCompiler(cmd, ".vy", "vyper")

            val isVyper get() = this is Vyper
            override fun toString() = cmd
        }

        fun JsonObjectBuilder.viaIR(value: Boolean = true) {
            if (value) {
                put("solc_via_ir", true)
            }
        }

        fun JsonObjectBuilder.solcOptimize(value: Int) =
            put("solc_optimize", value.toString())

        fun JsonObjectBuilder.solcOptimize(value: Boolean) {
            if (value) {
                solcOptimize(200)
            }
        }


        /**
         * [buildConf] is used to add stuff to the conf file, by working directly with the json. Above there are some
         * extension functions to make this easier, e.g., [viaIR].
         */
        @OptIn(ExperimentalPathApi::class)
        fun withGeneratedContract(
            primaryContractName: String = "test",
            contract: String,
            compiler: EVMCompiler,
            buildConf : JsonObjectBuilder.() -> Unit = {}
        ): CertoraBuild {
            val cleanupList = mutableListOf<Path>()

            return try {
                val contractFile = Files.createTempFile(workingDirectory(), "A", compiler.suffix)
                contractFile.writeText(contract)
                cleanupList.add(contractFile)
                val contractFileName = contractFile.fileName.toString()

                val contractFilename = contractFile.fileName.toString()

                val contractSourcesFile = certoraSourcesDir().toPath().resolve(contractFilename)
                contractSourcesFile.createParentDirectories() // we do not delete the directories created in this process
                contractFile.copyTo(contractSourcesFile, overwrite = true)
                cleanupList.add(contractSourcesFile)

                val confFile = Files.createTempFile(workingDirectory(), "", ".conf")
                val jsonBuilder = buildJsonObject {
                    put(compiler.jsonKeyword, compiler.cmd)
                    putJsonArray("files") {
                        when(compiler) {
                            is EVMCompiler.Solidity -> add("$contractFileName:$primaryContractName")
                            is EVMCompiler.Vyper -> add(contractFileName)
                        }
                    }
                    buildConf()
                }
                confFile.writeText(jsonBuilder.toString())
                cleanupList.add(confFile)

                val source = CertoraBuildSource.TempDirWithGeneratedContract(
                    buildDir = createTempDir(),
                    conf = confFile,
                    contract = contractFile
                )
                source.build(CertoraBuildKind.EVMBuild())
            } catch (e: Exception) {
                cleanupList.asReversed().forEach(Path::deleteRecursively)
                throw e
            }
        }
    }
}

/**
 * Creates a temporary directory and returns it as a File object.
 *
 * @return The created temporary directory.
 */
internal fun createTempDir(): Path {
    val tempDirFromEnv: Path? = System.getenv("CERTORA_TEMP_FOLDER")?.let(::Path)
    val prefix = "test"

    val tempDir: Path = if (tempDirFromEnv != null) {
        Files.createTempDirectory(tempDirFromEnv, prefix)
    } else {
        Files.createTempDirectory(prefix)
    }

    return tempDir
}

@OptIn(ExperimentalPathApi::class)
sealed class CertoraBuildSource {
    class TempDir(
        override val buildDir: Path,
        override val conf: Path,
    ) : CertoraBuildSource() {
        override fun cleanup() {
            this.buildDir.deleteRecursively()
        }
    }

    class TempDirWithGeneratedContract(
        override val buildDir: Path,
        override val conf: Path,
        val contract: Path,
    ) : CertoraBuildSource() {
        override fun cleanup() {
            this.buildDir.deleteRecursively()
            this.contract.deleteIfExists()
            this.conf.deleteIfExists()
        }
    }

    abstract val buildDir: Path
    abstract val conf: Path
    /** not using [java.io.Closeable] here for now */
    abstract fun cleanup()

    internal fun build(buildKind: CertoraBuildKind): CertoraBuild {
        this.ensureBuildDirDoesNotExist()

        val command = listOf(
            buildKind.certoraProverScript().toString(),
            this.conf.name,
            "--build_dir",
            this.buildDir.toString(),
            "--build_only"
        ) + buildKind.buildOptions()

        val processBuilder = ProcessBuilder(command)
            .directory(this.conf.parent.toFile())
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectError(ProcessBuilder.Redirect.PIPE)
            .start()

        val errorStream = ByteArrayOutputStream()
        val outputStream = ByteArrayOutputStream()

        processBuilder.inputStream.transferTo(outputStream)
        processBuilder.errorStream.transferTo(errorStream)

        val exitCode = processBuilder.waitFor()

        if (exitCode != 0) {
            throw CertoraBuildException(
                "Something went wrong during the build, check conf file.\n" +
                    "command: $command\n" +
                    "output: ${outputStream.toString().trim()}.\n" +
                    "error: ${errorStream.toString().trim()}"
            )
        }

        return CertoraBuild(this, command, buildKind)
    }

    /** this is a kludge: CertoraRun currently expects a build directory to not exist. */
    private fun CertoraBuildSource.ensureBuildDirDoesNotExist() {
        when (this) {
            is TempDir,
            is TempDirWithGeneratedContract -> {
                // we could create a new directory inside the build directory,
                // but it's easier to just delete the (known unique and empty) temp directory.
                // which is silly and could potentially lead to race conditions but whatever.
                try {
                    this.buildDir.deleteIfExists()
                } catch (_: DirectoryNotEmptyException) {
                    throw CertoraBuildException("got non-empty build directory: ${this.buildDir}")
                }
            }
        }
    }
}
