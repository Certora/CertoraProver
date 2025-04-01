interface I {
    function startPrank(address msgSender) external;
    function stopPrank() external;
}

library L {
    function assertEq(address a, address b) internal pure {}

    function checkSender(address a) external {
        assertEq(a, msg.sender);
    }
}

contract C {
    I i;
    bool b;

    function testStartPrank() external {
        i.startPrank(address(5));
        if (b) {
            address a = this.foo();
            assert(a == address(5));
        } else {
            this.checkSender(address(5));
        }
        this.checkSender(address(5));
        i.startPrank(address(6));
        this.checkSender(address(6));
        this.checkSender(address(6));
        i.stopPrank();
        this.checkSender(address(this));
    }

    function checkSender(address a) external {
        L.assertEq(a, msg.sender);
        innerCheckSender(a);
        L.checkSender(a);
    }

    function innerCheckSender(address a) public {
        L.assertEq(a, msg.sender);
    }

    function foo() external returns (address) { return msg.sender; }
}
