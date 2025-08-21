using D as d;

methods {
    unresolved external in _._ => DISPATCH(use_fallback=true) [
        D._
    ] default NONDET;
}

rule dispatchListInlinesFallback {
    env e;
    require e.msg.value == 0;
    bytes b;

    mathint old_u = d._u;
    foo@withrevert(e, b);
    satisfy !lastReverted && to_mathint(d._u) == old_u + 5;
}

rule dispatchListInlinesFoo {
    env e;
    require e.msg.value == 0;
    bytes b;

    mathint old_u = d._u;
    foo@withrevert(e, b);
    satisfy !lastReverted && to_mathint(d._u) == old_u + 7;
}
