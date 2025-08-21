contract C {
	function inner(uint x) public returns (uint) {
		return x+1;
	}

	function outer(uint y, uint z) public returns (uint) {
		return y;
	}
}
