// These tests are for the `calledContract` feature described in [RMP-6].
//
// There are two contracts `Primary` and `Secondary` and a library `Library`,
// each having a (summarized) `calleeInternal` and a `calleeExternal` method.
// The contracts also have several `call` methods that call the different callee
// methods from different callers.
//
// The summaries for all the `callee` methods just save the `calledContract`
// in the `called` ghost; the rules then check that the ghost is updated
// correctly.  All assertions should pass.
//

using Primary   as primary;
using Secondary as secondary;
using Tertiary  as tertiary;

methods {
    function _.calleeInternal() internal with (env e) => saveContracts(calledContract, executingContract, e.msg.sender) expect void;
    function _.calleeExternal() external with (env e) => saveContracts(calledContract, executingContract, e.msg.sender) expect void ALL;
}

ghost address called;
ghost address executing;
ghost address sender;

function saveContracts(address _called, address _executing, address _sender) {
    called = _called;
    executing = _executing;
    sender = _sender;
}

rule primaryInternal(env e) {
    callPrimaryInternal(e);
    assert called == primary;
    assert executing == primary;
    assert sender == e.msg.sender;
}

rule secondaryInternal(env e) {
    callSecondaryInternal(e);
    assert called == secondary;
    assert executing == secondary;
    assert sender == primary;
}

rule primaryExternal(env e) {
    callPrimaryExternal(e);
    assert called == primary;
    assert executing == primary;
    assert sender == primary;
}

rule secondaryExternal(env e) {
    callSecondaryExternal(e);
    assert called == secondary;
    assert executing == primary;
    assert sender == primary;
}

rule primaryExternalFromSecondary(env e) {
    callPrimaryExternalFromSecondary(e);
    assert called == primary;
    assert executing == secondary;
    assert sender == secondary;
}

rule libraryInternalFromPrimary(env e) {
    callLibraryInternalFromPrimary(e);
    assert called == primary;
    assert executing == primary;
    assert sender == e.msg.sender;
}

rule libraryExternalFromPrimary(env e) {
    callLibraryExternalFromPrimary(e);
    assert called == primary;
    assert executing == primary;
    assert sender == e.msg.sender;
}

rule libraryInternalFromSecondary(env e) {
    callLibraryInternalFromSecondary(e);
    assert called == secondary;
    assert executing == secondary;
    assert sender == primary;
}

rule libraryExternalFromSecondary(env e) {
    callLibraryExternalFromSecondary(e);
    assert called == secondary;
    assert executing == secondary;
    assert sender == primary;
}

rule secondaryToPrimaryFromSpec(env e) {
    secondary.callPrimaryExternal(e,primary);
    assert called == primary;
    assert executing == secondary;
    assert sender == secondary;
}

rule secondaryToSecondaryFromSpec(env e) {
    secondary.callSecondaryInternal(e);
    assert called == secondary;
    assert executing == secondary;
    assert sender == e.msg.sender;
}

rule secondaryToLibraryInternalFromSpec(env e) {
    secondary.callLibraryInternal(e);
    assert called == secondary;
    assert executing == secondary;
    assert sender == e.msg.sender;
}

rule secondaryToLibraryExternalFromSpec(env e) {
    secondary.callLibraryExternal(e);
    assert called == secondary;
    assert executing == secondary;
    assert sender == e.msg.sender;
}

rule tertiaryThroughSecondary(env e) {
    callTertiaryThroughSecondary(e);
    assert called == tertiary;
    assert executing == secondary;
    assert sender == secondary;
}

rule secondaryExternalViaDelegateFromPrimary(env e) {
    callSecondaryExternalViaDelegate(e);
    assert called == primary;
    assert executing == primary;
    assert sender == e.msg.sender;
}
