methods {
    unresolved external in foo(address,bytes) => NONDET;
    unresolved external in bar(address,bytes) => NONDET;
}

rule test {
    int other_fieldBefore = currentContract.other_field;
    int bar_returnedBefore = currentContract.bar_returned;

    env e;
    address t;
    test(e, t);

    assert other_fieldBefore == currentContract.other_field;
    satisfy bar_returnedBefore != currentContract.bar_returned;
}
