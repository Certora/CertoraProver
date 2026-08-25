methods {
    function quotient(int256) external returns (int256) envfree;
}

/*
 * Division rounds toward zero, so for x = -1 the quotient is 0 and the assertion
 * has a counterexample. This checks the compiled Solidity semantics.
 */
rule solidityDivHasCounterexample(int256 x) {
    require x < 0;
    int256 result = quotient(x);
    assert result < 0;
}

/*
 * The CVL mirror of the rule above, and the one that the divLt post-intervals
 * rewrite applies to: `x / 2 < 0` may only be rewritten into `x < 2 * 0` when the
 * dividend is non-negative, so this rule must keep its x = -1 counterexample.
 */
rule cvlDivHasCounterexample(int x) {
    require x < 0;
    assert x / 2 < 0;
}

/*
 * The rewrite stays available for a non-negative dividend, where truncation and
 * floor division agree.
 */
rule nonNegativeDividendVerifies(int x) {
    require x >= 0;
    assert x / 2 >= 0;
}
