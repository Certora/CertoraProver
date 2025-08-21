contract C {
    address target;
    function callWithExtraCalldata(uint256 u, uint v, uint w) external returns (uint, uint, uint) {
        address _target = target;
        bytes memory data = abi.encodeWithSignature("withExtraCalldata()", u, v, w);

        assembly {
            let success := call(gas(), _target, 0, add(data, 32), mload(data), 0, 0)

            returndatacopy(0, 0, returndatasize())
            if iszero(success) {
                revert(0, returndatasize())
            }
            return(0, returndatasize())
        }
    }
}

contract D {
    function withExtraCalldata() external returns (uint u, uint v, uint w) {
        assembly ("memory-safe") {
            u := calldataload(sub(calldatasize(), 96))
            v := calldataload(sub(calldatasize(), 64))
            w := calldataload(sub(calldatasize(), 32))
        }
    }
}
