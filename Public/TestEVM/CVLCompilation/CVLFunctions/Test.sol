contract Test {
    constructor() public {}

    function giveMeThree() public returns (uint) {
        return 3;
    }

    function revertsForSure() public {
        require(false);
    }
}