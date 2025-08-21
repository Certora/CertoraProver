contract Basic {
	mapping (address => uint) m;
	uint x;

	mapping (uint => address[]) foo;

	function key() internal returns (uint) {
		return msg.value;
	}

	function incr() external {
		x++;
		m[msg.sender]++;
		foo[key()].push(msg.sender);
	}

	function decr() external {
		x--;
		m[msg.sender]--;
		foo[key()].pop();
	}

	struct Foo {
		uint a;
		uint b;
	}

	struct WithNesting {
		Foo c;
	}

	Foo atRoot;
	WithNesting atRoot2;

	struct TightlyPacked {
		uint8 x;
		uint8 y;
	}

	struct EvenTighter {
		TightlyPacked w;
		uint8 z;
	}

	EvenTighter t;

	function incrStructField() external {
		atRoot.a++;
	}

	function incrNestedStructField() external {
		atRoot2.c.a++;
	}

	function incrTightlyPackedStruct() external {
		t.w.y++;
	}

	function transfer(address target, uint amount) external {
		require(amount >= m[msg.sender]);
		m[msg.sender] -= amount;
		m[target] += amount;
	}

	function butThenAlsoSend(address payable to) external payable {
		(bool rc, bytes memory data) = to.call{value: msg.value}(abi.encodeWithSignature("receiveCash()"));
		require(rc);
	}

	function receiveCash() external payable {
	}

	function maybeRevert(uint y) external {
		if(y == 3) {
			revert();
		}
		x += y;
	}
}
