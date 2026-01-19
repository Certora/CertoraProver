/*
 *     The Certora Prover
 *     Copyright (C) 2025  Certora Ltd.
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, version 3 of the License.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY, without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR a PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package sbf.support

import sbf.sbfLogger

/** Run [block] and print its running time **/
inline fun <T> timeIt(
    prefix: String,
    msg: String,
    block: () -> T
): T = timeItImpl(prefix, msg, block)

/** Run [block] and print its running time **/
inline fun <T> timeIt(
    msg: String,
    block: () -> T
): T = timeItImpl(null, msg, block)

inline fun <T> timeItImpl(
    prefix: String?,
    msg: String, block: () -> T
): T {

    @Suppress("name_shadowing")
    val prefix = prefix?.let { "[$it] " }.orEmpty()
    sbfLogger.info { "${prefix}Started $msg" }

    val start = System.currentTimeMillis()
    val result = block()
    val duration = System.currentTimeMillis() - start

    val timeStr = when {
        duration >= 1000 -> "${duration / 1000}s"
        else -> "${duration}ms"
    }

    sbfLogger.info { "${prefix}Finished $msg in $timeStr" }
    return result
}
