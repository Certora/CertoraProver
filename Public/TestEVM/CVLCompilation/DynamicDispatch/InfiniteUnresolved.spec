using Other as other;

methods {
    function C.delegate(address,bytes) external returns(bool);
    function _._ external => DISPATCH [
        C.delegate(address,bytes)
    ] default NONDET;
}

rule easy {
    env e;
    calldataarg arg;
    delegate(e, arg);
    assert true, "Expected fail.";
}
