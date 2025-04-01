pragma solidity 0.8.24;

import "./SomeInterface.sol";

contract StrongInvariants {
    uint256 storageValue = 1;
    SomeInterface tokenAsProperty;

    function shouldSucceed_dueToHavocedAllContractCall(SomeInterface token) external {
        // The invariant storageValue === 1 should succeed here as it is a regular call and the invariant will be assumed.
        address(token).call(abi.encodeWithSignature("havocAllContracts()"));
    }

    function shouldFail_dueToHavocedAllContractDelegateCall(SomeInterface token) external {
        // The invariant storageValue === 1 should fail here as the storage is havoc'ed due to the external call.
        address(token).delegatecall(abi.encodeWithSignature("havocAllContracts()"));
    }

    function shouldSucceed_dueToHavocedAllContract(SomeInterface token) external {
        // The invariant storageValue === 1 should not fail here as only the return value of the call is havoc'ed
        address(token).delegatecall(abi.encodeWithSignature("havocOnlyReturn()"));
    }

    function shouldFail_beforeExternalCallSummarizedHavocAll(SomeInterface token) external {
        storageValue = 2;
        // There is an unresolved external call, so the invariant storageValue === 1 should be checked here. It should fail before the summarized call.
        address(token).delegatecall(abi.encodeWithSignature("havocAllContracts()"));
        storageValue = 1;
    }

    function shouldSucceed_beforeExternalCallSummarizedNonDet(SomeInterface token) external {
        storageValue = 2;
        // There is an external call which is summarized using CVL, so the strong invariant will not be checked.
        address(token).delegatecall(abi.encodeWithSignature("havocOnlyReturn()"));
        storageValue = 1;
    }

    function shouldSucceed_beforeExternalCallWithLinking() external {
        storageValue = 2;
        // There is an external call which is summarized using CVL, so the strong invariant will not be checked.
        address(tokenAsProperty).delegatecall(abi.encodeWithSignature("repay()"));
        storageValue = 1;
    }

    function shouldFail_butOnlyAfterDelegateCall(SomeInterface token) external {
        storageValue = 1;
        // There is an unresolved external call, so the invariant storageValue === 1 must be checked here. It should _not_ fail at this point as it still holds.
        address(token).delegatecall(abi.encodeWithSignature("havocAllContracts()"));
    }

    function shouldSucceed_repayNoDelegate(SomeInterface token) external {
        token.repay();
    }

    function shouldSucceed_repayNoDelegateWithBreakingInvariantUndetected(SomeInterface token) external {
        storageValue = 2;
        // There is no unresolved external call, so the invariant storageValue === 1 won't be checked and the check won't fail on this method
        token.repay();
        storageValue = 1;
    }

    function shouldSucceed_addOne(uint x) external returns (uint){
        return x + 1;
    }


    function shouldFinallyFail_beforeExternalCallSummarizedHavocAll(SomeInterface token) external {
        // The invariant storageValue === 1 should not fail here...
        address(token).delegatecall(abi.encodeWithSignature("havocAllContracts()"));
        storageValue = 2;
        // There is an unresolved external call, so the invariant storageValue === 1 should be checked here. It should fail before the summarized call.
        address(token).delegatecall(abi.encodeWithSignature("havocAllContracts()"));
        storageValue = 1;
    }

    function beforeHavocECF(SomeInterface token) external {
        storageValue = 2;
        // There is an external call which is summarized using CVL, and it's a HAVOC_ECF so the strong invariant will be checked.
        address(token).delegatecall(abi.encodeWithSignature("havocECF()"));
        storageValue = 1;
    }

    function beforeHavocALL(SomeInterface token) external {
        storageValue = 2;
        // There is an external call which is summarized using CVL and it's a havoc all, so the strong invariant will be checked.
        address(token).delegatecall(abi.encodeWithSignature("havocALL()"));
        storageValue = 1;
    }
}
