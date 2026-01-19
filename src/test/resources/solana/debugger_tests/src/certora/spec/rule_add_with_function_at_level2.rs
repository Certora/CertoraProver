use cvlr::prelude::*;

#[rule]
pub fn rule_add_with_function_at_level2() {
    let input_a = nondet();
    let input_b = nondet();
    let faulty_add_result = faulty_add_at_level2(input_a, input_b);
    cvlr_assert_eq!(faulty_add_result, input_a + input_b);
}

#[inline(never)]
fn faulty_add_at_level2(faulty_add_param1: u64, faulty_add_param2: u64) -> u64 {
    if faulty_add_param1 > 100 {
        return foo(faulty_add_param1, faulty_add_param2);
    } else {
        return bar(faulty_add_param1, faulty_add_param2);
    }
}

fn foo(bar_param1: u64, bar_param2: u64) -> u64 {
    return bar_param1 - bar_param2;
}

fn bar(bar_param1: u64, bar_param2: u64) -> u64 {
    return bar_param1 + bar_param2;
}
