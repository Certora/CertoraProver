using Other as other;

methods {
    function C.update(uint) external returns uint => HAVOC_ALL;
    unresolved external in _._ => DISPATCH [
        C.update(uint),
    ] default NONDET;
}

/** Testing that bar does not change x anymore */
rule stateNotChanging {
    env e;
    calldataarg args;
    uint oldY = currentContract.y;
    require unresolved(e, args);
    uint newY = currentContract.y;
    satisfy newY != oldY, "Expecting summary of update to HAVOC_ALL to change y";
}
