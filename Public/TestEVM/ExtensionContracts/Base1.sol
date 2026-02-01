import "Immutable.sol";

contract Base1 is Immutable {
    uint inExtension1;
    uint inExtension2;

    address extension1Address; // Address of contract Extension1

    constructor(address _extension1Address) {
        extension1Address = _extension1Address;
    }

    function callSetInExtension1(uint _num) external {
        address(this).call(abi.encodeWithSignature("setInExtension1(uint256)", _num));
    }

    function callGetInExtension1() external returns (uint) {
        (bool success, bytes memory b) = address(this).call(abi.encodeWithSignature("getInExtension1()"));
        return abi.decode(b, (uint));
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
        (bool success, ) = extension1Address.delegatecall(msg.data);
        require(success, "Delegatecall failed");
    }

    function getImmut() public view returns (bytes32) {
        return IMMUT1;
    }

    bool b;
    function flipB() public {
        b = !b;
    }
}
