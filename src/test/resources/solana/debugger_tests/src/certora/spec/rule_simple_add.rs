use cvlr::prelude::*;

#[rule]
pub fn rule_simple_add() {
    let input_a: u64 = nondet();
    let input_b: u64 = nondet();
    let faulty_add_result = input_a - input_b;
    cvlr_assert_eq!(faulty_add_result, input_a + input_b + 1);
}
