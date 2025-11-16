// SPDX-License-Identifier: agpl-3.0
pragma solidity >=0.8.0;

abstract contract IReceiver {

    bool public depositCalled;
    bool public receiveCalled;
    bool public fallbackCalled;

    function deposit() external payable virtual;
}