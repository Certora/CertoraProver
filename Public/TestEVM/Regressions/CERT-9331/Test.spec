methods {
    function generator() internal returns (Test.S memory) => CVL_generator();
}
function CVL_generator() returns Test.S {
    Test.S res;
    require(res.x[0] == 1234);
    return res;
}
rule test() {
	env e;
	calldataarg arg;
	Test.S res = test(e, arg);
    assert res.x[0] == 1234;
}
