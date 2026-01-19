use cvlr::log::CvlrLog;
use cvlr::{nondet::Nondet, prelude::*};
use cvlr::log::cvlr_log_with;
use crate::certora::spec::structs::{ParentStruct, SubStruct};

#[rule]
pub fn rule_mutability() {
    let substruct = create_struct();
    clog!(substruct);

    let mut parent_struct = ParentStruct{
        substruct: &substruct,
        fixed_value_2: 2
    };
    clog!(parent_struct);
    // First the value is set to 2, so we don't fail here.
    cvlr_assert!(parent_struct.fixed_value_2 == 2);
    // Update the value to 3 so that the next assert fails.
    mutate_substruct(&mut parent_struct);
    clog!(parent_struct);
    cvlr_assert!(parent_struct.fixed_value_2 == 2);
}

#[inline(never)]
fn mutate_substruct(parent_struct: &mut ParentStruct){
    parent_struct.fixed_value_2 = 3;
}

#[inline(never)]
fn create_struct() -> SubStruct{
    return SubStruct{
        fixed_value_1: 1,
        nondet_value: 7,
    };
}