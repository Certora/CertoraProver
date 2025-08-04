
rule imprecision(mathint a, mathint b) {
	require b >= 0;
	assert a + b >= a;
}