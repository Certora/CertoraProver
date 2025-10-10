methods {
	function _.push(uint8) external => DISPATCHER(true);
	function _.unpacked(uint256) external => DISPATCHER(true);
}

rule simple_create {
	env e;
	uint8 param;
	assert deployAndPush(e, param) == param;
}
