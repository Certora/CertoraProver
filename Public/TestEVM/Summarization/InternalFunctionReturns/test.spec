methods {
    function owner() external returns (address) envfree;
    function doSomething() external returns (bool);

    // Try using a wildcard summary instead of Context._msgSender()
    function _._msgSender() internal with(env e) => e.msg.sender expect address;
}

// Rule to verify that the owner can successfully call doSomething
rule ownerCanCallDoSomething {
    env e;

    // Ensure the caller is the owner
    require e.msg.sender == owner();

    // Call doSomething - should not revert
    bool result = doSomething@withrevert(e);

    // Assert that it succeeded (did not revert)
    assert !lastReverted, "Owner should be able to call doSomething";

    // Assert that it returned true
    assert result == true, "doSomething should return true";
}

// Rule to verify that non-owners cannot call doSomething
rule nonOwnerCannotCallDoSomething {
    env e;

    // Ensure the caller is NOT the owner
    require e.msg.sender != owner();

    // Call doSomething - should revert
    doSomething@withrevert(e);

    // Assert that it reverted
    assert lastReverted, "Non-owner should not be able to call doSomething";
}
