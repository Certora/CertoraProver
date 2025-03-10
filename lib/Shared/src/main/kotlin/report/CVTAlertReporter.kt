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

package report

import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import log.*
import java.io.Closeable
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import utils.*

/**
 * An enum class containing the possible severity levels for alerts.
 */
enum class CVTAlertSeverity {
    INFO, WARNING, ERROR
}

/**
 * An enum class containing the possible alert types.
 * [externalName] is the name we output to JSON. as of now this is just a conversion to Title Case
 * [defaultUrl] is a documentation URL to use when a [CVTAlertInstance] has no user-defined URL
 */
enum class CVTAlertType(
    val externalName: String,
    val defaultUrl: CheckedUrl,
) {
    /** General alerts about the prover, its workflow, failures, etc  */
    GENERAL(
        externalName = "General",
        defaultUrl = CheckedUrl.LATEST_DOCS,
    ),
    /** Alerts coming from "CVL-land" -- syntax errors, no rules, etc. */
    CVL(
        externalName = "CVL",
        defaultUrl = CheckedUrl.CVL_OVERVIEW,
    ),
    /** Everything about how our method summaries were applied, or not. */
    SUMMARIZATION(
        externalName = "Summarization",
        defaultUrl = CheckedUrl.SUMMARIES,
    ),
    /** Problems in the "static analysis" part of the tool -- all the optimizations and tac transformations we do.. */
    ANALYSIS(
        externalName = "Analysis",
        defaultUrl = CheckedUrl.PROVER_TECHNIQUES,
    ),
    /** CVT ran out of resources -- time or memory, typically */
    OUT_OF_RESOURCES(
        externalName = "Out of Resources",
        defaultUrl = CheckedUrl.OUT_OF_RESOURCES,
    ),
    /** Alerts related to caching -- cache being used, etc. */
    CACHE(
        externalName = "Cache",
        defaultUrl = CheckedUrl.LATEST_DOCS, // no cache-related docs right now, but there should be
    ),
    /** Alerts relaed to diagnosability - call trace, rule reports and related parts */
    DIAGNOSABILITY(
        externalName = "Diagnosability",
        CheckedUrl.LATEST_DOCS,
    ),
    ;
}

/**
 * A class representing an instance of an alert- It contains the alert's:
 * [severity]- representing how severe the alert is (from [CVTAlertSeverity]),
 * [type]- the type of alert it is (from [CVTAlertType]),
 * [message]- the message containing the information and details regarding the alert,
 * [hint]- an optional additional message, for suggesting a possible fix to the issue,
 * [url]- an optional link to additional reference. if null, a sensible default will be derived from [type]
 * and the alert's location ([jumpToDefinition]).
 */
data class CVTAlertInstance(
    val type: CVTAlertType,
    val severity: CVTAlertSeverity,
    val jumpToDefinition: TreeViewLocation?,
    val message: String,
    val hint: String?,
    val url: CheckedUrl?,
) {

    private val timeStamp: String =
        DateTimeFormatter.ofPattern("dd/MM/uuuu-HH:mm:ss:SSS").withZone(ZoneId.systemDefault()).format(
            Instant.now().truncatedTo(
                ChronoUnit.MILLIS
            )
        )

    private fun urlOrDefault() = this.url ?: type.defaultUrl

    val jsonRepBuilder: JsonObjectBuilder.() -> Unit = {
        put(key = "type", type.externalName)
        put(key = "severity", severity.toString())
        jumpToDefinition?.let { put(key = "jumpToDefinition", it) }
        put(key = "message", message)
        hint?.let { put(key = "hint", it) }
        put(key = "url", urlOrDefault().toString())
        put(key = "timeStamp", timeStamp)
    }
}

internal val logger = Logger(LoggerTypes.ALWAYS)

/**
 * The main class representing the alert reporter. There are two instances of this class: [DisabledCVTAlertReporter]
 * and [EnabledCVTAlertReporter], where only the latter actually writes the alerts to a file. Both produce log and
 * regression output.
 * Whether alerts reporting is enabled follow [ArtifactManagerFactory.isEnabled] (see [Companion.invoke]). The alert
 * reporter can only be accessed via its factory functions. For simply sending an alert use [Companion.reportAlert],
 * for everything else use [Companion.invoke].
 * The alert reporter should be initialized via [init] before usage and closed via [close] when completed. As we assume
 * exclusive ownership of the file [EnabledCVTAlertReporter.fileName], only a single instance should ever exist.
 */
