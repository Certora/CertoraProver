using C as c;

// should be violated for setATo1 -> setBTo7 (either order) -> setCToSumAB
invariant cNot8() c.c != 8;

// should be violated for setATo1 -> setBToA
invariant bNot1() c.b != 1;

// should be violated for setATo1 -> setBToA -> setCToSumAB
invariant cNot2() c.c != 2;

function setup() {}
