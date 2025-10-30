interface ITest {
	function beep() external returns (uint);
}
contract Handler {
	function whatever() external returns (uint) {
		return ITest(address(this)).beep();
	}
}

contract Manager {
	address immutable handler;

	constructor(address _handler) {
		handler = _handler;
	}

	function getHandler() external returns (address) {
		return handler;
	}
}

contract Test is ITest {
	Manager manager;
	function entry() external returns (uint) {
        // This delegatecall cannot be resolved until after storage analysis, and so must be inlined late, during
        // summarization.
		(bool success, bytes memory ret) = address(manager.getHandler()).delegatecall(
			abi.encodeCall(Handler.whatever, ())
		);
		require(success);
		return abi.decode(ret, (uint));
	}

	function beep() external override returns (uint) {
		return 987345;
	}
}
