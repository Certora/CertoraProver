rule ok {
    env e;
    uint256 x;
    bool which;
    uint256 y = doThing(e, x, which);
    uint256 z = doOtherThing(e, x, which);
    satisfy(x == y);
    satisfy(x != z);
    satisfy(y == currentContract.certoralink_Spec_b1.field1.words[0]);
}
