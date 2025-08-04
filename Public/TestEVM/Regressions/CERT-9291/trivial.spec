rule trivial(method f) {
	env e;
	calldataarg arg;
	f(e, arg);
	assert true;
}
