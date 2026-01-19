rule timeStampFitsInUint40(env e) {
    uint timestamp = getTimestamp(e);
    uint40 checked = assert_uint40(timestamp);
    uint32 checked2 = assert_uint32(getTimestampDays(e));
    assert true;
}

rule worksAlsoWithManualEnvVars {
    env e1;
    uint timestamp1 = getTimestamp(e1);
    uint40 checked1 = assert_uint40(timestamp1);
    env e2;
    uint timestamp2 = getTimestamp(e2);
    uint40 checked2 = assert_uint40(timestamp2);
    assert true;
}
