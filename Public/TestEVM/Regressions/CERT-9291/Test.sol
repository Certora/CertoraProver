contract Test {
	function customIteration(bytes memory input) external returns (uint) {
		uint acc = 0;
		for(uint i = 32; i <= input.length; i+=32) {
			uint elem;
			assembly {
				elem := mload(add(input, i))
			}
			acc += elem;
		}
		return acc;

	}

	struct DecodedElems {
		uint scalar;
		uint[] arrays;
	}


	function doAbiDecode(bytes memory enc) external {
		DecodedElems[] memory x = abi.decode(enc, (DecodedElems[]));

	}

	struct TwoFields {
		uint a;
		uint b;
	}

	struct Aggregate {
        bytes32 scalar1;
        uint256 scalar2;
        TwoFields simpleAggregate;
        uint256[] array;
	}

	function bytesCast(bytes memory input) external returns (bytes4) {
		return bytes4(input);
	}

	mapping(address => Aggregate) state;

	function copyFromStorage() external {
		Aggregate memory fromStorage = state[msg.sender];
	}

	struct TwoStatic {
		uint[2] a;
		uint[2] b;
	}

	function customCall(TwoFields[2] memory f1, TwoStatic[2] memory f2) external returns (bool) {
		uint[12] memory input;
		for (uint256 i = 0; i < 2; i++) {
            uint256 j = i * 6;
            input[j + 0] = f1[i].a;
            input[j + 1] = f1[i].b;
            input[j + 2] = f2[i].a[0];
            input[j + 3] = f2[i].a[1];
            input[j + 4] = f2[i].b[0];
            input[j + 5] = f2[i].b[1];
        }
        uint256[1] memory out;
        bool success;

        address target = msg.sender;
        assembly {
            success := staticcall(sub(gas(), 2000), target, input, mul(12, 0x20), out, 0x20)
        }

        require(success);

        return out[0] != 0;
	}

	function selfGetter() external returns (TwoFields memory x, TwoStatic memory y) { }

	function decoder() external {
		(TwoFields memory x, ) = this.selfGetter();
	}

	function hasher(uint a, uint b, bytes calldata keys) external returns (bytes32) {
		return keccak256(abi.encode(a, b, keccak256(keys)));
	}

}
