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

package spec.cvlast.transformer

import spec.cvlast.CVLCmd
import spec.cvlast.CVLExp
import spec.cvlast.CVLExp.HavocTarget.Companion.downcastToHavocTarget
import utils.CollectingResult
import utils.CollectingResult.Companion.bind
import utils.CollectingResult.Companion.flatten
import utils.CollectingResult.Companion.lift
import utils.CollectingResult.Companion.transpose
import utils.ErrorCollector.Companion.collectingErrors

// TODO: CERT-2401
// This (and other MonadicTransformers) could be auto-generated.  This would have a few benefits:
//   - reduce and simplify the codebase, increasing consistency
//   - adapt to changes in the AST; adding new types or new fields would automatically update the transformer
//   - maybe reduce memory consumption (by applying only-copy-on-change transformation)
// See `mike/transformers-generate` for a sketch of an implementation


/**
 * A [CVLCmdTransformer] encapsulates a pass over a [CVLCmd] using the visitor pattern.
 * There is a method corresponding to each type of command; this method should return a copy of the passed command
 * with the appropriate transformations applied.
 *
 * The default implementations recursively visit all children of the provided command, and update the command with the
 * transformed children.
 *
 * @param E the error type for the returned [CollectingResult]s
 * @param expTransformer is used to transform the nested expressions in the command
 */
