methods {
    function get_neg_two() external returns int256 envfree;
}

//Imply with side effects doing short circuit
rule binaryImply() {
    mathint j;
    assert 0 <= j && j <= 10 => (assert_uint256(j) <= 10);
}

//And with side effects doing short circuit
rule binaryAnd() {
    mathint j;
    require 0 <= j && j <= 10 && (assert_uint256(j) <= 10);
    assert j <= 10;
}

//Or with side effects doing short circuit
rule binaryOr() {
    int256 j;
    assert 0 > j || (assert_uint256(j) >= 0);
}

//Or with side effects doing short circuit violated on J=-1
rule binaryOrViolated() {
    int256 j;
    assert -1 > j || (assert_uint256(j) >= 0);
}

//Imply with side effects doing short circuit
rule binaryDivByZeroShortCircuit() {
    mathint j;
    assert 0 < j && j <= 10 => 7 / j <= 10;
}

//Imply with side effects doing short circuit
rule binaryDivByZeroFail() {
    mathint j;
    assert 0 <= j && j <= 10 => 7 / j <= 10;
}
