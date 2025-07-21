pragma solidity >= 0.8.24;

contract Vault {
    bytes32 constant slot = keccak256("transient");
    uint256 storageValue;

    function tload(bytes32 key) internal returns (uint) {
        uint value;
        assembly {
            value := tload(key)
        }
        return value;
    }
    function tstore(bytes32 key, uint256 value) internal{
        assembly {
            tstore(key, value)
        }
    }
    function getDelta() public returns (uint256) {
        return tload(slot);
    }
    function addDelta(int256 delta) internal {
        uint256 value = getDelta();
        value = value + uint256(delta);
        tstore(slot, value);
    }

    function borrow(int256 delta) external {
        storageValue += uint256(delta);
        addDelta(delta);
    }

    function repay(int256 delta) external {
        storageValue -= uint256(delta);
        addDelta(-delta);
    }

    function identity(uint256 x) public returns (uint256) {
        return x;
    }
}
