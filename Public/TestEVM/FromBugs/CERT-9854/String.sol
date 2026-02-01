contract String {
    function getContractNameTest() public pure returns (string memory) {
        return type(String).name;
    }

    function compareWithContractName(string memory input) public pure returns (bool) {
        return compareStrings(type(String).name, input);
    }

    function compareStrings(string memory _a, string memory _b) public pure returns(bool) {
        return keccak256(abi.encodePacked(_a)) == keccak256(abi.encodePacked(_b));
    }

    mapping(string name => address pointer) public nameToAddress;
    mapping(uint256 name => address pointer) public uintToAddress;
    mapping(string name => uint256 pointer) public nameToUint;

    function myAddress() public returns (address) {
        return nameToAddress[type(String).name];
    }

    function uintToAddressAt1() public returns (address) {
        return uintToAddress[1];
    }

    function myUint() public returns (uint256) {
        return nameToUint[type(String).name];
    }

    function myUint(string memory input) public returns (uint256) {
        return nameToUint[input];
    }

    function myName() public returns (string memory){
        return type(String).name;
    }

    function myAddressNotZero() public returns (bool){
        return nameToAddress[type(String).name] != address(0);
    }
}
