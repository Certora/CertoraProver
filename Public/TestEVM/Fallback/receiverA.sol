pragma solidity >=0.8.0;

import {IReceiver} from "./IReceiver.sol";

contract receiverA is IReceiver {

    bool revertDeposit;

    function deposit() external payable override {
        require(!revertDeposit);
        depositCalled = true;
    }

    receive() external payable {
        receiveCalled = true;
    }

    fallback() external payable {
        fallbackCalled = true;
    }
}