// SPDX-License-Identifier: agpl-3.0
pragma solidity >=0.8.10;

contract C {
    mapping(bytes32 => bytes) public names;

    function getNames(bytes32 node) public view returns (bytes memory name) {
        name = names[node];
    }
}
