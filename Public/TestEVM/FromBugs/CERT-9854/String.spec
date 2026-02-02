// Compares the contract name in a CVL variable with the actual type(contract).name in Solidity,
// comparison happening in CVL.
rule plain_string_comparison_in_cvl{
	env e;
	string r1 = "String";
	string r2 = getContractNameTest(e);
	assert r1 == r2, "Should succeed";
}

// Compares the contract name in a CVL variable with the actual type(contract).name in Solidity,
// comparison happening in Solidity.
rule plain_string_comparison_in_solidity{
	env e;
	string r1 = "String";
	assert compareWithContractName(e, r1), "Should succeed";
}

// Using the CVL string variable as key in a mapping, then load the same key in Solidity
rule mapping_nameToAddress_load_in_solidity_with_cvl_string{
	env e;
	string r1 = "String";
	require(nameToAddress(e, r1) != 0);
	assert myAddress(e) != 0, "Should succeed";
}

// Using the CVL string variable as key in a mapping, then load the same key from CVL again
rule mapping_nameToAddress_load_in_cvl{
	env e;
	string r1 = "String";
	require(nameToAddress(e, r1) != 0);
	assert nameToAddress(e, r1) != 0, "Should succeed";
}

// Creating the string in Solidity, passing it to a CVL variable where it is used as key, the load then happens via a solidity key
rule mapping_nameToAddress_load_in_solidity_with_solidity_string{
	env e;
	string r1 = myName(e);
	require(nameToAddress(e, r1) != 0);
	assert myAddress(e) != 0, "Should succeed";
}

// String remains fully on the solidity side.
rule mapping_nameToAddress_fully_in_soldity{
	env e;
	require(myAddressNotZero(e));
	assert myAddress(e) != 0, "Should succeed";
}

// Using the CVL string variable as key in a mapping, then load the same key in Solidity
// Same tests as mapping_nameToAddress_load_in_solidity_with_cvl_string, just using a mapping where the value is of type Uint instead of address.
rule mapping_nameToUint_load_in_solidity_with_cvl_string{
	env e;
	string r1 = "String";
	require(nameToUint(e, r1) != 0);
	assert myUint(e) != 0, "Should succeed";
}

// Testing if the issue is related to string or not, by avoiding any usages of a CVL string
rule mapping_uintToAddress_key_in_cvl_then_key_in_solidity{
	env e;
	uint256 r1 = 1;
	require(uintToAddress(e, r1) != 0);
	assert uintToAddressAt1(e) != 0, "Should succeed";
}

rule mapping_nameToUint_load_in_solidity_with_cvl_string_empty{
	env e;
	string r1 = "";
	require(nameToUint(e, r1) != 0);
	assert myUint(e, r1) == myUint(e, r1), "Should succeed";
}

rule mapping_nameToUint_load_in_solidity_with_cvl_string_32bytes{
	env e;
	string r1 = "This string is exactly 32 bytes!";
	assert myUint(e, r1) == myUint(e, r1), "Should succeed";
}

rule mapping_nameToUint_load_in_solidity_with_cvl_string_64bytes{
	env e;
	string r1 = "This string is exactly 64 bytes long and requires more space!!!!!";
	assert myUint(e, r1) == myUint(e, r1), "Should succeed";
}

rule mapping_nameToUint_load_in_solidity_with_cvl_string_40bytes{
	env e;
	string r1 = "This string has exactly 40 bytes here!";
	assert myUint(e, r1) == myUint(e, r1), "Should succeed";
}

rule mapping_nameToUint_load_in_solidity_with_cvl_string_havocced{
	env e;
	string r1;
	require(r1.length <= 150); // To avoid unwinding condition in hashing bound length
	assert myUint(e, r1) == myUint(e, r1), "Should succeed";
}
