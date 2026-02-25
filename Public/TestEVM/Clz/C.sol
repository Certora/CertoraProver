contract C {
    function clz(uint256 x) pure public returns (uint256 r) {
        assembly {
            r := clz(x)
        }
    }
}
