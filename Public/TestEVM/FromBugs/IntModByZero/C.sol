// SPDX-License-Identifier: MIT
pragma solidity 0.8.21;

library Math {
    // Replaced by a CVL summary in the spec; only the signature matters here.
    function ceilDiv(uint256 x, uint256 denominator) internal pure returns (uint256) {
        return x / denominator;
    }
}

interface IPriceOracle {
    function getPrice() external view returns (uint256);
}

// The divisor handed to the summarized library call is derived from the oracle price, so
// constant propagation can pin it to a concrete value.
contract C {
    function calc(uint256 amount, IPriceOracle oracle) external view returns (uint256) {
        uint256 price = oracle.getPrice();
        return Math.ceilDiv(amount, price % 1e6);
    }
}
