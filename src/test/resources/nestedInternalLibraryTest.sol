// SPDX-License-Identifier: MIT
pragma solidity 0.8.29;

// First library with an internal function
library CryptographyHelpers {
    function calculateHashSumOf(uint256 value_) internal pure returns (uint256) {
        return uint256(keccak256(abi.encodePacked(value_)));
    }

    // Function with array parameter to test type layout inference
    function hashArray(uint256[] memory values) internal pure returns (uint256) {
        return uint256(keccak256(abi.encode(values)));
    }
}

// Second library that calls the first library's internal function
library RandomNumberHelpers {
    function generateRandomNumber(uint256 seed_) internal pure returns (uint256) {
        // This nested internal library call should be handled properly
        return CryptographyHelpers.calculateHashSumOf(seed_);
    }

    // Nested call with array parameter
    function generateRandomFromArray(uint256[] memory seeds) internal pure returns (uint256) {
        return CryptographyHelpers.hashArray(seeds);
    }
}

// Third library to test deeper nesting
library MathHelpers {
    function computeRandomModulo(uint256 value, uint256 modulus) internal pure returns (uint256) {
        // Nested call to another library's nested call
        uint256 randomValue = RandomNumberHelpers.generateRandomNumber(value);
        return randomValue % modulus;
    }
}

// Library with struct parameters to test type layout inference
library StructHelpers {
    struct Data {
        uint256 value;
        bytes32 hash;
    }

    function processData(Data memory data) internal pure returns (uint256) {
        return CryptographyHelpers.calculateHashSumOf(data.value);
    }
}

// Contract that uses the libraries
contract ExampleContract {
    using RandomNumberHelpers for uint256;
    using RandomNumberHelpers for uint256[];

    function getRandomValue(uint256 seed) external pure returns (uint256) {
        return RandomNumberHelpers.generateRandomNumber(seed);
    }

    // Test with array parameters
    function getRandomFromArray(uint256[] calldata seeds) external pure returns (uint256) {
        return RandomNumberHelpers.generateRandomFromArray(seeds);
    }

    // Test deeper nesting
    function getRandomModulo(uint256 seed, uint256 modulus) external pure returns (uint256) {
        return MathHelpers.computeRandomModulo(seed, modulus);
    }

    // Test with struct parameters
    function processStructData(uint256 value, bytes32 hash) external pure returns (uint256) {
        StructHelpers.Data memory data = StructHelpers.Data(value, hash);
        return StructHelpers.processData(data);
    }

    // Additional test case with multiple nested calls
    function getDoubleRandom(uint256 seed1, uint256 seed2) external pure returns (uint256) {
        uint256 random1 = RandomNumberHelpers.generateRandomNumber(seed1);
        uint256 random2 = RandomNumberHelpers.generateRandomNumber(seed2);
        return random1 ^ random2;
    }
}
