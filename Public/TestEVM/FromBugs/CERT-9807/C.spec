methods {
    function signedAdd() external returns (int256) envfree;
    function signedSub() external returns (int256) envfree;
}

// Both asserts are violated (a and b can both be zero). What the test guards is that the run
// reaches a verdict at all instead of erroring out while the bitvector query is being built.
rule signedAddIsNonZero() {
    assert signedAdd() != 0;
}

rule signedSubIsNonZero() {
    assert signedSub() != 0;
}
