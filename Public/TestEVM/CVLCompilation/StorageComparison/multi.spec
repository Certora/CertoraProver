using Basic as t;
using OtherContract as o;

methods {
  function _.receiveCash() external => DISPATCHER(true);
}

rule should_pass_1 {
	env e;
	storage init = lastStorage;
	t.incr(e);
	assert lastStorage[o] == init[o];
}

rule should_pass_2 {
	env e;
	storage init = lastStorage;
	require(e.msg.value > 0);
	t.butThenAlsoSend(e, o);
	assert lastStorage[o] == init[o];
}

rule should_pass_3 {
	env e;
	storage init = lastStorage;
	require(e.msg.sender == o);
	t.butThenAlsoSend(e, o);
	assert lastStorage == init;
}

rule should_fail_1 {
	env e;
	storage init = lastStorage;
	t.incr(e);
	assert lastStorage == init;
}
