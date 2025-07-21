// inapplicable for assert-tautology check.
rule trivial() {
    assert true;
}

rule trivialTautologyPasses(uint256 n) {
    if (n >= 1) {
        // tautology check should pass on this assert.
        assert n >= 1;
    }
    // this one is inapplicable.
    assert n >= 0;
}

rule trivialTautologyFails(uint256 n) {
    if (n >= 1) {
        // tautology check should fail on this assert.
        assert n >= 0;
    }
    // this one is inapplicable.
    assert n >= 0;
}

rule multipleAssertsPass(uint256 n) {
    assert n >= 0; // inapplicable
    require n >= 2;
    assert n >= 2; // tautology passes
    assert n >= 1; // tautology passes
}

rule multipleAssertsFailure(uint256 n) {
    assert n >= 0; // inapplicable
    require n >= 2;
    assert n >= 2; // tautology passes
    assert n >= 0; // tautology fails
}
