methods {
    function and256(int256,int256) external returns (int256) envfree;
    function and128(int128,int128) external returns (int128) envfree;
    function andu256(uint256,uint256) external returns (uint256) envfree;
    function andu128(uint128,uint128) external returns (uint128) envfree;
    function andb32(bytes32,bytes32) external returns (bytes32) envfree;
    function andb16(bytes16,bytes16) external returns (bytes16) envfree;
}

definition MAX_INT256() returns int256 = assert_int256(2^255 - 1);
definition MAX_INT128() returns int128 = assert_int128(2^127 - 1);
definition MAX_INT64() returns int64 = assert_int64(2^63 - 1);

rule constants {
    assert 0x1 & 0x2 == 0x0;
    assert 0x1 & 0x3 == 0x1;
    assert -0x1 & 0x1 == 0x1;
    // TODO: bytesk types
}

/*** uint256 ***/
rule symbolicAndConstantsu256 {
    uint256 a;
    assert a & 0x0 == 0x0;
    assert a & max_uint256 == a;

    uint256 b;
    require b < max_uint128;
    assert b & max_uint128 == b;

    uint256 c;
    require c > max_uint128;
    assert c & max_uint128 != c;
}

rule symbolicu256 {
    uint256 a;
    uint256 b;
    require a == b;
    assert a & b == a;

    uint256 c;
    uint256 d;
    require c != d;
    assert c & d != c || c & d != d;

    uint256 e;
    uint256 f;
    assert e & f == andu256(e, f);
}

/*** uint128 ***/
rule symbolicAndConstantsu128 {
    uint128 a;
    assert a & max_uint128 == a;
    assert a & 0x0 == 0x0;

    uint128 b;
    require b < max_uint64;
    assert b & max_uint64 == b;
    uint128 c;
    require c > max_uint64;
    assert c & max_uint64 != c;
}

rule symbolicu128 {
    uint128 a;
    uint128 b;
    assert a & b <= max_uint128;

    uint64 c;
    uint128 d;
    assert c & d <= max_uint64;

    uint128 e;
    uint128 f;
    assert e & f == andu128(e, f);
}

/*** int256 ***/
rule symbolicAndConstants256 {
    int256 a;
    assert a & 0x0 == 0x0;
    mathint resa = a & MAX_INT256();
    assert a >= 0 => assert_int256(resa) == a && a < 0 => resa == a - max_uint256, "wrong result a";

    int256 b;
    require b < assert_int256(MAX_INT128());
    mathint resb = b & MAX_INT128();
    assert b >= 0 => assert_int256(resb) == b && b < 0 => resb == b - max_uint256, "wrong result b";

    int256 c;
    require c > MAX_INT256();
    assert c & MAX_INT128() != c, "wrong result c";
}

rule symbolic256 {
    int256 a;
    int256 b;
    require a == b;
    assert a & b == a;

    int256 c;
    int256 d;
    require c != d;
    assert c & d != c || c & d != d;

    int256 e;
    int256 f;
    assert e & f == and256(e, f);
}

/*** int128 ***/
rule symbolicAndConstants128 {
    int128 a;
    assert a & 0x0 == 0x0;
    mathint resa = a & MAX_INT128();
    assert a >= 0 => assert_int128(resa) == a && a < 0 => resa == a - max_uint128, "wrong result a";

    int128 b;
    require b < assert_int128(MAX_INT64());
    mathint resb = b & MAX_INT64();
    assert b >= 0 => assert_int128(resb) == b && b < 0 => resb == b - max_uint128, "wrong result b";

    int128 c;
    require c > assert_int128(MAX_INT64());
    assert c & MAX_INT64() != c, "wrong result c";
}

rule symbolic128 {
    int128 a;
    int128 b;
    assert a & b <= MAX_INT128();

    int64 c;
    int128 d;
    assert c & d <= assert_int128(MAX_INT64());

    int128 e;
    int128 f;
    assert e & f == and128(e, f);
}

/*** bytes32 ***/
rule symbolicAndConstantsb32 {
    // TODO: once casting is done
    assert true;
}

rule symbolicb32 {
    bytes32 a;
    bytes32 b;
    require a == b;
    assert a & b == a;

    bytes32 c;
    bytes32 d;
    require c != d;
    assert c & d != c || c & d != d;

    bytes32 e;
    bytes32 f;
    assert e & f == andb32(e, f);
}

/*** bytes16 ***/
rule symbolicAndConstantsb16 {
    // TODO: once casting is done
    assert true;
}

rule symbolicb16 {
    bytes16 a;
    bytes16 b;
    require a == b;
    assert a & b == a;

    bytes16 c;
    bytes16 d;
    require c != d;
    assert c & d != c || c & d != d;

    bytes16 e;
    bytes16 f;
    assert e & f == andb16(e, f);
}
