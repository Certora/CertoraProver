rule constants {
    assert 0x1 | 0x0 == 0x1;
    assert max_uint256 | 0x01010101 == max_uint256;
    assert 0x0ff00f | 0xf00ff0 == 0xffffff;
}
