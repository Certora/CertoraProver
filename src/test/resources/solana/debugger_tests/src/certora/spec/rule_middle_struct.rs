
use cvlr::prelude::*;
use crate::certora::spec::structs::{MiddleStruct};
#[rule]
pub fn rule_middle_struct() {
    let input_a: MiddleStruct = nondet();
    let input_b: MiddleStruct = derive_middle_struct(&input_a);
    cvlr_assert!(input_a.field1 == input_b.field1 &&
        input_a.field2 == input_b.field2 &&
        input_a.field3 != input_b.field3 // fails only here!
    );
}

#[inline(never)]
fn derive_middle_struct(parameter_variable: &MiddleStruct) -> MiddleStruct{
    MiddleStruct{
        field3: nondet(),
        ..parameter_variable.clone()
    }
}