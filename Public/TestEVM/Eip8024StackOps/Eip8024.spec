/*
 * EIP-8024: backward compatible SWAPN, DUPN, EXCHANGE.
 *
 * Each rule pairs with one function of Eip8024.yul. The expected return values
 * come from the reference stack model in generate.py, which transcribes the
 * EIP-8024 semantics; the per-function comments in Eip8024.yul record which
 * instruction and immediate each one exercises.
 */

rule dupnDuplicatesTheEighteenthItem(uint256 x) {
    env e;
    uint256 result = dupn@withrevert(e, x);
    assert !lastReverted, "DUPN with a legal immediate and a deep enough stack must not halt";
    assert result == x, "DUPN 18 must duplicate the 18th stack item";
}

rule dupnDecodesImmediatesBelowTheReservedRange(uint256 x) {
    env e;
    uint256 result = dupnStopImmediate@withrevert(e, x);
    assert !lastReverted, "DUPN 145 must not halt on a 145-deep stack";
    assert result == x, "DUPN 145 (immediate 0x00) must duplicate the 145th stack item";
}

rule dupnImmediateIsNotExecuted(uint256 x) {
    env e;
    uint256 result = dupnSelfdestructImmediate@withrevert(e, x);
    assert !lastReverted, "DUPN 144 must not halt on a 144-deep stack";
    assert result == x, "DUPN 144 (immediate 0xff) must duplicate the 144th stack item";
}

rule swapnLiftsTheEighteenthItem(uint256 x, uint256 y) {
    env e;
    uint256 result = swapn@withrevert(e, x, y);
    assert !lastReverted, "SWAPN with a legal immediate and a deep enough stack must not halt";
    assert result == y, "SWAPN 17 must swap the top of the stack with the 18th item";
}

rule exchangeSwapsAShallowPair(uint256 x, uint256 y) {
    env e;
    uint256 result = exchange@withrevert(e, x, y);
    assert !lastReverted, "EXCHANGE with a legal immediate and a deep enough stack must not halt";
    assert result == y, "EXCHANGE 3 4 must swap the 4th and 5th stack items";
}

rule exchangeSwapsADeepPair(uint256 x, uint256 y) {
    env e;
    uint256 result = exchangeDeep@withrevert(e, x, y);
    assert !lastReverted, "EXCHANGE with a legal immediate and a deep enough stack must not halt";
    assert result == y, "EXCHANGE 1 20 must swap the 2nd and 21st stack items";
}

rule dupnHaltsOnAnIllegalImmediate() {
    env e;
    dupnJumpdestImmediate@withrevert(e);
    assert lastReverted, "DUPN must halt exceptionally on an immediate in the reserved range";
}
