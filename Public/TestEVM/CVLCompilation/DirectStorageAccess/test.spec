using Test as t;

hook Sload uint value topLevelMap[KEY bytes key] {
	assert value == t.topLevelMap[key];
}

rule test_complex_access {
    address key;
	uint idx;
	uint96 k = t.a[key][idx].gorp[idx];
	env e;
	uint96 k2;
	k2 = getter1(e, key, idx);
	assert k2 == k;
}

rule test_packed_arrays {
	env e;
	address key;
	uint idx;
	bool b = currentContract.a[key][idx].bar[idx];
	assert b == getter2(e, key, idx);
}

rule test_bytes_keys {
	bytes k;
	require(k.length % 32 == 0);
	env e;
	assert readMap(e, k) == currentContract.topLevelMap[k];
}

rule test_top_level {
	env e;
	assert t.topLevel1 == topLevel1(e);
	assert t.topLevel2 == topLevel2(e);
	assert t.topLevel3 == topLevel3(e);
}

rule test_array_lengths {
	env e;
	address k;
	uint i;
	assert getter3(e, k, i) == t.a[k][i].bar.length;
}

rule test_complex_mapping {
    env e;
	bytes k1;
	string k2;
	require(k1.length % 32 == 0);
	require(k2.length % 32 == 0);
	assert getter4(e, k1, k2) == currentContract.nestedMap[k1][k2].foo.y;
}

rule test_complex_mapping_2 {
    env e;
	uint k;
	uint i;
    address a = getter6(e, k, i);
	assert a == currentContract.structWithMapping[i].m[k], "direct storage access should match getter";
}

rule test_complex_access_in_quantifier {
    address key;
	uint idx;
	uint96 k = t.a[key][idx].gorp[idx];
	env e;
	uint96 k2;
	k2 = getter1(e, key, idx);
	assert k2 == k, "direct storage access should match getter";

    assert exists address a. exists uint u. to_mathint(t.a[a][u].gorp[u]) == to_mathint(k2);
}

rule test_packed_arrays_in_quantifier {
	require forall address k. forall uint i. t.a[k][i].bar[0];
    assert forall address x. forall uint y. t.a[x][y].bar[0];
}

rule test_array_lengths_in_quantifier {
	env e;
	address k;
	uint i;
    uint len = getter3(e, k, i);
	assert len == t.a[k][i].bar.length, "direct storage access should match getter";

    assert exists address a. exists uint u. len == t.a[a][u].bar.length;
}

definition sWithM(uint j, uint l) returns address = currentContract.structWithMapping[j].m[l];

rule test_complex_mapping_in_quantifier {
    env e;
	uint k;
	uint i;
    address a = getter6(e, k, i);
	assert a == currentContract.structWithMapping[i].m[k], "direct storage access should match getter";

    assert exists uint i1. exists uint k1. a == sWithM(i1, k1);
}

hook Sload bool b topLevel3 {
    require b == true;
}

// There shouldn't be hook inlining on direct storage access
rule test_no_hook_inlining {
    assert currentContract.topLevel3, "should fail if hooks isn't inlined";
}

hook Sload bool b a[KEY address _][INDEX uint _].bar[INDEX uint _] {
    require b == true;
}

// There shouldn't be hook inlining on direct storage access
rule test_no_hook_inlining_complex_access {
    address a; uint u; uint v;
    assert currentContract.a[a][u].bar[v], "should fail if hooks isn't inlined";
}
