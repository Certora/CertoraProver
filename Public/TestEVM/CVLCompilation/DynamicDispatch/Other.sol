pragma solidity ^0.8.10;

contract Other {
    uint x;
    uint y;


    fallback() external { y = 555; }

    function update(uint a) public returns (uint) {
        uint temp = x + a;
        x = temp;
        return temp;
    }

    function foo(uint8 t) public {
        uint temp = x + t;
        x = temp;
    }

    function mem(uint[] memory arr) public view returns(uint) {
        return arr[x];
    }

    function strg(uint[] calldata arr) public view returns(uint) {
        return arr[x];
    }

    function updatey() public {
        y = 3;
    }
}
