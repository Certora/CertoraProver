library MyHarnessLibrary {
	function rerouteTarget(
		mapping(address => Test.Complex[]) storage r,
		address which,
		Test.Complex[] memory payload
	) external returns (Test.Complex[] memory) {
		Test.Complex[] memory toRet = r[which];
		r[which] = payload;
		return toRet;
	}

	function simpler(
		mapping(address => Test.Simpler[]) storage r,
		address which,
		Test.Simpler[] memory target
	) external returns (Test.Simpler[] memory) {
		Test.Simpler[] memory toRet = r[which];
		r[which] = target;
		return toRet;
	}
}

contract Test {
	struct StaticStruct {
		uint256 myOtherPrimitive;
		bytes32 myBytesK;
	}

	struct Complex {
		bytes myBytes;
		uint[] myArray;
		uint[4] myStaticField;
		uint myPrimitiveField;
		StaticStruct myStaticStruct;
	}

	struct Simpler {
		bytes myBytes;
		uint[] data;
	}

	mapping(uint => mapping(address => Complex[])) myComplexDataStructure;

	mapping(uint => mapping(address => Simpler[])) simplerDataStructure;

	function doOtherUpdate(
		mapping(address => Simpler[]) storage myDataStructure,
		address who,
		Simpler[] memory what
	) internal returns (Simpler[] memory) {
		myDataStructure[who] =  new Simpler[](0);
		return new Simpler[](0);
	}

	function doInternalUpdate(
		mapping(address => Complex[]) storage toUpdate,
		address target,
		Complex[] memory toSet
	) internal returns (Complex[] memory) {
		toUpdate[target] = new Complex[](0);
		return new Complex[](0);
	}

	function externalEntry(
		uint tgt,
		Complex[] memory payload
	) external returns (Complex[] memory) {
		return doInternalUpdate(myComplexDataStructure[tgt], msg.sender, payload);
	}

	function externalEntry2(
		uint tgt,
		Simpler[] memory payload
	) external returns (Simpler[] memory) {
		return doOtherUpdate(simplerDataStructure[tgt], msg.sender, payload);
	}
}
