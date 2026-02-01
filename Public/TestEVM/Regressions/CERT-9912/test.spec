methods {
    function _.doThing(uint[], address) external => 5 expect uint256;
}

rule test_summary {
    env e;
    uint[] data;
    address arg;
    assert doThing(e, data, arg) == 5;
}