methods {
    function and256(int256,int256) external returns (int256) envfree;
    function and128(int128,int128) external returns (int128) envfree;
    function andu256(uint256,uint256) external returns (uint256) envfree;
    function andu128(uint128,uint128) external returns (uint128) envfree;
    function andb32(bytes32,bytes32) external returns (bytes32) envfree;
    function andb16(bytes16,bytes16) external returns (bytes16) envfree;
    function or256(int256,int256) external returns (int256) envfree;
    function or128(int128,int128) external returns (int128) envfree;
    function oru256(uint256,uint256) external returns (uint256) envfree;
    function oru128(uint128,uint128) external returns (uint128) envfree;
    function orb32(bytes32,bytes32) external returns (bytes32) envfree;
    function orb16(bytes16,bytes16) external returns (bytes16) envfree;
    function xor256(int256,int256) external returns (int256) envfree;
    function xor128(int128,int128) external returns (int128) envfree;
    function xoru256(uint256,uint256) external returns (uint256) envfree;
    function xoru128(uint128,uint128) external returns (uint128) envfree;
    function xorb32(bytes32,bytes32) external returns (bytes32) envfree;
    function xorb16(bytes16,bytes16) external returns (bytes16) envfree;
    function lshift256(int256,uint256) external returns (int256) envfree;
    function lshift128(int128,uint256) external returns (int128) envfree;
    function lshiftu256(uint256,uint256) external returns (uint256) envfree;
    function lshiftu128(uint128,uint256) external returns (uint128) envfree;
    function lshiftb32(bytes32,uint256) external returns (bytes32) envfree;
    function lshiftb16(bytes16,uint256) external returns (bytes16) envfree;
    function arithrshift256(int256,uint256) external returns (int256) envfree;
    function arithrshift128(int128,uint256) external returns (int128) envfree;
    function arithrshiftu256(uint256,uint256) external returns (uint256) envfree;
    function arithrshiftu128(uint128,uint256) external returns (uint128) envfree;
    function arithrshiftb32(bytes32,uint256) external returns (bytes32) envfree;
    function arithrshiftb16(bytes16,uint256) external returns (bytes16) envfree;
    function not256(int256) external returns (int256) envfree;
    function not128(int128) external returns (int128) envfree;
    function notu256(uint256) external returns (uint256) envfree;
    function notu128(uint128) external returns (uint128) envfree;
    function notb32(bytes32) external returns (bytes32) envfree;
    function notb16(bytes16) external returns (bytes16) envfree;
}

rule and256(int256 x, int256 y) {
    assert x & y == and256(x,y);
}

rule and128(int128 x, int128 y) {
    assert x & y == and128(x,y);
}

rule andu256(uint256 x, uint256 y) {
    assert x & y == andu256(x,y);
}

rule andu128(uint128 x, uint128 y) {
    assert x & y == andu128(x,y);
}

rule andb32(bytes32 x, bytes32 y) {
    assert x & y == andb32(x,y);
}

rule andb16(bytes16 x, bytes16 y) {
    assert x & y == andb16(x,y);
}

rule or256(int256 x, int256 y) {
    assert x | y == or256(x,y);
}

rule or128(int128 x, int128 y) {
    assert x | y == or128(x,y);
}

rule oru256(uint256 x, uint256 y) {
    assert x | y == oru256(x,y);
}

rule oru128(uint128 x, uint128 y) {
    assert x | y == oru128(x,y);
}

rule orb32(bytes32 x, bytes32 y) {
    assert x | y == orb32(x,y);
}

rule orb16(bytes16 x, bytes16 y) {
    assert x | y == orb16(x,y);
}

rule xor256(int256 x, int256 y) {
    assert x xor y == xor256(x,y);
}

rule xor128(int128 x, int128 y) {
    assert x xor y == xor128(x,y);
}

rule xoru256(uint256 x, uint256 y) {
    assert x xor y == xoru256(x,y);
}

rule xoru128(uint128 x, uint128 y) {
    assert x xor y == xoru128(x,y);
}

rule xorb32(bytes32 x, bytes32 y) {
    assert x xor y == xorb32(x,y);
}

rule xorb16(bytes16 x, bytes16 y) {
    assert x xor y == xorb16(x,y);
}

rule lshift256(int256 x, uint256 y) {
    assert x << y == lshift256(x,y);
}

rule lshift128(int128 x, uint256 y) {
    assert x << y == lshift128(x,y);
}

rule lshiftu256(uint256 x, uint256 y) {
    assert x << y == lshiftu256(x,y);
}

rule lshiftu128(uint128 x, uint256 y) {
    assert x << y == lshiftu128(x,y);
}

rule lshiftb32(bytes32 x, uint256 y) {
    assert x << y == lshiftb32(x,y);
}

// timesout CVLTODO
// when re-enabling, add `Syntax warning in spec file BWEquivalent.spec:133:12: Usage of bitwise operation x << y is experimental. Use with caution.` to the expected.txt
    // rule lshiftb16(bytes16 x, uint256 y) {
    //     assert x << y == lshiftb16(x,y);
    // }

rule rshift256(int256 x, uint256 y) {
    assert x >> y == arithrshift256(x,y);
}

rule rshift128(int128 x, uint256 y) {
    assert x >> y == arithrshift128(x,y);
}

rule rshiftu256(uint256 x, uint256 y) {
    assert x >> y == arithrshiftu256(x,y);
}

rule rshiftu128(uint128 x, uint256 y) {
    assert x >> y == arithrshiftu128(x,y);
}

rule rshiftb32(bytes32 x, uint256 y) {
    assert x >> y == arithrshiftb32(x,y);
}

rule rshiftb16(bytes16 x, uint256 y) {
    assert x >> y == arithrshiftb16(x,y);
}
