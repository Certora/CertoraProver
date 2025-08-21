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

package sbf

import config.ConfigType
import org.apache.commons.cli.Option

/** Static object that contains all the Solana CLI options **/
object SolanaConfig {
    val InlineFilePath = object : ConfigType.StringCmdLine(
        "cvt_inlining.txt",
        Option(
            "solanaInlining",
            true,
            "Path to file used to decide which functions should be inlined or not. [default:\"cvt_inlining.txt\"]"
        )
    ){}

    val SummariesFilePath = object : ConfigType.StringCmdLine(
        "cvt_summaries.txt",
        Option(
            "solanaSummaries",
            true,
            "Path to file that contains function summaries. [default:\"cvt_summaries.txt\"]"
        )
    ){}

    // If `-ruleSanityChecks basic` then only clvr vacuity checks are enabled.
    // If `-ruleSanityChecks advanced` then cvlr + tac vacuity checks are enabled.
    // This option is useful when it is false so that we only run tac vacuity checks.
    val EnableCvlrVacuity = object : ConfigType.BooleanCmdLine(
        true,
        Option(
            "solanaCvlrVacuity",
            true,
            "Enable clvr vacuity checks. [default: true]"
        )
    ) {}

    val EnableCpiAnalysis = object : ConfigType.BooleanCmdLine(
        // This feature is experimental for the moment, so we turn it off by default
        false,
        Option(
            "solanaCpiAnalysis",
            true,
            "Enable CPI calls analysis. [default: false]"
        )
    ) {}


    // Disassembling options
    val StackFrameSize = object : ConfigType.IntCmdLine(
        4096,
        Option(
            "solanaStackSize", true,
            "Size of the stack frame for SBF programs. [default: 4096]"
        )
    ) {
        override fun check(newValue: Int): Boolean {
            return (newValue >= 4096 && newValue % 4096 == 0)
        }
    }


    val SkipCallRegInst = object : ConfigType.BooleanCmdLine(
        false,
        Option(
            "solanaSkipCallRegInst",
            true,
            "Skip callx instructions. If enabled the analysis might be unsound. [default: false]"
        )
    ) {}

    // PTA options
    val UsePTA = object : ConfigType.BooleanCmdLine(
        true,
        Option(
            "solanaUsePTA",
            true,
            "Enable pointer analysis. If disabled the analysis might be unsound. [default: true]"
        )
    ) {}

    val OptimisticPTAJoin = object : ConfigType.BooleanCmdLine(
        false,
        Option(
            "solanaOptimisticJoin",
            true,
            "Optimistic join in the pointer analysis in presence of Rust dangling pointers. [default: false]"
        )
    ) {}

    val OptimisticPTAJoinWithStackPtr = object : ConfigType.BooleanCmdLine(
        false,
        Option(
            "solanaOptimisticJoinWithStackPtr",
            true,
            "At a joint point, if a field might point to either a stack pointer or non-stack pointer then it chooses the stack pointer. " +
                      "The flag ${OptimisticPTAJoin.name} must be enabled [default: false]"
        )
    ) {}

    val OptimisticPTAOverlaps = object : ConfigType.BooleanCmdLine(
        false,
        Option(
            "solanaOptimisticOverlaps",
            true,
            "Optimistically assume that no sharing due to overlapping can occur during pointer analysis. [default: false]"
        )
    ) {}

    val SlicerBackPropagateThroughAsserts = object : ConfigType.BooleanCmdLine(
        false,
        Option(
            "solanaSlicerThroughAsserts",
            true,
            "Optimistically backward propagate necessary preconditions through asserts. " +
                       "Enabling this flag might be unsound if a failing assertion lies on a dead path marked by the backward analysis. [default: false]"
        )
    ) {}

    val OptimisticDealloc = object : ConfigType.BooleanCmdLine(
        false,
        Option(
            "solanaOptimisticDealloc",
            true,
            "Optimistically dealloc memory without proving the pointer analysis that the deallocated memory does not represent multiple concrete memory objects [default: false]"
        )
    ) {}

    val OptimisticMemcpyPromotion = object : ConfigType.BooleanCmdLine(
        false,
        Option(
            "solanaOptimisticMemcpyPromotion",
            true,
            "Optimistically promote stores to memcpy if the analysis cannot prove that source and destination do not overlap. [default: false]"
        )
    ) {}

