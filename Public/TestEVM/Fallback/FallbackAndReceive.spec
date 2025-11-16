methods {
    function getN() external returns (uint) envfree;
    function getLastCalledFallback() external returns (bool) envfree;
    function getLastCalledReceive() external returns (bool) envfree;
    function getLastCalledNameclash() external returns (bool) envfree;
}

invariant last_called_consistent_with_n()
    (currentContract.getN() == 2 <=> currentContract.getLastCalledFallback() == true && currentContract.getLastCalledReceive() == false) &&
    (currentContract.getN() == 3 <=> currentContract.getLastCalledFallback() == false && currentContract.getLastCalledReceive() == true) &&
    (currentContract.getN() == 5 <=> currentContract.getLastCalledNameclash() == true);

invariant last_called_consistent_with_n_wrong_for_fallback()
    (currentContract.getN() == 42 <=> currentContract.getLastCalledFallback() == true && currentContract.getLastCalledReceive() == false) &&
    (currentContract.getN() == 3 <=> currentContract.getLastCalledFallback() == false && currentContract.getLastCalledReceive() == true) &&
    (currentContract.getN() == 5 <=> currentContract.getLastCalledNameclash() == true);


invariant last_called_consistent_with_n_wrong_for_receive()
    (currentContract.getN() == 2 <=> currentContract.getLastCalledFallback() == true && currentContract.getLastCalledReceive() == false) &&
    (currentContract.getN() == 42 <=> currentContract.getLastCalledFallback() == false && currentContract.getLastCalledReceive() == true) &&
    (currentContract.getN() == 5 <=> currentContract.getLastCalledNameclash() == true);


invariant last_called_consistent_with_n_wrong_for_nameclash()
    (currentContract.getN() == 2 <=> currentContract.getLastCalledFallback() == true && currentContract.getLastCalledReceive() == false) &&
    (currentContract.getN() == 3 <=> currentContract.getLastCalledFallback() == false && currentContract.getLastCalledReceive() == true) &&
    (currentContract.getN() == 42 <=> currentContract.getLastCalledNameclash() == true);

use builtin rule hasDelegateCalls;
use builtin rule msgValueInLoopRule;
use builtin rule sanity;
use builtin rule deepSanity;
use builtin rule viewReentrancy;
