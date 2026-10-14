// SPDX-License-Identifier: UNLICENSED
pragma solidity ^0.8.0;

// The read-only half of a split contract: the entire calldata buffer is copied over the scratch
// pad and forwarded to the implementation with a staticcall. Reading calldata at word granularity
// like this makes the calldata layout of readValue hold a word aligned byte range (at offset 32)
// next to the ABI aligned ones.
contract ViewProxy {
    address public viewImplementation;

    function readValue(address) external view returns (uint64 value) {
        address impl = viewImplementation;
        assembly {
            calldatacopy(0, 0, calldatasize())
            let ok := staticcall(gas(), impl, 0, calldatasize(), 0, 0)
            returndatacopy(0, 0, returndatasize())
            switch ok
            case 0 { revert(0, returndatasize()) }
            default { return(0, returndatasize()) }
        }
    }
}

contract Caller {
    address public proxy;

    function callReadValue(address key) external view returns (uint64) {
        return ViewProxy(proxy).readValue(key);
    }
}
