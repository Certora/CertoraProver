pragma solidity ^0.4.25;

contract Bank {
    mapping (address => uint256) public funds;

    function deposit(uint256 amount) public payable {
	 funds[msg.sender] += amount;
    }

    function transfer(address to, uint256 amount) public {
	 require(funds[msg.sender] > amount);
	 funds[msg.sender] -= amount;
	 funds[to] += amount;
    }

    function withdraw() public returns (bool success)  {
		uint256 amount = funds[msg.sender];
		funds[msg.sender] = 0;
	success = msg.sender.send(amount);
    }

    function withdraw(uint256 amount) public returns (bool success)  {
	 require( amount <= funds[msg.sender]);
	 funds[msg.sender] = funds[msg.sender] - amount;
         success = msg.sender.send(amount);
    }

	function getfunds(address account) public view returns (uint256) {
		return funds[account];
	}


	function ercBalance() public view returns (uint256) {
        return msg.sender.balance;
    }
	function init_state() public pure {}

}