contract Test {
	function doThing(uint x) internal returns (uint, uint[] memory, uint[] memory) {
		if(x == 4) {
			return (0, new uint[](0), new uint[](0));
		}
		uint[] memory y = new uint[](x);
		uint[] memory z = new uint[](x);
		return (x, y, z);
	}

	function doStuff(uint z) external {
		doThing(z);
	}
}
