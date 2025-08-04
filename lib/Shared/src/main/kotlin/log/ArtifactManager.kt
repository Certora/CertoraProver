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

package log

import allocator.Allocator
import config.Config
import config.ConfigType
import datastructures.stdcollections.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import log.ArtifactManagerFactory.WithArtifactMode.WithArtifacts
import log.ArtifactManagerFactory.WithArtifactMode.WithoutArtifacts
import org.apache.commons.lang3.SystemUtils
import spec.rules.IRule
import spec.cvlast.RuleIdentifier
import tac.*
import utils.*
import java.io.*
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap

/**
 * Serializes and dumps the provided test [artifact] of type [T] into a rule-specific test artifact file.
 * This function registers the specified [registerArtifactKey] with the Artifact Manager and utilizes it to
 * manage the artifact's lifecycle.
 *
 * @param registerArtifactKey The key used to register the test artifact with the Artifact Manager.
 * @param artifact The test artifact of type [T] to be serialized and stored.
 */
inline fun <reified T> Json.dumpTestArtifact(
    registerArtifactKey: RuleTestArtifactKey,
    artifact: T
) {
    // Register the provided artifact key with the Artifact Manager.
    ArtifactManagerFactory().registerRuleTestArtifact(registerArtifactKey)

    // Use the registered artifact key to manage the artifact lifecycle.
    ArtifactManagerFactory().useArtifact(registerArtifactKey) { artifactFile ->
        FileWriter(artifactFile).use { writer ->
            // Serialize the artifact using the provided JSON instance.
            writer.write(this@dumpTestArtifact.encodeToString(artifact))
        }
    }
}




@JvmInline
value class RuleOutputArtifactsKey(val ruleIdentifier: RuleIdentifier)

/**
 * used to save the coreTACProgram and the Examples info
 * into a dynamic file that has the rule name in order to reconstruct the counter-example
 * in unit tests
 *
 * XXX: it may be that [declarationId] can be derived from [RuleIdentifier],
 * which would make it redundant, but let's just keep both for now.
 */
data class RuleTestArtifactKey(val ruleIdentifier: RuleIdentifier, val declarationId: String, val kind: TestArtifactKind) {
    enum class TestArtifactKind(val suffixFileName: String) {
        EXAMPLES_INFO("examplesInfo.json"),
        CORE_TAC_PROGRAM("CoreTACProgram.tac"),
        VIOLATED_ASSERT("ViolatedAssert.json"),
        SINGLE_RULE("SingleRule.json"),
        EXPECTED("expected.json")
    }

    constructor(rule: IRule, kind: TestArtifactKind) : this(rule.ruleIdentifier, rule.declarationId, kind)

    internal val ruleNameSanitize = ArtifactFileUtils.SanitizedPath(declarationId).sanitizedFileBaseName
    internal val filename = "${ruleNameSanitize}_${kind.suffixFileName}"

    internal fun toArtifactLocation(): ArtifactLocation = DynamicArtifactLocation(
        StaticArtifactLocation.VerifierResults,
        File(ruleNameSanitize),
    )
}

/**
 * represents a rule's registered artifacts.
 *
 * this class allows obtaining the path of this rule's output files,
 * which have been registered in the process of constructing the class instance.
 * in other words, owning a value of this instance means thatthese artifacts have been registered.
 */
@JvmInline
value class RuleOutputArtifacts private constructor(private val id: Int) {
    val liveStatisticsFileName: String get() = "rule_live_statistics_${id}.json"
    val ruleOutputFileName: String get() = "rule_output_${id}.json"

    /**
     * Note, the suffix "certora-dap" is used by the VSCode extension.
     * The VSCode extension watches on additions, changes and deletions of
     * all files with this suffix, do not change.
     */
    val dapCalltraceOutputFileName: String get() = "dap_calltrace_${id}-certora-dap.json"

    companion object {
        /**
         * "allocates" and registers the artifacts for the rule's output files,
         * then returns an instance referencing these output files
         */
        internal fun new(registeredArtifacts: ConcurrentMap<String, ArtifactLocation>): RuleOutputArtifacts {
            val id = Allocator.getFreshId(Allocator.Id.RULE_OUTPUT)
            val artifacts = RuleOutputArtifacts(id)

            registeredArtifacts[artifacts.liveStatisticsFileName] = StaticArtifactLocation.TreeViewReports
            registeredArtifacts[artifacts.ruleOutputFileName] = StaticArtifactLocation.TreeViewReports
            registeredArtifacts[artifacts.dapCalltraceOutputFileName] = StaticArtifactLocation.TreeViewReports

            return artifacts
        }
    }
}

