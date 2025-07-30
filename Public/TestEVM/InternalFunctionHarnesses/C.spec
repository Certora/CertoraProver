using D as d;

use builtin rule sanity; // To verify the harness functions aren't included in this parametric rule

rule r(env e) {
    C.S s;
    bool b;
    address a;

    _privateFunc(e);
    _noImplementation(e, s);
    assert _withImplementation(e, a) != 0;

    noHarness(e);

    assert overloaded(e);
    assert overloaded(e, b) == b;

    d._onlyD(e);
    assert d._withImplementation(e, a) == 0;
}
