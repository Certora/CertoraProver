contract ToClone {
	uint8[] public unpacked;

	function push(uint8 elem) external {
		unpacked.push(elem);
	}
}

contract Creater {
	function deployAndPush(uint8 x) external returns (uint8) {
		ToClone c = new ToClone();
		c.push(x);
		return c.unpacked(0);
	}

}
