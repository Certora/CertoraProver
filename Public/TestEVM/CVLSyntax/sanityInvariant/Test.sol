contract Test {

    uint256[] public balances;
    uint256[] private privateBalances;
    constructor() {
    }
    function foo() public returns (uint) {
        return 1;
    }

    function balanceLength() public returns (uint) {
       return balances.length;
    }

    function privateBalanceLength() public view returns (uint) {
       return privateBalances.length;
    }

    function getPrivateBalances(uint x) public view returns (uint) {
       return privateBalances[x];
    }
}