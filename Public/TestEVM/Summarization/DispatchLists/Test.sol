interface MyInterface {
    function foo(uint x) external returns (uint);
    function bar(uint x) external returns (uint);
    function iTakeMoreComplicatedTypes(S memory s, E e) external returns (uint);
    function iDontReturnAnything() external;

    struct S { uint x; }
    enum E { A, B }
}

contract C {
    MyInterface receiver;
    MyInterface.S s;
    MyInterface.E e;
    F f; // linked to F
    bool nonReturningCalled;

    function testFoo(uint x) external returns (uint) {
        return receiver.foo(x);
    }
    function testBar(uint x) external returns (uint) {
        return receiver.bar(x);
    }
    function testITakeMoreComplicatedTypes() external returns (uint) {
        return receiver.iTakeMoreComplicatedTypes(s, e);
    }
    function testIDontReturnAnything() external {
        receiver.iDontReturnAnything();
    }
    function testKnownReceiver() external returns (uint) {
        return f.foo(42);
    }
    function testKnownReceiverUnknownSighash(bytes memory data) external {
        (bool success, bytes memory ret) = address(f).call(data);
        require(success);
    }

    function foo(uint x) external returns (uint) {
        return 1;
    }
    function bar(uint x) external returns (uint) {
        return 11;
    }
    function iTakeMoreComplicatedTypes(MyInterface.S memory s, MyInterface.E e) external returns (uint) {
        return 21;
    }
    function iDontReturnAnything() external {
        nonReturningCalled = true;
    }
}

contract D {
    bool fallbackCalled;
    function foo(uint x) external returns (uint) {
        return 2;
    }
    function foo(int x) external returns (uint) { // an overload that we should not dispatch to
        return 666;
    }
    function bar(uint x) external returns (uint) {
        return 12;
    }
    function iTakeMoreComplicatedTypes(MyInterface.S memory s, MyInterface.E e) external returns (uint) {
        return 22;
    }
    fallback() external {
        fallbackCalled = true;
    }
}

contract E {
    function foo(uint x) external returns (uint) {
        return 3;
    }
    function bar(uint x) external returns (uint) {
        return 13;
    }
    function iTakeMoreComplicatedTypes(MyInterface.S memory s, MyInterface.E e) external returns (uint) {
        return 23;
    }
}

contract F {
    bool fallbackCalled;
    bool fooCalled;
    bool barCalled;
    bool complicatedCalled;
    function foo(uint x) external returns (uint) {
        fooCalled = true;
        return 4;
    }
    function bar(uint x) external returns (uint) {
        barCalled = true;
        return 14;
    }
    function iTakeMoreComplicatedTypes(MyInterface.S memory s, MyInterface.E e) external returns (uint) {
        complicatedCalled = true;
        return 24;
    }
    fallback() external {
        fallbackCalled = true;
    }
}
