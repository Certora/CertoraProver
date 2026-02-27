// SPDX-License-Identifier: MIT
pragma solidity ^0.8.0;

import "./Context.sol";

contract TestContract is Context {
    address public owner;

    constructor() {
        owner = _msgSender();
    }

    modifier onlyOwner() {
        require(_msgSender() == owner, "Not the owner");
        _;
    }

    function setOwner(address newOwner) external onlyOwner {
        owner = newOwner;
    }

    function doSomething() external onlyOwner returns (bool) {
        // Only owner can call this
        return true;
    }
}
