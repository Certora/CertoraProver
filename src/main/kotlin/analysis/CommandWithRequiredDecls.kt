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

package analysis

import spec.cvlast.typedescriptors.ISimpleOutput
import vc.data.TACCmd
import vc.data.TACSymbol

data class CommandWithRequiredDecls<out T: TACCmd>(
    val cmds: List<T>,
    val varDecls: Set<TACSymbol.Var>
) : ISimpleOutput {

    constructor() : this(emptyList(), emptySet())

    constructor(_cmds: List<T>) : this(_cmds, emptySet())

    constructor(_cmds: List<T>, _decl: Collection<TACSymbol.Var>) : this(_cmds, _decl.toSet())

    constructor(_cmd: T) : this(listOf(_cmd))

    constructor(_cmd: T, _decl: TACSymbol.Var) : this(listOf(_cmd), setOf(_decl))

    constructor(_cmds: List<T>, vararg decls: TACSymbol.Var) : this(_cmds, decls.toSet())

    constructor(_cmd: T, vararg decls: TACSymbol.Var) : this(listOf(_cmd), decls.toSet())

    constructor(_cmd: T, _decls: Collection<TACSymbol.Var>) : this(listOf(_cmd), _decls.toSet())

    fun merge(vararg _otherDecls: TACSymbol.Var) = this.copy(varDecls = this.varDecls.plus(_otherDecls))

    @Suppress("ImportStdCollections")
    fun merge(vararg _otherDecls: TACSymbol) = this.copy(varDecls = this.varDecls + _otherDecls.filterIsInstance<TACSymbol.Var>())

    companion object {
        fun <T: TACCmd> List<T>.withDecls(vararg syms: TACSymbol): CommandWithRequiredDecls<T> {
            val decls = syms.asSequence().filterIsInstance<TACSymbol.Var>().toSet()
            return CommandWithRequiredDecls(this, decls)
        }

        fun <T: TACCmd> List<T>.withDecls(syms: Collection<TACSymbol>, vararg moreSyms: TACSymbol): CommandWithRequiredDecls<T> {
            val decls = (syms + moreSyms.asSequence()).filterIsInstance<TACSymbol.Var>().toSet()
            return CommandWithRequiredDecls(this, decls)
        }

        fun <T: TACCmd> mergeMany(cmdObjects: List<CommandWithRequiredDecls<T>>): CommandWithRequiredDecls<T> {
            val cmds = mutableListOf<T>()
            val decls = mutableSetOf<TACSymbol.Var>()
            cmdObjects.forEach { (_cmds, _decls) ->
                cmds.addAll(_cmds)
                decls.addAll(_decls)
            }
            return CommandWithRequiredDecls(cmds, decls)
        }

        fun <T: TACCmd> mergeMany(vararg command: CommandWithRequiredDecls<T>) = mergeMany(command.asList())

        /**
         * I tried covariance in the class type parameter but since the [merge] function takes parameters of type T,
         * T is not always covariant (or something like that)
         */
        fun <T: TACCmd.Spec> CommandWithRequiredDecls<T>.asSpec(): CommandWithRequiredDecls<TACCmd.Spec> = CommandWithRequiredDecls(
            cmds,
            varDecls
        )

        operator fun <T: TACCmd.Spec> invoke(cmds: List<T>, decls: Collection<TACSymbol>) = CommandWithRequiredDecls(cmds, decls.filterIsInstance<TACSymbol.Var>())
    }
}

class MutableCommandWithRequiredDecls<T: TACCmd> {
    private val cmds: MutableList<T> = mutableListOf()
    private val varDecls: MutableSet<TACSymbol.Var> = mutableSetOf()

    fun isEmpty() = cmds.isEmpty() && varDecls.isEmpty()

    fun extend(other: CommandWithRequiredDecls<T>) {
        this.cmds.addAll(other.cmds)
        this.varDecls.addAll(other.varDecls)
    }

    fun extend(v: List<T>, vararg decls: TACSymbol.Var) {
        this.cmds.addAll(v)
        decls.forEach(varDecls::add)
    }

    fun extend(v: Iterable<TACSymbol>) = v.filterIsInstanceTo(varDecls)

    fun extend(v: List<T>, vararg decls: TACSymbol) {
        this.cmds.addAll(v)
        decls.filterIsInstanceTo(varDecls)
    }

    fun extend(v: T, vararg decls: TACSymbol) {
        this.cmds.add(v)
        decls.filterIsInstanceTo(varDecls)
    }

    fun extend(v: T, vararg decls: TACSymbol.Var) {
        this.cmds.add(v)
        decls.forEach(varDecls::add)
    }

    fun extend(vararg decls: TACSymbol) {
        decls.filterIsInstanceTo(varDecls)
    }

    fun extend(v: T) {
        this.cmds.add(v)
    }

    fun extend(v: List<T>) {
        this.cmds.addAll(v)
    }

    fun extend(v: List<T>, decls: Collection<TACSymbol.Var>) {
        this.cmds.addAll(v)
        varDecls.addAll(decls)
    }

    fun toCommandWithRequiredDecls(): CommandWithRequiredDecls<T> {
        return CommandWithRequiredDecls(
            cmds, varDecls
        )
    }
}

/** @return A command that performs `this` followed by `other` */
fun <U : TACCmd, T: U> CommandWithRequiredDecls<T>.merge(_otherCmd: U) = CommandWithRequiredDecls(
    (this.cmds as List<U>) + _otherCmd,
    this.varDecls
)

/** @return A command that performs `this` followed by `other` */
fun <U: TACCmd, T: U> CommandWithRequiredDecls<T>.merge(_otherCmds: List<U>) = CommandWithRequiredDecls(
    this.cmds + _otherCmds,
    this.varDecls
)

/** @return A command that performs `this` followed by `other` */
fun <U: TACCmd, T: U> CommandWithRequiredDecls<T>.merge(_other: CommandWithRequiredDecls<U>) =
    CommandWithRequiredDecls(cmds = this.cmds + _other.cmds, varDecls = this.varDecls + _other.varDecls)

/** @return A command that performs `this` followed by `other`, with additional variable declarations */
fun <U: TACCmd, T: U> CommandWithRequiredDecls<T>.merge(_otherCmd: U, vararg _otherDecls: TACSymbol.Var) =
    CommandWithRequiredDecls(this.cmds + _otherCmd, this.varDecls.plus(_otherDecls))

typealias SimpleCmdsWithDecls = CommandWithRequiredDecls<TACCmd.Simple>
