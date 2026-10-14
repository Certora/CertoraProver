// SPDX-License-Identifier: MIT
pragma solidity 0.8.25;

// Repro for CERT-9807.
// Checked `+` and `-` on a signed type compile to the signed overflow-check builtins.
// Under `precise_bitwise_ops` the VC goes to the bitvector solvers, and with no
// multiplication overflow check in it those checks are encoded as SMT define-funs
// (`BvOverflowChecksViaDefineFuns`) instead of the SMT-LIB overflow predicates.
contract C {
    int256 public a;
    int256 public b;

    function signedAdd() external view returns (int256) {
        return a + b;
    }

    function signedSub() external view returns (int256) {
        return a - b;
    }
}
