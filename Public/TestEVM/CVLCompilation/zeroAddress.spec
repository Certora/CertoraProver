rule zeroAddress(env e) {
	assert e.msg.sender == 0, "sanity";
}
