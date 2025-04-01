//! This module contains functions that will be called from other modules for test purposes.
use super::intrinsics::*;

pub fn assert_false() {
    unsafe {
        CVT_assert(CVT_nondet_u64() < 10);
    }
}

pub fn print_location() {
    print_location!()
}

pub fn assert_false_with_attach_location() {
    cvt_assert_with_location!(false);
}

pub fn print_tag_with_attach_location() {
    cvt_print_tag_with_location!("tag_from_functionality_1")
}

pub fn print_value_with_attach_location() {
    cvt_print_value_with_location!("tag_from_functionality_2", 2)
}

pub fn get_nondet_u32() -> u32 {
    unsafe { CVT_nondet_u32() }
}


pub fn get_nondet_u64_with_attach_location() -> u64 {
    cvt_nondet_u64!()
}

pub fn satisfy_x_greater_than_10(x: u64) {
    cvt_satisfy_with_location!(x > 10);
}

pub fn print_u64_as_fixed(num: u64, bits: u64) {
    cvt_print_u64_as_fixed!("tag", num, bits);
}
