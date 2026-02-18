contract C {
    uint256 fooCalled = 0;
    uint256 barCalled = 0;
    function foo(address x, address z) public returns (uint) {
        if(x == address(0)){
            return 0;
        }
        address(x).call(abi.encodeWithSignature("bar(address,address)"));
        fooCalled = fooCalled + 1;
        return 1;
    }

    function bar(address y, address z) public returns (uint) {
        if(y == address(0)){
            return 0;
        }
        address(y).call(abi.encodeWithSignature("foo(address,address)"));
        barCalled = barCalled + 1;
        return 1;
    }
}
