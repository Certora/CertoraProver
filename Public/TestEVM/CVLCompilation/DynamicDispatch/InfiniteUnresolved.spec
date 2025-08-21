using Other as other;

methods {
    function C.delegate(address,bytes) external returns(bool);
    unresolved external in _._ => DISPATCH [
        C.delegate(address,bytes)
    ] default NONDET;
}

rule easy {
    env e;
    calldataarg arg;
    delegate(e, arg);
    assert true, "Expected fail.";
}
