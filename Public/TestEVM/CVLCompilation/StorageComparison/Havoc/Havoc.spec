rule testHavoc() {
    env e;
    uint before = currentContract.i;
    storage init = lastStorage;

    changeI(e, 2);

    havoc currentContract.i;
    require currentContract.i == before, "putting back the old value";

    // this should succeed thanks to the havoc and require
    assert lastStorage == init;
}