/**
 * In general, CVT generates several kinds of artifacts:
 * 1 - Reports: these are consumed by clients of CVT - these are the 2 HTML reports, the calltrace data underlying those,
 * and the outputs json consumed by CI/automated tools.
 * It is reasonable to assume that those should always be produced (though we currently allow not to)
 * 2 - Outputs: these are artifacts used by CVT itself for its normal operation. Ideally this would be an empty set.
 * 3 - Inputs: backup of the inputs provided by the client, concentrated together to allow reproducing the run.
 * 4 - Debugs: debugging information such as dumps of TACs and their HTML representation.
 * 5 - Formulas: a special kind of output that is instrumental to the tool - the SMTLib files sent to solvers.
 *
 * An [ArtifactManager] determines how to handle the different kinds of artifacts described above.
 * Currently there are 2 Managers for 2 modes: Enabled or Disabled.
 * An Enabled manager will not silence any output generation unless instructed otherwise,
 * a Disabled manager will suppress all outputs.
 *
 * The [mainPath] is the main directory where artifacts will be stored (if at all).
 * The artifact types fall into different subdirectories represented with [ArtifactLocation].
 * (In the future this may be generalized to represent a remote location, a database, mail service?)
 */

interface IArtifactsManager {



    val mainPath: String
    val mainReportsDir: File
    val outputDir: File
    val debugDir: File
    val formulasDir: File
    val inputsDir: File
    fun <T : DumpType> dumpCodeArtifacts(
        p: DebuggableProgram<T>,
        dumpType: T,
        location: ArtifactLocation,
        time: DumpTime
    )

    fun <T : DumpType> dumpMandatoryCodeArtifacts(
        p: DebuggableProgram<T>,
        dumpType: T,
        location: ArtifactLocation,
        time: DumpTime
    )

    fun <T : DumpType> dumpBinary(p: DebuggableProgram<T>, location: ArtifactLocation, label: String): TACFile?
    fun useArtifact(name: String, action: (String) -> Unit)
    fun useArtifact(versionedArtifact: VersionedArtifact, version: Int, action: (String) -> Unit)
    fun useArtifact(key: RuleTestArtifactKey, action: (String) -> Unit)
    fun <T> withArtifact(name: String, action: String.() -> Result<T>): Result<T>?
    fun getArtifactHandle(name: String): OutputStreamWriter
    fun backup(fileName: String, backupFileName: String)

    fun registerArtifact(
        name: String,
        location: ArtifactLocation = StaticArtifactLocation.Reports
    ): String?

    fun registerArtifact(
        versionedArtifact: VersionedArtifact,
        location: ArtifactLocation = StaticArtifactLocation.Reports
    ) = registerArtifact(versionedArtifact.unversionedName, location)

    fun registerArtifact(
        conf: ConfigType<String>
    ) = registerArtifact(
        conf.get(),
        if (conf.isDefault()) {
            StaticArtifactLocation.Reports
        } else {
            StaticArtifactLocation.CWD
        }
    )

    fun getArtifactHandle(conf: ConfigType<String>): OutputStreamWriter
    fun getCmdErrorFilepath(name: String): String {
        val trunc = fitFileLength("Cmd_${name}.err.txt", ".err.txt")
        return "${ArtifactManagerFactory().debugDir}${File.separator}$trunc"
    }

    fun getCmdOutputFilepath(name: String): String {
        val trunc = fitFileLength("Cmd_${name}.out.txt", ".out.txt")
        return "${ArtifactManagerFactory().debugDir}${File.separator}$trunc"
    }

