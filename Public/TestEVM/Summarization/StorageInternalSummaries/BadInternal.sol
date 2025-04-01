// SPDX-License-Identifier: UNLICENSED

pragma solidity >=0.8.7 <0.9.0;

contract BadInternal {

    uint256[256] a;

    function getWord(uint8 slot) internal returns (uint256 x) {
        assembly ("memory-safe") {
            x := sload(add(a.slot, slot))
        }
    }

    function get_index(uint256 x) internal returns (uint8 y) {
        assembly ("memory-safe") {
            y := x
        }
    }

    function test(uint256 i) public returns (uint256) {
        return getWord(get_index(i));
    }
}
