// SPDX-License-Identifier: UNLICENSED
pragma solidity ^0.8.13;

/// Exercises the prover's hashing model: injectivity, region separation
/// ("large gaps"), root-slot reservation, and storage-pointer arithmetic.
contract Hashing {
    uint256 public scalarA;                         // slot 0
    uint256 public scalarB;                         // slot 1
    mapping(uint256 => uint256) public mapA;        // slot 2
    mapping(uint256 => uint256) public mapB;        // slot 3
    uint256[] public arrA;                          // slot 4
    uint256[] public arrB;                          // slot 5

    struct Pair {
        uint256 fst;
        uint256 snd;
    }
    mapping(uint256 => Pair) public pairs;          // slot 6
    mapping(uint256 => mapping(uint256 => uint256)) public nested; // slot 7

    /// element size 6 (non-power-of-two) -- hook index recovery has to invert
    /// `dataSlot(grid) + 6*i + 2*j + k` with div/mod by the strides
    uint256[2][3][] public grid;                    // slot 8

    function setMapA(uint256 k, uint256 v) external { mapA[k] = v; }
    function setMapB(uint256 k, uint256 v) external { mapB[k] = v; }

    function setArrA(uint256 i, uint256 v) external { arrA[i] = v; }
    function setArrB(uint256 i, uint256 v) external { arrB[i] = v; }
    function pushArrA(uint256 v) external { arrA.push(v); }
    function arrALength() external view returns (uint256) { return arrA.length; }

    function setPairFst(uint256 k, uint256 v) external { pairs[k].fst = v; }
    function setPairSnd(uint256 k, uint256 v) external { pairs[k].snd = v; }

    function setNested(uint256 k1, uint256 k2, uint256 v) external { nested[k1][k2] = v; }

    function setGrid(uint256 i, uint256 v) external { grid[i][1][1] = v; }

    /// writes through a raw storage pointer computed as `dataSlot(arrA) + idx`,
    /// with NO bounds check -- on the real EVM, for `idx` large enough the
    /// pointer wraps around 2^256; the model keeps storage-key offsets from
    /// wrapping.
    function rawWriteArrA(uint256 idx, uint256 v) external {
        uint256 dataSlot = uint256(keccak256(abi.encode(uint256(4))));
        assembly {
            sstore(add(dataSlot, idx), v)
        }
    }

    /// reads through the same raw pointer computation.
    function rawReadArrA(uint256 idx) external view returns (uint256 v) {
        uint256 dataSlot = uint256(keccak256(abi.encode(uint256(4))));
        assembly {
            v := sload(add(dataSlot, idx))
        }
    }

    /// value hashes for the hash-family rules
    function keccakOf(uint256 x) external pure returns (uint256) {
        return uint256(keccak256(abi.encode(x)));
    }

    function keccakOf2(uint256 x, uint256 y) external pure returns (uint256) {
        return uint256(keccak256(abi.encode(x, y)));
    }

    function sha256Of(uint256 x) external pure returns (uint256) {
        return uint256(sha256(abi.encode(x)));
    }

    function sha256Of2(uint256 x, uint256 y) external pure returns (uint256) {
        return uint256(sha256(abi.encode(x, y)));
    }

    /// fills the array and then deletes it -- solc generates a clearing loop
    /// whose exit condition compares storage pointers (`ptr - start` style
    /// arithmetic on storage keys).
    function fillThenDelete(uint256 v, uint256 len) external {
        require(arrA.length == 0);
        for (uint256 i = 0; i < len; i++) {
            arrA.push(v);
        }
        delete arrA;
    }

    /// deletes the array whatever its length -- the clearing loop runs from
    /// `dataSlot(arrA)` up to `dataSlot(arrA) + arrA.length`
    function deleteArrA() external {
        delete arrA;
    }
}
