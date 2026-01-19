use cvlr::prelude::*;

#[rule]
pub fn rule_basic_add_inline_never() {
    let input_a: u64 = nondet();
    let input_b: u64 = nondet();
    let input_c: u64 = nondet();
    let faulty_add_result = basic_add_inline_never(input_a, input_b, input_c);
    clog!(input_a, input_b, faulty_add_result);
    cvlr_assert_eq!(faulty_add_result, input_a + input_b);
}

#[inline(never)]
fn basic_add_inline_never(
    basic_add_param1: u64,
    basic_add_param2: u64,
    basic_add_param3: u64,
) -> u64 {
    let mut i = 0;
    let mut res = 0;
    while i < basic_add_param3 {
        if i % 2 == 0 {
            res = do_multiple_inline_never(basic_add_param1, basic_add_param2) + 10;
        } else {
            res = do_multiple_inline_never(60, basic_add_param1) + 10;
        }
        if basic_add_param3 % 10 == 0 {
            res = basic_add_inline_never(basic_add_param3, basic_add_param2, basic_add_param1) + 10;
        }

        i += 1;
    }
    return res;
}

#[inline(never)]
fn do_multiple_inline_never(do_multiple_param1: u64, do_multiple_param2: u64) -> u64 {
    let mut i = 0;
    let mut res = 0;
    while i < do_multiple_param1 {
        res = do_multiple_param1 * do_multiple_param2;
        i += 1;
    }
    return res;
}
