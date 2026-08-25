contract Repeat {
	mapping (uint => uint) m;

	function setFive(uint k) external {
		m[k] = 5;
	}

	function setV(uint k, uint v) external {
		m[k] = v;
	}

	function get(uint k) external view returns (uint) {
		return m[k];
	}
}
