pragma solidity ^0.8.24;

import "./Lib.sol";
contract Contract {
    function add_withLibrary(uint x, uint y) public returns (uint256) {
        return Lib.add(x, y);
    }
}
