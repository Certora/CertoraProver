methods {
    function contractFunReturnsTuple() external returns (bool, int) envfree;
}

function doesNotReturnEnough(uint x) returns (uint, bool) {
	return x;
}

function returnsTooMany(uint z) returns uint {
	return (z, true);
}

function returnsWrongType(uint256 k) returns (bool, int256) {
	return (false, k);
}

function returnsLiterals() returns (uint, uint[], int32[]) {
	return (3, [ 1, 3 ], [ -1, 3 ]);
}

function returnsCorrect() returns (int256, bool) {
	return (-1, true);
}

rule expectsTooMany() {
	int256 k;
	bool b;
	uint noRhs;
	b, k, noRhs = returnsCorrect();
	assert true;
}

rule usesBottom() {
    _ = returnsCorrect();
	assert true;
}

rule expectsTooFew() {
	int256 k;
	k = returnsCorrect();
	assert true;
}

rule incorrectLhs {
	bool k;
	bytes5[] array;
	int signedArray;
	k, array, signedArray = returnsLiterals();
	assert true;
}

rule expectsTooManyContractFun() {
	int256 k;
	bool b;
	uint noRhs;
	b, k, noRhs = contractFunReturnsTuple();
	assert true;
}

rule usesBottomContractFun() {
	_ = contractFunReturnsTuple();
	assert true;
}

rule expectsTooFewContractFun() {
	int256 k;
	k = contractFunReturnsTuple();
	assert true;
}
