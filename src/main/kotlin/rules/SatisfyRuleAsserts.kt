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

package rules

import analysis.maybeNarrow
import datastructures.stdcollections.*
import rules.RuleSplitter.fixupAssertSnippetAnnotations
import utils.*
import vc.data.CoreTACProgram
import vc.data.TACCmd
import vc.data.TACMeta
import java.util.stream.Collectors

/**
 * Assuming [code] is a satisfy rule, rewrites every assert of it into an assume, except for the satisfies: the
 * asserts that carry a [TACMeta.SATISFY_ID] are what such a rule checks.
 *
 * This includes the asserts of the rule itself, which a satisfy rule may well contain: the CVLR vacuity check of the
 * Solana flow derives one that keeps them, so that it asks whether the program can be reached at all rather than
 * whether it holds.
 *
 * This is a flow where a rule as a whole either is or is not a satisfy rule.  A CVL rule mixes the two, and its
 * satisfies are checked one per subrule rather than all at once; see [RuleSplitter.generateSubRuleForSatisfy].
 */
fun assumeAssertsOfSatisfyRule(code: CoreTACProgram): CoreTACProgram {
    val asserts = code
        .parallelLtacStream()
        .mapNotNull { it.maybeNarrow<TACCmd.Simple.AssertCmd>() }
        .filter { TACMeta.SATISFY_ID !in it.cmd.meta }
        .collect(Collectors.toList())

    if (asserts.isEmpty()) {
        return code
    }

    return code.patching { p ->
        for ((ptr, cmd) in asserts) {
            p.replaceCommand(ptr, listOf(TACCmd.Simple.AssumeCmd(cmd.o, "rewrote assert: ${cmd.msg}", cmd.meta)))
        }

        fixupAssertSnippetAnnotations(code, p, asserts.map { it.cmd.o })
    }
}
