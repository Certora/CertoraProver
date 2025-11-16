methods {
	// method block to "ensure" PTA (and thus summaries) succeed
	function _.toResolve() external => 5 expect uint ALL;
}


rule my_test(bool a, bool b) {
	env e;
	assert entry(e, a, b) == 5;
}
