// SPDX-License-Identifier: GPL-2.0-or-later
pragma solidity 0.8.28;

// A mapping stays an unsplit WordMap storage variable, which is the only kind that gets a read
// tracker under -assumeDeadStorageIsZero.
contract Holder {
    mapping(uint256 => uint256) m;

    function touch() external {
        m[1] = m[1] + 1;
    }

    function get(uint256 k) external view returns (uint256) {
        return m[k];
    }
}

contract Caller {
    uint256 public a;
    uint256 public b;

    constructor(address t) {
        uint256 v = f(t);
        a = v;
        b = v;
    }

    // Summarized as NONDET in the spec. The unresolved call is materialized before the internal
    // summary is applied, and its havoc writes the other contract's storage and read trackers
    // inside this body.
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
