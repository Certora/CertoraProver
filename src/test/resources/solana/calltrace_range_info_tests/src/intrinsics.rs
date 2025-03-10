//! This module defines and exposes all the intrinsics functions that are
//! interesting for testing purposes.

pub mod rt_decls {
    #[allow(improper_ctypes)]
    extern "C" {
        pub fn CVT_assume(_c: bool);
        pub fn CVT_assert(_c: bool);
        pub fn CVT_satisfy(_c: bool);
        pub fn CVT_sanity(_c: bool);

        pub fn CVT_calltrace_print_tag(tag: &str);
        pub fn CVT_calltrace_print_u64_1(tag: &str, x: u64);
        pub fn CVT_calltrace_print_u64_2(tag: &str, x: u64, y: u64);
        pub fn CVT_calltrace_print_u64_3(tag: &str, x: u64, y: u64, z: u64);
        pub fn CVT_calltrace_print_u64_as_fixed(tag: &str, num: u64, bits: u64);
        pub fn CVT_calltrace_print_i64_1(tag: &str, x: i64);
        pub fn CVT_calltrace_print_i64_2(tag: &str, x: i64, y: i64);
        pub fn CVT_calltrace_print_i64_3(tag: &str, x: i64, y: i64, z: i64);
        pub fn CVT_calltrace_print_string(tag: &str, v: &str);
        pub fn CVT_calltrace_print_location(filepath: &str, line: u32);
        pub fn CVT_calltrace_attach_location(filepath: &str, line: u32);

        pub fn CVT_nondet_u8() -> u8;
        pub fn CVT_nondet_u16() -> u16;
        pub fn CVT_nondet_u32() -> u32;
        pub fn CVT_nondet_u64() -> u64;
        pub fn CVT_nondet_small_u128() -> u128;
        pub fn CVT_nondet_u128() -> u128;
        pub fn CVT_nondet_usize() -> usize;
        pub fn CVT_nondet_i8() -> i8;
        pub fn CVT_nondet_i16() -> i16;
        pub fn CVT_nondet_i32() -> i32;
        pub fn CVT_nondet_i64() -> i64;
        pub fn CVT_nondet_small_i128() -> i128;
        pub fn CVT_nondet_i128() -> i128;
    }
}
pub use rt_decls::*;

#[macro_export]
macro_rules! print_location {
    () => {
        unsafe {
            $crate::intrinsics::CVT_calltrace_print_location(std::file!(), std::line!());
        }
    };
}
pub use print_location;

#[macro_export]
macro_rules! cvt_assert_with_location {
    ($cond:expr) => {
        unsafe {
            $crate::intrinsics::CVT_calltrace_attach_location(std::file!(), std::line!());
            $crate::intrinsics::CVT_assert($cond);
        }
    };
}
pub use cvt_assert_with_location;

#[macro_export]
macro_rules! cvt_print_tag_with_location {
    ($tag:expr) => {
        unsafe {
            $crate::intrinsics::CVT_calltrace_attach_location(std::file!(), std::line!());
            $crate::intrinsics::CVT_calltrace_print_tag($tag);
        }
    };
}
pub use cvt_print_tag_with_location;

#[macro_export]
macro_rules! cvt_print_value_with_location {
    ($tag:expr, $value:expr) => {
        unsafe {
            $crate::intrinsics::CVT_calltrace_attach_location(std::file!(), std::line!());
            $crate::intrinsics::CVT_calltrace_print_u64_1($tag, $value);
        }
    };
}
pub use cvt_print_value_with_location;

#[macro_export]
macro_rules! cvt_nondet_u64 {
    () => {
        unsafe {
            $crate::intrinsics::CVT_calltrace_attach_location(std::file!(), std::line!());
            $crate::intrinsics::CVT_nondet_u64()
        }
    };
}
pub use cvt_nondet_u64;

#[macro_export]
macro_rules! cvt_satisfy_with_location {
    ($cond:expr) => {
        unsafe {
            let _x = $cond;
            $crate::intrinsics::CVT_calltrace_attach_location(std::file!(), std::line!());
            $crate::intrinsics::CVT_satisfy(_x);
        }
    };
}
pub use cvt_satisfy_with_location;

#[macro_export]
macro_rules! cvt_print_u64_as_fixed {
    ($tag:expr, $num:expr, $bits:expr) => {
        unsafe {
            let _tag = $tag;
            let _num = $num;
            let _bits = $bits;
            $crate::intrinsics::CVT_calltrace_attach_location(std::file!(), std::line!());
            $crate::intrinsics::CVT_calltrace_print_u64_as_fixed(_tag, _num, _bits);
        }
    };
}
pub use cvt_print_u64_as_fixed;
