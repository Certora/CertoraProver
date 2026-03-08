pragma solidity 0.8.24;

import "./SomeInterface.sol";


contract CVLSummaryStrongInvariant {
    uint256 storageValue = 1;

    function shouldSucceed_beforeUnresolvedCall(address token) external {
        // Unresolved external call triggers the invariant check
        address(token).delegatecall(abi.encodeWithSignature("havocAllContracts()"));
    }

    function shouldBeSummarized() external returns (uint256) {
        return storageValue;
    }

    function invariantCall() external returns (uint256) {
        return this.shouldBeSummarized();
    }
}