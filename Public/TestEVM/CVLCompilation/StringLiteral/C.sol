// SPDX-License-Identifier: agpl-3.0
pragma solidity >=0.8.10;

contract C {
    function compareStrings(string memory s1, string memory s2) internal pure returns (bool) {
        return keccak256(abi.encodePacked(s1)) == keccak256(abi.encodePacked(s2));
    }

    function whichString(string memory s) external pure returns(int) {
        require(bytes(s).length < 10);
        if (compareStrings(s, "option1")) {
            return 1;
        }
        if (compareStrings(s, "option2")) {
            return 2;
        }
        return -1;
    }
}
