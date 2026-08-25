// SPDX-License-Identifier: GPL-2.0-or-later
pragma solidity 0.8.28;

contract TransientHolder {
    uint256 transient held;

    function touch() external {
        held = held + 1;
    }

    function get() external view returns (uint256) {
        return held;
    }
}

contract HavocedCall {
    uint256 public a;
    uint256 public b;

    constructor(address t) {
        uint256 v = f(t);
        a = v;
        b = v;
    }

    // Summarized as NONDET in the spec. The unresolved call is materialized before the internal
    // summary is applied, and its havoc writes every other contract's storage and transient
    // storage inside this body.
    function f(address t) internal returns (uint256) {
        (bool ok, ) = t.call(abi.encodeWithSignature("touch()"));
        return ok ? 1 : 0;
    }

    function g(address t) external {
        uint256 v = f(t);
        a = v;
        b = v;
    }
}
