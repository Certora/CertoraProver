use cvlr::{nondet::Nondet, prelude::*};

use crate::certora::spec::structs::SubStruct;

#[rule]
pub fn rule_log_printing() {
    let nondet = u8::nondet();
    clog!(nondet);
    clog!("print value with tag", nondet);
    let other = some_arbitrary_function();
    clog!("other in rule", other);
    let substruct = SubStruct{
        fixed_value_1: u8::nondet(),
        nondet_value: 1,
    };
    clog!(substruct);
    cvlr_assert!(other == nondet && substruct.fixed_value_1 == 2);
}

#[inline(never)]
fn some_arbitrary_function() -> u8 {
    let nondet_some_arbitrary_func = u8::nondet();
    clog!(nondet_some_arbitrary_func);
    return nondet_some_arbitrary_func
}