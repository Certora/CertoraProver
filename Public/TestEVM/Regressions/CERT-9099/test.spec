rule expected_to_pass(env e) {
    address a;
    uint x;
    bool y;
    assert manualCompare(e, a, x, y);
}

rule expected_to_fail(env e) {
    calldataarg a;
    assert manualCompare(e, a);
}