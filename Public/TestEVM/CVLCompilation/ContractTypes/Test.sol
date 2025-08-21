interface IERC20 {
}

contract Test {
	function doSomethingWithERC(IERC20[] memory doSomething) external returns (IERC20[] memory) {
		IERC20[] memory toReturn = new IERC20[](doSomething.length / 2);
		for(uint i = 0; i < doSomething.length; i++) {
			toReturn[i] = doSomething[i];
		}
		return toReturn;
	}

	function doSomethingWithStaticERC(IERC20[2] memory doSomething) external returns (IERC20[1] memory) {
		IERC20[1] memory toReturn = [ doSomething[1] ];
		return toReturn;
	}
}
