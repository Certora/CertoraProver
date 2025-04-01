use super::intrinsics::*;

#[no_mangle]
pub fn rule_print_location_main_body() {
    print_location!();
    unsafe { CVT_assert(false); }
}

#[no_mangle]
pub fn rule_print_location_nested_call() {
    print_location_in_function();
    unsafe { CVT_assert(false); }
}

fn print_location_in_function() {
    print_location!();
}

#[no_mangle]
pub fn rule_print_location_in_branch() {
    if true {
        print_location!();
    } else {
        print_location!();
    }
    unsafe { CVT_assert(false); }
}

#[no_mangle]
pub fn rule_print_location_in_other_module() {
    super::functionality::print_location();
    unsafe { CVT_assert(false); }
}
