methods {
    function _._ external => DISPATCH [
        D.withExtraCalldata()
    ] default HAVOC_ALL;
}

rule r {
    env e;
    uint u; uint v; uint w;
    uint ret_u; uint ret_v; uint ret_w;
    (ret_u, ret_v, ret_w) = callWithExtraCalldata(e, u, v, w);
    assert ret_u == u;
    assert ret_v == v;
    assert ret_w == w;
}
