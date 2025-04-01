interface VM {
    function sign(uint256 privateKey, bytes32 digest) external returns (uint8 v, bytes32 r, bytes32 s);

}

library L {
    function makeAddrAndKey(string memory name) internal returns(address addr, uint256 privateKey) {}
    function assertEq(address a, address b) internal {}
}
contract C {
    VM vm;

    function test_signing(bytes memory toHash) external {
        (address alice, uint256 alicePk) = L.makeAddrAndKey("alice");

        bytes32 hash = keccak256(toHash);
        (uint8 v, bytes32 r, bytes32 s) = vm.sign(alicePk, hash);
        address signer = ecrecover(hash, v, r, s);
        L.assertEq(alice, signer); // [PASS]
    }
}
