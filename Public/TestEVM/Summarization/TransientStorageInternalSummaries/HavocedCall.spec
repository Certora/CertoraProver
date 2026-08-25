methods {
    function a() external returns uint256 envfree;
    function b() external returns uint256 envfree;
    // NONDET summary on an internal function that is called from the constructor and contains an
    // unresolved external call. The call's havoc writes the other contract's transient storage
    // inside the summarized body.
    function HavocedCall.f(address t) internal returns uint256 => NONDET;
}

invariant aEqualsB() a() == b();
