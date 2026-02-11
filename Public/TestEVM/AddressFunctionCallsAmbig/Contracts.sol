// SPDX-License-Identifier: MIT
pragma solidity ^0.8.0;

// Contract A: transfer() returning bool
contract A {
    bool public executed;

    function transfer() external returns (bool) {
        executed = true;
        return true;
    }
}

// Contract B: transfer() returning void
contract B {
    bool public executed;

    function transfer() external {
        executed = true;
    }
}

// Contract C: multi() returning (uint, uint)
contract C {
    function multi() external pure returns (uint, uint) {
        return (42, 84);
    }
}

// Contract D: multi() returning (uint)
contract D {
    function multi() external pure returns (uint) {
        return (100);
    }
}

// Contract E: transfer() returning bool (same as A, tests multiple matching)
contract E {
    function transfer() external returns (bool) {
        return false;
    }
}

// Contract F: getValue() returning uint8
contract F {
    function getValue() external pure returns (uint8) {
        return 42;
    }
}

// Contract G: getValue() returning uint256
contract G {
    function getValue() external pure returns (uint256) {
        return 100;
    }
}

// Contract H: getPair() returning (uint, bool)
contract H {
    function getPair() external pure returns (uint, bool) {
        return (1, true);
    }
}

// Contract I: getPair() returning (bool, uint)
contract I {
    function getPair() external pure returns (bool, uint) {
        return (true, 2);
    }
}

// Contract J: getTriple() returning (uint, uint, uint)
contract J {
    function getTriple() external pure returns (uint, uint, uint) {
        return (1, 2, 3);
    }
}

// Main contract for verification
contract Main {
}
