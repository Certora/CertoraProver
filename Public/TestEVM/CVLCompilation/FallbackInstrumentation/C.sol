// SPDX-License-Identifier: agpl-3.0
pragma solidity >=0.8.10;

contract C {
    uint256 public counter;

    receive() external payable {
        counter = 0xBAD;
    }

    fallback() external payable {
        counter = 0xDAB;
    }
}
