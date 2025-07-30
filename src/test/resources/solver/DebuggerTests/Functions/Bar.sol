contract Bar {
    function add(uint a, uint b) public pure returns (uint) {
        // Intentional bug to make the rule fail
        return a - b;
    }
}
