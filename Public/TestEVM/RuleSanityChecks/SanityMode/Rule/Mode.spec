methods {
    function alwaysTrue(uint n) external returns (bool) envfree;
    function val() external returns (uint) envfree;
    function condResult() external returns (bool) envfree;
    function alwaysRevert() external returns (bool) envfree;
}
rule Redundancy {
	require true;
	uint i;
	require i == 3;
	assert i == 3;
}

rule NotRedundantParametric(method f) filtered { f-> f.selector == sig:valSmallerThanSix().selector || f.selector == sig:valExactlyFive().selector } {
    require(val() > 4); // Redundant for valSmallerThanSix(), not redundant for valExactlyFive(). Thus, overall not redundant.
    require(val() < 6);
    env e;
    calldataarg args;
    f(e, args);
    assert condResult();
}

rule Tautology {
	require true;
	assert true;
}

rule Vacuity {
    uint x;
    require(x > 1);
    require(x < 1);
    assert x == 10;
}