open class CVLCmdTransformer<E>(
    val expTransformer: CVLExpTransformer<E>
) {

    open fun cmdList(list: List<CVLCmd>) = list.map { cmd(it) }

    open fun cmd(cmd: CVLCmd): CollectingResult<CVLCmd, E> = when (cmd) {
        is CVLCmd.Simple -> simple(cmd)
        is CVLCmd.Composite -> composite(cmd)
    }

    open fun simple(cmd: CVLCmd.Simple): CollectingResult<CVLCmd, E> = when (cmd) {
        is CVLCmd.Simple.Assert -> assertCmd(cmd)
        is CVLCmd.Simple.Satisfy -> satisfyCmd(cmd)
        is CVLCmd.Simple.AssumeCmd.Assume -> assumeCmd(cmd)
        is CVLCmd.Simple.AssumeCmd.AssumeInvariant -> assumeInv(cmd)
        is CVLCmd.Simple.Declaration -> decl(cmd)
        is CVLCmd.Simple.Definition -> def(cmd)
        is CVLCmd.Simple.Exp -> expCmd(cmd)
        is CVLCmd.Simple.Apply -> applyCmd(cmd)
        is CVLCmd.Simple.Havoc -> havoc(cmd)
        is CVLCmd.Simple.ResetStorage -> resetStorage(cmd)
        is CVLCmd.Simple.ResetTransientStorage -> resetTransientStorage(cmd)
        is CVLCmd.Simple.Return -> returnCmd(cmd)
        is CVLCmd.Simple.Revert -> revert(cmd)
        is CVLCmd.Simple.Label.Start -> labelStartCmd(cmd)
        is CVLCmd.Simple.Label.End -> labelEndCmd(cmd)
        is CVLCmd.Simple.Nop -> nop(cmd)
    }

    open fun composite(cmd: CVLCmd.Composite): CollectingResult<CVLCmd, E> = when (cmd) {
        is CVLCmd.Composite.Block -> blockCmd(cmd)
        is CVLCmd.Composite.If -> ifCmd(cmd)
    }

    open fun labelStartCmd(cmd: CVLCmd.Simple.Label.Start): CollectingResult<CVLCmd, E> =
        cmd.lift()

    open fun labelEndCmd(cmd: CVLCmd.Simple.Label.End): CollectingResult<CVLCmd, E> =
        cmd.lift()

    open fun assertCmd(cmd: CVLCmd.Simple.Assert): CollectingResult<CVLCmd, E> =
            expTransformer.expr(cmd.exp).bind { exp ->
                cmd.copy(range = cmd.range, exp = exp, description = cmd.description).lift()
            }

    open fun satisfyCmd(cmd: CVLCmd.Simple.Satisfy): CollectingResult<CVLCmd, E> =
            expTransformer.expr(cmd.exp).bind { exp ->
                cmd.copy(range = cmd.range, exp = exp, description = cmd.description).lift()
            }

    open fun assumeCmd(cmd: CVLCmd.Simple.AssumeCmd.Assume): CollectingResult<CVLCmd, E> =
            expTransformer.expr(cmd.exp).bind { exp ->
                cmd.copy(range = cmd.range, exp = exp).lift()
            }

    open fun assumeInv(cmd: CVLCmd.Simple.AssumeCmd.AssumeInvariant): CollectingResult<CVLCmd, E> =
            cmd.params.map { param -> expTransformer.expr(param) }.flatten().bind { params ->
                cmd.copy(range = cmd.range, id = cmd.id, params = params).lift()
            }

    open fun decl(cmd: CVLCmd.Simple.Declaration): CollectingResult<CVLCmd, E> = cmd.lift()

    open fun def(cmd: CVLCmd.Simple.Definition): CollectingResult<CVLCmd, E> =
            cmd.idL.map { lhs -> expTransformer.lhs(lhs) }.flatten().bind(expTransformer.expr(cmd.exp)) { idL, exp ->
                cmd.copy(range = cmd.range, type = cmd.type, idL = idL, exp = exp).lift()
            }

    open fun expCmd(cmd: CVLCmd.Simple.Exp): CollectingResult<CVLCmd, E> =
            expTransformer.expr(cmd.exp).bind { exp ->
                cmd.copy(range = cmd.range, exp = exp).lift()
            }

    open fun applyCmd(cmd: CVLCmd.Simple.Apply): CollectingResult<CVLCmd, E> =
            expTransformer.expr(cmd.exp).bind { exp ->
                cmd.copy(range = cmd.range, exp = exp as? CVLExp.ApplicationExp
                        ?: error("transformer must transform an ApplyExp to an ApplyExp (got $exp)") ).lift()
            }

    open fun havoc(cmd: CVLCmd.Simple.Havoc): CollectingResult<CVLCmd, E> = collectingErrors {
        val targets = cmd
            .targets
            .map { target -> expTransformer.expr(target.asExp).downcastToHavocTarget() }
            .flatten()

        val assumingExp = cmd.assumingExp?.let(expTransformer::expr).transpose()

        map(targets, assumingExp) { newTargets, newAssumingExp ->
            cmd.copy(targets = newTargets, assumingExp = newAssumingExp)
        }
    }

    open fun resetStorage(cmd: CVLCmd.Simple.ResetStorage): CollectingResult<CVLCmd, E> =
            expTransformer.expr(cmd.exp).bind { exp ->
                cmd.copy(range = cmd.range, exp = exp).lift()
            }

    open fun resetTransientStorage(cmd: CVLCmd.Simple.ResetTransientStorage): CollectingResult<CVLCmd, E> =
            expTransformer.expr(cmd.exp).bind { exp ->
                cmd.copy(range = cmd.range, exp = exp).lift()
            }

    open fun returnCmd(cmd: CVLCmd.Simple.Return): CollectingResult<CVLCmd, E> =
            (cmd.exps.map { exp -> expTransformer.expr(exp) }.flatten() ).bind { exp ->
                cmd.copy(range = cmd.range, exps = exp).lift()
            }

    @Suppress("NAME_SHADOWING")
    open fun blockCmd(cmd: CVLCmd.Composite.Block): CollectingResult<CVLCmd, E> =
            cmd.block.map { cmd -> cmd(cmd) }.flatten().bind { block ->
                cmd.copy(range = cmd.range, block = block).lift()
            }

    open fun ifCmd(cmd: CVLCmd.Composite.If): CollectingResult<CVLCmd, E> =
            expTransformer.expr(cmd.cond).bind(cmd(cmd.thenCmd), cmd(cmd.elseCmd)) { cond, thenCmd, elseCmd ->
                cmd.copy(range = cmd.range, cond = cond, thenCmd = thenCmd, elseCmd = elseCmd).lift()
            }

    open fun nop(cmd: CVLCmd.Simple.Nop): CollectingResult<CVLCmd, E> = cmd.lift()

    open fun revert(cmd: CVLCmd.Simple.Revert): CollectingResult<CVLCmd, E> = cmd.lift()
}
