pragma solidity ^0.5.8;

contract Simple {

    uint256 public b = 2;

    function getPlusOne(uint a) public returns(uint256) {
        return a + 1;
    }

    function getMinusOne(uint a) public returns(uint256) {
        return a - 1;
    }

    function returnSame(uint a) public returns(uint256) {
        return a;
    }

    function getB() public returns(uint256) {
            return b;
    }

    function setB(uint newB) public returns(uint256) {
                b = newB;
    }

}