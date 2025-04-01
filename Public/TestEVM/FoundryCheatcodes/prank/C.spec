methods {
    function L.assertEq(address a, address b) internal => asserEqSummary(a, b);
}

function asserEqSummary(address a, address b) {
    assert a == b;
}

rule r {
    env e;
    testPrank(e);
    assert true;
}
