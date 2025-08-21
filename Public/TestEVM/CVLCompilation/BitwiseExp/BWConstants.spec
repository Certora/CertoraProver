rule arithrshift {
    assert 0xffff >> 32 == 0;
}

rule logicrshift {
    assert 0xffff >>> 32 == 0;
}

rule arithrshiftneg {
    assert -1 >> 32 == -1;
}

rule logicrshiftneg {
    assert -1 >>> 1 > 0;
}

rule lshift {
    assert 0xff << 256 == 0;
}
