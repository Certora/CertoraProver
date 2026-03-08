methods{
    function _.shouldBeSummarized() external => summary() expect uint256 ALL;
}

ghost mathint counter{
    init_state axiom counter == 0;
}

function summary() returns uint256 {
    counter = counter + 2;
    return require_uint256(counter);
}

//This invariant only succeeds if the CVL summary above is applied in the invariant.
strong invariant strongInvariantCallingSolidityWithExternalCall(env e, address token)
    currentContract.invariantCall(e) % 2 == 0;
