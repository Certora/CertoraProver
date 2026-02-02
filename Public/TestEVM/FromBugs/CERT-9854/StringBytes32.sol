contract String {

    mapping(bytes32 name => uint256 pointer) public nameToUint;
    function myUint() public returns (uint256) {
        string memory name = type(String).name;
        bytes32 nameHash = keccak256(abi.encodePacked(name));
        return nameToUint[nameHash];
    }

    function getKey(string memory input) public returns (bytes32){
        string memory name = input;
        bytes32 nameHash = keccak256(abi.encodePacked(name));
        return nameHash;
    }
}