    val OptimisticMemcmp = object : ConfigType.BooleanCmdLine(
        false,
        Option(
            "solanaOptimisticMemcmp",
            true,
            "Optimistically assume that the memory regions to be compared are word-aligned. [default: false]"
        )
    ) {}

    val OptimisticNoMemmove = object : ConfigType.BooleanCmdLine(
        false,
        Option(
            "solanaOptimisticNoMemmove",
            true,
            "Optimistically replace memmove with memcpy [default: false]"
        )
    ) {}

    val AggressiveGlobalDetection = object : ConfigType.BooleanCmdLine(
        false,
        Option(
            "solanaAggressiveGlobalDetection",
            true,
            "Interpret a read access to an address x in the global segment (data.ro) as a global variable at x. " +
            "Caveat: this option treats x as an independent global variable even if in reality it is part of some aggregate global object (e.g., when x is an element of some global array). [default: false]"
        )
    ) {}

    val ScalarMaxVals = object : ConfigType.IntCmdLine(
        20,
        Option(
            "solanaScalarMaxVals", true,
            "Maximum number of values tracked by the scalar domain for registers and stack. [default: 20]"
        )
    ) {
        override fun check(newValue: Int) = newValue >= 1
    }

    val UseScalarPredicateDomain = object : ConfigType.BooleanCmdLine(
        true,
        Option(
            "solanaUseScalarPredicateDomain", true,
            "If true then the Scalar+Predicate domain is used by the Memory domain, else the Scalar domain. [default: true]."
        )
    ) {}

    val EnablePTAPseudoCanonicalize = object : ConfigType.BooleanCmdLine(
        true,
        Option(
            "solanaEnablePTAPseudoCanonicalize",
            true,
            "This option does not affect soundness but it affects precision/performance of PTA analysis [default: true]"
        )
    ) {}

    val SanityChecks = object : ConfigType.BooleanCmdLine(
        false,
        Option(
            "solanaSanityChecks",
            true,
            "Enable expensive sanity checks in the pointer analysis. [default: false]"
        )
    ) {}

    val PrintDevMsg = object : ConfigType.BooleanCmdLine(
        false,
        Option(
            "solanaPrintDevMsg",
            true,
            "If an error happens, then it prints extra information for developers. [default: false]"
        )
    ) {}

    // CFG optimizations
    val SlicerIter = object : ConfigType.IntCmdLine(
        2,
        Option(
            "solanaSlicerIter", true,
            "Number of times the slicer+pta optimizations loop is executed. [default: 2]"
        )
    ) {}

    val EnableRemovalOfCFGDiamonds = object : ConfigType.BooleanCmdLine(
        false,
        Option(
            "solanaRemoveCFGDiamonds",
            true,
            "Remove CFG diamonds by inserting select instructions [default: false]"
        )
    ) {}

    val DebugSlicer = object : ConfigType.BooleanCmdLine(
        false,
        Option(
            "solanaDebugSlicer",
            true,
            "Print debugging messages from the slicer and scalar domain [default: false]"
        )
    ) {}

    // TAC options
    val AddMemLayoutAssumptions = object : ConfigType.BooleanCmdLine(
        true,
        Option(
            "solanaAddMemLayoutAssumptions",
            true,
            "Add extra assumptions in TAC to restrict valid ranges of addresses. [default: true]"
        )
    ) {}

    val CvtNondetAccountInfo = object : ConfigType.BooleanCmdLine(
        false,
        Option(
            "solanaCvtNondetAccountInfo",
            true,
            "This is a TAC encoding option. " +
                "If enabled then apply TAC summary on CVT_nondet_account_info. " +
                "If disabled then the function is a non-op [default: false]." +
                "For Anchor-based projects, this flag should be true."
        )
    ) {}

    val WordSize = object : ConfigType.IntCmdLine(
        8,
        Option(
            "solanaWordSize", true,
            "Fixed word size in bytes (1, 2, 4, or 8) used for TAC encoding of memcmp instructions. [default: 8]"
        )
    ) {
        override fun check(newValue: Int): Boolean {
            return newValue == 1 || newValue == 2 || newValue == 4 || newValue == 8
        }
    }

