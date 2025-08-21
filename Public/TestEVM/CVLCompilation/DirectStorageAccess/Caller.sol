import "Test.sol";

contract Caller {
    Test store;
	function doComputation(bytes memory k) external returns (uint) {
		if(store.getStoppedState()) {
			return 0;
		}
		return store.getTokenPrice(msg.sender) + store.getPriceOracle(k);
	}
}
