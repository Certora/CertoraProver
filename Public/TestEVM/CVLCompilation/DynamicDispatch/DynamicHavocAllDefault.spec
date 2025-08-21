using Other as other;

methods {
    unresolved external in _._ => DISPATCH [
        C.r1(uint),
        C.r2(uint),
    ] default HAVOC_ALL;
}

rule easy {
    env e;
    calldataarg arg;
    unresolved(e, arg);
    satisfy true, "Reachability";
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
    uint run1 = currentContract.unresolvedOneOfTwo(e, arg);
    satisfy oldxC != currentContract.x, "Expecting Havoc all";
}
