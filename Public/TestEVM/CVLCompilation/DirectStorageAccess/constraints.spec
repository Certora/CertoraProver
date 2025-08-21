using Test as t;

rule type_constraints_unsigned_primitive {
    uint24 n = t.topLevel1;
    assert 0 <= n && n <= 2^24 - 1, "from type definition";
}

rule type_constraints_signed_primitive {
    int32 n = t.topLevelSigned;
    assert -(2^31) <= n && n <= 2^31 - 1, "from type definition";

    // make sure incorrectly-placed constraints did not force this value to be positive
    satisfy n < 0, "signed type";
}

// for sanity purposes
rule type_constraints_via_solidity {
    env e;
    assert typeConstraintsPreserved(e);
}
