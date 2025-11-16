contract Minimal {
    function payloaddd() internal view returns (bytes memory payload) {
        payload = abi.encodePacked(bytes2(0x0003));
    }
}
