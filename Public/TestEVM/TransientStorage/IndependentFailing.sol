contract IndependentFailing {
    mapping(uint => bool) normalStorageData;

    // loading from an arbitrary slot will make the transient storage analysis fail,
    // but we want any accesses to normal storage to still work as expected
    function tload(bytes32 slot) external view returns (bytes32 value) {
        /// @solidity memory-safe-assembly
        assembly {
            value := tload(slot)
        }
    }

    function test(bytes32 slot, uint x, bool b) external returns (bytes32) {
        normalStorageData[x] = b;
        return this.tload(slot);
    }

}
