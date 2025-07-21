pragma solidity >= 0.8.24;

contract SkipResetStorageStep {

    uint256 storageValue = 10;

    function viewFunction() public view returns (uint256){
        return storageValue;
    }

    function getDelta(uint256 x) public returns (uint256) {
        return x;
    }
    function borrow(int256 delta) external {
        storageValue += uint256(delta);
    }

    function repay(int256 delta) external {
        storageValue -= uint256(delta);
    }

}
