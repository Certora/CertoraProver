interface I {
    function rec(bytes memory, uint) external;
}

library L1 {
    function doCall(address recv, uint x, bytes calldata y) external {
        return I(recv).rec(y, x);
    }
}

contract V1 {
    function entry(bytes calldata y, uint x) external {
        L1.doCall(msg.sender, x, y);
    }
}

contract V1_Diff {
    function entry(bytes calldata, uint x) external {
        L1.doCall(msg.sender, x, "hello");
    }
}

contract V2 {
    function entry(bytes calldata y, uint x) external {
        I(msg.sender).rec(y, x);
    }
}
