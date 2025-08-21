rule contradictingFilters(method f)
        filtered { f -> f.selector == sig:addMoney(uint).selector
                     && f.selector == sig:decMoney(uint).selector }
    {
        env e;
        calldataarg args;
        f(e, args);
        assert false;
    }

rule twoMethodsFilters02(method f, method g)
        filtered { g -> g.selector == sig:addMoney(uint).selector
            && g.selector == sig:decMoney(uint).selector }
    {
        env e;
        calldataarg args;
        f(e, args);
        g(e,args);
        assert false;
    }


rule twoMethodsFilters20(method f, method g)
        filtered { f -> f.selector == sig:addMoney(uint).selector
                     && f.selector == sig:decMoney(uint).selector}
    {
        env e;
        calldataarg args;
        f(e, args);
        g(e,args);
        assert false;
    }

rule twoMethodsFilters21(method f, method g)
        filtered { f -> f.selector == sig:addMoney(uint).selector
                     && f.selector == sig:decMoney(uint).selector,
                     g -> g.selector == sig:addMoney(uint).selector }
    {
        env e;
        calldataarg args;
        f(e, args);
        g(e,args);
        assert false;
    }

rule twoMethodsFilters22(method f, method g)
        filtered { f -> f.selector == sig:addMoney(uint).selector
                     && f.selector == sig:decMoney(uint).selector,
                     g -> g.selector == sig:addMoney(uint).selector
                     && g.selector == sig:decMoney(uint).selector }
    {
        env e;
        calldataarg args;
        f(e, args);
        g(e,args);
        assert false;
    }

invariant contradictingInvariant() true filtered { f -> f.selector == sig:addMoney(uint).selector
                     && f.selector == sig:decMoney(uint).selector }

invariant explicitPreservedInvariant() true {
        preserved addMoney(uint i) with (env e) {
            require i>=0;
        }
        preserved decMoney(uint i) with (env e) {
            require i>=0;
        }
    }
