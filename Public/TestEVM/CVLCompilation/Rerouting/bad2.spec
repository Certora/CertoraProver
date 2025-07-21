methods {
    function Test.boop(uint[] storage x) internal returns (bool) => IsALibrary.foo(x);
}

rule trivial(method f) {
    env e;
    calldataarg args;
    f(e, args);
    assert true;
}