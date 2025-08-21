using Basic as t;

rule should_pass_1(uint x, env e) {
	storage init = lastStorage;
	t.maybeRevert@withrevert(e, x);
	assert lastReverted => (init == lastStorage);
	assert (init != lastStorage) => !lastReverted;
}

/*
  tests that reverts work
*/
rule should_pass_2(env e) {
	 storage init = lastStorage;
	 t.maybeRevert(e, 3);
	 assert init == lastStorage;
}

rule should_fail_1(uint x, env e) {
	require(x != 0);
	storage init = lastStorage;
	t.maybeRevert@withrevert(e, x);
	// this is the wrong revert condition, if x == 3, then storage won't change
	assert x != 4 => lastStorage != init;
}

rule should_fail_2(uint x, env e) {
    storage init = lastStorage;
	t.maybeRevert@withrevert(e, x);
    // if x is zero, there will also be no change
	assert x != 3 => lastStorage != init;
}
