contract Dummy {
    constructor() {}

    function and256(int256 x, int256 y) external pure returns (int256) {
        return x & y;
    }

    function and128(int128 x, int128 y) external pure returns (int128) {
        return x & y;
    }

    function andu256(uint256 x, uint256 y) external pure returns (uint256) {
        return x & y;
    }

    function andu128(uint128 x, uint128 y) external pure returns (uint128) {
        return x & y;
    }

    function andb32(bytes32 x, bytes32 y) external pure returns (bytes32) {
        return x & y;
    }

    function andb16(bytes16 x, bytes16 y) external pure returns (bytes16) {
        return x & y;
    }

    function or256(int256 x, int256 y) external pure returns (int256) {
        return x | y;
    }

    function or128(int128 x, int128 y) external pure returns (int128) {
        return x | y;
    }

    function oru256(uint256 x, uint256 y) external pure returns (uint256) {
        return x | y;
    }

    function oru128(uint128 x, uint128 y) external pure returns (uint128) {
        return x | y;
    }

    function orb32(bytes32 x, bytes32 y) external pure returns (bytes32) {
        return x | y;
    }

    function orb16(bytes16 x, bytes16 y) external pure returns (bytes16) {
        return x | y;
    }

    function xor256(int256 x, int256 y) external pure returns (int256) {
        return x ^ y;
    }

    function xor128(int128 x, int128 y) external pure returns (int128) {
        return x ^ y;
    }

    function xoru256(uint256 x, uint256 y) external pure returns (uint256) {
        return x ^ y;
    }

    function xoru128(uint128 x, uint128 y) external pure returns (uint128) {
        return x ^ y;
    }

    function xorb32(bytes32 x, bytes32 y) external pure returns (bytes32) {
        return x ^ y;
    }

    function xorb16(bytes16 x, bytes16 y) external pure returns (bytes16) {
        return x ^ y;
    }

    function lshift256(int256 x, uint256 y) external pure returns (int256) {
        return x << y;
    }

    function lshift128(int128 x, uint256 y) external pure returns (int128) {
        return x << y;
    }

    function lshiftu256(uint256 x, uint256 y) external pure returns (uint256) {
        return x << y;
    }

    function lshiftu128(uint128 x, uint256 y) external pure returns (uint128) {
        return x << y;
    }

    function lshiftb32(bytes32 x, uint256 y) external pure returns (bytes32) {
        return x << y;
    }

    function lshiftb16(bytes16 x, uint256 y) external pure returns (bytes16) {
        return x << y;
    }

    function arithrshift256(int256 x, uint256 y) external pure returns (int256) {
        return x >> y;
    }

    function arithrshift128(int128 x, uint256 y) external pure returns (int128) {
        return x >> y;
    }

    function arithrshiftu256(uint256 x, uint256 y) external pure returns (uint256) {
        return x >> y;
    }

    function arithrshiftu128(uint128 x, uint256 y) external pure returns (uint128) {
        return x >> y;
    }

    function arithrshiftb32(bytes32 x, uint256 y) external pure returns (bytes32) {
        return x >> y;
    }

    function arithrshiftb16(bytes16 x, uint256 y) external pure returns (bytes16) {
        return x >> y;
    }

    function not256(int256 x) external pure returns (int256) {
        return ~x;
    }

    function not128(int128 x) external pure returns (int128) {
        return ~x;
    }

    function notu256(uint256 x) external pure returns (uint256) {
        return ~x;
    }

    function notu128(uint128 x) external pure returns (uint128) {
        return ~x;
    }

    function notb32(bytes32 x) external pure returns (bytes32) {
        return ~x;
    }

    function notb16(bytes16 x) external pure returns (bytes16) {
        return ~x;
    }
}