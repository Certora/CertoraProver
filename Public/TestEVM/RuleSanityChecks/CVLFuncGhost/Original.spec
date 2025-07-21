/* The tool currently do not support sanity checks regarding statements inside CVL functions
and ghosts. For example, here when importing [my_function] from [Imported.spec], the require inside
the imported function is not sanity-checked to be redundant */

import "./Imported.spec";

methods {
	function GetC.c() internal returns uint => easier_multiplication(3,2);
}

ghost noParamsWithAxioms() returns uint {
    axiom noParamsWithAxioms() > 10;
}

// use imported CVL function and ghost
rule CVLUtils {
    env e;
    mathint topVar;
	if (topVar == 0) {
	    require false;
	    assert c(e) == 5;
	} else {
        assert easier_multiplication(3,2) == 6;
	}
	assert noParamsWithAxioms() > 4;
}

rule CVLFuncRevert {
	assert easier_multiplication(1000,1000) == 1000000;
}
