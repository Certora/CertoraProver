contract C {
    struct S {
        uint x;
        int y;
    }

    S s;
    function returnsStruct() external returns (C.S memory) { return s; }
    function returnsTuple() external returns (bytes memory, int) { return (new bytes(10), 1); }

}
