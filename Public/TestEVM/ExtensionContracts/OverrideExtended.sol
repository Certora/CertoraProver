contract Extended {
    function foo() external returns (string memory) {
        return "Extended.foo";
    }

    function foo(uint) external returns (string memory) {
        return "Extended.foo";
    }
}

contract Extender {
    function foo() external returns (string memory) {
        return "Extender.foo";
    }
}