    fun getRegisteredArtifactPathOrNull(name: String): String?
    fun getRegisteredArtifactPathOrNull(versionedArtifact: VersionedArtifact, version: Int): String?
    fun copyArtifact(dstLoc: ArtifactLocation, name: String)
    fun <T : DumpType> dumpCodeArtifacts(p: DebuggableProgram<T>, time: DumpTime)
    fun <T : DumpType> dumpCodeArtifacts(p: DebuggableProgram<T>, dumpType: T, time: DumpTime)
    fun getFilePathForSmtQuery(name: String, subdir: String?): String
    fun getRegisteredArtifactPath(conf: ConfigType<String>): String
    fun <T : VersionedArtifact> getRegisteredArtifactPath(conf: ConfigType<T>, version: Int): String
    fun getRegisteredArtifactPath(name: String): String
    fun getRegisteredArtifactPath(versionedArtifact: VersionedArtifact, version: Int): String
    fun getOrRegisterRuleOutputArtifacts(key: RuleOutputArtifactsKey): RuleOutputArtifacts?
    fun registerRuleTestArtifact(key: RuleTestArtifactKey)
    fun copyInputsToRootOfOutputDir(filenames: List<String>)
    fun getArtifactHandleStream(name: String): OutputStream
    fun useTempArtifact(name: String, action: (String) -> Unit): String {
        val tmpFile = File.createTempFile(name, null)
        tmpFile.deleteOnExit()
        action(tmpFile.path)
        return tmpFile.path
    }

    /**
     * Given a file name and its suffix, returns a variant of the file name that does not exceed the
     * [maxFilenameLength]. If [name] is short enough it will be returned as is; otherwise a variant is constructed
     *  using a hash.
     * Note: [name] should have [suffix] as a suffix already, as this will not auto-append [suffix] in case the name
     *  is short enough.
     */
    fun fitFileLength(name: String, suffix: String): String {
        if (maxFilenameLength == null) {
            return name
        }
        if (name.length <= maxFilenameLength) {
            return name
        }
        val truncation = maxFilenameLength - suffix.length - 9
        val hash =
            digestItems(listOf(name)).joinToString("") { it.toHexString() }.substring(0, 8)
        val newName = name.substring(0, truncation) + "-" + hash + suffix
        check(newName.length <= maxFilenameLength) { "New length is still too long... ${newName.length} > $maxFilenameLength" }
        return newName
    }

    companion object {
        private const val COMMON_FILENAME_LENGTH_LIMIT = 255
        private val maxFilenameLength: Int? =
            System.getenv("CERTORA_MAX_FILENAME")?.let(String::toInt) ?: COMMON_FILENAME_LENGTH_LIMIT

    }
}

inline fun IArtifactsManager.registerArtifact(
    name: String,
    location: ArtifactLocation = StaticArtifactLocation.Reports,
    action: (String) -> Unit
) {
    registerArtifact(name, location)?.let {
        getRegisteredArtifactPathOrNull(it)?.let {
            action(it)
        }
    }
}

inline fun IArtifactsManager.registerArtifact(
    versionedArtifact: VersionedArtifact,
    location: ArtifactLocation = StaticArtifactLocation.Reports,
    action: (String) -> Unit
) {
    registerArtifact(versionedArtifact, location)?.let {
        getRegisteredArtifactPathOrNull(it)?.let {
            action(it)
        }
    }
}

inline fun IArtifactsManager.registerArtifact(
    conf: ConfigType<String>,
    action: (String) -> Unit
) {
    registerArtifact(conf)?.let {
        getRegisteredArtifactPathOrNull(it)?.let {
            action(it)
        }
    }
}

inline fun IArtifactsManager.writeArtifact(
    name: String,
    location: ArtifactLocation = StaticArtifactLocation.Reports,
    overwrite: Boolean = false,
    append: Boolean = false,
    contents: () -> String) {
    registerArtifact(name, location) { n ->
        ArtifactFileUtils.getWriterForFile(n, overwrite, append).use { i ->
            i.append(contents())
        }
    }
}


private object StandardArtifactManager : IArtifactsManager {
    override val mainPath = ConfigType.ExecName.get()

    private fun getWhere(l: ArtifactLocation): File = l.getResolvedPath(mainPath)

    override val mainReportsDir: File
        get() = getWhere(StaticArtifactLocation.Reports)

    @Suppress("unused")
    val treeViewReportsDir: File
        get() = getWhere(StaticArtifactLocation.TreeViewReports)

    override val outputDir: File
        get() = getWhere(StaticArtifactLocation.Outputs)

