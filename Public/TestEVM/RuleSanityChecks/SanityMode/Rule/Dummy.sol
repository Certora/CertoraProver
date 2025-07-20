contract Dummy {
    uint public val;
    bool public condResult;

    function alwaysTrue(uint n) pure external returns (bool) { return true; }
    function valSmallerThanSix() public {
            if (val < 6) {
                condResult = true;
            } else {
                condResult = false;
            }
        }

    function valExactlyFive() public {
        if (val == 5) {
            condResult = true;
        } else {
            condResult = false;
        }
    }

    function alwaysRevert() pure external returns(bool) {
        revert();
    }
}
