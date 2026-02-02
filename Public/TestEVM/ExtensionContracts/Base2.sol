contract Base2 {
    uint unused;
    uint inExtension2;

    address extension2Address; // Address of contract Extension1

    constructor(address _extension2Address) {
        extension2Address = _extension2Address;
    }

    function callSetInExtension2(uint _num) external {
        address(this).call(abi.encodeWithSignature("setInExtension2(uint256)", _num));
    }

    function callGetInExtension2() external returns (uint) {
        (bool success, bytes memory b) = address(this).call(abi.encodeWithSignature("getInExtension2()"));
        return abi.decode(b, (uint));
    }

    // Fallback function will handle all calls that don't match a function signature
    fallback() external payable {
        // Delegate the call to contract B
        (bool success, ) = extension2Address.delegatecall(msg.data);
        require(success, "Delegatecall failed");
    }
}
