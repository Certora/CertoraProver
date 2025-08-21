rule calldatasizeIsSigned {
	env e;
	require e.msg.value == 0;
	uint256 start = certoraProposalStart@withrevert(e,1);
	assert !lastReverted, "Not expected to revert even if there is a signed check of calldatasize";
}

rule functionsDoesNotRevert(method f) { // limiting it in a type-checker bypassing way
	env e;
	require e.msg.value == 0;
	calldataarg arg;
	require !f.isFallback;
	require f.selector == sig:withManyArgs(uint256,uint256,uint256).selector /*(13bb0fe5)*/ ;
	f@withrevert(e,arg);
	assert !lastReverted, "Not expected to revert even if there is a signed check of calldatasize";
	/* Wonder what should we really expect to happen here.
		With ABIEncoderV2 (calldatasize is signed):
		We get a counterexample where calldatasize = 2^255.
		We do not have type info so we can't say it's at least 4+32*3.
		Currently we only assume it's not less than (unsigned check!) 4.
		// UPDATE: New implementation for parametric methods instruments `arg` with awareness to calldatasize, so we can make sure we only check for real reverts and not calldata-encoding related reverts. Is this a bug or a feature..?
		// So before, this rule failed (with some small calldatasize such as 4), and now it passes (calldatasize must be > 0x63)

		Without ABIEncoderV2 (calldatasize is unsigned):
		We fail because it can be too small - more than 4, but less than what we require.
		// UPDATE: This is no longer the case - we have the args, we can determine the minimal calldata size.
		Signed inequality checks consider the constant that we compare to, and its sign.
		*Probably* no degradation compared to the handling of calldatasize as unsigned in CVL too - this rule is expected to fail.
	 */
}
