methods {
    function WithPreservedBlock.summarizedValue(uint256) internal returns (uint256) => summary();
    function _.havocedMethod() external => NONDET;
}

persistent ghost uint256 retVal;

function summary() returns uint256{
    return retVal;
}

invariant storageValueIsConstantWithGenericPreserved(env e) 1 == currentContract.storageValue {
    preserved{
        require retVal != 3;
    }
}

strong invariant storageValueIsConstantWithExplicitPreserved(env e) 1 == currentContract.storageValue {
    preserved invariantDoesntHoldAfterHavocing(address token, uint256 input) with (env e2){
       require retVal != 3;
   }
    preserved invariantDoesHoldAfterHavocing(address token, uint256 input) with (env e2){
       require retVal != 3;
   }
    preserved invariantDoesHoldAfterBasicCall(address token, uint256 input) with (env e2){
       require retVal != 3;
   }
}

strong invariant invariantDoesntHoldWithoutPreserved(env e) 1 == currentContract.storageValue;
