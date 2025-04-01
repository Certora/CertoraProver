pragma solidity 0.8.24;

import "./SomeInterface.sol";

contract WithPreservedBlock {
    uint256 storageValue = 1;

    function summarizedValue(uint256 param) public returns (uint256){
        return param;
    }

    function invariantDoesHoldAfterBasicCall(SomeInterface token, uint256 input) external {
        if(summarizedValue(input) == 3){
           storageValue = 4;
        }
        address(token).call(abi.encodeWithSignature("noSummaryForThisCall()"));

        if(summarizedValue(input) == 3){
            storageValue = 5;
        }
    }

    function invariantDoesntHoldAfterHavocing(SomeInterface token, uint256 input) external {
        if(summarizedValue(input) == 3){
           storageValue = 4;
        }
        address(token).delegatecall(abi.encodeWithSignature("notSummaryForThisCall()"));
        if(summarizedValue(input) == 3){
            storageValue = 5;
        }
    }


    function invariantDoesHoldAfterHavocing(SomeInterface token, uint256 input) external {
        if(summarizedValue(input) == 3){
           storageValue = 4;
        }
        address(token).delegatecall(abi.encodeWithSignature("havocedMethod()"));
        if(summarizedValue(input) == 3){
            storageValue = 5;
        }
    }


    function mustFailForAll(SomeInterface token, uint256 input) external {
        //This method cannot be resolved, so the call will be havoc'ed (including it's storage)
        if(summarizedValue(input) == 3){
           storageValue = 4;
        }
        address(token).delegatecall(abi.encodeWithSignature("havocedMethodCall()"));
        //When using weakinvariants, the storage will be havoc'ed here, including the persistent return value.
        if(summarizedValue(input) == 3){
            storageValue = 5;
        }

        storageValue = 5;
    }

    function invariantMayHold(uint256 input) external {
        if(summarizedValue(input) == 3){
           storageValue = 6;
        }
    }
}
