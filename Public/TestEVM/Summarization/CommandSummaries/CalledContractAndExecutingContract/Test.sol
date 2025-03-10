// These tests are for the `calledContract` feature described in [RMP-6].
//
// There are two contracts `Primary` and `Secondary` and a library `Library`.
// Each has a `calleeInternal` and a `calleeExternal` function (these are
// summarized in Test.spec).  There are else several unsummarized `call...`
// entry points; each is used in a rule in `Test.spec`.

contract Primary {

    Secondary secondary;
    Tertiary tertiary;

    // callee functions are summarized

    function calleeInternal() internal { }
    function calleeExternal() external { }

    // call functions are unsummarized; each corresponds to a test

    function callPrimaryInternal() external {
        calleeInternal();
    }

    function callSecondaryInternal() external {
        secondary.callSecondaryInternal();
    }

    function callPrimaryExternal() external {
        this.calleeExternal();
    }

    function callSecondaryExternal() external {
        secondary.calleeExternal();
    }

    function callPrimaryExternalFromSecondary() external {
        secondary.callPrimaryExternal(this);
    }

    function callLibraryInternalFromPrimary() external {
        Library.calleeInternal();
    }

    function callLibraryExternalFromPrimary() external {
        Library.calleeExternal();
    }

    function callLibraryInternalFromSecondary() external {
        secondary.callLibraryInternal();
    }

    function callLibraryExternalFromSecondary() external {
        secondary.callLibraryExternal();
    }

    function callTertiaryThroughSecondary() external {
        secondary.callTertiaryExternal(tertiary);
    }

    function callSecondaryExternalViaDelegate() external {
        address(secondary).delegatecall(abi.encodeWithSignature("calleeExternal()"));
    }
}


contract Secondary {
    // the `callee` methods are summarized

    function calleeExternal() external { }
    function calleeInternal() internal { }

    // the `call` methods are used to dispatch to the callee methods

    function callPrimaryExternal(Primary receiver) external {
        receiver.calleeExternal();
    }

    function callSecondaryInternal() external {
        calleeInternal();
    }

    function callLibraryInternal() external {
        Library.calleeInternal();
    }

    function callLibraryExternal() external {
        Library.calleeExternal();
    }

    function callTertiaryExternal(Tertiary receiver) external {
        receiver.calleeExternal();
    }
}

library Library {
    function calleeExternal() external { }
    function calleeInternal() internal { }
}

contract Tertiary {
    // the `callee` methods are summarized

    function calleeExternal() external { }
    function calleeInternal() internal { }
}
