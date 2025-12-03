contract C {

    // this is not instrumented, becasue our instrumentation is bad for it.
    uint256 internal constant MAXINT256 = uint256(type(int256).max);

    function narrow(int64 x) public pure returns (int32) {
        int32 y = int32(x);
        return y;
    }

    function signed(uint a, uint128 b) public pure returns (int)  {
        int x = int256(a);
        return x + int128(b);
    }

    function noWidth(uint a) public pure returns (int) {
        return int(a);
    }

    function should_not_appear(uint x) public pure returns (uint)  {
       return x;
    }

    function should_pass(int8 x) public pure returns (uint8)  {
        require(x >= 0);
        uint8 y = uint8(x);
        return y;
    }

    function complex(int8 x, int8 y) public pure returns (uint8)  {
        uint8 z = uint8(x + y);
        return z;
    }

    function vacuous(uint32 x) public pure returns (uint8)  {
        require(x < 100);
        require(x > 100);
        uint8 z = uint8(x);
        return z;
    }
}

