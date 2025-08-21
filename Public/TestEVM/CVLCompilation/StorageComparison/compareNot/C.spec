methods {
	function setM(uint, uint) external envfree;
}

rule check1(uint i, uint v1, uint v2) {
	require v1 != v2;
	storage init = lastStorage;

    setM(i, v1) at init;
    storage after1 = lastStorage;

    setM(i, v2) at init;
    storage after2 = lastStorage;

    assert(after1[currentContract] != after2[currentContract]);
}

rule check2(uint i, uint v) {
	require currentContract.m[i] != v;
	storage init = lastStorage;

    setM(i, v) at init;
    storage after = lastStorage;

    assert(after[currentContract] != init[currentContract]);
}