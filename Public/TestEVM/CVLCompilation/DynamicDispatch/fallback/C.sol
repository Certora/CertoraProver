contract C {
    D d;
    function foo(bytes memory b) external {
        (bool success, bytes memory ret) =
            address(d).call(b);
        if (!success) {
            revert();
        }
    }
}

contract D {
    uint _u;

    function foo() external { _u += 7; }
    fallback(bytes calldata data) external returns (bytes memory) {
        uint u = abi.decode(data, (uint));
        if (u == 5) {
            _u += u;
        } else {
            revert();
        }
        return data;
    }
}
