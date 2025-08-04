contract Test {
    struct S {
        uint256[2] x;
        uint256[2] y;
    }
    function generator() internal pure returns (S memory) {
        return S([uint256(1), uint256(2)], [uint256(3), uint256(4)]);
    }
    function test() external pure returns (S memory) {
        return generator();
    }
}
