use cvlr::prelude::*;

#[rule]
pub fn rule_option_type_none() {
    let option_type_input: Option<u64> = nondet();
    cvlr_assert!(option_type_input.is_none());
}

#[rule]
pub fn rule_option_type_some() {
    let option_type_input: Option<u64> = create_some_of_2();
    cvlr_assert!(option_type_input.is_some() && option_type_input.unwrap() != 2);
}

#[inline(never)]
fn create_some_of_2() -> Option<u64> {
    return Some(2);
}