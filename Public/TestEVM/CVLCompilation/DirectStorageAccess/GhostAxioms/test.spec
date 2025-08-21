using Test as t;

ghost g(address, uint) returns bool {
    axiom forall address a. forall uint i. g(a, i) == t.a[a][i];
}

rule r(address a, uint i) {
    require t.a[a][i];

    assert exists address aa. exists uint ii. g(aa, ii);
}
