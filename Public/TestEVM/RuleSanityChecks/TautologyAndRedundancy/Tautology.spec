methods {
	function c() external returns bool envfree;
}

// the assert is tautologous only with respect to the setVarToTrue method, so we do not consider it as a tautology in
// general. sanity should fail here because we do an assertion structure check for the disjunction
rule PartiallySanityFail(method f) filtered {f-> f.selector == sig:setVarToTrue().selector || f.selector == sig:setVarToFalse().selector} {
    bool b;
    env e;
    calldataarg args;
    require b;
    f(e, args);
    assert b || c(e);
}

rule NestedAssert {
    mathint topVar;
	bool b;
	env e;
	require true;
	if (topVar == 0) {
	    int ifVar;
	    if (true) {
	        assert(true);
	        int ifIfVar = 1;
	        assert(ifIfVar == 1);
	        require(ifVar > 4);
	        if (b) {
	            // vacuously true
	            setVarToTrue(e);
	            assert(c(e) == true);
	        }
	        else {
	            // not vacuously true
	            uint fund;
	            require(fund == 1);
	            assert(fund > 0);
	        }
	    }
	}
	else {
	    mathint elseVar;
        assert (topVar * elseVar != 0 || elseVar == 0);
	}
	assert (true);
}

// make sure sanity is able to deal with multiple parametric functions
rule twoParametric(method f, method g) {
    bool b;
    env e;
    calldataarg args;
    require (b == true);
    f(e, args);
    g(e, args);
    // make sure sanity is able to deal with similar asserts in the same scope
    assert b || c(e);
    assert b || c(e);
}
