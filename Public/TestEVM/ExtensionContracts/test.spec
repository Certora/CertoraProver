using Base1 as base1;
using Base2 as base2;

methods {
    function Base2.getInExtension2() external returns (uint) => 1;
    function Extension1.getInExtension1() internal returns (uint) => 2;
}

rule setInExtension1Direct(env e) {
    uint n;
    base1.setInExtension1(e, n);
    assert base1.inExtension1 == n;
}

rule setInExtension1viaDelegate(env e) {
    uint n;
    base1.callSetInExtension1(e, n);
    assert base1.inExtension1 == n;
    assert base1.getInExtension1(e) == 2; // the summarization should apply
}

rule setInExtension2Direct(env e) {
    uint n;
    base1.setInExtension2(e, n);
    assert base1.inExtension2 == n;
}

rule setInExtension2viaDelegate(env e) {
    uint n;
    base1.callSetInExtension2(e, n);
    assert base1.inExtension2 == n;
    assert base1.callGetInExtension2(e) == n; // the summarization for getInExtension2 is only for base2
}

rule setInExtension2DirectBase2(env e) {
    uint n;
    base2.setInExtension2(e, n);
    assert base2.inExtension2 == n;
}

rule setInExtension2viaDelegateBase2(env e) {
    uint n;
    base2.callSetInExtension2(e, n);
    assert base2.inExtension2 == n;
    assert base2.callGetInExtension2(e) == 1; // the summarization should apply
}

rule parametricRule(method f) {
    env e;
    calldataarg args;
    f(e, args);
    satisfy true;
}

invariant specificPreservedInv() base1.inExtension2 == 0 {
    // Verify the specific preserved on an extension contract function works.
    // Without this preserved the invariant should fail on this function.
    preserved _.setInExtension2(uint256 _num) with (env e) {
        require _num == 0;
    }
}

invariant genericPreservedInv() base1.inExtension2 == 0 {
    // Should make all invariant rules vacuous (including the rule from
    // the extension contract's function)
    preserved {
        require false;
    }
}

rule immutablesAreTheSame() {
    env e;
    assert base1.getImmut(e) == base1.getExtensionImmut(e);
}

rule linking() {
    env e;
    bool oldB = base1.b;
    base1.callFlipB(e);
    assert oldB == !base1.b;
}
