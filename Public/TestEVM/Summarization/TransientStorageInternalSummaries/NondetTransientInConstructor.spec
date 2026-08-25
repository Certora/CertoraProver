methods {
    function a() external returns uint256 envfree;
    function b() external returns uint256 envfree;
    // NONDET summary applied to an internal function that writes transient storage and is called
    // from the constructor. The invariant's "Induction base: After the constructor" rule is where
    // the summary gets applied to the constructor's body.
    function TransientInConstructor.f() internal returns uint256 => NONDET;
}

invariant aEqualsB() a() == b();
