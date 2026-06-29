methods {
    function test(bool cond, uint256 x) external returns (uint256) envfree;
    function _.foo(uint256 x) internal => sumFoo() expect uint256;
    function _.bar(uint256 x) internal => sumBar() expect uint256;
}

function sumFoo() returns uint256 {
    return 100;
}

function sumBar() returns uint256 {
    return 200;
}

rule test(bool cond, uint256 x) {
    uint256 res = test(cond, x);
    assert cond => res == 100;
    assert !cond => res == 200;
}
