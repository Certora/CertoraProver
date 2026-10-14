persistent ghost g(uint256) returns bool;

// The shift is constant with respect to the quantified variable, so the axioms the shift generates
// while the generators visit the body must not be quantified over `other`.
rule shiftRightConstantInBody(uint256 w, uint256 bound) {
    require forall uint256 other. g(other) => (w >> 128) <= bound, "shift bounded";
    require g(0);
    assert (w >> 128) <= bound;
}

rule shiftLeftConstantInBody(uint256 w, uint256 bound) {
    require forall uint256 other. g(other) => (w << 8) <= bound, "shift bounded";
    require g(0);
    assert (w << 8) <= bound;
}
