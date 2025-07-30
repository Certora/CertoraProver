methods {
    function addInExternalCall(uint, uint) external returns (uint) envfree;
    function severalSolidityCalls(uint, uint) external returns (uint) envfree;
    function add(uint, uint) external returns (uint) envfree;
    function sumCoordinates(Contract.Point) external returns (uint) envfree;
    function sumCoordinatesInternal(Contract.Point) external returns (uint) envfree;
    function addToStorage(uint) external returns (uint) envfree;
    function addToStorage(uint) external returns (uint) envfree;
    function addSummarizedByNondet(uint, uint) external returns (uint) envfree;
    function addSummarizedByCVLFunction(uint, uint) external returns (uint) envfree;
    function _addSummmarizedByCVLFunction(uint a, uint b) internal => cvlFunctionAdd(a, b);
    function _addSummarizedByNondet(uint a, uint b) internal returns (uint) => NONDET;
}

function cvlFunctionAdd(uint a, uint b) {
    require(currentContract.storageResult == 10);
}

function cvlFunction(mathint a) returns mathint {
    return a + 1;
}

rule callCVLFunctionOnce() {
    mathint x;
    mathint y = cvlFunction(x);
    assert x == y + 2; // Fails as it should be z == x + 2 here.
}

rule callCVLFunction() {
    mathint x;
    mathint y = cvlFunction(x);
    mathint z = cvlFunction(x);
    assert z == y + 2; // Fails as it should be z == x + 2 here.
}

rule onlyCVLVariables() {
    mathint x = 3;
    mathint y;
    assert x == y + 2;
}

rule onlyCVLVariablesParameter(mathint y) {
    mathint x = 3;
    assert x == y + 2;
}

rule add() {
    uint x;
    uint y;
    uint z = add(x, y);
    assert z == x + y;
}

rule sumCoordinates() {
    Contract.Point point;
    uint z = sumCoordinates(point);
    assert z == point.x + point.y;
}

rule sumCoordinatesInternal() {
    Contract.Point point;
    uint z = sumCoordinatesInternal(point);
    assert z == point.x + point.y;
}

rule addToStorage() {
    uint x;
    uint z = addToStorage(x);
    assert z == x;
}

rule addSummarizedByCVLFunction() {
    uint x;
    uint y;
    uint z = addSummarizedByCVLFunction(x, y);
    assert z == x + y;
}

rule addSummarizedNyNondet() {
    uint x;
    uint y;
    uint z = addSummarizedByNondet(x, y);
    assert z == x + y;
}

ghost bool foo;

function setGhostToFalse() {
    foo = false;
}

rule updateGhost() {
    foo = true;
    setGhostToFalse();
    assert foo;
}

rule addInExternalCall() {
    uint x;
    uint y;
    uint z = addInExternalCall(x, y);
    assert z == x + y;
}

rule severalSolidityCalls() {
    uint x;
    uint y;
    uint z = severalSolidityCalls(x, y);
    assert z == x + y;
}

rule parametricMethod(method f) {
    uint x;
    uint y;
    uint z = add(x, y);
    calldataarg args;
    env e;
    f(e, args);
    assert z == x + y;
}

invariant simpleInvariant()
    currentContract.storageResult > 10;

rule callWithUnsuedParameter() {
    uint x;
    uint y;
    env e;
    uint z = unusedParameter(e, x, y);
    assert z == x + y;
}
