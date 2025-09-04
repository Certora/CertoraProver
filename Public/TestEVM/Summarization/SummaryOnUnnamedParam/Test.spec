methods {
    function internalUnimplementedFunctionWithUnnamedParam(uint256 myName) internal => CVL_implementation(myName);
}

function CVL_implementation(uint256 myNameInCVLParam) {
    assert myNameInCVLParam == 42 || currentContract.not42;
}

rule test {
    env e;
    uint x;
    test(e, x);
    assert true;
}
