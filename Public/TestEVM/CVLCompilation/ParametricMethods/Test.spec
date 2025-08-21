rule parametric_method_param(method f) {
    uint256 x;
    env e;
    calldataarg args;
    // make sure method struct gets populated and leads to correct branch
    // being called when method is param
    if (f.selector == sig:foo(uint256).selector) {
        assert foo(e, x) == assert_uint256(x + 5);
    } else {
        f@withrevert(e, args);
        assert false;
    }
}

rule parametric_method_variable {
    method f;
    uint256 x;
    env e;
    calldataarg args;
    // make sure method struct gets populated and leads to correct branch
    // being called when method is variable
    if (f.selector == sig:foo(uint256).selector) {
        assert foo(e, x) == assert_uint256(x + 5);
    } else {
        f@withrevert(e, args);
        assert false;
    }
}

rule foo_pruned_from_fallback_case {
    method f;
    uint256 x;
    env e;
    calldataarg args;
    // make sure that the foo branch gets pruned from the fallback
    // case (targeting the disjoint sighash calculation)
    if (f.selector == sig:foo(uint256).selector) {
        assert false;
    } else {
        f@withrevert(e, args);
        assert true;
    }
}

rule foo_no_invocation (method f) filtered { f -> f.selector == sig:foo(uint256).selector } {
    bool x;
    if (f.selector == sig:foo(uint256).selector) {
        x = true;
    } else {
        x = false;
        env e;
    }
    assert x;
}
