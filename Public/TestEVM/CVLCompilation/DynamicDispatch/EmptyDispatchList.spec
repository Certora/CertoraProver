using Other as other;

methods {
    function _._ external => DISPATCH [] default NONDET;
}

/**
* I want to test a case where the default is called. To do that I will force a scenario where
* there are two possible sighashes for the callsite, and none match any known function. Then
* I will test to see something passes and something does not (depending on the chosen default).
*/
rule lottery {
    env e;
    calldataarg arg;
    // Also make sure no x was changed as it may also behave similar nondet
    uint oldxC = currentContract.x;
    uint oldxO = other.x;
    uint run1 = currentContract.unresolvedOneOfTwo(e, arg);
    uint run2 = currentContract.unresolvedOneOfTwo(e, arg);
    assert oldxC == currentContract.x, "Expecting update to be uncallable";
    assert oldxO == other.x, "Expecting update to be uncallable";
    satisfy run1 != run2;
}
