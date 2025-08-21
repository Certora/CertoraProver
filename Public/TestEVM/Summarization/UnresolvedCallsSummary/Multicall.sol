// SPDX-License-Identifier: GPL-2.0-or-later
pragma solidity ^0.8.0;
//pragma solidity =0.7.6;
//pragma abicoder v2;


contract MulticallEnhanced {
    mapping(address => uint256) private balances;

    function multicall(bytes[] memory data) public returns (bytes[] memory results) {
        results = new bytes[](data.length);
        for (uint256 i = 0; i < data.length; i++) {
            (bool success, bytes memory result) = address(this).delegatecall(data[i]);

            if (!success) {
                // Next 5 lines from https://ethereum.stackexchange.com/a/83577
                if (result.length < 68) revert();
                assembly {
                    result := add(result, 0x04)
                }
                revert(abi.decode(result, (string)));
            }

            results[i] = result;
        }
    }

    function getBalance(address user) public view returns (uint256) {
        return balances[user];
    }

    function sumBalances(
        address[] memory users
    ) public returns (uint256) {
        bytes[] memory calls = new bytes[](users.length);
        for (uint256 i = 0; i < users.length; i++) {
            calls[i] = abi.encodeWithSignature("getBalance(address)", users[i]);
        }

        bytes[] memory results = multicall(calls);
        uint256 sum = 0;
        for (uint256 i = 0; i < results.length; i++) {
            sum += abi.decode(results[i], (uint256));
        }
        return sum;
    }

    function sumOfThree(
        address userA,
        address userB,
        address userC
    ) public returns (uint256) {
        address[] memory users = new address[](3);
        users[0] = userA;
        users[1] = userB;
        users[2] = userC;
        return sumBalances(users);
    }
}
