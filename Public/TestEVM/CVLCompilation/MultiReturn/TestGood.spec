function returnsLiterals() returns (uint, uint[], int32[]) {
	return (3, [ 1, 3 ], [ -1, 3 ]);
}

rule returnsLiteralWorks {
	uint k;
	uint[] array;
	int32[] signedArray;
	k, array, signedArray = returnsLiterals();
	assert true;
}
