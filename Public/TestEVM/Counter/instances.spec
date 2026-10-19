using Counter0 as first;
using Counter1 as second;

methods {
    function first.getCount() external returns (uint256) envfree;
    function second.getCount() external returns (uint256) envfree;
}

rule separateAddresses() {
    assert first != second;
    assert currentContract == first;
}

rule separateStorage() {
    env e;
    uint256 firstBefore = first.getCount();
    uint256 secondBefore = second.getCount();
    first.increment(e);
    assert first.getCount() == firstBefore + 1;
    assert second.getCount() == secondBefore;
}
