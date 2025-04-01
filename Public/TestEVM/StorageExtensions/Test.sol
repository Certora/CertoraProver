contract Test {
    uint existing;

	struct Beep {
        uint mask;
		uint[256] words;
        mapping(uint => uint) map;
	}

	struct Book {
		Beep field1;
		Beep field2;
	}

    function getBook1() internal returns (Book storage) {
		Book storage b;
        assembly ("memory-safe") {
            b.slot := 0x8580001204012435
        }
        return b;
    }

    function getBook2() internal returns (Book storage) {
		Book storage b;
        assembly ("memory-safe") {
            b.slot := 0x1234123412341234
        }
        return b;
    }
	function doThing(uint256 x, bool which) external returns (uint256) {
		Book storage b = getBook1();
		Beep storage theBeep = which ? b.field1 : b.field2;
        uint8 pos = uint8(x & 255);
        return theBeep.words[pos];
	}

	function doOtherThing(uint256 x, bool which) external returns (uint256) {
		Book storage b = getBook2();
		Beep storage theBeep = which ? b.field1 : b.field2;
        uint8 pos = uint8(x & 255);
        return theBeep.words[pos];
	}
}
