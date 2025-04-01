using Admin as admin;
methods {
    unresolved external in AdminProxy.privileged(bytes calldata) => DISPATCH [
        Admin.handleRequest(address,Admin.Params)
    ] default ASSERT_FALSE;
}

rule test_faithful_encodings {
    env e;
    uint savedValue = admin.lastAmount;
    address savedAddress = admin.lastReceiver;

    uint newValue;
    address newReceiver;
    Admin.Params args;
    require(args.amount == newValue);
    require(args.receiver == newReceiver);

    uint res = tryPrivileged(e, args);
    assert res == savedValue;
    assert e.msg.sender == admin.lastSender;
    assert admin.lastReceiver == newReceiver;
    assert admin.lastAmount == newValue;
}