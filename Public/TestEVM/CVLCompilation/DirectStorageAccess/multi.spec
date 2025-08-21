using Test as store;

methods {
	function store.getPriceOracle(bytes k) external returns (uint) => readOracle(k);
	function store.getTokenPrice(address k) external returns (uint96) => readStorageFor(k);
	function store.getStoppedState() external returns (bool) => readIsStopped();
}

function readOracle(bytes k) returns uint {
   return store.topLevelMap[k];
}

function readStorageFor(address k) returns uint96 {
   return store.a[k][0].baz;
}

definition readIsStopped() returns bool = store.topLevel3;

rule do_computation {
	env e;
	bytes k;
	require(k.length % 32 == 0);
	uint ret = doComputation(e, k);
	uint expected;
	if(store.topLevel3(e)) {
	    expected = 0;
    } else {
	      expected = require_uint256(
			  store.topLevelMap(e, k) +
			  store.getter5(e, e.msg.sender, 0)
		 );
    }
    assert expected == ret;
}
