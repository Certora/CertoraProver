contract Test {
    uint x;

    // Increasing loops
    function simpleIncreasingLoop() external {
        for (uint i = 0; i < 5; i++) {
            x++;
        }
    }

    function simpleIncreasingLoopStartNot0() external {
        for (uint i = 5; i < 10; i++) {
            x++;
        }
    }

    function simpleIncreasingLoopIncrementGT1() external {
        for (uint i = 0; i < 20; i += 4) {
            x++;
        }
    }

    // Non-exact divisor: ceil(18/4) = 5 iterations (i = 0, 4, 8, 12, 16)
    function simpleIncreasingLoopIncrementGT1NonExact() external {
        for (uint i = 0; i < 18; i += 4) {
            x++;
        }
    }

    function simpleWhileIncreasingLoop() external {
        uint i = 0;
        while (i < 5) {
            i++;
            x ++;
        }
    }

    /**
     * This one fails sanity because the loop condition isn't
     * in the head block of the loop, which breaks the current
     * unroll constant guessing logic.
     */
    function simpleDoWhileIncreasingLoop() external {
        uint i = 0;
        do {
            i++;
            x ++;
        } while (i < 5);
    }

    // Decreasing loops
    function simpleDecreasingLoop() external {
        for (uint i = 5; i > 0; i--) {
            x++;
        }
    }

    function simpleDecreasingLoopStartNot0() external {
        for (uint i = 10; i > 5; i--) {
            x++;
        }
    }

    function simpleDecreasingLoopIncrementGT1() external {
        for (uint i = 20; i > 0; i -= 4) {
            x++;
        }
    }

    // Non-exact divisor: ceil(18/4) = 5 iterations (i = 20, 16, 12, 8, 4)
    function simpleDecreasingLoopIncrementGT1NonExact() external {
        for (uint i = 20; i > 2; i -= 4) {
            x++;
        }
    }

    function simpleWhileDecreasingLoop() external {
        uint i = 5;
        while (i > 0) {
            i--;
            x ++;
        }
    }

    function simpleDoWhileDecreasingLoop() external {
        uint i = 5;
        do {
            i--;
            x ++;
        } while (i > 0);
    }

    function returnStaticArray() external returns (uint256[5] memory) {
        return [x++, x++, x++, x++, x++];
    }
}