    val TACOptLevel = object : ConfigType.IntCmdLine(
        0,
        Option(
            "solanaTACOptimize",
            true,
            "Perform TAC-to-TAC optimizations still as part of the Solana front-end. " +
                       "[default: 0 (no optimization)]"
        )
    ) {
        override fun check(newValue: Int) = newValue >= 0
    }

    val UseLegacyTACOpt = object : ConfigType.BooleanCmdLine(
        true,
        Option(
            "solanaUseLegacyTACOptimize",
            true,
            "Use old set of TAC optimizations [default: true]"
        )
    ) {}

    val UseTACMathInt = object : ConfigType.BooleanCmdLine(
        false,
        Option(
            "solanaTACMathInt",
            true,
            "Use mathematical integers in cases where it is sound (e.g., __multi3, __udivi3, etc). " +
                      "Disable this option if -useBitVectorTheory true. [default: false]"
        )
    ) {}

    val TACPromoteOverflow = object : ConfigType.BooleanCmdLine(
        true,
        Option(
            "solanaTACPromoteOverflow",
            true,
            "Detect overflow checks (e.g., checked_add and saturating_add) and promote them to convenient overflow checks in TAC. [default: true]"
        )
    ) {}

    val TACHeapAllocSize = object : ConfigType.IntCmdLine(
        512,
        Option(
            "solanaTACHeapAllocSize", true,
            "Default size of an unknown heap allocation. This size is used by the TAC symbolic allocator. [default: 512]"
        )
    ) {}

    val TACExternalAllocSize = object : ConfigType.IntCmdLine(
        64,
        Option(
            "solanaTACExternalAllocSize", true,
            "Default size of an unknown external allocation. This size is used by the TAC symbolic allocator. [default: 64]"
        )
    ) {}

    val TACMinSizeForCalltrace = object : ConfigType.IntCmdLine(
        300,
        Option(
            "solanaMinSizeForCalltrace", true,
            "Minimum size of an inlined function to be mentioned in the calltrace. " +
                       "This size is estimated by the number of SBF instructions before any frontend optimization. " +
                       "Thus, the actual size after all TAC optimizations will be usually smaller than this size. " +
                       "[default: 300]"
        )
    ) {
        override fun check(newValue: Int) = newValue >= 0
    }

    val TACMaxUnfoldedMemset = object : ConfigType.IntCmdLine(
        256,
        Option(
            "solanaTACMaxUnfoldedMemset",
            true,
            "If length of memset is less or equal than this number then memset is translated as multiple byte map stores. [default: 256]"
        )
    ) {}

    val TACMaxGlobalInit = object : ConfigType.IntCmdLine(
        64,
        Option(
            "solanaTACMaxGlobalInit",
            true,
            "The initialization of a global variable is part of the TAC encoding if the number of bytes being initialized is less or equal that this number. [default: 64]"
        )
    ) {}

    // Printing options

    val PrintResultsToStdOut = object : ConfigType.BooleanCmdLine(
        false,
        Option(
            "solanaPrintResults",
            false,
            "Print analyzed program and invariants to standard output. [default: false]"
        )
    ) {}

    val PrintResultsToDot = object : ConfigType.BooleanCmdLine(
        false,
        Option(
            "solanaPrintResultsToDot",
            false,
            "Print analyzed program and points-to graphs to dot format. [default: false]"
        )
    ) {}

    val PrintOriginalToStdOut = object : ConfigType.BooleanCmdLine(
        false,
        Option(
            "solanaPrintOriginal",
            false,
            "Print all SBF CFGs before inlining to standard output. [default: false]"
        )
    ) {}

    val PrintAnalyzedToStdOut = object : ConfigType.BooleanCmdLine(
        false,
        Option(
            "solanaPrintAnalyzed",
            false,
            "Print the actual analyzed program (after inlining and slicing) to standard output. [default: false]"
        )
    ) {}

    val PrintAnalyzedToDot = object : ConfigType.BooleanCmdLine(
        false,
        Option(
            "solanaPrintAnalyzedToDot",
            false,
            "Print the actual analyzed program (after inlining and slicing) to dot format. [default: false]"
        )
    ) {}

    val PrintTACToStdOut = object : ConfigType.BooleanCmdLine(
        false,
        Option(
            "solanaPrintTAC",
            false,
            "Print the TAC program (before loop unrolling) to standard output. [default: false]"
        )
    ) {}
}
