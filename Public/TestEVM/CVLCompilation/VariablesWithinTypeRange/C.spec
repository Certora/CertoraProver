methods {
    function foo(uint24 x) external envfree;
    function foo(int16 x) external envfree;
    function foo(bytes4 x) external envfree;
    function foo(C.E x) external envfree;
    function foo(C.S x) external envfree;
    function foo(C.T x) external envfree;
    function foo(bytes29[] x) external envfree;
    function foo(C.S[4] x) external envfree;
}

rule uint24Arg(uint24 x) {
    foo@withrevert(x);
    assert !lastReverted, "foo should not have reverted";
}
rule uint24Var {
    uint24 x;
    foo@withrevert(x);
    assert !lastReverted, "foo should not have reverted";
}

rule int16Arg(int16 x) {
    foo@withrevert(x);
    assert !lastReverted, "foo should not have reverted";
}
rule int16Var {
    int16 x;
    foo@withrevert(x);
    assert !lastReverted, "foo should not have reverted";
}

rule bytes4Arg(bytes4 x) {
    foo@withrevert(x);
    assert !lastReverted, "foo should not have reverted";
}
rule bytes4Var {
    bytes4 x;
    foo@withrevert(x);
    assert !lastReverted, "foo should not have reverted";
}

rule EArg(C.E x) {
    foo@withrevert(x);
    assert !lastReverted, "foo should not have reverted";
}
rule EVar {
    C.E x;
    foo@withrevert(x);
    assert !lastReverted, "foo should not have reverted";
}

rule SArg(C.S x) {
    foo@withrevert(x);
    assert !lastReverted, "foo should not have reverted";
}
rule SVar {
    C.S x;
    foo@withrevert(x);
    assert !lastReverted, "foo should not have reverted";
}

rule TArg(C.T x) {
    foo@withrevert(x);
    assert !lastReverted, "foo should not have reverted";
}
rule TVar {
    C.T x;
    foo@withrevert(x);
    assert !lastReverted, "foo should not have reverted";
}

// TODO: These should pass
rule dynamicArrArg(bytes29[] x) {
    foo@withrevert(x);
    assert !lastReverted, "foo should not have reverted";
}
rule dynamicArrVar {
    bytes29[] x;
    foo@withrevert(x);
    assert !lastReverted, "foo should not have reverted";
}

rule staticArrArg(C.S[4] x) {
    foo@withrevert(x);
    assert !lastReverted, "foo should not have reverted";
}
rule staticArrVar {
    C.S[4] x;
    foo@withrevert(x);
    assert !lastReverted, "foo should not have reverted";
}
