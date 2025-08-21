rule conflictWithFunctionNames {
	uint256 foo = 5; // foo is a name of two overloaded functions
	assert foo == 5, "sanity";
}
