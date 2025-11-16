methods {
    function getEthBalance(address) external returns (uint) envfree;
    function callDeposit(uint256) external envfree;
    function depositLowLevelCall(uint256) external returns (bool) envfree;
    function tisopedLowLevelCall(uint256) external returns (bool) envfree;
    function emptyCall(uint256) external returns (bool) envfree;
    function depositTryCatch(uint256) external returns (bool) envfree;
    function receiver() external returns (address) envfree;
    function isEOA(address) external returns (bool) envfree;
    function sendEthViaCall(uint256, address) external returns (bool) envfree;

    // Receiver booleans
    function getDepositCalled() external returns (bool) envfree;
    function getFallbackCalled() external returns (bool) envfree;
    function getReceiveCalled() external returns (bool) envfree;
}

rule ethBalanceIntegrity_deposit(uint256 amount) {
    address sender = currentContract;

    mathint balanceBank_Before = getEthBalance(sender);
    mathint balanceRecipient_Before = getEthBalance(receiver());

    bool success = depositLowLevelCall(amount);

    mathint balanceBank_After = getEthBalance(sender);
    mathint balanceRecipient_After = getEthBalance(receiver());

    if(success) {
        assert balanceBank_Before == balanceBank_After + amount;
        assert balanceRecipient_After == balanceRecipient_Before + amount;
        assert getDepositCalled();
    }
    else{
        assert balanceBank_Before == balanceBank_After;
        assert balanceRecipient_After == balanceRecipient_Before;
    }

}

rule ethBalanceIntegrity_tisoped(uint256 amount) {
    address sender = currentContract;
    mathint balanceBank_Before = getEthBalance(sender);
    mathint balanceRecipient_Before = getEthBalance(receiver());

    bool success = tisopedLowLevelCall(amount);

    mathint balanceBank_After = getEthBalance(sender);
    mathint balanceRecipient_After = getEthBalance(receiver());

    if(success) {
        assert balanceBank_Before == balanceBank_After + amount;
        assert balanceRecipient_After == balanceRecipient_Before + amount;
        assert getFallbackCalled();
    }
    else{
        assert balanceBank_Before == balanceBank_After;
        assert balanceRecipient_After == balanceRecipient_Before;
    }

}

rule fallbackEmptyCallFailure(uint256 amount) {

    address sender = currentContract;
    require !getFallbackCalled();
    require !getReceiveCalled();
    mathint balanceBank_Before = getEthBalance(sender);
    mathint balanceRecipient_Before = getEthBalance(receiver());

    bool success = emptyCall(amount);

    require(!success);
    mathint balanceBank_After = getEthBalance(sender);
    mathint balanceRecipient_After = getEthBalance(receiver());

    assert !getFallbackCalled() && !getReceiveCalled();
    assert balanceBank_Before == balanceBank_After;
    assert balanceRecipient_After == balanceRecipient_Before;
}

rule fallbackEmptyCallSuccess(uint256 amount) {

    address sender = currentContract;
    require !getFallbackCalled();
    require !getReceiveCalled();
    mathint balanceBank_Before = getEthBalance(sender);
    mathint balanceRecipient_Before = getEthBalance(receiver());

    bool success = emptyCall(amount);

    require(success);
    mathint balanceBank_After = getEthBalance(sender);
    mathint balanceRecipient_After = getEthBalance(receiver());

    assert !getReceiveCalled() => getFallbackCalled();
    assert balanceBank_Before == balanceBank_After + amount;
    assert balanceRecipient_After == balanceRecipient_Before + amount;
}

rule SendMoneyToEOA(uint256 amount, address account) {
    address sender = currentContract;
    require isEOA(account);
    mathint balanceBank_Before = getEthBalance(sender);
    mathint balanceRecipient_Before = getEthBalance(account);

    bool success = sendEthViaCall(amount, account);

    mathint balanceBank_After = getEthBalance(sender);
    mathint balanceRecipient_After = getEthBalance(account);

    if (success) {
        assert balanceBank_Before == balanceBank_After + amount;
        assert balanceRecipient_After == balanceRecipient_Before + amount;
    }
    else {
        assert balanceBank_After == balanceBank_Before;
        assert balanceRecipient_After == balanceRecipient_Before;
    }
}
