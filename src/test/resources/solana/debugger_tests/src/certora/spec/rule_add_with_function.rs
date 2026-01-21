use cvlr::prelude::*;

#[rule]
pub fn rule_add_with_function() {
    let input_a = nondet();
    let input_b = nondet();
    let faulty_add_result = faulty_add(input_a, input_b);
    cvlr_assert_eq!(faulty_add_result, input_a + input_b + 1);
}

fn faulty_add(faulty_add_param1: u64, faulty_add_param2: u64) -> u64 {
    let res = faulty_add_param1 + faulty_add_param2;
    return res;
}
