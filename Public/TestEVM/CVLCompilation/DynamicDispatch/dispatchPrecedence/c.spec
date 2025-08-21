using D as d;
using E as ee;

methods {
    function nestedUnresolved(address,bytes) external with (env e) => makeN3(e);
    unresolved external in C._ => DISPATCH [
        C.makeN1(),
        C.nestedUnresolved(address,bytes),
    ] default NONDET;
    unresolved external in currentContract.callNestedUnresolved(address,bytes) => DISPATCH [
        C.makeN1(),
    ] default NONDET;
    unresolved external in _.nestedUnresolved(address,bytes) => DISPATCH(use_fallback=true) [
        D._,
    ] default NONDET;
    unresolved external in D._ => DISPATCH [
        C.nestedUnresolved(address,bytes),
    ] default NONDET;
    unresolved external in E.callNotSummarizedUnresolved(address,bytes) => DISPATCH [
        E.makeN1(),
    ] default NONDET;
}

rule wildcardContractIsInlined {
    env e;
    calldataarg args;
    require currentContract.n == 0;
    unresolved(e, args);
    satisfy currentContract.n == 1;
}

rule recursiveInlining {
    env e;
    calldataarg args;
    require currentContract.n == 0;
    unresolved(e, args);

    // verify the summary of nestedUnresolved was inlined, and not the function itself with its dispatch list.
    assert currentContract.n != 2;
    satisfy currentContract.n == 3;
}

rule dispatchListCallstackPrecedence {
    env e;
    calldataarg args;
    require currentContract.n == 0;
    callNestedUnresolved(e, args);
    assert currentContract.n != 1, "The dispatch list for callNestedUnresolved shouldn't be inlined";
    satisfy currentContract.n == 3;
}

/*
 * The call stack should be: rule -> nestedUnresolved -> D.fallback
 * From here, the `satisfy` checks that `D.makeN1` is inlined, and the `assert`
 * checks that the fallback is inlined (and that it in turn inlines the
 * summary of `C.nestedUnresolved`).
 */
rule dispatchListDirectFromCVL {
    env e;
    calldataarg args;
    require currentContract.n == 0;
    nestedUnresolved(e, args);
    assert currentContract.n == 3 => d.n == 2;
    satisfy d.n == 1;
}

/*
 * The call stack will be callCallNotSummarizedUnresolved -> callNotSummarizedUnresolved -> notSummarizedUnresolved.
 * All the calls are external ones, so even though `callNotSummarizedUnresolved` has a dispatch list summary, the
 * unresolved call in `notSummarizedUnresolved` should cause a HAVOC_ECF and not inline that dispatch list.
 */
rule innerExternalCallNotDispatched {
    env e;
    calldataarg args;
    int oldN = ee.n;
    ee.callCallNotSummarizedUnresolved(e, args);
    assert ee.n == oldN, "The inner unresolved call should not be dispatched (so the default HAVOC_ECF should apply)";
}
