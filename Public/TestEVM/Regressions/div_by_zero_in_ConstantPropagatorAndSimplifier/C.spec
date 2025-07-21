rule r {
    env e;
    uint256 a;

    set0(e);
    uint256 res = foo(e, a);
    assert res == 0;
}
