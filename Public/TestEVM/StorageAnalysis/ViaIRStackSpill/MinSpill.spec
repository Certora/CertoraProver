// The storage hook makes this test a real regression guard: if the storage analysis fails
// (as it did before SpillLoadForwarder made via-IR stack-spill slots transparent), hook
// inlining throws a hard error instead of the rule silently passing on unsplit storage.

ghost mathint numTotalsStores;

hook Sstore _totals[KEY int256 k] uint256 v {
    numTotalsStores = numTotalsStores + 1;
}

rule spillSlotsDontBreakStorageAnalysis {
    env e;
    address owner;
    uint80 salt;
    int256[] keys;
    uint256[] shares;
    remove(e, owner, salt, keys, shares);
    assert true;
}
