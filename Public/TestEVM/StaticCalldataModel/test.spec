rule auto_delegate {
	env e;
	uint a;
	uint b;
	assert myThing(e, a, b) == require_uint256(a + b);
}
