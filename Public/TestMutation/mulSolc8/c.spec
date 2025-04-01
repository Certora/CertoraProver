methods {
	function mul(uint,uint) external returns (uint) envfree;
}

rule check(uint a, uint b) {
//	require a*b <= max_uint256;
	mathint c = mul@withrevert(a,b);
	assert !lastReverted <=> (c == a*b && a*b <= max_uint256);
}
