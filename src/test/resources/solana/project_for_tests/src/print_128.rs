use super::intrinsics::*;

#[no_mangle]
pub fn rule_print_u128() {
    cvt_print_u128!("zero", 0);
    cvt_print_u128!("one", 1);
    cvt_print_u128!("max_u64", u64::MAX as u128);
    cvt_print_u128!("max_u64_plus_one", (u64::MAX as u128) + 1);
    cvt_print_u128!("max_u128", u128::MAX);
    unsafe { CVT_assert(false) };
}

#[no_mangle]
pub fn rule_print_i128() {
    cvt_print_i128!("zero", 0);
    cvt_print_i128!("one", 1);
    cvt_print_i128!("minus_one", -1);
    cvt_print_i128!("max_i64", i64::MAX as i128);
    cvt_print_i128!("min_i64", i64::MIN as i128);
    cvt_print_i128!("max_i64_plus_one", (i64::MAX as i128) + 1);
    cvt_print_i128!("min_i64_plus_one", (i64::MIN as i128) - 1);
    cvt_print_i128!("max_i128", i128::MAX);
    cvt_print_i128!("min_i128", i128::MIN);
    unsafe { CVT_assert(false) };
}
