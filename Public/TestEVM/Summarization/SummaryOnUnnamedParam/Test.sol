contract Test {

    bool not42;

    function internalUnimplementedFunctionWithUnnamedParam(uint256) internal {}

    function setBool(uint x) internal {
        if(x != 42) {
            not42 = true;
        }
    }

    function test(uint x) external {
        uint y = x;
        setBool(x);
        internalUnimplementedFunctionWithUnnamedParam(y);
    }
}
