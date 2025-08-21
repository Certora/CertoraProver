methods {
    function returnsStruct() external returns C.S envfree;
    function returnsTuple() external returns bytes, int envfree;
}

function cvlReturnsStruct returns C.S {
    C.S s;
    return s;
}

function cvlReturnsTuple returns (uint[], bytes32) {
    uint[] uarr;
    bytes32 b;
    return (uarr, b);
}

// This one is to check the cases with return types with a tag that isn't bv256 in the end
function cvlReturnsBoolAndMathint returns (bool, mathint) {
    bool b;
    mathint m;
    return (b, m);
}

rule r {
    _ = returnsStruct();

    bytes by;
    by, _ = returnsTuple();

    _ = cvlReturnsStruct();

    bytes32 b;
    _, b = cvlReturnsTuple();

    _, _ = cvlReturnsBoolAndMathint();
    assert false, "should fail";
}
