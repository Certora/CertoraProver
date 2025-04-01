// use calltrace::cvt_cex_print_tag;
use super::intrinsics::*;

#[no_mangle]
pub fn rule_print_tag() {
    unsafe { CVT_calltrace_print_tag("tag1"); }
    unsafe { CVT_assert(false); }
}

#[no_mangle]
pub fn rule_print_tag_nested_call() {
    print_tag_fn();
    unsafe { CVT_assert(false); }
}

fn print_tag_fn() {
    unsafe { CVT_calltrace_print_tag("tag2"); }
}
