methods {
	function solDiv(int, int) external returns (int) envfree;
}

function checkDivBody(int a, int b) {
	int solRes = solDiv(a, b);
	mathint cvlRes = a / b;
	assert solRes == cvlRes;
}

rule checkDiv(int a, int b) {
	checkDivBody(a, b);
}

rule checkDiv1(int a, int b) {
	require a > 100;
	checkDivBody(a, b);
}

rule checkDiv2(int a, int b) {
	require a < -100;
	checkDivBody(a, b);
}

rule checkDiv3(int a, int b) {
	require b > 100;
	checkDivBody(a, b);
}

rule checkDiv4(int a, int b) {
	require b < -100;
	checkDivBody(a, b);
}

rule checkConstNumerator(int b) {
	checkDivBody(7, b);
	checkDivBody(-7, b);
}

rule checkConstDenominator(int a) {
	checkDivBody(a, 7);
	checkDivBody(a, -7);
}

rule CheckConstants() {
	checkDivBody(10, 3);
	checkDivBody(10, -3);
	checkDivBody(-10, 3);
	checkDivBody(-10, -3);
}
