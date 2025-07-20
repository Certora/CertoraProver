contract StructLink {
    struct S {
        uint ignoredField1;
        mapping(int => bool) ignoredField2;
        address boolPointer;
    }

    address ignoredSlot;
    S public s;

    function getBoolPointer() external returns (address) {
        return s.boolPointer;
    }
}

interface Bool {
    function returnsBool() external returns (bool);
}

contract True is Bool {
    function returnsBool() external returns (bool) { return true; }
}
contract False is Bool {
    function returnsBool() external returns (bool) { return false; }
}
