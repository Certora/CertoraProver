contract Test {
	struct ArrayWrapper {
		uint64[3] theData;
	}

	mapping(address => ArrayWrapper) packedStorageCopy;

	function usePackedStorage() external returns (uint256) {
		ArrayWrapper memory in_mem = packedStorageCopy[msg.sender];
		uint ret = 0;
		for(uint i = 0; i < 3; i++) {
			ret += in_mem.theData[i];
		}
		return ret;
	}
}
