contract Contract {
    uint256 public someField;
    function getSomeField() public view returns (uint) {
        return someField;
    }

    function setSomeField(uint256 val) public {
        someField = val;
        unrelatedFunction(val);
        unrelatedFunction(val);
    }

    function unrelatedFunction(uint256 val) public pure returns (uint) {
        return val;
    }
}
