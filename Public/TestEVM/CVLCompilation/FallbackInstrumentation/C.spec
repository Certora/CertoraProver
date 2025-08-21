methods {
    function counter() external returns uint256 envfree;
}

rule receiveFuncReached(method f) filtered { f -> f.isFallback } {
    env e;
    calldataarg args;
    require counter() == 0;
    f(e, args);
    uint256 c = counter();
    assert c != 0xBAD;
}

rule fallbackFuncReached(method f) filtered { f -> f.isFallback } {
    env e;
    calldataarg args;
    require counter() == 0;
    f(e, args);
    uint256 c = counter();
    assert c != 0xDAB;
}
