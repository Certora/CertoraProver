contract Test {
    struct InnerStruct {
        uint24 a;
        bytes24 b;
    }

    struct OuterStruct {
        InnerStruct c;
        int32 d;
    }

    OuterStruct public s;

    function getOuterStruct(uint24 a, bytes24 b, int32 d) external returns (OuterStruct memory) {
        OuterStruct memory ret = OuterStruct(InnerStruct(a, b), d);
        return ret;
    }
}
