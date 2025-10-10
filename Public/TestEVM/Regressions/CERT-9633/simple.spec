using Test as t;
rule basic_test {
	env e;
	address x;
	assert t.test(e, x) == (t.someMap[x].staticArray[0].a + t.someMap[x].staticArray[1].b);
}
