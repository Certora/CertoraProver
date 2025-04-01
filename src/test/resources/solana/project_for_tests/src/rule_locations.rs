use super::intrinsics::*;

#[no_mangle]
fn rule_passing_with_location() {
    unsafe {
        CVT_rule_location(std::file!(), std::line!() - 2);
        let x = CVT_nondet_i64();
        CVT_assume(x < 10);
        CVT_assert(x < 10);
    };
}

#[no_mangle]
fn rule_failing_with_location() {
    unsafe {
        CVT_rule_location(std::file!(), std::line!() - 2);
        CVT_assert(false);
    };
}
