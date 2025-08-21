pragma solidity ^0.8.4;


contract TestContract {
    uint curBal;

    function addMoney(uint num) public {
        curBal += num;
    }

    function decMoney(uint num) public {
        curBal -= num;
    }
}