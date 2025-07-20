methods {
    function b() external returns bool envfree;
    function c() external returns bool envfree;
    function x() external returns uint256 envfree;
    function y() external returns uint256 envfree;
    function z() external returns uint256 envfree;
    function foo() external envfree;
}


/**
 * Passing rule, timeout on redundant-requires check.
 */
rule passingWithOneTimeout(mathint n, mathint m, mathint k, bool _b) {
    if (n*m*k != 0) {
        require false;
        assert n*n*n + m*m*m != k*k*k;
    }
    require _b;
    assert _b;
}

/**
 * Vacuity fails, timeout on redundant-requires check.
 */
rule insaneWithOneTimeout(mathint n, mathint m, mathint k) {
    if (n*m*k != 0) {
        require false;
        assert n*n*n + m*m*m != k*k*k;
    }
    require false;
    assert false;
}

/**
 * Sanity timeout on [foo()], other functions fails sanity.
 */
rule parametricOneTimeout(method f) {
    require b();
    if (f.selector == sig:foo().selector && c()) {
        require false;
        env e;
        calldataarg args;
        f(e, args);
    }
    assert b();
}
