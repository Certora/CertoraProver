contract C {
    bytes public foo_returned;
    int256 public bar_returned;
    int public other_field;
    function foo(address target, bytes memory data) external view returns (bytes memory) {
        (bool success, bytes memory returndata) = target.staticcall(data);
        require(success);
        return returndata;
    }
    function bar(address target, bytes memory data) external view returns (bytes memory) {
        (bool success, bytes memory returndata) = target.staticcall(data);
        require(success);
        return returndata;
    }
    function test(address target) external {
        foo_returned = this.foo(target, abi.encodeWithSignature("getBytes()"));
        bytes memory result = this.bar(target, abi.encodeWithSignature("getInt()"));
        bar_returned = abi.decode(result, (int256));
    }
}