    override val debugDir: File
        get() = getWhere(StaticArtifactLocation.Debugs)

    override val formulasDir: File
        get() = getWhere(StaticArtifactLocation.Formulas)

    override val inputsDir: File
        get() = getWhere(StaticArtifactLocation.Input)

    override fun <T : DumpType> dumpCodeArtifacts(p: DebuggableProgram<T>, time: DumpTime) {
        dumpCodeArtifacts(p, p.defaultType, time)
    }

    override fun <T : DumpType> dumpCodeArtifacts(p: DebuggableProgram<T>, dumpType: T, time: DumpTime) {
        dumpCodeArtifacts(p, dumpType, StaticArtifactLocation.Debugs, time)
    }

    override fun <T : DumpType> dumpCodeArtifacts(
        p: DebuggableProgram<T>,
        dumpType: T,
        location: ArtifactLocation,
        time: DumpTime
    ) {
        if (!Config.LowFootprint.get() &&
            dumpType.isEnabled()
        ) {
            p.dump(dumpType, getWhere(location).path, time)
        }
    }

    override fun <T : DumpType> dumpMandatoryCodeArtifacts(
        p: DebuggableProgram<T>,
        dumpType: T,
        location: ArtifactLocation,
        time: DumpTime
    ) =
        p.dump(dumpType, getWhere(location).path, time)

    override fun <T : DumpType> dumpBinary(
        p: DebuggableProgram<T>,
        location: ArtifactLocation,
        label: String
    ): TACFile {
        return p.dumpBinary(getWhere(location).path, label)
    }

    // Standard generated reports locations will be here - actions for writing those files should go through artifact manager and not directly
    // The keys of this map is just the filenames - though it is expected to have those names come from configuration and not from a hard coded place.
    private val registeredArtifacts: ConcurrentMap<String, ArtifactLocation> = ConcurrentHashMap()

    /**
     * Maps a [RuleOutputArtifactsKey] to a corresponding rule_output json file.
     */
    private val rulesToOutputArtifacts: ConcurrentMap<RuleOutputArtifactsKey, RuleOutputArtifacts> = ConcurrentHashMap()

    private fun artifactPathOf(loc: ArtifactLocation, name: String): String = "${getWhere(loc)}${File.separator}$name"

    private fun artifactPathOfOrNull(locOrNull: ArtifactLocation?, name: String): String? =
        locOrNull?.let { loc: ArtifactLocation -> artifactPathOf(loc, name) }

    override fun getRegisteredArtifactPathOrNull(name: String) = artifactPathOfOrNull(registeredArtifacts[name], name)

    override fun getRegisteredArtifactPathOrNull(versionedArtifact: VersionedArtifact, version: Int) =
        artifactPathOfOrNull(
            registeredArtifacts[versionedArtifact.unversionedName],
            versionedArtifact.versionedNameBuilder(version)
        )

    override fun getRegisteredArtifactPath(conf: ConfigType<String>) = getRegisteredArtifactPath(conf.get())
    override fun <T : VersionedArtifact> getRegisteredArtifactPath(conf: ConfigType<T>, version: Int) =
        getRegisteredArtifactPath(conf.get(), version)

    override fun getRegisteredArtifactPath(name: String) = getRegisteredArtifactPathOrNull(name) ?: "N/A"
    override fun getRegisteredArtifactPath(versionedArtifact: VersionedArtifact, version: Int) =
        getRegisteredArtifactPathOrNull(versionedArtifact, version) ?: "N/A"

    override fun registerArtifact(name: String, location: ArtifactLocation): String {
        if (registeredArtifacts.containsKey(name)) {
            logger.info { "Artifact $name already registered in ${registeredArtifacts[name]}" }
        }
        registeredArtifacts.putIfAbsent(name, location)
        return name
    }

    /**
     * registers [key] in the artifact manager and returns the rule's registered files.
     *
     * the return type here is non-null, as one would expect from a function named "getOrRegister",
     * however because this function is only accessible via [IArtifactsManager.getOrRegisterRuleOutputArtifacts],
     * and null _can_ be returned from [DisabledArtifactManager.getOrRegisterRuleOutputArtifacts],
     * then in practice calling this function of does not actually guarantee that artifacts
     * have been initialized.
     *
     * this is an unfortunate consequence of the enabled/disabled artifact manager split,
     * which is a leaky abstraction.
     */
    override fun getOrRegisterRuleOutputArtifacts(key: RuleOutputArtifactsKey): RuleOutputArtifacts {
        return rulesToOutputArtifacts.getOrPut(key) {
            RuleOutputArtifacts.new(this.registeredArtifacts)
        }
    }

