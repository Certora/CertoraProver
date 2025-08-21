contract Test {

	struct Voter {
		address blessVoteAddr;
		address curseVoteAddr;
		address curseUnvoteAddr;
		uint8 blessWeight;
		uint8 curseWeight;
	}


	struct Config {
		Voter[] voters;
		uint16 blessWeightThreshold;
		uint16 curseWeightThreshold;
	}


	struct Permission {
		bytes4 what;
		address who;
	}

	struct PermissionChange {
		bool grant;
		Permission permission;
	}

	struct PermissionChangeRequest {
		address target;
		PermissionChange[] changes;
	}

	function setConfig(Config memory foo) external {
	}

	function getAddress() external returns (uint[] memory) {
		return new uint[](0);
	}

	function _execute(PermissionChangeRequest memory x) public returns (bytes32) {
		bytes32 seed = keccak256(abi.encodePacked(x.target));
		for(uint i = 0; i < x.changes.length; i++) {
			seed = keccak256(abi.encodePacked(seed, x.changes[i].grant, x.changes[i].permission.what, x.changes[i].permission.who));
		}
		return seed;
	}

	function manuallyComputeHash(address target, bool grant, bytes4 what, address who) external returns (bytes32) {
		return keccak256(
						 abi.encodePacked(
										  keccak256(abi.encodePacked(target)),
										  grant, what, who));
	}

	struct MyComplexStruct {
		MyInnerStruct[] nested;
	}

	struct MyInnerStruct {
		uint[] thing;
		uint field;
	}

	function complicated(MyComplexStruct memory z, uint x) external returns (uint) {
		return z.nested[x].thing.length;
	}

	function complicatedStatic(MyComplexStruct[2] memory z, uint x, uint y) external returns (uint) {
		return z[y].nested[x].thing.length;
	}

	function doSum(uint[] memory data) external returns (uint) {
		return data[0] + data[1];
	}
}
