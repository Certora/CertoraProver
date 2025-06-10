methods {
    function getRandomValue(uint256) external returns (uint256) envfree;
    function getDoubleRandom(uint256, uint256) external returns (uint256) envfree;
    function getRandomFromArray(uint256[]) external returns (uint256) envfree;
    function getRandomModulo(uint256, uint256) external returns (uint256) envfree;
    function processStructData(uint256, bytes32) external returns (uint256) envfree;
}

// Test that random values are non-zero
rule testRandomValue {
    uint256 seed;
    require seed != 0;

    uint256 result = getRandomValue(seed);
    assert result != 0;
}

// Test that different seeds produce different results
rule testDifferentSeeds {
    uint256 seed1;
    uint256 seed2;
    require seed1 != seed2;

    uint256 result1 = getRandomValue(seed1);
    uint256 result2 = getRandomValue(seed2);

    assert result1 != result2;
}

// Test the double random function
rule testDoubleRandom {
    uint256 seed1;
    uint256 seed2;
    require seed1 != 0 && seed2 != 0;

    uint256 result = getDoubleRandom(seed1, seed2);
    assert result != 0;
}

// Test array parameter handling
rule testArrayParameter {
    uint256 seed1;
    uint256 seed2;
    uint256 seed3;
    require seed1 != 0 && seed2 != 0 && seed3 != 0;

    // Note: Certora doesn't support dynamic arrays directly,
    // so we test the concept
    env e;
    uint256 result = getRandomFromArray@withrevert(e, [seed1, seed2, seed3]);
    assert !lastReverted => result != 0;
}

// Test deeper nesting with modulo operation
rule testDeepNesting {
    uint256 seed;
    uint256 modulus;
    require seed != 0 && modulus > 1;

    uint256 result = getRandomModulo(seed, modulus);
    assert result < modulus;
}

// Test struct parameter handling
rule testStructParameter {
    uint256 value;
    bytes32 hash;
    require value != 0;

    uint256 result = processStructData(value, hash);
    assert result != 0;
}

// Test that nested calls preserve deterministic behavior
rule testDeterministicNesting {
    uint256 seed;
    uint256 modulus;
    require seed != 0 && modulus > 1;

    uint256 result1 = getRandomModulo(seed, modulus);
    uint256 result2 = getRandomModulo(seed, modulus);

    assert result1 == result2;
}
