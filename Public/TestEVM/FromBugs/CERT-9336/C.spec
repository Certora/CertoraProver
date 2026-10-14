using Callee as callee;

// `Callee` is pinned at this address by the conf, and the dispatcher routes the call in `callIt`
// over the implementations in the scene. Passing the literal therefore has to land on `Callee`
// itself: if the argument were bound to anything else, the dispatcher would find no match, the
// return value would be arbitrary, and these rules would fail.
methods {
    function _.value() external => DISPATCHER(true);
}

definition CALLEE_ADDR() returns address = 0xce4604a000000000000000000000000a;

rule literalReachesThePinnedContract(env e) {
    uint256 direct = callee.value(e);
    uint256 viaLiteral = callIt(e, 0xce4604a000000000000000000000000a);
    assert viaLiteral == direct;
}

rule definitionReachesThePinnedContract(env e) {
    uint256 direct = callee.value(e);
    uint256 viaDefinition = callIt(e, CALLEE_ADDR());
    assert viaDefinition == direct;
}

rule variableReachesThePinnedContract(env e) {
    address a = CALLEE_ADDR();
    uint256 direct = callee.value(e);
    uint256 viaVariable = callIt(e, a);
    assert viaVariable == direct;
}
