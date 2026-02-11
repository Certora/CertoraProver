using Test as Test;

rule r(method f, env e, calldataarg args) {
    require Test.x == 0;
    f(e, args);
    assert Test.x == 5;
}
