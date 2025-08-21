methods {
	function inner(uint) external returns uint envfree;
	function outer(uint,uint) external returns uint envfree;
}

rule check {
	uint x;
	uint y;
	assert outer(y, inner(x)) == y;
}

rule unnested {
	uint x;
	uint y;
	uint t = inner(x);
	assert outer(y, t) == y;
}
