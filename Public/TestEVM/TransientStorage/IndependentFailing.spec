methods {
    function test(bytes32, uint, bool) external returns (bytes32) envfree;
}

rule canAccessNormalStorage {
    uint x;
    bytes32 slot;
    bool b;
    test(slot, x, b);
    assert currentContract.normalStorageData[x] == b;
}
