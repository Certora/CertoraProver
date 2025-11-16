pragma solidity >=0.8.0;

import {IReceiver} from "./IReceiver.sol";

contract receiverC is IReceiver {

    function deposit() external payable override {
        depositCalled = true;
    }
}