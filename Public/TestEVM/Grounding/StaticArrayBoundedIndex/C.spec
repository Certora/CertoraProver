rule array0ForallRead(uint256 k) {
    require k < 50, "max length";
    require forall uint256 i. i < 50 => currentContract.array0[i] > 100, "all above 100";
    assert currentContract.array0[k] > 100;
}

rule array1ForallRead(uint256 k) {
    require k < 50, "max length";
    require forall uint256 i. i < 50 => currentContract.array1[i] > 100, "all above 100";
    assert currentContract.array1[k] > 100;
}
