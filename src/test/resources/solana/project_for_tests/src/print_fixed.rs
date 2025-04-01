use super::intrinsics::*;

#[no_mangle]
pub fn rule_print_u64_as_fixed_main_body() {
    cvt_print_u64_as_fixed!("i", 0x4235, 14);
    unsafe { CVT_assert(false) };
}

#[no_mangle]
pub fn rule_print_u64_as_fixed_nested_call() {
    print_u64_as_fixed();
    unsafe { CVT_assert(false) };
}

fn print_u64_as_fixed() {
    cvt_print_u64_as_fixed!("tag", 0x4000, 14);
}

#[no_mangle]
fn rule_print_u64_as_fixed_other_module() {
    let num: u64 = 3;
    let bits: u64 = 1;
    super::functionality::print_u64_as_fixed(num, bits);
    unsafe { CVT_assert(false) };
}
