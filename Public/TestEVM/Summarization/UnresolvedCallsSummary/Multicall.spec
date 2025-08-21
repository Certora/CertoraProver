using MulticallEnhanced as _Multicall;

methods {
    function sumOfThree(address, address, address) external returns (uint256) envfree;
    function getBalance(address) external returns (uint256) envfree;
    unresolved external in _._ => DISPATCH [
       _Multicall.getBalance(address)
    ] default HAVOC_ALL;
}


rule exampleSumOfThree() {
    calldataarg args;
    uint256 total = sumOfThree(args);
    satisfy true;
}


rule testSumOfThree(address userA, address userB, address userC) {
    uint256 balanceA = getBalance(userA); // resolved - storage
    uint256 balanceB = getBalance(userB);
    uint256 balanceC = getBalance(userC);

    uint256 total = sumOfThree(userA, userB, userC); // Calls getBalance for each user through multicall
    assert to_mathint(total) == balanceA + balanceB + balanceC;
}
