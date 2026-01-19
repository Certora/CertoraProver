use cvlr::prelude::*;

#[rule]
pub fn rule_simple_recursion() {
    let input_a: u64 = nondet();
    let input_b: u64 = nondet();
    let faulty_add_result = basic_add_recursion(input_a, input_b);
    cvlr_assert_eq!(faulty_add_result, input_a + input_b);
}

fn basic_add_recursion(basic_add_recursion_param1: u64, basic_add_recursion_param2: u64) -> u64 {
    let mut i = 0;
    let mut res_basic_add_recursion = basic_add_recursion_param1 + basic_add_recursion_param2;
    while i < basic_add_recursion_param2 {
        if basic_add_recursion_param1 % 10 == 0 {
            res_basic_add_recursion =
                basic_add_recursion(basic_add_recursion_param2, basic_add_recursion_param1) + 10;
        }

        i += 1;
    }
    return res_basic_add_recursion;
}
