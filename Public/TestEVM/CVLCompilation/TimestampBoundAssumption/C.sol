// SPDX-License-Identifier: agpl-3.0
pragma solidity >=0.8.10;

contract C {
    function getTimestamp() public view returns (uint256 value) {
        value = block.timestamp;
    }
    function getTimestampDays() public view returns (uint256 value) {
        value = block.timestamp / 1 days;
    }
}
