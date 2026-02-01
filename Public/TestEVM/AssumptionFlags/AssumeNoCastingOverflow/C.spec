rule check1(int64 x) {
    env e;
    assert x == narrow(e, x);
}

rule check2(uint x) {
    env e;
    assert x == unsignedToSigned(e, x);
}