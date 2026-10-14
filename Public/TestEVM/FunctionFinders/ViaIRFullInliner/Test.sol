// SPDX-License-Identifier: MIT
pragma solidity >=0.8.0;

contract Test {
    uint256 public total;

    // Called from exactly one place: what the Yul full inliner exists to remove.
    function calledOnce(uint256 a) internal view returns (uint256) {
        return a + total;
    }

    // Called from two places, so inlining it duplicates the body.
    function calledTwice(uint256 a) internal pure returns (uint256) {
        return a * 3;
    }

    function outerOnce(uint256 x) external view returns (uint256) {
        return calledOnce(x);
    }

    function outerTwice(uint256 x) external pure returns (uint256) {
        return calledTwice(x) + calledTwice(x + 1);
    }
}
