contract Test {
	uint state;
	function setFlag() external {
		state = state | (1 << 10);
	}

	function entry() external {
		state = 0;
		address(msg.sender).call(abi.encodeWithSignature("setFlag()"));
		if((state >> 10) & 1 != 1) {
			revert("oh no");
		}
	}
}
