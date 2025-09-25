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

package move

import compiler.SourceContext
import config.*
import datastructures.stdcollections.*
import java.math.BigInteger
import java.nio.file.*
import java.security.MessageDigest
import kotlin.streams.*
import kotlinx.serialization.json.*
import log.*
import utils.*
import vc.data.*

/**
    Provides access to the source info from the Move source maps in the scene.
 */
class MoveSourceContext(val scene: MoveScene) {
    /** The TAC source context, relating source file indexes to their paths. */
    private val metaContext: SourceContext
    /** The TAC source file index for each Move source file hash. */
    private val metaContextIndexByHash: Map<BigInteger, Int>

    /** All of the Move source maps in the scene. */
    private val sourceMaps: Map<MoveModuleName, MoveSourceMap>

    /**
        Gets the source map for the given module (or null if there is no source map for the module).
     */
    operator fun get(module: MoveModuleName) = sourceMaps[module]

    /**
        Gets the source map for the given function (or null if there is no source map for the function).
     */
    operator fun get(funcDef: MoveModule.FunctionDefinition) =
        get(funcDef.function.name.module)?.functionMap?.get(funcDef.definitionIndex)

    /**
        Gets a TACMetaInfo representing the given source location, if the corresponding source file is available.
     */
    fun tacMeta(loc: MoveSourceMap.Location?): TACMetaInfo? {
        loc ?: return null
        val sourceIndex = metaContextIndexByHash[loc.fileHash] ?: return null
        val begin = loc.start
        val len = loc.end - loc.start
        return TACMetaInfo(
            source = sourceIndex,
            begin = begin,
            len = len,
            jumpType = null, // Not applicable for Move
            address = BigInteger.ZERO, // Not applicable for Move
            sourceContext = metaContext
        )
    }

    init {
        val json = Json { ignoreUnknownKeys = true }

        val metaContextIndexToFilePath = mutableMapOf<Int, String>()
        metaContextIndexByHash = mutableMapOf<BigInteger, Int>()

        // Find all of the Move source files, compute their hashes, and build the TAC source context.
        CertoraFileCache.certoraSourcesDir().takeIf { it.exists() }?.toPath()?.let { sourcesDir ->
            Files.walk(sourcesDir, FileVisitOption.FOLLOW_LINKS)
                .filter { @Suppress("ForbiddenMethodCall") it.toString().endsWith(".move") }
                .forEach { sourcePath ->
                    val relativePath = sourcesDir.relativize(sourcePath)

                    val index = metaContextIndexToFilePath.size
                    metaContextIndexToFilePath[index] = relativePath.toString()

                    val digest = MessageDigest.getInstance("SHA-256")
                    val hashBytes = digest.digest(Files.readAllBytes(sourcePath))
                    val hash = BigInteger(1, hashBytes)
                    metaContextIndexByHash[hash] = index
                }
        }

        metaContext = SourceContext(
            indexToFilePath = metaContextIndexToFilePath,
            sourceDir = "." // Source file paths are resolved relative to the certora sources dir itself
        )

        // Find all of the Move source maps in the scene and deserialize them.
        sourceMaps =
            Files.walk(scene.modulePath, FileVisitOption.FOLLOW_LINKS)
            .filter { (it.endsWith("source_maps") || it.endsWith("debug_info")) && Files.isDirectory(it) }
            .flatMap { Files.walk(it, FileVisitOption.FOLLOW_LINKS) }
            .filter { @Suppress("ForbiddenMethodCall") it.toString().endsWith(".json") }
            .parallel()
            .map {
                @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
                json.decodeFromStream<MoveSourceMap>(Files.newInputStream(it))
            }
            .asSequence()
            .associateBy { it.moduleName }
    }
}
