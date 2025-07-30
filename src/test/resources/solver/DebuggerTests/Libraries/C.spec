methods {
    function add_withLibrary(uint, uint) external returns (uint) envfree;
}

rule add_withLibrary() {
    uint x;
    uint y;
    uint z = add_withLibrary(x, y);
    assert z == x + y;
}
