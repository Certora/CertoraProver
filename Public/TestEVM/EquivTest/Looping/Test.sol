contract C1 {
	event MyLog(uint indexed thing, bytes data);

	function entry(uint count, uint256 a, uint128 b) external returns (uint) {
		bytes memory cheeky = new bytes(48);
		for(uint i = 0; i < count; i++) {
			assembly {
				mstore(add(cheeky, 0x20), 0)
				mstore(add(cheeky, 0x30), b)
				mstore(add(cheeky, 0x20), a)
			}
			emit MyLog(a, cheeky);
		}
		return count;
	}
}


contract C2 {
	event MyLog(uint indexed thing, bytes data);

	function entry(uint count, uint256 a, uint128 b) external returns (uint) {
		for(uint i = 0; i < count; i++) {
			bytes memory data = abi.encodePacked(a, b);
			emit MyLog(a, data);
		}
		return count;
	}
}