sealed class CVTAlertReporter: Closeable {

    /** Check whether this alert reporter has already been initialized */
    open fun isInit(): Boolean = true
    /** Initialize this alert reporter */
    open fun init() {}
    /**
     * Report an alert. This implementation merely logs the alert and emits a regression message.
     * If [EnabledCVTAlertReporter] is used, it is additionally put into the alert file.
     */
    open fun reportAlert(
        type: CVTAlertType,
        severity: CVTAlertSeverity,
        jumpToDefinition: TreeViewLocation?,
        message: String,
        hint: String?,
        url: CheckedUrl? = null,
    ) {
        when (severity) {
            CVTAlertSeverity.INFO -> logger.info { message }
            CVTAlertSeverity.WARNING -> Logger.alwaysWarn(message)
            CVTAlertSeverity.ERROR -> Logger.alwaysError(message)
        }
        Logger.regression { "Report alert: ${message}" }
        Logger.regression { "hint: ${hint}" }
    }
    /** Close this alert report */
    override fun close() {}

    /** Used when artifacts are disabled */
    private data object DisabledCVTAlertReporter : CVTAlertReporter()

    /**
     * Used when artifacts are enabled. All reports are converted to json and dumped into [fileName].
     */
    private data object EnabledCVTAlertReporter : CVTAlertReporter() {
        private var fileWriter: AlertReportFileWriter? = null

        private val fileName get() = "alertReport.json"

        private var firstAlert = true

        override fun isInit() = synchronized(this) { fileWriter != null }

        override fun init() {
            synchronized(this) {
                require(fileWriter == null) { "Alert file writer is already open, was object already initialized?" }

                ArtifactManagerFactory().registerArtifact(fileName, StaticArtifactLocation.Reports)
                val filePath = ArtifactManagerFactory().getRegisteredArtifactPath(fileName)

                val file = ArtifactFileUtils.getActualFile(filePath, overwrite = false, append = false)
                fileWriter = AlertReportFileWriter(file, append = false)
            }
        }

        /**
         * First calls the base version [CVTAlertReporter.reportAlert] which logs the alert. Then converts the alert to
         * to json and puts it into the alert file where it can be picked up by the web report.
         */
        override fun reportAlert(
            type: CVTAlertType,
            severity: CVTAlertSeverity,
            jumpToDefinition: TreeViewLocation?,
            message: String,
            hint: String?,
            url: CheckedUrl?,
        ) {
            super.reportAlert(type, severity, jumpToDefinition, message, hint, url)

            val writer = synchronized(this) { fileWriter }
            if (writer == null) {
                logger.warn { "reportAlerts.json not yet opened, could not write alert" }
                return
            }

            val alert = CVTAlertInstance(type, severity, jumpToDefinition, message, hint, url)
            val jsonObject = buildJsonObject(alert.jsonRepBuilder)

            /**
             * All string formatting is already done at this point, to minimize time spent inside this [synchronized] block.
             * Note that the logic of whether to add the leading comma must also be within the [synchronized] block in
             * order to avoid a case where thread A reads [firstAlert] as true so it just sets it to false, and
             * then, before it starts writing to the file, thread B comes in, sees that [firstAlert] is false, adds the
             * preceding comma, and gets to write first to the file. In this case we would end up with the malformed string:
             * `[,{thread B alert}{thread A alert}]`
             */
            synchronized(this) {
                val jsonToAppend = buildString {
                    if (firstAlert) {
                        firstAlert = false
                    } else {
                        append(',')
                    }
                    append(jsonObject)
                }
                writer.write(jsonToAppend)
            }
        }

        override fun close() {
            synchronized(this) {
                fileWriter?.close()
            }
        }
    }

    companion object {
        /** Retrieve the proper alert reporter */
        operator fun invoke(): CVTAlertReporter {
            return if (ArtifactManagerFactory.isEnabled()) {
                EnabledCVTAlertReporter
            } else {
                DisabledCVTAlertReporter
            }
        }

        /**
         * Simply forward to [CVTAlertReporter.reportAlert] on the instance returned by [invoke].
         * In any case, this sends the alert to logging, so please don't do it yourself as well.
         */
        fun reportAlert(
            type: CVTAlertType,
            severity: CVTAlertSeverity,
            jumpToDefinition: TreeViewLocation?,
            message: String,
            hint: String?,
            url: CheckedUrl? = null,
        ) = Companion().reportAlert(type, severity, jumpToDefinition, message, hint, url)
    }
}
