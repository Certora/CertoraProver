//This invariant is trivial by itself, and this check should succeed as all preserved block also succeed.
invariant shouldSucceed(env e, uint x) identity(e,x) == x {
    preserved onTransactionBoundary with(env e2) {
        uint y;
        assert true;
        //requireInvariant trivialInvariant(e2, y);
    }

    preserved with(env e2) {
        uint y;
        assert true;
        //requireInvariant trivialInvariant(e2, y);
    }
}

//This invariant is trivial, and this check should fail due to a failing requireInvariant in a preserved block
invariant shouldFailOnTransientPreserverdState(env e, uint x) identity(e,x) == x {
    preserved onTransactionBoundary with(env e2) {
        uint y;
        assert false;
        //requireInvariant failingInvariant(e2, y); //Only the transaction boundary step should fail due to the failure in the preserved block.
    }

    preserved with(env e2) {
        uint y;
        assert true;
        //requireInvariant trivialInvariant(e2, y);
    }
}

//This invariant is trivial, and will only fail in the preserved blocks. As all but the transaction boundary preserved block fail, only the transient storage reset step should succeed.
invariant shouldFailOnAllInductionStepsNotTransactionBoundary(env e, uint x) identity(e,x) == x {
    preserved onTransactionBoundary with(env e2) {
        uint y;
        assert true;
        //requireInvariant trivialInvariant(e2, y); //Only the transaction boundary step should succeed now - as this preserved block will _only_ be executed on the transaction boundary induction step
    }

    preserved with(env e2) {
        uint y;
        assert false;
        //requireInvariant failingInvariant(e2, y);
    }
}

invariant trivialInvariant(env e, uint y) identity(e,y) == y;

invariant failingInvariant(env e, uint y) identity(e,y) == require_uint256(y + 1);
