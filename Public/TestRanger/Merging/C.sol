contract C {
    int a;
    int b;
    int c;
    int complexity;

    function setATo1() public {
        a = 1;
    }

    function setBTo7() public {
        complexity = this.complex();
        b = 7;
    }

    function setBToA() public {
        b = a;
    }

    function setCToSumAB() public {
        c = a + b;
    }

    function complex() public returns (int) {
        int res = a + b + c;
        res = res * a;
        res = res * c;
        res = res * (a+b);
        res = res * (b+c);
        res = res * (a+c);
        return res;
    }
}
