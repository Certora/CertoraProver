pragma solidity 0.8.24;

import "./SomeInterface.sol";

contract ContractWithDelegate {
    uint256 storageValueNonCurrentContract = 2;
    function methodWithDelegateCallResolved(SomeInterface token) external {
        address(token).delegatecall(abi.encodeWithSignature("repay()"));
     }
    function methodWithDelegateCallUnresolved(SomeInterface token) external {
        address(token).delegatecall(abi.encodeWithSignature("nonExistingMethod()"));
     }
}
