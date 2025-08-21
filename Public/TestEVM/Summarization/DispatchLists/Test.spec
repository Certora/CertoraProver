using D as d;
using F as f;

methods {
    function testFoo(uint) external returns (uint) envfree;
    function testBar(uint) external returns (uint) envfree;
    function testITakeMoreComplicatedTypes() external returns (uint) envfree;
    function testIDontReturnAnything() external envfree;
    function testKnownReceiver() external returns (uint) envfree;
    function testKnownReceiverUnknownSighash(bytes data) external envfree;
    function _.foo(uint) external => DISPATCH(optimistic=true) [ C._, D._ ];
    function _.bar(uint) external => DISPATCHER(true);
    function _.iTakeMoreComplicatedTypes(MyInterface.S s, MyInterface.E e) external => DISPATCH(optimistic=true) [ D._, E._ ];
    function _.iDontReturnAnything() external => DISPATCH(optimistic=true, use_fallback=true) [ C._, D._ ];
    unresolved external in C.testKnownReceiverUnknownSighash(bytes) => DISPATCH(optimistic=true, use_fallback=true) [ F._ ];
}

rule testDispatch {
    uint x;
    uint res = testFoo(x);
    assert res == 1 || res == 2;
}

rule testDispatch2 {
    uint res = testITakeMoreComplicatedTypes();
    assert res == 22 || res == 23;
}

rule testDispatchFallback {
    require !d.fallbackCalled, "to set up value";
    require !currentContract.nonReturningCalled, "to set up value";
    testIDontReturnAnything();
    assert currentContract.nonReturningCalled || d.fallbackCalled || f.fallbackCalled;
    satisfy currentContract.nonReturningCalled;
    satisfy d.fallbackCalled;
}

rule testNoDispatchOnResolved {
    uint res = testKnownReceiver();
    assert res == 4;
}

rule testFallbackDispatchOnUnresolved {
    require !d.fallbackCalled, "to set up value";
    require !f.fallbackCalled, "to set up value";
    require !f.fooCalled, "to set up value";
    require !f.barCalled, "to set up value";
    require !f.complicatedCalled, "to set up value";
    calldataarg args;
    testKnownReceiverUnknownSighash(args);
    assert f.fallbackCalled || f.fooCalled || f.barCalled || f.complicatedCalled;
    assert !d.fallbackCalled;
    satisfy f.fallbackCalled;
}

rule testDispatcher {
    uint x;
    uint res = testBar(x);
    assert res == 11 || res == 12 || res == 13 || res == 14;
}
