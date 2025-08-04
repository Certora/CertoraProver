contract C {
    uint256 public bar;

    function foo() public returns (uint256) {
        return baz();
    }

    function baz() internal returns (uint256) {
        return 1;
    }

    function other() public returns (uint256) {
        return 1;
    }
}
