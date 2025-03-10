use super::intrinsics::*;

#[no_mangle]
pub fn rule_nondet_main_body() {
    let i = unsafe { CVT_nondet_u64() };
    unsafe { CVT_assert(i < 10) }
}

#[no_mangle]
pub fn rule_nondet_nested_call() {
    let _i = get_nondet_i64();
    unsafe { CVT_assert(false) }
}

pub fn get_nondet_i64() -> i64 {
    let i = unsafe { CVT_nondet_i64() };
    return i;
}

#[no_mangle]
pub fn rule_nondet_other_module() {
    let _i = super::functionality::get_nondet_u32();
    unsafe { CVT_assert(false) }
}
