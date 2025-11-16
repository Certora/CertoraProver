contract Minimal {
    function payloaddd() external view returns (bytes memory payload) {
        payload = abi.encodePacked(bytes2(0x0003));
    }
}
