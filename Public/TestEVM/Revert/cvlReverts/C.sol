contract C {
    bool fooWasCalled;
    D d;
    bool reachedAfterSummarized;

    function foo(bool b) external {
        fooWasCalled = true;
        if (!b) { revert(); }
    }

    function callFooFromContract(bool b) external {
        this.foo(b);
    }

    function summarizeMe(bool b) external {}
    function summarizeMeReturning(bool b) external returns (uint) { return 0; }
    function summarizeMeReturningInternal(bool b) internal returns (uint) { return 0; }
    function canRevert(bool b) external returns (bool) {
        if (!b) { revert(); }
        return b;
    }
    function callSummarizeMe(bool b) external {
        this.summarizeMe(b);
        reachedAfterSummarized = true;
    }
    function callSummarizeMeReturning(bool b) external returns (uint) {
        uint res = this.summarizeMeReturning(b);
        reachedAfterSummarized = true;
        return res;
    }
    function callSummarizeMeReturningInternal(bool b) external returns (uint) {
        uint res = summarizeMeReturningInternal(b);
        reachedAfterSummarized = true;
        return res;
    }

    function alwaysRevertContractFun() external {
        revert();
    }

    function callToSummarizeInD(bool b) external returns (uint) {
        return d.toSummarizeInD(b);
    }
    function callToSummarizeInDButVoid(bool b) external {
        d.toSummarizeInDButVoid(b);
    }
}

contract D {
    C c;
    function toSummarizeInD(bool b) public returns (uint) {
            return 0;
        }
    function toSummarizeInDButVoid(bool b) public {}
}
