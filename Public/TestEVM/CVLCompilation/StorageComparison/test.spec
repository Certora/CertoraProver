using Basic as t;

methods {
  function _.receiveCash() external => NONDET;
}

ghost mathint totalTransferred;
ghost totalTransferredFunc() returns mathint;

hook Sload uint x Basic.m[KEY address a] {
	totalTransferred = totalTransferred + x;
    havoc totalTransferredFunc assuming totalTransferredFunc@new() == totalTransferredFunc@old() + x;
}

rule should_pass_1 {
	storage init = lastStorage;
	env e;
	incr(e);
	incr(e);
	decr(e);
	decr(e);
	assert init[t] == lastStorage[t];
}

rule should_pass_2 {
	env e;
	incr(e);
	storage init = lastStorage;
	incr(e);
	decr(e);
	assert init[t] == lastStorage[t];
}

rule should_pass_3 {
	env e;
	env e2;
	address target;
	uint amount;

	storage init = lastStorage;
	transfer(e, e2.msg.sender, amount);
	transfer(e2, target, amount);
	storage s1 = lastStorage;
	transfer(e, target, amount) at init;
	assert s1[t] == lastStorage[t];
}

rule should_pass_4 {
	env e;
	storage init = lastStorage;
	address x;
	butThenAlsoSend(e, x);
	assert init[t] == lastStorage[t];
}

rule should_pass_5 {
	env e;
	storage init = lastStorage;
	require(e.msg.sender == t);
	receiveCash(e);
	assert init[nativeBalances] == lastStorage[nativeBalances];
}

rule should_pass_6 {
	env e;
	storage init = lastStorage;
	require(e.msg.sender == t);
	butThenAlsoSend(e, t);
	assert init[nativeBalances] == lastStorage[nativeBalances];
}

rule should_pass_7 {
	env e;
	storage init = lastStorage;
	address x;
	butThenAlsoSend(e, x);
	assert init[totalTransferred] == lastStorage[totalTransferred];
}

rule should_pass_8(address a) {
	env e;
	storage init = lastStorage;
	t.butThenAlsoSend(e, a);
	t.incr(e);
	storage scenario1 = lastStorage;
	t.incr(e) at init;
	t.butThenAlsoSend(e, a);
	assert scenario1 == lastStorage;
}

rule should_fail_1 {
	env e;
	storage init = lastStorage;
	incr(e);
	assert init[t] == lastStorage[t];
}


rule should_fail_2 {
	env e;
	storage init = lastStorage;
	address x;
	butThenAlsoSend(e, x);
	assert init == lastStorage;
}


rule should_fail_3 {
	env e;
	storage init = lastStorage;
	incrStructField(e);
	assert init == lastStorage;
}


rule should_fail_4 {
	env e;
	storage init = lastStorage;
	incrNestedStructField(e);
	assert init == lastStorage;
}


rule should_fail_5 {
	env e;
	storage init = lastStorage;
	incrTightlyPackedStruct(e);
	assert init == lastStorage;
}

rule should_fail_6 {
	env e;
	env e2;
	address target;
	uint amount;

	storage init = lastStorage;
	transfer(e, e2.msg.sender, amount);
	transfer(e2, target, amount);
	storage s1 = lastStorage;
	transfer(e, target, amount) at init;
	assert s1 == lastStorage;
}
