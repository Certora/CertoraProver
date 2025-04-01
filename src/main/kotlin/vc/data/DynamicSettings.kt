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

package vc.data

/**
 * This class holds command line arguments that can be changed during one run.
 *
 * In some cases, we want to issue checks with different settings. This means we can not rely on the global [Config]
 * object for these settings, as it is essentially immutable. The [DynamicSettings] holds a subset of [Config],
 * including all options that we might want to change. These options should never be accessed directly via [Config], but
 * only via this [DynamicSettings] class.
 *
 * Instances of [DynamicSettings] are created in (or close to) the entry point and initialized from the values in the
 * [Config]. From there, the settings are handed down through our pipeline as necessary.
 */

import analysis.TACBlock
import analysis.icfg.Inliner
import com.certora.collect.*
import config.DestructiveOptimizationsModeEnum
import config.Config
import datastructures.stdcollections.*
import tac.MetaKey
import utils.uncheckedAs
import java.io.Serializable
import java.util.WeakHashMap
import kotlin.reflect.KProperty

/** Simple utility to get the root block */
internal val CoreTACProgram.rootBlock: TACBlock
    get() = analysisCache.graph.elab(entryBlockId)

/**
 * Retrieves the command that sets the meta info [k] on a [CoreTACProgram]. Assumes the command lives in the root block
 * and that the command is unique. Returns null if no such command exists.
 */
internal fun <T: Serializable> CoreTACProgram.getMetaCommand(k: MetaKey<T>) =
    if(!isEmptyCode()) {
        rootBlock.commands
            .singleOrNull { it.cmd is TACCmd.Simple.AnnotationCmd && it.cmd.annot.k == k }
    } else {
        null
    }

/**
 * Retrieves the meta value set for [k] on a [CoreTACProgram] based on [getMetaCommand]. Returns null if the info is
 * not set.
 */
internal fun <T: Serializable> CoreTACProgram.getMetaValue(k: MetaKey<T>) =
    (getMetaCommand(k)?.cmd as? TACCmd.Simple.AnnotationCmd)?.annot?.v?.uncheckedAs<T>()

/** Sets meta info [k] to [v] and returns the new [CoreTACProgram]. */
internal fun <@Treapable T: Serializable> CoreTACProgram.withMetaValue(k: MetaKey<T>, v: T): CoreTACProgram =
    patching { patcher ->
        val newcmd = TACCmd.Simple.AnnotationCmd(k, v)
        val cmd = getMetaCommand(k)
        if (cmd != null) {
            patcher.replaceCommand(cmd.ptr, listOf(newcmd))
        } else {
            patcher.addBefore(rootBlock.commands.first().ptr, listOf(newcmd))
        }
    }

/**
 * Utility class to implement a lazy property on [CoreTACProgram] that caches the result of [getMetaValue] for some
 * specific meta key. If [getMetaValue] returns null, [default] is used instead to provide a default value.
 */
internal class LazyDefaultDelegate <T : Serializable>(
    private val meta: MetaKey<T>,
    private val default: () -> T,
) {
    // We only have a single instance of this delegate. We thus explicitly map programs to their value.
    private val cache = WeakHashMap<CoreTACProgram, T>()
    operator fun getValue(thisRef: CoreTACProgram, property: KProperty<*>): T =
        synchronized(cache) {
            // read from cache, or fill the cache based on [getMetaValue] or [default]
            cache.getOrPut(thisRef) { thisRef.getMetaValue(meta) ?: default() }
        }
}

val DYNSET_DESTRUCTIVEOPTIMIZATIONS = MetaKey<Boolean>("dynamicsettings.destructiveoptimizations")

@OptIn(Config.DestructiveOptimizationsOption::class)
val CoreTACProgram.destructiveOptimizations: Boolean by LazyDefaultDelegate(DYNSET_DESTRUCTIVEOPTIMIZATIONS) { Config.DestructiveOptimizationsMode.get() == DestructiveOptimizationsModeEnum.ENABLE }
fun CoreTACProgram.withDestructiveOptimizations(value: Boolean) = withMetaValue(DYNSET_DESTRUCTIVEOPTIMIZATIONS, value)


val CoreTACProgram.stateExtensions: Inliner.ExtendedStateVars by LazyDefaultDelegate(Inliner.ExtendedStateVars.META_KEY) {
    Inliner.ExtendedStateVars(mapOf())
}
fun CoreTACProgram.withStateExtensions(value: Inliner.ExtendedStateVars) = withMetaValue(Inliner.ExtendedStateVars.META_KEY, value)

/**
 * Marks whether a [CoreTACProgram] should be optimized using cone of influence optimizations or not.
 *
 * Useful to set to `false` if the progem being optimized is only a partial program (so may not end with an assert yet).
 */
val DYNSET_COI_OPTIMIZATIONS = MetaKey<Boolean>("dynamicsettings.coi.optimizations")
val CoreTACProgram.coiOptimizations: Boolean by LazyDefaultDelegate(DYNSET_COI_OPTIMIZATIONS) { true }
fun CoreTACProgram.withCoiOptimizations(value: Boolean) = withMetaValue(DYNSET_COI_OPTIMIZATIONS, value)
