/*
   The dead-storage-is-zero assumption may only constrain locations that were never accessed:
   two stores to the same location must not prune the path where the first stored value is
   non-zero.
 */
rule should_fail_1(uint k, uint v) {
	env e;
	setFive(e, k);
	setV(e, k, v);
	assert get(e, k) == 5;
}

rule should_fail_2(uint k) {
	env e;
	setFive(e, k);
	setV(e, k, 7);
	assert get(e, k) == 5;
}

rule should_pass_1(uint k, uint v) {
	env e;
	setV(e, k, v);
	assert get(e, k) == v;
}
