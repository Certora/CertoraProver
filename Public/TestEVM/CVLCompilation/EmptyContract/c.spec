invariant falseInv()   false;

rule falseRule(method f) {
    calldataarg arg;
    env e;
    f(e,arg);
    assert false;
    }

invariant falseInvFiltered()
    false
    filtered {
        f -> f.selector == 1
    }

rule falseRuleFiltered(method f)
       filtered { f -> false }
    {
    calldataarg arg;
    env e;
    f(e,arg);
    assert false;
    }
