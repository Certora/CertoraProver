// SPDX-License-Identifier: UNLICENSED
pragma solidity ^0.8.0;

interface IAllocator {
    function withdraw(uint256 amount) external;
}

contract Allocator {
    address[] internal strategies;

    function step(uint256 amount) external view returns (uint256) {
        return amount;
    }

    /**
     * The loop condition is a short-circuiting conjunction, so the unroller cannot
     * identify a single exit condition and unrolls one extra iteration into a
     * synthetic assert/assume-false sink.
     */
    function withdraw(uint256 amount) external {
        uint256 remaining = amount;
        for (uint256 i = 0; remaining > 0 && i < strategies.length; i++) {
            uint256 got = this.step(uint256(uint160(strategies[i])));
            remaining = got >= remaining ? 0 : remaining - got;
        }
    }
}

contract Vault {
    address internal immutable ALLOCATOR;

    constructor(address allocator) {
        ALLOCATOR = allocator;
    }

    // The linked call is inlined before unrolling, so unrolling this loop has to
    // duplicate the call ids of the inlined callee.
    function claim(uint256[] calldata amounts) external {
        for (uint256 i = 0; i < amounts.length; i++) {
            IAllocator(ALLOCATOR).withdraw(amounts[i]);
        }
    }
}
