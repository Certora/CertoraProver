// SPDX-License-Identifier: MIT
pragma solidity ^0.8.0;

contract Receiver {
    uint256 public value;

    function setValue(uint256 _value) external {
        value = _value;
    }
}

contract Caller {
    function callSetValue(address target, uint256 newValue) external returns (uint256) {
        (bool success, ) = target.call(
            abi.encodeWithSignature("setValue(uint256)", newValue)
        );
        require(success, "Call failed");
        return newValue;
    }
}