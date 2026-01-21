use cvlr::log::CvlrLog;
use cvlr::{nondet::Nondet, prelude::*};
use cvlr::log::cvlr_log_with;
use crate::certora::spec::structs::ParentStruct;
use crate::certora::spec::structs::SubStruct;

#[rule]
pub fn rule_reference_type() {
    let substruct = SubStruct{
        fixed_value_1: 1,
        nondet_value: nondet()
    };
    let struct1: ParentStruct<'_> = ParentStruct { substruct: &substruct, fixed_value_2: 2 };
    let create_same_clone = create_clone(&substruct);
    clog!(struct1);
    clog!(create_same_clone);
    cvlr_assert!(struct1 != create_same_clone);
}

#[inline(never)]
fn create_clone<'info>(substruct: &'info SubStruct) -> ParentStruct<'info> {
    let cloned_struct: ParentStruct<'info> = ParentStruct { substruct: substruct, fixed_value_2: 2 };
    cloned_struct
}