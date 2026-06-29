using C as VAULT;

methods {
    function C._getNextTick() internal returns (int256) => CVL_nextTick();
}

ghost int256 NEXT_TICK_GHOST;

function CVL_nextTick() returns int256 {
    return NEXT_TICK_GHOST;
}

// For negative `minimaTick` (sign==false, abs>0), the source's inner IF-arm runs
// and `absorb()` returns true. The Solidity body makes this trivially true.
// The Prover currently reports this rule Violated due to StoragePathPruner
// incorrectly marking the IF-arm unreachable.
rule absorb_negative_returns_true(env e) {
    bool sign_pre = VAULT.branchData2.minimaTickSign;
    mathint abs_pre = to_mathint(VAULT.branchData2.minimaTickAbs);
    require !sign_pre && abs_pre > 0;
    require NEXT_TICK_GHOST < -abs_pre;  // force into encode path
    bool ret = absorb(e);
    assert ret, "for negative minimaTick the IF-arm runs and absorb must return true";
}
