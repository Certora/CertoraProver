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

import com.certora.collect.*
import tac.*

@Deprecated("Do not use this object anymore - tags should be explicitly given")
object TACUtils {
    fun tagsFromList(l: List<TACCmd>, tags: TreapSet.Builder<TACSymbol.Var>) {
        l.forEach { c ->
            tagsFromCommand(c, tags)
        }
    }

    fun tagsFromCommand(c: TACCmd.Simple): TreapSet<TACSymbol.Var> {
        val tags = treapSetBuilderOf<TACSymbol.Var>()
        tagsFromCommand(c, tags)
        return tags.build()
    }

    fun tagsFromCommand(c: TACCmd, tags: TreapSet.Builder<TACSymbol.Var>) {
        c.getLhs()?.let {
            (it as? TACSymbol.Var)?.let {
                tags += it
            }
        }
        c.getFreeVarsOfRhs().forEach {
            tags += it
        }
        if (c is TACCmd.Simple.ByteLongCopy) { // TODO: can we update getFreeVarsOfRhs?
            tags += c.dstBase
        }
    }

    fun tagsFromList(l: List<TACCmd>): TreapSet<TACSymbol.Var> {
        val tags = treapSetBuilderOf<TACSymbol.Var>()
        tagsFromList(l, tags)
        return tags.build()
    }

    fun tagsFromBlocks(blocks: Map<NBId, List<TACCmd>>): TreapSet<TACSymbol.Var> {
        val tags = treapSetBuilderOf<TACSymbol.Var>()
        blocks.forEach {
            tagsFromList(it.value, tags)
        }
        return tags.build()
    }
}
