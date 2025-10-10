methods {
	function mul(uint, uint) external returns (uint) envfree;
}

rule imprecision(uint a, uint b) {
	uint m = mul(a, b);
	require a != 0;
	require b != 0;
	require a > m;
	require b > m;
	assert false;
}