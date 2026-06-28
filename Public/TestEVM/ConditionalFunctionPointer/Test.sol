contract Test {
    function foo(uint256 x) internal pure returns (uint256) {
        return compute(x + 1);
    }

    function bar(uint256 x) internal pure returns (uint256) {
        return compute(x + 2);
    }

    function compute(uint256 r) internal pure returns (uint256) {
        r = r ^ 0xdeadbeefdeadbeefdeadbeefdeadbeef;
        r = r * 3;
        r = r + 17;
        r = r ^ 0xcafebabecafebabecafebabecafebabe;
        return r;
    }

    function test(bool cond, uint256 x) external pure returns (uint256) {
        function(uint256) internal pure returns (uint256) f = cond ? foo : bar;
        return f(x);
    }
}
