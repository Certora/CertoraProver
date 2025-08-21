using Basic as t;
using OtherContract as o;

rule bad_2 {
	storage init = lastStorage;
	assert init[o] == lastStorage[t];
}

rule bad_3 {
	storage init = lastStorage;
	assert init[o] == lastStorage[nativeBalances];
}

rule bad_4 {
	uint yolo;
	storage init = lastStorage;
	assert lastStorage[yolo] == init[yolo];
}

ghost mapping(uint => mathint) map;
ghost mathint total;

rule bad_5 {
	storage init = lastStorage;
	assert init[total] == lastStorage[total];
}


rule bad_6 {
	storage init = lastStorage;
	uint yolo;
	assert init == yolo;
}


rule bad_1 {
	storage init = lastStorage;
	assert init < lastStorage;
}

rule bad_7(bool b) {
	storage init = lastStorage;
	assert (b ? init : lastStorage) == (b ? init : lastStorage);
	assert (b ? lastStorage[o] : init[o]) == init[o];
	assert lastStorage[b ? o : t] == init[o];
}
