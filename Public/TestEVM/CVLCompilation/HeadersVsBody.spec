rule twoEnvsInHeader(env e0, env e1) {
	require e1.block.number == require_uint256(e0.block.number+1);
	assert e0.block.number == e1.block.number, "sanity";
}

rule twoEnvsInBody {
	env e0;
	env e1;
	require e1.block.number == require_uint256(e0.block.number+1);
	assert e0.block.number == e1.block.number, "sanity";
}
