rule checkSanityParametric(method f) {
	require false;
	env e; calldataarg arg;
	f(e,arg);
	assert e.msg.value == 0;
}

invariant insane(uint x) x*0 > 0;
