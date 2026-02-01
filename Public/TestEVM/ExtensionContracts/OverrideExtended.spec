using Extended as Extended;

rule overriddenReplaced {
    env e;
    assert Extended.foo(e) == "Extender.foo";
}

rule notOverriddenNotReplaced {
    env e;
    uint u;
    assert Extended.foo(e, u) == "Extended.foo";
}

use builtin rule sanity;
