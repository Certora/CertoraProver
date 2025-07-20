methods {
    function TransientTest.maybeAccess(bool) external envfree;
}

ghost bool allTStore;
hook ALL_TSTORE(uint loc, uint v) {
    allTStore = true;
}

rule allTStoreHookWorks() {
    require !allTStore;
    bool b;
    maybeAccess(b);
    assert allTStore <=> b;
}
