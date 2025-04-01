using D as d;

override function init_fuzz_tests(method f, env e) {
    require nativeBalances[currentContract] == max_uint256; // to make sure there's enough for all the transfers in a test function
    require nativeBalances[d] == 0; // to avoid overflow when transferring value to it
}

use builtin rule verifyFoundryFuzzTests;
