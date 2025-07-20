contract OtherImplementer {
	function myThing(uint a, uint b) external returns (uint) {
		return a + b;
	}
}

contract MainImplementer {
	address other;

	function myThing(uint a, uint b) external returns (uint r) {
		a;
		b;
		address tgt = other;
		assembly {
			let ptr := mload(0x40)
			calldatacopy(ptr, 0, calldatasize())
			let succ := delegatecall(gas(), tgt, ptr, calldatasize(), 0, 0)
			let out := mload(0x40)
			returndatacopy(out, 0, returndatasize())
			switch succ
			case 0 {
				revert(out, returndatasize())
			}
			default {
				return(out, returndatasize())
			}
		}
	}
}
