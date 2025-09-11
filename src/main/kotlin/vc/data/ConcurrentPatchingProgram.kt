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

import analysis.CmdPointer
import analysis.SimpleCmdsWithDecls
import analysis.TACProgramPrinter
import datastructures.stdcollections.*
import tac.Tag
import utils.*
import utils.Color.Companion.black
import utils.Color.Companion.greenBg
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A simple wrapper for [SimplePatchingProgram] which can work concurrently.
 *
 * Calling [toCode] should be done only after all other functions are surely done, and can be called only once.
 */
class ConcurrentPatchingProgram(private val code: CoreTACProgram) {

    private val newVars = ConcurrentHashMap.newKeySet<TACSymbol.Var>()
    private val replacedVars = ConcurrentHashMap<TACSymbol.Var, TACSymbol.Var>()

    private val replacements = ConcurrentHashMap<CmdPointer, List<TACCmd.Simple>>()
    private val befores = ConcurrentHashMap<CmdPointer, List<TACCmd.Simple>>()
    private val afters = ConcurrentHashMap<CmdPointer, List<TACCmd.Simple>>()

    private val done = AtomicBoolean(false)

    private val graph = code.analysisCache.graph

    private fun checkNotDone() = check(!done.get()) {
        "Changing patching program after toCode has been called"
    }

    /** Declare new variables through this function, otherwise they'll not be registered in the generated program */
    fun newTempVar(name: String, tag: Tag) =
        TACKeyword.TMP(tag, "!$name").toUnique("!").also {
            newVars.add(it)
            checkNotDone()
        }

    fun addVar(v: TACSymbol.Var) {
        newVars.add(v)
        checkNotDone()
    }

    fun addVars(vararg v: TACSymbol.Var) {
        newVars.addAll(v)
        checkNotDone()
    }

    fun addVarDecls(vars: Iterable<TACSymbol.Var>) {
        newVars += vars
        checkNotDone()
    }

    fun replaceVarDefinition(oldVar : TACSymbol.Var, newVar : TACSymbol.Var) {
        val previous = replacedVars.put(oldVar, newVar)
        check(previous == null || previous == newVar) {
            "Replacing the same variable $oldVar with different new variables : $previous, $newVar"
        }
        checkNotDone()
    }

    fun replace(where: CmdPointer, newCmds: List<TACCmd.Simple>) {
        val oldCmds = replacements.put(where, newCmds)
        check(oldCmds == null) {
            "Replacing the same command twice at $where. Once with:\n   $oldCmds\nAnd again with:\n   $newCmds"
        }
        checkNotDone()
    }

    fun replace(where: CmdPointer, newCmd: TACCmd.Simple) {
        replace(where, listOf(newCmd))
    }

    fun delete(where: CmdPointer) {
        replace(where, emptyList())
    }

    fun replace(where: CmdPointer, cmdsAndVars: SimpleCmdsWithDecls) {
        replace(where, cmdsAndVars.cmds)
        addVarDecls(cmdsAndVars.varDecls)
    }

    private fun checkNotJump(where: CmdPointer) {
        graph.elab(where).cmd.let { cmd ->
            require(cmd !is TACCmd.Simple.JumpCmd && cmd !is TACCmd.Simple.JumpiCmd) {
                "Can't add commands after a jump cmd, at $where : $cmd"
            }
        }
    }

    /** This can accumulate such statememts */
    fun insertAfter(where: CmdPointer, newCmds: List<TACCmd.Simple>) {
        checkNotJump(where)
        afters.compute(where) { _, old ->
            old.orEmpty() + newCmds
        }
    }

    /** This can accumulate such statememts */
    fun insertAfter(where: CmdPointer, cmdsAndVars: SimpleCmdsWithDecls) {
        checkNotJump(where)
        afters.compute(where) { _, old ->
            old.orEmpty() + cmdsAndVars.cmds
        }
        addVarDecls(cmdsAndVars.varDecls)
    }

    /** Adds the new commands before those already accumulated in [appendBefore] and [prependBefore]` calls. */
    fun prependBefore(where: CmdPointer, newCmds: List<TACCmd.Simple>) {
        befores.compute(where) { _, old ->
            newCmds + old.orEmpty()
        }
    }

    /** Adds the new commands after those already accumulated in [appendBefore] and [prependBefore] calls. */
    fun appendBefore(where: CmdPointer, newCmds: List<TACCmd.Simple>) {
        befores.compute(where) { _, old ->
            old.orEmpty() + newCmds
        }
    }

    fun toCode() =
        toPatchingProgram().toCode(code)


    private fun finalReplacement(p: CmdPointer) =
        runIf(befores.containsKey(p) || replacements.containsKey(p) || afters.containsKey(p)) {
            befores[p].orEmpty() +
                (replacements[p] ?: listOf(graph.toCommand(p))) +
                afters[p].orEmpty()
        }

    fun toPatchingProgram(): SimplePatchingProgram {
        check(!done.getAndSet(true)) {
            "Calling toCode twice"
        }
        val patcher = code.toPatchingProgram()
        patcher.addVarDecls(newVars)
        replacedVars.forEachEntry { (old, new) ->
            patcher.replaceVarDecl(old, new)
        }
        for (ptr in replacements.keys + befores.keys + afters.keys) {
            patcher.replaceCommand(ptr, finalReplacement(ptr)!!)
        }
        return patcher
    }


    @Suppress("unused")
    fun debugPrinter() =
        TACProgramPrinter.standard().extraLines { (ptr, cmd) ->
            when (val newCmds = finalReplacement(ptr)) {
                null -> emptyList()
                emptyList<TACCmd>() -> listOf("DELETED".greenBg.black)
                else -> {
                    if (newCmds.first() == cmd) {
                        listOf("<original cmd> +".greenBg.black) + newCmds.drop(1).map { it.toStringNoMeta().greenBg.black }
                    } else {
                        newCmds.map { it.toStringNoMeta().greenBg.black }
                    }
                }
            }
        }
}
