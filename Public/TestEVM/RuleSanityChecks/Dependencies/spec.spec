methods {
    function x() external returns bool envfree;
    function y() external returns bool envfree;
}

invariant alwaysTrue() true;


rule one(uint256 areYouTheOne) {
    require areYouTheOne == 1;
    assert areYouTheOne == 1;
}

rule tautologousAssertRedundantRequire() {
    require true;
    assert true;
}

rule notTautologousAssertRedundantRequire(uint256 n) {
    require n == 1;
    require true;
    assert n == 1;
}

rule vacuousFermat(uint a, uint b, uint c) {
    require a == a;
    require a > b;
    require a < b;
    assert (a > 0 && b > 0 && c > 0) => (a*a*a + b*b*b != c*c*c);
}
