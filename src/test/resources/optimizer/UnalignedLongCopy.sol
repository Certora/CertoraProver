// SPDX-License-Identifier: GPL-2.0-or-later
pragma solidity ^0.8.0;

contract UnalignedLongCopy {
    // calldatacopy into a destination offset that is not word aligned, with a run-time length,
    // and a call that then reads that region back out.
    function probe() external view returns (uint256 out) {
        assembly {
            calldatacopy(36, 0, calldatasize())
            out := staticcall(gas(), address(), 0, add(calldatasize(), 36), 0, 0)
        }
    }
}
