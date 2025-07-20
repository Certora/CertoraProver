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

import datastructures.stdcollections.*
import java.io.Serializable

sealed interface KeyValueStats<P: Serializable> : SDFeature<P>, Serializable {
    fun addKeyValue(key: String, value: P) = apply {
        /* statsdata keys must not contain '.' */
        val tag = key.replace(".", "_")
        extractedData.add(SDElement(tag, value))
    }
}

data class GeneralKeyValueStats<P : Serializable>(
    override val precedingKeys: List<SDFeatureKey> = listOf(),
    override val extractedData: MutableList<SDElement<P>> = mutableListOf(),
) : KeyValueStats<P> {
    constructor(vararg precedingKeys: SDFeatureKey) : this(precedingKeys = precedingKeys.toList())

    override val descriptor = SDDescriptor.IdDescriptor<P>()

    override fun fork(vararg moreKeys: SDFeatureKey): GeneralKeyValueStats<P> =
        copy(
            extractedData = extractedData.deepCopy(),
            precedingKeys = precedingKeys + moreKeys.toList()
        )
}

/**
 * A [SDFeature] similar to [GeneralKeyValueStats] except for [descriptor],
 * where it presents [extractedData] as a scalar value (i.e. [extractedData].first())
 * if [extractedData].size == 1
 **/
data class ScalarKeyValueStats<P: Serializable>(
    override val precedingKeys: List<SDFeatureKey> = listOf(),
    override val extractedData: MutableList<SDElement<P>> = mutableListOf(),
) :
    KeyValueStats<P> {
    constructor(vararg precedingKeys: SDFeatureKey) : this(precedingKeys = precedingKeys.toList())

    override val descriptor = SDDescriptor.ScalarDescriptor<P>()

    override fun fork(vararg moreKeys: SDFeatureKey): ScalarKeyValueStats<P> =
        copy(
            extractedData = extractedData.deepCopy(),
            precedingKeys = precedingKeys + moreKeys.toList()
        )
}

// key for all analysis success results
const val ANALYSIS_SUCCESS_STATS_KEY = "ANALYSIS"
const val INSTRUMENTATION_SUCCESS_STATS_KEY = "INSTRUMENTATION"
const val ANALYSIS_STORAGE_SUBKEY = "STORAGE"
const val ANALYSIS_TRANSIENT_STORAGE_SUBKEY = "TRANSIENT_STORAGE"
const val ANALYSIS_UNPACKING_SUBKEY = "UNPACKING"
const val ANALYSIS_POINTSTO_SUBKEY = "POINTSTO"
const val ANALYSIS_CALLGRAPH_SUBKEY = "CALLGRAPH"
const val ANALYSIS_INIT_SUBKEY = "INIT"
// wasm analysis keys
const val ANALYSIS_SCALARSET_INFERENCE_SUBKEY = "SCALARSET"
// key for all aggregated time results
const val TIME_STATS_KEY = "TIMES"
// key for call resolution (focus on sighash) statistics
const val CALL_RESOLUTION_KEY = "CALL_RESOLUTION"
const val CALL_RESOLUTION_SUCCESS_KEY = "CALL_RES_SUCCESS"
const val CALL_RESOLUTION_FAIL_KEY = "CALL_RES_FAIL"

/**
 * @param tag - the innermost name associated with the result
 * @param key - main key (usually ANALYSIS_SUCCESS_STATS_KEY)
 * @param subkey - analysis name key
 * @param result - if the analysis succeeded, set to true
 */
fun recordSuccess(tag: String, key: String, subkey: String, result: Boolean) {
    recordSuccess(result, tag, key, subkey)
}

/**
 * @param result - if the analysis succeeded, set to true
 * @param tag - the innermost name associated with the result
 * @param key - main key (usually ANALYSIS_SUCCESS_STATS_KEY)
 * @param subkeys - additional keys for identifying sub results for a particular analysis
 */
fun recordSuccess(result: Boolean, tag: String, key: String, vararg subkeys: String) {
    SDCollectorFactory.collector().collectFeature(
        ScalarKeyValueStats<Boolean>(SDFeatureKey(key), *subkeys.map(::SDFeatureKey).toTypedArray()).addKeyValue(
            tag,
            result
        )
    )
}

fun recordNumber(collector: SDCollector, result: Int, tag: String, key: String, vararg subkeys: String) {
    collector.collectFeature(
        ScalarKeyValueStats<Int>(SDFeatureKey(key), *subkeys.map(::SDFeatureKey).toTypedArray()).addKeyValue(
            tag,
            result
        )
    )
}

fun recordSuccessFailNumbers(successResult: Int, failResult: Int, successTag: String, failTag: String, key: String, vararg subkeys: String) {
    SDCollectorFactory.collector().let { collector ->
        recordNumber(collector, successResult, successTag, key, *subkeys)
        recordNumber(collector, failResult, failTag, key, *subkeys)
    }
}

/**
 * In case of a non-primitive (java.io.Serializable) class, override `toString` to produce `Json`.  For example:
 * ```
 * import kotlinx.serialization.Serializable
 * import java.io.Serializable as JavaSerializable
 * import kotlinx.serialization.json.Json
 * @Serializable
 * class A(val v: String, val i: Int) : JavaSerializable { override fun toString(): String = return Json.encodeToString(arrayOf(this)) }
 *
 * recordAny(A("a", 0), "key2", "key1")
 * ```
 * will produce
 * ```
 * { "key1": { "key2": [{"v":"a","i":0}] } }
 * ```
 * @param statistic a statistic to be logged.
 * @param tag the innermost name associated with the result
 * @param key main key (e.g., `"SMT"`)
 * @param subkeys additional keys for identifying sub results for a particular analysis
 */

fun <P: Serializable> SDCollector.recordAny(statistic: P, tag: String, key: String, vararg subkeys: String) {
    this.recordAny(statistic, tag, SDFeatureKey(key), *subkeys)
}

fun <P: Serializable> SDCollector.recordAny(statistic: P, tag: String, key: SDFeatureKey, vararg subkeys: String) {
    collectFeature(
        GeneralKeyValueStats<P>(key, *subkeys.map(::SDFeatureKey).toTypedArray()).addKeyValue(
            tag,
            statistic
        )
    )
}
