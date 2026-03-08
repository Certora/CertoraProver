contract C {
    function test() external payable {
        payable(address(0)).transfer(msg.value);
    }
}
