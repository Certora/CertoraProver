contract Test {
	function transfer(address payable target) external payable {
		(bool t, bytes memory ret) = target.call{value: msg.value}(abi.encodeWithSignature("receive()"));
		require(t);
	}
}
