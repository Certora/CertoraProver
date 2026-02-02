contract Immutable {
    bytes32 public immutable IMMUT1 = initImmut("IMMUT1");
    bytes32 public immutable IMMUT2 = initImmut("IMMUT2");

    function initImmut(string memory key) private pure returns (bytes32) {
        return keccak256(abi.encode(key));
    }
}
