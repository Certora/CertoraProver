contract C {

    function narrow(int64 x) public pure returns (int32) {
        int32 y = int32(x);
        return y;
    }

    function unsignedToSigned(uint x) public pure returns (int)  {
        return int(x);
    }

}
