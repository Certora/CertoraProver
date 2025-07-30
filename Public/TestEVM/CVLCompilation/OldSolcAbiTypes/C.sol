pragma experimental ABIEncoderV2;

contract C {
    enum E {
        A,
        B
    }

    struct S {
        uint u;
        int i;
    }

    function returnTypes() external returns (C, E, S memory) {
        S memory s;
        return (this, E.A, s);
    }
}
