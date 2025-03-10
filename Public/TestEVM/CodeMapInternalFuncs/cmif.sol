contract cmif {

    uint x;
    bool b1;
    bool b2;
    bool b3;
    bool b4;
    bool b5;

    function extFunc() public {
        intFunc();
    }

    function extFuncTwoCalls() public {
        intFunc();
        intFunc2();
    }

    function intFunc() private {
        x++;
    }

    function intFunc2() private {
        x--;
    }

    function heavyPaths() private returns (uint) {
        if (b1) { x++; } else { x--; }
        if (b2) { x++; } else { x--; }
        if (b3) { x++; } else { x--; }
        if (b4) { x++; } else { x--; }
        if (b5) { x++; } else { x--; }
        return x;
    }

    function heavyNonLinear(uint a, uint b) private pure returns (uint r) {
        r = a * b;
        r = r * r;
        r = r * r;
        r = r * r;
        r = r * r;
        r = r * r;
        return r;
    }

    function intFuncBranching(bool b) private {
        if (b) {
            intFunc();
        } else {
            intFunc2();
        }
    }

    function intFuncBranchingHeavy(uint i, uint j) private returns (uint) {
        uint r1 = heavyNonLinear(i, j);
        uint r2 = heavyPaths();
        return r1 + r2;
    }

    function extFuncBranching(bool b) public {
        if (b) {
            x++;
        } else {
            intFuncBranching(b);
        }
    }

    function extFuncComplex(bool b, uint i, uint j) public returns (uint) {
        if (b) {
            return x;
        } else {
            return intFuncBranchingHeavy(i , j);
        }
    }
}