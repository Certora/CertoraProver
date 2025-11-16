pragma solidity >=0.8.0;

import {IReceiver} from "./IReceiver.sol";

contract Bank {
    address public receiver;

    function getEthBalance(address account) public view returns (uint) {
        return account.balance;
    }

    function isEOA(address account) public view returns (bool) {
        uint size;
        assembly {
            size := extcodesize(account)
        }
        return (size == 0);
    }

    function callDeposit(uint256 amount) public {
	   IReceiver(receiver).deposit{value: amount}();
    }

    function sendEth(uint256 amount) public {
        payable(receiver).transfer(amount);
    }

    function depositLowLevelCall(uint256 amount) public returns (bool) {
	(bool success, ) = receiver.call{value: amount}
            (abi.encodeWithSignature("deposit()"));
        return success;
    }

    function tisopedLowLevelCall(uint256 amount) public returns (bool) {
		(bool success, ) = receiver.call{value: amount}
                (abi.encodeWithSignature("tisoped()"));
            return success;
    }

    // notice: calling receiver.call{value: amount}(abi.encodeWithSignature(""))
    // will result in a havoc even if optimisticFallback=true
    function emptyCall(uint256 amount) public returns (bool) {
        (bool success, ) = receiver.call{value: amount}("");
        return success;
    }

    function sendEthViaCall(uint256 amount, address account) public returns (bool) {
        (bool success, ) = account.call{value: amount}("");
            return success;
    }

    function depositTryCatch(uint256 amount) public returns (bool) {
	 try IReceiver(receiver).deposit{value: amount}() {
            return true;
	 }
	 catch {
            return false;
	 }
    }

    // Getters for receiver contract
    function getDepositCalled() public view returns (bool)
    {
        return IReceiver(receiver).depositCalled();
    }

    function getFallbackCalled() public view returns (bool)
    {
        return IReceiver(receiver).fallbackCalled();
    }

    function getReceiveCalled() public view returns (bool)
    {
        return IReceiver(receiver).receiveCalled();
    }

}