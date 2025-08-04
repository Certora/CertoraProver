library L {
    function foo(uint256[] memory x) public pure {
        require(x[0] > 5);
    }
}

contract LHarness {
    function foo(uint256[] memory x) public pure {
        L.foo(x);
    }
}

contract Test {
    mapping(uint => uint) m;

    function test(uint256[] memory foo, uint256[] memory bar) public {
        assembly {
            mstore(bar, 10)
        }
        m[100] = 0;
        L.foo(foo);
    }
}