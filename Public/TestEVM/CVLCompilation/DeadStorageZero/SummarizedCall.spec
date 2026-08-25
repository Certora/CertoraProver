methods {
    function a() external returns uint256 envfree;
    function b() external returns uint256 envfree;
    // NONDET summary on an internal function that is called from the constructor and contains an
    // unresolved external call, so the call's havoc writes read-tracker variables inside the body.
    function Caller.f(address t) internal returns uint256 => NONDET;
}

invariant aEqualsB() a() == b();
