methods {
    function assertEq(uint a, uint b) internal => asserEqSummary(a, b);
}

function asserEqSummary(uint a, uint b) {
    assert a == b;
}

rule r {
    env e;
    uint n = testWarp(e, false, 0);
    testWarp(e, true, n);
    assert true;
}
