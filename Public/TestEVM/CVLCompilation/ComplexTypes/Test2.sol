interface IFace {}

contract Test2 {
	struct InnerStruct {
		uint x;
		uint y;
        IFace i;
	}

	struct OuterStruct {
		uint a;
		uint b;
		uint[] anArray;
		InnerStruct[] structArray;
	}

	function decodeThing(OuterStruct[] calldata x) external returns (bool) {
		return x[0].anArray.length == x[1].a && x[0].structArray[x[1].b].x == x[1].anArray[x[1].b];
	}
}
