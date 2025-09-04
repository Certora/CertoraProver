rule r(method f, env e, calldataarg args) {
    f(e, args);
    satisfy true;
}

invariant i() 5 >= 0;
