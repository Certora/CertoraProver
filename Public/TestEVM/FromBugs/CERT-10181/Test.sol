contract Test {
	mapping (bytes4 => uint) public balances;

	fallback() external {
		require(msg.data.length >= 4);
		uint bal = balances[msg.sig];
		assembly {
			mstore(mload(0x40), bal)
			return(mload(0x40), 0x20)
		}
	}
}
