methods {
    function Test.foo(uint[] storage x) internal returns (uint) => NotALibrary.foo(x);
    function Test.bar(uint[] storage x) internal returns (uint) => IsALibrary.foo(x, x);
    function Test.baz(uint[] storage x) internal returns (uint) with(env e) => IsALibrary.foo(x);

    function Test.gorp(uint[] storage x, uint y) internal returns (uint) => IsALibrary.bar(x, y + 1);

    function Test.boop(uint[] storage x) internal returns (bool) => IsALibrary.foo(x);

	function Test.beep(uint[] storage x) internal returns (uint) => IsALibrary.missing(x);
}

rule trivial(method f) {
    env e;
    calldataarg args;
    f(e, args);
    assert true;
}
