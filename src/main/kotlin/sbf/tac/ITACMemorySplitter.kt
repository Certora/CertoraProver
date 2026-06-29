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

package sbf.tac

import sbf.cfg.LocatedSbfInstruction
import sbf.disassembler.SbfRegister
import sbf.domains.MemSummaryArgumentType
import sbf.domains.PTAOffset
import vc.data.TACExpr

/**
 * Disambiguate a memory access by mapping the memory access to a symbolic variable
 **/
interface TACMemSplitter {
    /**
     *  @param locInst is a memory instruction (store or load)
     *  @return  a (symbolic) TAC variable that represents the memory location being de-referenced.
     *  If inst is not reachable then it returns null.
     **/
    fun getTACMemory(locInst: LocatedSbfInstruction): LoadOrStoreInfo?

    /**
     *  @param locInst call to `sol_memcpy_`, `memcpy_zext`, `memcpy_trunc`, `sol_memmove_`, `sol_memcmp_`, or `sol_memset_`
     *  @return all TAC variables that represent the source and destination memory locations.
     *  If inst is not reachable then it returns null.
     */
    fun getTACMemoryFromMemIntrinsic(locInst: LocatedSbfInstruction): MemInstrinsicsInfo?

    /**
     * @param locInst call to an internal function.
     * @return a list of memory locations that are modified by the summary.
     * If inst is not reachable then it returns null.
     */
    fun getTACMemoryFromSummary(locInst: LocatedSbfInstruction): List<SummaryArgInfo>?


    /** Represent a memory load or store instructions in TAC **/
    sealed class LoadOrStoreInfo

    /**
     *  Represent a memory load or store from/to the stack in TAC.
     *
     *  @param variables maps stack offsets (relative to `r10`) to TAC variables. These offsets are always negative.
     *  @param reconstructedValues maps each stack offset to the value last written there (from the scalar domain), populated only
     *  when the pointer domain reconstructed the cell via split or merge. A top value means the write is unknown and the load result is havoced.
     *  @param locationsToHavoc is the scalars or byte map indexes that must be havoced by the memory operation.
     **/
    data class StackLoadOrStoreInfo(
        val variables: Map<PTAOffset, TACByteStackVariable>,
        val reconstructedValues: Map<PTAOffset, PTAMemSplitter.ReconstructedIntegerValue>,
        val locationsToHavoc: HavocMemLocations): LoadOrStoreInfo()
    /**
     *  A non-stack ByteMap operand: either a single concrete ByteMap variable,
     *  or a dispatch over several maps guarded by address-range predicates.
     **/
    sealed class ByteMapTarget {
        /** A single ByteMap variable. */
        data class Base(val v: TACByteMapVariable): ByteMapTarget()

        /**
         * The access dispatches over multiple region ByteMaps. [branches] is tried in order;
         * if none of the guards holds, [default] is used.
         */
        data class Ite(
            val branches: List<Branch>,
        ): ByteMapTarget() {
            init { require(branches.isNotEmpty()) { "ByteMapTarget.Ite needs at least one branch" } }

            /**
             * One branch of the dispatch: when [guard] holds, the access targets [map].
             * Callers must order branches so that the union of all guards covers the address
             * range the access can land in: the last branch is treated as the fallthrough
             * (its guard is dropped when building the ite expression for loads).
             */
            data class Branch(val guard: TACExpr, val map: TACByteMapVariable)
        }
    }

    /**
     *  Represent a memory load or store from/to non-stack in TAC.
     *
     *  @param variable: TAC bytemap operand that models the de-referenced memory location.
     *  @param locationsToHavoc is the scalars or byte map indexes that must be havoced by the memory operation.
     **/
    data class NonStackLoadOrStoreInfo(
        val variable: ByteMapTarget,
        val locationsToHavoc: HavocMemLocations): LoadOrStoreInfo()

