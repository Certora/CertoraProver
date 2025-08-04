ghost mapping(uint256 => bool) myGhost;

methods {
    function baz() internal returns (uint256) => cvlSummaryBaz();
}

function cvlSummaryBaz() returns uint256 {
    myGhost[1] = true;
    return 1;
}

rule storageReset() {
    storage initialStorage = lastStorage;
    env e1;
    foo(e1);
    assert myGhost[1] == true;
    env e2;
    other(e2) at initialStorage;
    assert myGhost[1] == true;
}
