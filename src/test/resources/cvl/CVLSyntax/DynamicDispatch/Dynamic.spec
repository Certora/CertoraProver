methods {
    unresolved external in _._ => DISPATCH [
        C.bar(uint),
        _.update(uint),
        Other._
    ] default NONDET ;
}

rule easy {
    assert true;
}
