contract Test {
	function entry(bool a, bool b) external returns (uint) {
		string memory fst = a ? "hello" : "world";
		string memory snd = b ? "boop" : "beep";
		this.toCall(fst, snd);
		return this.toResolve();
	}

	function toResolve() external returns (uint) {
		return 4;
	}

	function toCall(string memory s1, string memory s2) external {
	}
}
