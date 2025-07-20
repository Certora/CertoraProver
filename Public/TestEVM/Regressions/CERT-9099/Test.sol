contract Test {
    function manualCompare(address a, uint x, bool y) external returns (bool) {
        bytes memory b = abi.encodeWithSignature("manualCompare(address,uint256,bool)", a, x, y);
        return keccak256(b) == keccak256(msg.data);
    }
}