    /**
     * What `*(arg.reg + arg.offset)` resolves to in TAC: either a stack scalar
     * or a non-stack ByteMap target (which itself may be a [ByteMapTarget.Base] or
     * an [ByteMapTarget.Ite] dispatch).
     */
    sealed class SummaryArgVariable {
        data class Stack(val v: TACByteStackVariable): SummaryArgVariable()
        data class NonStack(val target: ByteMapTarget): SummaryArgVariable()
    }

    /**
     * Represent that (*[reg]+[offset]) is mapped to [variable] and has type [type]
     */
    data class SummaryArgInfo(
        val reg: SbfRegister,
        val offset: PTAOffset,
        val width: Byte,
        val allocatedSpace: ULong,
        val type: MemSummaryArgumentType,
        val variable: SummaryArgVariable)

    /**
     * Represent `sol_memcpy_`, `memcpy_zext`, `memcpy_trunc`, `sol_memmove_`, `sol_memcmp_`, or `sol_memset_` in TAC.
     **/
    sealed class MemInstrinsicsInfo

    /** class to model `sol_memcpy`  **/
    sealed class MemTransferInfo: MemInstrinsicsInfo()
    /** class to model `memcpy_zext`  **/
    sealed class MemcpyZExt: MemInstrinsicsInfo()
    /** class to model `sol_memcmp` **/
    sealed class MemcmpInfo: MemInstrinsicsInfo()
    /** class to model `sol_memset` **/
    sealed class MemsetInfo: MemInstrinsicsInfo()
    /** class to model sol_memmove which is not supported at the moment **/
    object NotImplMemInstInfo : MemInstrinsicsInfo()


    /** class to keep track which TAC variables should be havoc **/
    sealed class HavocMemLocations
    /**
     *  This class contains the list of stack variables that should be havoced in case they are used later.
     *
     *  However, there is no need to havoc stack locations because the pointer analysis would throw an exception if
     *  any of these stack locations corresponding to these variables are actually accessed.
     *
     *  For now, we still havoc them but the TAC optimizations should remove them since they are dead.
     */
    data class HavocScalars(val vars: Map<PTAOffset, List<TACByteStackVariable>>): HavocMemLocations()
    data class HavocMapBytes(val vars: List<PTAOffset>): HavocMemLocations()
    data class StackSlice(val lb: Long, val ub: Long)

    /**
     *  Transfer memory from stack to stack
     *
     *  @param source is a map from stack offsets to slices copied from the stack.
     *  @param destination is a map from stack offsets to slices being overwritten.
     *  @param length is the number of bytes being copied.
     **/
    class StackMemTransferInfo(
        val source: Map<PTAOffset, StackSlice>,
        val destination: Map<PTAOffset, StackSlice>,
        val length: Long,
        val locationsToHavoc: HavocScalars): MemTransferInfo()

    /**
     * Transfer memory from non-stack to non-stack
     **/
    class NonStackMemTransferInfo(
        val source: ByteMapTarget,
        val destination: ByteMapTarget,
        val length: Long?,
        val locationsToHavoc: HavocMapBytes): MemTransferInfo()

    /**
     * Transfer memory from non-stack to stack, or vice-versa.
     */
    class MixedRegionsMemTransferInfo (
        val byteMap: ByteMapTarget,
        val stack: Map<PTAOffset, StackSlice>,
        /// To know the direction of the transfer. If true then from non-stack to stack
        val isDestStack: Boolean,
        val length: Long,
        val locationsToHavoc: HavocMemLocations
    ): MemTransferInfo() {
        init {
            if (isDestStack) {
                check(locationsToHavoc is HavocScalars) {
                    "precondition of MixedRegionsMemTransferInfo failed (1)"
                }
            } else {
                check(locationsToHavoc is HavocMapBytes) {
                    "precondition of MixedRegionsMemTransferInfo failed (2)"
                }
            }
        }
    }

    /** Class to represent `sol_memcpy` instructions that cannot be translated to TAC **/
    object UnsupportedMemTransferInfo: MemTransferInfo()


    /** Class to model `memcpy_zext` **/
    data class SupportedMemcpyZExtInfo(val memcpy: MemTransferInfo, val memset: MemsetInfo): MemcpyZExt()

