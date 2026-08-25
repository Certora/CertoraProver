// SPDX-License-Identifier: GPL-2.0-or-later
pragma solidity 0.8.28;

contract TransientInConstructor {
    uint256 public a;
    uint256 public b;
    uint256 transient tvar;

    constructor() {
        uint256 v = f();
        a = v;
        b = v;
    }

    // Writes transient storage. Summarized as NONDET in the spec, so this body is removed; the write
    // to `tvar` is state, not a local that needs restoring at the summary's exit.
    function f() internal returns (uint256) {
        tvar = tvar + 1;
        return tvar;
    }

    function g() external {
        uint256 v = f();
        a = v;
        b = v;
    }
}
