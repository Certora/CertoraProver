// SPDX-License-Identifier: MIT
pragma solidity 0.8.21;

// Repro for CERT-10119.
// `absorb()` reads `branchData2.minimaTickSign / minimaTickAbs` from storage and
// decodes a signed int via a ternary. The nested if/else dispatches on the sign:
// for negative `minimaTick`, the IF-arm runs and `absorb()` returns true.
// The accompanying CVL rule asserts this trivially-true property.
//
// `StoragePathPruner` (driven by `StorageAnalysis`) incorrectly marks the IF-arm
// as unreachable and rewrites the Jumpi to always take the ELSE-arm, so the rule
// is reported Violated.
contract C {
    struct BranchData {
        bool    minimaTickSign;
        uint24  minimaTickAbs;
    }
    BranchData public branchData2;
    bool public topTickSign;

    function _getNextTick() internal returns (int) { return 0; }

    function absorb() public payable returns (bool) {
        int nextTick_ = _getNextTick();
        uint256 mtAbs_ = uint256(branchData2.minimaTickAbs);
        int256  minimaTick = branchData2.minimaTickSign ? int(mtAbs_) : -int(mtAbs_);

        bool ret = false;
        if (nextTick_ < minimaTick) {
            if (minimaTick < 0) {
                topTickSign = false;
                ret = true;
            } else {
                topTickSign = true;
                ret = false;
            }
        }
        return ret;
    }
}