    override fun registerRuleTestArtifact(key: RuleTestArtifactKey) {
        registeredArtifacts.putIfAbsent(
            key.filename,
            key.toArtifactLocation()
        )
    }

    override fun useArtifact(key: RuleTestArtifactKey, action: (String) -> Unit) {
        val ruleOutputPath = getRegisteredArtifactPathOrNull(key.filename)
        if (ruleOutputPath == null) {
            logger.warn { "Could not use output artifact for rule ${key.ruleIdentifier.displayName}, since this rule has not been registered." }
            return
        }
        action(ruleOutputPath)
    }

    override fun useArtifact(versionedArtifact: VersionedArtifact, version: Int, action: (String) -> Unit) {
        val fullPath = getRegisteredArtifactPathOrNull(versionedArtifact, version)
        if (fullPath == null) {
            logger.warn {
                "Could not use versioned artifact (version=$version), since ${
                    versionedArtifact.unversionedName
                } is not a valid file name."
            }
            return
        }
        action(fullPath)
    }

    override fun useArtifact(name: String, action: (String) -> Unit) {
        val fullPath = getRegisteredArtifactPathOrNull(name)
        if (fullPath == null) {
            logger.warn { "Could not use artifact, since $name is not a valid file name." }
            return
        }
        action(fullPath)
    }

    override fun <T> withArtifact(name: String, action: String.() -> Result<T>): Result<T> {
        return getRegisteredArtifactPathOrNull(name)?.let { it.action() }
            ?: Result.failure(IllegalArgumentException("Non existing artifact $name"))
    }

    override fun getArtifactHandle(conf: ConfigType<String>): OutputStreamWriter = getArtifactHandle(conf.get())

    override fun getArtifactHandle(name: String): OutputStreamWriter = OutputStreamWriter(getArtifactHandleStream(name))

    override fun getArtifactHandleStream(name: String): OutputStream {
        val fullPath = getRegisteredArtifactPathOrNull(name)
        return if (fullPath == null) {
            error("Non existing artifact $name")
        } else {
            FileOutputStream(fullPath)
        }
    }

    /**
     * Copy important files to a backup location.
     * @param fileName The name of the file to back up. Note that this may be simply a standalone
     *                 file name (ie .certora_build) or a path (ie .certora_config/Method2.sol_1/0_Method2.sol)
     * @param backupFileName The file name that fileName will be backed up to
     * @return Unit
     */
    override fun backup(fileName: String, backupFileName: String) {
        try {
            val reader = CertoraFileCache.getContentReader(fileName)
            reader.use {
                // build the directory structure, if needed.
                val backupFileDir = File(backupFileName).parentFile
                backupFileDir.mkdirs()
                val writer = File(backupFileName).bufferedWriter()
                writer.use {
                    reader.copyTo(writer)
                }
            }
        } catch (e: Exception) {
            Logger.alwaysError("Received exception $e; Failed to backup $fileName", e)
        }
    }


    /**
     * Copies an artifact (usually to a S3 uploaded folder), for example cvt_version.json from inputs to Reports
     * Assumes [name] already registered under some location
     */
    override fun copyArtifact(dstLoc: ArtifactLocation, name: String) {
        val src = getRegisteredArtifactPath(name)
        val dst = artifactPathOf(dstLoc, name)
        if (src == dst) {
            logger.warn { "Trivial copy of an artifact $name from $src to $dst, skipping" }
        }
        Files.copy(File(src).toPath(), File(dst).toPath())
    }


    private val logger = Logger(LoggerTypes.COMMON)


    override fun copyInputsToRootOfOutputDir(filenames: List<String>) {
        val target = ArtifactManagerFactory().mainPath
        try {
            filenames.forEach { filename ->
                val targetFile =
                    "${target}${File.separator}${ArtifactFileUtils.getBasenameOnly(filename)}.${
                        File(filename).extension
                    }"
                val reader = CertoraFileCache.getContentReader(filename)
                reader.use {
                    val writer = File(targetFile).bufferedWriter()
                    writer.use { reader.copyTo(it) }
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to copy some inputs files ${filenames}, got error: ${e.message}")
        }

    }

    /**
     * Note: We assume that invalid filenames will be ignored by [useArtifact].
     */
    override fun getFilePathForSmtQuery(name: String, subdir: String?): String {
        val subdirString = subdir.orEmpty()
        val baseDir = ArtifactManagerFactory().formulasDir.resolve(subdirString)
        val basePathPrefix =
            "${baseDir.path}${File.separator}${Config.FormulaFileBasename.get()}_${Allocator.getFreshNumber()}_"
        val smt2file = "${basePathPrefix}${name}.smt2"
        val fileLengthLimitWindows = 250
        if (SystemUtils.IS_OS_WINDOWS && smt2file.length > fileLengthLimitWindows) {
            logger.warn {
                "Cannot dump .smt2 file for $name, since we're on Windows and file name" +
                    "would be too long. (assumed length limit: $fileLengthLimitWindows)"
            }
            return "" // invalid file name -- will be ignored
        }
        if (!baseDir.exists() && !baseDir.mkdirs()) {
            logger.error("Failed to create formulas directory ${baseDir.path}")
        }
        return smt2file
    }
}

