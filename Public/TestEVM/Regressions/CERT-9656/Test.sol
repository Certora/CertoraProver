uint constant DOUBLE_CACHE_LENGTH = 2;
interface Intf {
    struct SubStruct {
        uint104 f1;
        int104 f2;
        uint48 f3;
    }

	struct SignedSubStruct {
        int104 signedF1;
        int104 f2;
        uint48 f3;
    }

    struct Record {
        SubStruct report;
        uint96 f1;
        uint96 f2;
        SignedSubStruct[2] staticStructs;
        uint128 postField1;
        uint128 postField2;
        uint128 postField3;
        uint128 postField4;
    }

	function getRecord() external returns (Record memory);
}

contract Test {
	address handler;

	Intf.SignedSubStruct[2] field;

	function entry() external returns (uint) {
		Intf.Record memory record = Intf(handler).getRecord();
		field = record.staticStructs;
		return this.pleaseSummarize(record.postField4);
	}

    function pleaseSummarize(uint256 a) external returns (uint) {
        return a + 1;
    }
}
