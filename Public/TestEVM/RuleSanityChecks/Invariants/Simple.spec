methods {
  function returnSame(uint) external returns uint256 envfree;
  function getB() internal returns uint256 => ALWAYS(5);
  function getB() external returns uint256 envfree;
  function b() external returns uint256 envfree;
}

invariant wrongInv(uint a) returnSame(a) < 1;
invariant alwaysTrueInv() getB() == 5;

rule allSanityFail() {
    requireInvariant wrongInv(2);
    assert true;
}

rule allSanityPass() {
    if (getB() == 2) {
        assert false;
    }
    uint i;
    requireInvariant wrongInv(i);
    assert (returnSame(i) < 1);
}

invariant zeroOrTwo() (b() * b()) == (b() + b()) {
	preserved {
		require true;
	}
	preserved setB(uint y) with (env e) {
		require y == 0 || y == 2;
	}
}
