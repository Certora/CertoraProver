pragma solidity ^0.8.10;

contract C {
    uint x;
    uint y;
    bool fallbackCalled;

    constructor() public {
        y = 1;
        fallbackCalled = false;
    }

    fallback() external payable {
        fallbackCalled = true;
    }

    function update(uint a) public returns (uint) {
        uint temp = x + a;
        x = temp;
        return temp;
    }

    function bar(uint t) public {
        uint temp = x + t;
        x = temp;
    }

    function r1(uint x) public returns(uint) {
        return 1;
    }

    function r2(uint x) public returns(uint) {
        return 2;
    }

    function mem(uint[] memory arr) public view returns(uint) {
        return arr[x];
    }

    function strg(uint[] calldata arr) public view returns(uint) {
        return arr[x];
    }

    function unresolved(address a, bytes memory encoding) public returns(bool) {
        (bool success, bytes memory data) = a.call(encoding);
        return success;
    }

    function abicall(uint a) public returns(bool) {
        (bool success, bytes memory data) = address(this).call(abi.encodeWithSignature("update(uint256)", a));
        return success;
    }

    function abicallNotExist(uint a) public payable returns(bool) {
        (bool success, ) = address(this).delegatecall(abi.encodeWithSignature(""));
        return success;
    }

    function delegate(address a, bytes memory encoding) public returns(bool) {
        (bool success, bytes memory data) = a.delegatecall(encoding);
        return success && x < 0xffffffffffffffff;
    }

    function updatey() public {
        y = 2;
    }

    /**
    * Runs an unresolved call but forces it to be one of two sighashes.
    * We are using this to check that two sighashes won't crash, and
    * that we will call the default case.
    */
    function unresolvedOneOfTwo(address a, bytes memory encoding, uint chooser) public returns(uint) {
        encoding[0] = bytes1(0);
        encoding[1] = bytes1(0);
        encoding[2] = bytes1(0);
        if (chooser == 0) {
            encoding[3] = bytes1(uint8(2));
        } else {
            encoding[3] = bytes1(uint8(3));
        }
        (bool success, bytes memory b) = a.call(encoding);
        uint256 number;
        for(uint i=0;i<b.length;i++){
            number = number + uint(uint8(b[i]))*(2**(8*(b.length-(i+1))));
        }
        return number;
    }

    /**
    * Runs an unresolved call but forces it to be one of two sighashes.
    * We are using this to check that two sighashes won't crash, and
    * that we will **not** call the default case, by making the sighashes be
    * known.
    */
    function unresolvedOneOfTwoKnown(address a, uint chooser, uint val) public returns(uint) {
        bytes memory calld = chooser == 0 ? abi.encodeWithSignature("r1(uint256)", val) : abi.encodeWithSignature("r2(uint256)", val);
        require(a == address(this));
        (bool success, bytes memory b) = a.call(calld);
        uint256 number;
        for(uint i=0;i<b.length;i++){
            number = number + uint(uint8(b[i]))*(2**(8*(b.length-(i+1))));
        }
        return number;
    }

    function unresolvedOneOfTwoNotExistingKnown(address a, uint chooser, uint val) public returns(uint) {
        bytes memory calld = chooser == 0 ? abi.encodeWithSignature("not_really_exists(uint256)", val) : abi.encodeWithSignature("definitely_not_exists(uint256)", val);
        require(a == address(this));
        (bool success, bytes memory b) = a.call(calld);
        uint256 number;
        for(uint i=0;i<b.length;i++){
            number = number + uint(uint8(b[i]))*(2**(8*(b.length-(i+1))));
        }
        return number;
    }
}