private object DisabledArtifactManager : IArtifactsManager {
    private val logger = Logger(LoggerTypes.COMMON)
    private val stubDir = workingDirectory().toFile()

    override val mainPath: String
        get() = stubDir.toString()
    override val mainReportsDir: File
        get() = stubDir
    override val outputDir: File
        get() = stubDir
    override val debugDir: File
        get() = stubDir
    override val formulasDir: File
        get() = stubDir
    override val inputsDir: File
        get() = stubDir

    override fun <T : DumpType> dumpCodeArtifacts(
        p: DebuggableProgram<T>,
        dumpType: T,
        location: ArtifactLocation,
        time: DumpTime
    ) = Unit

    override fun <T : DumpType> dumpCodeArtifacts(p: DebuggableProgram<T>, time: DumpTime) = Unit

    override fun <T : DumpType> dumpCodeArtifacts(p: DebuggableProgram<T>, dumpType: T, time: DumpTime) = Unit

    override fun <T : DumpType> dumpMandatoryCodeArtifacts(
        p: DebuggableProgram<T>,
        dumpType: T,
        location: ArtifactLocation,
        time: DumpTime
    ) {
        logger.info { "When disabling artifacts, cannot dump mandatory artifact of type ${dumpType} in $location" }
    }

    override fun <T : DumpType> dumpBinary(
        p: DebuggableProgram<T>,
        location: ArtifactLocation,
        label: String
    ): TACFile? = null

    override fun useArtifact(name: String, action: (String) -> Unit) {}
    override fun useArtifact(versionedArtifact: VersionedArtifact, version: Int, action: (String) -> Unit) {}
    override fun useArtifact(key: RuleTestArtifactKey, action: (String) -> Unit) {}

    override fun <T> withArtifact(name: String, action: String.() -> Result<T>): Result<T>? {
        logger.info {
            "Cannot apply actions when artifacts are disabled"
        }
        return null
    }


    override fun getArtifactHandle(name: String) = OutputStreamWriter(OutputStream.nullOutputStream())
    override fun getArtifactHandle(conf: ConfigType<String>) = OutputStreamWriter(OutputStream.nullOutputStream())

    override fun backup(fileName: String, backupFileName: String) {}

    override fun registerArtifact(name: String, location: ArtifactLocation): String? = null

    override fun getRegisteredArtifactPathOrNull(name: String) = null
    override fun getRegisteredArtifactPathOrNull(versionedArtifact: VersionedArtifact, version: Int) = null

    override fun copyArtifact(dstLoc: ArtifactLocation, name: String) {}

    override fun getFilePathForSmtQuery(name: String, subdir: String?): String {
        val subdirString = subdir?.let { "${File.separator}$it" }.orEmpty()
        return this.useTempArtifact(Config.FormulaFileBasename.get() + subdirString) { }
    }

    override fun getRegisteredArtifactPath(conf: ConfigType<String>) = "N/A"

