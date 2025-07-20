interface IERC420 {
	function blazeIt() external returns (uint);
}

contract Test {
	struct StateStruct {
		address target;
		bytes data;
		uint amount;
	}

	mapping(address => StateStruct) state;

	function harness() external {
		StateStruct memory config = state[msg.sender];
		if(IERC420(config.target).blazeIt() > config.amount) {
			revert("too much");
		}
		(bool rc, ) = config.target.staticcall(config.data);
		require(rc);
	}
}
