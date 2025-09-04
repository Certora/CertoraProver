contract Test {
	bool init;

	modifier initOnly() {
		require(init);
		_;
	}

	function int_f(string memory y) internal initOnly returns (uint) {
		return 0;
	}

	function entry(string memory x) external returns (uint) {
		return int_f(x);
	}
}
