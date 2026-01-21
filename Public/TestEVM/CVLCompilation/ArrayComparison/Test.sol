// SPDX-License-Identifier: MIT
pragma solidity ^0.8.0;

contract Test {
    // Simple struct (no arrays)
    struct SimpleStruct {
        uint256 x;
        int128 y;
    }

    // Struct with array field
    struct StructWithArray {
        uint256 id;
        uint256[] values;
    }

    // Struct with nested struct and array
    struct StructWithNestedAndArray {
        SimpleStruct inner;
        uint256[] arr;
    }

    // For testing array of structs
    function createSimpleStruct(uint256 x, int128 y) external pure returns (SimpleStruct memory) {
        return SimpleStruct(x, y);
    }

    function createIntArray(uint256 a, uint256 b, uint256 c) external pure returns (uint256[] memory) {
        uint256[] memory arr = new uint256[](3);
        arr[0] = a;
        arr[1] = b;
        arr[2] = c;
        return arr;
    }

    function createStructWithArray(uint256 id, uint256 a, uint256 b) external pure returns (StructWithArray memory) {
        uint256[] memory values = new uint256[](2);
        values[0] = a;
        values[1] = b;
        return StructWithArray(id, values);
    }
}
