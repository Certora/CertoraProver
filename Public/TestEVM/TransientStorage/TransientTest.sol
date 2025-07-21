// SPDX-License-Identifier: GPL-2.0-or-later
pragma solidity 0.8.28;

contract TransientTest {
    bool transient locked;
    bool transient maybe_accessed;
    uint non_transient_other_field;
    bool non_transient_bool_field;

    function lock() public returns (bool) {
        locked = true;
        return true;
    }

    function unlock() public returns (bool) {
        locked = false;
        non_transient_other_field = 2;
        non_transient_bool_field = true;
        return false;
    }

    function isLocked() public view returns (bool) {
        return locked;
    }

    function maybeAccess(bool b) public {
        if (b) {
            maybe_accessed = true;
        }
    }
}
