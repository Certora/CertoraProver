methods {
    unresolved external in foo(address,bytes) => HAVOC_ALL;
    unresolved external in bar(address,bytes) => HAVOC_ALL;
}

rule test {
    int other_fieldBefore = currentContract.other_field;
    int bar_returnedBefore = currentContract.bar_returned;

    env e;
    address t;
    test(e, t);

    satisfy other_fieldBefore != currentContract.other_field
        && bar_returnedBefore != currentContract.bar_returned;
}
