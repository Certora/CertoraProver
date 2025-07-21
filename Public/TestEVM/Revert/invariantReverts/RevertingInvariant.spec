methods {
    function limits(uint256) external returns uint256 envfree;
    function getLimit(uint256) external returns uint256 envfree;
    function limitsLength() external returns uint256 envfree;
    function MAX_LIMIT() external returns uint256 envfree;
}

// this invariant reverts in the pre-state for the interesting case of index == i == old(limits.length)
// therefore missing the counterexample
// but at least we can warn that it always reverts after the constructor
invariant limitInRangeBad(uint256 i) limits(i) <= MAX_LIMIT();

// this would be the better way to implement the desired invariant, it catches the violation
invariant limitInRangeGood(uint256 i) i < limitsLength() => limits(i) <= MAX_LIMIT();

// this would be the better way to implement the desired invariant, it catches the violation
invariant limitInRangeGood2(uint256 i) i < limitsLength() => getLimit(i) <= MAX_LIMIT();

rule showBadCase() {
    uint i = currentContract.limitsLength();
    uint biggerThanMax = 101;
    env e;
    setLimit(e, i, biggerThanMax);
    assert limits(i) > MAX_LIMIT();
}
