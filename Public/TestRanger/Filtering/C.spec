definition isToExclude(method f) returns bool = f.selector == sig:bar().selector;

invariant inv() currentContract.n < 5 filtered { f -> !isToExclude(f) }

rule r() {
    method f;
    env e;
    calldataarg args;
    f(e, args);
    assert currentContract.n < 5;
}
