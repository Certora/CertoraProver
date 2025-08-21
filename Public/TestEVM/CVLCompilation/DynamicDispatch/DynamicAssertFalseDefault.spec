using Other as other;

methods {
    unresolved external in _._ => DISPATCH [
        C.r1(uint),
        C.r2(uint),
    ] default ASSERT_FALSE;
}

rule easy {
    env e;
    calldataarg arg;
    unresolved(e, arg);
    satisfy true, "Reachability";
}

rule lottery {
    env e;
    calldataarg arg;
    currentContract.unresolvedOneOfTwo(e, arg);
    assert true; // will fail due to summary of the unresolved call from `unresolvedOneOfTwo`
}
