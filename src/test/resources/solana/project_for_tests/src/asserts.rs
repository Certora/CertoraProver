use super::intrinsics::*;

#[no_mangle]
pub fn rule_failing_assert() {
    unsafe { CVT_assert(false) }
}

#[no_mangle]
pub fn rule_failing_assert_nested_call() {
    let x: u64 = unsafe { CVT_nondet_u64() };
    let y: u64 = unsafe { CVT_nondet_u64() };
    assert_x_greater_y(x, y);
}

fn assert_x_greater_y(x: u64, y: u64) {
    unsafe { CVT_assert(x > y) };
}

#[no_mangle]
pub fn rule_failing_assert_in_branch() {
    let x: u64 = unsafe { CVT_nondet_u64() };
    if x > unsafe { CVT_nondet_u64() } {
        if x < unsafe { CVT_nondet_u64() } {
            unsafe { CVT_assert(false) }
        }
    }
}

#[no_mangle]
pub fn rule_failing_assert_in_other_module() {
    super::functionality::assert_false();
}

#[no_mangle]
pub fn rule_failing_assert_column_1() {
    unsafe {
CVT_assert(CVT_nondet_u64() > 1)  // This is purposefully unformatted to have a test case with an assert that starts at the first column
    };
}
