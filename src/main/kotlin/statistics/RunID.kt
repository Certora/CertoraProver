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

package statistics

import config.Config
import config.ConfigType
import scene.IScene
import spec.cvlast.CVLAst
import java.security.MessageDigest
import utils.*

/**
 * Maintains a unique identifier information of the current CVT run.
 */
interface RunID {

    val allData: Set<SDFeature<*>>

    val runStartData: Set<SDFeature<*>>

    val runEndData: Set<SDFeature<*>>

    val sceneData: Set<ScalarKeyValueStats<String>>

    val cvlData: Set<ScalarKeyValueStats<String>>

    /**
     * Report the start of a run of CVT.
     */
    fun reportRunStart(): RunID

    /**
     * Report the end of a run of CVT.
     */
    fun reportRunEnd(): RunID

    /**
     * Report all the contracts listed in the specified [scene].
     */
    fun reportScene(scene: IScene): RunID

    /**
     * Report the CVL spec file [cvlFile] represented by the specified [cvlAst].
     */
    fun reportCVL(cvlFile: String, cvlAst: CVLAst): RunID
}

object RunIDFactory {
    /**
        * Returns the [RunID] of the current CVT run.
        */
    fun runId(enabler: ConfigType<Boolean> = Config.EnableStatistics): RunID {
        return when (enabler.get()) {
            true -> EnabledRunID
            false -> DisabledRunID
        }
    }
}

object DisabledRunID : RunID {
    override val allData: Set<SDFeature<*>>
        get() = emptySet()
    override val runStartData: Set<SDFeature<*>>
        get() = emptySet()
    override val runEndData: Set<SDFeature<*>>
        get() = emptySet()
    override val sceneData: Set<ScalarKeyValueStats<String>>
        get() = emptySet()
    override val cvlData: Set<ScalarKeyValueStats<String>>
        get() = emptySet()

    override fun reportRunStart() = this
    override fun reportRunEnd() = this
    override fun reportScene(scene: IScene) = this
    override fun reportCVL(cvlFile: String, cvlAst: CVLAst) = this
}

object EnabledRunID : RunID {

    private val RUN_ID = "run_id".toSDFeatureKey()
    private val START_TIMESTAMP = "start_timestamp".toTimeTag()
    private val END_TIMESTAMP = "end_timestamp".toTimeTag()
    private val START_TO_END_TIME = "start_to_end_time".toTimeTag()

    private val startToEndTime = ElapsedTimeStats(RUN_ID)
    private val startStamper = TimeStampStats(RUN_ID)
    private val endStamper = TimeStampStats(RUN_ID)

    private val baseSceneLogger =
        ScalarKeyValueStats<String>(RUN_ID, IScene.ForkInfo.BASE.toString().toSDFeatureKey())
    private val cvlSceneLogger =
        ScalarKeyValueStats<String>(RUN_ID, IScene.ForkInfo.CVL.toString().toSDFeatureKey())

    private fun sceneForkInfoToLogger(forkInfo: IScene.ForkInfo): ScalarKeyValueStats<String> {
        return when (forkInfo) {
            IScene.ForkInfo.BASE -> baseSceneLogger
            IScene.ForkInfo.CVL -> cvlSceneLogger
        }
    }

    private val cvlSpecsLogger = ScalarKeyValueStats<String>(RUN_ID, "CVL".toSDFeatureKey())

    override val runStartData: Set<SDFeature<*>>
        get() = listOf(startStamper).filterTo(mutableSetOf(), SDFeature<*>::hasData)

    override val runEndData: Set<SDFeature<*>>
        get() = listOf(startToEndTime, endStamper).filterTo(mutableSetOf(), SDFeature<*>::hasData)

    override val sceneData: Set<ScalarKeyValueStats<String>>
        get() = listOf(
            baseSceneLogger,
            cvlSceneLogger
        ).filterTo(mutableSetOf(), ScalarKeyValueStats<*>::hasData)

    override val cvlData: Set<ScalarKeyValueStats<String>>
        get() = listOf(cvlSpecsLogger).filterTo(mutableSetOf(), ScalarKeyValueStats<*>::hasData)

    override val allData: Set<SDFeature<*>>
        get() = runStartData.union(runEndData).union(sceneData).union(cvlData)

    override fun reportRunStart() = apply {
        startToEndTime.startMeasuringTimeOf(START_TO_END_TIME)
        startStamper.stampCurrentTime(START_TIMESTAMP)
    }

    override fun reportRunEnd() = apply {
        startToEndTime.endMeasuringTimeOf(START_TO_END_TIME)
        endStamper.stampCurrentTime(END_TIMESTAMP)
    }

    override fun reportScene(scene: IScene) = apply {

        scene.getContracts().forEach { contract -> //Map contract bytecode digest to contract name
            // if contract is synthetic, assume empty bytecode
            val bytecode = contract.bytecode?.bytes.orEmpty()
            sceneForkInfoToLogger(scene.forkInfo).addKeyValue(
                MessageDigest.getInstance("SHA-256").digest(bytecode.toByteArray()).toHexString(),
                contract.name
            )
        }
    }

    override fun reportCVL(cvlFile: String, cvlAst: CVLAst) = apply {
        cvlSpecsLogger.addKeyValue(cvlAst.hashCode().toString(16), cvlFile) //map CVL AST hash to CVL filename
    }
}
