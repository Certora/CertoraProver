rule call_with_address {
	env e;
	address[] some_addresses;
	require(some_addresses.length < 4);
	address[] returned = doSomethingWithERC(e, some_addresses);
	assert some_addresses.length / 2 == to_mathint(returned.length);
	assert returned.length == 0 || returned[0] == some_addresses[0];
}


rule call_with_address_static {
	env e;
	address[2] some_addresses;
	address[1] returned = doSomethingWithStaticERC(e, some_addresses);
	assert returned[0] == some_addresses[1];
}
