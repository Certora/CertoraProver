interface I {
    function warp(uint256 newTimestamp) external;
}

contract C {
    I i;
    uint ts;
    function assertEq(uint a, uint b) internal pure {}

    function testWarp(bool secondCall, uint n) external returns (uint) {
        if (secondCall) {
            assertEq(ts + n, block.timestamp);
        } else {
            ts = block.timestamp;
        }

        // Simple warp usage
        i.warp(block.timestamp + 1);
        assertEq(ts + n + 1, block.timestamp);

        // block.timestamp here should be the "new" value
        i.warp(block.timestamp + 1);
        assertEq(ts + n + 2, block.timestamp);

        // test consistency across function calls
        i.warp(block.timestamp + 1);
        assertEq(ts + this.testWarpCalled(n + 3), block.timestamp);
        return n + 4;
    }

    function testWarpCalled(uint n) external returns (uint) {
        assertEq(ts + n, block.timestamp);
        i.warp(block.timestamp + 1);
        assertEq(ts + n + 1, block.timestamp);
        return n + 1;

    }
}
