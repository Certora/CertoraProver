// These tests are about ghosts that are accessed only in quantifiers (not) showing up in the GhostState of the calltrace.

ghost mapping(address => mapping(address => int128)) myGhost {
    init_state axiom forall address a . forall address b . myGhost[a][b] == 0;
}

// this one works, since the indices of the ghost access are directly variables, so we can record the access and
// thanks to skolemization find a concrete value for the index for which we can show the accessed value
invariant Inv()
    currentContract.a || (forall address a . forall address b . myGhost[a][b] > 10);

ghost mapping(uint => address) myOtherGhost;

// this one does not work as of now, since the GhostAccess snippets need variables as indices,
// but the way we compile quantifiers leaves us with complex expressions instead,
// since we do not allow cmds in the quantifier body, only expressions,
// so the expression can not be simplified to an assigment and then a variable usage in this context.
// A clean way to make this work would be to not compile the body of a quantifier specially,
// but actually simplify it into cmds like everywhere else, and then support a block as the quantifier body.
invariant Inv2()
    currentContract.a || (forall address a . forall uint b . myGhost[a][myOtherGhost[b]] > 10);
