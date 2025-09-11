ghost uint256 cachedIndex;

rule buggyGhost(uint256 assetId) {
    env e;

    cachedIndex = currentContract.i;
    uint256 before = cachedIndex;

    storage init_state = lastStorage;

    assert before == cachedIndex;

    bar(e) at init_state;
    assert before == cachedIndex;
}
