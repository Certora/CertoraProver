library IsALibrary {
    function foo(uint[] storage hello) external returns (uint) {
        return hello.length;
    }

    function bar(uint[] storage hello, uint d) external returns (uint) {
        return hello.length + d;
    }
}

contract NotALibrary {
    function foo(uint[] memory hello) external returns (uint) {
        return hello.length;
    }
}

contract Test {
    uint[] myArray;

    function foo(uint[] storage f) internal returns (uint) {
        return 0;
    }

    function bar(uint[] storage f) internal returns (uint) {
        return 0;
    }

    function baz(uint[] storage f) internal returns (uint) {
        return 0;
    }

    function gorp(uint [] storage f, uint y) internal returns (uint) {
        return 0;
    }

    function boop(uint [] storage f) internal returns (bool) {
        return false;
    }

	function beep(uint [] storage f) internal returns (uint) {
		return 0;
	}


    function entry() external {
        foo(myArray);
        bar(myArray);
		beep(myArray);
        gorp(myArray, baz(myArray));
    }
}
