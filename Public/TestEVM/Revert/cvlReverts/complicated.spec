using D as d;

methods {
    function d.toSummarizeInD(bool b) external returns (uint) with (env e)  => cvlDispatchCall(b, calledContract, e);
    function d.toSummarizeInD(bool b) internal returns (uint) with (env e)  => cvlSummarizeMe(b);
    function d.toSummarizeInDButVoid(bool b) external with (env e)  => cvlDispatchCallVoid(b, calledContract, e);
    function d.toSummarizeInDButVoid(bool b) internal with (env e)  => cvlSummarizeMeVoid(b);
}

function cvlSummarizeMe(bool b) returns uint {
    return cvlCanRevert(b);
}

function cvlSummarizeMeVoid(bool b) {
    cvlCanRevert(b);
}

function cvlCanRevert(bool b) returns uint {
    if (!b) { revert("for a reason"); }
    return 42;
}

function cvlDispatchCall(bool b, address callee, env e) returns uint {
    if (callee == d) {
        return d.toSummarizeInD(e, b);
    }
    require false, "we only care about calls on d";
    return 0;
}

function cvlDispatchCallVoid(bool b, address callee, env e) {
    if (callee == d) {
        d.toSummarizeInDButVoid(e, b);
        return;
    }
    require false, "we only care about calls on d";
}

rule test {
    bool b;
    env e;
    callToSummarizeInD@withrevert(e, b);
    assert !b => lastReverted;
}

rule testVoidVersion {
    bool b;
    env e;
    callToSummarizeInDButVoid@withrevert(e, b);
    assert !b => lastReverted;
}
