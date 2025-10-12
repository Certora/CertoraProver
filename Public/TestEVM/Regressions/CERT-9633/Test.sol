contract Test {
	struct AllPacked {
		int104 a;
		int104 b;
		uint48 refSlot;
	}

	struct Container {
		uint96 packed1;
		uint96 packed2;
		AllPacked[2] staticArray;
		uint128 post1;
		uint128 post2;
	}

	mapping(address => Container) someMap;

	function test(address x) external returns (int104) {
		AllPacked[2] memory bloop = someMap[x].staticArray;
		return bloop[0].a + bloop[1].b;
	}
}
