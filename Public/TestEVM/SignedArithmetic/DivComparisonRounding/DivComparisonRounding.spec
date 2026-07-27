methods {
    function quotient(int256) external returns (int256) envfree;
    function solidityAssertion(int256) external envfree;
}

/*
 * This must fail with x == -1: Solidity signed division rounds toward zero.
 * It validates the concrete contract behavior independently of CVL division.
 */
rule solidity_semantics_has_counterexample(int256 x) {
    require x < 0;
    int256 result = quotient(x);
    assert result < 0;
}

/*
 * This is the CVL mirror of the Solidity assertion.  It must also fail with
 * x == -1, but the divLt post-interval rewrite currently changes
 *
 *     x / 2 < 0
 *
 * into
 *
 *     x < 2 * 0
 *
 * and therefore incorrectly proves the assertion from x < 0.
 */
rule cvl_mirror_must_have_counterexample(int x) {
    require x < 0;
    assert x / 2 < 0;
}

/*
 * A direct, non-symbolic witness for the intended Solidity semantics.
 */
rule quotient_of_minus_one_is_zero() {
    int256 result = quotient(-1);
    assert result == 0;
}
