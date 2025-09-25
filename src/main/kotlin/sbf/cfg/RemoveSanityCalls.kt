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

package sbf.cfg

import sbf.callgraph.CVTCore

/**
 * If [isVacuityRule] is true then all calls to `CVT_sanity` are replaced with `CVT_satisfy`.
 * Otherwise, the calls to `CVT_sanity` are removed.
 *
 * Note that except for tests, calls to `CVT_sanity` are expected to be inserted only by cvlr library.
 */

fun removeSanityCalls(cfg: MutableSbfCFG, isVacuityRule: Boolean) {
    for (b in cfg.getMutableBlocks().values) {
        var removedInst = 0
        val locInsts = b.getLocatedInstructions()
        for (locInst in locInsts) {
            val inst = locInst.inst
            if (inst.isSanity()) {
                if (isVacuityRule) {
                    b.replaceInstruction(
                        locInst.pos,
                        SbfInstruction.Call(CVTCore.SATISFY.function.name, metaData = inst.metaData)
                    )
                } else {
                    b.removeAt(locInst.pos - removedInst)
                    removedInst++
                }
            }
        }
    }
}
