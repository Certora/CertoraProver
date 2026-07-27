// SPDX-License-Identifier: GPL-3.0
pragma solidity ^0.8.0;

/**
 * Minimal witness for signed division rounding toward zero.
 *
 * For x == -1, Solidity evaluates x / 2 to 0, so the assertion in
 * solidityAssertion is false.  The corresponding CVL expression is used by
 * DivComparisonRounding.spec to expose an unsound prover optimization.
 */
contract DivComparisonRounding {
    function quotient(int256 x) external pure returns (int256) {
        return x / 2;
    }

    function solidityAssertion(int256 x) external pure {
        require(x < 0);
        assert(x / 2 < 0);
    }
}
