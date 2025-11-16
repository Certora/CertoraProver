library L1 {
    function doRevert() external {
        revert("oh no");
    }

    function doPassthrough(bytes calldata z) external returns (bytes memory) {
        return z;
    }

    function doMutate(bytes calldata z) external returns (bytes memory) {
        return abi.encodePacked(z, "hello");
    }

    function doMutate(bytes memory x, uint z) external returns (bytes memory) {
        return abi.encodePacked(x, z);
    }
}

contract V1 {
    function entry() external {
        L1.doRevert();
    }


    function entry(bytes calldata y) external returns (bytes memory) {
        require(y.length < 2**28);
        return L1.doPassthrough(y);
    }

    function entry(bytes calldata x, uint z) external returns (bytes memory) {
        require(x.length < 2 ** 10);
        return L1.doMutate(x, z);
    }
}

contract V1_Diff {
    function entry(bytes calldata y) external returns (bytes memory) {
        return L1.doMutate(y);
    }

    function entry(bytes calldata x, uint z) external returns (bytes memory) {
        require(x.length < 2 ** 10);
        return L1.doMutate(x, 0);
    }

}

contract V2_Diff {
    function entry() external {
        revert("bad news");
    }
}

contract V2 {
    function entry() external {
        revert("oh no");
    }

    function entry(bytes calldata x, uint z) external returns (bytes memory) {
        require(x.length < 2 ** 10);
        return abi.encodePacked(x, z);
    }

    function entry(bytes calldata y) external returns (bytes memory) {
        require(y.length < 2 ** 28);
        return y;
    }
}