    /** Class to represent `memcpy_zext` instructions that cannot be translated to TAC **/
    object UnsupportedMemcpyZExtInfo: MemcpyZExt()

    /**
     * This is a wrapper that conveniently groups together `sol_memcmp_` operands as TAC variables.
     * Both memcmp operands point to the stack.
     *
     *  @param op1 is a list of destination TAC variables.
     *  @param op2 is a list of source TAC variables.
     *  @param op1Range is a pair of lowest and highest offsets being compared on the stack.
     *  @param op2Range is a pair of lowest and highest offsets being compared on the stack.
     *  @param length is the number of bytes to be compared.
     *  @param wordSize is the size of a word in bytes.
     *
     *  The number of words is [length]/[wordSize].
     */
    class StackMemCmpInfo (
        val op1: List<TACByteStackVariable>,
        val op2: List<TACByteStackVariable>,
        val op1Range: StackSlice,  // for generating TAC metadata
        val op2Range: StackSlice,  // for generating TAC metadata
        val length: Long,
        val wordSize: Byte
    ): MemcmpInfo()  {
        init {
            // source and destination must have the same number of variables
            if (op1.size != op2.size) {
                throw TACTranslationError("creating an invalid StackMemCmpInfo object")
            }
        }
    }

    /**
     * This is a wrapper that conveniently groups together `sol_memcmp_` operands as TAC variables.
     * Both memcmp operands point to non-stack memory.
     *
     *  @param length is the number of bytes to be compared.
     *  @param wordSize is the size of a word in bytes.
     *
     *  The number of words is [length]/[wordSize].
     */
    class NonStackMemCmpInfo (
        val op1: ByteMapTarget,
        val op2: ByteMapTarget,
        val length: Long,
        val wordSize: Byte
    ): MemcmpInfo()

    /**
     *  One memcmp operand is on the stack but the other is not.
     *
     *  Operands are normalized so that [scalars] is always a list of scalars and [byteMap] is a byte map.
     *  [scalarsReg] ([byteMapReg]) tells which register (r1 or r2) is [scalars] ([byteMap]).
     */
    class MixedRegionsMemCmpInfo (
        val scalars: List<TACByteStackVariable>,
        val byteMap: ByteMapTarget,
        val scalarsReg: SbfRegister,
        val byteMapReg: SbfRegister,
        val stackOpRange: StackSlice?,  // for generating TAC metadata
        val length: Long,
        val wordSize: Byte
    ): MemcmpInfo()  {
        init {
            if (scalarsReg == byteMapReg) {
                throw TACTranslationError("creating an invalid MixedRegionsTACMemCmpInfo object (1)")
            }
            if (!(scalarsReg >= SbfRegister.R1 && scalarsReg <= SbfRegister.R2) ) {
                throw TACTranslationError("creating an invalid MixedRegionsTACMemCmpInfo object (2)")
            }
            if (!(byteMapReg >= SbfRegister.R1 && byteMapReg <= SbfRegister.R2) ) {
                throw TACTranslationError("creating an invalid MixedRegionsTACMemCmpInfo object (3)")
            }
        }
    }

    /** Class to represent sol_memcmp instructions that cannot be translated to TAC **/
    object UnsupportedMemCmpInfo: MemcmpInfo()

    /** Class to represent a memset on the stack **/
    class StackZeroMemsetInfo(val stackOpRange: StackSlice, val length: Long): MemsetInfo()

    /**
     *  Class to represent a memset on a non-stack memory region.
     *
     *  @param offset byte offset relative to R1: writes go to `r1 + offset, ..., r1 + offset + length - 1`.
     *  Defaults to 0 (regular memset). Used by `memcpy_zext`'s trailing zero-fill, where the fill starts
     *  at `r1 + i` for the copied length `i`.
     */
    class NonStackMemsetInfo(
        val byteMap: ByteMapTarget,
        val value: Long,
        val length: Long,
        val offset: Long = 0,
    ): MemsetInfo()

    /** Class to represent memset instructions that cannot be translated to TAC **/
    object UnsupportedMemsetInfo: MemsetInfo()
}


