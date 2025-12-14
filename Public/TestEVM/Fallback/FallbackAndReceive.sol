pragma solidity >=0.8.0;

abstract contract FallbackAndReceive {
    uint public n;
    bool public last_called_fallback;
    bool public last_called_receive;
    bool public last_called_nameclash;
}

contract A is FallbackAndReceive{
    fallback() external payable {
        n = 2;
        last_called_fallback = true;
        last_called_receive = false;
        last_called_nameclash = false;
    }
    function receiveOrFallback() external payable {
        n = 5;
        last_called_fallback = false;
        last_called_receive = false;
        last_called_nameclash = true;
    }
}

contract B is FallbackAndReceive {
    fallback() external payable {
        n = 2;
        last_called_fallback = true;
        last_called_receive = false;
        last_called_nameclash = false;
    }
    receive() external payable {
        n = 3;
        last_called_fallback = false;
        last_called_receive = true;
        last_called_nameclash = false;
    }
}

contract C is FallbackAndReceive {
    fallback(bytes calldata input) external payable returns (bytes memory output) {
        n = 2;
        last_called_fallback = true;
        last_called_receive = false;
        last_called_nameclash = false;
    }
}

contract Caller {
    FallbackAndReceive a;
    constructor(FallbackAndReceive _a) {
        a = _a;
        require(a.n() == 0 &&
        a.last_called_fallback() == false &&
        a.last_called_receive() == false &&
        a.last_called_nameclash() == false);
    }
    function noSuchFun() external {}
    function should_call_fallback() external {
        address(a).call(abi.encodeWithSignature("noSuchFun()"));
    }
    function should_call_receive_if_exists() external {
        payable(address(a)).send(2 ether);
    }
    function should_call_receive_more_low_level() external {
        address(a).call{value: 2 ether}("");
    }
    function should_call_nameclash() external {
        address(a).call(abi.encodeWithSignature("receiveOrFallback()"));
    }
    function getN() external view returns (uint) {
        return a.n();
    }
    function getLastCalledFallback() external view returns (bool) {
        return a.last_called_fallback();
    }
    function getLastCalledReceive() external view returns (bool) {
        return a.last_called_receive();
    }
    function getLastCalledNameclash() external view returns (bool) {
        return a.last_called_nameclash();
    }
    receive() external payable {}
}
