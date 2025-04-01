use super::intrinsics::*;

#[no_mangle]
pub fn rule_vacuity_test_expect_sanity_failure() {
    unsafe {
        let amount: u64 = CVT_nondet_u64();
        CVT_assume(amount >= 10);
        CVT_assume(amount <= 9);
        CVT_assert(amount == 10); // Expect a sanity failure here as the assumes are conflicting.
    }
    // Sanity adds cvlr_assert!(false) here;
}

#[no_mangle]
pub fn rule_vacuity_test_expect_sanity_success() {
    unsafe {
        let amount: u64 = CVT_nondet_u64();
        CVT_assume(amount >= 10);
        CVT_assume(amount <= 10);
        CVT_assert(amount == 10); // Expect no sanity failure here as this assert is reachable.
    }
    // Sanity adds cvlr_assert!(false) here;
}

#[no_mangle]
pub fn rule_multi_assert() {
    unsafe {
        let x: u64 = CVT_nondet_u64();
        CVT_assert(x < 20);
        let y: u64 = CVT_nondet_u64();
        CVT_assert(y < 20);
    }
}
