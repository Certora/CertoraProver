pragma solidity >=0.8.0;

import {IReceiver} from "./IReceiver.sol";

contract receiverB is IReceiver {

    function deposit() external payable override {
        depositCalled = true;
    }

    fallback() external payable {
        fallbackCalled = true;
    }
}