    override fun <T : VersionedArtifact> getRegisteredArtifactPath(conf: ConfigType<T>, version: Int) = "N/A"

    override fun getRegisteredArtifactPath(name: String) = "N/A"

    override fun getRegisteredArtifactPath(versionedArtifact: VersionedArtifact, version: Int) = "N/A"
    override fun getOrRegisterRuleOutputArtifacts(key: RuleOutputArtifactsKey): RuleOutputArtifacts? = null

    override fun registerRuleTestArtifact(key: RuleTestArtifactKey) {}
    override fun copyInputsToRootOfOutputDir(filenames: List<String>) {}
    override fun getArtifactHandleStream(name: String): OutputStream = BufferedOutputStream(OutputStream.nullOutputStream())
}

/**
 * Provides access to a global instance of [ArtifactManager], which can be either a [StandardArtifactManager] or a
 * [DisabledArtifactManager].
 *
 * Note (alex): factory is not the most appropriate name here .. more like `ArtifactManagerInstance` or so.
 *    maybe even better: put this all into the companion of [ArtifactManager]..
 */
/**
 * Singleton object responsible for creating and managing instances of [IArtifactsManager].
 * The [ArtifactManagerFactory] allows clients to retrieve an [IArtifactsManager] based on the
 * current configuration.
 */
object ArtifactManagerFactory {

    /**
     * Enumeration representing different modes for handling artifacts.
     * - [WithArtifacts]: Indicates that artifacts are enabled.
     * - [WithoutArtifacts]: Indicates that artifacts are disabled.
     */
    enum class WithArtifactMode {
        WithArtifacts, WithoutArtifacts;
    }

    // Configuration for handling artifacts. By default, it is set to the default value in the config.
    private val artifactsHandling = ConfigType.WithArtifacts

    /**
     * Check whether artifacts are enabled or not.
     *
     * @return `true` if artifacts are enabled, `false` otherwise.
     */
    fun isEnabled(): Boolean = when (artifactsHandling.get()) {
        WithArtifactMode.WithArtifacts -> true
        WithArtifactMode.WithoutArtifacts -> false
    }

    /**
     * Returns an instance of [IArtifactsManager] based on the current configuration.
     *
     * @return [IArtifactsManager] instance, either [StandardArtifactManager] if artifacts are enabled,
     * or [DisabledArtifactManager] if artifacts are disabled.
     */
    operator fun invoke(): IArtifactsManager {
        return if (isEnabled()) {
            StandardArtifactManager
        } else {
            DisabledArtifactManager
        }
    }

    /**
     * use this when an enabled artifact manager is required.
     * a non-null value means that an enabled artifact manager was returned.
     *
     * a kludge to simulate type safety.
     * due to class visibility, the caller can't statically validate
     * which artifact manager they got from [ArtifactManagerFactory.invoke],
     * by using this function, the caller asserts that they're aware of this.
     */
    fun enabledOrNull(): IArtifactsManager? = invoke().takeIf { this.isEnabled() }

    /**
     * Annotate a function with this annotation to indicate that it should only be used in unit testing.
     *
     * @param message The error message to be shown if the function is used outside of tests.
     * @param level The level of strictness for the opt-in requirement. Default is [RequiresOptIn.Level.ERROR].
     */
    @Target(AnnotationTarget.FUNCTION)
    @RequiresOptIn(message = "Should only be used in unit testing", level = RequiresOptIn.Level.ERROR)
    annotation class UsedOnlyInTests

    /**
     * Returns an instance of [IArtifactsManager] based on the provided [withArtifactMode].
     *
     * @param withArtifactMode The [WithArtifactMode] to determine the type of [IArtifactsManager] to return.
     * @return [IArtifactsManager] instance, either [StandardArtifactManager] if [WithArtifactMode.WithArtifacts],
     * or [DisabledArtifactManager] if [WithArtifactMode.WithoutArtifacts].
     */
    @UsedOnlyInTests
    operator fun invoke(withArtifactMode: WithArtifactMode): IArtifactsManager = when (withArtifactMode) {
        WithArtifactMode.WithArtifacts -> StandardArtifactManager
        WithArtifactMode.WithoutArtifacts -> DisabledArtifactManager
    }
}
