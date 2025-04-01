pragma solidity 0.8.24;

import "./SomeInterface.sol";
import "./Dummy.sol";
import "./ContractWithDelegate.sol";


contract MultiContract {
    uint256 storageValue = 1;
    ContractWithDelegate other;
    SomeInterface dummy;

    function shouldSucceed_callOtherContractUnresolvedDelegateCall(SomeInterface token) external {
        other.methodWithDelegateCallUnresolved(token);
     }

    function shouldFail_callOtherContractUnresolvedDelegateCall(SomeInterface token) external {
        storageValue = 2;
        //There is an unresolved external call, within the next call, so the invariant storageValue === 1 should be checked before the unresolved call that will be inlined and it should fail
        other.methodWithDelegateCallUnresolved(token);
        storageValue = 1;
     }
    function shouldSucceed_callOtherContractUnresolvedDelegateCall_forceInvariantToHold(SomeInterface token) external {
        storageValue = 1;
        other.methodWithDelegateCallUnresolved(token);
        storageValue = 1;
     }

    function shouldSuceed_callOtherContractResolvedDelegateCall() external {
        other.methodWithDelegateCallResolved(dummy);
     }
    function shouldSuceed_callOtherContractResolvedDelegateCall_forceInvariantToHold() external {
        storageValue = 1;
        other.methodWithDelegateCallResolved(dummy);
        storageValue = 1;
     }

    function shouldFail_beforeExternalCallSummarizedNonDet(SomeInterface token) external {
        storageValue = 2;
        //There is an unresolved external call, so the invariant storageValue === 1 should be checked here. It should fail before the summarized call.
        address(token).delegatecall(abi.encodeWithSignature("havocOnlyReturn()"));
        storageValue = 1;
    }
}
