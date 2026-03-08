interface Bar {
    function bar(uint256 i) external view returns (address);
}
contract C {
    function foo(address a, Bar bar, uint256 count) public view returns (address) {
        for (uint256 i = 0; i < count; i++) {
            address b = bar.bar(i);
            if (b == a) {
                return b;
            }
        }
        revert("Not found");
    }
}
