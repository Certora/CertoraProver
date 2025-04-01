contract Test {
	// simplified version of the OpenZeppelin short string datastructure
	function readLength(bytes32 enc) external returns (string memory) {
		uint len = uint256(enc) & 0xff;
		if(len > 31) {
			revert();
		}
		string memory toRet = new string(32);
		assembly {
			mstore(toRet, len)
			mstore(add(toRet, 0x20), enc)
		}
		return toRet;
	}

	function badUpdate(uint[] memory myArray, uint newLength) external {
		assembly {
			mstore(myArray, newLength)
		}
	}
}
