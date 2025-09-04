contract C {
    int a;
    int b;
    int c;
    int d;

    function setATo1() public {
        a = 1;
    }

    function setBTo7() public {
        b = 7;
    }

    function setCToSumAB() public {
        c = a + b;
    }

    function setBToA() public {
        b = a;
    }

    function setDToB() public {
        d = b;
    }
}
