// Using the CVL string variable as key in a mapping, then load the same key in Solidity
// Same tests as mapping_nameToAddress_load_in_solidity_with_cvl_string, just using a mapping where the value is of type Uint instead of address.
rule mapping_nameToUint_load_in_solidity_with_cvl_string{
	env e;
	string r1 = "String";
	require(nameToUint(e, getKey(e, r1)) != 0);
	assert myUint(e) != 0, "Should succeed";
}