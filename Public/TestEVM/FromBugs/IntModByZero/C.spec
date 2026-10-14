ghost uint256 ghostPrice;

methods {
    function calc(uint256, address) external returns (uint256) envfree;
    function _.ceilDiv(uint256 a, uint256 c) internal => modCVL(a, c) expect uint256;
    function _.getPrice() external => ghostPrice expect uint256;
}

// A mathint remainder with no guard on the divisor.
function modCVL(uint256 a, uint256 c) returns uint256 {
    mathint x = a;
    return require_uint256(x % c);
}

// 10^13 % 10^6 == 0, so the divisor reaching modCVL folds to the constant 0. The rule must reach the
// division-by-zero assert that cvl generates for `x % c`, not an internal error.
rule modByConstantZero(address oracle) {
    require ghostPrice == 10^13;

    uint256 result = calc(10^6, oracle);

    assert result == 0;
}

// Control: the same summary with a divisor that folds to a non-zero constant still computes.
rule modByConstantNonZero(address oracle) {
    require ghostPrice == 10^13 + 7;

    uint256 result = calc(10^6, oracle);

    assert result == 10^6 % 7;
}
