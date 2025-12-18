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

package verifier.splits

import analysis.CmdPointer
import analysis.opt.intervals.IntervalsRewriter
import datastructures.stdcollections.*
import tac.NBId
import utils.*
import vc.data.CoreTACProgram
import vc.data.TACCmd
import vc.data.TACExpr
import verifier.splits.SplitAddress.Block.DontPass
import verifier.splits.SplitAddress.Block.MustPass


/**
 * The identifier of a split within a split tree.
 */
@KSerializable
sealed interface SplitAddress : HasKSerializable {
    val parent: SplitAddress?
    val isRoot get() = parent == null
    val depth: Int get() = (parent?.depth ?: -1) + 1
    val asList : List<String>
    fun name() = asList.joinToString("_")

    fun fullInfo(): String

    @KSerializable
    data object Root : SplitAddress {
        override val parent = null
        override val asList = emptyList<String>()
        override fun fullInfo() = "ROOT"
        override fun toString() = name()
    }

    @KSerializable
    sealed interface Block : SplitAddress {
        val pivot: NBId
        fun sibling() : Block
        override fun fullInfo() = "${name()}[$pivot]"

        @KSerializable
        data class MustPass(override val parent: SplitAddress, override val pivot: NBId) : Block {
            override fun sibling() = DontPass(parent, pivot)
            override val asList = parent.asList + "0"
            override fun toString() = name()
        }

        @KSerializable
        data class DontPass(override val parent: SplitAddress, override val pivot: NBId) : Block {
            override fun sibling() = MustPass(parent, pivot)
            override val asList = parent.asList + "1"
            override fun toString() = name()
        }
    }

    @KSerializable
    data class Rerun(override val parent: SplitAddress) : SplitAddress {
        override val asList = parent.asList + "R"
        override fun toString() = name()
        override fun fullInfo() = "${name()}[rerun]"
    }

    @KSerializable
    data class Assumption(
        override val parent: SplitAddress,
        val assumption: AddedAssumption,
        val numericalCode : Int
    ) : SplitAddress {

        /**
         * Signifies the addition of an `assume [e]` after the [afterAssertNum]'s assert.
         * The reason the placement is according to the assert count in the block, is that the program where
         * the new assumption is generated may be a reduced program, and not the original one. However, we count
         * on the fact that the optimizations we use (specifically [IntervalsRewriter]) never removes asserts, though
         * it may make them trivial. Also, and importantly, the specific spot between two consecutive asserts that an
         * assume is placed, does not matter - it'll have the same effect.
         */
        @KSerializable
        data class AddedAssumption(val block: NBId, val afterAssertNum : Int, val e: TACExpr, val isUnderApprox : Boolean) {
            constructor(code : CoreTACProgram, ptr : CmdPointer, e : TACExpr, isUnderApprox: Boolean) :
                this(
                    ptr.block,
                    code.code[ptr.block]!!.subList(0, ptr.pos).count {
                        it.cmd is TACCmd.Simple.AssertCmd
                    },
                    e,
                    isUnderApprox
                )
            override fun toString() = "Assume@$block[$afterAssertNum] $e"
        }

        // the +2 is for not interfering with the 0 and 1 splits of `Block`.
        override val asList = parent.asList + "A${numericalCode}"
        override fun fullInfo() = "${name()}[$assumption]"
        override fun toString() = name()
    }

    private fun ancestorsSeq() : Sequence<SplitAddress> =
        parent?.ancestorsSeq().orEmpty() + sequenceOf(this)

    fun mustPass() = ancestorsSeq().filterIsInstance<MustPass>().mapToSet { it.pivot }
    fun dontPass() = ancestorsSeq().filterIsInstance<DontPass>().mapToSet { it.pivot }
    fun assumptions() = ancestorsSeq().filterIsInstance<Assumption>().mapToSet { it.assumption }

    companion object {
        // reverse lexicographical comparison of two splits. So if splits are ordered by this, first one
        // will be 0000, and second 1000, which are very different splits. That's good for searching (probably)
        fun compareRevLexical(a: SplitAddress, b: SplitAddress) = run {
            require(a.depth == b.depth) // leaving this decision out of here.
            (a.asList zip b.asList)
                .map { (i1, i2) -> i1.compareTo(i2) }
                .lastOrNull { it != 0 } ?: 0
        }
    }
}

