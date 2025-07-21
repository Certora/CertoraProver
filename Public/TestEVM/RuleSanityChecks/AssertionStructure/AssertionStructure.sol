contract AssertionStructure {
    uint public counter;

    function incrementAndGet() external returns (uint) {
        counter = counter + 1;
        return counter;
    }
}
