rule check_encoding(address target, bool grant, bytes4 what, address who) {
	env e;
	Test.PermissionChangeRequest req;
	address whoInBuffer = req.changes[0].permission.who;
	require(req.target == target);
	require(req.changes.length == 1);
	require(req.changes[0].grant == grant);
	require(req.changes[0].permission.what == what);
	require(whoInBuffer == who);
	bytes32 throughEncoding = _execute(e, req);
	bytes32 direct = manuallyComputeHash(e, target, grant, what, who);
	assert direct == throughEncoding;
}

rule regression() {
	env e;

    Test.Config configA;
    Test.Config configB;
    //assume same config just different order in voters

    require configA.blessWeightThreshold == configB.blessWeightThreshold;
    require configA.curseWeightThreshold == configB.curseWeightThreshold;
    require configA.voters.length == configB.voters.length;
    require configA.voters.length == 2;
    address voterA0 = configA.voters[0].blessVoteAddr;
	storage init = lastStorage;
	setConfig(e, configA);
	storage stateAfter = lastStorage;
	setConfig(e, configB) at init;
	assert lastStorage == stateAfter;
}

rule check_complicated_wrong(uint v) {
	env e;
	Test.MyComplexStruct x;
	uint[] whoa = x.nested[v].thing;
	require whoa.length == 4;
	assert complicated(e, x, v) == 3;
}

rule check_complicated_right(uint v) {
	env e;
	Test.MyComplexStruct x;
	uint[] whoa = x.nested[v].thing;
	require whoa.length == 3;
	assert complicated(e, x, v) == 3;
}

rule check_complicated_static(uint v, uint y) {
	env e;
	Test.MyComplexStruct[2] z;
	require(z[y].nested[v].thing.length == 3);
	assert complicatedStatic(e, z, v, y) == 3;
}

definition to_uint(mathint a) returns uint256 = require_uint256(a);

rule correctLiteralStaticCompilation(uint a, uint i, uint j) {
	uint[2][3] enc = [ [ a, to_uint(a + 1)  ], [ to_uint(a + 2), to_uint(a + 3) ], [ to_uint(a + 4), to_uint(a + 5) ] ];
	require(i < 2);
	require(j < 3);
	uint256 expected_value = to_uint(a + i + (j * 2));
	assert enc[j][i] == expected_value;
}



rule correctLiteralDynamicCompilation(uint a, uint i, uint j) {
	uint[][3] enc = [ [ a, to_uint(a + 1)  ], [ to_uint(a + 2), to_uint(a + 3) ], [ to_uint(a + 4), to_uint(a + 5) ] ];
	require(i < 2);
	require(j < 3);
	uint256 expected_value = to_uint(a + i + (j * 2));
	assert enc[j][i] == expected_value;
}

rule correctLiteralDynamicDynamic(uint a, uint b, uint c, uint i) {
	uint[][] enc = [ [a, to_uint(a + 1) ], [b, to_uint(b + 2), to_uint(b + 3)], [ c, to_uint(c + 4), to_uint(c + 5), to_uint(c + 6) ] ];
	require(i < enc.length);
	env e;
	mathint tot = doSum(e, enc[i]);
	assert tot == enc[i][0] + enc[i][1];
}
