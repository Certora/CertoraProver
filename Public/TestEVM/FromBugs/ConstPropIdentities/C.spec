methods {
    function selfDiv(uint256) external returns (uint256) envfree;
    function oneMod(uint256) external returns (uint256) envfree;
    function zeroExp(uint256) external returns (uint256) envfree;
}

// False at x = 0, where evm gives 0 / 0 == 0.
rule selfDiv_is_one(uint256 x) {
    assert selfDiv(x) == 1;
}

// False at n = 1 (and at n = 0), where evm gives 1 % n == 0.
rule oneMod_is_one(uint256 n) {
    require n != 0;
    assert oneMod(n) == 1;
}

// False at e = 0, where evm gives 0 ** 0 == 1.
rule zeroExp_is_zero(uint256 e) {
    assert zeroExp(e) == 0;
}

// Controls: the edge cases themselves, which the constant folding of the simplifier resolves.
rule edge_cases() {
    assert selfDiv(0) == 0;
    assert oneMod(1) == 0;
    assert zeroExp(0) == 1;
}

// Control: `1 % n` is still simplified to 1 once the divisor is known to be greater than 1.
rule oneMod_above_one(uint256 n) {
    require n > 1;
    assert oneMod(n) == 1;
}
