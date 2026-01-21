// SPDX-License-Identifier: MIT
pragma solidity ^0.8.0;

contract TestContract {
    uint256 public value;
    function setStorageValue(uint256 newValue) external returns (uint256) {
        value = newValue;
        return value;
    }
}