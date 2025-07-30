
rule imprecision(mathint a, mathint b) {
	require a > 0;
	require b > 0;
	require a != 5;
	assert a ^ b != 5;
}