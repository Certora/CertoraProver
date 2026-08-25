// SPDX-License-Identifier: GPL-3.0
pragma solidity ^0.8.0;

contract C {
    function quotient(int256 x) external pure returns (int256) {
        return x / 2;
    }
}
