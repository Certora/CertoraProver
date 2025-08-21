/*
Ran by Dynamic.conf:
https://prover.certora.com/output/15800/2c746d952cfd49a4b0cf1a12d9667088?anonymousKey=051aba266693845c18d27586c815c0ef69e68332
*/
using Other as other;

rule easy {
    env e;
    calldataarg arg;
    unresolved(e, arg);
    satisfy true, "Reachability";
}

methods {
    unresolved external in C.unresolved(address,bytes) => DISPATCH [
        C.bar(uint)
    ] default NONDET;
}
rule failDueToStateChange {
    env e;
    calldataarg arg;
    uint oldx = currentContract.x;
    currentContract.unresolved(e, arg);
    /**
    if (add == C && sighash == sig:bar(uint)) C.bar(...)
    else if (....)
    else NONDET
    */
    assert oldx == currentContract.x, "Expecting fail due to bar or update";
}

rule failDueToStateChange2 {
    env e;
    calldataarg arg;
    uint oldx = currentContract.x;
    currentContract.abicall(e, arg);
    assert oldx == currentContract.x, "Expecting fail due to bar or update";
}

/** This tests that we allow reverts from dispatch list functions */
rule itReverts {
    env e;
    calldataarg arg;
    // Require a revert
    require currentContract.unresolved(e, arg);
    satisfy true, "We should allow reverts";
}

methods {
    unresolved external in _.delegate(address,bytes) => DISPATCH [
        _.updatey(),
    ] default NONDET;
}
/** This tests that delegate calls will only be called on `this` */
rule delegateItToSomeoneElse {
    env e;
    calldataarg arg;
    require currentContract.y == 1;
    require other.y == 1;
    currentContract.delegate(e, arg);
    assert 3 != other.y, "Delegate calls should not work with function from other contracts";
    satisfy 2 == currentContract.y, "Expecting C.updatey to work with a delegate call";
}

/**
* I want to test a case where the default is called. To do that I will force a scenario where
* there are two possible sighashes for the callsite, and none match any known function. Then
* I will test to see something passes and something does not (depending on the chosen default).
*/
methods {
    unresolved external in C.unresolvedOneOfTwo(address,bytes,uint) => DISPATCH [
        _.updatey(),
    ] default NONDET;
}
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

/**
* Now we test that the default case won't be called when the sighash always matches
* a known function.
*/
methods {
    unresolved external in C.unresolvedOneOfTwoKnown(address,uint,uint) => DISPATCH [
        C.r1(uint),
        C.r2(uint),
    ] default HAVOC_ALL;
}
rule fixed_lottery {
    env e;
    calldataarg arg;
    // Also make sure no x was changed as it may also behave similar nondet
    uint oldxC = currentContract.x;
    uint oldxO = other.x;
    uint run1 = currentContract.unresolvedOneOfTwoKnown(e, arg);
    uint run2 = currentContract.unresolvedOneOfTwoKnown(e, arg);
    assert oldxC == currentContract.x, "Expecting update to be uncallable";
    assert oldxO == other.x, "Expecting update to be uncallable";
    assert run1 == run2;
}

methods {
    unresolved external in C.unresolvedOneOfTwoNotExistingKnown(address,uint,uint) => DISPATCH [
        C._,
        Other._,
    ] default NONDET;
}
rule noCalleesMatchDefault {
    env e;
    calldataarg arg;
    // Also make sure no x was changed as it may also behave similar nondet
    uint oldxC = currentContract.x;
    uint oldxO = other.x;
    uint run1 = currentContract.unresolvedOneOfTwoNotExistingKnown(e, arg);
    uint run2 = currentContract.unresolvedOneOfTwoNotExistingKnown(e, arg);
    assert oldxC == currentContract.x, "Expecting update to be uncallable";
    assert oldxO == other.x, "Expecting update to be uncallable";
    satisfy run1 != run2;
}

/*
Need to force calling the fallback on the contract.
*/
rule noCalleesMatchFallback {
    env e;
    calldataarg arg;
    require !currentContract.fallbackCalled;
    currentContract.abicallNotExist(e, arg);
    satisfy currentContract.fallbackCalled, "Expecting contract fallback";
}
