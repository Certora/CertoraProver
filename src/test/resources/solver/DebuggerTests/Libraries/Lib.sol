pragma solidity ^0.8.24;
library Lib {
    function add(uint256 a, uint256 b) internal pure returns (uint256) {
        // This bug is here on purpose
        return a - b;
    }
}
