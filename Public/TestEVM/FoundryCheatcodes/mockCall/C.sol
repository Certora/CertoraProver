interface I {
    function mockCall(address where, bytes calldata data, bytes calldata retdata) external;
    function mockCall(address where, uint256 value, bytes calldata data, bytes calldata retdata) external;
    function clearMockedCalls() external;

    function multiplier(uint a, uint b) external returns (uint);
}

library L {
    function assertEq(uint a, uint b) internal pure {}
}

contract C {
    I i;
    D d;
    E e;

    function testMockCallBasic(uint dummy) external {
        L.assertEq(d.adder(1, 2), 3);
        i.mockCall(address(d), abi.encodeWithSignature("adder(uint256,uint256)"), abi.encode(0));
        L.assertEq(d.adder(1, 2), 0);
        i.clearMockedCalls();
        L.assertEq(d.adder(4, 5), 9);
    }

    function testMockCallPartialData(uint dummy) external {
        L.assertEq(d.adder(1, 2), 3);
        i.mockCall(address(d), abi.encodeWithSignature("adder(uint256,uint256)", uint256(1)), abi.encode(0));
        L.assertEq(d.adder(1, 2), 0);
        L.assertEq(d.adder(4, 5), 9);
        i.clearMockedCalls();
        L.assertEq(d.adder(1, 6), 7);
    }

    function testMockCallWithValue(uint dummy) external {
        L.assertEq(d.adder{value: 10}(1, 2), 3);
        i.mockCall(address(d), 10, abi.encodeWithSignature("adder(uint256,uint256)"), abi.encode(0));
        L.assertEq(d.adder{value: 10}(1, 2), 0);
        L.assertEq(d.adder{value: 11}(1, 2), 3);
        i.clearMockedCalls();
        L.assertEq(d.adder{value: 10}(4, 5), 9);
    }

    function testMockCallMultipleMocks(uint dummy) external {
        L.assertEq(d.adder(1, 2), 3);
        L.assertEq(d.subber(3, 2), 1);
        i.mockCall(address(d), abi.encodeWithSignature("adder(uint256,uint256)"), abi.encode(0));
        L.assertEq(d.adder(1, 2), 0);
        L.assertEq(d.subber(3, 2), 1);
        i.mockCall(address(d), abi.encodeWithSignature("subber(uint256,uint256)"), abi.encode(10));
        L.assertEq(d.adder(1, 2), 0);
        L.assertEq(d.subber(3, 2), 10);
        // Also make sure that when there are multiple calls to `mackCall` with the same arguments we
        // take the return data from the latest call.
        i.mockCall(address(d), abi.encodeWithSignature("adder(uint256,uint256)"), abi.encode(5));
        L.assertEq(d.adder(1, 2), 5);
        L.assertEq(d.subber(3, 2), 10);
        i.clearMockedCalls();
        L.assertEq(d.adder(1, 2), 3);
        L.assertEq(d.subber(3, 2), 1);
    }

    function testMockCallDifferentCallees(uint dummy) external {
        L.assertEq(d.adder(1, 2), 3);
        L.assertEq(e.adder(1, 2), 3);
        i.mockCall(address(d), abi.encodeWithSignature("adder(uint256,uint256)"), abi.encode(0));
        L.assertEq(d.adder(1, 2), 0);
        L.assertEq(e.adder(1, 2), 3);
        i.clearMockedCalls();
        L.assertEq(d.adder(4, 5), 9);
        L.assertEq(e.adder(1, 2), 3);
    }

    function testMockCallUnresolved(uint dummy) external {
        i.mockCall(address(i), abi.encodeWithSignature("multiplier(uint256,uint256)"), abi.encode(0));
        L.assertEq(i.multiplier(1, 2), 0);
    }

    function testMockCallConditional(bool b) external {
        L.assertEq(d.adder(1, 2), 3);
        if (b) {
            i.mockCall(address(d), abi.encodeWithSignature("adder(uint256,uint256)"), abi.encode(0));
        }
        L.assertEq(d.adder(1, 2), b? 0 : 3);
    }

    // This is here so we could test method filters that filter out all test functions.
    function nonTestFunction() external {}
}

contract D {
    function adder(uint a, uint b) payable external returns (uint) {
        return a + b;
    }

    function subber(uint a, uint b) payable external returns (uint) {
        return a - b;
    }
}

contract E {
    function adder(uint a, uint b) payable external returns (uint) {
        return a + b;
    }

    function subber(uint a, uint b) payable external returns (uint) {
        return a - b;
    }
}
