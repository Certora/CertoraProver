pragma solidity ^0.5.0;

contract ConflictWithFunctionNames {
	function foo(uint256) public {

	}

	function foo(address) public {

	}

}