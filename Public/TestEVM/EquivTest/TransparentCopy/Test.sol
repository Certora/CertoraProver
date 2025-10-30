contract V1 {
    function impl(address a, bytes calldata g) external {
		a.call(g);
    }
}

contract V2 {
    function impl(address a, bytes calldata g) external {
		bytes memory buf = g;
		a.call(buf);
    }
}
