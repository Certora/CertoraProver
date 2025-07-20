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

package analysis.storage

import analysis.LTACCmd
import analysis.SimpleCmdsWithDecls
import analysis.split.StorageSplitter
import evm.EVM_BITWIDTH256
import evm.MAX_EVM_UINT256
import spec.cvlast.typedescriptors.VMSignedNumericValueTypeDescriptor
import spec.cvlast.typedescriptors.VMTypeDescriptor
import spec.cvlast.typedescriptors.VMUnsignedNumericValueTypeDescriptor
import utils.SignUtilities
import utils.SignUtilities.maxSignedValueOfBitwidth
import utils.SignUtilities.minSignedValueOfBitwidth
import utils.SignUtilities.to2sComplement
import utils.`impossible!`
import vc.data.*
import vc.data.TACCmd.Simple.AssigningCmd.AssignExpCmd
import vc.data.TACCmd.Simple.AssigningCmd.WordLoad
import vc.data.TACCmd.Simple.WordStore
import vc.data.TACMeta.BIT_WIDTH
import vc.data.TACMeta.STORAGE_TYPE
import vc.data.tacexprutil.ExprUnfolder.Companion.unfoldPlusOneCmd

/**
 * A convenience class for holding the information about a store operation after [StorageSplitter] changed some
 * of the stores to simple assignments.
 */
class StoreInfo(val lcmd: LTACCmd) {
    val cmd = lcmd.cmd

    init {
        check(isStoreCmd(lcmd)) { "${cmd.toStringNoMeta()} is not a storage store command." }
    }

    val width = base.meta[BIT_WIDTH] ?: EVM_BITWIDTH256

    val type get() = base.meta[STORAGE_TYPE]

    val isAssign get() = cmd is AssignExpCmd

    val base
        get() = when (cmd) {
            is AssignExpCmd -> cmd.lhs
            is WordStore -> cmd.base
            else -> `impossible!`
        }

    val value
        get() = when (cmd) {
            is AssignExpCmd -> (cmd.rhs as TACExpr.Sym).s
            is WordStore -> cmd.value
            else -> `impossible!`
        }

    val loc get() = (cmd as WordStore).loc

    companion object {
        fun isStoreCmd(lcmd: LTACCmd) =
            lcmd.cmd.let { cmd ->
                (cmd is WordStore && cmd.base.isStorageOrTransientStorage()) ||
                    (cmd is AssignExpCmd && cmd.lhs.isStorageOrTransientStorage())
            }

        fun storeInfoOrNull(lcmd: LTACCmd) =
            if (isStoreCmd(lcmd)) StoreInfo(lcmd) else null
    }
}

/**
 * A convenience class for holding the information about a load operation after [StorageSplitter] changed some
 * of the loads to simple assignments.
 */
class LoadInfo private constructor(val lcmd: LTACCmd) {
    val cmd = lcmd.cmd

    init {
        check(isLoadCmd(lcmd)) { "${cmd.toStringNoMeta()} is not a storage load command." }
    }

    val width = base.meta[BIT_WIDTH] ?: EVM_BITWIDTH256

    val type get() = base.meta[STORAGE_TYPE]

    val isAssign get() = cmd is AssignExpCmd

    val base
        get() = when (cmd) {
            is AssignExpCmd -> (cmd.rhs as TACExpr.Sym.Var).s
            is WordLoad -> cmd.base
            else -> `impossible!`
        }

    val loc get() = (cmd as WordLoad).loc

    val lhs
        get() = when (cmd) {
            is AssignExpCmd -> cmd.lhs
            is WordLoad -> cmd.lhs
            else -> `impossible!`
        }

    companion object {
        fun isLoadCmd(lcmd: LTACCmd) =
            lcmd.cmd.let { cmd ->
                (cmd is WordLoad && cmd.base.isStorageOrTransientStorage()) ||
                    (cmd is AssignExpCmd && cmd.rhs is TACExpr.Sym.Var && cmd.rhs.s.isStorageOrTransientStorage())
            }

        fun loadInfoOrNull(lcmd: LTACCmd) =
            if (isLoadCmd(lcmd)) LoadInfo(lcmd) else null
    }
}


/** A class for generating cmds enforcing bound constraints for signed and unsigned int types according to width */
class WidthConstraints(private val boundee: TACSymbol.Var) {

    private val txf = TACExprFactUntyped

    fun byType(t: VMTypeDescriptor) =
        when (t) {
            is VMSignedNumericValueTypeDescriptor -> signed(t.bitwidth)
            is VMUnsignedNumericValueTypeDescriptor -> unsigned(t.bitwidth)
            else -> null
        }

    fun unsigned(width: Int): SimpleCmdsWithDecls {
        if (width == EVM_BITWIDTH256) {
            return SimpleCmdsWithDecls()
        }

        return unfoldPlusOneCmd(
            tempVarPrefix = "unsignedBound",
            expr = txf {
                LAnd(
                    Le(boundee.asSym(), SignUtilities.maxUnsignedValueOfBitwidth(width).asTACExpr),
                    Ge(boundee.asSym(), 0.asTACExpr)
                )
            },
            last = { TACCmd.Simple.AssumeCmd(it.s, "unsignedBound") }
        )
    }


    fun signed(width: Int): SimpleCmdsWithDecls {
        if (width == EVM_BITWIDTH256) {
            return SimpleCmdsWithDecls()
        }
        val boundee = boundee.asSym()
        val expr = txf {
            LOr(
                LAnd(
                    Le(boundee, maxSignedValueOfBitwidth(width).asTACExpr),
                    Ge(boundee, 0.asTACExpr)
                ),
                LAnd(
                    Ge(
                        boundee,
                        minSignedValueOfBitwidth(width).to2sComplement().asTACExpr
                    ),
                    Le(boundee, MAX_EVM_UINT256.asTACExpr)
                ),
            )
        }
//        return unfoldPlusOneCmd(
//            tempVarPrefix = "signedBound",
//            expr = expr,
//            last = { TACCmd.Simple.AssumeCmd(it.s) }
//        )
        return SimpleCmdsWithDecls(
            TACCmd.Simple.AssumeExpCmd(expr)
        )
    }

}


