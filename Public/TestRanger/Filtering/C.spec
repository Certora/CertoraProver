invariant inv() currentContract.n < 5 filtered { f -> f.selector != sig:bar().selector }

rule r() {
    method f;
    env e;
    calldataarg args;
    f(e, args);
    assert currentContract.n < 5;
}
