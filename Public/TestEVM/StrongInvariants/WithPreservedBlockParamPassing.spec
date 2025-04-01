methods {
    function _.havocedMethod() external => NONDET;
}

strong invariant storageValueIsConstantWithExplicitPreservedRestrictParam(env e) 1 == currentContract.storageValue {
    preserved invariantDoesntHoldAfterHavocing(address token, uint256 input) with (env e2){
       require input != 3;
   }
    preserved invariantDoesHoldAfterHavocing(address token, uint256 input) with (env e2){
       require input != 3;
   }
    preserved invariantDoesHoldAfterBasicCall(address token, uint256 input) with (env e2){
       require input != 3;
   }
}
