persistent ghost bool persi;
           ghost bool non_persi;

rule persistence(method f, env e, calldataarg args) {
    persi = true;
    non_persi = true;

    assert persi;
    assert non_persi;

    storage initial = lastStorage;

    persi = false;
    non_persi = false;

    assert !persi;
    assert !non_persi;

    f(e, args) at initial;

    // persistent ghost should not be affected by storage load
    assert !persi;
    assert non_persi;

    assert false, "test should end here";
}
