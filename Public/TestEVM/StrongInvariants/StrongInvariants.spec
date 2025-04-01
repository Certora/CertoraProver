
methods{
    function _.havocOnlyReturn() external => NONDET;
    function _.repay() external => DISPATCHER(true);
    function _.havocECF() external => HAVOC_ECF;
    function _.havocALL() external => HAVOC_ALL;
}

strong invariant storageValueIsConstant_strong(env e) 1 == currentContract.storageValue;

weak invariant storageValueIsConstant_weak(env e) 1 == currentContract.storageValue;

invariant storageValueIsConstant_default(env e) 1 == currentContract.storageValue;

invariant trivialInvariant(env e, uint y) require_uint256(y + 1) == shouldSucceed_addOne(e, y);
