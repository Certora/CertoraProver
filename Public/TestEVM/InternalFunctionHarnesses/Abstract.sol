
struct S {
    uint u;
    int i;
}

abstract contract Abstract {
    /// @custom:storage-location erc7201:Internal.Function.Harnesses
    struct HarnessesStorage {
        S s;
    }

    /// @dev keccak256(abi.encode(uint256(keccak256("Internal.Function.Harnesses")) - 1)) & ~bytes32(uint256(0xff))
    bytes32 private constant HARNESSES_STORAGE_LOCATION = 0xf9bbc9cb9bf986592ac78fbb1e5dc3f9a293ed8092899e754d3568efca86d800;

    function _getHarnessesStorage() internal pure returns (HarnessesStorage storage $) {
        assembly {
            $.slot := HARNESSES_STORAGE_LOCATION
        }
    }

    function _noImplementation(S memory s) internal virtual;

    function _withImplementation(address payable a) internal virtual returns (uint256) {
        return 0;
    }

    function _privateFunc() private {}
}
