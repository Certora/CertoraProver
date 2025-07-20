contract RevertingInvariant {
    uint256 public constant MAX_LIMIT = 100;
    uint256[] public limits;

    function setLimit(uint256 index, uint256 value) public {
        if (index < limits.length) {
            // this check is at the wrong line
            require(value <= MAX_LIMIT);
            // change previously set limit.
            limits[index] = value;
        } else {
            require(index == limits.length);
            // add a new limit to the end
            limits.push(value);
        }
    }

    function limitsLength() public view returns (uint256) {
        return limits.length;
    }

    function getLimit(uint index) public view returns (uint256) {
        return limits[index];
    }
}
