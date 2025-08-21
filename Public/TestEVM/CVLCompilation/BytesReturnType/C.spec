methods {
    function getNames(bytes32) external returns bytes envfree;
}

rule checkGetNames(bytes32 node) {
    getNames(node); // In CVL1 this would trigger an internal error because it assumed the return value should be tagged bv256
    assert false;
}
