contract vacuous {
    uint private b = 5;

    function setB() public returns (bool) {
         if (b != 5) {
            revert("reverting");
            return false;
         }
         b = 6;
         return true;
    }
}
