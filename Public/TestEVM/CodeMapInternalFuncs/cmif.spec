
rule straightline {
    env e;
    extFunc(e);
    satisfy true;
}

rule straightlineTwoCalls {
    env e;
    extFuncTwoCalls(e);
    satisfy true;
}

rule someBranching {
    env e;
    bool b;
    extFuncBranching(e, b);
    satisfy true;
}

// contains some functions with nonlinear computations, and many paths
rule complex {
    env e;
    bool b;
    uint i;
    uint j;
    uint r = extFuncComplex(e, b, i, j);

    uint cube = 3760028875; // answer: 1555
    uint x;
    require x * x * x == cube;
    uint y;
    require y * y == cube;

    satisfy r % 2 == 0;
}
