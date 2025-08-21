function min(uint256 x, uint256 y) returns uint256 {
    if (x < y) {
        return x;
    } else {
        return y;
    }
}

function min_fallthrough(uint256 x, uint256 y) returns uint256 {
    if (x < y) {
        return x;
    }
    assert x >= y;
    return y;
}

function weird(uint256 x, uint256 y, uint256 z) returns uint256 {
    // returns x if it is max, otherwise returns min
    // this should test if somehow the return from this function gets wired
    // into the return of the call to min
    if (x > y && x > z) {
        return x;
    }
    uint256 min_1 = min(x, y);
    uint256 min_2 = min(y, z);
    return min(min_1, min_2);
}

function doIt(uint a, uint b) returns mathint {
    mathint ret;
    require ret == a + b;
    mathint needful;
    needful = a;
    needful = b;
    return ret;
}

ghost uint theVariable;
function yeetage() {
    // make sure globals compiled right here;
    assert theVariable == 5;
}

definition five() returns uint256 = 5;

function not(bool x) {
    if (x) {
        assert false;
    } else {
        assert true;
    }
}

function boolean() returns bool {
    mathint m;
    if (m == 0) {
        return true;
    }
    return false;
}

// we are checking that the call to five() gets
// correctly inlined, specifically as a subexpression
// to an argument to a CVL Function call
rule definitionNestedInArg {
    uint x;
    uint y;
    require x <= y && x > 5;
    not(x > y && y == five());
    assert true;
}

rule yeet {
    require theVariable == 5;
    yeetage();
    assert true;
}

rule callsCollide {
    doIt(5, 10);
    doIt(15, 20);
    assert false;
}

// testing a potential bug should the compiler give the out variable of two calls to the same function
// the same name
rule returnsCouldCollide(uint a1, uint b1, uint a2, uint b2) {
    require doIt(a1, b1) == 5;
    require doIt(a2, b2) == 10;
    assert false;
}

rule test_min(uint256 x, uint256 y) {
    require x < y;
    assert min(x, y) == x;
    uint256 z = min(y, x);
    assert z == x;
}

rule test_min_fallthrough(uint256 x, uint256 y) {
    require x < y;
    assert min_fallthrough(x, y) == x;
    uint256 z = min_fallthrough(y, x);
    assert z == x;
}

rule test_min_3(uint256 x, uint256 y, uint256 z) {
    require x > y && y > z;
    assert weird(x, y, z) == x;
}

rule test_ignore_return(uint256 x, uint256 y) {
    min_fallthrough(x, y);
    assert true;
}

function call_method_return_reverted(method f) returns bool {
    env e; calldataarg args;
    require e.msg.value == 0;
    // generated struct arg assigments don't have move semantics...
    f@withrevert(e, args);
    return lastReverted;
}

rule doesRevert(method f) {
    assert !call_method_return_reverted(f);
}

rule functionInTernaryExp {
    assert boolean() ? false : true;
}
