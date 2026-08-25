// SPDX-License-Identifier: MIT
pragma solidity 0.8.21;

// All three functions use raw evm opcodes, where the edge cases are observable:
// `div(x, x)` is 0 at x = 0, `mod(1, n)` is 0 for n in {0, 1}, and `exp(0, e)` is 1 at e = 0.
// The simplifier used to fold these to the constants 1, 1 and 0 respectively.
contract C {
    function selfDiv(uint256 x) external pure returns (uint256 r) {
        assembly { r := div(x, x) }
    }

    function oneMod(uint256 n) external pure returns (uint256 r) {
        assembly { r := mod(1, n) }
    }

    function zeroExp(uint256 e) external pure returns (uint256 r) {
        assembly { r := exp(0, e) }
    }
}
