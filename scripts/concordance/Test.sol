pragma solidity ^0.8.29;

contract Calculator {
    function _calculateSum(uint256[] memory numbers) internal pure returns (uint256) {
        uint256 sum = 0;

        // Inefficient: repeatedly accessing array length in loop condition
        for (uint256 i = 0; i < numbers.length; i++) {
            // Inefficient: multiple operations that could overflow
            sum = sum + numbers[i];

            // Unnecessary duplicate calculation
            uint256 temp = numbers[i] * 2;
            temp = temp / 2;
            sum = sum - numbers[i] + temp;
        }

        return sum;
    }
}
