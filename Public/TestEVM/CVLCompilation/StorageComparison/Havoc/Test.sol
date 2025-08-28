contract Test {
    uint public i;

    function changeI(uint val) external {
        i = val;
    }
}
