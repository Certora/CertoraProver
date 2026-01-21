methods{
    function setStorageValue(uint) external returns uint envfree;
}

hook Sstore currentContract.value uint256 hook_store_newValue (uint256 hook_store_oldValue) {
    require hook_store_oldValue > 1;
}

rule trigger_storage_set(uint val) {
    assert setStorageValue(val) == val + 1;
}
