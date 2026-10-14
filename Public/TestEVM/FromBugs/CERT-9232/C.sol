// SPDX-License-Identifier: MIT
pragma solidity ^0.8.13;

interface IReader {
    function echoOne(address key) external pure returns (address);
    function echoTwo(address key, uint256 tag) external pure returns (address, uint256);
}

// Echoes its arguments, so a rule can pin the exact bytes that arrived rather than compare two
// reads of shared state.
contract Impl is IReader {
    function echoOne(address key) external pure returns (address) {
        return key;
    }

    function echoTwo(address key, uint256 tag) external pure returns (address, uint256) {
        return (key, tag);
    }
}

// Copies its whole calldata buffer over the scratch pad and forwards it, so its calldata layout
// holds word aligned byte ranges next to the ABI aligned ones.
contract ViewProxy {
    address public viewImplementation;

    // Declared with the callee's signatures rather than as a fallback: the forwarded buffer is what
    // gives this method's calldata layout its word aligned ranges, and a bare fallback does not
    // produce them.
    function echoOne(address) external view returns (address) {
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

    function echoTwo(address, uint256) external view returns (address, uint256) {
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

    // One argument: calldata is 36 bytes, so the word at offset 32 covers the low 4 bytes of the
    // address and then runs past the end of the buffer.
    function callEchoOne(address key) external view returns (address) {
        return IReader(proxy).echoOne(key);
    }

    // Two arguments: calldata is 68 bytes, so the word at offset 32 straddles the tail of the
    // first argument and the head of the second, and the word at offset 64 is live as well.
    function callEchoTwo(address key, uint256 tag) external view returns (address, uint256) {
        return IReader(proxy).echoTwo(key, tag);
    }
}
