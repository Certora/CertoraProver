using A as a;
using B as b;
using C as c;
using D as d;
using E as ee;
using F as f;
using G as g;
using H as h;
using I as i;
using J as j;

// TEST 1: Explicit bool type declaration -> dispatches to A (returns bool)
rule explicitBoolTypeDispatchesToA(env e) {
    address addr;
    bool result = addr.transfer(e);
    assert addr == a || addr == ee, "Explicit bool LHS should dispatch to contract A";
    assert addr == a => result == true;
    assert addr == ee => result == false;
}

// TEST 2: Command form (no LHS) -> should work with A, B, E
rule commandFormWorksWithVoid(env e) {
    address addr;
    addr.transfer(e);  // No return value captured
    assert addr == a || addr == b || addr == ee, "Command form should accept all";
}

// TEST 3: Multi-return with two variables -> dispatches to C (uint, uint)
rule multiReturnTwoVarsDispatchesToC(env e) {
    address addr;
    uint x;
    uint y;
    x, y = addr.multi(e);
    assert addr == c, "Two LHS vars should dispatch to C";
    assert x == 42 && y == 84;
}

// TEST 4: Single return -> dispatches to D (uint)
rule singleReturnDispatchesToD(env e) {
    address addr;
    uint result = addr.multi(e);
    assert addr == d, "Single LHS var should dispatch to D";
    assert result == 100;
}

// TEST 5: Assignment without type declaration (variable pre-declared)
rule assignmentToPreDeclaredBoolVar(env e) {
    address addr;
    bool existingVar;
    existingVar = addr.transfer(e);  // No type on this line, but existingVar is bool
    assert addr == a || addr == ee, "Pre-declared bool var should provide type hint";
}

// TEST 6: Assignment without type declaration for uint
rule assignmentToPreDeclaredUintVar(env e) {
    address addr;
    uint existingVar;
    existingVar = addr.multi(e);  // No type on this line
    assert addr == d, "Pre-declared uint var should dispatch to D";
}

// TEST 7: Multiple contracts with same return type - both are valid targets
rule multipleMatchingContractsBool(env e) {
    address addr;
    bool result = addr.transfer(e);
    // Both A and E return bool
    assert addr == a || addr == ee, "Both A and E should be valid for bool return";
}

// TEST 8: Subtype - uint8 result assigned to uint256 variable
rule subtypeUint8ToUint256(env e) {
    address addr;
    uint256 result = addr.getValue(e);
    // G returns uint256 (exact match), F returns uint8 (convertible?)
    assert addr == g || addr == f, "Check subtype compatibility";
}

// TEST 9: Tuple order (uint, bool) -> dispatches to H
rule tupleOrderUintBool(env e) {
    address addr;
    uint x;
    bool y;
    x, y = addr.getPair(e);
    assert addr == h, "(uint, bool) order should dispatch to H";
    assert x == 1 && y == true;
}

// TEST 10: Tuple order (bool, uint) -> dispatches to I
rule tupleOrderBoolUint(env e) {
    address addr;
    bool x;
    uint y;
    x, y = addr.getPair(e);
    assert addr == i, "(bool, uint) order should dispatch to I";
    assert x == true && y == 2;
}

// TEST 11: Triple return value
rule tripleReturnValues(env e) {
    address addr;
    uint x;
    uint y;
    uint z;
    x, y, z = addr.getTriple(e);
    assert addr == j, "Triple return should dispatch to J";
    assert x == 1 && y == 2 && z == 3;
}

// TEST 12: Wildcard with one variable - x, _ = addr.multi(e)
rule wildcardWithOneVar(env e) {
    address addr;
    uint x;
    x, _ = addr.multi(e);
    assert addr == c, "One var + wildcard should dispatch to C (uint, uint)";
    assert x == 42;
}

// TEST 13: Double wildcard - _, _ = addr.multi(e)
rule doubleWildcard(env e) {
    address addr;
    _, _ = addr.multi(e);
    assert addr == c, "Double wildcard with 2 positions should dispatch to C";
}
