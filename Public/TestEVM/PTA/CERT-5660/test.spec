rule trivial(method f) {
	env e;
	calldataarg arg;
	f(e, arg);
	assert true;
}

rule badUpdate_fine(uint x) {
	env e;
	uint[] trg;
	uint newLength;
	require(newLength <= trg.length);
	badUpdate(e, trg, newLength);
	assert true;
}
