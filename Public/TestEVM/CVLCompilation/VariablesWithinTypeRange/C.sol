contract C {
    type T is bytes5;
    enum E {
        A, B
    }
    struct S {
        uint64 x;
        int128 y;
        bytes15 z;
    }

    function foo(uint24 x) public {}
    function foo(int16 x) public {}
    function foo(bytes4 x) public {}
    function foo(E x) public {}
    function foo(S memory x) public {}
    function foo(T x) public {}
    function foo(bytes29[] memory x) public {}
    function foo(S[4] memory x) public {}
}
