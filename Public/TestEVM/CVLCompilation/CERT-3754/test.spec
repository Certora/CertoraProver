function func1() returns bool {
    // We used to get an error because this variable and the `res` below were both translated
    // to the TAC variable `res`; changing one of the variable names solves the
    // error.
	env e;
    bool res = f2(e);
    return res;
}

function func2() returns uint {
	env e;
    uint res = f1(e);
    return res;
}


rule s {
    func1();
    func2();
    assert true;
}
