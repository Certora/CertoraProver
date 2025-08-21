methods {
    function whichString(string s) external returns(int) envfree;
}

rule whichString {
    int w1 = whichString("option1");
    assert w1 == 1, "wrong value for 'option1'";
    int w2 = whichString("option2");
    assert w2 == 2, "wrong value for ;option2'";
    int w3 = whichString("non-option");
    assert w3 == -1, "wrong value for 'non-option'";
}


rule shouldFail {
	int w1 = whichString("option1");
	assert w1 == 2;
}
