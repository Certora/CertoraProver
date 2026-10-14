// SPDX-License-Identifier: MIT
pragma solidity ^0.8.21;

interface ICallee {
    function value() external view returns (uint256);
}

contract Callee is ICallee {
    uint256 private stored;

    function value() external view returns (uint256) {
        return stored;
    }
}

contract C {
    // The parameter's CVL type is the contract type ICallee, not address.
    function callIt(ICallee c) external view returns (uint256) {
        return c.value();
    }
}
