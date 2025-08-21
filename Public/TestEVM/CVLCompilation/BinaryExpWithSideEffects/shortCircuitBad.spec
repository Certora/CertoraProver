methods {
    function get_neg_two() external returns int256 envfree;
}

rule quantifierWithSideEffect() {
    // casting in forall
    mathint y;
    assert (forall uint256 x. x > 0) => assert_uint256(y) > 0;
}
