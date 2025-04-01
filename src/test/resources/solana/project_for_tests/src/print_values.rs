use super::intrinsics::*;


#[no_mangle]
pub fn rule_print_values() {
    unsafe { CVT_calltrace_print_u64_1("tag1", 1); }
    unsafe { CVT_assert(false) }
}

#[no_mangle]
pub fn rule_print_values_nested_call() {
    print_values();
    unsafe { CVT_assert(false) }
}

fn print_values() {
    let x: u64 = unsafe { CVT_nondet_u64() };
    unsafe { CVT_calltrace_print_u64_2("nested_tag", x, x); }
}

#[no_mangle]
pub fn rule_print_values_in_match() {
    match unsafe { CVT_nondet_u8() } {
        0 => {
            unsafe { CVT_calltrace_print_u64_3("tag_in_match", 1, 2, 3); }
            unsafe { CVT_assert(false) }
        }
        _ => {}
    }
}
