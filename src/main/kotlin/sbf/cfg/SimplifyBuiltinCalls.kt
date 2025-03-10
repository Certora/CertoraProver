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
import sbf.callgraph.SolanaFunction
import sbf.disassembler.SbfRegister

/** Rename memory intrinsics (memset, memcpy, memmove, and memcmp) **/
fun simplifyMemoryIntrinsics(cfg: MutableSbfCFG) {
    fixSolMemcmp(cfg)
    renameCalls(cfg, ::renameMemoryIntrinsics)
}

/**
 *  Rename CVT special verification calls
 *  **Precondition**: the function names are demangled
 */
fun renameCVTCalls(cfg: MutableSbfCFG) = renameCalls(cfg, ::renameCVTCall)

/**
 * Replace:
 * ```
 *  call memcpy  with call sol_memcpy_
 *  call memmove with call sol_memmove_
 *  call memset  with call sol_memset_
 *  call memcmp  with call sol_memcmp_
 * ```
 **/
private fun renameMemoryIntrinsics(call: SbfInstruction.Call): SbfInstruction? {
    when (call.name) {
        "memcpy" -> {
            /** Replace `memcpy` with `sol_memcmpy_` **/
            return SolanaFunction.toCallInst(SolanaFunction.SOL_MEMCPY, call.metaData)
        }
        "memmove" -> {
            /** Replace `memmove` with `sol_memmove_` **/
            return SolanaFunction.toCallInst(SolanaFunction.SOL_MEMMOVE, call.metaData)
        }
        "memset" -> {
            /** Replace `memset` with `sol_memset_` **/
            return SolanaFunction.toCallInst(SolanaFunction.SOL_MEMSET, call.metaData)
        }
        "memcmp" -> {
            /** Replace `memcmp` with `sol_memcmp_` **/
            return SolanaFunction.toCallInst(SolanaFunction.SOL_MEMCMP, call.metaData)
        }
        else -> return null
    }
}
/**
 *  Uniform translation of `sol_memcmp_` and `memcmp`
 *
 * `sol_memcmp_` stores the result of the comparison in `*r4`.
 *  On the other hand, `memcmp` stores the result of the comparison in `r0`.
 * `memcmp` is a wrapper for `sol_memcmp_` that avoids calling the builtin function if the length is small.
 *  But semantically, they are equivalent.
 *
 * Thus, for the analysis is convenient to change one or the other so that both functions stores the result of the
 * comparison in the same register. We choose to store the result is stored in `r0` because all the analyses were
 * (__incorrectly__) already making that assumption.
 *
 * To fix the program so that our own (more convenient) semantics is compatible with the actual SBF semantics,
 * we need to perform the following intra-block transformation:
 *
 *
 * ```
 * call sol_memcmp_
 * ```
 *
 * into:
 *
 * ```
 * call sol_memcmp_
 * *(*u32)r4 := r0
 * ```
 *
 * Again, this is okay because all the analyses already stored the result in `r0` and we never execute the code.
 * Note that to change `memcmp` to store the result in `*r4` is not easy because we would need to make sure that
 * `r4` is not alive at the time `memcmp` is executed.
 *
 */
private fun fixSolMemcmp(cfg: MutableSbfCFG) {
    for (b in cfg.getMutableBlocks().values) {
        val insertionPoints = ArrayList<Int>()
        for ((i, inst) in b.getInstructions().withIndex()) {
            if (inst is SbfInstruction.Call && inst.name == SolanaFunction.SOL_MEMCMP.syscall.name) {
                insertionPoints.add(i)
            }
        }
        for ((addedInsts, i) in insertionPoints.withIndex()) {
            b.add((i+1)+addedInsts,
                    SbfInstruction.Mem(Deref(4, Value.Reg(SbfRegister.R4_ARG), 0),
                                    Value.Reg(SbfRegister.R0_RETURN_VALUE), false))
        }
    }
}


/** Replace in-place call instructions **/
private fun renameCalls(cfg: MutableSbfCFG, renameFunction: (SbfInstruction.Call) -> SbfInstruction?) {
    for (b in cfg.getMutableBlocks().values) {
        var i = 0
        while (i < b.numOfInstructions()) {
            val inst = b.getInstructions()[i]
            if (inst is SbfInstruction.Call) {
                inst.let {
                    val renamedInst = renameFunction(it)
                    if (renamedInst != null) {
                        b.replaceInstruction(i, renamedInst)
                    }
                }
            }
            i++
        }
    }
}


/**
 * **Precondition**: the function names are demangled.
 *
 * First, it removes all the namespaces to CVT calls.
 * Second, it replaces the original CVT calls for more convenient (but yet equivalent) calls.
 * More specifically, it replaces
 *
 * - `CVT_assume` with `Assume`
 * - `CVT_assert` with `Assert`
 * - `CVT_sanity` with `CVT_satisfy`
 * ```
 **/
private fun renameCVTCall(call: SbfInstruction.Call): SbfInstruction? {
    // Remove namespaces
    val fname = removeNamespacesIfCVTCall(call.name)
    val cvtFunction = CVTCore.from(fname)
    if (cvtFunction != null) {
        when (cvtFunction) {
            CVTCore.ASSUME -> {
                val cond = Condition(CondOp.EQ, Value.Reg(SbfRegister.R1_ARG), Value.Imm(1UL))
                return SbfInstruction.Assume(cond, call.metaData)
            }
            CVTCore.ASSERT -> {
                val cond = Condition(CondOp.NE, Value.Reg(SbfRegister.R1_ARG), Value.Imm(0UL))
                return SbfInstruction.Assert(cond, call.metaData)
            }
            else -> {
                /* handled below */
            }
        }
    }
    return if (fname != call.name) {
        call.copy(name = fname)
    } else {
        null
    }
}

@Suppress("ForbiddenMethodCall")
private fun removeNamespacesIfCVTCall(name: String): String {
    return if (name.contains("cvt") || name.contains("CVT")) {
        val sanitizedName = name.substringAfterLast("::", "")
        if (sanitizedName ==  "") {
            name
        } else {
            sanitizedName
        }
    } else {
        name
    }
}
