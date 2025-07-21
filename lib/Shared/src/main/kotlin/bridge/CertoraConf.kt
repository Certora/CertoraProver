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
@file:UseSerializers(utils.BigIntegerSerializer::class)

package bridge

import kotlinx.serialization.SerializationException
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import log.ArtifactManagerFactory
import log.IArtifactsManager
import log.Logger
import log.LoggerTypes
import spec.CVLReservedVariables
import spec.cvlast.SolidityContract
import utils.ArtifactFileUtils
import utils.CertoraErrorType
import utils.CertoraException
import utils.CertoraFileCache
import java.io.File
import java.nio.file.Path

/* Utilities for reading project build, verification, and advanced tool configuration */

private val logger = Logger(LoggerTypes.COMMON)

fun getFallbackMethod(primaryContractName: String) =
    Method(
        name = CVLReservedVariables.certorafallback_0.name,
        fullArgs = listOf(),
        returns = listOf(),
        isLibrary = false,
        contractName = primaryContractName
    )

typealias BuildConf = Map<String, SingleDeployedContract>
typealias NamedContractIdentifier = SolidityContract
typealias ContractIdentifier = NamedContractIdentifier

object CertoraConf {
    private fun loadBuildConfFromJsonStr(jsonStr: String): BuildConf {
        // should we make this non-strict to make it more backward compatible? TODO in the end should be strict
        // 3/30/20: nonstrict was split into two properties: ignoreUnknownKeys and isLenient (which does not require
        // the input stringto fit the JSON specification i.e. allows malformed but parsable input: currently this will
        // fail without ignoreUnknown keys set to true
        return try {
            Json {
                ignoreUnknownKeys = true
            }.decodeFromString(MapSerializer(String.serializer(), SingleDeployedContract.serializer()), jsonStr)
        } catch (e: SerializationException) {
            throw CertoraException(
                CertoraErrorType.INCOMPATIBLE_SERIALIZATION,
                "The Certora CLI version is incompatible with the Certora Cloud version. " +
                    "You may need to update your Certora CLI. " +
                    "Users running on a specific Prover branch may need to install a specific CLI release.",
                e
            )
        }
    }

    private fun loadVerificationConfFromJsonStr(jsonStr: String): VerificationQuery {
        // TODO: Make this strict again, on purpose changed to support changing formats
        val verificationQuery = try {
            Json {
                ignoreUnknownKeys = true
            }.decodeFromString(VerificationQuery.serializer(), jsonStr)
        } catch (e: SerializationException) {
            throw CertoraException(
                CertoraErrorType.INCOMPATIBLE_SERIALIZATION,
                "The Certora CLI version is incompatible with the Certora Cloud version. " +
                    "You may need to update your Certora CLI. " +
                    "Users running on a specific Prover branch may need to install a specific CLI release.",
                e
            )
        }
        // Do some verification
        validateVerificationQueries(verificationQuery)
        return verificationQuery
    }

    private fun <C> loadConf(path: Path, loader: (String) -> C): C {
        return CertoraFileCache.getContentReader(path.toString()).use { it.readText() }.let(loader)
    }

    fun loadVerificationConf(verificationConf: Path): VerificationQuery =
        loadConf(verificationConf, ::loadVerificationConfFromJsonStr)

    fun loadBuildConf(buildConf: Path): BuildConf = loadConf(buildConf, ::loadBuildConfFromJsonStr)

    private fun validateVerificationQueries(verificationQuery: VerificationQuery) {
        if (verificationQuery.type == VerificationQueryType.spec
            && verificationQuery.primary_contract.isBlank()
        ) {
            logger.error("Primary contract for spec verification query $verificationQuery is blank")
        }
    }

    /**
     * Copy important files to a backup location.
     * @param buildFilename The name of the build config file (ie .certora_build)
     * @param verificationFilename The name of the verification config file (ie .certora_verify)
     * @param cfgFileNames The names of sol/spec files (located in the .certora_config dir)
     * @param metadataFilename The name of the metadata config file (ie .certora_metadata.
     *                         Note: This file may not be present)
     */
    fun copyInputs(
        buildFilename: String, verificationFilename: String, cfgFileNames: Set<String>,
        metadataFilename: String
    ) {
        val manager = ArtifactManagerFactory()
        manager.backupFileWithoutRelativePath(buildFilename)
        manager.backupFileWithoutRelativePath(verificationFilename)
        backupFiles(cfgFileNames, manager)
        if (File(metadataFilename).exists()) {
            manager.backupFileWithoutRelativePath(metadataFilename)
        }
    }

    /**
     * Copies the files into [manager]'s input directory, retaining the relative paths.
     */
    fun backupFiles(fileNames: Set<String>, manager: IArtifactsManager = ArtifactManagerFactory()) {
        fileNames.forEach { manager.backupRetainRelativePath(it) }
    }

    /**
     * Backups the file without the relative path. For example, `mydir/file.txt` will be backed up as `inputs/file.txt`.
     */
    private fun IArtifactsManager.backupFileWithoutRelativePath(fileName: String) {
        val backupFileName = "${this.inputsDir}${File.separator}${ArtifactFileUtils.getNameFromPath(fileName)}"
        this.backup(fileName, backupFileName)
    }

    /**
     * Backups the file retaining the relative path. For example, `mydir/file.txt` will be backed up as `inputs/mydir/file.txt`.
     */
    private fun IArtifactsManager.backupRetainRelativePath(fileName: String) {
        val backupFileName = "${this.inputsDir}${File.separator}${fileName}"
        this.backup(fileName, backupFileName)
    }

}
