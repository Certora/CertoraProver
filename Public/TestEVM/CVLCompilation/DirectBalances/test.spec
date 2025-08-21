using Test as t;

methods {
	function _.receive() external => NONDET;
}

rule should_pass_1(address a, address b, uint256 amount) {
	assert nativeBalances[a] >= 0 && nativeBalances[a] <= max_uint256;
	assert nativeBalances[b] >= 0 && nativeBalances[b] <= max_uint256;
	assert nativeBalances[t] >= 0 && nativeBalances[t] <= max_uint256;

	env e;
	require(a != b);
	require(a != t);
	require(b != t);
	require(e.msg.value == amount);
	require(e.msg.sender == a);
	uint256 aBefore = nativeBalances[a];
	uint256 bBefore = nativeBalances[b];
	uint256 tBefore = nativeBalances[t];
	transfer(e, b);
	uint256 aAfter = nativeBalances[a];
	uint256 bAfter = nativeBalances[b];
	uint256 tAfter = nativeBalances[t];
	assert assert_uint256(aBefore - e.msg.value) == aAfter;
	assert assert_uint256(bBefore + e.msg.value) == bAfter;
	assert tAfter == tBefore;
}


rule should_fail_1(address a, address b, uint256 amount) {
	env e;
	require(e.msg.value == amount);
	require(e.msg.sender == a);
	uint256 aBefore = nativeBalances[a];
	uint256 bBefore = nativeBalances[b];
	uint256 tBefore = nativeBalances[t];
	transfer(e, b);
	uint256 aAfter = nativeBalances[a];
	uint256 bAfter = nativeBalances[b];
	uint256 tAfter = nativeBalances[t];
	assert assert_uint256(aBefore - e.msg.value) == aAfter;
	assert assert_uint256(bBefore + e.msg.value) == bAfter;
	assert tAfter == tBefore;
}
