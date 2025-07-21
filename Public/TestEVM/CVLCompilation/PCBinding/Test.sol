contract Test {
	function hello(uint a) external {
		this.world(this.different(a));
	}

	function world(uint b) external {
	}

	function different(uint a) external pure returns (uint) {
		return a + 1;
	}
}
