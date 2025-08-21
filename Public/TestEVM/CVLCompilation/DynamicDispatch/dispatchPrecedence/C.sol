contract C {
    int n;
    function unresolved(address a, bytes memory b) external {
        (bool success, bytes memory ret) =
            a.call(b);
        if (!success) {
            revert();
        }
    }

    function callNestedUnresolved(address a, bytes memory b) external {
        this.nestedUnresolved(a, b);
    }
    function nestedUnresolved(address a, bytes memory b) external {
        (bool success, bytes memory ret) =
            a.call(b);
        if (!success) {
            revert();
        }
    }

    function makeN1() external { n = 1; }
    function makeN2() external { n = 2; }
    function makeN3() external { n = 3; }
}

contract D {
    int n;
    function makeN1() external { n = 1; }

    fallback(bytes calldata data) external returns (bytes memory) {
        address a;
        bytes memory b;
        (a, b) = abi.decode(data, (address, bytes));
        n = 2;
        (bool success, bytes memory ret) =
            a.call(b);
        if (!success) {
            revert();
        }
        return ret;
    }
}

contract E {
    int n;

    function makeN1() external { n = 1; }

    function notSummarizedUnresolved(address a, bytes memory b) external {
        (bool success, bytes memory ret) =
            a.call(b);
        if (!success) {
            revert();
        }
    }

    function callNotSummarizedUnresolved(address a, bytes memory b) external {
        this.notSummarizedUnresolved(a, b);
    }

    function callCallNotSummarizedUnresolved(address a, bytes memory b) external {
        this.callNotSummarizedUnresolved(a, b);
    }
}
