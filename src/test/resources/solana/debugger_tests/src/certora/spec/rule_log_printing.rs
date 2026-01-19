use cvlr::{nondet::Nondet, prelude::*};

#[rule]
pub fn rule_log_printing() {
    let nondet = u8::nondet();
    clog!(nondet);
    clog!("print value with tag", nondet);
    let other = some_arbitrary_function();
    clog!("other in rule", other);
    cvlr_assert!(other == nondet);
}

#[inline(never)]
fn some_arbitrary_function() -> u8 {
    let nondet_some_arbitrary_func = u8::nondet();
    clog!(nondet_some_arbitrary_func);
    return nondet_some_arbitrary_func
}