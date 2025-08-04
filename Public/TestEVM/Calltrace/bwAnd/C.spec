methods {
	function bwAnd(uint, uint) external returns (uint) envfree;
}

rule imprecision(uint a) {
	uint b = require_uint256(a + 1);
	assert bwAnd(a, b) % 2 == 0;
}