contract Admin {
    struct Params {
        uint amount;
        bool direction;
        address receiver;
    }
    uint public lastAmount;
    address public lastReceiver;
    address public lastSender;

    address adminProxy;

    function handleRequest(address sender, Params memory args) external returns (uint) {
        if(msg.sender != adminProxy) {
            revert("NOT AUTHORIZED");
        }
        uint toRet = lastAmount;
        lastSender = sender;
        lastAmount = args.amount;
        lastReceiver = args.receiver;
        return toRet;
    }
}

contract AdminProxy {
    Admin protectedModule;
    function privileged(bytes calldata operation) external returns (bytes memory) {
        (bool success, bytes memory ret) = address(protectedModule).call(operation);
        if(!success) {
            revert("WRAPPED_FAILED");
        }
        return ret;
    }
}


contract Test {
    AdminProxy proxy;
    function tryPrivileged(Admin.Params memory args) external returns (uint) {
        bytes memory payload = abi.encodeCall(Admin.handleRequest, (msg.sender, args));
        bytes memory res = proxy.privileged(payload);
        return abi.decode(res, (uint));
    }
}