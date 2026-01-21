contract C {
    struct S {
        uint a;
        bool[3][2] b;
    }

    function foo(S memory s) external {}
    function foo() external {}

    function bar(S memory s) external {}

    function qux() external {}
    function qux(S memory s) external {}
}

contract D {
    struct S {
        uint a;
        bool[3][2] b;
    }

    function bar(S memory s) external {}
    function baz(S memory s) external {}
}

contract E {
    struct S {
        uint a;
        bool[3][2] b;
    }

    function foo(S memory s) external {}
    function baz(S memory s) external {}
}
