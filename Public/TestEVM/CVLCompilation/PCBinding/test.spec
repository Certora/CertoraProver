ghost mapping(uint32 => uint16) selector_to_pc;

hook STATICCALL(uint g, address addr, uint argsOffset, uint argsLength, uint retOffset, uint retLength) uint rc {
	assert pc > 0, "implausible PC";
	assert selector_to_pc[selector] == 0;
	selector_to_pc[selector] = pc;
}


hook CALL(uint g, address addr, uint value, uint argsOffset, uint argsLength, uint retOffset, uint retLength) uint rc {
	assert pc > 0, "implausible PC";
	assert selector_to_pc[selector] == 0;
	selector_to_pc[selector] = pc;
}

rule the_rule {
	env e;
	uint x;
	selector_to_pc[0x60ff085f] = 0; // different selector
	selector_to_pc[0x3ef4f4ca] = 0; // world selector
	hello(e, x);
	assert selector_to_pc[0x3ef4f4ca] != 0, "didn't hit world";
	assert selector_to_pc[0x60ff085f] != 0, "didn't hit different";
	assert selector_to_pc[0x3ef4f4ca] != selector_to_pc[0x60ff085f], "same pc for diff selectors";
}
